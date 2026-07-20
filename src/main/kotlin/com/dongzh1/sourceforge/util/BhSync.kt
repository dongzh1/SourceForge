package com.dongzh1.sourceforge.util

import com.dongzh1.sourceforge.SourceForge
import org.bukkit.Bukkit
import org.bukkit.scheduler.BukkitTask
import java.io.File

/**
 * BetterHud 配置/贴图同步：把插件内置的 betterhud/ 资源写入 plugins/BetterHud/ 对应路径。
 * 缺失或内容与内置版本不同时覆盖（这些文件由 SourceForge 管理，更新插件即同步新版；
 * 想自定义请复制改名后改 config 指向）。安装/更新后自动重载 BetterHud 生效。
 *
 * 与 SourceWild util/BhSync.kt 同一套写法（同一服务器上两个插件各自同步各自的 popup/layout）。
 */
object BhSync {

    fun sync(files: List<String>) {
        if (Bukkit.getPluginManager().getPlugin("BetterHud") == null) return
        val bhFolder = File(SourceForge.inst.dataFolder.parentFile, "BetterHud")
        if (!bhFolder.isDirectory) return
        var installed = false
        for (path in files) {
            val bytes = SourceForge.inst.getResource("betterhud/$path")?.readBytes() ?: continue
            val target = File(bhFolder, path)
            if (target.exists() && target.readBytes().contentEquals(bytes)) continue
            runCatching {
                target.parentFile.mkdirs()
                target.writeBytes(bytes)
                installed = true
            }
        }
        if (installed) {
            Bukkit.getConsoleSender().sendMessage(
                "§a[SourceForge] §fBetterHud 配置已同步（${files.size} 项检查），将在 BetterHud 就绪后自动重载")
            reloadBetterHudWhenReady()
        }
    }

    private fun reloadBetterHudWhenReady() {
        var attempts = 0
        var task: BukkitTask? = null
        task = Bukkit.getScheduler().runTaskTimer(SourceForge.inst, Runnable {
            attempts++
            if (Bukkit.getPluginManager().isPluginEnabled("BetterHud")) {
                task?.cancel()
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "betterhud reload")
                return@Runnable
            }
            if (attempts >= MAX_BETTER_HUD_READY_ATTEMPTS) {
                task?.cancel()
                SourceForge.inst.logger.warning("[SourceForge] BetterHud 在等待期内未启用，未自动重载配置")
            }
        }, 1L, 20L)
    }

    private const val MAX_BETTER_HUD_READY_ATTEMPTS = 60
}
