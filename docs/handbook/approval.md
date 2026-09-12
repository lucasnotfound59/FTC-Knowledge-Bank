# 审批规则与完整示例

## 扩大范围不是格式迁移

team→global 扩大适用范围必须取得新的总软件负责人审批（overall_software_lead），不能复用旧的 team_software_lead 审批或只改队号为空。迁移后使用 shared 来源和 global policyLevel；原赛季限制保留，candidate 不因迁移自动转正。

迁移台账必须保留旧审批的 approver/role/team/approvedAt、旧/新 ID、固定来源、内容冲突及新审批时间与授权依据。旧审批证明的是旧范围；新审批证明扩大后的范围，不能把旧日期伪装成 global 授权。参考 [本次迁移台账](../rule-migrations/2026-09-11-team-rules-global-teamchina.md)。普通原范围内 candidate 审批仍按下述流程执行。示例占位身份与时间不授予任何人审批权。

审批必须在原 candidate 的同一条目内完成：保留它的 `id`、内容、适用范围和证据，把 `status` 从 `candidate` 改为 `approved`，再添加 `approval`。不要把审批片段另存成独立规则文件。

官方规则和共享规则只能由总软件负责人审批。下面是一个完整、可校验的 approved shared 规则文档：

```yaml
schemaVersion: 4
rules:
  - id: shared.example-approved
    topic: example-shared-practice
    title: Example shared practice
    instruction: Describe one shared action the code should follow.
    rationale: Explain why multiple teams use this practice.
    status: approved
    authority: shared
    policyLevel: global
    applicability:
      teams: []
      seasons: ["2025-2026"]
      profiles: []
    evidence:
      - type: git
        repository: owner/repository
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
schemaVersion: 4
rules:
  - id: team-20827.example-approved
    topic: example-team-practice
    title: Example team practice
    instruction: Describe one action team 20827 code should follow.
    rationale: Explain why team 20827 uses this practice.
    status: approved
    authority: team
    policyLevel: local
    applicability:
      teams: ["20827"]
      seasons: ["2025-2026"]
      profiles: []
    evidence:
      - type: git
        repository: owner/repository
        commit: abcdef1234567890
        file: TeamCode/src/main/java/example/TeamExample.java
        symbol: TeamExample
    approval:
      approver: lead-20827
      role: team_software_lead
      team: "20827"
      approvedAt: 2026-08-13T00:00:00Z
```

`approved` 规则如果缺少审批会校验失败，非 `approved` 规则则不能包含审批。审批是受校验、受版本控制的 YAML 元数据，不是身份认证或不可变审批历史。

日常操作优先使用[候选提取与审批命令](../../docs/candidate-extraction.md)。以下 YAML 示例表达审批数据格式，不赋予使用者审批权限。
