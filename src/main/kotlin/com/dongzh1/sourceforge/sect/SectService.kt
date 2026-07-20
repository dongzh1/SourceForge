package com.dongzh1.sourceforge.sect

import com.dongzh1.sourceforge.SourceForge
import com.dongzh1.sourceforge.api.SourceForgeCombatAPI
import com.dongzh1.sourceforge.economy.EconomyBridge
import com.dongzh1.sourceforge.item.CraftEngineHook
import com.dongzh1.sourceforge.util.Text
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File

data class SectDefinition(
    val id: String,
    val displayName: String,
    val description: List<String>,
    val outgoingDamageMultiplier: Double,
    val rewardIds: List<String>,
    val dialogueId: String?
)

data class SectRewardDefinition(
    val sectId: String,
    val id: String,
    val item: String?,
    val material: Material,
    val name: String,
    val lore: List<String>,
    val behavior: String,
    val durationSeconds: Long,
    val journeyReward: Boolean,
    val purchasePrice: Double
)

data class SectDialogueOptionDefinition(
    val text: String,
    val goto: String?,
    val actions: List<String>,
    val condition: String?
)

data class SectDialoguePageDefinition(
    val id: String?,
    val speaker: String,
    val text: String,
    val sound: String?,
    val options: List<SectDialogueOptionDefinition>,
    val condition: String?,
    val avatar: String?,
    val enter: List<String>
)

data class SectDialogueDefinition(
    val id: String,
    val npcId: String,
    val avatar: String?,
    val stare: Boolean,
    val condition: String?,
    val pages: List<SectDialoguePageDefinition>,
    val end: List<String>
)

enum class JourneyPreparationStatus {
    SUCCESS,
    NO_SECT,
    ALREADY_PREPARED,
    NO_REWARDS,
    REWARD_BUILD_FAILED
}

data class JourneyPreparationResult(
    val status: JourneyPreparationStatus,
    val sect: SectDefinition? = null,
    val grantedRewards: Int = 0
)

enum class SectPurchaseStatus {
    SUCCESS,
    NO_SECT,
    UNKNOWN_REWARD,
    NOT_FOR_SALE,
    ECONOMY_UNAVAILABLE,
    INSUFFICIENT_FUNDS,
    REWARD_BUILD_FAILED
}

data class SectPurchaseResult(
    val status: SectPurchaseStatus,
    val sect: SectDefinition? = null,
    val reward: SectRewardDefinition? = null,
    val price: Double = 0.0
)

/** 教派运行时服务。每个教派一个独立 yml，奖励和对话均从文件加载。 */
class SectService(private val plugin: SourceForge) : Listener {
    private val selectedSectKey = NamespacedKey(plugin, "sect_selected")
    private val journeyPreparedKey = NamespacedKey(plugin, "sect_journey_prepared")
    private val wineUntilKey = NamespacedKey(plugin, "sect_golden_wine_until")
    private val wineIndicatorKey = NamespacedKey(plugin, "sect_golden_wine_indicator")
    private val rewardKey = NamespacedKey(plugin, "sect_reward")
    private val definitions = linkedMapOf<String, SectDefinition>()
    private val rewards = linkedMapOf<String, SectRewardDefinition>()
    private val dialogues = linkedMapOf<String, SectDialogueDefinition>()
    private val outputMultiplierKey = "sect.configured_output"
    private val database = SectDatabase(plugin)

    init {
        reload()
        SourceForgeCombatAPI.registerDamageMultiplier(
            outputMultiplierKey,
            { player, _ -> selected(player)?.outgoingDamageMultiplier ?: 1.0 },
            { player -> (selected(player)?.outgoingDamageMultiplier ?: 1.0) != 1.0 || isGoldenWineActive(player) }
        )
        SourceForgeCombatAPI.registerDamageMultiplier(
            "sect.golden_wine",
            { player, _ -> if (isGoldenWineActive(player)) 1.1 else 1.0 },
            { player -> isGoldenWineActive(player) }
        )
    }

    fun reload() {
        definitions.clear()
        rewards.clear()
        dialogues.clear()
        val folder = File(plugin.dataFolder, "sects")
        val files = folder.listFiles { file -> file.isFile && file.extension.equals("yml", true) }
            ?.sortedBy { it.name } ?: emptyList()
        for (file in files) loadSect(file)
        if (files.isEmpty()) plugin.logger.warning("教派目录为空: ${folder.path}")
    }

    fun start() {
        if (!database.connect()) return
        plugin.server.onlinePlayers.forEach(::loadFromDatabase)
    }

    fun close() {
        database.close()
    }

