package com.dongzh1.sourceforge

import com.dongzh1.sourceforge.command.SourceForgeCommand
import com.dongzh1.sourceforge.config.EnhancementConfig
import com.dongzh1.sourceforge.config.ForgeConfig
import com.dongzh1.sourceforge.config.LotteryConfig
import com.dongzh1.sourceforge.mod.LotteryListener
import com.dongzh1.sourceforge.mod.ModUpgradeMenu
import com.dongzh1.sourceforge.enchant.SourceEnchantListener
import com.dongzh1.sourceforge.enchant.SourceForgeMMPlaceholders
import com.dongzh1.sourceforge.enchant.SourceForgeSkillListener
import com.dongzh1.sourceforge.forge.ForgeListener
import com.dongzh1.sourceforge.forge.InventoryAttributeListener
import com.dongzh1.sourceforge.hud.EntityTargetTracker
import com.dongzh1.sourceforge.item.ForgeItemService
import com.dongzh1.sourceforge.mod.ModListener
import com.dongzh1.sourceforge.mod.ModRegistry
import com.dongzh1.sourceforge.mod.ModService
import com.dongzh1.sourceforge.mod.NightmareConfig
import com.dongzh1.sourceforge.mod.NightmareListener
import com.dongzh1.sourceforge.mod.NightmareService
import com.dongzh1.sourceforge.multiblock.ForgeStructureConfig
import com.dongzh1.sourceforge.multiblock.ForgeStructureListener
import com.dongzh1.sourceforge.multiblock.ForgeStructureManager
import com.dongzh1.sourceforge.nav.NavigationManager
import com.dongzh1.sourceforge.papi.SourceForgePapi
import com.dongzh1.sourceforge.status.ElementConfig
import com.dongzh1.sourceforge.status.StatusEffectManager
import com.xbaimiao.easylib.EasyPlugin
import org.bukkit.Bukkit
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.io.File

@Suppress("unused")
class SourceForge : EasyPlugin() {

    lateinit var forgeConfig: ForgeConfig
        private set
    lateinit var itemService: ForgeItemService
        private set
    lateinit var modService: ModService
        private set
    lateinit var nightmareService: NightmareService
        private set
    lateinit var forgeListener: ForgeListener
        private set
    lateinit var skillListener: SourceForgeSkillListener
        private set
    lateinit var navigationManager: NavigationManager
        private set
    lateinit var structureManager: ForgeStructureManager
        private set
    lateinit var enhancementConfig: EnhancementConfig
        private set
    lateinit var lotteryConfig: LotteryConfig
        private set
    lateinit var elementConfig: ElementConfig
        private set
    lateinit var statusManager: StatusEffectManager
        private set
    lateinit var scriptService: com.dongzh1.sourceforge.script.ScriptService
        private set

