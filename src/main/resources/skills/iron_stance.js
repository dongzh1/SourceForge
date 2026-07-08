// 铁壁架势（iron_stance）— GraalJS。装进武器「Shift+F」技能触发栏，Shift+F 开/关架势。
// 架势中：持续耗蓝、获得减伤(RESISTANCE)、略微减速(平衡)，并可【格挡】——每 block-cd 毫秒可完全挡下
// 一击，格挡成功开"破绽"联动窗口(供盾击反震完美反击)。能量耗尽或再次 Shift+F 关闭。
// 钩子 onActivate(开关) / onTick(每秒维持) / onDamaged(受击结算)。
function upkeep()     { return Math.max(0.0, sf.cfgDouble("iron-stance.upkeep-per-sec", 4.0)); }
function openCost()   { return Math.max(0.0, sf.cfgDouble("iron-stance.open-mana", 10.0)); }
function blockMana()  { return Math.max(0.0, sf.cfgDouble("iron-stance.block-mana", 8.0)); }   // 评审#3：每次完全格挡的代价
function resistAmp()  { return Math.max(0, sf.cfgInt("iron-stance.resistance-amp", 1)); }        // 评审#3：降到 Resistance II(-40%)
function slowAmp()    { return Math.max(0, sf.cfgInt("iron-stance.slow-amp", 0)); }
function blockCd()    { return Math.max(0, sf.cfgInt("iron-stance.block-cooldown-ms", 1500)); }
function parryWin()   { return Math.max(0.0, sf.cfgDouble("iron-stance.parry-seconds", 1.0)); }  // 评审#6：< blockCd，恢复"看准格挡"反应窗口

function close(playerId, msg) {
    sf.setActive("iron_stance", playerId, false);
    sf.removePotion(playerId, "DAMAGE_RESISTANCE");
    sf.removePotion(playerId, "SLOW");
    if (msg) sf.actionBar(playerId, msg);
}

function onActivate(playerId) {
    if (sf.isActive("iron_stance", playerId)) {
        close(playerId, "&7架势解除");
        sf.playSound(playerId, "ITEM_SHIELD_BREAK", 0.7, 1.0);
        return;
    }
    if (!sf.drainMana(playerId, openCost())) { sf.actionBar(playerId, "&c能量不足，无法开架势"); return; }
    sf.setActive("iron_stance", playerId, true);
    sf.playSound(playerId, "ITEM_SHIELD_BLOCK", 1.0, 0.8);
    sf.actionBar(playerId, "&e⛨ 铁壁架势 &7» 减伤·可格挡");
}

// 每秒维持：耗蓝，不足则自动解除；刷新减伤/减速 + 环绕粒子
function onTick(playerId) {
    if (!sf.isActive("iron_stance", playerId)) return;
    if (!sf.drainMana(playerId, upkeep())) { close(playerId, "&c能量耗尽，架势解除"); return; }
    sf.potion(playerId, "DAMAGE_RESISTANCE", 30, resistAmp());  // 持续减伤(1.5s刷新)
    if (slowAmp() > 0) sf.potion(playerId, "SLOW", 30, slowAmp() - 1);
    sf.shape(playerId, "circle", 1.3, 16, "dust", "#FFD24A", 0.8, false);
}

// 架势中受击：每 blockCd 毫秒可完全挡下一击并开"破绽"；冷却中则照常受伤(仍有减伤)
function onDamaged(playerId, damage, cause) {
    if (!sf.isActive("iron_stance", playerId)) return false;
    var now = sf.now();
    var next = parseInt(sf.stateGet("iron_stance", playerId, "blockAt") || "0");
    if (now < next) return false;   // 格挡冷却中，这一击照常(仍有减伤)
    // 评审#3：每次完全格挡消耗一笔能量；蓝不足则不免疫(退化为仅减伤)，杜绝零成本永久无敌
    if (!sf.drainMana(playerId, blockMana())) return false;
    sf.stateSet("iron_stance", playerId, "blockAt", String(now + blockCd()));
    sf.comboSet(playerId, "parry", parryWin());   // 开"破绽"供盾击反震完美反击
    sf.playSound(playerId, "ITEM_SHIELD_BLOCK", 1.0, 1.2);
    sf.shape(playerId, "arc", 2.0, 18, "crit", "#FFFFFF", 1.0, true);
    sf.actionBar(playerId, "&a⛨ 格挡! &7» &e盾击可完美反击");
    return true;   // 完全免这一击
}
