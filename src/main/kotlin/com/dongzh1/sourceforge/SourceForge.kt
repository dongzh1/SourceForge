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

    override fun enable() {
        inst = this
        saveDefaultConfig()
        saveBundledResource("affixes.yml")
        saveBundledResource("combat.yml")
        saveBundledResource("recipes.yml")
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
        saveBundledResource("nightmare.yml")
        saveBundledResource("enhancement.yml")
        reloadAll()

        val command = SourceForgeCommand(this)
        getCommand("sourceforge")?.setExecutor(command)
        getCommand("sourceforge")?.tabCompleter = command
        val fl = ForgeListener(this)
        Bukkit.getPluginManager().registerEvents(fl, this)
        forgeListener = fl
        Bukkit.getPluginManager().registerEvents(InventoryAttributeListener(this), this)
        Bukkit.getPluginManager().registerEvents(ModListener(this), this)
        Bukkit.getPluginManager().registerEvents(LotteryListener(this), this)
        Bukkit.getPluginManager().registerEvents(NightmareListener(this), this)
        Bukkit.getPluginManager().registerEvents(SourceEnchantListener(this), this)
        SourceForgePapi.register(this)
        SourceForgeMMPlaceholders(this).registerIfAvailable()
        val sl = SourceForgeSkillListener(this)
        sl.registerIfAvailable()
        skillListener = sl

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

        logger.info("SourceForge 已启动")
    }

    override fun disable() {
        if (::structureManager.isInitialized) {
            structureManager.saveAll()
        }
    }

    fun reloadAll() {
        reloadConfig()
        val affixesConfig = YamlConfiguration.loadConfiguration(File(dataFolder, "affixes.yml"))
        val combatConfig = YamlConfiguration.loadConfiguration(File(dataFolder, "combat.yml"))
        forgeConfig = ForgeConfig.load(config, affixesConfig, File(dataFolder, "recipes.yml"), combatConfig)
        enhancementConfig = EnhancementConfig.load(File(dataFolder, "enhancement.yml"))
        lotteryConfig = LotteryConfig.load(config)
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

    companion object {
        lateinit var inst: SourceForge
            private set
    }
}
