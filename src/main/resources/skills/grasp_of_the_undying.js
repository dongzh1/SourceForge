// 不朽之握（grasp_of_the_undying）— 护甲被动技能槽。onAttack: 内部冷却好了(每N秒一次)，下一次
// 攻击附带额外真实伤害并回血；冷却中的攻击不触发（不吞挥砍，只是不额外结算）。

var readyAt = {};  // playerId -> 冷却结束时间(ms)

function intervalMs(){ return Math.max(500, sf.cfgDouble("keystone.grasp.interval-sec", 4) * 1000); }
function damage()     { return Math.max(0.0, sf.cfgDouble("keystone.grasp.damage", 7.0)); }
function healPct()    { return Math.max(0.0, sf.cfgDouble("keystone.grasp.heal-pct", 2.0)); }

function onAttack(playerId) {
    var now = sf.now();
    if ((readyAt[playerId] || 0) > now) return;
    readyAt[playerId] = now + intervalMs();

    sf.playSound(playerId, "BLOCK_ROOTED_DIRT_BREAK", 0.8, 0.8);
    sf.slash(playerId, 3.0, 100.0, damage(), "GLOW", 14);
    sf.healPercentAura(playerId, 0.0, healPct());
    sf.actionBar(playerId, "&2* 不朽之握");
}
