# SourceForge 使用说明

SourceForge 负责生成源质锻造系统的蓝图和随机词条装备。其他插件统一使用 `sf:` 表达式调用 SourceForge，不需要各自实现随机物品逻辑。

## 基础命令

```text
/sourceforge forge
/sourceforge reload
/sourceforge validate
/sourceforge giveblueprint <玩家> <蓝图ID> [等级] [数量]
/sourceforge giveequipment <玩家> <装备ID> [等级] [数量] [词条数]
/sourceforge givematerial <玩家> <材料ID> [数量]
/sourceforge give <玩家> <sf表达式> [数量]
/sourceforge testdamage <玩家> <physical|magic|element|true> <伤害> [fire|ice|lightning]
/sourceforge reroll
/sourceforge upgrade
/sourceforge enchant list
/sourceforge enchant book <玩家> <附魔ID> [等级] [数量]
/sourceforge enchant apply
/sourceforge enchant slotitem <玩家> [数量]
/sourceforge enchant slot
/sourceforge debug combat <on|off>
```

别名：

```text
/sf forge
/sf give error_0403 sf:blueprint:short_sword?tier=random:1-3 1
```

## 锻造界面

`/sf forge` 会打开 6 行锻造界面。

```text
蓝图槽: 左上方单独空格，只放 SourceForge 蓝图。
基础材料区: 左侧 6 个空格，放蓝图 requirements 需要的材料。
附加材料区: 右侧 9 个空格，放 materials/*.yml 里配置的词条材料。
开始锻造: 底部中间铁砧按钮。
```

界面会强制校验格子类型：

```text
蓝图槽不能放普通物品。
基础材料区必须先放蓝图，只接受当前蓝图 requirements 里的材料。
附加材料区只接受 SourceForge materials 文件夹配置过的材料。
灰色背景板不能放置任何物品。
Shift 快速放入会被阻止，避免自动塞错区域。
```

附加材料不是必需品。不放附加材料时，装备不会生成任何词条，只会得到蓝图对应的基础成品；放入附加材料后，每种附加材料最多生效一次，并把自己的词条候选加入随机池。

界面有基础音效反馈：

```text
合法放入物品: 清脆提示音。
放错格子、材料不足、锻造失败: 低音提示。
锻造成功: 铁砧音效。
```

## sf 表达式

生成蓝图：

```text
sf:blueprint:<蓝图ID>
sf:blueprint:<蓝图ID>?tier=<等级>
sf:blueprint:<蓝图ID>?tier=random:<最小>-<最大>
```

示例：

```text
sf:blueprint:short_sword
sf:blueprint:short_sword?tier=2
sf:blueprint:short_sword?tier=random:1-3
sf:blueprint:flintlock?tier=random:1-3
sf:blueprint:source_bow?tier=random:1-3
sf:blueprint:source_crossbow?tier=random:1-3
sf:blueprint:longsword?tier=random:1-3
```

生成装备：

```text
sf:equipment:<装备ID>?tier=<等级>
sf:equipment:<装备ID>?tier=<等级>&affixes=<固定词条数>
sf:equipment:<装备ID>?tier=<等级>&blueprint=<蓝图ID>
```

示例：

```text
sf:equipment:short_sword?tier=1
sf:equipment:short_sword?tier=3&affixes=3
sf:equipment:short_sword?tier=random:1-3&blueprint=short_sword
sf:equipment:flintlock?tier=3&affixes=4
sf:equipment:source_bow?tier=3&affixes=4
sf:equipment:source_crossbow?tier=3&affixes=4
sf:equipment:dagger?tier=random:1-3&blueprint=dagger
```

说明：

- `blueprint` 表达式会生成蓝图物品，蓝图等级写入 PDC。
- `equipment` 表达式会直接生成成品装备，不经过锻造 GUI，因此不会读取附加材料。
- `equipment` 表达式会直接生成无附加材料成品；不写 `affixes` 时为 0 词条，写 `affixes=3` 时按装备等级词条池随机最多 3 条。
- 生成的装备默认不可损坏，并写入 `chunkworld:level`、`sourceforge:score` 和按评分计算的 `pixelshop:price`。
- 远程武器目前通过 Bukkit 投射物事件继承词条。燧发枪使用 `CROSSBOW` 底层物品，射出的箭会携带装备词条并在命中时结算。
- 战斗调试可用 `/sf debug combat on` 临时打开，命中时会显示模式、武器类型、武器分类和每个词条的实际结算值。

