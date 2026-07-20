// 电刑（electrocute）— 护甲被动技能槽。onAttack: 4秒窗口内命中3次(简化为"挥了3次")引爆额外伤害，
// 随后进入独立冷却，冷却期间不计数（不吞挥砍，只是不触发引爆）。脚本id必须 == MOD id。

var hits = {};      // playerId -> 窗口内已计次数
var windowAt = {};  // playerId -> 当前窗口开始时间(ms)
var readyAt = {};   // playerId -> 冷却结束时间(ms)

function windowMs()  { return Math.max(500, sf.cfgInt("keystone.electrocute.window-ms", 4000)); }
function cooldownMs(){ return Math.max(0, sf.cfgDouble("keystone.electrocute.cooldown-sec", 20) * 1000); }
function damage()    { return Math.max(0.0, sf.cfgDouble("keystone.electrocute.damage", 8.0)); }

function onAttack(playerId) {
    var now = sf.now();
    if ((readyAt[playerId] || 0) > now) return;  // 冷却中，不计数

    if ((now - (windowAt[playerId] || 0)) > windowMs()) {
        hits[playerId] = 0;
        windowAt[playerId] = now;
    }
    hits[playerId] = (hits[playerId] || 0) + 1;

    if (hits[playerId] >= 3) {
        hits[playerId] = 0;
        readyAt[playerId] = now + cooldownMs();
        sf.playSound(playerId, "ENTITY_LIGHTNING_BOLT_IMPACT", 0.5, 1.6);
        var hit = sf.slash(playerId, 3.0, 100.0, damage(), "ELECTRIC_SPARK", 20);
        sf.actionBar(playerId, "&c* 电刑 &7引爆" + (hit > 0 ? " &c命中 " + hit : ""));
    }
}