    override fun enable() {
        inst = this
        saveDefaultConfig()
        saveBundledResource("affixes.yml")
        saveBundledResource("combat.yml")
        saveBundledResource("recipes.yml")
        saveBundledResource("forge_gui.yml")
        saveBundledResource("materials/sharp_stone.yml")
        saveBundledResource("materials/focus_crystal.yml")
        saveBundledResource("materials/venom_vial.yml")
        saveBundledResource("materials/war_ember.yml")
        saveBundledResource("materials/blood_essence.yml")
        saveBundledResource("materials/frost_core.yml")
        saveBundledResource("materials/metal_spring.yml")
        saveBundledResource("materials/gunpowder_charge.yml")
        saveBundledResource("materials/rime_shard.yml")
        saveBundledResource("materials/bulwark_plate.yml")
        saveBundledResource("materials/rune_thread.yml")
        saveBundledResource("mods/serration.yml")
        saveBundledResource("mods/vitality.yml")
        // 基础属性MOD（可升级）
        saveBundledResource("mods/critical_strike.yml")
        saveBundledResource("mods/savage_blow.yml")
        saveBundledResource("mods/corrosive_edge.yml")
        saveBundledResource("mods/steel_fiber.yml")
        saveBundledResource("mods/redirection.yml")
        saveBundledResource("mods/flow.yml")
        saveBundledResource("mods/intensify.yml")
        saveBundledResource("mods/continuity.yml")
        saveBundledResource("mods/streamline.yml")
        saveBundledResource("mods/stretch.yml")
        // 元素MOD（火/冰/毒/电，自带触发几率）
        saveBundledResource("mods/hellfire.yml")
        saveBundledResource("mods/frostbite.yml")
        saveBundledResource("mods/venom.yml")
        saveBundledResource("mods/shock.yml")
        saveBundledResource("mods/test_status.yml")
        saveBundledResource("mods/desecrate.yml")
        saveBundledResource("skills/desecrate.js")
        // 单元素测试卡（火/冰/毒/电，各 100% 触发，单卡只叠一种）
        saveBundledResource("mods/test_heat.yml")
        saveBundledResource("mods/test_cold.yml")
        saveBundledResource("mods/test_toxin.yml")
        saveBundledResource("mods/test_electric.yml")
        saveBundledResource("nightmare.yml")
        saveBundledResource("enhancement.yml")
        saveBundledResource("elements.yml")
        ensureElementAffixes()   // 老服 affixes.yml 已存在不会被覆盖，这里补齐缺失的元素属性
        reloadAll()
        statusManager = StatusEffectManager(this)
        scriptService = com.dongzh1.sourceforge.script.ScriptService(this).also { it.load() }

        val command = SourceForgeCommand(this)
        getCommand("sourceforge")?.setExecutor(command)
        getCommand("sourceforge")?.tabCompleter = command
        val fl = ForgeListener(this)
        Bukkit.getPluginManager().registerEvents(fl, this)
        forgeListener = fl
        Bukkit.getPluginManager().registerEvents(InventoryAttributeListener(this), this)
        Bukkit.getPluginManager().registerEvents(ModListener(this), this)
        val skillMod = com.dongzh1.sourceforge.mod.SkillModListener(this)
        Bukkit.getPluginManager().registerEvents(skillMod, this)
        skillMod.start()   // 技能MOD：摸尸开关 + MANA 回复/消耗 + 范围翻倍掉落
        Bukkit.getPluginManager().registerEvents(LotteryListener(this), this)
        Bukkit.getPluginManager().registerEvents(NightmareListener(this), this)
        Bukkit.getPluginManager().registerEvents(SourceEnchantListener(this), this)
        SourceForgePapi.register(this)
        SourceForgeMMPlaceholders(this).registerIfAvailable()
        val sl = SourceForgeSkillListener(this)
        sl.registerIfAvailable()
        skillListener = sl
        // 通用元素增伤：怪受伤时按 AMP 异常层数放大(覆盖 SF/MM/原版/DoT)。注册在 ForgeListener 之后，确保最后乘。
        Bukkit.getPluginManager().registerEvents(com.dongzh1.sourceforge.status.ElementDamageListener(this), this)
        // MM 伤害触发元素异常(增伤已由上面的通用监听处理)
        com.dongzh1.sourceforge.status.MythicDamageAmpListener(this).registerIfAvailable()

        val nav = NavigationManager(this)
        Bukkit.getPluginManager().registerEvents(nav, this)
        nav.start()
        navigationManager = nav

        // 怪物血条名字追踪：供 BetterHud %sourceforge_target_name% 显示干净的目标名
        Bukkit.getPluginManager().registerEvents(EntityTargetTracker, this)

        // 源质锻炉多方块系统
        val sm = ForgeStructureManager(this, ForgeStructureConfig.load(config))
        sm.loadAll()
        structureManager = sm
        Bukkit.getPluginManager().registerEvents(ForgeStructureListener(this), this)
        // tick：每 tick 推进作业；autosave：每 5 分钟落盘脏世界
        server.scheduler.runTaskTimer(this, Runnable { sm.tick() }, 1L, 1L)
        server.scheduler.runTaskTimer(this, Runnable { sm.saveDirty() }, 6000L, 6000L)

        // 元素异常状态结算（DoT/减速/视觉），周期 = elements.yml tick-period
        val statusPeriod = elementConfig.tickPeriod
        server.scheduler.runTaskTimer(this, Runnable { statusManager.tick() }, statusPeriod, statusPeriod)

        logger.info("SourceForge 已启动")
    }

