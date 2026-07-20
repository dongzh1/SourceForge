// 征服者（conqueror）— 护甲被动技能槽。onAttack: 8秒内持续攻击叠层，每层攻击按当前层数回复自身
// 生命(healPercentAura radius=0 = 只回自己，复用光环回复原语)；8秒未接上下一击则清零。

var stacks = {};  // playerId -> 当前层数(0..maxStacks)
var lastAt = {};  // playerId -> 上次攻击时间(ms)

function chainMs()      { return Math.max(1000, sf.cfgInt("keystone.conqueror.chain-ms", 8000)); }
function maxStacks()    { return Math.max(1, sf.cfgInt("keystone.conqueror.max-stacks", 5)); }
function healPerStack()  { return Math.max(0.0, sf.cfgDouble("keystone.conqueror.heal-per-stack-pct", 0.4)); }

function onAttack(playerId) {
    var now = sf.now();
    if ((now - (lastAt[playerId] || 0)) > chainMs()) stacks[playerId] = 0;
    lastAt[playerId] = now;
    var s = Math.min(maxStacks(), (stacks[playerId] || 0) + 1);
    stacks[playerId] = s;

    sf.healPercentAura(playerId, 0.0, s * healPerStack());
    if (s === maxStacks()) sf.actionBar(playerId, "&6* 征服者 &7层数拉满");
}
