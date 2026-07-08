// 冲刺突进（dash_thrust）— GraalJS。装进武器「右键」技能触发栏，右键突进。
// 沿视线冲刺 + 前方光束命中（纯物理 ×base_damage，元素由武器元素MOD命中时自动附加），
// 并开启"突进"联动窗口：3 秒内接月华斩(左键) 伤害 ×1.5。钩子 onActivate。
var nextAt = {};   // playerId -> 下次可用(ms)

function cd()      { return Math.max(0, sf.cfgInt("dash-thrust.cooldown-ms", 7000)); }
function power()   { return Math.max(0.2, sf.cfgDouble("dash-thrust.power", 1.4)); }
function up()      { return sf.cfgDouble("dash-thrust.up", 0.25); }
function range()   { return Math.max(1.0, sf.cfgDouble("dash-thrust.range", 6.0)); }
function dmgMult() { return Math.max(0.0, sf.cfgDouble("dash-thrust.dmg-mult", 1.0)); }
function mana()    { return Math.max(0.0, sf.cfgDouble("dash-thrust.mana", 18.0)); }
function windowS() { return Math.max(0.0, sf.cfgDouble("dash-thrust.combo-seconds", 3.0)); }

function onActivate(playerId) {
    var now = sf.now();
    if ((nextAt[playerId] || 0) > now) return;
    if (!sf.drainMana(playerId, mana())) { sf.actionBar(playerId, "&c能量不足"); return; }
    nextAt[playerId] = now + cd();
    sf.skillCd(playerId, "&f冲刺突进", cd() / 1000.0);

    var base = sf.stat(playerId, "base_damage");
    if (base < 1.0) base = 1.0;   // 评审#8：base 为0也保证发出命中，让武器元素MOD能附加
    sf.dash(playerId, power(), up());
    sf.playSound(playerId, "ENTITY_BREEZE_SHOOT", 1.0, 1.2);
    // 前方光束：视觉轨迹 + 命中纯物理（元素随武器元素MOD）；hitPlayers=false
    var hit = sf.beam(playerId, range(), "crit", "#FFFFFF", 0.9, base * dmgMult(), "", 0, false);
    sf.comboSet(playerId, "dash", windowS());   // 开"突进"联动窗口
    sf.actionBar(playerId, "&f冲刺突进 &7» &e可接月华斩" + (hit > 0 ? "  &c命中 " + hit : ""));
}
