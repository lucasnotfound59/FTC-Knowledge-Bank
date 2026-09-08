# 审批规则与完整示例

审批必须在原 candidate 的同一条目内完成：保留它的 `id`、内容、适用范围和证据，把 `status` 从 `candidate` 改为 `approved`，再添加 `approval`。不要把审批片段另存成独立规则文件。

官方规则和共享规则只能由总软件负责人审批。下面是一个完整、可校验的 approved shared 规则文档：

```yaml
schemaVersion: 1
rules:
  - id: shared.example-approved
    topic: example-shared-practice
    title: Example shared practice
    instruction: Describe one shared action the code should follow.
    rationale: Explain why multiple teams use this practice.
    status: approved
    authority: shared
    applicability:
      seasons: [2025-2026]
    evidence:
      - repository: owner/repository
        commit: abcdef1234567890
        file: TeamCode/src/main/java/example/SharedExample.java
        symbol: SharedExample
    approval:
      approver: overall-software-lead
      role: overall_software_lead
      approvedAt: 2026-08-13T00:00:00Z
```

team 规则只能由该队的软件负责人审批，并且规则必须只适用于审批人对应的同一个队号。完整文档示例：

```yaml
schemaVersion: 1
rules:
  - id: team-20827.example-approved
    topic: example-team-practice
    title: Example team practice
    instruction: Describe one action team 20827 code should follow.
    rationale: Explain why team 20827 uses this practice.
    status: approved
    authority: team
    applicability:
      teams: ["20827"]
      seasons: [2025-2026]
    evidence:
      - repository: owner/repository
        commit: abcdef1234567890
        file: TeamCode/src/main/java/example/TeamExample.java
        symbol: TeamExample
    approval:
      approver: lead-20827
      role: team_software_lead
      team: "20827"
      approvedAt: 2026-08-13T00:00:00Z
```

`approved` 规则如果缺少审批会校验失败，非 `approved` 规则则不能包含审批。当前审批只是受校验、受版本控制的 YAML 元数据，尚未实现审批 UI 或不可变审批历史。

日常操作优先使用[候选提取与审批命令](../../docs/candidate-extraction.md)。以下 YAML 示例表达审批数据格式，不赋予使用者审批权限。