直接获得装备：

```text
/sf giveequipment Steve short_sword 3 1
/sf giveequipment Steve short_sword 3 1 3
/sf giveequipment Steve short_sword 3 5 3
```

第一个示例等同于没有放附加材料锻造出的 3 级短剑，会读取装备自己的 `tier-affixes`，例如短剑会有基础攻击词条。第二个示例会跳过锻造流程，直接生成一把 3 级短剑，并从该装备等级词条池随机最多 3 条词条。若要生成完全无词条白板，显式把最后参数写成 `0`。

当数量大于 1 时，插件会逐件生成装备，每一件都会重新随机属性；不会把同一把随机好的武器直接堆叠成多件。

## 装备评分和价格

装备生成时会写入评分和价格：

```text
sourceforge:score
pixelshop:price
```

评分公式：

```text
基础分 = 装备等级 * score.base-per-tier
词条评分 = 词条数值 * 评分权重
总评分 = 基础分 + 所有词条评分
最终评分 = max(score.min-score, 总评分取整)
```

评分权重读取顺序：

```text
1. score.affix-weights.<词条ID>
2. score.combat-weights.<战斗类型>
3. score.combat-weights.default
```

默认权重：

```text
普通/物理/法术/元素伤害: 数值 * 10
真实伤害: 数值 * 18
破甲: 数值 * 15
暴击率: 数值 * 250
暴击伤害: 数值 * 90
中毒/灼烧/缓慢元素词条: 数值 * 12
物理/法术抗性: 数值 * 350
元素抗性: 数值 * 4
闪避率: 数值 * 500
```

价格公式：

```text
价格倍率 = score.price.multiplier-base + 评分 / score.price.score-divisor
最终价格 = max(score.price.min-price, equipment.<id>.pixelshop-price * 价格倍率)
```

因此 `pixelshop-price` 现在是装备的基础价格，不再是固定最终价格。等级越高、词条越强，评分越高，价格越高。

评分配置在 `config.yml`：

```yaml
score:
  base-per-tier: 100.0
  min-score: 1
  price:
    multiplier-base: 0.5
    score-divisor: 250.0
    min-price: 1.0
  combat-weights:
    default: 5.0
    physical_damage: 10.0
    magic_damage: 10.0
    crit_damage: 90.0
    physical_resistance: 350.0
    magic_resistance: 350.0
    dodge_chance: 500.0
  affix-weights:
    crit_damage: 100.0
```

`combat-weights` 是按战斗类型统一加分，`affix-weights` 是按具体词条 ID 覆盖。权重可以写负数，用来让某些负面词条扣分。

## 当前内置装备

```text
short_sword  短剑，均衡近战武器，适合测试所有基础词条。
longsword    长剑，偏高基础伤害和暴击。
spear        长枪，偏破甲、控场和穿刺感。
dagger       短匕，偏暴击和真实伤害。
flintlock    燧发枪，远程武器，偏破甲、灼烧和暴击。
source_bow   源质弓，远程武器，偏稳定输出、缓慢和蔓毒。
source_crossbow 源质弩，远程武器，偏高穿透、破甲和爆发。
bulwark_helmet/chestplate/leggings/boots  玄铁守御套，偏物理抗性。
ward_helmet/chestplate/leggings/boots      织法秘纹套，偏法术和元素抗性。
```

给玩家发蓝图示例：

```text
/sf giveblueprint error_0403 short_sword 1-3 1
/sf giveblueprint error_0403 flintlock 1-3 1
/sf giveblueprint error_0403 source_bow 1-3 1
/sf giveblueprint error_0403 source_crossbow 1-3 1
/sf giveblueprint error_0403 longsword 1-3 1
/sf giveblueprint error_0403 spear 1-3 1
/sf giveblueprint error_0403 dagger 1-3 1
/sf giveblueprint error_0403 bulwark_chestplate 1-3 1
/sf giveblueprint error_0403 ward_chestplate 1-3 1
```

给玩家发附加材料示例：

```text
/sf givematerial error_0403 sharp_stone 1
/sf givematerial error_0403 focus_crystal 1
/sf givematerial error_0403 gunpowder_charge 1
/sf givematerial error_0403 metal_spring 1
/sf givematerial error_0403 bulwark_plate 1
/sf givematerial error_0403 rune_thread 1
```

## 当前内置附加材料