    private fun loadSect(file: File) {
        val yaml = YamlConfiguration.loadConfiguration(file)
        val id = (yaml.getString("id") ?: file.nameWithoutExtension).lowercase()
        if (!id.matches(Regex("[a-z0-9_-]{1,32}"))) {
            plugin.logger.warning("教派文件 ${file.name} 的 id 非法，已跳过")
            return
        }
        val rewardIds = yaml.getConfigurationSection("rewards")?.getKeys(false)?.map(String::lowercase)?.toList()
            ?: emptyList()
        rewardIds.forEach { rewardId ->
            val path = "rewards.$rewardId"
            val material = Material.matchMaterial(yaml.getString("$path.material", "PAPER") ?: "PAPER")
                ?: Material.PAPER
            rewards[rewardKey(id, rewardId)] = SectRewardDefinition(
                sectId = id,
                id = rewardId,
                item = yaml.getString("$path.item")?.takeIf { it.isNotBlank() },
                material = material,
                name = yaml.getString("$path.name", "&f教派物品") ?: "&f教派物品",
                lore = yaml.getStringList("$path.lore"),
                behavior = yaml.getString("$path.behavior", "none") ?: "none",
                durationSeconds = yaml.getLong("$path.duration-seconds", 3600L).coerceAtLeast(1L),
                journeyReward = yaml.getBoolean("$path.journey-reward", true),
                purchasePrice = yaml.getDouble("$path.purchase-price", 0.0).coerceAtLeast(0.0)
            )
        }
        val dialogueId = if (yaml.isConfigurationSection("dialogue")) "sect_$id" else null
        definitions[id] = SectDefinition(
            id = id,
            displayName = yaml.getString("display-name", id) ?: id,
            description = yaml.getStringList("description"),
            outgoingDamageMultiplier = yaml.getDouble("outgoing-damage-multiplier", 1.0).coerceAtLeast(0.0),
            rewardIds = rewardIds,
            dialogueId = dialogueId
        )
        if (dialogueId != null) parseDialogue(id, dialogueId, yaml)
    }

