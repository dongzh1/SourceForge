package com.dongzh1.sourceforge.script

import com.dongzh1.sourceforge.SourceForge
import com.dongzh1.sourceforge.enchant.MythicMobsHook
import com.dongzh1.sourceforge.particle.ParticleEmitter
import com.dongzh1.sourceforge.particle.ParticleGeometry
import com.dongzh1.sourceforge.particle.ParticleRenderer
import com.dongzh1.sourceforge.particle.SfParticle
import com.dongzh1.sourceforge.particle.SfTargeters
import com.dongzh1.sourceforge.particle.SkillAction
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import com.dongzh1.sourcejs.api.JsExport
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
    @JsExport
    fun isActive(skillId: String, playerId: String): Boolean {
        // 先解析 UUID 再查；非法 id 直接 false，避免把 null 丢进 ConcurrentHashMap 的 set.contains(null) 抛 NPE
        val uid = runCatching { UUID.fromString(playerId) }.getOrNull() ?: return false
        return activeBySkill[skillId]?.contains(uid) == true
    }

    @JsExport
    fun setActive(skillId: String, playerId: String, on: Boolean) {
        val uid = runCatching { UUID.fromString(playerId) }.getOrNull() ?: return
        val set = activeBySkill.computeIfAbsent(skillId) { ConcurrentHashMap.newKeySet() }
        if (on) set.add(uid) else set.remove(uid)
    }

    // ===== MANA =====
    @JsExport
    fun mana(playerId: String): Double = player(playerId)?.let { plugin.energyService.getEnergyCurrent(it) } ?: 0.0

    @JsExport
    fun manaMax(playerId: String): Double = player(playerId)?.let { plugin.energyService.getEnergyMax(it) } ?: 0.0

    @JsExport
    fun drainMana(playerId: String, amount: Double): Boolean =
        player(playerId)?.let { plugin.energyService.deductEnergy(it, amount) } ?: false

    // ===== 属性 =====
    @JsExport
    fun stat(playerId: String, affixId: String): Double =
        player(playerId)?.let { plugin.itemService.readTotalAffix(it, affixId) } ?: 0.0

    // ===== 光环/群体（半径内玩家聚合，供光环类技能用，如 hearth_whisper）=====
    /** 半径内的【其他】在线玩家数（不含自己，同世界，直线距离），供光环类技能按受益人数追加耗能。 */
    @JsExport
    fun nearbyAllyCount(playerId: String, radius: Double): Int {
        val p = player(playerId) ?: return 0
        val r = radius.coerceIn(0.0, 64.0)
        return p.getNearbyEntities(r, r, r).count { it is Player && !it.isDead }
    }

    /**
     * 光环回血：给施法者自己 + 半径内其他玩家，各自按【自身】最大生命值百分比([percentPerSecond]，
     * 如 1.8 表示 1.8%)回复生命，各自封顶自身满血。
     * 简化实现：无队伍/公会系统对接，半径内所有玩家均受益，不区分队友(见 hearth_whisper MOD 说明)。
     * 返回本次受益人数（含自己）。
     */
    @JsExport
    fun healPercentAura(playerId: String, radius: Double, percentPerSecond: Double): Int {
        val p = player(playerId) ?: return 0
        val pct = (percentPerSecond / 100.0).coerceIn(0.0, 1.0)
        if (pct <= 0.0) return 0
        val r = radius.coerceIn(0.0, 64.0)
        val targets = ArrayList<Player>()
        targets.add(p)
        for (e in p.getNearbyEntities(r, r, r)) {
            if (e is Player && e.uniqueId != p.uniqueId && !e.isDead) targets.add(e)
        }
        var affected = 0
        for (t in targets) {
            val maxHealth = t.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH)?.value ?: t.health
            if (maxHealth <= 0.0) continue
            affected++
            if (t.health >= maxHealth) continue
            t.health = (t.health + maxHealth * pct).coerceAtMost(maxHealth)
        }
        return affected
    }

    // ===== 输出 / MM =====
    @JsExport
    fun msg(playerId: String, text: String) {
        player(playerId)?.sendMessage(text.replace('&', '§'))
    }

    @JsExport
    fun mmCast(playerId: String, skill: String): Boolean {
        val p = player(playerId) ?: return false
        return mythic.castSkill(p, skill) == MythicMobsHook.CastResult.SUCCESS
    }

    @JsExport
    fun log(text: String) = plugin.logger.info("[skill-script] $text")

    // ===== 时间 / 配置 =====
    @JsExport
    fun now(): Long = System.currentTimeMillis()

    @JsExport
    fun cfgInt(path: String, def: Int): Int = plugin.config.getInt(path, def)

    @JsExport
    fun cfgDouble(path: String, def: Double): Double = plugin.config.getDouble(path, def)

    // ===== 表现：音效 / 药水 / 粒子 / 动作栏 =====
    @JsExport
    fun playSound(playerId: String, sound: String, volume: Double, pitch: Double) {
        val p = player(playerId) ?: return
        runCatching {
            p.playSound(p.location, org.bukkit.Sound.valueOf(sound.uppercase()), volume.toFloat(), pitch.toFloat())
        }
    }

    /** legacy 药水名 → 1.21 registry 名（Paper 1.21 的 getByName 已无 legacy 兼容，"SLOW"/"DAMAGE_RESISTANCE" 会返回 null）。 */
    private fun potionType(name: String): org.bukkit.potion.PotionEffectType? {
        val key = when (name.trim().lowercase()) {
            "slow", "slowness" -> "slowness"
            "damage_resistance", "resistance" -> "resistance"
            "fast_digging", "haste" -> "haste"
            "slow_digging", "mining_fatigue" -> "mining_fatigue"
            "increase_damage", "strength" -> "strength"
            "jump", "jump_boost" -> "jump_boost"
            "confusion", "nausea" -> "nausea"
            "heal", "instant_health" -> "instant_health"
            "harm", "instant_damage" -> "instant_damage"
            else -> name.trim().lowercase()
        }
        return org.bukkit.potion.PotionEffectType.getByName(key)
    }

    @JsExport
    fun potion(playerId: String, type: String, ticks: Int, amplifier: Int) {
        val p = player(playerId) ?: return
        runCatching {
            val t = potionType(type) ?: return
            p.addPotionEffect(org.bukkit.potion.PotionEffect(t, ticks, amplifier, true, false, false))
        }
    }

    @JsExport
    fun removePotion(playerId: String, type: String) {
        val p = player(playerId) ?: return
        runCatching {
            val t = potionType(type) ?: return
            p.removePotionEffect(t)
        }
    }

    @JsExport
    fun particle(playerId: String, particle: String, count: Int) {
        val p = player(playerId) ?: return
        runCatching {
            p.world.spawnParticle(org.bukkit.Particle.valueOf(particle.uppercase()), p.location.add(0.0, 1.0, 0.0), count, 0.4, 0.5, 0.4, 0.15)
        }
    }

    @JsExport
    fun actionBar(playerId: String, text: String) {
        val p = player(playerId) ?: return
        val msg = text.replace('&', '§')
        runCatching {
            p.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR, net.md_5.bungee.api.chat.TextComponent(msg))
        }.onFailure { p.sendMessage(msg) }
    }

    // ===== 位移原语（突进/撤退）=====
    /** 突进：沿视线水平方向给玩家冲量，[up] 附加上抛。用于突进类位移技能。 */
    @JsExport
    fun dash(playerId: String, power: Double, up: Double) {
        val p = player(playerId) ?: return
        val dir = p.location.direction.setY(0.0)
        if (dir.lengthSquared() < 1e-6) return
        dir.normalize().multiply(power.coerceIn(0.0, 4.0))
        p.velocity = org.bukkit.util.Vector(dir.x, up.coerceIn(-2.0, 2.0), dir.z)
        p.fallDistance = 0f
    }

    /**
     * 破坏玩家瞄准的方块，仅当其类型与 [materialName]（不区分大小写，如 "OBSIDIAN"）匹配才会真正破坏，
     * 用玩家主手物品当破坏工具传入 breakNaturally（保证镐子等级/附魔/掉落规则跟原版一致）。
     * 未瞄准任何方块、或类型不符时不破坏，返回 false（黑曜石爆破等技能据此判断是否进入冷却）。
     */
    @JsExport
    fun breakTargetBlock(playerId: String, range: Double, materialName: String): Boolean {
        val p = player(playerId) ?: return false
        val target = p.getTargetBlockExact(range.toInt().coerceIn(1, 32)) ?: return false
        val material = org.bukkit.Material.matchMaterial(materialName) ?: return false
        if (target.type != material) return false
        return target.breakNaturally(p.inventory.itemInMainHand)
    }

    /**
     * 玩家瞄准方向 [range] 格内最近一个存活生物实体的生命值占最大生命值比例(0.0~1.0)；
     * 没瞄准到任何生物/无法读取最大生命值时返回 -1.0，脚本用负数判断"没有效目标"（如黑暗收割
     * 靠这个读目标血量决定要不要打出处决打击，之前版本没有这个原语只能按固定倍率打，见MOD说明）。
     */
    @JsExport
    fun targetHealthFraction(playerId: String, range: Double): Double {
        val p = player(playerId) ?: return -1.0
        val target = p.getTargetEntity(range.toInt().coerceIn(1, 32)) as? org.bukkit.entity.LivingEntity ?: return -1.0
        if (target.isDead) return -1.0
        val max = target.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH)?.value ?: return -1.0
        if (max <= 0.0) return -1.0
        return (target.health / max).coerceIn(0.0, 1.0)
    }

    /** 撤退：沿视线【反方向】给冲量（后跃），[up] 附加上抛；顺带清坠落距离避免摔伤。 */
    @JsExport
    fun leapBack(playerId: String, power: Double, up: Double) {
        val p = player(playerId) ?: return
        val dir = p.location.direction.setY(0.0)
        if (dir.lengthSquared() < 1e-6) return
        dir.normalize().multiply(-power.coerceIn(0.0, 4.0))
        p.velocity = org.bukkit.util.Vector(dir.x, up.coerceIn(-2.0, 2.0), dir.z)
        p.fallDistance = 0f
    }

    /** 以玩家为心、[radius] 内敌对活体从玩家背离方向击退 [strength]（[up] 上抛）。用于盾击/震地。 */
    @JsExport
    fun knockbackNearby(playerId: String, radius: Double, strength: Double, up: Double) {
        val p = player(playerId) ?: return
        val origin = p.location
        for (e in SfTargeters.radius(origin, radius.coerceIn(0.0, 32.0), p, SfTargeters.Filter.ENEMIES)) {
            val d = e.location.toVector().subtract(origin.toVector())
            d.y = 0.0
            if (d.lengthSquared() < 1e-6) d.x = 1.0
            d.normalize().multiply(strength.coerceIn(0.0, 4.0))
            runCatching { e.velocity = org.bukkit.util.Vector(d.x, up.coerceIn(-1.0, 2.0), d.z) }
        }
    }

    /** 以玩家为心、[radius] 内敌对活体加 [slowTicks] tick 的缓慢([amp])，近似硬直/眩晕。 */
    @JsExport
    fun slowNearby(playerId: String, radius: Double, slowTicks: Int, amp: Int) {
        val p = player(playerId) ?: return
        val slow = org.bukkit.potion.PotionEffectType.SLOWNESS
        for (e in SfTargeters.radius(p.location, radius.coerceIn(0.0, 32.0), p, SfTargeters.Filter.ENEMIES)) {
            runCatching {
                e.addPotionEffect(org.bukkit.potion.PotionEffect(slow, slowTicks.coerceAtLeast(1), amp.coerceAtLeast(0), true, false, false))
            }
        }
    }

    // ===== 跨技能联动窗口（玩家级 tag，供不同技能MOD互读，实现连招联动）=====
    private val comboTags = ConcurrentHashMap<String, Long>()  // "uuid key" -> 到期(ms)
    private fun comboKey(playerId: String, key: String) = "$playerId $key"

    /** 给玩家挂一个联动 tag，[seconds] 秒后自动失效（如突进后开一个"可接斩"窗口）。 */
    @JsExport
    fun comboSet(playerId: String, key: String, seconds: Double) {
        comboTags[comboKey(playerId, key)] = System.currentTimeMillis() + (seconds.coerceAtLeast(0.0) * 1000).toLong()
    }

    /** 该玩家的联动 tag 是否仍生效（只读，不清除）。 */
    @JsExport
    fun comboActive(playerId: String, key: String): Boolean {
        val exp = comboTags[comboKey(playerId, key)] ?: return false
        if (System.currentTimeMillis() > exp) { comboTags.remove(comboKey(playerId, key)); return false }
        return true
    }

    /** 消费一次联动 tag：生效则返回 true 并立即清除（用于"一次性"联动，如撤退后下一斩必暴）。 */
    @JsExport
    fun comboConsume(playerId: String, key: String): Boolean {
        val exp = comboTags.remove(comboKey(playerId, key)) ?: return false
        return System.currentTimeMillis() <= exp
    }

    // ===== BetterHud 技能冷却弹窗（复用 MM 技能同款 popup，迁移到MOD技能用）=====
    /** 弹出/更新技能冷却弹窗。[displayName] 作堆叠 key 与显示名，[seconds] 冷却秒数。 */
    @JsExport
    fun skillCd(playerId: String, displayName: String, seconds: Double) {
        val p = player(playerId) ?: return
        val popup = plugin.forgeConfig.betterHud.skillCdPopup
        val ticks = (seconds.coerceAtLeast(0.0) * 20).toLong()
        com.dongzh1.sourceforge.hud.BetterHudHook.showSkillCd(plugin, p, popup, displayName, seconds, ticks)
    }

    /**
     * 前方弧形 AoE 斩击：对玩家朝向 arcDeg 度扇形内、radius 格内的存活实体造成 damage 伤害（走 SF 战斗结算）。
     * 命中数返回给脚本。会在身前喷指定粒子。
     */
    @JsExport
    fun slash(playerId: String, radius: Double, arcDeg: Double, damage: Double, particle: String, count: Int): Int {
        val p = player(playerId) ?: return 0
        val dir = p.location.direction.setY(0.0)
        if (dir.lengthSquared() < 1e-6) return 0
        dir.normalize()
        val halfArc = arcDeg / 2.0
        var hit = 0
        for (e in p.getNearbyEntities(radius, radius, radius)) {
            if (e === p || e !is org.bukkit.entity.LivingEntity || e.isDead) continue
            val to = e.location.toVector().subtract(p.location.toVector()).setY(0.0)
            val toN = if (to.lengthSquared() < 1e-6) dir.clone() else to.normalize()
            val angle = Math.toDegrees(Math.acos(dir.dot(toN).coerceIn(-1.0, 1.0)))
            if (angle <= halfArc) {
                runCatching { e.damage(damage.coerceAtLeast(0.0), p) }
                hit++
            }
        }
        runCatching {
            val pt = org.bukkit.Particle.valueOf(particle.uppercase())
            val center = p.location.add(0.0, 1.0, 0.0).add(dir.clone().multiply(1.5))
            p.world.spawnParticle(pt, center, count, 1.0, 0.5, 1.0, 0.05)
        }
        return hit
    }

    // ===== 粒子技能轮子（PacketEvents 发包，碰撞可对目标执行技能） =====
    // 渲染器/发射器延迟创建：首次用到才建，确保此时 PacketEvents 插件已 enable、后端选择正确。
    private val renderer: ParticleRenderer by lazy { ParticleRenderer(plugin) }
    private val emitter: ParticleEmitter by lazy { ParticleEmitter(plugin, renderer) }

    /** 颜色解析：支持 "#RRGGBB" / "RRGGBB" / 常见颜色名，返回不透明 ARGB。空/非法回落白色。 */
    private fun parseColorArgb(raw: String?): Int {
        if (raw.isNullOrBlank()) return 0xFFFFFFFF.toInt()
        val hex = raw.trim().removePrefix("#")
        if (hex.length in 1..6) hex.toIntOrNull(16)?.let { return (0xFF shl 24) or (it and 0xFFFFFF) }
        val rgb = when (raw.trim().uppercase()) {
            "RED" -> 0xFF5555; "GREEN", "LIME" -> 0x55FF55; "AQUA", "CYAN" -> 0x55FFFF
            "YELLOW" -> 0xFFFF55; "BLUE" -> 0x5555FF; "PINK", "MAGENTA", "LIGHT_PURPLE" -> 0xFF55FF
            "GOLD", "ORANGE" -> 0xFFAA00; "PURPLE" -> 0xAA00AA; "WHITE" -> 0xFFFFFF; "BLACK" -> 0x101010
            else -> 0xFFFFFF
        }
        return (0xFF shl 24) or rgb
    }

    private fun brushOf(particle: String, colorHex: String, size: Double, count: Int = 1): SfParticle {
        val argb = parseColorArgb(colorHex)
        return SfParticle(
            type = particle, count = count.coerceAtLeast(0),
            colorArgb = argb, toColorArgb = argb, size = size.toFloat().coerceIn(0.05f, 4.0f)
        )
    }

    private fun filterOf(hitPlayers: Boolean): SfTargeters.Filter =
        if (hitPlayers) SfTargeters.Filter.ALL_LIVING else SfTargeters.Filter.ENEMIES

    /** 由伤害/元素组合出命中动作（SF 原生“对被命中目标执行技能”）。都为空则得到一个空动作。 */
    private fun onHit(damage: Double, elementId: String, elementStacks: Int): SkillAction {
        val list = ArrayList<SkillAction>(2)
        if (damage > 0.0) list.add(emitter.actions.damage(damage))
        if (elementId.isNotBlank()) list.add(emitter.actions.element(elementId, elementStacks))
        return emitter.actions.all(*list.toTypedArray())
    }

    /**
     * 半圆弧斩击（旗舰轮子）：玩家身前 [arcDeg] 度、[radius] 格扇形，命中活体造成 [damage] 伤害并叠 [elementStacks] 层元素 [elementId]。
     * 弧线以 [particle]/[colorHex]/[size] 画出，[durationTicks] 帧扫动。返回命中数。elementId 传 "" 表示不叠元素。
     */
    @JsExport
    fun arcSlash(
        playerId: String, radius: Double, arcDeg: Double, points: Int,
        particle: String, colorHex: String, size: Double, durationTicks: Int,
        damage: Double, elementId: String, elementStacks: Int, hitPlayers: Boolean
    ): Int {
        val p = player(playerId) ?: return 0
        return runCatching {
            emitter.arcSlash(
                p, radius, arcDeg, points, 0.0, 1.0,
                brushOf(particle, colorHex, size), durationTicks, true,
                filterOf(hitPlayers), 0, onHit(damage, elementId, elementStacks)
            )
        }.getOrDefault(0)
    }

    /**
     * 直线弹体：从玩家视线发射，[speed] 格/tick、最大 [range] 格，碰到活体造成伤害/叠元素。[pierce]=true 穿透。
     */
    @JsExport
    fun projectile(
        playerId: String, speed: Double, range: Double, hitRadius: Double,
        particle: String, colorHex: String, size: Double, pierce: Boolean,
        damage: Double, elementId: String, elementStacks: Int, hitPlayers: Boolean
    ) {
        val p = player(playerId) ?: return
        runCatching {
            emitter.projectile(
                p, speed, range, hitRadius, 0.3, pierce, 0, true,
                brushOf(particle, colorHex, size), 0.8,
                filterOf(hitPlayers), onHit(damage, elementId, elementStacks)
            )
        }
    }

    /**
     * 瞬发光束：从玩家眼睛沿视线 [range] 格，撞方块截断，命中沿途所有活体。返回命中数。
     */
    @JsExport
    fun beam(
        playerId: String, range: Double, particle: String, colorHex: String, size: Double,
        damage: Double, elementId: String, elementStacks: Int, hitPlayers: Boolean
    ): Int {
        val p = player(playerId) ?: return 0
        return runCatching {
            emitter.beam(
                p, range, 0.4, 0.4, brushOf(particle, colorHex, size),
                true, true, 0, 3, filterOf(hitPlayers), onHit(damage, elementId, elementStacks)
            )
        }.getOrDefault(0)
    }

    /**
     * 地面扩散冲击环：以玩家脚下为心，半径 [rStart]→[rEnd] 扩散，波锋扫过的活体各命中一次。
     */
    @JsExport
    fun waveRing(
        playerId: String, rStart: Double, rEnd: Double, durationTicks: Int, points: Int,
        particle: String, colorHex: String, size: Double,
        damage: Double, elementId: String, elementStacks: Int, hitPlayers: Boolean
    ) {
        val p = player(playerId) ?: return
        runCatching {
            emitter.waveRing(
                p.location, p, rStart, rEnd, durationTicks, points, 0.2,
                brushOf(particle, colorHex, size), filterOf(hitPlayers), 0,
                onHit(damage, elementId, elementStacks)
            )
        }
    }

    /**
     * 纯视觉形状：在玩家身上画一个几何形状（不结算命中）。[shapeName]∈ line/circle/ring/arc/semicircle/sphere/helix/disc。
     * [faceLook]=true 形状正对视线方向，否则平躺（法线朝上）。
     */
    @JsExport
    fun shape(
        playerId: String, shapeName: String, radius: Double, points: Int,
        particle: String, colorHex: String, size: Double, faceLook: Boolean
    ) {
        val p = player(playerId) ?: return
        runCatching {
            val offsets = when (shapeName.trim().lowercase()) {
                "line", "beam" -> ParticleGeometry.line(radius, points, true)
                "circle", "ring" -> ParticleGeometry.circle(radius, points)
                "arc", "semicircle" -> ParticleGeometry.arc(radius, 0.0, 180.0, points)
                "sphere" -> ParticleGeometry.sphere(radius, points)
                "helix" -> ParticleGeometry.helix(radius, radius * 2.0, 3.0, points, false)
                "disc" -> ParticleGeometry.disc(radius, 4, points)
                else -> ParticleGeometry.circle(radius, points)
            }
            val dir = if (faceLook) p.location.direction else org.bukkit.util.Vector(0.0, 1.0, 0.0)
            val oriented = ParticleGeometry.orientAll(offsets, dir)
            renderer.renderLocal(p.location.add(0.0, 1.0, 0.0), oriented, brushOf(particle, colorHex, size))
        }
    }

    // ===== per-(skill,player) 状态存储（评审 #6：避免脚本把每玩家状态放共享 JS 全局导致跨玩家串）=====
    // 键 = "skillId\u0000playerId\u0000key"（playerId 为 UUID，不含 \u0000，可安全用作分隔）。
    private val scriptState = ConcurrentHashMap<String, String>()

    private fun stateKey(skillId: String, playerId: String, key: String) = "$skillId\u0000$playerId\u0000$key"

    @JsExport
    fun stateGet(skillId: String, playerId: String, key: String): String? = scriptState[stateKey(skillId, playerId, key)]

    @JsExport
    fun stateSet(skillId: String, playerId: String, key: String, value: String) {
        scriptState[stateKey(skillId, playerId, key)] = value
    }

    @JsExport
    fun stateClear(skillId: String, playerId: String, key: String) {
        scriptState.remove(stateKey(skillId, playerId, key))
    }

    // ===== 供宿主(SkillModListener)用，非脚本可见 =====
    /** 玩家退出时清掉其全部脚本状态。 */
    fun clearPlayerState(playerId: String) {
        val mid = "\u0000$playerId\u0000"
        scriptState.keys.removeIf { it.contains(mid) }
        comboTags.keys.removeIf { it.startsWith("$playerId ") }
    }

    fun activeSkillIds(): Set<String> = activeBySkill.keys.toSet()
    fun activePlayers(skillId: String): Set<UUID> = activeBySkill[skillId]?.toSet() ?: emptySet()
    fun deactivate(skillId: String, uid: UUID) { activeBySkill[skillId]?.remove(uid) }
}