```text
锐化石        sourceforge:sharp_stone        偏攻击伤害、破甲。
会心晶片      sourceforge:focus_crystal      偏暴击率、暴击伤害。
蔓毒药剂      sourceforge:venom_vial         偏中毒、缓慢。
战意余烬      sourceforge:war_ember          偏灼烧、攻击伤害。
血髓          sourceforge:blood_essence      偏暴击伤害、真实伤害。
霜核          sourceforge:frost_core         偏缓慢、破甲。
金属弹簧      sourceforge:metal_spring       偏暴击率、少量攻击伤害。
火药装药      sourceforge:gunpowder_charge   偏灼烧、暴击伤害。
霜片          sourceforge:rime_shard         偏缓慢。
玄铁甲片      sourceforge:bulwark_plate      偏物理抗性。
秘纹丝线      sourceforge:rune_thread        偏法术抗性和元素抗性。
```

## PixelShop 接入

PixelShop 商品的 `id` 可以直接写 `sf:` 表达式。

```yaml
items:
  - id: "sf:blueprint:short_sword?tier=random:1-3"
    money: 500
    points: 0
    limit: 1
    refresh: daily
```

也可以售卖固定等级蓝图：

```yaml
items:
  - id: "sf:blueprint:short_sword?tier=2"
    money: 1200
    points: 0
    limit: 1
    refresh: weekly
```

如果要直接卖成品装备：

```yaml
items:
  - id: "sf:equipment:short_sword?tier=1&affixes=2"
    money: 3000
    points: 0
    limit: 1
    refresh: daily
```

建议商店优先卖蓝图，不直接大量卖成品装备，这样锻造材料和 ChunkWorld 资源循环更稳定。

## ChunkWorld 接入

ChunkWorld 的战利品、容器补货、试炼刷怪笼奖励、暴露度掉落等配置中，物品 ID 可以写 `sf:` 表达式。

示例：结构容器战利品里小概率出短剑蓝图：

```yaml
loot-pools:
  default:
    items:
      short_sword_blueprint:
        id: "sf:blueprint:short_sword?tier=random:1-3"
        amount: 1
        chance: 0.03
```

示例：试炼刷怪笼奖励：

```yaml
item-rewards:
  short_sword_blueprint:
    id: "sf:blueprint:short_sword?tier=random:1-3"
    amount: 1
    chance: 0.05
```

示例：暴露度掉落：

```yaml
drops:
  short_sword_blueprint:
    id: "sf:blueprint:short_sword?tier=random:1-2"
    amount: 1
    chance: 0.01
```

## MythicMobs 接入

MythicMobs 可以通过控制台命令调用 SourceForge。

```yaml
Skills:
  - command{c="sf give <target.name> sf:blueprint:short_sword?tier=random:1-3 1";asConsole=true} @trigger
```

如果目标不是玩家，建议把奖励接到掉落表或 ChunkWorld 奖励系统里，不直接对怪物目标发 `/sf give`。

## 配置结构

蓝图配置：

```yaml
blueprints:
  short_sword:
    display-name: "短剑"
    equipment-type: short_sword
    tier:
      min: 1
      max: 3
      fixed: 0
    affix-slots:
      min: 1
      max: 3
    max-affixes: 4
    requirements:
      - id: "minecraft:iron_ingot"
        amount: 2
      - id: "yuanzhi:yuanzhi1"
        amount: 1
      - id: "minecraft:stick"
        amount: 1
```

装备配置：

```yaml
equipment:
  short_sword:
    display-name: "短剑"
    material: IRON_SWORD
    ce-id: ""
    weapon-category: melee_light
    chunkworld-level: "blueprint-tier"
    pixelshop-price: 100.0
    base-lore:
      - "&8源质锻造成品"
    affixes:
      - attack_damage
      - crit_chance
      - crit_damage
      - poison_chance
    tier-affixes:
      1:
        attack_damage:
          chance: 1.0
          min: 1.0
          max: 2.0
      2:
        attack_damage:
          chance: 1.0
          min: 2.0
          max: 3.5
        crit_chance:
          chance: 0.30
          min: 0.02
          max: 0.05
```

词条配置：

