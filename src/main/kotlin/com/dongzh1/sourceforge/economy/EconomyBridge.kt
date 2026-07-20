package com.dongzh1.sourceforge.economy

import net.milkbowl.vault.economy.Economy
import org.bukkit.Bukkit
import org.bukkit.entity.Player

/**
 * Vault 经济桥接：**所有 Vault 类型引用隔离在这里**，隔离约定同 [com.dongzh1.sourceforge.nav.WaypointPacketBridge]——
 * 服务器没装 Vault/没装任何经济后端插件时，本对象的方法安全降级返回 false/0，不会因为
 * NoClassDefFoundError 拖崩插件；调用方(锻炉强化)据此把强化功能静默禁用即可。
 *
 * 只在真正找到 Economy 服务时才缓存(经济后端插件一旦注册通常不会中途撤销)，找不到时每次都
 * 重新查一次 ServicesManager——避免"Vault 比 SourceForge 晚加载"导致的启动瞬间误判永久生效。
 */
object EconomyBridge {
    @Volatile
    private var economy: Economy? = null

    private fun economy(): Economy? {
        economy?.let { return it }
        val found = runCatching {
            Bukkit.getServicesManager().getRegistration(Economy::class.java)?.provider
        }.getOrNull()
        if (found != null) economy = found
        return found
    }

    /** Vault + 经济后端插件是否都已就绪。 */
    fun isAvailable(): Boolean = runCatching { economy() != null }.getOrDefault(false)

    fun balance(player: Player): Double = runCatching { economy()?.getBalance(player) ?: 0.0 }.getOrDefault(0.0)

    fun has(player: Player, amount: Double): Boolean = runCatching { economy()?.has(player, amount) ?: false }.getOrDefault(false)

    /** 扣款；成功返回 true。经济不可用/余额不足时返回 false，不做任何扣除。 */
    fun withdraw(player: Player, amount: Double): Boolean = runCatching {
        val econ = economy() ?: return@runCatching false
        if (amount <= 0.0) return@runCatching true
        if (!econ.has(player, amount)) return@runCatching false
        econ.withdrawPlayer(player, amount).transactionSuccess()
    }.getOrDefault(false)
}
