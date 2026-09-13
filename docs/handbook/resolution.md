# 校验、适用范围与确定性裁决

当前发布为 V0.6.0（CLI 2.0.0、YAML v4、kernel JSON v2、项目接入协议 v2）。

```bash
ftckb validate knowledge --json
ftckb resolve knowledge --team 20827 --season 2025-2026 --generic-profile --json
ftckb resolve knowledge --team 20827 --season 2025-2026 --profile command-based --json
```

这两条 resolve 分别展示 generic 与命令架构，不需要 API key；同项目 check 使用同一 profile。

来源 authority（official/team/shared）与策略 policyLevel（global/local/shared）不同。有效优先级为 **OFFICIAL > GLOBAL > LOCAL > SHARED**：official 来源始终最高，其余按 policyLevel，不按路径或 ID。

1. 只有 approved 能生效；candidate/deprecated/rejected 进入 excludedRules。
2. teams/seasons 空列表表示不限；非空按队号和赛季过滤。global 不意味着跨赛季：本次迁移保留 2025-2026。
3. profiles 为空表示无架构要求，非空时必须全部包含在 normalized profiles 中。generic 是显式空选择；rookiebot 隐含 simple-opmode，ftclib-command 隐含 command-based，simple-opmode 和 command-based 不兼容。不得根据依赖推断。
4. excludedRules 记录所有 status/team/season/profile 不匹配原因。
5. 同主题低有效层级进入 overriddenRules，记录 ruleId/topic/winnerIds/effectiveLevel（最高层级）。
6. 同主题最高有效层级存在两条以上规则即 conflict，即使来源 authority 不同；该主题没有 active 胜者，其余主题仍可输出。

kernel JSON v2 冲突含 topic/effectiveLevel/ruleIds/authorities。resolve 冲突退出 2、ok=false；文本模式抑制 active 行。缺 profile 是 context-required（2），未知/互斥 profile 是 invalid-context（2），语法错误是 usage（64）。

activeRules 按 id、conflicts 按 topic、excludedRules/overriddenRules 按 ruleId，嵌套 IDs/reasons/profiles 排序；同数据和上下文结果逐字节一致。不能用“最后一个规则”覆盖冲突。

当前 47（41 已批准 + 6 候选）；20827 与 16093 同赛季/profile 的 active IDs 相同，数量为 generic 25、command-based 28、rookiebot 37、ftclib-command 29。generic 不接收三条 global.command-*，command-based 才接收；RookieBot 12 条实践仅 rookiebot 接收。`global.test-utility-layout` 是唯一明确跨赛季的 global rule，也是第 5 条带硬检查的 active rule：机器人侧 OpMode 进入 `TeamCode/src/main/java/org/firstinspires/ftc/teamcode/tests/`，工具进入 `TeamCode/src/main/java/org/firstinspires/ftc/teamcode/utils/`；确定的错误路径/JUnit 新增退出码 1，而完整 Agent instruction 仍裁决语义分类。Knowledge Bank 自身 Kotlin/CLI JUnit 测试不在目标 TeamCode 禁止范围内。此规则裁决和编译均不代表真机通过。完整字段见 [机器契约](../kernel-contract.md)。
