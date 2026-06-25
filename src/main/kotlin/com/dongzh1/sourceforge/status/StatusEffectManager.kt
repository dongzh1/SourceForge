package com.dongzh1.sourceforge.status

import com.dongzh1.sourceforge.SourceForge
import org.bukkit.Bukkit
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Mob
import org.bukkit.entity.Player
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.util.Vector
import java.util.EnumMap
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 怪物异常状态管理器：按怪物 UUID 记录每种元素的层数与到期时间，并按 ElementEffect 结算各自效果。
 * - 触发：SF 命中（ForgeListener）按 status_chance 独立给每种（融合后的）元素叠层；MM 技能可复用 applyStacks。
 * - 结算：enable() 按 elements.yml 的 tick-period 调度 tick()，处理 DoT/减速/毒云/混乱、清理过期、喷粒子。
 * - 命中放大：病毒/腐蚀/磁力（AMP）通过 outgoingDamageMultiplier 在 ForgeListener 命中时放大伤害。
 */
class StatusEffectManager(private val plugin: SourceForge) {

    // radius：施法时按 ability_range 缩放后的有效半径，tick 时(无 source)也能用。
    private class State(var stacks: Int, var expireAt: Long, var radius: Double = 0.0)

    private val mobs = ConcurrentHashMap<UUID, EnumMap<ElementType, State>>()

    /** 内置触发 CD：key=(攻击者UUID, 怪UUID) -> 允许下次触发的时间戳。 */
    private val triggerCd = ConcurrentHashMap<Pair<UUID, UUID>, Long>()

    private fun cfg() = plugin.elementConfig

    /**
     * 触发闸门：同一(攻击者→怪)在 trigger-cooldown-ms 内只允许触发一次元素。
     * 返回 true 表示放行(并续上 CD)，false 表示在 CD 内、本次跳过。普攻与 MM 触发前都先过它，
     * 从而堵住「一刀走 SF+MM 双路径」「高频多段技能」对同一只怪的重复触发。
     */
    fun tryTriggerGate(attacker: Player, mob: LivingEntity): Boolean {
        val cdMs = cfg().triggerCooldownMs
        if (cdMs <= 0L) return true
        val now = System.currentTimeMillis()
        val key = attacker.uniqueId to mob.uniqueId
        val until = triggerCd[key]
        if (until != null && now < until) return false
        triggerCd[key] = now + cdMs
        return true
    }

    /** 给目标怪叠 [count] 层 [type]。allowChain=false 用于电连锁，避免无限递归。 */
    fun applyStacks(entity: LivingEntity, type: ElementType, count: Int, source: Player?, allowChain: Boolean = true, cause: String = "?") {
        val c = cfg()
        if (!c.enabled || count <= 0) return
        if (entity is Player) return
        if (entity.isDead || !entity.isValid) return
        val def = c.element(type) ?: return

        // 技能属性：持续时间吃 ability_duration、作用半径吃 ability_range（均按 1+属性 当倍率，从施法玩家读）。
        val durMult = 1.0 + (source?.let { plugin.itemService.readTotalAffix(it, "ability_duration") } ?: 0.0)
        val rangeMult = 1.0 + (source?.let { plugin.itemService.readTotalAffix(it, "ability_range") } ?: 0.0)

        val now = System.currentTimeMillis()
        val map = mobs.computeIfAbsent(entity.uniqueId) { EnumMap(ElementType::class.java) }
        val st = map.getOrPut(type) { State(0, 0L) }
        val before = st.stacks
        st.stacks = (st.stacks + count).coerceAtMost(c.stackCap)
        st.expireAt = now + (def.durationMs * durMult).toLong().coerceAtLeast(0L)
        st.radius = (def.radius * rangeMult).coerceAtLeast(0.0)

        // 触发瞬时效果
        var burstDamage = 0.0
        var chained = false
        when (def.effect) {
            ElementEffect.BURST -> {
                burstDamage = def.burstPerStack * st.stacks
                if (burstDamage > 0.0) damageMob(entity, burstDamage)
                if (def.stunTicks > 0) applySlow(entity, def.stunTicks, 6)
                // 电：连锁——给附近一只其它怪也叠 1 层（不再二次连锁）；半径吃 ability_range
                if (allowChain && st.radius > 0.0) {
                    nearestOther(entity, st.radius)?.let {
                        chained = true
                        applyStacks(it, type, 1, source, allowChain = false, cause = "连锁")
                    }
                }
            }
            ElementEffect.KNOCKBACK -> {
                if (source != null && def.knockback > 0.0) knockback(entity, source, def.knockback)
                if (def.stunTicks > 0) applySlow(entity, def.stunTicks, 4)
            }
            ElementEffect.CONFUSE -> confuse(entity, st.radius)
            ElementEffect.PULL -> pullNearby(entity, st.radius, def.knockback)
            else -> {}
        }
        if (c.visualEnabled) spawnParticle(entity, def, c.particleCount)

        // 详细 debug：每一次叠层都打来源、层数变化、本次瞬时伤害/增伤
        if (source != null && isDebug(source.uniqueId)) {
            val sb = StringBuilder("§8[元素] §7[$cause] §f${type.id} §7${before}→§a${st.stacks}§7层")
            when (def.effect) {
                ElementEffect.BURST ->
                    if (burstDamage > 0.0) sb.append(" §c瞬击=${"%.1f".format(burstDamage)} §8(${def.burstPerStack}×${st.stacks})${if (chained) " §b+连锁" else ""}")
                ElementEffect.AMP -> sb.append(" §d受伤×${"%.2f".format(1.0 + def.ampPerStack * st.stacks)}")
                ElementEffect.DOT, ElementEffect.GAS -> sb.append(" §e灼烧${"%.1f".format(def.dotPerStack * st.stacks)}/秒")
                else -> {}
            }
            source.sendMessage(sb.toString())
        }
    }