```yaml
affixes:
  attack_damage:
    display-name: "锐锋"
    pdc-key: "attack_damage"
    value-type: double
    min: 1.0
    max: 3.0
    decimals: 1
    chance: 5
    combat: damage
    melee:
      combat: damage
      scale: 1.0
    ranged:
      combat: damage
      scale: 0.75
    effects:
      dagger_melee:
        combat: damage
        scale: 0.85
      longsword_melee:
        combat: damage
        scale: 1.15
      source_bow_ranged:
        combat: damage
        scale: 0.70
      source_crossbow_ranged:
        combat: damage
        scale: 0.85
      flintlock_ranged:
        combat: damage
        scale: 1.25
    lore: "&7锐锋: &f攻击伤害 +%value%"
```

`melee` 和 `ranged` 可以让同一个词条在近战和远程下结算不同。`effects` 是更细的覆盖表，适合让同名词条在短剑、长剑、弓、弩、枪上表现不同。

```yaml
melee:
  combat: poison
  scale: 1.0
  duration-ticks: 100
  amplifier: 0
ranged:
  combat: poison
  damage-type: magic
  element: wood
  scale: 0.55
  status-chance: 0.16
  duration-ticks: 60
  amplifier: 0
effects:
  dagger_melee:
    combat: poison
    damage-type: magic
    element: wood
    scale: 1.30
    status-chance: 0.32
    duration-ticks: 120
    amplifier: 0
  source_bow_ranged:
    combat: poison
    scale: 0.65
    duration-ticks: 70
    amplifier: 0
  flintlock_ranged:
    combat: poison
    scale: 0.25
    duration-ticks: 40
    amplifier: 0
```

含义：

- `combat` 是实际结算类型，不写时沿用上层 `combat`。
- `damage-type` 是伤害类别：`physical` 物理、`magic` 魔法/元素、`true` 真实。
- `element` 是元素：`fire` 火、`ice` 冰、`lightning` 雷、`water` 水、`wood` 木、`none` 无元素。
- `scale` 会在锻造阶段乘到装备最终词条数值上，lore 显示值和实战读取值一致。比如会心晶片在短匕上更高，在燧发枪上更低。
- `status-chance` 是附带原版状态概率。蔓毒、灼烧、寒缓的主要价值是元素伤害，状态只是附带效果。
- `duration-ticks` 用于中毒、燃烧、缓慢等持续效果，20 ticks 约等于 1 秒。
- `amplifier` 是药水效果等级，从 0 开始。
- `effects` 匹配优先级是 `武器ID_模式` > `武器ID` > `武器分类_模式` > `武器分类` > `melee/ranged`。
- 当前推荐分类：`melee_light` 轻近战，`melee_heavy` 重近战，`polearm` 长柄，`bow` 弓，`crossbow` 弩，`firearm` 枪。

当前内置差异设计：

```text
短匕/短剑  蔓毒、会心、真实伤害收益更好，基础伤害和破甲偏低。
长剑       基础伤害、暴击伤害收益更好，控制和毒弱一些。
长枪       破甲更好，投掷时按 ranged 模式结算。
源质弓     控制和蔓毒更稳定，直接伤害和破甲较低。
源质弩     破甲和暴伤更好，毒和控制弱一些。
燧发枪     破甲、灼烧、暴伤最高，蔓毒、会心概率收益较低。
```

## 附魔技能

锻造出的装备默认只提供基础定位属性：武器默认出物理伤害、法术伤害、暴击倍率；防具默认出物理防御、法术防御、闪避。元素伤害、元素抗性、真实伤害、暴击率等特殊数值由附加材料提供；吸血、攻击触发、右键主动等技能由 SourceForge 附魔提供。

```text
/sf enchant list
/sf enchant book <玩家> <附魔ID> [等级] [数量]
/sf enchant apply
/sf enchant slotitem <玩家> [数量]
/sf enchant slot
```

使用方式：

```text
1. 管理员用 /sf enchant book 给予附魔书。
2. 玩家主手持 SourceForge 装备。
3. 玩家副手放 SourceForge 附魔书。
4. 执行 /sf enchant apply 应用到主手装备。
```

每件新锻造出的 SourceForge 装备默认有 3 个附魔槽。不同附魔占用 1 个槽，升级已有附魔不额外占槽。附魔槽满时不能附加新附魔。管理员可以用 `/sf enchant slotitem <玩家> [数量]` 发放附魔扩容晶核，玩家主手持装备、副手放晶核，执行 `/sf enchant slot` 后为装备增加 1 个附魔槽，当前上限 6 个。

当前内置附魔：