    override fun disable() {
        if (::structureManager.isInitialized) {
            structureManager.saveAll()
        }
        if (::statusManager.isInitialized) {
            statusManager.clearAll()
        }
        if (::scriptService.isInitialized) {
            scriptService.close()
        }
    }

    fun reloadAll() {
        reloadConfig()
        val affixesConfig = YamlConfiguration.loadConfiguration(File(dataFolder, "affixes.yml"))
        val combatConfig = YamlConfiguration.loadConfiguration(File(dataFolder, "combat.yml"))
        forgeConfig = ForgeConfig.load(config, affixesConfig, File(dataFolder, "recipes.yml"), combatConfig, File(dataFolder, "forge_gui.yml"))
        enhancementConfig = EnhancementConfig.load(File(dataFolder, "enhancement.yml"))
        lotteryConfig = LotteryConfig.load(config)
        elementConfig = ElementConfig.load(YamlConfiguration.loadConfiguration(File(dataFolder, "elements.yml")))
        itemService = ForgeItemService(this, forgeConfig)
        val modsFolder = File(dataFolder, "mods")
        val (modMap, modWarnings) = ModRegistry.load(modsFolder, forgeConfig.affixes)
        modService = ModService(this, forgeConfig, modMap)
        if (modWarnings.isNotEmpty()) {
            logger.warning("MOD 配置告警 ${modWarnings.size} 个:")
            modWarnings.forEach { logger.warning(" - $it") }
        }
        val (nightmareConfig, nightmareWarnings) = NightmareConfig.load(File(dataFolder, "nightmare.yml"), forgeConfig.affixes)
        nightmareService = NightmareService(this, nightmareConfig, forgeConfig.affixes)
        if (nightmareWarnings.isNotEmpty()) {
            logger.warning("梦魇MOD 配置告警 ${nightmareWarnings.size} 个:")
            nightmareWarnings.forEach { logger.warning(" - $it") }
        }
        if (forgeConfig.validationWarnings.isNotEmpty()) {
            logger.warning("SourceForge 配置校验发现 ${forgeConfig.validationWarnings.size} 个问题:")
            forgeConfig.validationWarnings.forEach { logger.warning(" - $it") }
        }
    }

    fun buildItemExpression(expression: String, player: Player?, amount: Int): ItemStack? {
        return itemService.buildExpression(expression, player, amount)
    }

    private fun saveBundledResource(path: String) {
        if (!File(dataFolder, path).isFile) {
            saveResource(path, false)
        }
    }

    /**
     * 迁移：老服的 affixes.yml 早已存在，saveBundledResource 不会覆盖它，导致新加的元素属性进不去配置，
     * 进而 /sf stats 不显示、命中读不到元素值（elemSum=0）无法触发。这里检测缺失则把 4 个元素属性
     * 以 2 空格缩进追加到文件末尾（即 affixes: 映射内），幂等（已存在则跳过），不影响用户已有词条。
     */
    private fun ensureElementAffixes() {
        val file = File(dataFolder, "affixes.yml")
        if (!file.isFile) return
        val text = runCatching { file.readText() }.getOrNull() ?: return
        if (text.contains("heat_damage:")) return
        val block = buildString {
            append("\n\n  # ===== 元素属性（自动补全；与 status_chance 联动，详见 elements.yml）=====\n")
            for ((id, name, color) in listOf(
                Triple("heat_damage", "火元素", "&c"),
                Triple("cold_damage", "冰元素", "&b"),
                Triple("toxin_damage", "毒元素", "&a"),
                Triple("electric_damage", "电元素", "&e")
            )) {
                append("  $id:\n")
                append("    display-name: \"$name\"\n")
                append("    pdc-key: \"$id\"\n")
                append("    value-type: double\n")
                append("    min: 0.5\n")
                append("    max: 2.0\n")
                append("    decimals: 1\n")
                append("    combat: elemental\n")
                append("    scale: 1.0\n")
                val label = name.substring(0, 1)
                append("    lore: \"&7$label: $color+%value%\"\n\n")
            }
        }
        runCatching {
            file.appendText(block)
            logger.info("[迁移] 已向 affixes.yml 追加 4 个元素属性（火/冰/毒/电）")
        }
    }

    companion object {
        lateinit var inst: SourceForge
            private set
    }
}
