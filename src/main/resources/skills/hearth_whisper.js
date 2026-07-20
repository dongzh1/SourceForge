// 篝火低语（hearth_whisper）— 护甲被动技能槽首发卡。装进护甲被动技能槽后装备即生效，不再有
// 开关概念：SkillModListener.autoActivatePassiveSkills() 每秒自动 setActive(true)，卸下装备后
// 同一套"装备已移除"检测自动 setActive(false)，本脚本因此不再实现 onActivate，只留 onTick。
//
// 机制：只要佩戴者【静止或缓慢移动】，就以自身为心 radius 格光环，每秒为光环内的玩家(含自己；
// 无队伍系统，按"光环覆盖范围内的其他玩家"计)按各自最大生命值百分比回复生命。移动过快时暂停
// 回复(不关闭技能，宿主端"装备已移除"以外不会主动停用)。护甲被动技能槽不设资源消耗，因此这里
// 不再有 drainMana/耗能相关逻辑——常驻被动没有"能量耗尽自动关闭"这回事。
//
// 依赖【尚未落地】的脚本API（未直接改 SfScriptApi.kt，调用处按下方 typeof 检查静默降级）：
//   sf.isSlowMoving(playerId, maxBlocksPerSecond): boolean — 距上次调用(约1次/秒)水平移动是否≤阈值；
//        缺失时按"未限制移动"处理，回复不受移速门控
//   sf.healPercentAura(playerId, radius, percentPerSecond): int — 给自己+半径内其他玩家按各自最大
//        生命值百分比回血(各自封顶满血)；缺失时只剩粒子表现，不会真的回血

function radius()        { return Math.max(0.0, sf.cfgDouble("hearth-whisper.radius", 6.0)); }
function regenPercent()  { return Math.max(0.0, sf.cfgDouble("hearth-whisper.regen-percent-per-second", 1.1)); }
function moveThreshold() { return Math.max(0.0, sf.cfgDouble("hearth-whisper.move-threshold-bps", 1.5)); }

// 每秒结算：静止/缓慢移动才回血；移动过快则只保留一圈提示粒子，不回血也不关闭。
function onTick(playerId) {
    var slow = (typeof sf.isSlowMoving === "function") ? sf.isSlowMoving(playerId, moveThreshold()) : true;
    if (!slow) {
        sf.shape(playerId, "circle", 1.0, 10, "smoke", "#523f26", 0.4, false);
        return;
    }

    if (typeof sf.healPercentAura === "function") {
        sf.healPercentAura(playerId, radius(), regenPercent());
    }
    sf.shape(playerId, "circle", radius(), 24, "flame", "#FFCD46", 0.55, false);
    sf.shape(playerId, "circle", 1.2, 12, "heart", "#FF5555", 0.5, false);
}
