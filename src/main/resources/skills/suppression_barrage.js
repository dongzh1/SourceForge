// 弹幕压制（suppression_barrage）— GraalJS。装进枪械「Shift+F」技能触发栏。
// Shift+F 同时抛出多枚弹丸，沿玩家面朝方向左右扇形均匀分布角度射出，每枚独立命中判定，
// 各自造成 base_damage×dmg-mult 纯物理伤害（元素随武器元素MOD命中时自动附加）。
// 钩子 onActivate(playerId)。数值在 config.yml 的 suppression-barrage 段（可选，未配置则用下面的默认值）。
//
// 【技术说明：为什么用 sf.beamAngled 而不是"临时转视角再转回来"】
// sf.beam 内部固定读取玩家【实际】视线方向(caster.location.direction)，没有角度偏移参数，
// 无法直接在同一次技能里朝不同方向连发。曾考虑的替代方案是：在脚本里临时把玩家 yaw 转到目标角度、
// 发一发 beam、再转回原来的 yaw，如此循环 5 次——但这条路线在当前 API 下走不通、也不理想：
//   1) 脚本沙箱只能看到 sf.* 暴露的方法(HostAccess.EXPLICIT)，摸不到 Bukkit Player 对象，
//      SfScriptApi 里目前没有 setRotation/getRotation 之类的原语，要走这条路同样得新增宿主函数，
//      并不比直接加一个角度偏移版 beam 更省事。
//   2) 即便加了 setRotation，同一 tick 内连续 5 次"转向→发包→转回"会真的改变玩家客户端朝向，
//      伴随视角快照/画面抖动、以及与移动同步包的竞态风险(玩家此刻的真实转头输入可能被瞬间覆盖又
//      恢复)，观感和安全性都不如"方向纯靠向量数学算出来，玩家朝向全程不变"的方案。
// 因此最终选择新增 sf.beamAngled(playerId, yawOffsetDeg, ...)：方向 = 玩家视线绕竖直轴旋转
// yawOffsetDeg 度，其余行为(起点/撞墙截断/命中判定/渲染)与 sf.beam 完全一致。该函数目前在
// SfScriptApi.kt / ParticleEmitter.kt 里还不存在，需要按开发报告给出的 diff 补充后才会生效。

var nextAt = {}; // playerId -> 下次可用(ms)

function cd()      { return Math.max(0, sf.cfgInt("suppression-barrage.cooldown-ms", 8000)); }
function mana()     { return Math.max(0.0, sf.cfgDouble("suppression-barrage.mana", 20.0)); }
function range()    { return Math.max(1.0, sf.cfgDouble("suppression-barrage.range", 20.0)); }
function pellets()  { return Math.max(1, sf.cfgInt("suppression-barrage.pellets", 5)); }
function halfSpread() { return Math.max(0.0, sf.cfgDouble("suppression-barrage.half-spread-deg", 15.0)); }
function dmgMult()  { return Math.max(0.0, sf.cfgDouble("suppression-barrage.dmg-mult", 0.5)); }

function onActivate(playerId) {
    var now = sf.now();
    if ((nextAt[playerId] || 0) > now) return;
    if (!sf.drainMana(playerId, mana())) { sf.actionBar(playerId, "&c能量不足"); return; }
    nextAt[playerId] = now + cd();
    sf.skillCd(playerId, "&6弹幕压制", cd() / 1000.0);

    var base = sf.stat(playerId, "base_damage");
    if (base < 1.0) base = 1.0;   // base 为0也保证每枚弹丸能正常发出命中，让武器元素MOD能附加
    var perHit = base * dmgMult();

    var n = pellets();
    var half = halfSpread();
    var hitTotal = 0;
    for (var i = 0; i < n; i++) {
        // -half ~ +half 范围内 n 枚均匀分布角度（n=1 时取正前方 0°）
        var offset = (n === 1) ? 0 : (-half + (2 * half * i) / (n - 1));
        // 每枚独立调用一次 beamAngled = 独立一次命中判定/独立一次伤害结算
        hitTotal += sf.beamAngled(playerId, offset, range(), "crit", "#FFAA00", 0.7, perHit, "", 0, false);
    }

    sf.playSound(playerId, "ITEM_CROSSBOW_SHOOT", 1.0, 1.6);
    sf.playSound(playerId, "ENTITY_GENERIC_EXPLODE", 0.35, 1.9);
    sf.particle(playerId, "SMOKE", 10);
    sf.actionBar(playerId, "&6弹幕压制 &7» &e" + n + "发扇形齐射" + (hitTotal > 0 ? "  &c命中 " + hitTotal : ""));
}