    /** 命中时对该怪的伤害放大倍率（病毒/腐蚀/磁力等 AMP 元素，按当前层数叠乘）。 */
    fun outgoingDamageMultiplier(entity: LivingEntity): Double {
        val map = mobs[entity.uniqueId] ?: return 1.0
        val c = cfg()
        var mult = 1.0
        for ((type, st) in map) {
            val def = c.element(type) ?: continue
            if (def.effect == ElementEffect.AMP && def.ampPerStack > 0.0) {
                mult *= (1.0 + def.ampPerStack * st.stacks)
            }
        }
        return mult
    }

    /**
     * 按玩家身上的总元素属性 + status_chance，对目标触发元素异常（融合后每元素独立 roll）。
     * 供 MM 伤害钩子复用（MM 技能/元素伤害命中怪时，按施法玩家的元素叠层）。
     */
    fun procFromPlayerTotals(player: Player, target: LivingEntity) {
        val c = cfg()
        if (!c.enabled || target is Player || target.isDead || !target.isValid) return
        val svc = plugin.itemService
        val baseVals = LinkedHashMap<ElementType, Double>()
        for (def in c.active) {
            if (!def.type.isBase) continue
            val v = svc.readTotalAffix(player, def.affix)
            if (v > 0.0) baseVals[def.type] = v
        }
        if (baseVals.isEmpty()) return
        val statusChance = svc.readTotalAffix(player, "status_chance")
        if (statusChance <= 0.0) return
        if (!tryTriggerGate(player, target)) {
            if (isDebug(player.uniqueId)) player.sendMessage("§8[元素] §7[MM] §8触发CD中,跳过")
            return
        }
        val guaranteed = kotlin.math.floor(statusChance).toInt()
        val frac = statusChance - guaranteed
        val debugOn = isDebug(player.uniqueId)
        val dbg = if (debugOn) StringBuilder() else null
        for ((type, _) in c.combine(baseVals)) {
            var procs = guaranteed
            if (frac > 0.0 && java.util.concurrent.ThreadLocalRandom.current().nextDouble() < frac) procs++
            if (procs > 0) applyStacks(target, type, procs, player, cause = "MM")
            dbg?.append("${type.id}×$procs ")
        }
        if (debugOn) {
            player.sendMessage(
                "§8[元素debug·MM命中] §7status=§f${"%.2f".format(statusChance)} §7触发=§a${dbg?.toString()?.trim()?.ifEmpty { "无" } ?: "无"} " +
                    "§7目标层数: §f${stacksSummary(target)}"
            )
        }
    }

