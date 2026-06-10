# SourceForge 详细说明与使用指南

本文档面向服主、配置维护者和后续开发者，说明 SourceForge 简称 SF 插件的用途、命令、锻造流程、配置结构、战斗结算和外部插件接入方式。

## 1. 插件定位

SourceForge 是一个源质锻造系统插件，核心功能是：

- 生成蓝图。
- 通过锻造 GUI 消耗基础材料和附加材料。
- 生成带随机词条的武器、防具。
- 给其他插件提供 `sf:` 物品表达式。
- 结算 SourceForge 装备的物理、魔法、元素、真实伤害。
- 接管玩家受到的原版伤害，把原版护甲值当作物理防御，把原版护甲韧性当作法术防御。

插件主命令是：

```text
/sourceforge
/sf
```

管理员权限：

```text
sourceforge.admin
```

## 2. 运行环境

当前项目配置：

```text
Paper API: 1.21.11
Java: 21
Kotlin: 1.9.20
EasyLib: 3.9.0
```

软依赖：

```text
CraftEngine
ChunkWorld
EasyLevels
```

CraftEngine 不是硬依赖，但如果配置里写了 `ce-id` 或材料 ID，例如 `sourceforge:short_sword`，服务器需要有对应 CraftEngine 物品资源，否则会退回或无法生成对应物品。

## 3. 文件结构

插件首次启动后会生成：

```text
plugins/SourceForge/
├─ config.yml
├─ affixes.yml
├─ combat.yml
└─ materials/
   ├─ sharp_stone.yml
   ├─ focus_crystal.yml
   ├─ venom_vial.yml
   ├─ war_ember.yml
   ├─ blood_essence.yml
   ├─ frost_core.yml
   ├─ metal_spring.yml
   ├─ gunpowder_charge.yml
   ├─ rime_shard.yml
   ├─ bulwark_plate.yml
   └─ rune_thread.yml
```

文件用途：

```text
config.yml      主配置，包含蓝图、装备模板、GUI 标题、物品显示名。
affixes.yml     词条定义，包含词条名、PDC 键、显示 lore、战斗类型、近战/远程差异。
combat.yml      战斗抗性、怪物档案、元素克制、真实伤害倍率。
materials/*.yml 附加材料配置，每个文件定义一种锻造材料能提供哪些词条。
```

重载命令：

```text
/sf reload
```

重载会重新读取 `config.yml`、`affixes.yml`、`combat.yml`、`materials/*.yml`。

查看配置校验结果：

```text
/sf validate
```

## 4. 基础命令

打开锻造界面：

```text
/sf
/sf forge
```

重载配置：

```text
/sf reload
```

给予蓝图：

```text
/sf giveblueprint <玩家> <蓝图ID> [等级] [数量]
```

直接给予装备：

```text
/sf giveequipment <玩家> <装备ID> [等级] [数量] [词条数]
```

不写 `词条数` 时等同于没有附加材料的锻造成品，会读取装备自己的 `tier-affixes`，例如武器通常会有基础攻击词条。写了词条数时，会跳过锻造流程，直接从该装备等级词条池随机生成最多指定数量的词条。显式写 `0` 才会生成完全无词条白板。

示例：

```text
/sf giveblueprint Steve short_sword 1 1
/sf giveblueprint Steve short_sword 1-3 1
/sf giveblueprint Steve ward_chestplate random:2-3 1
```

给予附加材料：

```text
/sf givematerial <玩家> <材料ID> [数量]
```

示例：

```text
/sf givematerial Steve sharp_stone 3
/sf givematerial Steve rune_thread 4
```

通过 `sf:` 表达式给予物品：

```text
/sf give <玩家> <sf表达式> [数量]
```

示例：

```text
/sf give Steve sf:blueprint:short_sword?tier=random:1-3 1
/sf give Steve sf:equipment:short_sword?tier=3&affixes=4 1
```

开启或关闭战斗调试：

```text
/sf debug combat on
/sf debug combat off
```

调试开启后，命中或受击时会看到伤害类型、防御、抗性、最终伤害、元素治疗等信息。

测试伤害：

