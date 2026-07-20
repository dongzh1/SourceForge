// 相位骤袭（phase_rush）— 护甲被动技能槽。onAttack: 4秒窗口内命中3次触发急速冲刺(速度提升+驱散
// 自身减速)，随后进入独立冷却，冷却期间不计数。脚本id必须 == MOD id。

var hits = {};      // playerId -> 窗口内已计次数
var windowAt = {};  // playerId -> 当前窗口开始时间(ms)
var readyAt = {};   // playerId -> 冷却结束时间(ms)

function windowMs()   { return Math.max(500, sf.cfgInt("keystone.phase-rush.window-ms", 4000)); }
function cooldownMs() { return Math.max(0, sf.cfgDouble("keystone.phase-rush.cooldown-sec", 15) * 1000); }
function durationTicks(){ return Math.max(1, Math.round(sf.cfgDouble("keystone.phase-rush.duration-sec", 3) * 20)); }
function amplifier()  { return Math.max(0, sf.cfgInt("keystone.phase-rush.amplifier", 1)); }

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
        sf.removePotion(playerId, "slow");
        sf.potion(playerId, "speed", durationTicks(), amplifier());
        sf.playSound(playerId, "ITEM_ELYTRA_FLYING", 0.5, 1.6);
        sf.actionBar(playerId, "&b* 相位骤袭 &7疾走中");
    }
}
