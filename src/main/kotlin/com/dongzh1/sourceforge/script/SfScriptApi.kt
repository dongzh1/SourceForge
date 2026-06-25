package com.dongzh1.sourceforge.script

import com.dongzh1.sourceforge.SourceForge
import com.dongzh1.sourceforge.enchant.MythicMobsHook
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.graalvm.polyglot.HostAccess
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 暴露给技能脚本(JS)的 SF 原语对象，脚本里以全局变量 `sf` 访问。
 * 只有 @HostAccess.Export 标注的方法对脚本可见（HostAccess.EXPLICIT），脚本碰不到任意 Java/Bukkit，安全。
 *
 * 玩家以 UUID 字符串在脚本与宿主间传递。技能开关状态(active)由本类统一维护，按 (skillId, playerUUID)。
 */
class SfScriptApi(private val plugin: SourceForge) {

    private val activeBySkill = ConcurrentHashMap<String, MutableSet<UUID>>()
    private val mythic = MythicMobsHook()

    private fun player(id: String): Player? =
        runCatching { Bukkit.getPlayer(UUID.fromString(id)) }.getOrNull()

    // ===== 开关状态 =====
    @HostAccess.Export
    fun isActive(skillId: String, playerId: String): Boolean =
        activeBySkill[skillId]?.contains(runCatching { UUID.fromString(playerId) }.getOrNull()) == true

    @HostAccess.Export
    fun setActive(skillId: String, playerId: String, on: Boolean) {
        val uid = runCatching { UUID.fromString(playerId) }.getOrNull() ?: return
        val set = activeBySkill.computeIfAbsent(skillId) { ConcurrentHashMap.newKeySet() }
        if (on) set.add(uid) else set.remove(uid)
    }

    // ===== MANA =====
    @HostAccess.Export
    fun mana(playerId: String): Double = player(playerId)?.let { plugin.forgeListener.getEnergyCurrent(it) } ?: 0.0

    @HostAccess.Export
    fun manaMax(playerId: String): Double = player(playerId)?.let { plugin.forgeListener.getEnergyMax(it) } ?: 0.0

    @HostAccess.Export
    fun drainMana(playerId: String, amount: Double): Boolean =
        player(playerId)?.let { plugin.forgeListener.deductEnergy(it, amount) } ?: false

    // ===== 属性 =====
    @HostAccess.Export
    fun stat(playerId: String, affixId: String): Double =
        player(playerId)?.let { plugin.itemService.readTotalAffix(it, affixId) } ?: 0.0

    // ===== 输出 / MM =====
    @HostAccess.Export
    fun msg(playerId: String, text: String) {
        player(playerId)?.sendMessage(text.replace('&', '§'))
    }

    @HostAccess.Export
    fun mmCast(playerId: String, skill: String): Boolean {
        val p = player(playerId) ?: return false
        return mythic.castSkill(p, skill) == MythicMobsHook.CastResult.SUCCESS
    }

    @HostAccess.Export
    fun log(text: String) = plugin.logger.info("[skill-script] $text")

    // ===== 供宿主(SkillModListener)用，非脚本可见 =====
    fun activeSkillIds(): Set<String> = activeBySkill.keys.toSet()
    fun activePlayers(skillId: String): Set<UUID> = activeBySkill[skillId]?.toSet() ?: emptySet()
    fun deactivate(skillId: String, uid: UUID) { activeBySkill[skillId]?.remove(uid) }
}
