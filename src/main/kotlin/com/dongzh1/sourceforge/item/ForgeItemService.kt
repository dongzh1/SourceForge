package com.dongzh1.sourceforge.item

import com.dongzh1.sourceforge.SourceForge
import com.dongzh1.sourceforge.config.AffixConfig
import com.dongzh1.sourceforge.config.AffixRollConfig
import com.dongzh1.sourceforge.config.EquipmentConfig
import com.dongzh1.sourceforge.config.ForgeConfig
import com.dongzh1.sourceforge.config.ForgeRecipe
import com.dongzh1.sourceforge.config.ForgeRecipeMode
import com.dongzh1.sourceforge.config.RecipeMaterial
import com.dongzh1.sourceforge.util.Text
import com.dongzh1.sourceforge.util.color
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
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
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * 外部词缀加成回调。其它插件注册后，玩家属性统计时会把 bonus(player) 的结果累加进去。
 * 返回 affixId->数值；调用方应按需(如玩家所在世界/状态)返回，不满足条件就返回空 Map，
 * 从而实现"临时、非持久、可随时失效"的属性加成（数值从不写在玩家/物品上）。
 */
fun interface ExternalAffixProvider {
    fun bonus(player: Player): Map<String, Double>
}

class ForgeItemService(
    private val plugin: SourceForge,
    private val config: ForgeConfig
) {
    private val typeKey = NamespacedKey(plugin, "type")
    private val categoryKey = NamespacedKey(plugin, "weapon_category")
    private val tierKey = NamespacedKey(plugin, "tier")
    private val enhanceLevelKey = NamespacedKey(plugin, "enhance_level")
    private val baseDamageKey = NamespacedKey(plugin, "base_damage")
    private val enhanceShieldCapacityKey = NamespacedKey(plugin, "enhance_shield_capacity")
    private val enhanceHealthKey = NamespacedKey(plugin, "enhance_health")
    private val modCapacityKey = com.dongzh1.sourceforge.mod.ModKeys.modCapacity(plugin)
    private val modCapacityMaxKey = NamespacedKey(plugin, "mod_capacity_max")
    private val affixesKey = NamespacedKey(plugin, "affixes")
    private val projectileMarkerKey = NamespacedKey(plugin, "projectile_source")
    private val scoreKey = NamespacedKey(plugin, "score")
    // 蓝图配方数据：直接写在 CraftEngine 物品配置的 pdc: 块里（不再走 recipes.yml 这张单独的表，
    // 用户要求把配方数据集中到 CE 物品定义本身，方便配置）。全部用 STRING 类型存取，绕开
    // YAML->NBT 数字类型转换的不确定性，数值在这边自己 parse。
    private val blueprintEquipmentKey = NamespacedKey(plugin, "blueprint_equipment")
    private val blueprintTierKey = NamespacedKey(plugin, "blueprint_tier")
    private val blueprintModeKey = NamespacedKey(plugin, "blueprint_mode")
    private val blueprintTimeSecondsKey = NamespacedKey(plugin, "blueprint_time_seconds")
    private val blueprintMaterialsKey = NamespacedKey(plugin, "blueprint_materials")
    private val blueprintWeaponCategoryKey = NamespacedKey(plugin, "blueprint_weapon_category")
    private val blueprintMinTierKey = NamespacedKey(plugin, "blueprint_min_tier")
    private val chunkWorldLevelKey = NamespacedKey("chunkworld", "level")
    private val pixelShopPriceKey = NamespacedKey("pixelshop", "price")

    /** 预建 affixId -> NamespacedKey，避免热路径反复构造（构造会做正则校验）。 */
    private val affixKeys: Map<String, NamespacedKey> =
        config.affixes.values.associate { it.id to NamespacedKey(plugin, it.pdcKey) }

    /** affixId -> mod_delta_<pdcKey>，MOD 系统额外加成层；与基础词条相加。 */
    private val modDeltaKeys: Map<String, NamespacedKey> =
        com.dongzh1.sourceforge.mod.ModKeys.modDeltaKeys(plugin, config.affixes.values)

    /**
     * 每玩家全身词条总和缓存；装备变化时由监听器失效，避免每次战斗/回盾都扫全背包。
     * 同时带一个很短的 TTL 作为兜底：即使某条装备变更路径（命令塞装备、漏斗/发射器装甲、
     * 重生重装等）漏掉了显式失效，陈旧读取也会在 ~250ms 内自愈，不会让 ability_efficiency
     * 之类词条长时间读到 0。
     */
    private val statCache = ConcurrentHashMap<UUID, CachedStats>()

    private class CachedStats(val totals: Map<String, Double>, val expireAt: Long)

    /**
     * 外部临时词缀 Provider 列表（如 PixelRPG 副本临时强化）。统计属性时一并累加。
     * 数值由回调实时返回、从不持久化，调用方可据世界/状态返回 0，保证副本外不生效、重启即归零。
     */
    private val externalProviders = java.util.concurrent.CopyOnWriteArrayList<ExternalAffixProvider>()

    fun registerExternalAffixProvider(provider: ExternalAffixProvider) {
        externalProviders.addIfAbsent(provider)
    }

    fun unregisterExternalAffixProvider(provider: ExternalAffixProvider) {
        externalProviders.remove(provider)
    }

    /** 导出已注册的外部词缀 Provider；reload 重建本服务实例时迁移，避免 PixelRPG 等临时属性在 /sf reload 后丢失。 */
    fun exportExternalProviders(): List<ExternalAffixProvider> = externalProviders.toList()

    /** 已注册的外部词缀 Provider 数量（诊断用）。 */
    fun externalProviderCount(): Int = externalProviders.size

    /** 当前所有外部 Provider 对该玩家的临时词缀贡献合计（诊断用，实时回调，不写入任何地方）。 */
    fun externalProviderTotals(player: Player): Map<String, Double> {
        val totals = HashMap<String, Double>()
        for (provider in externalProviders) {
            val bonus = runCatching { provider.bonus(player) }.getOrNull() ?: continue
            for ((affixId, value) in bonus) {
                if (value != 0.0 && config.affixes.containsKey(affixId)) {
                    totals[affixId] = (totals[affixId] ?: 0.0) + value
                }
            }
        }
        return totals
    }

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
            val candidate = if (roll != null && !roll.value.isNaN()) roll.value * affix.scale else currentValue
            affix to maxOf(currentValue, candidate)
        }
    }

    /**
     * 蓝图原地升级（Feature：蓝图重铸）：把 [oldItem] 换成 [targetEquipment] 在 [targetTier] 的
     * 外观（CE身份/材质）与基础属性，但保留原物品的附魔、MOD 镶嵌（含梦魇MOD实例数据）与已投入的强化等级。
     * 与 [upgradeEquipment]（同一 equipment id 只把 tier+1，不换外观）是两个不同的操作，互不影响。
     * 调用方需自行校验武器类型/等级门槛（见 recipe.requiresWeaponCategory / recipe.minTier）。
     */
    fun reforgeEquipment(oldItem: ItemStack, targetEquipment: EquipmentConfig, targetTier: Int): ItemStack? {
        val oldMeta = oldItem.itemMeta ?: return null
        val base = targetEquipment.ceId?.let { CraftEngineHook.build(it, 1) } ?: ItemStack(targetEquipment.material, 1)
        val meta = base.itemMeta

        oldMeta.enchants.forEach { (ench, lvl) -> meta.addEnchant(ench, lvl, true) }
        copyStringPdc(oldMeta, meta, com.dongzh1.sourceforge.mod.ModKeys.modInstalled(plugin))
        copyStringPdc(oldMeta, meta, com.dongzh1.sourceforge.mod.ModKeys.modSkillInstalled(plugin))
        com.dongzh1.sourceforge.mod.ModKeys.nmSlots(plugin).forEach { copyStringPdc(oldMeta, meta, it) }
        com.dongzh1.sourceforge.mod.ModKeys.rivenSlots(plugin).forEach { copyStringPdc(oldMeta, meta, it) }
        // 保留原版盔甲纹饰(锻造台Trim)：跟 CE 外观模型是两套独立渲染层，重铸换模型不该连带把纹饰丢了。
        if (oldMeta is org.bukkit.inventory.meta.ArmorMeta && meta is org.bukkit.inventory.meta.ArmorMeta) {
            oldMeta.trim?.let { meta.trim = it }
        }
        base.itemMeta = meta

        val maxAffixes = maxOf(1, targetEquipment.tierAffixes[targetTier]?.size ?: targetEquipment.affixIds.size)
        writeEquipment(base, targetEquipment, targetTier, rollAffixes(targetEquipment, targetTier, maxAffixes))

        // 重铸后强化等级重新开始(用户明确要求)：不再继承旧武器的 enhance_level/base_damage/mod_capacity
        // 强化增量——旧武器身上任何强化痕迹都不带进新装备，玩家需要重新从 0 级强化。base 本来就是
        // writeEquipment 刚写好的全新装备，不用额外清理任何 PDC。

        plugin.modService.reapplyModEffects(base)
        return base
    }

    private fun copyStringPdc(from: org.bukkit.inventory.meta.ItemMeta, to: org.bukkit.inventory.meta.ItemMeta, key: NamespacedKey) {
        from.persistentDataContainer.get(key, PersistentDataType.STRING)
            ?.let { to.persistentDataContainer.set(key, PersistentDataType.STRING, it) }
    }

    private fun writeEquipment(
        item: ItemStack,
        equipment: EquipmentConfig,
        tier: Int,
        selected: List<Pair<AffixConfig, Double>>
    ) {
        val meta = item.itemMeta
        Text.name(meta, "&f${equipment.displayName}")
        meta.isUnbreakable = true
        meta.addItemFlags(ItemFlag.HIDE_UNBREAKABLE)

        // Attack damage: write base_damage to ATTACK_DAMAGE
        meta.removeAttributeModifier(Attribute.ATTACK_DAMAGE)
        val baseDamage = selected.sumOf { (affix, value) ->
            if (affix.combat == "base_damage") value * affix.scale else 0.0
        }
        if (baseDamage > 0.0 && isDamageBearingEquipment(equipment)) {
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

        meta.removeAttributeModifier(Attribute.MOVEMENT_SPEED)
        val movementSpeedValue = selected.sumOf { (affix, value) ->
            if (affix.combat == "movement_speed") value * affix.scale else 0.0
        }
        if (movementSpeedValue > 0.0 && usesEquippedSlot(equipment)) {
            meta.addAttributeModifier(
                Attribute.MOVEMENT_SPEED,
                AttributeModifier(
                    NamespacedKey(plugin, "equipment_movement_speed_${equipment.id.lowercase()}"),
                    movementSpeedValue,
                    AttributeModifier.Operation.ADD_SCALAR,
                    equipmentSlotGroup(equipment)
                )
            )
        }

        val lore = mutableListOf<String>()
        lore += equipment.baseLore.map { it.replace("%tier%", tier.toString()) }
        if (lore.isNotEmpty()) lore += ""

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
        lore += "&7等级 &e$tier &8| &7评分 &b$score"
        if (price > 0.0) {
            lore += "&8价格 &6${format(price, 1)}"
        }
        if (selected.isNotEmpty()) {
            lore += ""
            lore += "&6● 属性&7:"
            selected.forEach { (affix, value) ->
                lore += "  &7${affix.displayName} ${affix.color}+${formatAffixValue(affix, value)}"
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

    private fun isDamageBearingEquipment(equipment: EquipmentConfig): Boolean {
        return equipment.effectiveSlots.any { it in setOf("mainhand", "offhand", "hand") }
    }

    /**
     * 将 MOD 聚合后的护甲/生命/移速增量写为装备上的原版属性修饰符（键固定，便于覆盖更新）。
     * base_damage / 暴击 / 技能 / 能量 / 护盾等 MOD 增量已通过 readAffixValue 的 delta 叠加在
     * 战斗与 statTotals 路径生效，无需原版修饰符；movement_speed（如 fleetfoot 疾风之靴）是
     * SF 12维属性系统里第三个、也是唯一直接落在原版 Attribute.MOVEMENT_SPEED 的例外词条——
     * 用 ADD_SCALAR（多个来源之间线性相加再统一乘算一次）而非 MULTIPLY_SCALAR_1，避免跟其他
     * 移速来源（药水/其它插件）产生指数级乘算。
     */
    fun applyModVanillaAttributes(item: ItemStack, armorDelta: Double, healthDelta: Double, movementSpeedDelta: Double) {
        val equipment = equipmentConfig(item) ?: return
        val meta = item.itemMeta
        val armorKey = NamespacedKey(plugin, "mod_attr_armor_${equipment.id.lowercase()}")
        val healthKey = NamespacedKey(plugin, "mod_attr_health_${equipment.id.lowercase()}")
        val movementSpeedKey = NamespacedKey(plugin, "movement_speed_${equipment.id.lowercase()}")
        removeModifierByKey(meta, Attribute.ARMOR, armorKey)
        removeModifierByKey(meta, Attribute.MAX_HEALTH, healthKey)
        removeModifierByKey(meta, Attribute.MOVEMENT_SPEED, movementSpeedKey)
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
            if (movementSpeedDelta > 0.0) {
                meta.addAttributeModifier(
                    Attribute.MOVEMENT_SPEED,
                    AttributeModifier(movementSpeedKey, movementSpeedDelta, AttributeModifier.Operation.ADD_SCALAR, equipmentSlotGroup(equipment))
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

    // ==================== 武器强化（Feature A） ====================

    /** 读取武器当前强化等级（PDC sourceforge:enhance_level INT，默认 0）。 */
    fun enhanceLevel(item: ItemStack?): Int {
        if (item == null || !item.hasItemMeta()) return 0
        return item.itemMeta.persistentDataContainer.get(enhanceLevelKey, PersistentDataType.INTEGER) ?: 0
    }

    /**
     * 对一件武器原地应用一次强化升级（mutates `item`）：
     * - enhance_level += 1（写为 targetLevel）
     * - base_damage(DOUBLE) += baseDamageBonus
     * - mod_capacity(INT) += modCapacityBonus，受 mod_capacity_max 上限（若存在）
     * 并刷新 lore 中的强化等级行。
     */
    fun applyEnhancement(
        item: ItemStack,
        targetLevel: Int,
        baseDamageBonus: Double,
        modCapacityBonus: Int,
        shieldCapacityBonus: Double = 0.0,
        healthBonus: Double = 0.0
    ) {
        val meta = item.itemMeta
        val pdc = meta.persistentDataContainer
        val equipment = equipmentConfig(item)
        val damageBearing = equipment?.let { isDamageBearingEquipment(it) } == true

        pdc.set(enhanceLevelKey, PersistentDataType.INTEGER, targetLevel)

        if (damageBearing) {
            val baseDamage = equipment?.let { baseEquipmentDamage(it, equipmentTier(item)) } ?: 0.0
            val currentTotal = pdc.get(baseDamageKey, PersistentDataType.DOUBLE) ?: baseDamage
            val currentEnhancement = (currentTotal - baseDamage).coerceAtLeast(0.0)
            pdc.set(baseDamageKey, PersistentDataType.DOUBLE, baseDamage + currentEnhancement + baseDamageBonus)
        } else {
            pdc.remove(baseDamageKey)
            pdc.set(enhanceShieldCapacityKey, PersistentDataType.DOUBLE, (pdc.get(enhanceShieldCapacityKey, PersistentDataType.DOUBLE) ?: 0.0) + shieldCapacityBonus)
            pdc.set(enhanceHealthKey, PersistentDataType.DOUBLE, (pdc.get(enhanceHealthKey, PersistentDataType.DOUBLE) ?: 0.0) + healthBonus)
        }

        val curCap = pdc.get(modCapacityKey, PersistentDataType.INTEGER) ?: 0
        val capMax = pdc.get(modCapacityMaxKey, PersistentDataType.INTEGER)
        var newCap = curCap + modCapacityBonus
        if (capMax != null) newCap = newCap.coerceAtMost(capMax)
        pdc.set(modCapacityKey, PersistentDataType.INTEGER, newCap)

        if (equipment != null) {
            val attackDamageKey = NamespacedKey(plugin, "attack_damage_${equipment.id.lowercase()}")
            removeModifierByKey(meta, Attribute.ATTACK_DAMAGE, attackDamageKey)
            val totalDamage = pdc.get(baseDamageKey, PersistentDataType.DOUBLE) ?: 0.0
            if (damageBearing && totalDamage > 0.0) {
                meta.addAttributeModifier(
                    Attribute.ATTACK_DAMAGE,
                    AttributeModifier(
                        attackDamageKey,
                        totalDamage,
                        AttributeModifier.Operation.ADD_NUMBER,
                        equipmentSlotGroup(equipment)
                    )
                )
            }
        }
        if (equipment != null) refreshEnhancementHealthAttribute(meta, equipment, pdc)

        updateEnhancementLore(meta, targetLevel, pdc)

        item.itemMeta = meta
        plugin.modService.reapplyModEffects(item)
    }

    fun rebalanceEnhancement(item: ItemStack?): Boolean {
        if (!isSourceEquipment(item) || item == null) return false
        val level = enhanceLevel(item)
        val equipment = equipmentConfig(item) ?: return false
        val category = if (isProtectionEquipment(item)) {
            plugin.enhancementConfig.category("armor_physical")
        } else {
            plugin.enhancementConfig.category(weaponCategory(item))
        } ?: return false
        if (level <= 0 && isDamageBearingEquipment(equipment)) return false
        val targetLevel = level.coerceIn(0, category.maxLevel)
        val damageBearing = isDamageBearingEquipment(equipment)
        val targetDamage = if (damageBearing) {
            baseEquipmentDamage(equipment, equipmentTier(item)) + category.levels.take(targetLevel).sumOf { it.baseDamage }
        } else 0.0
        val targetShield = if (damageBearing) 0.0 else category.levels.take(targetLevel).sumOf { it.shieldCapacity }
        val targetHealth = if (damageBearing) 0.0 else category.levels.take(targetLevel).sumOf { it.health }
        val categoryId = weaponCategory(item) ?: "default"
        val baseCapacity = plugin.forgeConfig.modCapacity.computeCapacity(categoryId, equipmentTier(item))
        val targetCapacity = baseCapacity + category.levels.take(targetLevel).sumOf { it.modCapacity }
        val meta = item.itemMeta
        val pdc = meta.persistentDataContainer
        pdc.set(enhanceLevelKey, PersistentDataType.INTEGER, targetLevel)
        if (damageBearing && targetDamage > 0.0) pdc.set(baseDamageKey, PersistentDataType.DOUBLE, targetDamage)
        else pdc.remove(baseDamageKey)
        if (targetShield > 0.0) pdc.set(enhanceShieldCapacityKey, PersistentDataType.DOUBLE, targetShield)
        else pdc.remove(enhanceShieldCapacityKey)
        if (targetHealth > 0.0) pdc.set(enhanceHealthKey, PersistentDataType.DOUBLE, targetHealth)
        else pdc.remove(enhanceHealthKey)
        val capMax = pdc.get(modCapacityMaxKey, PersistentDataType.INTEGER)
        pdc.set(modCapacityKey, PersistentDataType.INTEGER, if (capMax == null) targetCapacity else targetCapacity.coerceAtMost(capMax))

        val attackDamageKey = NamespacedKey(plugin, "attack_damage_${equipment.id.lowercase()}")
        removeModifierByKey(meta, Attribute.ATTACK_DAMAGE, attackDamageKey)
        if (damageBearing && targetDamage > 0.0) {
            meta.addAttributeModifier(
                Attribute.ATTACK_DAMAGE,
                AttributeModifier(attackDamageKey, targetDamage, AttributeModifier.Operation.ADD_NUMBER, equipmentSlotGroup(equipment))
            )
        }
        refreshEnhancementHealthAttribute(meta, equipment, pdc)
        updateEnhancementLore(meta, targetLevel, pdc)
        item.itemMeta = meta
        plugin.modService.reapplyModEffects(item)
        return true
    }

    fun downgradeEnhancement(item: ItemStack?, targetLevel: Int): Boolean {
        if (!isSourceEquipment(item) || item == null) return false
        val currentLevel = enhanceLevel(item)
        if (currentLevel <= targetLevel) return false
        val equipment = equipmentConfig(item) ?: return false
        val categoryId = weaponCategory(item) ?: return false
        val category = if (isProtectionEquipment(item)) {
            plugin.enhancementConfig.category("armor_physical")
        } else {
            plugin.enhancementConfig.category(categoryId)
        } ?: return false
        val level = targetLevel.coerceIn(0, category.maxLevel)
        val damageBearing = isDamageBearingEquipment(equipment)
        val targetDamage = if (damageBearing) {
            baseEquipmentDamage(equipment, equipmentTier(item)) + category.levels.take(level).sumOf { it.baseDamage }
        } else 0.0
        val targetShield = if (damageBearing) 0.0 else category.levels.take(level).sumOf { it.shieldCapacity }
        val targetHealth = if (damageBearing) 0.0 else category.levels.take(level).sumOf { it.health }
        val baseCapacity = plugin.forgeConfig.modCapacity.computeCapacity(categoryId, equipmentTier(item))
        val targetCapacity = baseCapacity + category.levels.take(level).sumOf { it.modCapacity }

        val meta = item.itemMeta
        val pdc = meta.persistentDataContainer
        pdc.set(enhanceLevelKey, PersistentDataType.INTEGER, level)
        if (damageBearing && targetDamage > 0.0) pdc.set(baseDamageKey, PersistentDataType.DOUBLE, targetDamage)
        else pdc.remove(baseDamageKey)
        if (targetShield > 0.0) pdc.set(enhanceShieldCapacityKey, PersistentDataType.DOUBLE, targetShield)
        else pdc.remove(enhanceShieldCapacityKey)
        if (targetHealth > 0.0) pdc.set(enhanceHealthKey, PersistentDataType.DOUBLE, targetHealth)
        else pdc.remove(enhanceHealthKey)
        val capMax = pdc.get(modCapacityMaxKey, PersistentDataType.INTEGER)
        pdc.set(modCapacityKey, PersistentDataType.INTEGER, if (capMax == null) targetCapacity else targetCapacity.coerceAtMost(capMax))

        val attackDamageKey = NamespacedKey(plugin, "attack_damage_${equipment.id.lowercase()}")
        removeModifierByKey(meta, Attribute.ATTACK_DAMAGE, attackDamageKey)
        if (damageBearing && targetDamage > 0.0) {
            meta.addAttributeModifier(
                Attribute.ATTACK_DAMAGE,
                AttributeModifier(attackDamageKey, targetDamage, AttributeModifier.Operation.ADD_NUMBER, equipmentSlotGroup(equipment))
            )
        }
        refreshEnhancementHealthAttribute(meta, equipment, pdc)
        updateEnhancementLore(meta, level, pdc)
        item.itemMeta = meta
        plugin.modService.reapplyModEffects(item)
        return true
    }

    private fun updateEnhancementLore(
        meta: org.bukkit.inventory.meta.ItemMeta,
        level: Int,
        pdc: PersistentDataContainer
    ) {
        val plainText = PlainTextComponentSerializer.plainText()
        val lore = (meta.lore() ?: meta.lore?.map { Text.comp(it) } ?: emptyList()).toMutableList()
        val index = lore.indexOfFirst { plainText.serialize(it).contains("强化") }
        if (level <= 0) {
            if (index >= 0) lore.removeAt(index)
        } else {
            val line = Text.comp("&7强化等级 &b+$level")
            if (index >= 0) lore[index] = line else lore.add(line)
        }
        for (affixId in readAffixIds(pdc)) {
            val affix = config.affixes[affixId] ?: continue
            val affixIndex = lore.indexOfFirst { plainText.serialize(it).contains(affix.displayName) }
            if (affixIndex < 0) continue
            val value = readAffixValue(pdc, affixId)
            lore[affixIndex] = Text.comp("  &7${affix.displayName} ${affix.color}+${formatAffixValue(affix, value)}")
        }
        meta.lore(lore)
    }

    private fun refreshEnhancementHealthAttribute(
        meta: org.bukkit.inventory.meta.ItemMeta,
        equipment: EquipmentConfig,
        pdc: PersistentDataContainer
    ) {
        val key = NamespacedKey(plugin, "enhance_attr_health_${equipment.id.lowercase()}")
        removeModifierByKey(meta, Attribute.MAX_HEALTH, key)
        if (!isDamageBearingEquipment(equipment)) {
            val health = pdc.get(enhanceHealthKey, PersistentDataType.DOUBLE) ?: 0.0
            if (health > 0.0) {
                meta.addAttributeModifier(
                    Attribute.MAX_HEALTH,
                    AttributeModifier(key, health, AttributeModifier.Operation.ADD_NUMBER, equipmentSlotGroup(equipment))
                )
            }
        }
    }

    private fun baseEquipmentDamage(equipment: EquipmentConfig, tier: Int): Double {
        val rolls = equipment.tierAffixes[tier]
            ?: equipment.tierAffixes.filterKeys { it <= tier }.maxByOrNull { it.key }?.value
            ?: return 0.0
        val scale = config.affixes["base_damage"]?.scale ?: 1.0
        return rolls.filter { it.affixId == "base_damage" && !it.value.isNaN() }.sumOf { it.value * scale }
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
        val enhancement = when (affixId) {
            "shield_capacity" -> container.get(enhanceShieldCapacityKey, PersistentDataType.DOUBLE) ?: 0.0
            "health" -> container.get(enhanceHealthKey, PersistentDataType.DOUBLE) ?: 0.0
            else -> 0.0
        }
        return baseValue + delta + enhancement
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

    fun isProtectionEquipment(item: ItemStack?): Boolean {
        val equipment = equipmentConfig(item) ?: return false
        return equipment.effectiveSlots.any { it in setOf("head", "chest", "legs", "feet", "armor") }
    }


    /**
     * 数玩家背包内匹配某 CE id 的物品数量(仅 storageContents，含快捷栏)。锻炉GUI材料展示(ForgeMenu)
     * 与实际扣料判定(ForgeMenuListener)统一走这一个口子，避免两边各自拷贝一份统计逻辑改漏导致
     * "显示够了"和"实际扣不够"对不上。
     */
    fun countInInventory(player: Player, ceId: String): Int {
        var total = 0
        for (item in player.inventory.storageContents) {
            if (item == null || item.type == Material.AIR) continue
            if (CraftEngineHook.matches(item, ceId)) total += item.amount
        }
        return total
    }

    fun consumeFromInventory(player: Player, ceId: String, amount: Int): Boolean {
        val required = amount.coerceAtLeast(1)
        if (countInInventory(player, ceId) < required) return false
        var remaining = required
        val storage = player.inventory.storageContents
        for (slot in storage.indices) {
            if (remaining <= 0) break
            val item = storage[slot] ?: continue
            if (item.type == Material.AIR || !CraftEngineHook.matches(item, ceId)) continue
            val taken = minOf(remaining, item.amount)
            item.amount -= taken
            remaining -= taken
            player.inventory.setItem(slot, item.takeIf { it.amount > 0 })
        }
        return remaining == 0
    }

    /**
     * SF 装备的原版附魔卫生检查：不在 [com.dongzh1.sourceforge.enchant.EnchantBridgeConfig] 白名单里的
     * 一律清除；白名单内的按配置最高等级夹紧（防止管理员/NBT编辑塞进超范围等级）。每次伤害结算前调用，
     * 确保白名单以外的原版附魔无论通过什么途径混进 PDC 都翻不起浪。
     */
    fun stripVanillaEnchantments(item: ItemStack?) {
        if (!isSourceEquipment(item) || item == null) return
        val cfg = config.enchantBridge
        item.enchantments.entries.toList().forEach { (enchant, level) ->
            if (!cfg.isAllowed(enchant)) {
                item.removeEnchantment(enchant)
            } else {
                val capped = cfg.capLevel(enchant, level)
                if (capped != level) item.addUnsafeEnchantment(enchant, capped)
            }
        }
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

    /** 该护甲件的部位 key（head/chest/legs/feet），供 MOD applicable-slots 精确限定装备位用；
     *  非护甲/无法识别返回 null。复用 equipment 的 effective-slots，不新开 PDC 字段。 */
    fun armorSlotKey(item: ItemStack?): String? {
        val slots = equipmentConfig(item)?.effectiveSlots ?: return null
        return listOf("head", "chest", "legs", "feet").firstOrNull { it in slots }
    }

    /**
     * 从蓝图物品自身的 PDC 解析锻造配方——数据源是 CraftEngine 物品配置里的 `pdc:` 块
     * （见 relics_duskgold.yml），不再有单独的 recipes.yml。缺任意必填字段(equipment/materials)
     * 视为"这不是一张有效蓝图"，返回 null（蓝图槽逻辑据此判定"无有效蓝图"）。
     * blueprintId 取 CraftEngine 物品 id，仅用于调试消息标识来源，不参与查表。
     */
    fun readBlueprintRecipe(item: ItemStack?, blueprintId: String): ForgeRecipe? {
        if (item == null || item.type == Material.AIR || !item.hasItemMeta()) return null
        val pdc = item.itemMeta.persistentDataContainer
        val equipmentId = pdc.get(blueprintEquipmentKey, PersistentDataType.STRING)?.takeIf { it.isNotBlank() } ?: return null
        // 每段格式 "<命名空间>:<路径>:<数量>"，比如 "minecraft:netherite_ingot:1"——ceId 本身带冒号，
        // 不能直接按 ':' 切成 2 段(会切出 3 段导致误判失败)，要按最后一个冒号切分。
        val materials = pdc.get(blueprintMaterialsKey, PersistentDataType.STRING)
            ?.split(',')
            ?.mapNotNull { entry ->
                val trimmed = entry.trim()
                val lastColon = trimmed.lastIndexOf(':')
                if (lastColon <= 0) return@mapNotNull null
                val amount = trimmed.substring(lastColon + 1).toIntOrNull() ?: return@mapNotNull null
                RecipeMaterial(trimmed.substring(0, lastColon), amount)
            }
            ?: emptyList()
        if (materials.isEmpty()) return null
        val mode = if (pdc.get(blueprintModeKey, PersistentDataType.STRING).equals("upgrade", ignoreCase = true)) {
            ForgeRecipeMode.UPGRADE
        } else {
            ForgeRecipeMode.CREATE
        }
        return ForgeRecipe(
            blueprintId = blueprintId,
            equipmentId = equipmentId,
            tier = pdc.get(blueprintTierKey, PersistentDataType.STRING)?.toIntOrNull()?.coerceAtLeast(1) ?: 1,
            timeSeconds = pdc.get(blueprintTimeSecondsKey, PersistentDataType.STRING)?.toDoubleOrNull()?.coerceAtLeast(0.0) ?: 60.0,
            materials = materials,
            mode = mode,
            requiresWeaponCategory = pdc.get(blueprintWeaponCategoryKey, PersistentDataType.STRING)?.takeIf { it.isNotBlank() }?.lowercase(),
            minTier = pdc.get(blueprintMinTierKey, PersistentDataType.STRING)?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        )
    }

    fun projectileWeaponType(projectile: Projectile): String? {
        return projectile.persistentDataContainer.get(typeKey, PersistentDataType.STRING)
    }

    fun projectileWeaponCategory(projectile: Projectile): String? {
        return projectile.persistentDataContainer.get(categoryKey, PersistentDataType.STRING)
            ?: projectileWeaponType(projectile)?.let { config.equipment[it]?.weaponCategory }
    }

    /**
     * 按目标 tier 直接读取写死的词条值（不再随机采样）。
     * `limit < 该tier词条总数` 只会在显式请求"只要前N条"时出现（如 `/sf giveequipment` 传了更小的 affixes 参数，
     * 纯管理员调试用途，玩家锻造路径的 maxAffixes 恒等于该 tier 词条总数）——按配置原始顺序确定性裁剪，不做随机抽取。
     */
    private fun rollAffixes(
        equipment: EquipmentConfig,
        tier: Int,
        maxAffixes: Int
    ): List<Pair<AffixConfig, Double>> {
        val limit = maxAffixes.coerceAtLeast(0)
        if (limit <= 0) return emptyList()

        val tierRolls = equipment.tierAffixes[tier]
            ?: equipment.tierAffixes.filterKeys { it <= tier }.maxByOrNull { it.key }?.value
            ?: emptyList()

        val rolled = linkedMapOf<String, Pair<AffixConfig, Double>>()
        for (roll in tierRolls) {
            applyRoll(roll, rolled)
        }
        val result = rolled.values.toList()
        if (result.size <= limit) return result

        val order = tierRolls.map { it.affixId }
        return result
            .sortedBy { (affix, _) -> order.indexOf(affix.id).let { if (it < 0) Int.MAX_VALUE else it } }
            .take(limit)
    }

    /** value 为 NaN 表示该词条配置未迁移完成（还是老的 {chance,min,max} 结构），已在启动校验里报过警告，这里安全跳过不写入。 */
    private fun applyRoll(
        roll: AffixRollConfig,
        rolled: MutableMap<String, Pair<AffixConfig, Double>>
    ): Boolean {
        if (roll.value.isNaN()) return false
        val affix = config.affixes[roll.affixId] ?: return false
        rolled[affix.id] = affix to roll.value * affix.scale
        return true
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
        return readAffixIds(item.itemMeta.persistentDataContainer)
    }

    private fun readAffixIds(pdc: PersistentDataContainer): List<String> {
        return pdc.get(affixesKey, PersistentDataType.STRING)
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
        // 外部临时词缀(副本强化等)：实时回调累加，从不持久化；只接受已知 affixId
        for (provider in externalProviders) {
            val bonus = runCatching { provider.bonus(player) }.getOrNull() ?: continue
            for ((affixId, value) in bonus) {
                if (value != 0.0 && config.affixes.containsKey(affixId)) {
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

    private fun format(value: Double, decimals: Int): String =
        com.dongzh1.sourceforge.util.AffixFormat.number(value, decimals)

    /** 词条数值：比例词条 ×100 加 %（小数位相应减 2），其余按 decimals；统一去掉尾随 0。与 MOD 卡口径一致
     *（实现共用 [com.dongzh1.sourceforge.util.AffixFormat]，避免两边各自维护一份格式化逻辑改漏）。 */
    private fun formatAffixValue(affix: AffixConfig, value: Double): String =
        com.dongzh1.sourceforge.util.AffixFormat.affixValue(affix.percent, affix.decimals, value)

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