```text
lifesteal     汲血，被动，造成伤害后按最终伤害回血。
flame_burst   焰爆，被动，攻击时概率追加火元素伤害。
frost_bind    霜缚，被动，攻击时概率追加冰元素伤害并缓速。
thunder_dash  雷跃，主动，右键突进并造成雷元素范围伤害。
keen_edge     利刃，被动，重做自锋利/力量，提高物理和非元素法术伤害。
shockwave     震退，被动，重做自击退/冲击，攻击概率击退目标。
thorn_guard   棘甲，被动，重做自荆棘，受近战伤害时反弹真实伤害。
```

附魔等级参数在 `plugins/SourceForge/enchants.yml` 中配置，改完后执行 `/sf reload` 生效。每个附魔都可以按等级写不同数值：

```yaml
enchants:
  lifesteal:
    display-name: "汲血"
    type-name: "被动"
    max-level: 3
    description-format: "造成伤害后回复 %lifesteal_percent%% 最终伤害"
    short-format: "伤害后回血 %lifesteal_percent%%"
    levels:
      1:
        lifesteal-rate: 0.07
      2:
        lifesteal-rate: 0.10
      3:
        lifesteal-rate: 0.13

  frost_bind:
    display-name: "霜缚"
    type-name: "被动"
    max-level: 3
    description-format: "%chance_percent%% 概率追加 %ice_damage% 冰元素伤害并缓速 %slow_seconds% 秒"
    short-format: "%chance_percent%% 冰伤缓速"
    levels:
      1:
        chance: 0.10
        ice-damage: 2.5
        slow-duration-ticks: 60
        slow-amplifier: 0
```

常用等级参数：

```text
lifesteal-rate      吸血比例，0.10 = 回复最终伤害的 10%。
chance              触发概率，0.20 = 20%。
fire-damage         焰爆追加火元素伤害。
ice-damage          霜缚追加冰元素伤害。
slow-duration-ticks 霜缚缓速时间，20 ticks = 1 秒。
slow-amplifier      缓速等级，0 = 缓慢 I。
lightning-damage    雷跃雷元素伤害。
radius              雷跃范围。
cooldown-millis     主动技能冷却毫秒数。
launch-strength     雷跃水平突进力度。
launch-y            雷跃上抬力度。
damage-bonus-rate   利刃增伤比例。
knockback-strength  震退力度。
reflect-damage      棘甲反弹真实伤害。
```

`description-format` 和 `short-format` 会显示在附魔书和装备 lore 上。可用占位包括 `%lifesteal_percent%`、`%chance_percent%`、`%fire_damage%`、`%ice_damage%`、`%lightning_damage%`、`%slow_seconds%`、`%cooldown_seconds%`、`%damage_bonus_percent%`、`%reflect_damage%`。

主动附魔可以调用 MythicMobs 技能。示例：

```yaml
enchants:
  mythic_flame_wave:
    display-name: "炎浪"
    type-name: "主动"
    max-level: 3
    action: mythic_skill
    mythic-skill: "SF_FlameWave"
    description-format: "右键释放 MythicMobs 技能 SF_FlameWave，冷却 %cooldown_seconds% 秒"
    short-format: "右键炎浪 %cooldown_seconds%秒冷却"
    levels:
      1:
        cooldown-millis: 12000
```

`mythic_skill` 动作需要服务器安装 MythicMobs。未安装或 API 不兼容时，玩家释放会收到提示，不会扣冷却。

MythicMobs 技能配置位置示例：

```text
plugins/MythicMobs/Skills/sourceforge_skills.yml
```

示例技能：

```yaml
SF_FlameWave:
  Skills:
    - damage{a=8} @EntitiesInRadius{r=4}
    - ignite{ticks=80} @EntitiesInRadius{r=4}
    - effect:particles{p=flame;a=60;hS=1.2;vS=0.3} @self
```

如果要让 MythicMobs 技能造成 SourceForge 法术/元素/真实伤害，推荐调用 SourceForge 命令：

```yaml
SF_MagicBolt:
  Skills:
    - command{c="sf mmdamage <target.name> magic 20";asConsole=true} @target

SF_FireBurst:
  Skills:
    - command{c="sf mmdamage <target.name> element 18 fire";asConsole=true} @target

SF_TrueStrike:
  Skills:
    - command{c="sf mmdamage <target.name> true 12";asConsole=true} @target
```

`sf mmdamage` 会走 SourceForge 的防御系统：`magic` 吃法术防御，`element` 只吃对应元素抗性，`true` 走真实伤害倍率。直接使用 MythicMobs 自己的 `damage` 机制时，是否能被识别成法术/元素取决于它最终触发的 Bukkit DamageType，不如 `sf mmdamage` 稳定。

