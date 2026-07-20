// 强攻（press_the_attack）— 护甲被动技能槽。onAttack: 3秒内连续攻击(不超时)计数，第3击额外伤害+
// 目标减速；超时(3秒内没有下一次攻击)则从头计。脚本id必须 == MOD id。

var stacks = {};  // playerId -> 当前连续计数(1..3)
var lastAt = {};  // playerId -> 上次攻击时间(ms)

function chainMs()  { return Math.max(500, sf.cfgInt("keystone.press-the-attack.chain-ms", 3000)); }
function damage()   { return Math.max(0.0, sf.cfgDouble("keystone.press-the-attack.damage", 10.0)); }

function onAttack(playerId) {
    var now = sf.now();
    if ((now - (lastAt[playerId] || 0)) > chainMs()) stacks[playerId] = 0;
    lastAt[playerId] = now;
    stacks[playerId] = (stacks[playerId] || 0) + 1;

    if (stacks[playerId] >= 3) {
        stacks[playerId] = 0;
        sf.playSound(playerId, "ENTITY_PLAYER_ATTACK_CRIT", 0.6, 1.3);
        var hit = sf.slash(playerId, 3.0, 100.0, damage(), "CRIT", 16);
        sf.slowNearby(playerId, 3.0, 30, 1);
        sf.actionBar(playerId, "&6* 强攻" + (hit > 0 ? " &7命中 " + hit : ""));
    }
}
