# Limelight 规则降级为 Soft 提醒 — 设计说明

日期：2026-09-13  
目标版本：仓库 V0.5.0；CLI 2.0.0、YAML v4 和 kernel JSON v2 保持不变。

## 1. 问题

`shared.limelight-check-result-validity` 与
`shared.limelight-enforce-freshness-policy` 当前各带一条
`regex-required`，且 `appliesTo` 为 `**/*.java`。现有检查器只能按路径和新增行正则判断，
不能确认文件是否真的读取 Limelight。结果是 `DriveSubsystem.java` 等无视觉代码也会因为缺少
`isValid()` 或 freshness 调用而产生 hard violation。

`installationOk=true` 只说明项目接入有效；这种误报仍会令 `projectCheck.ok=false` 和 CLI
退出码为 1。向无关文件插入视觉代码绕过检查不可接受。

## 2. Hard 与 Soft 的正式语义

用户确认的产品语义为：

- hard：机器确定违反后进入 `violations`，`check` 返回 1，Agent 必须修复，不能交付；
- soft：相关改动触发人工审阅后进入 `soft`，`check` 仍返回 0，Agent 必须把提示展示给用户，
  但不擅自阻止交付；
- 没有相关改动：既不产生 hard violation，也不产生该规则的 soft 提示。

soft 不能被描述成机器已经证明代码违反安全要求。它表示“相关代码需要模型和人检查”。
例如正则能看见 `getLatestResult()`，但不能可靠证明所有控制流都正确处理 validity/freshness。

## 3. 已确认方案

采用已有 `reviewTriggers` 表达条件式 soft 审阅：

- 删除上述两条规则的 hard `checks`；
- 将 `knowledge/shared/tools/limelight.yaml` 从 schema v3 迁移为 v4；
- 为两条规则添加 `reviewTriggers`，路径仍可覆盖 Java，但新增行必须出现 Limelight 相关标识，
  例如 `Limelight3A`、`LLResult`、`getLatestResult()` 或 Limelight pose 读取；
- Standardizer 对“无 checks 且有 reviewTriggers”的 active rule，仅在任一 trigger 匹配 diff 时
  写入 `soft`；
- 无 checks 且无 reviewTriggers 的既有 soft 规则继续始终提示，保持兼容；
- 保留目标规则 ID、topic、instruction、rationale、approved 状态、authority、适用范围、证据和原审批；
- 不把规则改回 candidate，不使用 `**/*Vision*.java` 等文件名猜测。

`reviewTriggers.paths` 先匹配触及路径。`addedLinePatterns` 非空时，只要该路径的一条新增行匹配
任一正则即触发；为空时，触及路径本身即触发。规则级多个 trigger 使用 OR。结果按 rule ID
确定性排序并去重，每条规则最多产生一条 soft 提示。

## 4. 对外行为

迁移前：

```text
无关 Java 新增行 -> 两条 regex-required violation -> check exit 1
```

迁移后：

```text
无 Limelight 标识的 Java diff -> 无 Limelight hard/soft 结果 -> check exit 0
出现 Limelight 相关标识的 diff -> 两条 Limelight soft 提醒 -> check exit 0
```

`resolve --json` 中两条规则仍位于 `activeRules`，`checks` 为空，并携带 `reviewTriggers`。
`check --json` 的现有 `soft` 结构不变；Agent 根据 rule ID、instruction 和相关 diff 向用户展示
提示。是否构成真实违反、是否允许机器人运行，仍由模型审阅、人工判断、测试和真机验证决定。

## 5. 文档与版本

仓库版本提升为 V0.5.0，因为 standardizer 新增条件式 soft 审阅能力。CLI 命令和输出结构不变，
因此 CLI 2.0.0 与 kernel JSON v2 不提升。Limelight 文件迁移到已支持的 YAML v4，不新增 schema
版本。规则总数仍为 46（40 approved + 6 candidate）。

同步更新所有声称“两条 Limelight hard check 仍会误报”的用户文档，改为：

- Limelight validity/freshness 当前是 approved、triggered soft guidance；
- `check` 不会因无关 Java 文件缺少视觉调用而失败，也不会产生无关 Limelight soft；
- 相关视觉新增行会触发 soft，Agent 必须向用户报告；
- soft 是人工审阅请求，不是自动认定违反或自动通过；编译或静态检查不等于真机安全；
- 当前硬检查规则数量由 6 条降为 4 条。

Limelight 教程中“这些规则当前都是 candidate”的陈旧说明也必须改为实际状态：五条
Limelight 规则均为 approved，其中 validity/freshness 两条是 soft。

## 6. 验收

增加仓库级回归，至少证明：

1. 两条目标规则仍为 approved、证据与审批存在、`checks` 为空且 `reviewTriggers` 完整；
2. 普通 `DriveSubsystem.java` 新增行既不产生 Limelight violation，也不产生 Limelight soft；
3. 新增 `getLatestResult()`、`LLResult` 等相关代码时，`soft` 包含两个准确 rule ID；
4. 相关视觉代码即使缺少 validity/freshness 处理也只提示、退出 0；
5. 同一规则多个 trigger 同时命中仍只输出一次；输出顺序确定；
6. 无 trigger 的既有 soft 规则继续始终提示，避免兼容性回归；
7. `validate knowledge --json` 为 `ok:true`，规则数仍为 46；
8. generic resolve 无冲突；完整 Kotlin、Python、smoke 和 `git diff --check` 通过。

不宣称 Android Studio UI、Robot Controller、Driver Station、部署或真机验证完成。

## 7. 非目标

- 不声称 soft trigger 能完成 Java 控制流分析或自动证明代码违反规则；
- 不增加新的 hard check 类型或改变现有 hard check 行为；
- 不改变其他 Limelight 规则；
- 不更改规则优先级、profile、接入协议或 API key 行为；
- 不自动 tag 或 push。