附加材料配置放在 `plugins/SourceForge/materials/*.yml`，每一种材料一个文件：

```yaml
id: sharp_stone
display-name: "锐化石"
item: "sourceforge:sharp_stone"
amount: 1
affixes:
  attack_damage:
    chance: 0.85
    min: 0.8
    max: 2.0
    weapon-categories:
      - melee_light
      - melee_heavy
      - polearm
    category-scales:
      melee_light: 0.90
      melee_heavy: 1.15
      polearm: 1.0
  armor_pierce:
    chance: 0.35
    min: 0.3
    max: 0.9
```

材料字段说明：

- `weapon-categories` 限制这个材料词条只会出现在指定武器分类上。
- `weapon-types` 限制这个材料词条只会出现在指定装备 ID 上，例如 `flintlock`。
- `category-scales` 按武器分类缩放锻造出来的最终数值。
- `type-scales` 按装备 ID 缩放，优先级高于 `category-scales`。
- 不写限制时，表示所有能使用该词条的装备都可以尝试生成。

锻造流程：

1. 放入蓝图，蓝图可以是固定等级，也可以是 `1-3` 这种等级范围。
2. 插件先检查并占用蓝图 `requirements` 里的基础材料。
3. 再从剩余输入物品里识别附加材料，每种材料配置最多生效一次。
4. 如果没有放入附加材料，直接生成无词条成品。
5. 如果放入了附加材料，根据实际锻造等级读取 `equipment.<id>.tier-affixes`，生成等级词条候选。
6. 根据放入的附加材料读取 `materials/*.yml`，追加材料词条候选。
7. 每个候选按 `chance` 概率独立判定，成功后在 `min` 到 `max` 之间随机数值。
8. 同一个词条如果被多处生成，只保留数值最高的一条。
9. 最终词条数量不超过蓝图的 `max-affixes`。

注意：如果附加材料和基础材料使用同一种物品，必须额外多放一份；基础材料会优先被配方占用。

## 两套防具例子

下面两套是防具套装模板，四件都通过蓝图锻造。防具词条会写入 PDC，玩家穿在身上后，受击时插件会读取四件装备的抗性总和进行减伤。

### 例子一：玄铁守御套

定位：物理抗性套，适合抗普通近战、箭矢、物理型 MM 怪物。弱点是法术和元素伤害抗性较低。

四件蓝图：

```text
bulwark_helmet      玄铁守御头盔
bulwark_chestplate  玄铁守御胸甲
bulwark_leggings    玄铁守御护腿
bulwark_boots       玄铁守御靴子
```

推荐附加材料：

```text
bulwark_plate  玄铁甲片，主堆物理抗性
rune_thread    秘纹丝线，可少量补法术抗性
```

测试命令：

```text
/sf giveblueprint error_0403 bulwark_helmet 2-3 1
/sf giveblueprint error_0403 bulwark_chestplate 2-3 1
/sf giveblueprint error_0403 bulwark_leggings 2-3 1
/sf giveblueprint error_0403 bulwark_boots 2-3 1
/sf givematerial error_0403 bulwark_plate 4
/sf givematerial error_0403 rune_thread 2
/sf forge
```

可能生成的单件示例：

```text
玄铁守御胸甲
等级: 3
词条:
铁壁: 物理抗性 +0.11
秘护: 法术抗性 +0.03
避火: 火元素抗性 +3.0%
```

四件穿齐后的预期：

```text
物理抗性约 0.28 - 0.45
法术抗性约 0.04 - 0.12
少量随机元素抗性
```

适合测试：

```text
让僵尸、骷髅、物理型 MythicMobs 攻击玩家。
/sf debug combat on
```

预期效果：后台或玩家 debug 会显示防具减伤，普通物理伤害明显降低；遇到火球、法术、元素攻击时降低较少。

### 例子二：织法秘纹套

定位：法术抗性套，适合抗元素、魔法、火球、法术型 MM 怪物。弱点是普通物理抗性较低。

四件蓝图：

```text
ward_helmet      织法秘纹兜帽
ward_chestplate  织法秘纹长袍
ward_leggings    织法秘纹护腿
ward_boots       织法秘纹软靴
```

推荐附加材料：

