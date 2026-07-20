// 秘法交汇（arcane_comet）— 护甲被动技能槽。onAttack: 冷却好了就即时结算一次冲击波伤害
// (原版的"延迟落下"因脚本引擎没有延迟回调原语而简化为即时，见 yml 头注释)。

var readyAt = {};  // playerId -> 冷却结束时间(ms)

function cooldownMs(){ return Math.max(0, sf.cfgDouble("keystone.arcane-comet.cooldown-sec", 6) * 1000); }
function damage()    { return Math.max(0.0, sf.cfgDouble("keystone.arcane-comet.damage", 9.0)); }

function onAttack(playerId) {
    var now = sf.now();
    if ((readyAt[playerId] || 0) > now) return;
    readyAt[playerId] = now + cooldownMs();

    sf.playSound(playerId, "ENTITY_FIREWORK_ROCKET_BLAST", 0.6, 1.5);
    sf.waveRing(playerId, 0.3, 3.0, 8, 20, "SOUL_FIRE_FLAME", "#8A5CFF", 0.4, damage(), "", 0, false);
    sf.actionBar(playerId, "&b* 秘法交汇");
}
