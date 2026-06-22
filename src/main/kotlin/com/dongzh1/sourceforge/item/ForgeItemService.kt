package com.dongzh1.sourceforge.item

import com.dongzh1.sourceforge.SourceForge
import com.dongzh1.sourceforge.config.AffixConfig
import com.dongzh1.sourceforge.config.AffixRollConfig
import com.dongzh1.sourceforge.config.EquipmentConfig
import com.dongzh1.sourceforge.config.ForgeConfig
import com.dongzh1.sourceforge.util.color
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.attribute.Attribute
import org.bukkit.attribute.AttributeModifier
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.inventory.EquipmentSlotGroup
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.persistence.PersistentDataContainer
import java.text.DecimalFormat
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

class ForgeItemService(
    private val plugin: SourceForge,
    private val config: ForgeConfig
) {
    private val typeKey = NamespacedKey(plugin, "type")
    private val categoryKey = NamespacedKey(plugin, "weapon_category")
    private val tierKey = NamespacedKey(plugin, "tier")
    private val affixesKey = NamespacedKey(plugin, "affixes")
    private val projectileMarkerKey = NamespacedKey(plugin, "projectile_source")
    private val scoreKey = NamespacedKey(plugin, "score")
    private val chunkWorldLevelKey = NamespacedKey("chunkworld", "level")
    private val pixelShopPriceKey = NamespacedKey("pixelshop", "price")

    /** 预建 affixId -> NamespacedKey，避免热路径反复构造（构造会做正则校验）。 */
    private val affixKeys: Map<String, NamespacedKey> =
        config.affixes.values.associate { it.id to NamespacedKey(plugin, it.pdcKey) }

    /** affixId -> mod_delta_<pdcKey>，MOD 系统额外加成层；与基础词条相加。 */
    private val modDeltaKeys: Map<String, NamespacedKey> =
        config.affixes.values.associate { it.id to NamespacedKey(plugin, "mod_delta_${it.pdcKey}") }

    /**
     * 每玩家全身词条总和缓存；装备变化时由监听器失效，避免每次战斗/回盾都扫全背包。
     * 同时带一个很短的 TTL 作为兜底：即使某条装备变更路径（命令塞装备、漏斗/发射器装甲、
     * 重生重装等）漏掉了显式失效，陈旧读取也会在 ~250ms 内自愈，不会让 ability_efficiency
     * 之类词条长时间读到 0。
     */
    private val statCache = ConcurrentHashMap<UUID, CachedStats>()

    private class CachedStats(val totals: Map<String, Double>, val expireAt: Long)

    private fun affixKey(affix: AffixConfig): NamespacedKey =
        affixKeys[affix.id] ?: NamespacedKey(plugin, affix.pdcKey)


    fun createEquipment(
        equipment: EquipmentConfig,
        tier: Int,
        maxAffixes: Int
    ): ItemStack {
        val base = equipment.ceId?.let { CraftEngineHook.build(it, 1) } ?: ItemStack(equipment.material, 1)
        val selected = rollAffixes(equipment, tier, maxAffixes)
        writeEquipment(base, equipment, tier, selected)
        return base
    }

    fun createDirectEquipment(equipmentId: String, tier: Int, affixes: Int? = null): ItemStack? {
        val equipment = config.equipment[equipmentId] ?: return null
        val normalizedTier = tier.coerceAtLeast(1)
        val maxAffixes = affixes ?: maxOf(1, equipment.tierAffixes[normalizedTier]?.size ?: 1)
        return createEquipment(equipment, normalizedTier, maxAffixes.coerceAtLeast(0))
    }

    fun rerollEquipment(item: ItemStack): Boolean {
        val equipment = config.equipment[weaponType(item)] ?: return false
        val tier = equipmentTier(item)
        val maxAffixes = maxOf(1, readAffixIds(item).size)
        val selected = rollAffixes(equipment, tier, maxAffixes)
        clearAffixes(item)
        writeEquipment(item, equipment, tier, selected)
        plugin.modService.reapplyModEffects(item)
        return true
    }

    fun upgradeEquipment(item: ItemStack): Boolean {
        val equipment = config.equipment[weaponType(item)] ?: return false
        val maxTier = (equipment.tierAffixes.keys.maxOrNull() ?: 5).coerceAtLeast(1)
        val current = equipmentTier(item)
        if (current >= maxTier) return false
        val nextTier = current + 1
        // 升级保留玩家已 roll 出的词条组成（build 不被洗掉），仅把数值提升到新 tier，且永不降低。
        // 若是无词条的旧物品（理论上不会发生），回退到按新 tier 重新 roll。
        val preserved = upgradeExistingAffixes(item, equipment, nextTier)
        val selected = if (preserved.isNotEmpty()) {
            preserved
        } else {
            val maxAffixes = maxOf(1, readAffixIds(item).size)
            rollAffixes(equipment, nextTier, maxAffixes)
        }
        clearAffixes(item)
        writeEquipment(item, equipment, nextTier, selected)
        plugin.modService.reapplyModEffects(item)
        return true
    }

    /**
     * 读取装备现有词条，按新 tier 提升其数值。保留词条组成不变；
     * 每条数值取 max(原值, 新 tier 的一次 roll)，保证升级只增不减，不会洗掉玩家辛苦 roll 的好词条。
     */
    private fun upgradeExistingAffixes(
        item: ItemStack,
        equipment: EquipmentConfig,
        nextTier: Int
    ): List<Pair<AffixConfig, Double>> {
        val tierRolls = equipment.tierAffixes[nextTier]
            ?: equipment.tierAffixes.filterKeys { it <= nextTier }.maxByOrNull { it.key }?.value
            ?: emptyList()
        return readAffixIds(item).mapNotNull { id ->
            val affix = config.affixes[id] ?: return@mapNotNull null
            val currentValue = readAffixValue(item, id)
            val roll = tierRolls.firstOrNull { it.affixId == id }
            val candidate = if (roll != null) randomValue(roll, affix) * affix.scale else currentValue
            affix to maxOf(currentValue, candidate)
        }
    }

    private fun writeEquipment(
        item: ItemStack,
        equipment: EquipmentConfig,
        tier: Int,
        selected: List<Pair<AffixConfig, Double>>
    ) {
        val meta = item.itemMeta
        meta.setDisplayName(color("&f${equipment.displayName}"))
        meta.isUnbreakable = true
        meta.addItemFlags(ItemFlag.HIDE_UNBREAKABLE)

        // Attack damage: write base_damage to ATTACK_DAMAGE
        meta.removeAttributeModifier(Attribute.ATTACK_DAMAGE)
        val baseDamage = selected.sumOf { (affix, value) ->
            if (affix.combat == "base_damage") value * affix.scale else 0.0
        }
        if (baseDamage > 0.0 && usesEquippedSlot(equipment)) {
            meta.addAttributeModifier(
                Attribute.ATTACK_DAMAGE,
                AttributeModifier(
                    NamespacedKey(plugin, "attack_damage_${equipment.id.lowercase()}"),
                    baseDamage,
                    AttributeModifier.Operation.ADD_NUMBER,
                    equipmentSlotGroup(equipment)
                )
            )
        }

        // Attack speed
        meta.removeAttributeModifier(Attribute.ATTACK_SPEED)
        attackSpeedModifier(equipment)?.takeIf { usesEquippedSlot(equipment) }?.let { modifier ->
            meta.addAttributeModifier(
                Attribute.ATTACK_SPEED,
                AttributeModifier(
                    NamespacedKey(plugin, "attack_speed_${equipment.id.lowercase()}"),
                    modifier,
                    AttributeModifier.Operation.ADD_NUMBER,
                    equipmentSlotGroup(equipment)
                )
            )
        }

        // Armor: write armor affix to ARMOR
        meta.removeAttributeModifier(Attribute.ARMOR)
        val armorValue = selected.sumOf { (affix, value) ->
            if (affix.combat == "armor") value * affix.scale else 0.0
        }
        if (armorValue > 0.0 && usesEquippedSlot(equipment)) {
            meta.addAttributeModifier(
                Attribute.ARMOR,
                AttributeModifier(
                    NamespacedKey(plugin, "armor_${equipment.id.lowercase()}"),
                    armorValue,
                    AttributeModifier.Operation.ADD_NUMBER,
                    equipmentSlotGroup(equipment)
                )
            )
        }

        // Health: write health affix to MAX_HEALTH
        meta.removeAttributeModifier(Attribute.MAX_HEALTH)
        val healthValue = selected.sumOf { (affix, value) ->
            if (affix.combat == "health" || affix.combat == "shield_capacity") value * affix.scale else 0.0
        }
        if (healthValue > 0.0 && usesEquippedSlot(equipment)) {
            meta.addAttributeModifier(
                Attribute.MAX_HEALTH,
                AttributeModifier(
                    NamespacedKey(plugin, "health_${equipment.id.lowercase()}"),
                    healthValue,
                    AttributeModifier.Operation.ADD_NUMBER,
                    equipmentSlotGroup(equipment)
                )
            )
        }

        val lore = mutableListOf<String>()
        lore += equipment.baseLore.map { it.replace("%tier%", tier.toString()) }
        if (lore.isNotEmpty()) lore += ""
        lore += "&7等级: &e$tier"

        val pdc = meta.persistentDataContainer
        pdc.set(typeKey, PersistentDataType.STRING, equipment.id)
        pdc.set(categoryKey, PersistentDataType.STRING, equipment.weaponCategory)
        pdc.set(tierKey, PersistentDataType.INTEGER, tier)
        pdc.set(chunkWorldLevelKey, PersistentDataType.INTEGER, chunkWorldLevel(equipment, tier))

        val affixIds = mutableListOf<String>()
        for ((affix, value) in selected) {
            affixIds += affix.id
            writeAffixValue(pdc, affix, value)
        }
        pdc.set(affixesKey, PersistentDataType.STRING, affixIds.joinToString(","))
        pdc.set(
            NamespacedKey(plugin, "mod_capacity"),
            PersistentDataType.INTEGER,
            config.modCapacity.computeCapacity(equipment.weaponCategory, tier)
        )
        val score = calculateScore(equipment, tier, selected)
        val price = calculatePrice(equipment, score)
        pdc.set(scoreKey, PersistentDataType.INTEGER, score)
        if (price > 0.0) {
            pdc.set(pixelShopPriceKey, PersistentDataType.DOUBLE, price)
        }
        lore += "&7评分: &b$score"
        if (price > 0.0) {
            lore += "&7价格: &6${format(price, 1)}"
        }
        if (selected.isNotEmpty()) {
            lore += ""
            lore += "&7加成"
            selected.forEach { (affix, value) ->
                lore += "&f${affix.displayName} &7+${format(value, affix.decimals)}"
            }
        }

        meta.lore = color(lore)
        item.itemMeta = meta
    }

    private fun calculateScore(
        equipment: EquipmentConfig,
        tier: Int,
        selected: List<Pair<AffixConfig, Double>>
    ): Int {
        val scoreConfig = config.score
        val base = tier.coerceAtLeast(1) * scoreConfig.basePerTier
        val affixScore = selected.sumOf { (affix, value) ->
            scoreAffix(affix.id, affix.combat, value)
        }
        return (base + affixScore).toInt().coerceAtLeast(scoreConfig.minScore)
    }

    private fun scoreAffix(affixId: String, combat: String, value: Double): Double {
        val scoreConfig = config.score
        val weight = scoreConfig.affixWeights[affixId.lowercase()]
            ?: scoreConfig.combatWeights[combat.lowercase()]
            ?: scoreConfig.combatWeights["default"]
            ?: 5.0
        return value * weight
    }

    private fun calculatePrice(equipment: EquipmentConfig, score: Int): Double {
        if (equipment.pixelShopPrice <= 0.0) return 0.0
        val scoreConfig = config.score
        val multiplier = scoreConfig.priceMultiplierBase + score / scoreConfig.priceScoreDivisor
        return maxOf(scoreConfig.minPrice, equipment.pixelShopPrice * multiplier)
    }

    private fun attackSpeedModifier(equipment: EquipmentConfig): Double? {
        val attackSpeed = when (equipment.weaponCategory.lowercase()) {
            "melee_light" -> 2.1
            "melee_heavy" -> 1.25
            "polearm" -> 1.45
            "bow" -> 1.0
            "crossbow" -> 0.85
            "firearm" -> 0.65
            else -> return null
        }
        return attackSpeed - 4.0
    }

    private fun equipmentSlotGroup(equipment: EquipmentConfig): EquipmentSlotGroup {
        return when {
            "mainhand" in equipment.effectiveSlots -> EquipmentSlotGroup.MAINHAND
            "offhand" in equipment.effectiveSlots -> EquipmentSlotGroup.OFFHAND
            "hand" in equipment.effectiveSlots -> EquipmentSlotGroup.HAND
            "head" in equipment.effectiveSlots -> EquipmentSlotGroup.HEAD
            "chest" in equipment.effectiveSlots -> EquipmentSlotGroup.CHEST
            "legs" in equipment.effectiveSlots -> EquipmentSlotGroup.LEGS
            "feet" in equipment.effectiveSlots -> EquipmentSlotGroup.FEET
            "armor" in equipment.effectiveSlots -> EquipmentSlotGroup.ARMOR
            else -> EquipmentSlotGroup.ANY
        }
    }

    private fun usesEquippedSlot(equipment: EquipmentConfig): Boolean {
        return equipment.effectiveSlots.any {
            it in setOf("mainhand", "offhand", "hand", "head", "chest", "legs", "feet", "armor")
        }
    }

    /**
     * 将 MOD 聚合后的护甲/生命增量写为装备上的原版属性修饰符（键固定，便于覆盖更新）。
     * base_damage / 暴击 / 技能 / 能量 / 护盾等 MOD 增量已通过 readAffixValue 的 delta 叠加在
     * 战斗与 statTotals 路径生效，无需原版修饰符，故此处只处理护甲与生命。
     */
    fun applyModVanillaAttributes(item: ItemStack, armorDelta: Double, healthDelta: Double) {
        val equipment = equipmentConfig(item) ?: return
        val meta = item.itemMeta
        val armorKey = NamespacedKey(plugin, "mod_attr_armor_${equipment.id.lowercase()}")
        val healthKey = NamespacedKey(plugin, "mod_attr_health_${equipment.id.lowercase()}")
        removeModifierByKey(meta, Attribute.ARMOR, armorKey)
        removeModifierByKey(meta, Attribute.MAX_HEALTH, healthKey)
        if (usesEquippedSlot(equipment)) {
            if (armorDelta > 0.0) {
                meta.addAttributeModifier(
                    Attribute.ARMOR,
                    AttributeModifier(armorKey, armorDelta, AttributeModifier.Operation.ADD_NUMBER, equipmentSlotGroup(equipment))
                )
            }
            if (healthDelta > 0.0) {
                meta.addAttributeModifier(
                    Attribute.MAX_HEALTH,
                    AttributeModifier(healthKey, healthDelta, AttributeModifier.Operation.ADD_NUMBER, equipmentSlotGroup(equipment))
                )
            }
        }
        item.itemMeta = meta
    }

    private fun removeModifierByKey(meta: org.bukkit.inventory.meta.ItemMeta, attribute: Attribute, key: NamespacedKey) {
        val modifiers = meta.getAttributeModifiers(attribute) ?: return
        for (modifier in modifiers) {
            if (modifier.key == key) {
                meta.removeAttributeModifier(attribute, modifier)
            }
        }
    }

    fun buildExpression(expression: String, player: Player?, amount: Int): ItemStack? {
        val expr = SourceForgeExpression.parse(expression) ?: return null
        return when (expr.kind) {
            "equipment" -> {
                if (expr.id !in config.equipment) return null
                val tier = parseTier(expr.params["tier"], 1, 5)
                val affixesCount = expr.params["affixes"]?.toIntOrNull()
                createDirectEquipment(expr.id, tier, affixesCount)
            }
            else -> null
        }
    }

    fun buildConfiguredItem(itemId: String, amount: Int): ItemStack? {
        val ceItem = CraftEngineHook.build(itemId, amount)
        if (ceItem != null) return ceItem
        val material = Material.matchMaterial(itemId.uppercase()) ?: return null
        return ItemStack(material, amount)
    }

    fun equipmentTier(item: ItemStack?): Int {
        if (item == null || !item.hasItemMeta()) return 0
        return item.itemMeta.persistentDataContainer.get(tierKey, PersistentDataType.INTEGER) ?: 0
    }

    fun readAffixValue(item: ItemStack?, affixId: String): Double {
        if (item == null || !item.hasItemMeta()) return 0.0
        return readAffixValue(item.itemMeta.persistentDataContainer, affixId)
    }

    fun readAffixValue(container: PersistentDataContainer, affixId: String): Double {
        val affix = config.affixes[affixId] ?: return 0.0
        val key = affixKey(affix)
        val baseValue = when (affix.valueType) {
            "int", "integer" -> container.get(key, PersistentDataType.INTEGER)?.toDouble() ?: 0.0
            "string" -> container.get(key, PersistentDataType.STRING)?.toDoubleOrNull() ?: 0.0
            else -> container.get(key, PersistentDataType.DOUBLE) ?: 0.0
        }
        val delta = modDeltaKeys[affixId]?.let { container.get(it, PersistentDataType.DOUBLE) } ?: 0.0
        return baseValue + delta
    }

    fun readScore(item: ItemStack?): Int {
        if (item == null || !item.hasItemMeta()) return 0
        return item.itemMeta.persistentDataContainer.get(scoreKey, PersistentDataType.INTEGER) ?: 0
    }

    fun readPrice(item: ItemStack?): Double {
        if (item == null || !item.hasItemMeta()) return 0.0
        return item.itemMeta.persistentDataContainer.get(pixelShopPriceKey, PersistentDataType.DOUBLE) ?: 0.0
    }

    fun markProjectile(projectile: Projectile, weapon: ItemStack) {
        stripVanillaEnchantments(weapon)
        val pdc = projectile.persistentDataContainer
        val weaponPdc = weapon.itemMeta?.persistentDataContainer
        val typeId = weaponPdc?.get(typeKey, PersistentDataType.STRING)
        val categoryId = weaponPdc?.get(categoryKey, PersistentDataType.STRING)
            ?: typeId?.let { config.equipment[it]?.weaponCategory }
        pdc.set(projectileMarkerKey, PersistentDataType.BYTE, 1)
        pdc.set(typeKey, PersistentDataType.STRING, typeId ?: "ranged")
        pdc.set(categoryKey, PersistentDataType.STRING, categoryId ?: "ranged")
        pdc.set(tierKey, PersistentDataType.INTEGER, weaponPdc?.get(tierKey, PersistentDataType.INTEGER) ?: 1)
        if (weaponPdc == null) return
        for (affixId in config.affixes.keys) {
            val value = readAffixValue(weaponPdc, affixId)
            if (value <= 0.0) continue
            val affix = config.affixes[affixId] ?: continue
            val key = affixKey(affix)
            when (affix.valueType) {
                "int", "integer" -> pdc.set(key, PersistentDataType.INTEGER, value.toInt())
                "string" -> pdc.set(key, PersistentDataType.STRING, format(value, affix.decimals))
                else -> pdc.set(key, PersistentDataType.DOUBLE, value)
            }
        }
    }

    fun isSourceProjectile(projectile: Projectile?): Boolean {
        if (projectile == null) return false
        return projectile.persistentDataContainer.has(projectileMarkerKey, PersistentDataType.BYTE)
    }

    fun isSourceEquipment(item: ItemStack?): Boolean {
        if (item == null || item.type == Material.AIR || !item.hasItemMeta()) return false
        return item.itemMeta.persistentDataContainer.has(typeKey, PersistentDataType.STRING)
    }

    fun stripVanillaEnchantments(item: ItemStack?) {
        if (!isSourceEquipment(item)) return
        item?.enchantments?.keys?.toList()?.forEach { item.removeEnchantment(it) }
    }

    fun weaponType(item: ItemStack?): String? {
        if (item == null || item.type == Material.AIR || !item.hasItemMeta()) return null
        return item.itemMeta.persistentDataContainer.get(typeKey, PersistentDataType.STRING)
    }

    fun weaponCategory(item: ItemStack?): String? {
        if (item == null || item.type == Material.AIR || !item.hasItemMeta()) return null
        return item.itemMeta.persistentDataContainer.get(categoryKey, PersistentDataType.STRING)
            ?: weaponType(item)?.let { config.equipment[it]?.weaponCategory }
    }

    fun equipmentConfig(item: ItemStack?): EquipmentConfig? {
        return config.equipment[weaponType(item)]
    }

    fun projectileWeaponType(projectile: Projectile): String? {
        return projectile.persistentDataContainer.get(typeKey, PersistentDataType.STRING)
    }

    fun projectileWeaponCategory(projectile: Projectile): String? {
        return projectile.persistentDataContainer.get(categoryKey, PersistentDataType.STRING)
            ?: projectileWeaponType(projectile)?.let { config.equipment[it]?.weaponCategory }
    }

    private fun rollAffixes(
        equipment: EquipmentConfig,
        tier: Int,
        maxAffixes: Int
    ): List<Pair<AffixConfig, Double>> {
        val limit = maxAffixes.coerceAtLeast(0)
        if (limit <= 0) return emptyList()
        val candidates = mutableListOf<AffixCandidate>()

        val tierRolls = equipment.tierAffixes[tier]
            ?: equipment.tierAffixes.filterKeys { it <= tier }.maxByOrNull { it.key }?.value
            ?: equipment.affixIds.mapNotNull { id ->
                config.affixes[id]?.let { AffixRollConfig(id, 1.0, it.min, it.max) }
            }
        tierRolls.forEach { candidates += AffixCandidate(it, "tier") }

        val rolled = linkedMapOf<String, Pair<AffixConfig, Double>>()
        for (candidate in candidates) {
            if (!passesChance(candidate.roll.chance)) continue
            applyCandidate(candidate, rolled)
        }
        val result = rolled.values.toMutableList()
        if (result.size <= limit) return result

        val weights = result.associate { (affix, _) ->
            val chance = candidates.firstOrNull { it.roll.affixId == affix.id }?.roll?.chance ?: 1.0
            affix.id to chance.coerceAtLeast(0.0001)
        }
        val limited = mutableListOf<Pair<AffixConfig, Double>>()
        while (limited.size < limit && result.isNotEmpty()) {
            val picked = weightedPick(result, weights) ?: break
            limited += picked
            result.remove(picked)
        }
        return limited
    }

    private fun applyCandidate(
        candidate: AffixCandidate,
        rolled: MutableMap<String, Pair<AffixConfig, Double>>
    ): Boolean {
        val affix = config.affixes[candidate.roll.affixId] ?: return false
        val value = randomValue(candidate.roll, affix) * affix.scale
        val previous = rolled[affix.id]
        if (previous == null || value > previous.second) {
            rolled[affix.id] = affix to value
        }
        return true
    }

    private fun weightedPick(pool: List<Pair<AffixConfig, Double>>, weights: Map<String, Double>): Pair<AffixConfig, Double>? {
        val total = pool.sumOf { weights[it.first.id] ?: 1.0 }.takeIf { it > 0.0 } ?: return pool.randomOrNull()
        var roll = Random.nextDouble(total)
        for (entry in pool) {
            roll -= weights[entry.first.id] ?: 1.0
            if (roll <= 0.0) return entry
        }
        return pool.lastOrNull()
    }

    private fun passesChance(chance: Double): Boolean {
        if (chance <= 0.0) return false
        if (chance >= 1.0) return true
        return Random.nextDouble() < chance
    }

    private fun randomValue(roll: AffixRollConfig, affix: AffixConfig): Double {
        val min = if (roll.max > roll.min) roll.min else affix.min
        val max = if (roll.max > roll.min) roll.max else affix.max
        if (max <= min) return min
        return Random.nextDouble(min, max)
    }

    private fun writeAffixValue(
        pdc: PersistentDataContainer,
        affix: AffixConfig,
        value: Double
    ) {
        val key = affixKey(affix)
        when (affix.valueType) {
            "int", "integer" -> pdc.set(key, PersistentDataType.INTEGER, value.toInt())
            "string" -> pdc.set(key, PersistentDataType.STRING, format(value, affix.decimals))
            else -> pdc.set(key, PersistentDataType.DOUBLE, value)
        }
    }

    private fun chunkWorldLevel(equipment: EquipmentConfig, tier: Int): Int {
        return when (equipment.chunkWorldLevelMode.lowercase()) {
            "0", "none" -> 0
            "blueprint-tier", "tier" -> tier
            else -> equipment.chunkWorldLevelMode.toIntOrNull() ?: tier
        }.coerceAtLeast(1)
    }

    private fun clearAffixes(item: ItemStack) {
        val meta = item.itemMeta
        val pdc = meta.persistentDataContainer
        for (affix in config.affixes.values) {
            pdc.remove(affixKey(affix))
        }
        for (key in modDeltaKeys.values) {
            pdc.remove(key)
        }
        pdc.remove(affixesKey)
        pdc.remove(scoreKey)
        pdc.remove(pixelShopPriceKey)
        item.itemMeta = meta
    }

    private fun readAffixIds(item: ItemStack): List<String> {
        if (!item.hasItemMeta()) return emptyList()
        return item.itemMeta.persistentDataContainer.get(affixesKey, PersistentDataType.STRING)
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?: emptyList()
    }

    fun readBaseDamage(item: ItemStack?): Double {
        return readAffixValue(item, "base_damage")
    }

    fun readCriticalChance(item: ItemStack?): Double {
        return readAffixValue(item, "critical_chance")
    }

    fun readCriticalDamage(item: ItemStack?): Double {
        return readAffixValue(item, "critical_damage")
    }

    fun readTotalAffix(player: Player, affixId: String): Double {
        return statTotals(player)[affixId] ?: 0.0
    }

    /** 装备变化时由 InventoryAttributeListener 调用，丢弃缓存，下次读取按最新背包重算。 */
    fun invalidateStatCache(player: Player) {
        statCache.remove(player.uniqueId)
    }

    private fun statTotals(player: Player): Map<String, Double> {
        // 仅在主线程访问 Bukkit 背包；异步触发（如非主线程的技能事件）时跳过缓存，直接安全重算，
        // 避免在异步线程克隆 itemMeta 抛异常被上层 try/catch 静默吞掉导致词条读到 0。
        if (!plugin.server.isPrimaryThread) {
            return runCatching { computeStatTotals(player) }.getOrDefault(emptyMap())
        }
        val now = System.currentTimeMillis()
        val cached = statCache[player.uniqueId]
        if (cached != null && now < cached.expireAt) {
            return cached.totals
        }
        val totals = computeStatTotals(player)
        statCache[player.uniqueId] = CachedStats(totals, now + STAT_CACHE_TTL_MS)
        return totals
    }

    private fun computeStatTotals(player: Player): Map<String, Double> {
        val totals = HashMap<String, Double>()
        for (item in effectiveSourceItems(player)) {
            val pdc = item.itemMeta?.persistentDataContainer ?: continue
            for (affixId in config.affixes.keys) {
                val value = readAffixValue(pdc, affixId)
                if (value != 0.0) {
                    totals[affixId] = (totals[affixId] ?: 0.0) + value
                }
            }
        }
        return totals
    }

    fun readDisplayTotalAffix(player: Player, affixId: String): Double {
        val total = readTotalAffix(player, affixId)
        return when (affixId) {
            "ability_strength", "ability_duration", "ability_range" -> 1.0 + total
            "shield_capacity" -> 10.0 + total
            else -> total
        }
    }

    fun hasSourceArmor(player: Player): Boolean {
        return player.inventory.armorContents.any { isSourceEquipment(it) }
    }

    fun effectiveSourceItems(player: Player): List<ItemStack> {
        val inventory = player.inventory
        val items = mutableListOf<ItemStack>()
        addSourceItem(items, inventory.helmet)
        addSourceItem(items, inventory.chestplate)
        addSourceItem(items, inventory.leggings)
        addSourceItem(items, inventory.boots)
        addSourceItem(items, inventory.itemInMainHand)
        addSourceItem(items, inventory.itemInOffHand)
        for ((slot, item) in inventory.storageContents.withIndex()) {
            if (slot == inventory.heldItemSlot) continue
            if (isBackpackEffectiveSource(item)) {
                addSourceItem(items, item)
            }
        }
        return items
    }

    fun backpackSourceItems(player: Player): List<ItemStack> {
        val inventory = player.inventory
        return inventory.storageContents
            .filterIndexed { slot, item -> slot != inventory.heldItemSlot && isBackpackEffectiveSource(item) }
            .filterNotNull()
    }

    private fun addSourceItem(items: MutableList<ItemStack>, item: ItemStack?) {
        if (!isSourceEquipment(item)) return
        if (items.any { it === item }) return
        items += item!!
    }

    private fun isBackpackEffectiveSource(item: ItemStack?): Boolean {
        if (!isSourceEquipment(item)) return false
        return equipmentConfig(item)?.effectiveSlots?.any { it == "inventory" || it == "backpack" } == true
    }

    private fun format(value: Double, decimals: Int): String {
        if (decimals <= 0) return value.toInt().toString()
        return DecimalFormat("0." + "0".repeat(decimals)).format(value)
    }

    private fun parseTier(raw: String?, min: Int, max: Int): Int {
        if (raw.isNullOrBlank()) return min
        parseSimpleRange(raw)?.let {
            val range = normalizeTierRange(it, min, max)
            return Random.nextInt(range.first, range.last + 1)
        }
        if (raw.startsWith("random:", ignoreCase = true)) {
            val range = raw.substringAfter(":")
            val parts = range.split("-", limit = 2).mapNotNull { it.toIntOrNull() }
            if (parts.size == 2) {
                val a = minOf(parts[0], parts[1])
                val b = maxOf(parts[0], parts[1])
                return Random.nextInt(a, b + 1).coerceIn(min, max)
            }
        }
        return (raw.toIntOrNull() ?: min).coerceIn(min, max)
    }

    private fun parseTierRange(raw: String?, fallback: IntRange, min: Int, max: Int): IntRange {
        if (raw.isNullOrBlank()) return normalizeTierRange(fallback, min, max)
        val value = raw.removePrefix("random:")
        parseSimpleRange(value)?.let { return normalizeTierRange(it, min, max) }
        val fixed = value.toIntOrNull()
        if (fixed != null) return normalizeTierRange(fixed..fixed, min, max)
        return normalizeTierRange(fallback, min, max)
    }

    private fun parseSimpleRange(raw: String): IntRange? {
        val parts = raw.split("-", limit = 2).map { it.trim().toIntOrNull() }
        if (parts.size != 2 || parts[0] == null || parts[1] == null) return null
        return minOf(parts[0]!!, parts[1]!!)..maxOf(parts[0]!!, parts[1]!!)
    }

    private fun normalizeTierRange(range: IntRange, min: Int, max: Int): IntRange {
        val low = minOf(range.first, range.last).coerceIn(min, max)
        val high = maxOf(range.first, range.last).coerceIn(min, max)
        return minOf(low, high)..maxOf(low, high)
    }

    private fun formatTierRange(range: IntRange): String {
        return if (range.first == range.last) range.first.toString() else "${range.first}-${range.last}"
    }

    private companion object {
        /** 词条总和缓存兜底 TTL（毫秒）。约 5 tick，足够保留热路径性能，又能让漏失效的陈旧读取快速自愈。 */
        const val STAT_CACHE_TTL_MS = 250L
    }
}

private data class AffixCandidate(
    val roll: AffixRollConfig,
    val source: String
)

data class SourceForgeExpression(
    val kind: String,
    val id: String,
    val params: Map<String, String>
) {
    companion object {
        fun parse(raw: String): SourceForgeExpression? {
            if (!raw.startsWith("sf:", ignoreCase = true) && !raw.startsWith("sourceforge:", ignoreCase = true)) return null
            val body = raw.substringAfter(":")
            val path = body.substringBefore("?")
            val parts = path.split(":", limit = 2)
            if (parts.size != 2) return null
            val params = body.substringAfter("?", "")
                .takeIf { it.isNotBlank() }
                ?.split("&")
                ?.mapNotNull {
                    val key = it.substringBefore("=").trim()
                    val value = it.substringAfter("=", "").trim()
                    if (key.isBlank()) null else key to value
                }
                ?.toMap()
                ?: emptyMap()
            return SourceForgeExpression(parts[0].lowercase(), parts[1], params)
        }
    }
}
