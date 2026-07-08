package com.dongzh1.sourceforge.mod

import com.dongzh1.sourceforge.SourceForge
import com.dongzh1.sourceforge.config.AffixConfig
import com.dongzh1.sourceforge.config.ForgeConfig
import com.dongzh1.sourceforge.item.CraftEngineHook
import com.dongzh1.sourceforge.util.Text
import com.dongzh1.sourceforge.util.color
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.persistence.PersistentDataType
import java.text.DecimalFormat

class ModService(
    private val plugin: SourceForge,
    private val forgeConfig: ForgeConfig,
    val mods: Map<String, ModConfig>
) {
    private val modCapacityKey = ModKeys.modCapacity(plugin)
    private val modInstalledKey = NamespacedKey(plugin, "mod_installed")
    private val modIdKey = NamespacedKey(plugin, "mod_id")

    /** MOD 物品上的段位（Feature B）。安装后段位也编码进 mod_installed 的 token (`id#rank`)。 */
    private val modRankKey = NamespacedKey(plugin, "mod_rank")

    /** 梦魇MOD 占用某槽位时，mod_installed 中的占位 token。 */
    private val nmToken = "~nm"

    /** 每个槽位的梦魇MOD 实例数据 (nm_data 字符串)，仅当该槽 token == ~nm 时有效。 */
    private val nmSlotKeys: List<NamespacedKey> = (0 until 8).map { NamespacedKey(plugin, "nm_slot_$it") }

    /** 技能槽（独立于 8 个普通MOD槽）的安装记录：逗号分隔的技能MOD id。 */
    private val modSkillInstalledKey = NamespacedKey(plugin, "mod_skill_installed")

    /** 技能槽位最大数量上限（= 触发栏总数 6；GUI 与存储都按此上限）。实际可用数由 skillSlotCount 决定。 */
    private val maxSkillSlots = TriggerSlot.COUNT

    /** MM 物品身份桥：安装技能MOD 时给装备盖 mythicmobs:type/version。 */
    private val mythicHook = MythicItemHook(plugin)

    /** 该装备可用的技能触发栏数量（config: mods.skill-slots，默认 = 触发栏总数 6）。 */
    fun skillSlotCount(@Suppress("UNUSED_PARAMETER") item: ItemStack? = null): Int =
        plugin.config.getInt("mods.skill-slots", TriggerSlot.COUNT).coerceIn(0, maxSkillSlots)

    /** 读取技能槽（长度 = maxSkillSlots，未占用为 null）。 */
    fun readSkillSlots(item: ItemStack?): List<String?> {
        val raw = if (item == null || !item.hasItemMeta()) null
        else item.itemMeta.persistentDataContainer.get(modSkillInstalledKey, PersistentDataType.STRING)
        val tokens = (raw ?: "").split(",")
        return (0 until maxSkillSlots).map { tokens.getOrNull(it)?.trim()?.takeIf { t -> t.isNotEmpty() } }
    }

    private fun writeSkillSlots(meta: ItemMeta, slots: List<String?>) {
        val padded = (0 until maxSkillSlots).map { slots.getOrNull(it) ?: "" }
        meta.persistentDataContainer.set(modSkillInstalledKey, PersistentDataType.STRING, padded.joinToString(","))
    }

    /** 该 MOD 是否为技能MOD。 */
    fun isSkillMod(item: ItemStack?): Boolean = modConfig(item)?.skill == true

    /** 装备上已安装的技能MOD id 集合。 */
    fun installedSkillModIds(item: ItemStack?): Set<String> =
        readSkillSlots(item).filterNotNull().toSet()

    /** 取某触发栏上安装的技能MOD id（无则 null）。供 SkillModListener 按玩家操作路由到对应技能。 */
    fun skillModAtTrigger(item: ItemStack?, trigger: TriggerSlot): String? =
        readSkillSlots(item).getOrNull(trigger.index)?.trim()?.takeIf { it.isNotEmpty() }

    /** 当前装备应盖的 MM 物品身份：取第一个占用的技能槽对应 MOD 的 mm-item（每件装备只能有一个身份）。 */
    private fun activeMmIdentity(item: ItemStack?): String? =
        readSkillSlots(item).filterNotNull().firstNotNullOfOrNull { mods[it]?.mmItem }

    /** affixId -> AffixConfig 直接查表，热路径复用。 */
    private val affixById: Map<String, AffixConfig> = forgeConfig.affixes

    /** affixId -> mod_delta_<pdcKey> NamespacedKey，避免反复构造。 */
    private val modDeltaKeys: Map<String, NamespacedKey> =
        ModKeys.modDeltaKeys(plugin, forgeConfig.affixes.values)

    /** 装备 lore「改造」分区的锚点行；reapplyModEffects 以此切开基础段与改造段并重建后者。 */
    private val marker = color("&b● 改造&7:")

    /** 旧版锚点（曾用 `---- 改造 ----`）；已发出的装备迁移时也要能识别并剥离，避免重复叠加。 */
    private val legacyMarker = color("&7---- 改造 ----")

    private fun isModMarker(line: String): Boolean = line == marker || line == legacyMarker

    /** MOD 卡统一排版构建器（yml 无 item-lore 时的默认排版）。 */
    private val loreBuilder = ModLoreBuilder(plugin, forgeConfig)

    enum class InstallResult {
        SUCCESS,
        CAPACITY_EXCEEDED,
        WRONG_CATEGORY,
        MAX_COUNT_EXCEEDED,
        EXCLUSIVITY_CONFLICT,
        SLOT_OCCUPIED,
        INVALID_MOD,
        NOT_EQUIPMENT,
        SEALED_NIGHTMARE,
        /** 把普通MOD 放进了技能槽。 */
        SKILL_SLOT_REQUIRED,
        /** 把技能MOD 放进了普通MOD槽。 */
        SKILL_MOD_WRONG_SLOT,
        /** 技能MOD 的 allowed-triggers 不允许放进该触发栏。 */
        TRIGGER_NOT_ALLOWED
    }

    fun allModIds(): Set<String> = mods.keys

    /** MOD 的中文显示名(去色)；无则回退 id。供 /sf givemod 中文显示。 */
    fun modDisplayName(id: String): String {
        val cfg = mods[id] ?: return id
        return org.bukkit.ChatColor.stripColor(color(cfg.displayName))?.takeIf { it.isNotBlank() } ?: id
    }

    /** 把查询(中文显示名 或 英文id，大小写不敏感)解析成 modId；找不到返回 null。 */
    fun resolveModId(query: String): String? {
        val q = query.trim()
        if (q.isEmpty()) return null
        mods.keys.firstOrNull { it.equals(q, true) }?.let { return it }       // 英文 id
        return mods.keys.firstOrNull { modDisplayName(it).equals(q, true) }   // 中文显示名
    }

    /** givemod 补全建议：直接用配置里的中文 display-name(无空格时)，否则回退 id。 */
    fun modSuggestions(): List<String> = mods.keys.map { id ->
        val disp = modDisplayName(id)
        if (disp.isNotBlank() && !disp.contains(' ')) disp else id
    }

    /** 槽位 token 是否为梦魇MOD 占位。 */
    fun isNightmareSlot(token: String?): Boolean = token == nmToken

    /** 读取某个 ~nm 槽位的梦魇MOD 实例数据字符串（无则 null）。 */
    fun nightmareSlotData(item: ItemStack?, slotIndex: Int): String? {
        if (item == null || !item.hasItemMeta() || slotIndex !in 0 until 8) return null
        return item.itemMeta.persistentDataContainer.get(nmSlotKeys[slotIndex], PersistentDataType.STRING)
    }

    fun isModItem(item: ItemStack?): Boolean {
        if (item == null || item.type == Material.AIR || !item.hasItemMeta()) return false
        return item.itemMeta.persistentDataContainer.has(modIdKey, PersistentDataType.STRING)
    }

    fun modId(item: ItemStack?): String? {
        if (item == null || item.type == Material.AIR || !item.hasItemMeta()) return null
        return item.itemMeta.persistentDataContainer.get(modIdKey, PersistentDataType.STRING)
    }

    fun modConfig(item: ItemStack?): ModConfig? {
        val id = modId(item) ?: return null
        return mods[id]
    }

    /** 读取 MOD 物品上的段位（无则 0）。 */
    fun modRank(item: ItemStack?): Int {
        if (item == null || item.type == Material.AIR || !item.hasItemMeta()) return 0
        return item.itemMeta.persistentDataContainer.get(modRankKey, PersistentDataType.INTEGER) ?: 0
    }

    /**
     * 解析普通槽位 token `<id>` 或 `<id>#<rank>` -> (id, rank)。
     * 梦魇 token `~nm` 不走这里。无效返回 null。
     */
    fun parseSlotToken(token: String?): Pair<String, Int>? {
        if (token.isNullOrBlank() || token == nmToken) return null
        val hash = token.indexOf('#')
        return if (hash < 0) {
            token to 0
        } else {
            val id = token.substring(0, hash)
            val rank = token.substring(hash + 1).toIntOrNull() ?: 0
            if (id.isBlank()) null else id to rank
        }
    }

    private fun slotToken(id: String, rank: Int): String =
        if (rank > 0) "$id#$rank" else id

    fun createModItem(id: String, amount: Int = 1, rank: Int = 0): ItemStack? {
        val mod = mods[id] ?: return null
        val r = rank.coerceIn(0, mod.maxRank)
        val item = CraftEngineHook.build(mod.itemId, amount.coerceAtLeast(1))
            ?: ItemStack(mod.material, amount.coerceAtLeast(1))
        val meta = item.itemMeta
        Text.name(meta, mod.displayName)
        val effectValue: (String) -> Double = { affixId -> mod.effectAtRank(affixId, r) }
        val rankLine = if (mod.maxRank > 0) "&7段位: &b$r&7/&f${mod.maxRank}" else null
        val loreLines = if (mod.itemLore.isNotEmpty()) {
            // 旧式手写 item-lore：保留占位符替换逻辑，向后兼容未迁移的服务器配置。
            val base = mod.itemLore.map { line ->
                var l = line.replace("%cost%", mod.cost.toString())
                    .replace("%rank%", r.toString())
                    .replace("%max_rank%", mod.maxRank.toString())
                for (affixId in mod.effects.keys) {
                    val affix = affixById[affixId]
                    val formatted = if (affix != null) format(effectValue(affixId), affix.decimals) else format(effectValue(affixId), 1)
                    l = l.replace("%effect_$affixId%", formatted)
                }
                l
            }.toMutableList()
            if (rankLine != null && base.none { it.contains("%rank%") || it.contains("段位") }) {
                base += rankLine
            }
            base
        } else {
            loreBuilder.build(mod, r)
        }
        meta.lore = color(loreLines)
        mod.customModelData?.let { meta.setCustomModelData(it) }
        meta.persistentDataContainer.set(modIdKey, PersistentDataType.STRING, id)
        if (mod.maxRank > 0) {
            meta.persistentDataContainer.set(modRankKey, PersistentDataType.INTEGER, r)
        }
        item.itemMeta = meta
        return item
    }

    fun readInstalledSlots(item: ItemStack?): List<String?> {
        val raw = if (item == null || !item.hasItemMeta()) null
        else item.itemMeta.persistentDataContainer.get(modInstalledKey, PersistentDataType.STRING)
        val tokens = (raw ?: "").split(",")
        val slots = MutableList<String?>(8) { null }
        for (i in 0 until 8) {
            val token = tokens.getOrNull(i)?.trim()
            slots[i] = if (token.isNullOrBlank()) null else token
        }
        return slots
    }

    /** 该物品上已安装的（普通）MOD id 集合。供技能MOD判断装备是否带某个技能。 */
    fun installedModIds(item: ItemStack?): Set<String> {
        val result = HashSet<String>()
        for (token in readInstalledSlots(item)) {
            if (token == null || token == nmToken) continue
            parseSlotToken(token)?.let { result.add(it.first) }
        }
        return result
    }

    private fun writeInstalledSlots(meta: ItemMeta, slots: List<String?>) {
        val padded = (0 until 8).map { slots.getOrNull(it) ?: "" }
        meta.persistentDataContainer.set(modInstalledKey, PersistentDataType.STRING, padded.joinToString(","))
    }

    fun usedCapacity(item: ItemStack?): Int {
        if (item == null) return 0
        val slots = readInstalledSlots(item)
        val pdc = if (item.hasItemMeta()) item.itemMeta.persistentDataContainer else null
        var total = 0
        for (i in slots.indices) {
            val token = slots[i] ?: continue
            total += if (token == nmToken) {
                val data = pdc?.get(nmSlotKeys[i], PersistentDataType.STRING)
                data?.let { plugin.nightmareService.parseDataString(it)?.cost } ?: 0
            } else {
                val (id, _) = parseSlotToken(token) ?: continue
                mods[id]?.cost ?: 0
            }
        }
        return total
    }

    fun readCapacity(item: ItemStack): Int {
        val meta = item.itemMeta
        val existing = meta.persistentDataContainer.get(modCapacityKey, PersistentDataType.INTEGER)
        if (existing != null) return existing
        val category = plugin.itemService.weaponCategory(item) ?: "default"
        val tier = plugin.itemService.equipmentTier(item)
        val computed = forgeConfig.modCapacity.computeCapacity(category, tier)
        meta.persistentDataContainer.set(modCapacityKey, PersistentDataType.INTEGER, computed)
        item.itemMeta = meta
        return computed
    }

    fun maxSlots(item: ItemStack): Int =
        forgeConfig.modCapacity.computeMaxSlots(plugin.itemService.weaponCategory(item) ?: "default")

    fun validateInstall(item: ItemStack, mod: ModConfig, targetSlot: Int): InstallResult {
        val category = plugin.itemService.weaponCategory(item)
        val equipId = plugin.itemService.weaponType(item)
        if (!mod.appliesTo(category, equipId)) return InstallResult.WRONG_CATEGORY
        val slots = readInstalledSlots(item)
        val sameCount = slots.count { parseSlotToken(it)?.first == mod.id }
        if (sameCount >= mod.maxPerEquipment) return InstallResult.MAX_COUNT_EXCEEDED
        val group = mod.exclusivityGroup
        if (!group.isNullOrBlank()) {
            val conflict = slots.withIndex().any { (idx, token) ->
                if (idx == targetSlot || token == null) return@any false
                val installedId = parseSlotToken(token)?.first ?: return@any false
                installedId != mod.id &&
                    mods[installedId]?.exclusivityGroup?.takeIf { it.isNotBlank() } == group
            }
            if (conflict) return InstallResult.EXCLUSIVITY_CONFLICT
        }
        if (usedCapacity(item) + mod.cost > readCapacity(item)) return InstallResult.CAPACITY_EXCEEDED
        return InstallResult.SUCCESS
    }

    fun tryInstall(item: ItemStack, modItem: ItemStack, slotIndex: Int): InstallResult {
        if (!plugin.itemService.isSourceEquipment(item)) return InstallResult.NOT_EQUIPMENT
        if (plugin.nightmareService.isNightmare(modItem)) {
            return tryInstallNightmare(item, modItem, slotIndex)
        }
        val mod = modConfig(modItem) ?: return InstallResult.INVALID_MOD
        if (mod.skill) return InstallResult.SKILL_MOD_WRONG_SLOT
        val slots = readInstalledSlots(item).toMutableList()
        if (slotIndex !in 0 until 8) return InstallResult.INVALID_MOD
        if (slots[slotIndex] != null) return InstallResult.SLOT_OCCUPIED
        val v = validateInstall(item, mod, slotIndex)
        if (v != InstallResult.SUCCESS) return v
        val rank = modRank(modItem).coerceIn(0, mod.maxRank)
        slots[slotIndex] = slotToken(mod.id, rank)
        val meta = item.itemMeta
        writeInstalledSlots(meta, slots)
        item.itemMeta = meta
        reapplyModEffects(item)
        modItem.amount -= 1
        return InstallResult.SUCCESS
    }

    private fun tryInstallNightmare(item: ItemStack, modItem: ItemStack, slotIndex: Int): InstallResult {
        val nm = plugin.nightmareService
        if (nm.state(modItem) != NightmareService.STATE_UNVEILED) return InstallResult.SEALED_NIGHTMARE
        if (slotIndex !in 0 until 8) return InstallResult.INVALID_MOD
        val instance = nm.parseData(modItem) ?: return InstallResult.INVALID_MOD
        val slots = readInstalledSlots(item).toMutableList()
        if (slots[slotIndex] != null) return InstallResult.SLOT_OCCUPIED
        val category = plugin.itemService.weaponCategory(item)
        if (category == null || !category.equals(instance.category, ignoreCase = true)) {
            return InstallResult.WRONG_CATEGORY
        }
        if (usedCapacity(item) + instance.cost > readCapacity(item)) return InstallResult.CAPACITY_EXCEEDED
        slots[slotIndex] = nmToken
        val meta = item.itemMeta
        writeInstalledSlots(meta, slots)
        meta.persistentDataContainer.set(nmSlotKeys[slotIndex], PersistentDataType.STRING, nm.serialize(instance))
        item.itemMeta = meta
        reapplyModEffects(item)
        modItem.amount -= 1
        return InstallResult.SUCCESS
    }

    fun tryRemove(item: ItemStack, slotIndex: Int): ItemStack? {
        val slots = readInstalledSlots(item).toMutableList()
        if (slotIndex !in 0 until 8) return null
        val token = slots[slotIndex] ?: return null
        slots[slotIndex] = null
        val meta = item.itemMeta
        writeInstalledSlots(meta, slots)
        if (token == nmToken) {
            val data = meta.persistentDataContainer.get(nmSlotKeys[slotIndex], PersistentDataType.STRING)
            meta.persistentDataContainer.remove(nmSlotKeys[slotIndex])
            item.itemMeta = meta
            reapplyModEffects(item)
            val instance = data?.let { plugin.nightmareService.parseDataString(it) }
            return instance?.let { plugin.nightmareService.buildFromData(it) }
        }
        item.itemMeta = meta
        reapplyModEffects(item)
        val (id, rank) = parseSlotToken(token) ?: return fallbackModItem(token)
        return createModItem(id, 1, rank) ?: fallbackModItem(id)
    }

    /** 安装技能MOD 到技能槽 slotIndex。成功后重算效果并盖上 MM 物品身份。 */
    fun tryInstallSkill(item: ItemStack, modItem: ItemStack, slotIndex: Int): InstallResult {
        if (!plugin.itemService.isSourceEquipment(item)) return InstallResult.NOT_EQUIPMENT
        val mod = modConfig(modItem) ?: return InstallResult.INVALID_MOD
        if (!mod.skill) return InstallResult.SKILL_SLOT_REQUIRED
        if (slotIndex !in 0 until skillSlotCount()) return InstallResult.INVALID_MOD
        // 触发栏约束：该MOD的 allowed-triggers 必须允许此槽位对应的触发（例：砍击只允许 left）
        val trigger = TriggerSlot.byIndex(slotIndex) ?: return InstallResult.INVALID_MOD
        if (!mod.allowsTrigger(trigger)) return InstallResult.TRIGGER_NOT_ALLOWED
        val category = plugin.itemService.weaponCategory(item)
        val equipId = plugin.itemService.weaponType(item)
        if (!mod.appliesTo(category, equipId)) return InstallResult.WRONG_CATEGORY
        val slots = readSkillSlots(item).toMutableList()
        if (slots.getOrNull(slotIndex) != null) return InstallResult.SLOT_OCCUPIED
        if (slots.count { it == mod.id } >= mod.maxPerEquipment) return InstallResult.MAX_COUNT_EXCEEDED
        slots[slotIndex] = mod.id
        val meta = item.itemMeta
        writeSkillSlots(meta, slots)
        item.itemMeta = meta
        reapplyModEffects(item)
        modItem.amount -= 1
        return InstallResult.SUCCESS
    }

    /** 取出技能槽 slotIndex 的技能MOD，返回还原的 MOD 物品。 */
    fun tryRemoveSkill(item: ItemStack, slotIndex: Int): ItemStack? {
        val slots = readSkillSlots(item).toMutableList()
        val id = slots.getOrNull(slotIndex) ?: return null
        slots[slotIndex] = null
        val meta = item.itemMeta
        writeSkillSlots(meta, slots)
        item.itemMeta = meta
        reapplyModEffects(item)
        return createModItem(id, 1, 0) ?: fallbackModItem(id)
    }

    private fun fallbackModItem(id: String): ItemStack {
        val item = ItemStack(Material.GRAY_DYE, 1)
        val meta = item.itemMeta
        Text.name(meta, "&8未知 MOD: $id")
        meta.persistentDataContainer.set(modIdKey, PersistentDataType.STRING, id)
        item.itemMeta = meta
        return item
    }

    fun reapplyModEffects(item: ItemStack) {
        val meta = item.itemMeta
        val pdc = meta.persistentDataContainer
        // 清掉所有 mod_delta_*
        for (key in modDeltaKeys.values) {
            pdc.remove(key)
        }
        val slots = readInstalledSlots(item)
        // 聚合各 affixId 的增量
        val deltaMap = HashMap<String, Double>()
        for (i in slots.indices) {
            val token = slots[i] ?: continue
            if (token == nmToken) {
                val data = pdc.get(nmSlotKeys[i], PersistentDataType.STRING) ?: continue
                val instance = plugin.nightmareService.parseDataString(data) ?: continue
                for ((affixId, value) in instance.affixes) {
                    if (affixId !in affixById) continue
                    deltaMap[affixId] = (deltaMap[affixId] ?: 0.0) + value
                }
                continue
            }
            val (id, rank) = parseSlotToken(token) ?: continue
            val mod = mods[id] ?: continue
            for (affixId in mod.effects.keys) {
                if (affixId !in affixById) continue
                deltaMap[affixId] = (deltaMap[affixId] ?: 0.0) + mod.effectAtRank(affixId, rank)
            }
        }
        // 技能槽：技能MOD 也可带词条（段位固定取满）。
        for (id in readSkillSlots(item)) {
            val mod = id?.let { mods[it] } ?: continue
            for (affixId in mod.effects.keys) {
                if (affixId !in affixById) continue
                deltaMap[affixId] = (deltaMap[affixId] ?: 0.0) + mod.effectAtRank(affixId, mod.maxRank)
            }
        }
        for ((affixId, delta) in deltaMap) {
            val key = modDeltaKeys[affixId] ?: continue
            pdc.set(key, PersistentDataType.DOUBLE, delta)
        }

        // 重建 lore：保留 marker 之前的内容
        val used = usedCapacity(item)
        // 容量直接通过 in-scope 的 pdc 读取/计算，使其与 lore 一起在末尾的单次 item.itemMeta = meta 中提交，
        // 避免调用 readCapacity(item) 时其内部的 item.itemMeta = meta2 被本方法稍后的 meta 提交覆盖丢失。
        val capacity = pdc.get(modCapacityKey, PersistentDataType.INTEGER) ?: run {
            val cat = plugin.itemService.weaponCategory(item) ?: "default"
            val tier = plugin.itemService.equipmentTier(item)
            val c = forgeConfig.modCapacity.computeCapacity(cat, tier)
            pdc.set(modCapacityKey, PersistentDataType.INTEGER, c)
            c
        }
        val existing = meta.lore ?: emptyList()
        val kept = mutableListOf<String>()
        for (line in existing) {
            if (isModMarker(line)) break
            kept += line
        }
        // 去掉 marker 前的尾随空行（避免反复叠加空行）
        while (kept.isNotEmpty() && kept.last().isBlank()) kept.removeAt(kept.size - 1)
        val rebuilt = kept.toMutableList()
        rebuilt += ""
        rebuilt += marker
        val remaining = (capacity - used).coerceAtLeast(0)
        val capColor = if (used > capacity) "&c" else "&a"
        rebuilt += color("  &7容量 $capColor$remaining&7/&f$capacity")

        // 技能触发栏：装了技能MOD 就把「释放方式 » 技能名」显示在装备 lore 上（槽位下标 == 触发方式）。
        val skillSlots = readSkillSlots(item)
        val skillLines = mutableListOf<String>()
        for (i in skillSlots.indices) {
            val id = skillSlots[i] ?: continue
            val trigger = TriggerSlot.byIndex(i) ?: continue
            val name = mods[id]?.displayName ?: id
            skillLines += color("  &e${trigger.display} &8» $name")
        }
        if (skillLines.isNotEmpty()) {
            rebuilt += ""
            rebuilt += color("&e● 技能&7:")
            rebuilt += skillLines
        }
        meta.lore = rebuilt
        item.itemMeta = meta

        // 原版属性：护甲与生命/护盾
        val armorDelta = deltaMap.entries.sumOf { (affixId, value) ->
            if (affixById[affixId]?.combat == "armor") value else 0.0
        }
        val healthDelta = deltaMap.entries.sumOf { (affixId, value) ->
            val combat = affixById[affixId]?.combat
            if (combat == "health" || combat == "shield_capacity") value else 0.0
        }
        plugin.itemService.applyModVanillaAttributes(item, armorDelta, healthDelta)

        // MM 物品身份：按第一个占用的技能槽盖身份（无技能MOD 则清除）。
        mythicHook.applyIdentity(item, activeMmIdentity(item))
    }

    private fun format(value: Double, decimals: Int): String {
        if (decimals <= 0) return value.toInt().toString()
        return DecimalFormat("0." + "0".repeat(decimals)).format(value)
    }
}
