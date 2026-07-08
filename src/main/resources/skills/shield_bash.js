// 盾击反震（shield_bash）— GraalJS。装进武器「右键」技能触发栏（与冲刺突进二选一）。
// 前方扇形盾击：纯物理 ×base + 击退 + 短硬直(缓慢)。联动：若刚被铁壁架势格挡("破绽")，
// 伤害 ×2 且范围/击退加大(完美反击)。钩子 onActivate。
var nextAt = {};

function cd()      { return Math.max(0, sf.cfgInt("shield-bash.cooldown-ms", 4000)); }
function mana()    { return Math.max(0.0, sf.cfgDouble("shield-bash.mana", 12.0)); }
function radius()  { return Math.max(1.0, sf.cfgDouble("shield-bash.radius", 3.0)); }
function arc()     { return Math.max(30.0, sf.cfgDouble("shield-bash.arc", 120.0)); }
function dmgMult() { return Math.max(0.0, sf.cfgDouble("shield-bash.dmg-mult", 0.8)); }
function knock()   { return Math.max(0.0, sf.cfgDouble("shield-bash.knockback", 0.9)); }
function stunTicks(){ return Math.max(0, sf.cfgInt("shield-bash.stun-ticks", 15)); }

function onActivate(playerId) {
    var now = sf.now();
    if ((nextAt[playerId] || 0) > now) return;
    if (!sf.drainMana(playerId, mana())) { sf.actionBar(playerId, "&c能量不足"); return; }
    nextAt[playerId] = now + cd();
    sf.skillCd(playerId, "&e盾击反震", cd() / 1000.0);

    var base = sf.stat(playerId, "base_damage");
    if (base < 1.0) base = 1.0;   // 评审#8：base 为0也保证命中，让武器元素MOD能附加
    var mult = dmgMult();
    var r = radius();
    var kb = knock();
    var perfect = sf.comboConsume(playerId, "parry");
    if (perfect) { mult *= 2.0; r += 1.5; kb += 0.5; }   // 完美反击

    sf.playSound(playerId, "ITEM_SHIELD_BLOCK", 1.0, perfect ? 0.7 : 1.0);
    // 扇形命中(纯物理，元素随武器) + 全向击退 + 短硬直
    var hit = sf.arcSlash(playerId, r, arc(), 24, "crit", perfect ? "#FFE066" : "#CFCFCF", 1.0, 4, base * mult, "", 0, false);
    sf.knockbackNearby(playerId, r, kb, 0.35);
    if (stunTicks() > 0) sf.slowNearby(playerId, r, stunTicks(), 3);
    sf.actionBar(playerId, (perfect ? "&6完美反击!" : "&e盾击反震") + (hit > 0 ? " &c击中 " + hit : ""));
}
