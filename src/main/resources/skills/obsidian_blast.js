// 黑曜石爆破（obsidian_blast）— GraalJS 技能。脚本 id 必须 == MOD id（mods/obsidian_blast.yml 的 obsidian_blast）。
// 右键装了 obsidian_blast MOD 的镐子(触发栏) → 瞬间破坏瞄准的黑曜石方块，0.2秒冷却。
//
// 钩子：onActivate(playerId) 右键触发。
// 参数读 config.yml 的 obsidian_blast 段；冷却状态 per-player 存本脚本上下文(主线程单线程访问，安全)。

var nextAt = {}; // playerId -> 下次可用时间(ms)

function cooldownMs() { return Math.max(0, sf.cfgInt("obsidian_blast.cooldown-ms", 200)); }
function range()      { return Math.max(1, sf.cfgDouble("obsidian_blast.range", 6.0)); }

function onActivate(playerId) {
    var now = sf.now();
    if ((nextAt[playerId] || 0) > now) return; // 冷却中，直接吞掉本次点击

    var broken = sf.breakTargetBlock(playerId, range(), "OBSIDIAN");
    if (!broken) {
        sf.actionBar(playerId, "&7瞄准点不是黑曜石");
        return; // 未命中黑曜石不进入冷却，允许玩家立刻调整瞄准重试
    }
    nextAt[playerId] = now + cooldownMs();
    sf.playSound(playerId, "ENTITY_GENERIC_EXPLODE", 0.6, 1.4);
    sf.particle(playerId, "SMOKE", 12);
    sf.actionBar(playerId, "&5黑曜石爆破!");
}