```text
rune_thread    秘纹丝线，主堆法术抗性和元素抗性
bulwark_plate  玄铁甲片，可少量补物理抗性
```

测试命令：

```text
/sf giveblueprint error_0403 ward_helmet 2-3 1
/sf giveblueprint error_0403 ward_chestplate 2-3 1
/sf giveblueprint error_0403 ward_leggings 2-3 1
/sf giveblueprint error_0403 ward_boots 2-3 1
/sf givematerial error_0403 rune_thread 4
/sf givematerial error_0403 bulwark_plate 2
/sf forge
```

可能生成的单件示例：

```text
织法秘纹长袍
等级: 3
词条:
秘护: 法术抗性 +0.11
避火: 火元素抗性 +5.0%
御寒: 冰元素抗性 +4.0%
铁壁: 物理抗性 +0.02
```

四件穿齐后的预期：

```text
法术抗性约 0.28 - 0.45
物理抗性约 0.04 - 0.12
火/冰/雷元素抗性随机出现
```

适合测试：

```text
让火焰弹、烈焰人、法术型 MythicMobs 攻击玩家。
/sf debug combat on
```

预期效果：元素或法术伤害明显降低；普通近战和箭矢伤害降低较少。

## 战斗抗性和元素

`combat.yml` 控制怪物抗性、元素克制和怪物普通攻击类型。插件不按怪物名字猜测抗性，推荐在 MythicMobs 怪物配置里明确添加 scoreboard tag。

MythicMobs 怪物配置位置示例：

```text
plugins/MythicMobs/Mobs/sourceforge_mobs.yml
```

怪物配置示例：

```yaml
WoodEnt:
  Type: ZOMBIE
  Display: '木灵守卫'
  Health: 80
  Damage: 8
  Options:
    PreventOtherDrops: true
  Skills:
    - command{c="tag <mob.uuid> add sf_profile:wood_creature";asConsole=true} @self ~onSpawn
```

只要实体最终有 `sf_profile:<档案ID>` 这个 scoreboard tag，就会使用对应档案。没有 tag 时，只按 `combat.yml` 的 `profile.tags`、`entity-types` 和 `default-monster-profile` 匹配，不再读取实体名字或显示名。

怪物档案示例：

```yaml
monster-profiles:
  wood_creature:
    display-name: "木系怪物"
    tags:
      - "sf_profile:wood_creature"
    entity-types: []
    name-contains: [] # 已废弃，不参与匹配；保留只是兼容旧配置。
    elements:
      - wood
    attack-type: element
    attack-element: wood
    physical-resistance: 0.10
    magic-resistance: 0.05
    element-resistances:
      fire: -25.0
      wood: 45.0
```

结算规则：

- 物理伤害吃 `physical-resistance`，破甲会降低目标物理抗性。
- 元素伤害只吃对应 `element-resistances`，抗性使用百分制；负数会增伤，超过 `100.0` 会把超出的部分转为治疗。
- 真实伤害不吃抗性，但会乘 `true-damage-scale`，默认 `0.45`，避免过强。
- 暴击先于防御判定，只放大物理伤害和非元素魔法伤害，不影响元素伤害和真实伤害。
- 怪物普通攻击类型由档案的 `attack-type` 指定：`physical`、`magic`、`element`、`true`。`attack-type: element` 时读取 `attack-element`。

当前已实现词条：

```text
damage       物理额外伤害
crit_chance  暴击概率
crit_damage  暴击伤害倍率加成
poison       木元素魔法伤害，附带概率中毒
burn         火元素魔法伤害，附带概率点燃
slow         冰元素魔法伤害，附带概率缓慢
pierce       降低目标物理抗性
true_damage  真实伤害，受 true-damage-scale 全局削弱
dodge_chance 闪避率，受到伤害前概率闪避本次伤害
```

后续要加雷、水、岩、范围伤害、MM 技能触发时，可以继续扩展 `combat` 类型和元素表。

## 原版属性、PDC 与 PAPI

SourceForge 装备现在把能接入原版的部分尽量写入原版属性，把原版没有的战斗属性写入 PDC。

原版属性：

