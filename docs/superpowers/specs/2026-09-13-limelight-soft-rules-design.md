# Limelight 规则降级为 Soft 提醒 — 设计说明

日期：2026-09-13  
目标版本：仓库 V0.4.1；CLI 2.0.0、YAML schema 和 kernel JSON v2 保持不变。

## 1. 问题

`shared.limelight-check-result-validity` 与
`shared.limelight-enforce-freshness-policy` 当前各带一条
`regex-required`，且 `appliesTo` 为 `**/*.java`。现有检查器只能按路径和新增行正则判断，
不能确认文件是否真的读取 Limelight。结果是 `DriveSubsystem.java` 等无视觉代码也会因为缺少
`isValid()` 或 freshness 调用而产生 hard violation。

`installationOk=true` 只说明项目接入有效；这种误报仍会令 `projectCheck.ok=false` 和 CLI
退出码为 1。向无关文件插入视觉代码绕过检查不可接受。

## 2. 已确认方案

采用最小、确定性的软化方案：

- 删除上述两条规则的 `checks`；
- 保留规则 ID、topic、instruction、rationale、approved 状态、authority、适用范围、证据和原审批；
- 不把规则改回 candidate，不删除 Limelight 安全指导；
- 不增加文件名猜测，不使用 `**/*Vision*.java` 等不可靠范围；
- 本次不扩展 standardizer 的条件触发语言。

当前 standardizer 对没有 `checks` 的 active rule 输出一条 `soft`。因此两条规则会在每次
`ftckb check` 中作为人工确认提醒出现，但不会进入 `violations`，也不会令检查退出 1。
这是用户明确接受的权衡：暂时允许无关 diff 出现提示，以消除错误阻断。

## 3. 对外行为

迁移前：

```text
无关 Java 新增行 -> 两条 regex-required violation -> check exit 1
```

迁移后：

```text
任意 diff -> 两条 Limelight soft 提醒 -> 不产生对应 hard violation
```

`resolve --json` 中两条规则仍位于 `activeRules`，但 `checks` 为空。`check --json` 中提醒位于
`soft`；是否最终允许机器人运行仍由代码审查、测试和真机验证决定。

## 4. 文档与版本

仓库版本提升为 V0.4.1，表示对 V0.4.0 误报的兼容性修复。CLI 代码、CLI 2.0.0、YAML
支持版本和 kernel JSON v2 均不改变。规则总数仍为 46（40 approved + 6 candidate）。

同步更新所有声称“两条 Limelight hard check 仍会误报”的用户文档，改为：

- Limelight validity/freshness 当前是 approved soft guidance；
- `check` 不会因无关 Java 文件缺少视觉调用而失败；
- soft 仍需人工判断，编译或静态检查不等于真机安全；
- 当前硬检查规则数量由 6 条降为 4 条。

Limelight 教程中“这些规则当前都是 candidate”的陈旧说明也必须改为实际状态：五条
Limelight 规则均为 approved，其中 validity/freshness 两条是 soft。

## 5. 验收

增加仓库级回归，至少证明：

1. 两条目标规则仍为 approved、证据与审批存在且 `checks` 为空；
2. 普通 `DriveSubsystem.java` 新增行不会产生两条 Limelight violation；
3. 检查结果的 `soft` 包含两个准确 rule ID；
4. 即使新增代码含 Limelight 读取，两条规则仍只提示、不 hard fail；
5. `validate knowledge --json` 为 `ok:true`，规则数仍为 46；
6. generic resolve 无冲突；完整 Kotlin、Python、smoke 和 `git diff --check` 通过。

不宣称 Android Studio UI、Robot Controller、Driver Station、部署或真机验证完成。

## 6. 非目标

- 不实现基于 `Limelight3A`、`LLResult` 或 `getLatestResult()` 的条件式 soft/hard 触发；
- 不改变其他 Limelight 规则；
- 不更改规则优先级、profile、接入协议或 API key 行为；
- 不自动 tag 或 push。

未来如需要减少 soft 噪声，可另行设计让 `reviewTriggers` 参与 standardizer 提示过滤；该功能
必须有独立契约和回归，不能在本次补丁中暗中加入。
