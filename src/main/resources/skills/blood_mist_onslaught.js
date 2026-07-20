// 血雾冲阵（blood_mist_onslaught）— GraalJS。装进近战武器「Shift+F」技能触发栏（与 iron_stance 共用槽位，
// 防御/进攻二选一，槽位互斥由触发栏"一槽一MOD"结构天然保证）。
//
// onActivate 时间线：
//   1) 1.2秒免伤窗口——用「高等级抗性」模拟"无敌"（把常规伤害压到0；不含摔落/虚空/饥饿等原版例外来源，
//      与需求文档给出的"可以用潜在的抗性提升...模拟无敌"完全对应）。
//   2) 冲刺位移到落点。
//   3) 落点半径AOE(base_damage×1.8) + 命中目标4秒流血DOT(每秒当前生命值3%真实伤害)。
//
// 【方案选择：降级冲刺，非真无碰撞穿墙】
// 需求允许两种落地方式：A)真无敌+真穿墙位移；B)降级为"普通冲刺位移+落点AOE+DOT"。本实现选 B，
// 但把"普通冲刺位移"再往前做了一步精化：不是用摩擦力衰减的速度冲量(那样"落点"要等好几tick物理结算完
// 才确定，脚本引擎目前没有延迟/调度原语能等到那一刻)，而是用一次性的"沿视线直线步进探测、撞到不可通行
// 方块就提前停下"的瞬间传送(sf.dashTeleport，见下方【需要共享文件改动】)——不允许穿墙(每一步都会检测方块
// 是否可通行)，但位移是同步瞬间完成的，这样"落点"在脚本里当场就是确定值，AOE/DOT可以精确地打在真正的
// 落点而不是出发点。这比单纯 sf.dash() 冲量位移更贴合"位移到落点后造成AOE"的要求，同时依然不做真穿墙
// （不会把玩家瞬移进方块内部/穿透墙体），符合"不强求真正无碰撞穿墙"的降级许可。
//
// 【需要共享文件改动】SfScriptApi.kt 目前没有 dashTeleport / aoeBleed 这两个原语，本脚本调用前用
// typeof 探测——如果维护者还没打上报告里给出的 diff，会自动退化为纯 sf.dash()+sf.slash()（落点AOE
// 落在出发点附近、且没有流血DOT，但不会报错崩溃）。diff 内容见本次任务的最终报告。

var nextAt = {}; // playerId -> 下次可用(ms)

function cd()        { return Math.max(0, sf.cfgInt("blood_mist_onslaught.cooldown-ms", 12000)); }
function manaCost()  { return Math.max(0.0, sf.cfgDouble("blood_mist_onslaught.mana", 25.0)); }
function dashDist()  { return Math.max(1.0, sf.cfgDouble("blood_mist_onslaught.distance", 6.0)); }
function dashUp()    { return sf.cfgDouble("blood_mist_onslaught.up", 0.15); }
function radius()    { return Math.max(0.5, sf.cfgDouble("blood_mist_onslaught.radius", 2.5)); }
function dmgMult()   { return Math.max(0.0, sf.cfgDouble("blood_mist_onslaught.dmg-mult", 1.8)); }
function bleedSec()  { return Math.max(0.0, sf.cfgDouble("blood_mist_onslaught.bleed-seconds", 4.0)); }
function bleedPct()  { return Math.max(0.0, sf.cfgDouble("blood_mist_onslaught.bleed-percent", 0.03)); }
function immuneSec() { return Math.max(0.0, sf.cfgDouble("blood_mist_onslaught.immune-seconds", 1.2)); }

function onActivate(playerId) {
    var now = sf.now();
    if ((nextAt[playerId] || 0) > now) return;
    if (!sf.drainMana(playerId, manaCost())) { sf.actionBar(playerId, "&c能量不足"); return; }
    nextAt[playerId] = now + cd();
    sf.skillCd(playerId, "&c血雾冲阵", cd() / 1000.0);

    // 1) 免伤窗口：抗性拉满(amplifier=8 → 等级9 → 100%减伤，早已超过原版5级封顶的完全减伤线，
    //    留足冗余不依赖精确的减伤系数假设)，模拟"无敌"。
    var immuneTicks = Math.round(immuneSec() * 20);
    sf.potion(playerId, "resistance", immuneTicks, 8);
    sf.playSound(playerId, "ENTITY_ENDERMAN_TELEPORT", 0.8, 0.8);

    var base = sf.stat(playerId, "base_damage");
    if (base < 1.0) base = 1.0; // base=0也保证发出有效AOE
    var dmg = base * dmgMult();

    // 2) 位移：优先用"落点直接传送"(撞墙提前停下，不真穿墙)；引擎补丁落地前用普通冲量冲刺兜底。
    if (typeof sf.dashTeleport === "function") {
        sf.dashTeleport(playerId, dashDist(), dashUp());
    } else {
        sf.dash(playerId, Math.min(4.0, dashDist() / 2.0), dashUp());
    }

    // 3) 落点AOE + 流血DOT：优先用一体化原语；引擎补丁落地前退化为纯AOE(无DOT)。
    var hit;
    if (typeof sf.aoeBleed === "function") {
        hit = sf.aoeBleed(playerId, radius(), dmg, bleedSec(), bleedPct(), "SMOKE", "#8b0d1f");
    } else {
        hit = sf.slash(playerId, radius(), 360, dmg, "SMOKE", 14);
    }
    sf.particle(playerId, "SMOKE", 18);
    sf.playSound(playerId, "ENTITY_PLAYER_ATTACK_SWEEP", 1.0, 0.6);
    sf.actionBar(playerId, "&c血雾冲阵 &7» &f命中 " + hit + (hit > 0 ? " &7·流血中" : ""));
}
