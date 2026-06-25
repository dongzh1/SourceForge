// 技能脚本 · 摸尸 (desecrate)
// 右键开关；开启时每秒耗蓝，范围内死亡生物掉落翻倍。
// 范围吃 ability_range；每秒耗蓝被 ability_efficiency 按百分比降低。
// 全部逻辑/数值都在这里改，不用动 Java。

var SKILL = "desecrate";
var BASE_RANGE = 8.0;        // 基础范围(格)
var BASE_COST = 2.0;         // 基础每秒耗蓝
var DROP_MULT = 2;           // 掉落倍率

function onToggle(p) {
    if (sf.isActive(SKILL, p)) {
        sf.setActive(SKILL, p, false);
        sf.msg(p, "&7[摸尸] 已&c关闭");
    } else if (sf.mana(p) > 0) {
        sf.setActive(SKILL, p, true);
        sf.msg(p, "&7[摸尸] 已&a开启 &8(持续耗蓝, 范围内掉落翻倍)");
    } else {
        sf.msg(p, "&7[摸尸] &cMANA 不足，无法开启");
    }
}

function onTick(p) { // 每秒，仅对开启的玩家
    var eff = Math.min(sf.stat(p, "ability_efficiency"), 0.9); // 效率封顶90%
    var cost = BASE_COST * (1.0 - eff);
    if (!sf.drainMana(p, cost)) {
        sf.setActive(SKILL, p, false);
        sf.msg(p, "&7[摸尸] &cMANA 耗尽，自动关闭");
    }
}

function onKillNearby(p, dist) { // 返回掉落倍率
    var range = BASE_RANGE * (1.0 + sf.stat(p, "ability_range"));
    return dist <= range ? DROP_MULT : 1;
}
