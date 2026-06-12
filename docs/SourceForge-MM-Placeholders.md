# SourceForge MythicMobs 原生占位符

以下占位符可在 MM 技能、怪物配置、物品 Lore 等任意位置使用，读取施法者（caster）全身 SourceForge 装备的属性总和。

格式：`<sourceforge.属性ID>` 或缩写 `<sf.属性ID>`

---

## 战斗属性

| 占位符 | 说明 | 示例值 |
|---|---|---|
| `<sourceforge.base_damage>` | 基础伤害（全身总和） | `3.5` |
| `<sourceforge.critical_chance>` | 暴击几率（小数） | `0.15` |
| `<sourceforge.critical_damage>` | 暴击伤害倍率（小数） | `0.45` |
| `<sourceforge.status_chance>` | 触发几率（小数） | `0.08` |

## 生存属性

| 占位符 | 说明 | 示例值 |
|---|---|---|
| `<sourceforge.armor>` | 护甲值 | `12` |
| `<sourceforge.health>` | 生命值加成 | `8` |
| `<sourceforge.shield_capacity>` | 护盾容量 | `5` |

## 技能属性

| 占位符 | 说明 | 基础值 | 示例值 |
|---|---|---|---|
| `<sourceforge.energy_max>` | 能量上限 | `0` | `20` |
| `<sourceforge.ability_strength>` | 技能强度 | **1.0 (100%)** | `1.5` |
| `<sourceforge.ability_duration>` | 技能持续时间 | **1.0 (100%)** | `1.3` |
| `<sourceforge.ability_efficiency>` | 技能效率（减CD） | **0.0 (0%)** | `0.5` |
| `<sourceforge.ability_range>` | 技能范围（格） | **3.0 (3格)** | `4.5` |

> 基础值说明：裸装时 `ability_strength` 和 `ability_duration` 返回 `1.0`（100%），`ability_range` 返回 `3.0`（3格），`ability_efficiency` 返回 `0.0`。穿装备后在此基础上叠加。

---

## 用法示例

### 技能伤害受强度影响

```yaml
SF_BladeDance:
  Cooldown: 3
  Skills:
  - damage{amount=5} @EIR{r=<sf.ability_range>}
  - message{m="&cDMG 5 * <sf.ability_strength> = &f<skill.damage>"} @Self
```

### 药水持续时间受技能持续影响

```yaml
SF_HasteAura:
  Cooldown: 3
  Skills:
  - potion{type=Haste;duration=1200;level=1} @Self
  - message{m="&eHaste 60s * <sf.ability_duration>"} @Self
```

### 怪物伤害读取玩家强度

```yaml
SFDummy:
  Type: ZOMBIE
  Skills:
  - message{m="&c-<skill.damage> &7| Caster STR &f<sourceforge.ability_strength>"} @trigger ~onDamaged
```

### 怪物配置中引用

```yaml
MyBoss:
  Type: ZOMBIE
  Health: 1000
  Skills:
  - damage{amount=<sf.ability_strength>} @target ~onAttack
```

---

## 全部属性 ID 速查

```
base_damage        critical_chance     critical_damage     status_chance
armor              health              shield_capacity
energy_max         ability_strength    ability_duration
ability_efficiency ability_range
```

每个属性都有两种写法：
- `<sourceforge.属性ID>` — 全名
- `<sf.属性ID>` — 缩写
