// 致命节奏（lethal_tempo）— 护甲被动技能槽。onAttack: 连续攻击(2秒内不中断)逐层叠加伤害加成，
// 每次攻击都附带一次当前层数对应的额外伤害；中断则清零。脚本id必须 == MOD id。

var stacks = {};  // playerId -> 当前层数(0..maxStacks)
var lastAt = {};  // playerId -> 上次攻击时间(ms)

function chainMs()    { return Math.max(500, sf.cfgInt("keystone.lethal-tempo.chain-ms", 2000)); }
function maxStacks()  { return Math.max(1, sf.cfgInt("keystone.lethal-tempo.max-stacks", 5)); }
function perStackPct(){ return Math.max(0.0, sf.cfgDouble("keystone.lethal-tempo.per-stack-pct", 5.0)); }
function minBase()    { return Math.max(0.0, sf.cfgDouble("keystone.lethal-tempo.min-base", 1.0)); }

function onAttack(playerId) {
    var now = sf.now();
    if ((now - (lastAt[playerId] || 0)) > chainMs()) stacks[playerId] = 0;
    lastAt[playerId] = now;
    var s = Math.min(maxStacks(), (stacks[playerId] || 0) + 1);
    stacks[playerId] = s;
    if (s <= 0) return;

    var base = sf.stat(playerId, "base_damage");
    if (base < minBase()) base = minBase();
    var bonus = base * (s * perStackPct() / 100.0);
    sf.slash(playerId, 3.0, 100.0, bonus, "CRIT", 4 + s * 2);
    if (s === maxStacks()) sf.actionBar(playerId, "&6* 致命节奏 &7节奏拉满 (" + s + "/" + maxStacks() + ")");
}
