// 震地（ground_slam）— GraalJS。装进武器「空中」技能触发栏（跳起后左键触发）。
// 向下猛砸 + 落地冲击环：纯物理 ×base + 击退（元素随武器元素MOD）。联动：铁壁架势开启中施放，
// 范围/击退/伤害加大，且落地获得短暂减伤(稳如泰山)。钩子 onActivate。
var nextAt = {};

function cd()       { return Math.max(0, sf.cfgInt("ground-slam.cooldown-ms", 6000)); }
function mana()     { return Math.max(0.0, sf.cfgDouble("ground-slam.mana", 18.0)); }
function radius()   { return Math.max(1.0, sf.cfgDouble("ground-slam.radius", 4.5)); }
function dmgMult()  { return Math.max(0.0, sf.cfgDouble("ground-slam.dmg-mult", 1.4)); }
function knock()    { return Math.max(0.0, sf.cfgDouble("ground-slam.knockback", 0.7)); }
function slamDown() { return Math.max(0.0, sf.cfgDouble("ground-slam.slam-down", 1.6)); }

function onActivate(playerId) {
    var now = sf.now();
    if ((nextAt[playerId] || 0) > now) return;
    if (!sf.drainMana(playerId, mana())) { sf.actionBar(playerId, "&c能量不足"); return; }
    nextAt[playerId] = now + cd();
    sf.skillCd(playerId, "&6震地", cd() / 1000.0);

    var base = sf.stat(playerId, "base_damage");
    if (base < 1.0) base = 1.0;   // 评审#8：base 为0也保证命中，让武器元素MOD能附加
    var mult = dmgMult(), r = radius(), kb = knock();
    var guard = sf.isActive("iron_stance", playerId);   // 守卫套内部联动
    if (guard) { r += 1.0; kb += 0.3; mult *= 1.2; }

    sf.dash(playerId, 0.15, -slamDown());   // 向下猛砸
    sf.playSound(playerId, "ENTITY_IRON_GOLEM_ATTACK", 1.0, 0.8);
    // 落地冲击环(纯物理，元素随武器) + 全向击退
    sf.waveRing(playerId, 0.5, r, 8, 40, "crit", guard ? "#FFC24A" : "#E0E0E0", 1.0, base * mult, "", 0, false);
    sf.knockbackNearby(playerId, r, kb, 0.4);
    if (guard) sf.potion(playerId, "DAMAGE_RESISTANCE", 30, 1);   // 稳如泰山
    sf.actionBar(playerId, guard ? "&6稳如泰山·震地!" : "&e震地!");
}
