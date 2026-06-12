package com.dongzh1.sourceforge

import com.dongzh1.sourceforge.command.SourceForgeCommand
import com.dongzh1.sourceforge.config.ForgeConfig
import com.dongzh1.sourceforge.enchant.SourceEnchantListener
import com.dongzh1.sourceforge.enchant.SourceEnchantService
import com.dongzh1.sourceforge.enchant.SourceForgeMMPlaceholders
import com.dongzh1.sourceforge.forge.ForgeListener
import com.dongzh1.sourceforge.forge.InventoryAttributeListener
import com.dongzh1.sourceforge.item.ForgeItemService
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
    lateinit var enchantService: SourceEnchantService
        private set

    override fun enable() {
        inst = this
        saveDefaultConfig()
        saveBundledResource("affixes.yml")
        saveBundledResource("combat.yml")
        saveBundledResource("enchants.yml")
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
        reloadAll()

        val command = SourceForgeCommand(this)
        getCommand("sourceforge")?.setExecutor(command)
        getCommand("sourceforge")?.tabCompleter = command
        Bukkit.getPluginManager().registerEvents(ForgeListener(this), this)
        Bukkit.getPluginManager().registerEvents(InventoryAttributeListener(this), this)
        Bukkit.getPluginManager().registerEvents(SourceEnchantListener(this), this)
        SourceForgePapi.register(this)
        SourceForgeMMPlaceholders(this).registerIfAvailable()

        logger.info("SourceForge 已启动")
    }

    fun reloadAll() {
        reloadConfig()
        val affixesConfig = YamlConfiguration.loadConfiguration(File(dataFolder, "affixes.yml"))
        val combatConfig = YamlConfiguration.loadConfiguration(File(dataFolder, "combat.yml"))
        val enchantsConfig = YamlConfiguration.loadConfiguration(File(dataFolder, "enchants.yml"))
        forgeConfig = ForgeConfig.load(config, affixesConfig, File(dataFolder, "materials"), combatConfig)
        itemService = ForgeItemService(this, forgeConfig)
        enchantService = SourceEnchantService(this, enchantsConfig)
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