```text
/sf testdamage Steve physical 20
/sf testdamage Steve magic 20
/sf testdamage Steve element 20 fire
/sf testdamage Steve element 20 ice
/sf testdamage Steve element 20 lightning
/sf testdamage Steve true 20
```

重铸和升级手持装备：

```text
/sf reroll
/sf upgrade
/sf enchant list
/sf enchant book <玩家> <附魔ID> [等级] [数量]
/sf enchant apply
/sf enchant slotitem <玩家> [数量]
/sf enchant slot
```

`reroll` 会重置手持 SourceForge 装备的词条，`upgrade` 会把手持 SourceForge 装备提升 1 级，最高不超过对应蓝图的 `tier.max`。

附魔命令：

```text
/sf enchant list
/sf enchant book Steve lifesteal 1 1
/sf enchant apply
/sf enchant slotitem Steve 1
/sf enchant slot
```

`enchant book` 会给予 SourceForge 附魔书。`enchant apply` 会把副手附魔书应用到主手 SourceForge 装备。

每件新锻造出的 SourceForge 装备默认有 3 个附魔槽。不同附魔占用 1 个槽，升级已有附魔不额外占槽。附魔槽满时不能附加新附魔。`enchant slotitem` 会给予附魔扩容晶核，玩家主手持装备、副手放晶核，执行 `enchant slot` 后为装备增加 1 个附魔槽，当前上限 6 个。

当前内置附魔：

