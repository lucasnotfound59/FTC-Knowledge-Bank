# 创建候选规则与团队协作

## 创建候选规则

1. 不要覆盖已有的 `rules.yaml`。推荐在对应 authority 目录创建一个名称唯一的新文件，例如 `knowledge/teams/20827/example.yaml`。
2. 新文件必须是完整文档，包含一次 `schemaVersion` 和一次 `rules:`。Git-only 旧格式可参考 `knowledge/schema/examples/rule-example.yaml.example`；含网页证据的旧格式使用 schema v2，并参考 `knowledge/schema/examples/web-rule-example.yaml.example`。不要直接将示例文件改名后放进加载目录。
3. 如果选择编辑已有文件，只把单条以 `- id:` 开头的 list item 追加到原有 `rules:` 列表下；不能再次写入 `schemaVersion` 或第二个 `rules` 根键。
4. 从仓库总结出的知识必须先写成 `status: candidate`，且不含 `approval`。
5. Git 证据填写精确仓库、commit、文件以及非空 symbol 或正整数 line；网页证据填写官方 HTTPS 地址、标题、发布者、核验日期和具体章节。
6. 保存后运行 `validate`。校验通过只表示格式与规则约束正确，不代表候选规则已经获批或生效。

完整的 team 候选规则示例：

```yaml
schemaVersion: 4
rules:
  - id: team-20827.example-candidate
    topic: example-topic
    title: Example team practice
    instruction: Describe one action the code should follow.
    rationale: Explain why team 20827 uses this practice.
    status: candidate
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
        file: TeamCode/src/main/java/example/Example.java
        symbol: Example
```

运行校验：

```bash
./gradlew :apps:knowledge-cli:run --args="validate knowledge" --quiet
```

合法的 `candidate` 会通过校验，但不会出现在 `resolve` 的 active 输出中。

## 团队协作流程

```text
队员识别可复用经验
→ 写入 candidate 并附 repository/commit/file/symbol-or-line 证据
→ 运行 validate
→ 软件负责人审查 instruction、rationale、适用范围和证据
→ 授权负责人添加 approval 并改为 approved
→ 再次 validate 和 resolve
→ 通过 Git review 合并
```

普通贡献者可以提出候选规则，但不能自行声明并不具备的审批角色。审批者应确认规则内容、证据、适用范围和自身权限，而不是只检查 YAML 能否通过解析。

team→global 需要新的总软件负责人审批，并在迁移台账保留旧审批和新范围；不可把 candidate 顺便转正，不可无证据移除赛季限制。来源 authority 与 policyLevel 分离，详细步骤见 [审批规范](approval.md)。校验、编译和静态检查不代表 Robot Controller / Driver Station / 真机安全验证。

新规则建议使用 YAML v4：显式声明 policyLevel、teams、seasons、profiles；v1-v3 仅为兼容旧文件。v4 同样支持 checks 和可选 reviewTriggers；详细字段见[字段参考](https://ftckb.lucasxl.com/reference/rule-schema/)。
