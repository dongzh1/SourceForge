// 超载重弹（overcharge_shot）— GraalJS。装进远程武器(弓/弩/枪)「Shift+右键」技能触发栏。
// Shift+右键 发射一枚慢速超载弹体，命中造成纯物理伤害(×2.4 base_damage，元素随武器元素MOD附加) + 强力击退。
// 钩子：onActivate(playerId)。
//
// 【依赖新脚本API】sf.projectileKb —— 在 sf.projectile 基础上追加"命中击退"（目标沿背离施法者方向被推开），
// 用于承载"命中 + 强力击退"这类远程重击效果。若该函数尚未落地（见本 MOD 报告【需要共享文件改动】），
// 自动退化为 sf.projectile（伤害照常生效，仅暂时没有击退），不会因为函数缺失而整体报错/不触发。
var nextAt = {}; // playerId -> 下次可用(ms)

function cd()        { return Math.max(0, sf.cfgInt("overcharge-shot.cooldown-ms", 9000)); }
function mana()       { return Math.max(0.0, sf.cfgDouble("overcharge-shot.mana", 24.0)); }
function dmgMult()   { return Math.max(0.0, sf.cfgDouble("overcharge-shot.dmg-mult", 2.4)); }
function speed()      { return Math.max(0.05, sf.cfgDouble("overcharge-shot.speed", 0.5)); }   // 慢速弹体
function range()      { return Math.max(1.0, sf.cfgDouble("overcharge-shot.range", 24.0)); }
function hitRadius()  { return Math.max(0.1, sf.cfgDouble("overcharge-shot.hit-radius", 0.9)); }
function knockback()  { return Math.max(0.0, sf.cfgDouble("overcharge-shot.knockback", 1.6)); }   // ≈2格击退
function knockUp()    { return Math.max(0.0, sf.cfgDouble("overcharge-shot.knockback-up", 0.25)); }

function onActivate(playerId) {
    var now = sf.now();
    if ((nextAt[playerId] || 0) > now) return;
    if (!sf.drainMana(playerId, mana())) { sf.actionBar(playerId, "&c能量不足"); return; }
    nextAt[playerId] = now + cd();
    sf.skillCd(playerId, "&6超载重弹", cd() / 1000.0);

    var base = sf.stat(playerId, "base_damage");
    if (base < 1.0) base = 1.0;   // base 为0也保证发出命中，让武器元素MOD能附加
    var dmg = base * dmgMult();

    sf.playSound(playerId, "ITEM_CROSSBOW_LOADING_END", 1.0, 0.6);
    sf.playSound(playerId, "ENTITY_GENERIC_EXPLODE", 0.4, 1.6);

    if (typeof sf.projectileKb === "function") {
        // 慢速超载弹体：金色粒子轨迹，命中纯物理伤害(不带元素) + 强力击退
        sf.projectileKb(playerId, speed(), range(), hitRadius(), "crit", "#FFCD46", 0.9, false,
            dmg, "", 0, knockback(), knockUp(), false);
    } else {
        // 退化路径：projectileKb 落地前，先保证伤害生效(暂无击退)
        sf.projectile(playerId, speed(), range(), hitRadius(), "crit", "#FFCD46", 0.9, false,
            dmg, "", 0, false);
    }
    sf.actionBar(playerId, "&6超载重弹 &7» &c蓄力发射!");
}