```text
lifesteal     汲血，被动，造成伤害后回复最终伤害的一部分。
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

## 5. 锻造 GUI

打开：

```text
/sf forge
```

GUI 是 54 格，即 6 行箱子界面。

槽位定义：

```text
蓝图槽: 10
基础材料槽: 12, 13, 14, 21, 22, 23
附加材料槽: 15, 16, 17, 24, 25, 26, 33, 34, 35
锻造按钮: 49
```

使用流程：

1. 把 SourceForge 蓝图放入蓝图槽。
2. 把蓝图 `requirements` 需要的材料放入基础材料槽。
3. 可选：把附加材料放入附加材料槽。
4. 点击底部锻造按钮。
5. 插件扣除蓝图、基础材料、附加材料，并把成品装备放入玩家背包。
6. 背包满时，多余物品会掉落在玩家位置。

限制规则：

```text
蓝图槽只能放 SF 蓝图。
基础材料槽只接受当前蓝图 requirements 里的材料。
附加材料槽只接受 materials/*.yml 中配置过的材料。
背景板不能放物品。
Shift 快速放入会被阻止，避免材料进入错误区域。
关闭 GUI 时，未消耗材料会返还给玩家，背包满则掉落。
```

附加材料不是必需的。不放附加材料也能锻造，但通常只会得到较少或没有额外词条，具体取决于装备模板的等级词条配置。

## 6. sf 表达式

`sf:` 表达式用于让 PixelShop、ChunkWorld、MythicMobs、命令奖励等系统直接生成 SF 物品。

### 6.1 蓝图表达式

格式：

```text
sf:blueprint:<蓝图ID>
sf:blueprint:<蓝图ID>?tier=<固定等级>
sf:blueprint:<蓝图ID>?tier=random:<最小>-<最大>
```

示例：

```text
sf:blueprint:short_sword
sf:blueprint:short_sword?tier=2
sf:blueprint:short_sword?tier=random:1-3
sf:blueprint:ward_chestplate?tier=random:2-3
```

蓝图物品会写入：

```text
blueprint ID
蓝图等级
蓝图等级范围
```

如果蓝图是 `1-3` 范围，真正锻造时会在范围内随机一个等级。

### 6.2 装备表达式

格式：

```text
sf:equipment:<装备ID>?tier=<等级>
sf:equipment:<装备ID>?tier=<等级>&affixes=<最大词条数>
sf:equipment:<装备ID>?tier=<等级>&blueprint=<蓝图ID>
```

示例：

```text
sf:equipment:short_sword?tier=1
sf:equipment:short_sword?tier=3&affixes=4
sf:equipment:flintlock?tier=3&affixes=4
sf:equipment:ward_chestplate?tier=random:2-3&blueprint=ward_chestplate
```

注意：

```text
equipment 表达式直接生成成品装备，不经过锻造 GUI。
不经过锻造 GUI 时，不读取附加材料。
affixes 指定最多保留多少条词条。
不写 affixes 时默认 0 词条，等同于没有附加材料的直接成品。
```

## 7. 当前内置蓝图和装备

武器：

```text
short_sword       短剑，轻近战，均衡测试武器。
longsword         长剑，重近战，偏基础伤害和暴击伤害。
spear             长枪，长柄武器，偏破甲和寒缓。
dagger            短匕，轻近战，偏暴击、真实伤害。
flintlock         燧发枪，远程 firearm，偏灼烧、破甲、爆发。
source_bow        源质弓，远程 bow，偏稳定输出、蔓毒、寒缓。
source_crossbow   源质弩，远程 crossbow，偏破甲和暴击伤害。
```

防具：

```text
bulwark_helmet      玄铁守御头盔，物抗套。
bulwark_chestplate  玄铁守御胸甲，物抗套。
bulwark_leggings    玄铁守御护腿，物抗套。
bulwark_boots       玄铁守御靴子，物抗套。

ward_helmet         织法秘纹兜帽，法抗/元素抗套。
ward_chestplate     织法秘纹长袍，法抗/元素抗套。
ward_leggings       织法秘纹护腿，法抗/元素抗套。
ward_boots          织法秘纹软靴，法抗/元素抗套。
```

## 8. 当前内置附加材料

```text
sharp_stone        锐化石，偏攻击伤害、破甲。
focus_crystal      会心晶片，偏暴击率、暴击伤害。
venom_vial         淬毒药剂，偏木元素蔓毒、缓慢。
war_ember          战意余烬，偏火元素灼烧、攻击伤害。
blood_essence      血髓，偏暴击伤害、真实伤害。
frost_core         霜核，偏冰元素寒缓、破甲。
metal_spring       金属弹簧，偏暴击率、少量攻击伤害。
gunpowder_charge   火药装药，偏火元素灼烧、暴击伤害。
rime_shard         霜片，偏冰元素寒缓。
bulwark_plate      玄铁甲片，偏物理抗性、少量火抗。
rune_thread        秘纹丝线，偏法术抗性、火/冰/雷元素抗性。
```

每种附加材料在一次锻造里最多识别一次。如果同种材料堆叠放入，只会按该材料配置消耗对应 `amount` 并添加一次候选词条。

## 9. 锻造随机流程

实际锻造逻辑：

1. 读取蓝图 ID。
2. 读取蓝图对应的装备模板。
3. 校验基础材料是否满足蓝图 `requirements`。
4. 校验附加材料是否存在于 `materials/*.yml`。
5. 消耗基础材料。
6. 消耗识别到的附加材料。
7. 根据蓝图等级范围随机出实际装备等级。
8. 读取装备模板 `tier-affixes`，加入等级词条候选。
9. 读取附加材料配置，加入材料词条候选。
10. 每个候选按 `chance` 独立判定。
11. 判定成功后，在 `min` 到 `max` 间随机数值。
12. 根据武器 ID 或武器分类套用 `scale`。
13. 同一个词条多次出现时，只保留数值最高的一条。
14. 如果词条数超过上限，按候选权重随机保留。
15. 写入装备 PDC 和 lore。

锻造出的装备默认只负责基础定位属性：武器默认出物理伤害、法术伤害、暴击倍率；防具默认出物理防御、法术防御、闪避。元素伤害、元素抗性、真实伤害、暴击率等特殊数值由附加材料提供；吸血、攻击触发、右键主动等技能效果由附魔系统提供。

生成装备会写入：

```text
sourceforge:type
sourceforge:weapon_category
sourceforge:tier
sourceforge:affixes
各词条自己的 PDC 键
chunkworld:level
pixelshop:price
sourceforge:score
```

装备会设置为不可损坏，并隐藏原版属性和不可损坏标记。

## 10. config.yml 配置说明

### 10.1 GUI

```yaml
gui:
  title: "&0源质锻造"
```

### 10.2 调试

```yaml
debug:
  combat: false

forge:
  guarantee-material-affix: true
```

也可以通过命令修改：

```text
/sf debug combat on
/sf debug combat off
```

`forge.guarantee-material-affix` 为 `true` 时，每个有效附加材料如果正常随机没有产出词条，会从该材料可用词条里保底补一条。

### 10.3 蓝图物品外观

```yaml
blueprint-item:
  material: PAPER
  ce-id: "sourceforge:blueprint_short_sword"
  custom-model-data: 0
  name-format: "&9蓝图: %name%"
  lore:
    - "&7蓝图等级: &e%tier%"
    - "&7装备类型: &f%equipment_type%"
    - "&7所需材料: &f%materials%"
```

字段：

```text
material           没有 CE 物品时使用的 Bukkit 材质。
ce-id              CraftEngine 物品 ID，可为空。
custom-model-data  原版 CMD，大于 0 时写入。
name-format        蓝图显示名格式。
lore               支持 %tier%、%equipment_type%、%materials%、%blueprint%。
```

### 10.4 item-display-names

用于把材料 ID 显示为中文名。

```yaml
item-display-names:
  "minecraft:iron_ingot": "铁锭"
  "yuanzhi:yuanzhi1": "源质核心（Ⅰ级）"
  "sourceforge:sharp_stone": "锐化石"
```

### 10.5 blueprints

示例：

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

字段：

```text
display-name     蓝图显示名。
equipment-type   锻造出的装备 ID，必须能在 equipment 中找到。
tier.min/max     蓝图默认随机等级范围。
tier.fixed       大于 0 时固定等级。
affix-slots      预留字段，目前主要由 max-affixes 控制最终词条数量。
max-affixes      最多保留的词条数量。
requirements     基础材料列表。
```

### 10.6 equipment

示例：

```yaml
equipment:
  short_sword:
    display-name: "短剑"
    material: IRON_SWORD
    ce-id: "sourceforge:short_sword"
    weapon-category: melee_light
    chunkworld-level: "blueprint-tier"
    pixelshop-price: 100.0
    base-lore:
      - "&8源质锻造成品"
    affixes:
      - attack_damage
      - crit_chance
      - crit_damage
    tier-affixes:
      1:
        attack_damage:
          chance: 1.0
          min: 1.0
          max: 2.0
```

字段：

```text
display-name       装备显示名。
material           没有 CE 物品时使用的 Bukkit 材质。
ce-id              CraftEngine 物品 ID，可为空。
weapon-category    武器/装备分类，影响词条 scale 和战斗模式。
chunkworld-level   写入 chunkworld:level，blueprint-tier 表示使用锻造等级。
pixelshop-price    装备基础价格，最终写入 pixelshop:price 时会乘评分倍率。
base-lore          装备基础 lore。
affixes            允许出现的词条 ID。
tier-affixes       不同等级自带的词条候选。
```

推荐分类：

```text
melee_light      轻近战，例如短剑、短匕。
melee_heavy      重近战，例如长剑。
polearm          长柄，例如长枪。
bow              弓。
crossbow         弩。
firearm          枪。
armor_physical   物抗防具。
armor_magic      法抗/元素抗防具。
```

### 10.7 装备评分和价格

每件装备生成时会写入：

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

默认词条评分：

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

所以 `pixelshop-price` 现在表示基础价格，不是固定最终价格。等级越高、词条越强，评分越高，写入 PixelShop 的价格越高。

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

`combat-weights` 按战斗类型统一计分，`affix-weights` 按具体词条 ID 覆盖。权重可以写负数，用来让负面词条扣分。

## 11. affixes.yml 词条说明

词条定义示例：

```yaml
attack_damage:
  display-name: "锐锋"
  pdc-key: "attack_damage"
  value-type: double
  min: 1.0
  max: 3.0
  decimals: 1
  combat: damage
  melee:
    combat: damage
    damage-type: physical
    element: none
    scale: 1.0
  ranged:
    combat: damage
    damage-type: physical
    element: none
    scale: 0.75
  effects:
    longsword_melee:
      combat: damage
      scale: 1.15
    flintlock_ranged:
      combat: damage
      scale: 1.25
  lore: "&7锐锋: &f攻击伤害 +%value%"
```

基础字段：

```text
display-name    词条显示名。
pdc-key         写入装备 PDC 的键。
value-type      double / int / string。
min/max         默认范围，材料或等级词条没有范围时用它兜底。
decimals        lore 显示的小数位。
combat          战斗类型。
lore            写入装备 lore，支持 %name% 和 %value%。
```

战斗字段：

```text
damage-type     physical / magic / true。
element         none / fire / ice / lightning / water / wood。
scale           锻造时乘到最终词条值上。
status-chance   附带原版状态概率。
duration-ticks  状态持续 ticks，20 ticks = 1 秒。
amplifier       药水效果等级，从 0 开始。
```

`effects` 匹配优先级：

```text
武器ID_模式 > 武器ID > 武器分类_模式 > 武器分类 > melee/ranged
```

示例：

```text
dagger_melee
short_sword_melee
longsword_melee
spear_ranged
source_bow_ranged
source_crossbow_ranged
flintlock_ranged
armor_physical
armor_magic
```

当前支持的 `combat` 类型：

```text
damage                额外伤害，默认物理。
physical_damage       物理伤害。
magic_damage          非元素魔法伤害。
element_damage        元素伤害。
true_damage           真实伤害。
pierce                破甲，降低目标物理抗性。
crit_chance           暴击率。
crit_damage           暴击伤害倍率加成。
poison                木元素伤害，附带中毒。
burn                  火元素伤害，附带点燃。
slow                  冰元素伤害，附带缓慢。
physical_resistance   防具物理抗性。
magic_resistance      防具非元素魔法抗性。
fire_resistance       火元素抗性，百分制。
ice_resistance        冰元素抗性，百分制。
lightning_resistance  雷元素抗性，百分制。
dodge_chance          闪避率，受到伤害前概率闪避本次伤害。
```

吸血不再作为锻造词条出现，改由 SourceForge 附魔技能提供。

## 12. materials/*.yml 附加材料说明

示例：

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
```

字段：

```text
id                  材料配置 ID，用于 /sf givematerial。
display-name        材料显示名。
item                实际识别的物品 ID，可为 minecraft:* 或 CraftEngine ID。
amount              每次锻造识别该材料时消耗数量。
affixes             这个材料能加入的词条候选。
chance              候选词条出现概率。
min/max             候选词条随机范围。
weapon-types        限定装备 ID。
weapon-categories   限定装备分类。
type-scales         按装备 ID 缩放数值。
category-scales     按装备分类缩放数值。
```

限制规则：

```text
如果 weapon-types 非空，装备 ID 必须匹配。
如果 weapon-categories 非空，装备分类必须匹配。
type-scales 优先级高于 category-scales。
如果材料和基础材料使用同一种物品，需要额外多放一份，基础材料会优先被配方占用。
```

## 13. 战斗系统总览

SF 战斗系统分两部分：

```text
1. SourceForge 装备主动造成的伤害。
2. 玩家或生物受到原版伤害时的防御接管。
3. SourceForge 附魔被动和主动技能造成的额外效果。
```

### 13.1 SourceForge 武器伤害

当玩家使用 SF 武器近战，或使用已标记的投射物命中目标时：

1. 读取武器 PDC 里的词条。
2. 根据武器 ID、武器分类、近战/远程模式解析词条效果。
3. 拆分为物理、非元素魔法、元素、真实伤害。
4. 如果存在暴击概率，先判定是否暴击。
5. 暴击触发时只放大物理伤害和非元素魔法伤害。
6. 元素伤害和真实伤害不受暴击影响。
7. 读取目标怪物档案或默认档案。
8. 结算物抗、法抗、元素抗性、元素克制。
9. 触发中毒、点燃、缓慢和附魔被动。
10. 设置事件最终伤害。

远程武器支持：

```text
BOW
CROSSBOW
TRIDENT
SNOWBALL
EGG
```

弓、弩射出的投射物会在发射时复制武器词条；三叉戟、雪球、鸡蛋会在发射事件中标记。

### 13.2 原版伤害接管

玩家或生物受到伤害时，插件会按伤害类型分类，并重新计算伤害。

防御属性：

```text
原版护甲值 Attribute.ARMOR = 物理防御
原版护甲韧性 Attribute.ARMOR_TOUGHNESS = 法术防御
SourceForge 防具 physical_resistance = 物理抗性，0.35 表示额外降低 35%
SourceForge 防具 magic_resistance = 非元素魔法抗性，0.35 表示额外降低 35%
SourceForge 防具 fire/ice/lightning_resistance = 元素抗性，百分制
```

物理和非元素魔法防御公式：

```text
如果原始伤害 <= 0:
  最终伤害 = 0

如果防御值 <= 0:
  最终伤害 = max(1.0, 原始伤害)

如果防御值 > 0:
  伤害倍率 = max(0.10, 攻击力 / (攻击力 + 防御值))
  最终伤害 = max(1.0, 原始伤害 * 伤害倍率)
```

攻击力来源：

```text
优先读取伤害来源实体的 Attribute.ATTACK_DAMAGE。
如果没有实体攻击者，则使用 max(1.0, 原始伤害) 作为攻击力。
```

### 13.3 伤害分类

真实伤害：

```text
fall
fly_into_wall
out_of_world
outside_border
generic_kill
stalagmite
```

带元素魔法伤害：

```text
campfire            火元素
fireball            火元素
hot_floor           火元素
in_fire             火元素
lava                火元素
on_fire             火元素
unattributed_fireball 火元素
lightning_bolt      雷元素
```

非元素魔法伤害：

```text
bad_respawn_point
dragon_breath
explosion
fireworks
indirect_magic
magic
player_explosion
sonic_boom
wither
wither_skull
```

其他未列出的伤害默认按物理伤害结算，例如普通攻击、箭矢、三叉戟、仙人掌、窒息、溺水、饥饿、摔落方块、荆棘等。

## 14. 元素抗性百分制

元素伤害只读取对应元素抗性，不叠加 `magic_resistance`。

公式：

```text
倍率 = 1 - 元素抗性 / 100
```

示例：

```text
fire_resistance = 25.0
受到火元素伤害 = 原始火元素伤害 * 0.75

fire_resistance = -25.0
受到火元素伤害 = 原始火元素伤害 * 1.25

fire_resistance = 100.0
受到火元素伤害 = 0

fire_resistance = 120.0
受到火元素伤害 = 0
治疗 = 原始火元素伤害 * 0.20
```

玩家防具词条示例：

```yaml
fire_resistance:
  min: 3.0
  max: 6.0
  lore: "&7避火: &f火元素抗性 +%value%%"
```

怪物档案示例：

```yaml
element-resistances:
  fire: -25.0
  wood: 45.0
  lightning: 120.0
```

含义：

```text
fire: -25.0      多受 25% 火元素伤害。
wood: 45.0       少受 45% 木元素伤害。
lightning: 120.0 雷元素伤害变为治疗，治疗量为原始雷元素伤害的 20%。
```

## 15. combat.yml 怪物档案

怪物档案示例：

```yaml
monster-profiles:
  wood_creature:
    display-name: "木系怪物"
    tags:
      - "sf_profile:wood_creature"
    entity-types: []
    name-contains: [] # 已废弃，不参与匹配。
    elements:
      - wood
    attack-type: element
    attack-element: wood
    physical-resistance: 0.10
    magic-resistance: 0.05
    element-resistances:
      fire: -25.0
      wood: 45.0
      water: 20.0
```

匹配优先级：

```text
1. 实体 scoreboard tag 里有 sf_profile:<档案ID>。
2. 实体 scoreboard tag 匹配 profile.tags。
3. 实体类型匹配 entity-types。
4. 使用 default-monster-profile。
```

`name-contains` 已废弃，不再参与匹配；保留字段只是为了兼容旧配置。不要再用怪物名字判断抗性。

怪物普通攻击类型由档案指定：

```yaml
monster-profiles:
  wood_creature:
    tags:
      - "sf_profile:wood_creature"
    entity-types: []
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

`attack-type` 可写 `physical`、`magic`、`element`、`true`。`attack-type: element` 时读取 `attack-element`，例如 `fire`、`ice`、`lightning`、`water`、`wood`。

`elements` 用于元素克制表：

```yaml
element-reactions:
  fire:
    wood: 1.35
    ice: 1.20
    water: 0.70
    fire: 0.45
```

含义：攻击元素是 `fire`，目标档案带 `wood` 元素时，最终元素伤害再乘 `1.35`。

元素吸收治疗上限：

```yaml
element-absorb:
  max-heal-per-hit: 10.0
```

`max-heal-per-hit` 控制元素抗性超过 100% 后的单次最大治疗量。写 `10.0` 表示每次最多治疗 10 点生命，写 `0.0` 表示关闭元素吸收治疗。

## 16. 套装效果

当前内置两套基础套装效果，按穿戴件数自动生效：

```text
玄铁守御 bulwark_*:
2 件: 额外 +0.05 物理抗性
4 件: 额外 +0.12 物理抗性

织法秘纹 ward_*:
2 件: 额外 +0.05 非元素法术抗性，火/冰/雷元素抗性各 +3%
4 件: 额外 +0.12 非元素法术抗性，火/冰/雷元素抗性各 +8%
```

套装加成会叠加到装备词条总和上。物理/法术抗性最终仍限制在 `0.75` 以内，元素抗性使用百分制，不做 `0.75` 上限。

## 17. 与其他插件接入

### 17.1 CraftEngine

配置中出现的 `ce-id` 和非 `minecraft:` 物品 ID 会优先走 CraftEngine。

示例：

```yaml
equipment:
  short_sword:
    material: IRON_SWORD
    ce-id: "sourceforge:short_sword"
```

如果 CraftEngine 物品不存在，可能无法生成目标外观或材料。

### 17.2 PixelShop

商品 ID 可以写 `sf:` 表达式。

卖蓝图：

```yaml
items:
  - id: "sf:blueprint:short_sword?tier=random:1-3"
    money: 500
    points: 0
    limit: 1
    refresh: daily
```

卖成品：

```yaml
items:
  - id: "sf:equipment:short_sword?tier=3&affixes=4"
    money: 3000
    points: 0
    limit: 1
    refresh: weekly
```

建议优先卖蓝图，保留锻造材料循环。

### 17.3 ChunkWorld

战利品、容器补货、刷怪笼奖励等地方可以直接写 `sf:` 表达式。

```yaml
loot-pools:
  default:
    items:
      short_sword_blueprint:
        id: "sf:blueprint:short_sword?tier=random:1-3"
        amount: 1
        chance: 0.03
```

### 17.4 MythicMobs

可以用命令发放奖励：

```yaml
Skills:
  - command{c="sf give <target.name> sf:blueprint:short_sword?tier=random:1-3 1";asConsole=true} @trigger
```

推荐给怪物添加 scoreboard tag 来匹配 SF 怪物档案。MythicMobs 怪物配置位置示例：

```text
plugins/MythicMobs/Mobs/sourceforge_mobs.yml
```

怪物配置示例：

```yaml
Skills:
  - command{c="tag <mob.uuid> add sf_profile:wood_creature";asConsole=true} @self ~onSpawn
```

如果你的 MythicMobs 版本不支持 `<mob.uuid>` 这种占位，改用该版本支持的 tag/scoreboard 机制即可，最终目标是让实体带：

```text
sf_profile:<档案ID>
```

## 17. 常用测试流程

### 17.1 测试短剑锻造

```text
/sf giveblueprint Steve short_sword 1-3 1
/sf givematerial Steve sharp_stone 1
/sf givematerial Steve focus_crystal 1
/sf forge
```

### 17.2 测试远程武器

```text
/sf giveblueprint Steve flintlock 3 1
/sf givematerial Steve gunpowder_charge 2
/sf givematerial Steve focus_crystal 1
/sf forge
/sf debug combat on
```

然后用生成的武器攻击目标，看调试输出。

### 17.3 测试物理防具

```text
/sf giveblueprint Steve bulwark_helmet 3 1
/sf giveblueprint Steve bulwark_chestplate 3 1
/sf giveblueprint Steve bulwark_leggings 3 1
/sf giveblueprint Steve bulwark_boots 3 1
/sf givematerial Steve bulwark_plate 4
/sf forge
```

让僵尸、骷髅、普通怪攻击玩家。

### 17.4 测试元素抗性和吸收

把某件防具写入超过 100 的火抗，或临时配置高火抗词条：

```yaml
fire_resistance:
  min: 120.0
  max: 120.0
```

穿上后受到火元素伤害，应当不受伤并回血。

## 18. 常见问题

### 18.1 `/sf givematerial` 无法生成材料

检查：

```text
materials/*.yml 的 item 是否存在。
CraftEngine 是否有对应物品。
如果是原版物品，是否写成 minecraft:xxx。
```

### 18.2 锻造界面放不进物品

检查：

```text
蓝图槽只能放 SF 蓝图。
基础材料区只收当前蓝图 requirements。
附加材料区只收 materials/*.yml 里配置过的材料。
Shift 快速放入会被阻止，需要手动放。
```

### 18.3 装备没有词条

可能原因：

```text
没有放附加材料。
材料词条 chance 没通过。
材料的 weapon-types 或 weapon-categories 不匹配。
装备 affixes 列表没有包含该词条。
max-affixes 太低，词条被随机筛掉。
```

### 18.4 远程武器词条不生效

检查：

```text
武器是否是 SF 装备。
是否通过弓/弩/三叉戟/雪球/鸡蛋事件发射。
投射物是否由玩家发射。
是否开启 /sf debug combat on 查看注入提示。
```

### 18.5 元素抗性看起来没有生效

确认单位：

```text
元素抗性现在是百分制。
5.0 表示 5%，不是 0.05。
120.0 表示吸收并治疗 20% 原伤害。
```

旧装备提醒：

```text
如果旧装备 PDC 里存的是 0.05，现在会被当成 0.05%，不是 5%。
旧装备需要迁移，或者重新锻造。
```

### 18.6 怪物档案没有匹配

检查：

```text
实体是否真的带 scoreboard tag。
tag 是否是 sf_profile:<档案ID>。
combat.yml 中档案 ID 是否小写一致。
entity-types 是否使用 Bukkit 实体名的小写形式，例如 blaze、magma_cube。
name-contains 已废弃，不再参与匹配；请给 MM 怪物添加 sf_profile:<档案ID>。
```

## 19. 维护建议

推荐配置思路：

```text
武器数值先从 attack_damage 控制基础强度。
破甲只影响物理抗性，不影响元素抗性。
元素伤害用于绕开物理/法术防御，但会被对应元素抗性克制。
真实伤害保持稀有，并用 true-damage-scale 压低强度。
火/冰/雷抗性使用百分制后，建议普通装备单件 2% - 8%，套装 20% - 40%。
超过 100% 的元素吸收建议只给特殊装备、Boss 或机制怪使用。
```

改配置后的检查顺序：

```text
1. /sf reload
2. /sf giveblueprint 测试蓝图是否存在
3. /sf givematerial 测试材料是否能生成
4. /sf forge 测试 GUI 是否能锻造
5. /sf debug combat on 测试战斗结算
6. 查看控制台是否有 YAML 或 CraftEngine 物品错误
```

## 20. 快速命令合集

```text
/sf forge
/sf reload
/sf debug combat on
/sf debug combat off

/sf giveblueprint Steve short_sword 1-3 1
/sf giveblueprint Steve flintlock 1-3 1
/sf giveblueprint Steve source_bow 1-3 1
/sf giveblueprint Steve ward_chestplate 2-3 1

/sf givematerial Steve sharp_stone 1
/sf givematerial Steve focus_crystal 1
/sf givematerial Steve rune_thread 4

/sf give Steve sf:blueprint:short_sword?tier=random:1-3 1
/sf give Steve sf:equipment:short_sword?tier=3&affixes=4 1
```
