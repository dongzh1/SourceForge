package com.dongzh1.sourceforge.hud

import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.player.PlayerQuitEvent
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 记录每个玩家「最近攻击的生物」的干净显示名，供 BetterHud 怪物血条弹窗使用。
 *
 * 为什么需要它：BetterHud 的 [entity_name] 取自 Paper 序列化后的名字串，自定义名（MM 怪）
 * 会带 § 旧版颜色码 → BetterHud 按 MiniMessage 解析时抛 "Legacy formatting codes" 异常 →
 * 整条弹窗渲染失败、血条消失。这里在玩家攻击瞬间把名字去码/翻译，经 PAPI
 * %sourceforge_target_name% 以纯文本喂给弹窗：既不崩，又能显示真实自定义名 / 原版中文名。
 */
object EntityTargetTracker : Listener {

    private data class Entry(val name: String, val at: Long)

    private val last = ConcurrentHashMap<UUID, Entry>()

    /** 名字保留时长：覆盖弹窗存活期即可，过期返回空串以免残留弹窗显示旧名。 */
    private const val TTL_MS = 5000L

    /** 匹配单个 § 颜色/格式码（含 §x 十六进制序列的每一对）。 */
    private val SECTION = Regex("§.")

    // LOWEST：抢在 BetterHud 的 entity_attack 触发器创建弹窗、快照 [papi:] 之前先把名字存好，
    // 否则弹窗会缓存到空值（手动 /papi parse 因晚于本事件故正常）。不忽略已取消事件——
    // 玩家挥砍即记名，与伤害是否最终生效无关。
    @EventHandler(priority = EventPriority.LOWEST)
    fun onDamage(event: EntityDamageByEntityEvent) {
        val victim = event.entity as? LivingEntity ?: return
        if (victim is Player) return // 不追踪 PvP 目标
        val player = resolvePlayer(event.damager) ?: return
        last[player.uniqueId] = Entry(displayName(victim), System.currentTimeMillis())
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        last.remove(event.player.uniqueId)
    }

    /** 玩家最近攻击生物的干净名字；无/过期返回空串。 */
    fun nameFor(uuid: UUID): String {
        val e = last[uuid] ?: return ""
        if (System.currentTimeMillis() - e.at > TTL_MS) return ""
        return e.name
    }

    private fun resolvePlayer(damager: Entity): Player? = when (damager) {
        is Player -> damager
        is Projectile -> damager.shooter as? Player
        else -> null
    }

    @Suppress("DEPRECATION")
    private fun displayName(victim: LivingEntity): String {
        // getCustomName() 返回带 § 的旧版串；去码得纯文本（如 "假人"）。
        val custom = victim.customName
        if (custom != null) {
            val plain = SECTION.replace(custom, "").trim()
            if (plain.isNotEmpty()) return plain
        }
        // 无自定义名（原版怪）：按类型键译中文，未收录则用键名去下划线兜底。
        val key = victim.type.key.key
        return CN[key] ?: key.replace('_', ' ')
    }

    /** 原版生物类型键（小写命名空间键）-> 中文名，仅在生物无自定义名时使用。 */
    private val CN: Map<String, String> = mapOf(
        "zombie" to "僵尸",
        "husk" to "尸壳",
        "drowned" to "溺尸",
        "zombie_villager" to "僵尸村民",
        "zombified_piglin" to "僵尸猪灵",
        "skeleton" to "骷髅",
        "stray" to "流浪者",
        "wither_skeleton" to "凋灵骷髅",
        "bogged" to "沼骸",
        "creeper" to "苦力怕",
        "spider" to "蜘蛛",
        "cave_spider" to "洞穴蜘蛛",
        "enderman" to "末影人",
        "endermite" to "末影螨",
        "silverfish" to "蠹虫",
        "witch" to "女巫",
        "slime" to "史莱姆",
        "magma_cube" to "岩浆怪",
        "blaze" to "烈焰人",
        "ghast" to "恶魂",
        "phantom" to "幻翼",
        "guardian" to "守卫者",
        "elder_guardian" to "远古守卫者",
        "shulker" to "潜影贝",
        "vex" to "恼鬼",
        "vindicator" to "卫道士",
        "evoker" to "唤魔者",
        "pillager" to "掠夺者",
        "ravager" to "劫掠兽",
        "illusioner" to "幻术师",
        "piglin" to "猪灵",
        "piglin_brute" to "猪灵蛮兵",
        "hoglin" to "疣猪兽",
        "zoglin" to "僵尸疣猪兽",
        "warden" to "监守者",
        "wither" to "凋灵",
        "ender_dragon" to "末影龙",
        "breeze" to "旋风人",
        "creaking" to "嘎枝",
        "pig" to "猪",
        "cow" to "牛",
        "sheep" to "绵羊",
        "chicken" to "鸡",
        "rabbit" to "兔子",
        "horse" to "马",
        "donkey" to "驴",
        "mule" to "骡",
        "llama" to "羊驼",
        "trader_llama" to "行商羊驼",
        "skeleton_horse" to "骷髅马",
        "zombie_horse" to "僵尸马",
        "wolf" to "狼",
        "cat" to "猫",
        "ocelot" to "豹猫",
        "fox" to "狐狸",
        "panda" to "熊猫",
        "polar_bear" to "北极熊",
        "bee" to "蜜蜂",
        "turtle" to "海龟",
        "dolphin" to "海豚",
        "cod" to "鳕鱼",
        "salmon" to "鲑鱼",
        "pufferfish" to "河豚",
        "tropical_fish" to "热带鱼",
        "squid" to "鱿鱼",
        "glow_squid" to "发光鱿鱼",
        "axolotl" to "美西螈",
        "goat" to "山羊",
        "frog" to "青蛙",
        "tadpole" to "蝌蚪",
        "allay" to "悦灵",
        "villager" to "村民",
        "wandering_trader" to "流浪商人",
        "iron_golem" to "铁傀儡",
        "snow_golem" to "雪傀儡",
        "mooshroom" to "哞菇",
        "bat" to "蝙蝠",
        "parrot" to "鹦鹉",
        "strider" to "炽足兽",
        "sniffer" to "嗅探兽",
        "camel" to "骆驼",
        "armadillo" to "犰狳",
        "armor_stand" to "盔甲架"
    )
}