    fun tick() {
        val c = cfg()
        val now = System.currentTimeMillis()
        if (triggerCd.isNotEmpty()) triggerCd.entries.removeIf { now >= it.value }   // 清理过期触发CD
        if (!c.enabled || mobs.isEmpty()) return
        val secPerTick = c.tickPeriod / 20.0

        val mobIt = mobs.entries.iterator()
        while (mobIt.hasNext()) {
            val entry = mobIt.next()
            val ent = Bukkit.getEntity(entry.key) as? LivingEntity
            if (ent == null || ent.isDead || !ent.isValid) { mobIt.remove(); continue }
            val map = entry.value

            val elIt = map.entries.iterator()
            while (elIt.hasNext()) {
                val me = elIt.next()
                val st = me.value
                if (now >= st.expireAt || st.stacks <= 0) { elIt.remove(); continue }
                val def = c.element(me.key) ?: continue
                when (def.effect) {
                    ElementEffect.DOT -> {
                        val dmg = def.dotPerStack * st.stacks * secPerTick
                        if (dmg > 0.0) damageMob(ent, dmg)
                    }
                    ElementEffect.GAS -> {
                        val dmg = def.dotPerStack * st.stacks * secPerTick
                        if (dmg > 0.0) {
                            damageMob(ent, dmg)
                            // 毒云：波及周围怪（半径吃 ability_range，已缩放存于 st.radius）
                            if (st.radius > 0.0) {
                                for (near in nearbyOthers(ent, st.radius)) damageMob(near, dmg)
                            }
                        }
                    }
                    ElementEffect.SLOW -> {
                        val amp = ((st.stacks - 1) / def.slowStacksPerLevel).coerceIn(0, def.slowMaxAmp)
                        applySlow(ent, (c.tickPeriod * 2).toInt(), amp)
                    }
                    ElementEffect.CONFUSE -> confuse(ent, st.radius)
                    else -> {} // BURST/AMP/KNOCKBACK 在 applyStacks/命中时结算
                }
                if (c.visualEnabled) spawnParticle(ent, def, c.particleCount)
            }
            if (map.isEmpty()) mobIt.remove()
        }
    }

    /** 无来源真伤式 DoT：不带 damager，避免重新进入 ForgeListener 命中逻辑造成二次触发。 */
    private fun damageMob(ent: LivingEntity, amount: Double) {
        if (amount <= 0.0 || ent.isDead) return
        ent.noDamageTicks = 0
        runCatching { ent.damage(amount) }
        ent.noDamageTicks = 0
    }

    private fun applySlow(ent: LivingEntity, durationTicks: Int, amplifier: Int) {
        runCatching {
            ent.addPotionEffect(
                PotionEffect(PotionEffectType.SLOWNESS, durationTicks, amplifier, true, false, false)
            )
        }
    }

    /** 击退：从攻击者方向把怪推开，带一点上抛。 */
    private fun knockback(ent: LivingEntity, source: Player, strength: Double) {
        runCatching {
            val dir = ent.location.toVector().subtract(source.location.toVector())
            dir.y = 0.0
            if (dir.lengthSquared() < 1e-6) dir.x = 1.0
            dir.normalize().multiply(strength)
            dir.y = 0.35
            ent.velocity = Vector(dir.x, dir.y, dir.z)
        }
    }

    /** 磁力·聚怪：把 radius 内的其它怪都拉向中心怪。 */
    private fun pullNearby(center: LivingEntity, radius: Double, strength: Double) {
        if (radius <= 0.0) return
        val s = if (strength > 0.0) strength else 0.5
        for (near in nearbyOthers(center, radius)) {
            runCatching {
                val dir = center.location.toVector().subtract(near.location.toVector())
                if (dir.lengthSquared() < 1e-6) return@runCatching
                dir.normalize().multiply(s)
                near.velocity = near.velocity.add(Vector(dir.x, 0.15, dir.z))
            }
        }
    }

    /** 混乱：让怪把目标改成附近的另一只怪（互相攻击）。 */
    private fun confuse(ent: LivingEntity, radius: Double) {
        val mob = ent as? Mob ?: return
        val r = if (radius > 0.0) radius else 8.0
        val other = nearestOther(ent, r) as? LivingEntity ?: return
        runCatching { mob.target = other }
    }

    private fun nearbyOthers(ent: LivingEntity, radius: Double): List<LivingEntity> =
        ent.getNearbyEntities(radius, radius, radius)
            .filterIsInstance<LivingEntity>()
            .filter { it !== ent && it !is Player && !it.isDead }

    private fun nearestOther(ent: LivingEntity, radius: Double): LivingEntity? =
        nearbyOthers(ent, radius).minByOrNull { it.location.distanceSquared(ent.location) }

    private fun spawnParticle(ent: LivingEntity, def: ElementDef, count: Int) {
        val p = def.particle ?: return
        if (count <= 0) return
        val loc = ent.location.add(0.0, ent.height * 0.6, 0.0)
        runCatching { ent.world.spawnParticle(p, loc, count, 0.3, 0.4, 0.3, 0.0) }
    }

    fun clearAll() = mobs.clear()

    // ===== 调试：/sf debug element <on|off> =====
    private val debugPlayers = java.util.concurrent.ConcurrentHashMap.newKeySet<UUID>()

    fun setDebug(uuid: UUID, on: Boolean) {
        if (on) debugPlayers.add(uuid) else debugPlayers.remove(uuid)
    }

    fun isDebug(uuid: UUID): Boolean = debugPlayers.contains(uuid)

    fun stacksSummary(entity: LivingEntity): String {
        val map = mobs[entity.uniqueId] ?: return "无"
        if (map.isEmpty()) return "无"
        return map.entries.joinToString(" ") { "${it.key.id}=${it.value.stacks}" }
    }
}