    private fun parseDialogue(sectId: String, dialogueId: String, yaml: YamlConfiguration) {
        val npcId = yaml.getString("dialogue.npc", yaml.getString("npc.id", "${sectId}_guide"))
            ?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,32}")) } ?: "${sectId}_guide"
        val pages = yaml.getMapList("dialogue.pages").map { page ->
            val options = (page["options"] as? List<*>)?.mapNotNull { raw ->
                val option = raw as? Map<*, *> ?: return@mapNotNull null
                SectDialogueOptionDefinition(
                    text = option["text"]?.toString() ?: "",
                    goto = option["goto"]?.toString(),
                    actions = (option["actions"] as? List<*>)?.map { it.toString() } ?: emptyList(),
                    condition = option["condition"]?.toString()
                )
            } ?: emptyList()
            SectDialoguePageDefinition(
                id = page["id"]?.toString(),
                speaker = page["speaker"]?.toString() ?: "",
                text = page["text"]?.toString() ?: "",
                sound = page["sound"]?.toString(),
                options = options,
                condition = page["condition"]?.toString(),
                avatar = page["avatar"]?.toString(),
                enter = (page["enter"] as? List<*>)?.map { it.toString() } ?: emptyList()
            )
        }
        dialogues[sectId] = SectDialogueDefinition(
            id = dialogueId,
            npcId = npcId,
            avatar = yaml.getString("dialogue.avatar"),
            stare = yaml.getBoolean("dialogue.stare", true),
            condition = yaml.getString("dialogue.condition"),
            pages = pages,
            end = yaml.getStringList("dialogue.end")
        )
    }

    fun sects(): List<SectDefinition> = definitions.values.toList()

    fun find(id: String?): SectDefinition? = id?.let { definitions[it.lowercase()] }

    fun dialogueFor(sectId: String): SectDialogueDefinition? = dialogues[sectId.lowercase()]

    fun selected(player: Player): SectDefinition? = find(
        player.persistentDataContainer.get(selectedSectKey, PersistentDataType.STRING)
    )

    fun isMember(player: Player, sectId: String): Boolean = selected(player)?.id == sectId.lowercase()

    fun select(player: Player, id: String): SectDefinition? {
        val sect = find(id) ?: return null
        player.persistentDataContainer.set(selectedSectKey, PersistentDataType.STRING, sect.id)
        persist(player)
        return sect
    }

    fun clearSelection(player: Player) {
        player.persistentDataContainer.remove(selectedSectKey)
        player.persistentDataContainer.remove(journeyPreparedKey)
        persist(player)
    }

    fun isJourneyPrepared(player: Player): Boolean =
        player.persistentDataContainer.has(journeyPreparedKey, PersistentDataType.BYTE)

    fun prepareJourney(player: Player): JourneyPreparationResult {
        val sect = selected(player) ?: return JourneyPreparationResult(JourneyPreparationStatus.NO_SECT)
        if (isJourneyPrepared(player)) return JourneyPreparationResult(JourneyPreparationStatus.ALREADY_PREPARED, sect)
        if (sect.rewardIds.isEmpty()) {
            player.persistentDataContainer.set(journeyPreparedKey, PersistentDataType.BYTE, 1)
            persist(player)
            return JourneyPreparationResult(JourneyPreparationStatus.NO_REWARDS, sect)
        }
        val configured = sect.rewardIds.map { rewards[rewardKey(sect.id, it)] }
        if (configured.any { it == null }) {
            plugin.logger.warning("教派 ${sect.id} 引用了未配置的奖励: ${sect.rewardIds}")
            return JourneyPreparationResult(JourneyPreparationStatus.REWARD_BUILD_FAILED, sect)
        }
        val built = configured.filterNotNull().filter { it.journeyReward }
        if (built.isEmpty()) {
            player.persistentDataContainer.set(journeyPreparedKey, PersistentDataType.BYTE, 1)
            persist(player)
            return JourneyPreparationResult(JourneyPreparationStatus.NO_REWARDS, sect)
        }
        val items = built.mapNotNull { createRewardItem(it) }
        if (items.size != built.size) return JourneyPreparationResult(JourneyPreparationStatus.REWARD_BUILD_FAILED, sect)
        items.forEach { give(player, it) }
        player.persistentDataContainer.set(journeyPreparedKey, PersistentDataType.BYTE, 1)
        persist(player)
        return JourneyPreparationResult(JourneyPreparationStatus.SUCCESS, sect, items.size)
    }

    fun purchasableRewardIds(player: Player): List<String> = selected(player)?.let { sect ->
        sect.rewardIds.filter { rewards[rewardKey(sect.id, it)]?.purchasePrice ?: 0.0 > 0.0 }
    } ?: emptyList()

    fun purchaseReward(player: Player, rewardId: String?): SectPurchaseResult {
        val sect = selected(player) ?: return SectPurchaseResult(SectPurchaseStatus.NO_SECT)
        val reward = rewardId?.lowercase()?.let { rewards[rewardKey(sect.id, it)] }
            ?: return SectPurchaseResult(SectPurchaseStatus.UNKNOWN_REWARD, sect = sect)
        if (reward.purchasePrice <= 0.0) {
            return SectPurchaseResult(SectPurchaseStatus.NOT_FOR_SALE, sect, reward)
        }
        if (!EconomyBridge.isAvailable()) {
            return SectPurchaseResult(SectPurchaseStatus.ECONOMY_UNAVAILABLE, sect, reward, reward.purchasePrice)
        }
        val item = createRewardItem(reward)
            ?: return SectPurchaseResult(SectPurchaseStatus.REWARD_BUILD_FAILED, sect, reward, reward.purchasePrice)
        if (!EconomyBridge.withdraw(player, reward.purchasePrice)) {
            return SectPurchaseResult(SectPurchaseStatus.INSUFFICIENT_FUNDS, sect, reward, reward.purchasePrice)
        }
        give(player, item)
        return SectPurchaseResult(SectPurchaseStatus.SUCCESS, sect, reward, reward.purchasePrice)
    }

    fun finishJourney(player: Player): Boolean {
        if (!isJourneyPrepared(player)) return false
        player.persistentDataContainer.remove(journeyPreparedKey)
        persist(player)
        return true
    }

    fun isGoldenWineActive(player: Player): Boolean {
        val until = player.persistentDataContainer.get(wineUntilKey, PersistentDataType.LONG) ?: return false
        if (until > System.currentTimeMillis()) return true
        player.persistentDataContainer.remove(wineUntilKey)
        player.persistentDataContainer.remove(wineIndicatorKey)
        return false
    }

    fun hasGoldenWineIndicator(player: Player): Boolean =
        isGoldenWineActive(player) &&
            player.persistentDataContainer.has(wineIndicatorKey, PersistentDataType.BYTE)

    fun goldenWineRemainingSeconds(player: Player): Long {
        val until = player.persistentDataContainer.get(wineUntilKey, PersistentDataType.LONG) ?: return 0L
        return ((until - System.currentTimeMillis()).coerceAtLeast(0L) / 1000L)
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    fun onRewardInteract(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_AIR && event.action != Action.RIGHT_CLICK_BLOCK) return
        val item = event.item ?: return
        val key = item.itemMeta?.persistentDataContainer?.get(rewardKey, PersistentDataType.STRING) ?: return
        val reward = rewards[key] ?: return
        if (!handleReward(event.player, reward)) return
        persist(event.player)
        event.isCancelled = true
    }

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        loadFromDatabase(event.player)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        persist(event.player)
    }

    private fun handleReward(player: Player, reward: SectRewardDefinition): Boolean = when (reward.behavior.lowercase()) {
        "signal_gun", "merchant_summoner" -> {
            val location = player.location.clone()
            val direction = location.direction.setY(0).normalize().multiply(1.5)
            location.add(direction)
            val villager = player.world.spawn(location, org.bukkit.entity.Villager::class.java)
            villager.customName(Text.comp("&6${find(reward.sectId)?.displayName ?: "教派"}商人"))
            villager.isCustomNameVisible = true
            villager.profession = org.bukkit.entity.Villager.Profession.ARMORER
            consumeOne(player, reward)
            true
        }
        "golden_wine" -> {
            val until = System.currentTimeMillis() + reward.durationSeconds * 1000L
            player.persistentDataContainer.set(wineUntilKey, PersistentDataType.LONG, until)
            player.persistentDataContainer.set(wineIndicatorKey, PersistentDataType.BYTE, 1)
            player.addPotionEffect(
                PotionEffect(
                    PotionEffectType.STRENGTH,
                    (reward.durationSeconds * 20L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                    0,
                    false,
                    false,
                    true
                ),
                true
            )
            consumeOne(player, reward)
            player.sendMessage("§6[${find(reward.sectId)?.displayName ?: "教派"}] §f黄金酒生效，所有输出提高至 §e1.1 倍§f，持续 §e${reward.durationSeconds} 秒")
            true
        }
        else -> false
    }

    private fun createRewardItem(reward: SectRewardDefinition): ItemStack? {
        val item = reward.item?.let { CraftEngineHook.build(it, 1) } ?: ItemStack(reward.material)
        val meta = item.itemMeta ?: return null
        Text.apply(meta, reward.name, reward.lore)
        meta.persistentDataContainer.set(rewardKey, PersistentDataType.STRING, rewardKey(reward.sectId, reward.id))
        item.itemMeta = meta
        return item
    }

    private fun give(player: Player, item: ItemStack) {
        player.inventory.addItem(item).values.forEach { player.world.dropItemNaturally(player.location, it) }
    }

    private fun consumeOne(player: Player, reward: SectRewardDefinition) {
        val key = rewardKey(reward.sectId, reward.id)
        for (slot in 0 until player.inventory.size) {
            val item = player.inventory.getItem(slot) ?: continue
            val itemKey = item.itemMeta?.persistentDataContainer?.get(this.rewardKey, PersistentDataType.STRING)
            if (itemKey != key) continue
            item.amount -= 1
            player.inventory.setItem(slot, item.takeIf { it.amount > 0 })
            return
        }
    }

    private fun loadFromDatabase(player: Player) {
        if (!database.isConnected) return
        val uuid = player.uniqueId
        plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
            val stored = database.load(uuid)
            plugin.server.scheduler.runTask(plugin, Runnable {
                if (!player.isOnline || player.uniqueId != uuid) return@Runnable
                if (stored == null) {
                    persist(player)
                    return@Runnable
                }
                player.persistentDataContainer.remove(selectedSectKey)
                stored.selectedSect?.takeIf { find(it) != null }?.let {
                    player.persistentDataContainer.set(selectedSectKey, PersistentDataType.STRING, it)
                }
                if (stored.journeyPrepared) {
                    player.persistentDataContainer.set(journeyPreparedKey, PersistentDataType.BYTE, 1)
                } else {
                    player.persistentDataContainer.remove(journeyPreparedKey)
                }
                if (stored.wineUntilMillis > System.currentTimeMillis()) {
                    player.persistentDataContainer.set(wineUntilKey, PersistentDataType.LONG, stored.wineUntilMillis)
                    player.persistentDataContainer.set(wineIndicatorKey, PersistentDataType.BYTE, 1)
                    player.addPotionEffect(
                        PotionEffect(
                            PotionEffectType.STRENGTH,
                            ((stored.wineUntilMillis - System.currentTimeMillis()) / 50L)
                                .coerceAtLeast(1L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                            0,
                            false,
                            false,
                            true
                        ),
                        true
                    )
                } else {
                    player.persistentDataContainer.remove(wineUntilKey)
                    player.persistentDataContainer.remove(wineIndicatorKey)
                }
            })
        })
    }

    private fun persist(player: Player) {
        if (!database.isConnected) return
        val state = StoredSectState(
            selectedSect = selected(player)?.id,
            journeyPrepared = isJourneyPrepared(player),
            wineUntilMillis = player.persistentDataContainer.get(wineUntilKey, PersistentDataType.LONG) ?: 0L
        )
        val uuid = player.uniqueId
        plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
            runCatching { database.save(uuid, state) }
                .onFailure { plugin.logger.warning("[教派] 保存玩家状态失败 ($uuid): ${it.message}") }
        })
    }

    private fun rewardKey(sectId: String, rewardId: String): String = "$sectId:${rewardId.lowercase()}"
}
