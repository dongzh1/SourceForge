// 月华斩（crescent_slash）— GraalJS 攻击技能。脚本 id 必须 == MOD id（mods/crescent_slash.yml）。
// 半圆弧斩击【示例技能】：演示新增的粒子技能轮子 sf.arcSlash——
//   左键挥砍 → 在身前画一道 180° 半圆弧（青白 dust 粒子，PacketEvents 一次性发包），
//   对扇形内活体造成「SF 基础伤害 × 倍率」并叠 1 层火元素（碰到的目标被执行元素技能）。
//
// 钩子：onActivate(playerId)  装进武器「左键」技能触发栏后，由左键挥砍触发（allowed-triggers:[left]）。
// 参数读 config.yml 的 crescent-slash 段；per-player 冷却存本脚本上下文（主线程单线程访问，安全）。

var nextAt = {};   // playerId -> 下次可斩时间(ms)

function cd()      { return Math.max(0, sf.cfgInt("crescent-slash.cooldown-ms", 600)); }
function radius()  { return Math.max(1.0, sf.cfgDouble("crescent-slash.radius", 4.5)); }
function arcDeg()  { return Math.max(10.0, sf.cfgDouble("crescent-slash.arc", 180.0)); }
function points()  { return Math.max(6, sf.cfgInt("crescent-slash.points", 28)); }
function dmgMult() { return Math.max(0.0, sf.cfgDouble("crescent-slash.dmg-mult", 1.6)); }
function minBase() { return Math.max(0.0, sf.cfgDouble("crescent-slash.min-base", 1.0)); }

function onActivate(playerId) {
    var now = sf.now();
    if ((nextAt[playerId] || 0) > now) return;   // 冷却中，吞掉这次挥砍
    nextAt[playerId] = now + cd();
    sf.skillCd(playerId, "&b月华斩", cd() / 1000.0);   // BetterHud 冷却弹窗

    // 伤害基准 = SF 基础伤害（无装备时退到 min-base），再乘倍率
    var base = sf.stat(playerId, "base_damage");
    if (base < minBase()) base = minBase();
    var mult = dmgMult();
    // 联动：突进后接斩 ×1.5；撤退后接斩 ×1.6（各消费一次一次性窗口）
    var combo = "";
    // 评审#9：联动倍率收敛，配合 base dmg-mult 1.3 使连斩峰值 ≤1.8×base(§5 主力斩上限)
    if (sf.comboConsume(playerId, "dash")) { mult *= 1.3; combo = " &e突进连斩!"; }
    else if (sf.comboConsume(playerId, "retreat")) { mult *= 1.35; combo = " &d反击斩!"; }
    var dmg = base * mult;

    sf.playSound(playerId, "ENTITY_PLAYER_ATTACK_SWEEP", 1.0, 0.9);

    // 半圆(180°) 弧，青白 dust 粒子，5 tick 扫动；命中造成 dmg 纯物理伤害，不打玩家。
    // 【设计原则】技能不自带元素：命中走 SF 战斗结算，武器上装了哪种元素属性MOD 就自动附加哪种元素
    // （ForgeListener 按武器元素词条×status_chance 触发），故这里 elementId 传 "" / 0。
    // 签名: arcSlash(playerId, radius, arcDeg, points, particle, colorHex, size, durationTicks,
    //                damage, elementId, elementStacks, hitPlayers) -> 命中数
    var hit = sf.arcSlash(
        playerId, radius(), arcDeg(), points(),
        "dust", "#66E0FF", 1.1, 5,
        dmg, "", 0, false
    );

    sf.actionBar(playerId, "&b月华斩" + combo + (hit > 0 ? "  &c命中 " + hit : ""));
}
