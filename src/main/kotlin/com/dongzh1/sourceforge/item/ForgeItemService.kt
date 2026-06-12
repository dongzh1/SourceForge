package com.dongzh1.sourceforge.item

import com.dongzh1.sourceforge.SourceForge
import com.dongzh1.sourceforge.config.AffixConfig
import com.dongzh1.sourceforge.config.AffixRollConfig
import com.dongzh1.sourceforge.config.BlueprintConfig
import com.dongzh1.sourceforge.config.EquipmentConfig
import com.dongzh1.sourceforge.config.ForgeConfig
import com.dongzh1.sourceforge.config.ForgeMaterialConfig
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
import kotlin.random.Random

class ForgeItemService(
    private val plugin: SourceForge,
    private val config: ForgeConfig
) {
    private val blueprintKey = NamespacedKey(plugin, "blueprint")
    private val blueprintTierKey = NamespacedKey(plugin, "blueprint_tier")
    private val blueprintTierMinKey = NamespacedKey(plugin, "blueprint_tier_min")
    private val blueprintTierMaxKey = NamespacedKey(plugin, "blueprint_tier_max")
    private val typeKey = NamespacedKey(plugin, "type")
    private val categoryKey = NamespacedKey(plugin, "weapon_category")
    private val tierKey = NamespacedKey(plugin, "tier")
    private val affixesKey = NamespacedKey(plugin, "affixes")
    private val projectileMarkerKey = NamespacedKey(plugin, "projectile_source")
    private val scoreKey = NamespacedKey(plugin, "score")
    private val chunkWorldLevelKey = NamespacedKey("chunkworld", "level")
    private val pixelShopPriceKey = NamespacedKey("pixelshop", "price")


    fun createBlueprint(blueprint: BlueprintConfig, tierRange: IntRange = blueprint.defaultTierRange(), amount: Int = 1): ItemStack {
        val item = config.blueprintItem.ceId?.let { CraftEngineHook.build(it, amount.coerceAtLeast(1)) }
            ?: ItemStack(config.blueprintItem.material, amount.coerceAtLeast(1))
        val meta = item.itemMeta
        val normalizedRange = normalizeTierRange(tierRange, blueprint.tierMin, blueprint.tierMax)
        val tierText = formatTierRange(normalizedRange)
        meta.setDisplayName(color(config.blueprintItem.nameFormat.replace("%name%", blueprint.displayName)))
        config.blueprintItem.customModelData?.let { meta.setCustomModelData(it) }
        val lore = config.blueprintItem.lore.map { line ->
            line.replace("%blueprint%", blueprint.displayName)
                .replace("%tier%", tierText)
                .replace("%equipment_type%", config.equipmentDisplayName(blueprint.equipmentType))
                .replace("%materials%", blueprint.requirements.joinToString(", ") { "${config.displayName(it.id)} x${it.amount}" })
        }
        meta.lore = color(lore)
        meta.persistentDataContainer.set(blueprintKey, PersistentDataType.STRING, blueprint.id)
        meta.persistentDataContainer.set(blueprintTierKey, PersistentDataType.INTEGER, normalizedRange.first)
        meta.persistentDataContainer.set(blueprintTierMinKey, PersistentDataType.INTEGER, normalizedRange.first)
        meta.persistentDataContainer.set(blueprintTierMaxKey, PersistentDataType.INTEGER, normalizedRange.last)
        item.itemMeta = meta
        return item
    }

    fun blueprintId(item: ItemStack?): String? {
        if (item == null || item.type == Material.AIR || !item.hasItemMeta()) return null
        return item.itemMeta.persistentDataContainer.get(blueprintKey, PersistentDataType.STRING)
    }

    fun blueprintTier(item: ItemStack?): Int {
        if (item == null || !item.hasItemMeta()) return 1
        return item.itemMeta.persistentDataContainer.get(blueprintTierKey, PersistentDataType.INTEGER) ?: 1
    }

    fun blueprintTierRange(item: ItemStack?, blueprint: BlueprintConfig): IntRange {
        if (item == null || !item.hasItemMeta()) return blueprint.defaultTierRange()
        val pdc = item.itemMeta.persistentDataContainer
        val min = pdc.get(blueprintTierMinKey, PersistentDataType.INTEGER)
        val max = pdc.get(blueprintTierMaxKey, PersistentDataType.INTEGER)
        if (min != null && max != null) {
            return normalizeTierRange(min..max, blueprint.tierMin, blueprint.tierMax)
        }
        val legacy = pdc.get(blueprintTierKey, PersistentDataType.INTEGER)
        return if (legacy != null) {
            normalizeTierRange(legacy..legacy, blueprint.tierMin, blueprint.tierMax)
        } else {
            blueprint.defaultTierRange()
        }
    }

    fun createEquipment(
        blueprint: BlueprintConfig,
        equipment: EquipmentConfig,
        tier: Int,
        materials: List<ForgeMaterialConfig>
    ): ItemStack {
        return createEquipment(equipment, tier, blueprint.maxAffixes, materials)
    }

    fun createEquipment(
        equipment: EquipmentConfig,
        tier: Int,
        maxAffixes: Int,
        materials: List<ForgeMaterialConfig>
    ): ItemStack {
        val base = equipment.ceId?.let { CraftEngineHook.build(it, 1) } ?: ItemStack(equipment.material, 1)
        val selected = rollAffixes(equipment, tier, maxAffixes, materials)
        writeEquipment(base, equipment, tier, selected)
        return base
    }

    fun createDirectEquipment(equipmentId: String, tier: Int, affixes: Int? = null): ItemStack? {
        val equipment = config.equipment[equipmentId] ?: return null
        val normalizedTier = tier.coerceAtLeast(1)
        val blueprint = config.blueprints.values.firstOrNull { it.equipmentType == equipment.id }
        val maxAffixes = affixes ?: blueprint?.maxAffixes ?: maxOf(1, equipment.tierAffixes[normalizedTier]?.size ?: 1)
        return createEquipment(equipment, normalizedTier, maxAffixes.coerceAtLeast(0), emptyList())
    }

    fun rerollEquipment(item: ItemStack): Boolean {
        val equipment = config.equipment[weaponType(item)] ?: return false
        val tier = equipmentTier(item)
        val blueprint = config.blueprints.values.firstOrNull { it.equipmentType == equipment.id }
        val maxAffixes = blueprint?.maxAffixes ?: maxOf(1, readAffixIds(item).size)
        val selected = rollAffixes(equipment, tier, maxAffixes, emptyList())
        clearAffixes(item)
        writeEquipment(item, equipment, tier, selected)
        return true
    }

    fun upgradeEquipment(item: ItemStack): Boolean {
        val equipment = config.equipment[weaponType(item)] ?: return false
        val blueprint = config.blueprints.values.firstOrNull { it.equipmentType == equipment.id }
        val maxTier = blueprint?.tierMax ?: 5
        val current = equipmentTier(item)
        if (current >= maxTier) return false
        val maxAffixes = blueprint?.maxAffixes ?: maxOf(1, readAffixIds(item).size)
        val selected = rollAffixes(equipment, current + 1, maxAffixes, emptyList())
        clearAffixes(item)
        writeEquipment(item, equipment, current + 1, selected)
        return true
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

    fun buildExpression(expression: String, player: Player?, amount: Int): ItemStack? {
        val expr = SourceForgeExpression.parse(expression) ?: return null
        return when (expr.kind) {
            "blueprint" -> {
                val blueprint = config.blueprints[expr.id] ?: return null
                val tierRange = parseTierRange(expr.params["tier"], blueprint.defaultTierRange(), blueprint.tierMin, blueprint.tierMax)
                createBlueprint(blueprint, tierRange, amount)
            }
            "equipment" -> {
                val equipment = config.equipment[expr.id] ?: return null
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
        val key = NamespacedKey(plugin, affix.pdcKey)
        return when (affix.valueType) {
            "int", "integer" -> container.get(key, PersistentDataType.INTEGER)?.toDouble() ?: 0.0
            "string" -> container.get(key, PersistentDataType.STRING)?.toDoubleOrNull() ?: 0.0
            else -> container.get(key, PersistentDataType.DOUBLE) ?: 0.0
        }
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
        pdc.set(projectileMarkerKey, PersistentDataType.BYTE, 1)
        pdc.set(typeKey, PersistentDataType.STRING, weaponType(weapon) ?: "ranged")
        pdc.set(categoryKey, PersistentDataType.STRING, weaponCategory(weapon) ?: "ranged")
        pdc.set(tierKey, PersistentDataType.INTEGER, weaponPdc?.get(tierKey, PersistentDataType.INTEGER) ?: 1)
        for (affixId in config.affixes.keys) {
            val value = readAffixValue(weapon, affixId)
            if (value <= 0.0) continue
            val affix = config.affixes[affixId] ?: continue
            val key = NamespacedKey(plugin, affix.pdcKey)
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
        maxAffixes: Int,
        materials: List<ForgeMaterialConfig>
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
        materials.forEach { material ->
            material.affixes.forEach { candidates += AffixCandidate(it, material.id) }
        }

        val rolled = linkedMapOf<String, Pair<AffixConfig, Double>>()
        val contributedSources = mutableSetOf<String>()
        for (candidate in candidates) {
            if (!passesChance(candidate.roll.chance)) continue
            if (applyCandidate(candidate, rolled)) {
                contributedSources += candidate.source
            }
        }
        if (config.forge.guaranteeMaterialAffix) {
            for (material in materials) {
                if (material.id in contributedSources) continue
                val fallback = candidates
                    .filter { it.source == material.id }
                    .shuffled()
                    .firstOrNull { applyCandidate(it, rolled) }
                if (fallback != null) {
                    contributedSources += material.id
                }
            }
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
        val key = NamespacedKey(plugin, affix.pdcKey)
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
            pdc.remove(NamespacedKey(plugin, affix.pdcKey))
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
        return effectiveSourceItems(player).sumOf { readAffixValue(it, affixId) }
    }

    fun readDisplayTotalAffix(player: Player, affixId: String): Double {
        val total = readTotalAffix(player, affixId)
        return when (affixId) {
            "ability_strength", "ability_duration", "ability_range" -> 1.0 + total
            "shield" -> 10.0 + total
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