```text
GENERIC_ATTACK_DAMAGE / Attribute.ATTACK_DAMAGE
- 来源：装备锻造出的物理伤害。
- 作用：主手拿装备时，原版面板和攻击事件能看到攻击伤害。
- 说明：近战结算时不再重复读取 physical_damage PDC 追加一次，避免物理伤害翻倍；远程投射物仍会从 PDC 继承物理伤害。

GENERIC_ATTACK_SPEED / Attribute.ATTACK_SPEED
- 来源：装备类型。
- 作用：控制剑的恢复条节奏，主动技能冷却另按附魔配置独立计算。

GENERIC_ARMOR / Attribute.ARMOR
- 来源：玩家当前装备的原版护甲值。
- 作用：在 SourceForge 结算里作为物理防御。

GENERIC_ARMOR_TOUGHNESS / Attribute.ARMOR_TOUGHNESS
- 来源：玩家当前装备的原版护甲韧性。
- 作用：在 SourceForge 结算里作为法术防御。
```

SourceForge PDC：

```text
sourceforge:type                 装备类型 ID
sourceforge:weapon_category      装备分类
sourceforge:tier                 装备等级
sourceforge:affixes              当前装备拥有的加成 ID 列表
sourceforge:score                物品评分
pixelshop:price                  按评分计算后的 PixelShop 价格
chunkworld:level                 写给 ChunkWorld 的等级

sourceforge:physical_damage      物理伤害配置值，用于生成原版攻击伤害，远程投射物也读取它
sourceforge:magic_damage         非元素法术伤害
sourceforge:true_damage          真实伤害
sourceforge:crit_chance          暴击率
sourceforge:crit_damage          暴击伤害倍率加成
sourceforge:armor_pierce         破甲
sourceforge:poison_chance        木元素伤害，同时按配置概率中毒
sourceforge:burn_chance          火元素伤害，同时按配置概率点燃
sourceforge:slow_chance          冰元素伤害，同时按配置概率缓慢

sourceforge:physical_resistance  物理抗性，玩家防具累加
sourceforge:magic_resistance     法术抗性，玩家防具累加
sourceforge:fire_resistance      火元素抗性，百分制
sourceforge:ice_resistance       冰元素抗性，百分制
sourceforge:lightning_resistance 雷元素抗性，百分制
sourceforge:water_resistance     水元素抗性，百分制，预留可配置
sourceforge:wood_resistance      木元素抗性，百分制，预留可配置
sourceforge:dodge_chance         闪避率

sourceforge:enchant_slots        附魔槽数量
sourceforge:enchants             已附魔 ID 列表
sourceforge:enchant_<id>         指定 SourceForge 附魔等级
```

PlaceholderAPI 占位符：

```text
%sourceforge_type%
%sourceforge_category%
%sourceforge_tier%
%sourceforge_score%
%sourceforge_price%

%sourceforge_physical_damage%
%sourceforge_magic_damage%
%sourceforge_true_damage%
%sourceforge_fire_damage%
%sourceforge_ice_damage%
%sourceforge_lightning_damage%
%sourceforge_water_damage%
%sourceforge_wood_damage%
%sourceforge_crit_chance%
%sourceforge_crit_chance_percent%
%sourceforge_crit_damage%
%sourceforge_crit_damage_percent%
%sourceforge_armor_pierce%

%sourceforge_physical_resistance%
%sourceforge_physical_resistance_percent%
%sourceforge_magic_resistance%
%sourceforge_magic_resistance_percent%
%sourceforge_fire_resistance%
%sourceforge_ice_resistance%
%sourceforge_lightning_resistance%
%sourceforge_water_resistance%
%sourceforge_wood_resistance%
%sourceforge_dodge_chance%
%sourceforge_dodge_chance_percent%

%sourceforge_enchant_slots%
%sourceforge_enchant_used%
%sourceforge_enchant_<附魔ID>%
%sourceforge_affix_<加成ID>%

%sourceforge_vanilla_attack_damage%
%sourceforge_vanilla_attack_speed%
%sourceforge_vanilla_physical_defense%
%sourceforge_vanilla_armor%
%sourceforge_vanilla_magic_defense%
%sourceforge_vanilla_armor_toughness%
```

读取规则：

- 伤害、暴击、破甲、装备类型、评分、价格读取玩家主手 SourceForge 装备。
- 抗性、闪避读取玩家身上 SourceForge 防具总和。
- 原版物理防御和法术防御读取玩家实时 `ARMOR`、`ARMOR_TOUGHNESS` 属性。
- `%sourceforge_affix_<加成ID>%` 可读取任意已配置加成，例如 `%sourceforge_affix_burn_chance%`。
- `%sourceforge_enchant_<附魔ID>%` 返回指定 SourceForge 附魔等级，没有则返回 `0`。
