# Command 规则真实批准验收（2026-10-23）

用户明确批准审批卡四条规则，批准人原话“我,lucas”，角色 overall_software_lead。执行前 YAML SHA256 为 `7a6f6fee1b913e0f2c6de92a7954d2039a995379ef9b6a3acd99e3e01c4127e1`，与审批卡一致。四次真实 `ftckb approve knowledge --id ID --approver lucas --role overall_software_lead` 均退出 0、stderr 空；未用内存副本或测试身份冒充批准。

批准 ID：`shared.command-runtime-boundary`、`shared.command-scheduler-lifecycle`、`shared.command-default-resumption`、`shared.command-scheduler-reentrancy`。独立审查确认除 status/approval 外四条所有字段均与卡片一致，其他规则原字节未变。当前 validate 为 schema2、ok=true、52 条（46 approved、6 candidate）。

真实 2 队 × 2 赛季 × 5 profile 共 20 次 resolve 全部 schema2、exit0、0 conflicts、stderr 空。相对候选阶段，command-based / ftclib-command 仅增加上述四个 active IDs；generic / simple-opmode / rookiebot active IDs 不变。2025-2026 数量为 26/33/34/26/38，2026-2027 为 17/21/21/17/29（依次 generic/command-based/ftclib-command/simple-opmode/rookiebot）。test 模式不额外排除这四条；现有两条 work-mode-test 排除保持原契约。

三个 fixture（validate-ok、resolve-ok、resolve-test）由真实 CLI 重新生成。当前文档计数与 profile 数量已同步，历史候选阶段报告保留历史事实。

审批后受影响测试 59/59：CommandBasedKnowledgeTest 4、CommandFrameworkReviewTest 4、CliDocumentationAcceptanceTest 9、KnowledgeGuideAcceptanceTest 10、TeamChinaRuleMigrationAcceptanceTest 9、VendorDependencyRuleAcceptanceTest 10、KernelJsonAcceptanceTest 13。后续检索/裁决/候选兼容/Dashboard 补集 64/64；两组合类不重叠，共 123 个不同 Kotlin 用例。测试未声称 Robot Controller、Driver Station 或真机通过。

原始证据位于 `/private/tmp/ftckb-command-approval-20261023/`：approval.json、validate.json、matrix-summary.json、20 个 resolve JSON、tests.json、tests.log。补集 `/private/tmp/ftckb-final-complement-tests-20261023.{json,log}`。主 Dashboard 已恢复为稳定发行副本的 54327，实际 main knowledge / 默认 audit 身份与 52 条目录已核；旧 60284 保留。凭据仅私有文件，不入仓库。当前修改均未 commit/push。
