// 黑暗收割（dark_harvest）— 护甲被动技能槽。onKillNearby: 附近死亡即收割一层灵魂(永久累加，封顶)。
// onAttack: 只对生命值低于阈值的目标生效——目标越接近死亡，处决打击伤害倍率越高；血量高于阈值的
// 目标不触发。靠 sf.targetHealthFraction() 读目标血量比例，恢复原版"处决"定位(此前版本没这个
// 原语，只能按固定层数倍率每击都打，不分对方血量高低)。层数存内存(不跨重登)，脚本id必须 == MOD id。

var souls = {};  // playerId -> 已收割层数

function maxStacks()   { return Math.max(1, sf.cfgInt("keystone.dark-harvest.max-stacks", 20)); }
function perStack()    { return Math.max(0.0, sf.cfgDouble("keystone.dark-harvest.per-stack", 0.6)); }
function baseDamage()  { return Math.max(0.0, sf.cfgDouble("keystone.dark-harvest.base-damage", 2.0)); }
function thresholdFrac(){ return Math.max(0.01, Math.min(1.0, sf.cfgDouble("keystone.dark-harvest.hp-threshold-pct", 50) / 100.0)); }
function targetRange() { return Math.max(1.0, sf.cfgDouble("keystone.dark-harvest.target-range", 4.0)); }

// dist 参数是宿主传的"死亡点到该玩家的距离"，SkillModListener 只在同世界才会调用本钩子；
// 15格内视为"参与了这场战斗"，比原版"造成击杀伤害"宽松，但脚本层拿不到伤害归属更精确的信号。
function onKillNearby(playerId, dist) {
    if (dist > sf.cfgDouble("keystone.dark-harvest.harvest-radius", 15.0)) return 1;
    var s = souls[playerId] || 0;
    if (s < maxStacks()) {
        souls[playerId] = s + 1;
        sf.playSound(playerId, "ENTITY_WITHER_HURT", 0.4, 1.5);
        sf.actionBar(playerId, "&4* 黑暗收割 &7灵魂 " + souls[playerId] + "/" + maxStacks());
    }
    return 1;
}

function onAttack(playerId) {
    var frac = sf.targetHealthFraction(playerId, targetRange());
    if (frac < 0 || frac > thresholdFrac()) return;  // 没瞄准到生物 / 目标血量还不够低，不触发

    var s = souls[playerId] || 0;
    var dmg = baseDamage() + s * perStack();
    // 血量越低倍率越高：刚好卡在阈值上是1倍，血量归零(濒死)时最高2倍。
    var mult = 1.0 + (thresholdFrac() - frac) / thresholdFrac();
    dmg *= mult;

    sf.playSound(playerId, "ENTITY_WITHER_AMBIENT", 0.5, 0.7);
    sf.slash(playerId, 3.0, 100.0, dmg, "SOUL", 10);
    sf.actionBar(playerId, "&4* 黑暗收割 &7处决打击 x" + mult.toFixed(2));
}
