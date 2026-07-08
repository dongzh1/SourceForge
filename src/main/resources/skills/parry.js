// 震刀（parry）— GraalJS 技能。脚本 id 必须 == MOD id（mods/parry.yml 的 parry）。
// 右键持有装了 parry MOD 的武器 → 展开架势窗口；窗口内被实体/弹射物命中 → 免疫该次伤害（单次格挡）。
//
// 钩子：
//   onToggle(playerId)              右键
//   onDamaged(playerId, dmg, cause) 受击；返回 true = 取消本次伤害（免疫）
// 参数读 config.yml 的 parry 段；状态 per-player 存本脚本上下文（主线程单线程访问，安全）。

var parryUntil = {};    // playerId -> 架势窗口结束(ms)
var cooldownUntil = {}; // playerId -> 冷却结束(ms)

function windowMs()   { return Math.max(1, sf.cfgInt("parry.window-ticks", 100)) * 50; }
function manaCost()   { return Math.max(0, sf.cfgDouble("parry.mana-cost", 8.0)); }
function cooldownMs() { return Math.max(0, sf.cfgInt("parry.cooldown-ms", 500)); }
function slowAmp()    { return Math.max(0, sf.cfgInt("parry.slow-amplifier", 4)); }
function slowTicks()  { return Math.max(0, sf.cfgInt("parry.slow-ticks", 20)); }

function onToggle(playerId) {
    var now = sf.now();
    if ((parryUntil[playerId] || 0) > now) return;        // 已在架势，不重复展开
    if ((cooldownUntil[playerId] || 0) > now) {
        sf.actionBar(playerId, "&7震刀冷却中…");
        sf.playSound(playerId, "BLOCK_NOTE_BLOCK_BASS", 0.45, 0.65);
        return;
    }
    if (!sf.drainMana(playerId, manaCost())) {
        sf.actionBar(playerId, "&c能量不足，无法展开架势");
        sf.playSound(playerId, "BLOCK_NOTE_BLOCK_BASS", 0.45, 0.65);
        return;
    }
    parryUntil[playerId] = now + windowMs();
    sf.playSound(playerId, "ENTITY_PLAYER_ATTACK_CRIT", 1.2, 1.6);
    if (slowTicks() > 0) sf.potion(playerId, "SLOWNESS", slowTicks(), slowAmp());
    sf.actionBar(playerId, "&b震刀·架势展开 &7(" + (windowMs() / 1000).toFixed(1) + "s)");
}

// 返回 true = 免疫（宿主取消该次 EntityDamageByEntityEvent）
function onDamaged(playerId, damage, cause) {
    var now = sf.now();
    if ((parryUntil[playerId] || 0) <= now) return false;  // 未在架势
    delete parryUntil[playerId];                            // 单次格挡，窗口结束
    cooldownUntil[playerId] = now + cooldownMs();
    sf.removePotion(playerId, "SLOWNESS");
    sf.playSound(playerId, "BLOCK_ANVIL_PLACE", 0.9, 1.5);
    sf.particle(playerId, "CRIT", 24);
    sf.actionBar(playerId, "&a震刀·格挡成功!");
    return true;
}
