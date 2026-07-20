# 遗纹遗物配置

`dreammark-relics/` 会递归加载全部 `.yml`。每个文件的 `relic-id` 必须唯一，并对应一个已在 CraftEngine 与 `relics.yml` 登记的遗物物品。

每份配置独立控制：

- `tasks.unlock`：携带该遗物进入荒野时接取的 SourceTasks 任务；完成后才能回城兑换。
- `tasks.unveil`：兑换出封缄彼端遗纹时接取的 SourceTasks 任务；配置该任务的目标类型为 `DREAMMARK_UNVEIL`。
- `reward.weapons`：可被抽取的具体 SourceForge 装备 id，支持权重。
- `reward.affixes`：封缄时已确定的满段词条数值；标量固定，`min/max` 每张随机。
- `reward.capacity`：该遗纹安装到装备普通 MOD 槽后占用的容量曲线。

封缄物品会把目标武器、词条、占用和来源配置快照写入 PDC；已有物品不会被后续重载或改配置破坏。对已苏醒遗纹使用梦织时，会按其 `relic-id` 对应的当前配置重新生成词条。
