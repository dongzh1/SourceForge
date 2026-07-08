// 疾风撤退（wind_retreat）— GraalJS。装进武器「Shift+右键」技能触发栏。
// 向后跃 + 短暂迅捷，开"撤退"联动窗口(2.5s)：接月华斩(左键) 伤害 ×1.6。钩子 onActivate。
var nextAt = {};

function cd()        { return Math.max(0, sf.cfgInt("wind-retreat.cooldown-ms", 6000)); }
function power()     { return Math.max(0.2, sf.cfgDouble("wind-retreat.power", 1.2)); }
function up()        { return sf.cfgDouble("wind-retreat.up", 0.35); }
function mana()      { return Math.max(0.0, sf.cfgDouble("wind-retreat.mana", 16.0)); }
function speedTicks(){ return Math.max(0, sf.cfgInt("wind-retreat.speed-ticks", 40)); }
function windowS()   { return Math.max(0.0, sf.cfgDouble("wind-retreat.combo-seconds", 2.5)); }

function onActivate(playerId) {
    var now = sf.now();
    if ((nextAt[playerId] || 0) > now) return;
    if (!sf.drainMana(playerId, mana())) { sf.actionBar(playerId, "&c能量不足"); return; }
    nextAt[playerId] = now + cd();
    sf.skillCd(playerId, "&b疾风撤退", cd() / 1000.0);

    sf.leapBack(playerId, power(), up());
    if (speedTicks() > 0) sf.potion(playerId, "SPEED", speedTicks(), 1);
    sf.playSound(playerId, "ENTITY_ENDER_DRAGON_FLAP", 0.8, 1.5);
    sf.shape(playerId, "ring", 1.5, 20, "dust", "#8AF0FF", 0.9, false);  // 脚下气环
    sf.comboSet(playerId, "retreat", windowS());
    sf.actionBar(playerId, "&b疾风撤退 &7» &d可接反击斩");
}
