// 灵光召唤（summon_aery）— 护甲被动技能槽。onAttack: 冷却好了就发一枚飞行弹体附带额外魔法伤害
// (只保留伤害半边效果，护盾半边脚本层拿不到"打的是友是敌"信号，见 yml 头注释)。

var readyAt = {};  // playerId -> 冷却结束时间(ms)

function cooldownMs(){ return Math.max(0, sf.cfgDouble("keystone.summon-aery.cooldown-sec", 8) * 1000); }
function damage()    { return Math.max(0.0, sf.cfgDouble("keystone.summon-aery.damage", 6.0)); }

function onAttack(playerId) {
    var now = sf.now();
    if ((readyAt[playerId] || 0) > now) return;
    readyAt[playerId] = now + cooldownMs();

    sf.playSound(playerId, "ENTITY_ALLAY_AMBIENT_WITH_ITEM", 0.7, 1.4);
    sf.projectile(playerId, 0.9, 16.0, 0.8, "END_ROD", "#7FE8FF", 0.35, false, damage(), "", 0, false);
    sf.actionBar(playerId, "&b* 灵光召唤");
}
