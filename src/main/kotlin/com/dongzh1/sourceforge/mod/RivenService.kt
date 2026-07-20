package com.dongzh1.sourceforge.mod

import com.dongzh1.sourceforge.SourceForge
import com.dongzh1.sourceforge.api.SourceForgeActionEvent
import com.dongzh1.sourceforge.config.AffixConfig
import com.dongzh1.sourceforge.item.CraftEngineHook
import com.dongzh1.sourceforge.relic.DreammarkRelicDefinition
import com.dongzh1.sourceforge.util.AffixFormat
import com.dongzh1.sourceforge.util.Text
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.persistence.PersistentDataType
import kotlin.math.abs
import kotlin.random.Random

data class RivenInstance(
    val weaponId: String,
    val groupId: String,
    val rank: Int,
    val disposition: Double,
    val rolls: Int,
    val positiveCount: Int,
    val hasNegative: Boolean,
    val affixes: Map<String, Double>,
    val baseDrain: Int? = null,
    val drainPerRank: Int? = null,
    val relicId: String? = null
)

enum class RivenRollResult {
    SUCCESS,
    INVALID_RIVEN,
    SEALED,
    PENDING_SELECTION,
    INSUFFICIENT_DREAM_CORE
}

class RivenService(
    private val plugin: SourceForge,
    private val config: RivenConfig,
    private val affixById: Map<String, AffixConfig>
) {
    private val markerKey = NamespacedKey(plugin, "riven")
    private val stateKey = NamespacedKey(plugin, "riven_state")
    private val groupKey = NamespacedKey(plugin, "riven_group")
    private val challengeKey = NamespacedKey(plugin, "riven_challenge")
    private val progressKey = NamespacedKey(plugin, "riven_progress")
    private val goalKey = NamespacedKey(plugin, "riven_goal")
    private val dataKey = NamespacedKey(plugin, "riven_data")
    private val veiledDataKey = NamespacedKey(plugin, "riven_veiled_data")
    private val pendingDataKey = NamespacedKey(plugin, "riven_pending")

    companion object {
        const val STATE_VEILED = "veiled"
        const val STATE_UNVEILED = "unveiled"
        private const val DATA_VERSION = "1"
    }

    fun groupIds(): Set<String> = config.groups.keys

    fun resolvesGroup(raw: String?): Boolean = config.resolveGroup(raw) != null

    fun isRiven(item: ItemStack?): Boolean {
        if (item == null || item.type == Material.AIR || !item.hasItemMeta()) return false
        return item.itemMeta.persistentDataContainer.has(markerKey, PersistentDataType.BYTE)
    }

    fun state(item: ItemStack?): String? {
        if (!isRiven(item)) return null
        return item!!.itemMeta.persistentDataContainer.get(stateKey, PersistentDataType.STRING)
    }

    fun isUnveiled(item: ItemStack?): Boolean = state(item) == STATE_UNVEILED

    fun parseData(item: ItemStack?): RivenInstance? {
        if (!isRiven(item)) return null
        val raw = item!!.itemMeta.persistentDataContainer.get(dataKey, PersistentDataType.STRING) ?: return null
        return parseDataString(raw)
    }

    private fun veiledInstance(item: ItemStack?): RivenInstance? {
        if (!isRiven(item)) return null
        val raw = item!!.itemMeta.persistentDataContainer.get(veiledDataKey, PersistentDataType.STRING) ?: return null
        return parseDataString(raw)
    }

    fun parseDataString(raw: String): RivenInstance? {
        val values = linkedMapOf<String, String>()
        raw.split("|").forEach { part ->
            val separator = part.indexOf('=')
            if (separator > 0) values[part.substring(0, separator)] = part.substring(separator + 1)
        }
        if (values["v"] != DATA_VERSION) return null
        val weaponId = values["weapon"]?.takeIf { it.isNotBlank() } ?: return null
        val groupId = values["group"]?.takeIf { it.isNotBlank() } ?: return null
        val affixes = linkedMapOf<String, Double>()
        values["stats"]?.split(',')?.forEach { pair ->
            val separator = pair.lastIndexOf(':')
            if (separator <= 0) return@forEach
            val value = pair.substring(separator + 1).toDoubleOrNull() ?: return@forEach
            affixes[pair.substring(0, separator)] = value
        }
        if (affixes.isEmpty()) return null
        return RivenInstance(
            weaponId = weaponId,
            groupId = groupId,
            rank = values["rank"]?.toIntOrNull()?.coerceIn(0, config.maxRank) ?: 0,
            disposition = values["disp"]?.toDoubleOrNull()?.coerceAtLeast(0.1) ?: 1.0,
            rolls = values["rolls"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
            positiveCount = values["pos"]?.toIntOrNull()?.coerceIn(1, 3) ?: affixes.values.count { it > 0.0 },
            hasNegative = values["neg"] == "1",
            affixes = affixes,
            baseDrain = values["drain-base"]?.toIntOrNull()?.coerceAtLeast(1),
            drainPerRank = values["drain-rank"]?.toIntOrNull()?.coerceAtLeast(0),
            relicId = values["relic"]?.takeIf { it.isNotBlank() }
        )
    }

    fun serialize(instance: RivenInstance): String {
        val stats = instance.affixes.entries.joinToString(",") { (id, value) -> "$id:$value" }
        val drainBase = instance.baseDrain?.let { "|drain-base=$it" }.orEmpty()
        val drainRank = instance.drainPerRank?.let { "|drain-rank=$it" }.orEmpty()
        val relic = instance.relicId?.takeIf { it.isNotBlank() }?.let { "|relic=$it" }.orEmpty()
        return "v=$DATA_VERSION|weapon=${instance.weaponId}|group=${instance.groupId}|rank=${instance.rank}|disp=${instance.disposition}|rolls=${instance.rolls}|pos=${instance.positiveCount}|neg=${if (instance.hasNegative) 1 else 0}$drainBase$drainRank$relic|stats=$stats"
    }

    fun createVeiled(groupInput: String? = null): ItemStack? {
        val group = config.resolveGroup(groupInput) ?: return null
        val challenge = config.challenges.values.randomOrNull() ?: return null
        return createVeiled(group, challenge, null)
    }

    fun createVeiled(definition: DreammarkRelicDefinition): ItemStack? {
        val weaponId = definition.rollWeaponId() ?: return null
        val equipment = plugin.forgeConfig.equipment[weaponId] ?: return null
        val group = config.resolveGroup(equipment.weaponCategory) ?: return null
        val challenge = definition.challengeId?.let { config.challenges[it] }
            ?: config.challenges.values.randomOrNull()
            ?: return null
        val instance = rollConfiguredInstance(weaponId, group, 0, 0, definition) ?: return null
        return createVeiled(group, challenge, instance)
    }

    private fun createVeiled(
        group: RivenGroupConfig,
        challenge: RivenChallengeConfig,
        configuredInstance: RivenInstance?
    ): ItemStack {
        val item = ItemStack(config.material, 1)
        val meta = item.itemMeta
        val pdc = meta.persistentDataContainer
        pdc.set(markerKey, PersistentDataType.BYTE, 1)
        pdc.set(stateKey, PersistentDataType.STRING, STATE_VEILED)
        pdc.set(groupKey, PersistentDataType.STRING, group.id)
        pdc.set(challengeKey, PersistentDataType.STRING, challenge.id)
        pdc.set(progressKey, PersistentDataType.INTEGER, 0)
        pdc.set(goalKey, PersistentDataType.INTEGER, challenge.goal)
        configuredInstance?.let { pdc.set(veiledDataKey, PersistentDataType.STRING, serialize(it)) }
        item.itemMeta = meta
        renderLore(item)
        return item
    }

    fun addKillProgress(event: EntityDeathEvent) {
        val player = event.entity.killer ?: return
        val storage = player.inventory.storageContents
        for (slot in storage.indices) {
            val item = storage[slot] ?: continue
            if (!isRiven(item) || state(item) != STATE_VEILED) continue
            val meta = item.itemMeta
            val pdc = meta.persistentDataContainer
            val group = config.resolveGroup(pdc.get(groupKey, PersistentDataType.STRING)) ?: continue
            val challenge = config.challenges[pdc.get(challengeKey, PersistentDataType.STRING)?.lowercase()]
                ?: config.challenges.values.firstOrNull()
                ?: continue
            if (!matchesChallenge(player, group, challenge, veiledInstance(item)?.weaponId)) continue
            val goal = pdc.get(goalKey, PersistentDataType.INTEGER)?.coerceAtLeast(1) ?: challenge.goal
            val progress = (pdc.get(progressKey, PersistentDataType.INTEGER) ?: 0) + 1
            if (progress >= goal) {
                item.itemMeta = meta
                if (unveil(item)) {
                    SourceForgeActionEvent(player, SourceForgeActionEvent.Action.DREAMMARK_UNVEILED, group.id).callEvent()
                    player.sendMessage("§5[彼端遗纹] §f遗纹已苏醒！")
                }
            } else {
                pdc.set(progressKey, PersistentDataType.INTEGER, progress)
                item.itemMeta = meta
                renderLore(item)
            }
            player.inventory.setItem(slot, item)
        }
    }

    fun unveil(item: ItemStack): Boolean {
        if (!isRiven(item) || state(item) != STATE_VEILED) return false
        val meta = item.itemMeta
        val pdc = meta.persistentDataContainer
        val group = config.resolveGroup(pdc.get(groupKey, PersistentDataType.STRING)) ?: return false
        val hasConfiguredInstance = pdc.has(veiledDataKey, PersistentDataType.STRING)
        val instance = if (hasConfiguredInstance) {
            val configured = pdc.get(veiledDataKey, PersistentDataType.STRING)?.let(::parseDataString) ?: return false
            val configuredEquipment = plugin.forgeConfig.equipment[configured.weaponId] ?: return false
            if (configuredEquipment.weaponCategory.lowercase() !in group.categories) return false
            configured.copy(groupId = group.id)
        } else {
            val candidates = plugin.forgeConfig.equipment.entries.filter { (_, equipment) -> equipment.weaponCategory.lowercase() in group.categories }
            val chosen = candidates.randomOrNull() ?: return false
            rollInstance(chosen.key, group, 0, 0) ?: return false
        }
        pdc.set(stateKey, PersistentDataType.STRING, STATE_UNVEILED)
        pdc.set(dataKey, PersistentDataType.STRING, serialize(instance))
        pdc.remove(veiledDataKey)
        pdc.remove(progressKey)
        pdc.remove(goalKey)
        pdc.remove(challengeKey)
        item.itemMeta = meta
        renderLore(item)
        return true
    }

    fun buildFromData(instance: RivenInstance): ItemStack {
        val item = ItemStack(config.material, 1)
        val meta = item.itemMeta
        val pdc = meta.persistentDataContainer
        pdc.set(markerKey, PersistentDataType.BYTE, 1)
        pdc.set(stateKey, PersistentDataType.STRING, STATE_UNVEILED)
        pdc.set(groupKey, PersistentDataType.STRING, instance.groupId)
        pdc.set(dataKey, PersistentDataType.STRING, serialize(instance))
        item.itemMeta = meta
        renderLore(item)
        return item
    }

    fun rank(item: ItemStack?): Int = parseData(item)?.rank ?: 0

    fun maxRank(): Int = config.maxRank

    fun cost(instance: RivenInstance): Int = (
        (instance.baseDrain ?: config.baseDrain) + instance.rank * (instance.drainPerRank ?: config.drainPerRank)
    ).coerceAtLeast(1)

    fun nextCost(instance: RivenInstance): Int? {
        if (instance.rank >= config.maxRank) return null
        return (
            (instance.baseDrain ?: config.baseDrain) +
                (instance.rank + 1) * (instance.drainPerRank ?: config.drainPerRank)
        ).coerceAtLeast(1)
    }

    fun rankUpgradeCost(rank: Int): Double = config.rankUpgradeCost(rank)

    fun effectiveAffixes(instance: RivenInstance, rank: Int = instance.rank): Map<String, Double> {
        val factor = (rank.coerceIn(0, config.maxRank) + 1).toDouble() / (config.maxRank + 1).toDouble()
        return instance.affixes.mapValues { (_, value) -> value * factor }
    }

    fun appliesTo(instance: RivenInstance, item: ItemStack?): Boolean =
        plugin.itemService.weaponType(item)?.equals(instance.weaponId, ignoreCase = true) == true

    fun hasPendingRoll(item: ItemStack?): Boolean =
        isRiven(item) && item!!.itemMeta.persistentDataContainer.has(pendingDataKey, PersistentDataType.STRING)

    fun rerollCost(item: ItemStack?): Int? = parseData(item)?.let { config.rerollCost(it.rolls) }

    fun reroll(player: Player, item: ItemStack): RivenRollResult {
        if (!isRiven(item)) return RivenRollResult.INVALID_RIVEN
        if (state(item) != STATE_UNVEILED) return RivenRollResult.SEALED
        if (hasPendingRoll(item)) return RivenRollResult.PENDING_SELECTION
        val current = parseData(item) ?: return RivenRollResult.INVALID_RIVEN
        val cost = config.rerollCost(current.rolls)
        val definition = current.relicId?.let { plugin.dreammarkRelics.get(it) }
        val candidate = if (definition != null) {
            val group = groupFor(current.weaponId, current.groupId) ?: return RivenRollResult.INVALID_RIVEN
            rollConfiguredInstance(
                current.weaponId,
                group,
                current.rank,
                current.rolls + 1,
                definition,
                current.baseDrain,
                current.drainPerRank
            ) ?: return RivenRollResult.INVALID_RIVEN
        } else {
            val group = config.resolveGroup(current.groupId) ?: return RivenRollResult.INVALID_RIVEN
            rollInstance(current.weaponId, group, current.rank, current.rolls + 1, current.disposition)
                ?: return RivenRollResult.INVALID_RIVEN
        }
        if (!plugin.itemService.consumeFromInventory(player, config.dreamCoreItemId, cost)) return RivenRollResult.INSUFFICIENT_DREAM_CORE
        val meta = item.itemMeta
        meta.persistentDataContainer.set(pendingDataKey, PersistentDataType.STRING, serialize(candidate))
        item.itemMeta = meta
        renderLore(item)
        return RivenRollResult.SUCCESS
    }

    fun acceptPending(item: ItemStack): Boolean {
        val meta = item.itemMeta
        val pdc = meta.persistentDataContainer
        val pending = pdc.get(pendingDataKey, PersistentDataType.STRING)?.let(::parseDataString) ?: return false
        pdc.set(dataKey, PersistentDataType.STRING, serialize(pending))
        pdc.remove(pendingDataKey)
        item.itemMeta = meta
        renderLore(item)
        return true
    }

    fun keepCurrent(item: ItemStack): Boolean {
        if (!hasPendingRoll(item)) return false
        val meta = item.itemMeta
        meta.persistentDataContainer.remove(pendingDataKey)
        item.itemMeta = meta
        renderLore(item)
        return true
    }

    fun withRank(item: ItemStack, targetRank: Int): ItemStack? {
        if (hasPendingRoll(item)) return null
        val instance = parseData(item) ?: return null
        val meta = item.itemMeta
        meta.persistentDataContainer.set(dataKey, PersistentDataType.STRING, serialize(instance.copy(rank = targetRank.coerceIn(0, config.maxRank))))
        item.itemMeta = meta
        renderLore(item)
        return item
    }

    fun renderLore(item: ItemStack) {
        if (!isRiven(item)) return
        val meta = item.itemMeta
        if (state(item) == STATE_UNVEILED) renderUnveiledLore(meta, item) else renderVeiledLore(meta, item)
        item.itemMeta = meta
    }

    private fun rollConfiguredInstance(
        weaponId: String,
        group: RivenGroupConfig,
        rank: Int,
        rolls: Int,
        definition: DreammarkRelicDefinition,
        baseDrain: Int? = definition.baseDrain,
        drainPerRank: Int? = definition.drainPerRank
    ): RivenInstance? {
        val affixes = definition.rollAffixes()
        if (affixes.isEmpty() || affixes.keys.any { it !in affixById }) return null
        val positiveCount = affixes.values.count { it > 0.0 }
        val hasNegative = affixes.values.any { it < 0.0 }
        if (positiveCount !in 1..3 || affixes.values.count { it < 0.0 } > 1) return null
        return RivenInstance(
            weaponId = weaponId,
            groupId = group.id,
            rank = rank.coerceIn(0, config.maxRank),
            disposition = 1.0,
            rolls = rolls.coerceAtLeast(0),
            positiveCount = positiveCount,
            hasNegative = hasNegative,
            affixes = affixes,
            baseDrain = baseDrain?.coerceAtLeast(1),
            drainPerRank = drainPerRank?.coerceAtLeast(0),
            relicId = definition.relicId
        )
    }

    private fun groupFor(weaponId: String, preferredGroupId: String): RivenGroupConfig? {
        return config.resolveGroup(preferredGroupId)
            ?: plugin.forgeConfig.equipment[weaponId]?.weaponCategory?.let { config.resolveGroup(it) }
    }

    private fun rollInstance(
        weaponId: String,
        group: RivenGroupConfig,
        rank: Int,
        rolls: Int,
        dispositionOverride: Double? = null
    ): RivenInstance? {
        val entries = group.pool.values.filter { it.positive > 0.0 && it.affixId in affixById }
        if (entries.size < config.positiveMin) return null
        val positiveCount = Random.nextInt(config.positiveMin, config.positiveMax + 1).coerceAtMost(entries.size)
        val selected = entries.shuffled().take(positiveCount)
        val negativeEntry = if (Random.nextDouble() < config.negativeChance) {
            entries.filter { it.canBeNegative && selected.none { selectedEntry -> selectedEntry.affixId == it.affixId } }.randomOrNull()
        } else null
        val hasNegative = negativeEntry != null
        val positiveMultiplier = positiveMultiplier(positiveCount, hasNegative)
        val negativeMultiplier = negativeMultiplier(positiveCount, hasNegative)
        val category = plugin.forgeConfig.equipment[weaponId]?.weaponCategory
        val disposition = dispositionOverride ?: config.dispositionFor(weaponId, category)
        val affixes = linkedMapOf<String, Double>()
        selected.forEach { entry ->
            val scale = affixById[entry.affixId]?.scale ?: 1.0
            affixes[entry.affixId] = entry.positive * scale * disposition * positiveMultiplier * Random.nextDouble(0.9, 1.1)
        }
        negativeEntry?.let { entry ->
            val scale = affixById[entry.affixId]?.scale ?: 1.0
            affixes[entry.affixId] = -(entry.negative ?: entry.positive) * scale * disposition * negativeMultiplier * Random.nextDouble(0.9, 1.1)
        }
        return RivenInstance(
            weaponId = weaponId,
            groupId = group.id,
            rank = rank.coerceIn(0, config.maxRank),
            disposition = disposition,
            rolls = rolls.coerceAtLeast(0),
            positiveCount = positiveCount,
            hasNegative = hasNegative,
            affixes = affixes
        )
    }

    private fun matchesChallenge(
        player: Player,
        group: RivenGroupConfig,
        challenge: RivenChallengeConfig,
        requiredWeaponId: String?
    ): Boolean {
        val heldWeapon = player.inventory.itemInMainHand
        val category = plugin.itemService.weaponCategory(heldWeapon)?.lowercase() ?: return false
        if (requiredWeaponId != null) {
            if (!plugin.itemService.weaponType(heldWeapon).equals(requiredWeaponId, ignoreCase = true)) return false
        } else if (category !in group.categories) {
            return false
        }
        return when (challenge.type) {
            "kill" -> true
            "sneak_kill" -> player.isSneaking
            "airborne_kill" -> !player.isOnGround
            "melee_kill" -> category in MELEE_CATEGORIES
            "ranged_kill" -> category in RANGED_CATEGORIES
            else -> false
        }
    }

    private fun renderVeiledLore(meta: ItemMeta, item: ItemStack) {
        val pdc = meta.persistentDataContainer
        val group = config.resolveGroup(pdc.get(groupKey, PersistentDataType.STRING))
        val challenge = config.challenges[pdc.get(challengeKey, PersistentDataType.STRING)?.lowercase()]
        val goal = pdc.get(goalKey, PersistentDataType.INTEGER)?.coerceAtLeast(1) ?: challenge?.goal ?: 1
        val progress = (pdc.get(progressKey, PersistentDataType.INTEGER) ?: 0).coerceIn(0, goal)
        val configured = veiledInstance(item)
        val weaponName = configured?.let { plugin.forgeConfig.equipmentDisplayName(it.weaponId) }
        Text.name(meta, "&5${weaponName ?: "彼端遗纹"} &8· &7封缄")
        val lines = mutableListOf(
            "&8◆ &5彼端遗纹  &8│  &7封缄",
            "&8◆ &7适配装备  &f${weaponName ?: group?.displayName ?: "未知"}",
            "&8◆ &7解封试炼  &f${challenge?.displayText() ?: "完成试炼"}",
            "&8◆ &7试炼进度  &f$progress&7/&f$goal"
        )
        if (configured != null) {
            lines += ""
            lines += "&8◆ &7苏醒后占用  &e${cost(configured)}"
            lines += ""
            lines += "&d✦ 封缄词条"
            configured.affixes.forEach { (affixId, value) -> lines += candidateEffectLine(affixId, value) }
        }
        lines += ""
        lines += if (configured == null) "&7手持对应类型武器完成试炼后自动苏醒 &8(无需右键)" else "&7手持上述装备完成试炼后自动苏醒 &8(无需右键)"
        Text.lore(meta, lines)
    }

    private fun renderUnveiledLore(meta: ItemMeta, item: ItemStack) {
        val instance = parseData(item) ?: return
        val weaponName = plugin.forgeConfig.equipmentDisplayName(instance.weaponId)
        Text.name(meta, "&5$weaponName &d${rivenTitle(instance)}")
        val lines = mutableListOf(
            "&8◆ &5彼端遗纹  &8│  &7占用 &e${cost(instance)}",
            "&8◆ &7绑定武器  &f$weaponName",
            "&8◆ &7共鸣度  &d${dispositionStars(instance.disposition)} &7×&f${formatNumber(instance.disposition, 2)}",
            rankLine(instance),
            "&8◆ &7梦织次数  &f${instance.rolls}"
        )
        lines += ""
        lines += "&d✦ 遗纹属性"
        instance.affixes.keys.forEach { affixId -> lines += effectLine(instance, affixId) }
        pendingInstance(item)?.let { candidate ->
            lines += ""
            lines += "&6✦ 梦织候选"
            effectiveAffixes(candidate).forEach { (affixId, value) -> lines += candidateEffectLine(affixId, value) }
            lines += "  &8▪ &a/sf dreammark accept &7接受候选"
            lines += "  &8▪ &c/sf dreammark keep &7保留当前"
        }
        lines += ""
        lines += "&7适用装备  &f$weaponName"
        Text.lore(meta, lines)
    }

    private fun pendingInstance(item: ItemStack): RivenInstance? =
        item.itemMeta.persistentDataContainer.get(pendingDataKey, PersistentDataType.STRING)?.let(::parseDataString)

    private fun rankLine(instance: RivenInstance): String {
        val rank = instance.rank.coerceIn(0, config.maxRank)
        return if (rank >= config.maxRank) {
            "&8◆ &7段位  &6${"◆".repeat(config.maxRank)} &7MAX"
        } else {
            "&8◆ &7段位  &b${"◆".repeat(rank)}&8${"◇".repeat(config.maxRank - rank)} &7$rank/${config.maxRank}"
        }
    }

    private fun effectLine(instance: RivenInstance, affixId: String): String {
        val current = effectiveAffixes(instance)[affixId] ?: 0.0
        val next = if (instance.rank < config.maxRank) effectiveAffixes(instance, instance.rank + 1)[affixId] else null
        val affix = affixById[affixId]
        val name = affix?.displayName ?: affixId
        val color = affix?.color ?: "&f"
        val currentText = signed(affix, current)
        return if (next == null) {
            "  &8▪ &7$name  $color$currentText &8MAX"
        } else {
            "  &8▪ &7$name  $color$currentText &8→ &a${signed(affix, next)}"
        }
    }

    private fun candidateEffectLine(affixId: String, value: Double): String {
        val affix = affixById[affixId]
        val name = affix?.displayName ?: affixId
        val color = affix?.color ?: "&f"
        return "  &8▪ &7$name  $color${signed(affix, value)}"
    }

    private fun rivenTitle(instance: RivenInstance): String {
        return instance.affixes.filterValues { it > 0.0 }.keys.take(2)
            .map { affixById[it]?.displayName ?: it }
            .joinToString("·")
            .ifBlank { "未知" }
    }

    private fun dispositionStars(value: Double): String {
        val count = when {
            value >= 1.25 -> 5
            value >= 1.1 -> 4
            value >= 0.9 -> 3
            value >= 0.7 -> 2
            else -> 1
        }
        return "●".repeat(count) + "○".repeat(5 - count)
    }

    private fun signed(affix: AffixConfig?, value: Double): String {
        val text = AffixFormat.affixValue(affix?.percent == true, affix?.decimals ?: 1, abs(value))
        return if (value < 0.0) "-$text" else "+$text"
    }

    private fun formatNumber(value: Double, decimals: Int): String = AffixFormat.number(value, decimals)

    private fun positiveMultiplier(positiveCount: Int, hasNegative: Boolean): Double = when {
        positiveCount == 2 && hasNegative -> 1.243
        positiveCount == 3 && hasNegative -> 0.942
        positiveCount == 3 -> 0.755
        else -> 1.0
    }

    private fun negativeMultiplier(positiveCount: Int, hasNegative: Boolean): Double = when {
        !hasNegative -> 0.0
        positiveCount == 2 -> 0.5
        else -> 0.755
    }

    private val MELEE_CATEGORIES = setOf("melee_light", "melee_heavy", "polearm")
    private val RANGED_CATEGORIES = setOf("bow", "crossbow", "firearm")
}
