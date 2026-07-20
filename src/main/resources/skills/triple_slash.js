// 三段斩（triple_slash）— GraalJS 攻击技能。脚本 id 必须 == MOD id（mods/triple_slash.yml）。
// 左键挥砍 → 推进连段(1→2→3→回到1)，对前方扇形范围造成「SF 基础伤害 × 段位倍率」的 AoE 斩击。
// 连段窗口内连击才会进段，超时(默认 2s)则从第 1 段重来；每段有小 CD 防止瞬连。
//
// 钩子：onActivate(playerId) 左键技能触发栏；onAttack 仅保留给旧普通槽物品兼容。
// 参数读 config.yml 的 triple-slash 段；状态 per-player 存本脚本上下文（主线程单线程访问，安全）。

var stage = {};    // playerId -> 当前段(1..3)
var stageAt = {};  // playerId -> 上次进段时间(ms)
var nextAt = {};   // playerId -> 下次可斩时间(ms)，段内 CD

function resetMs()  { return Math.max(200, sf.cfgInt("triple-slash.reset-ticks", 40) * 50); }
function slashCd()  { return Math.max(0, sf.cfgInt("triple-slash.cooldown-ms", 250)); }
function radius()   { return Math.max(1.0, sf.cfgDouble("triple-slash.radius", 3.0)); }
function arc()      { return Math.max(10.0, sf.cfgDouble("triple-slash.arc", 120.0)); }
function minBase()  { return Math.max(0.0, sf.cfgDouble("triple-slash.min-base", 1.0)); }

function dmgMult(s) {
    if (s === 1) return sf.cfgDouble("triple-slash.dmg-mult-1", 1.0);
    if (s === 2) return sf.cfgDouble("triple-slash.dmg-mult-2", 1.2);
    return sf.cfgDouble("triple-slash.dmg-mult-3", 1.8);
}

function onAttack(playerId) {
    var now = sf.now();
    if ((nextAt[playerId] || 0) > now) return;  // 段内 CD，吞掉这次挥砍

    // 连段窗口过期 → 从第 1 段重来；否则 1→2→3→1
    var cur = stage[playerId] || 0;
    if ((now - (stageAt[playerId] || 0)) > resetMs()) cur = 0;
    var s = cur >= 3 ? 1 : cur + 1;
    stage[playerId] = s;
    stageAt[playerId] = now;
    nextAt[playerId] = now + slashCd();

    // 伤害基准 = SF 基础伤害（无装备时退到 min-base），再乘段位倍率
    var base = sf.stat(playerId, "base_damage");
    if (base < minBase()) base = minBase();
    var dmg = base * dmgMult(s);

    // 表现：第三段为收招重斩，音调更低、粒子更多
    sf.playSound(playerId, "ENTITY_PLAYER_ATTACK_SWEEP", 1.0, s === 3 ? 0.8 : 1.2);
    var hit = sf.slash(playerId, radius(), arc(), dmg, "CRIT", s === 3 ? 30 : 12);

    sf.actionBar(playerId, "&e三段斩 &7第 &f" + s + " &7段" + (hit > 0 ? "  &c命中 " + hit : ""));
}

function onActivate(playerId) {
    onAttack(playerId);
}
