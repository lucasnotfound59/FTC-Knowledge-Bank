# 候选规范自动提取与审批流（设计）

目标：把队伍代码仓库里的既有模式自动变成**待审批的候选规则**（status=candidate、无 approval、带 Git 证据），
由总软件负责人/队伍软件负责人审批后生效。这是知识库自动增长闭环：`提取 → 审批 → 裁决 → 被外部 Agent 消费`。

## 1. 命令

```bash
# 从仓库提取候选规则，写入 YAML 文件（不直接入库，先给负责人看）
ftckb extract --repo PATH --team 20827 [--season YYYY-YYYY] --provider deepseek \
             [--config PATH] [--knowledge PATH] [--output PATH] [--max-candidates 8]

# 列出候选规则（审批前审查）
ftckb candidates <knowledge-root> [--json]

# 审批：candidate → approved（ApprovalPolicy 强校验）
ftckb approve <knowledge-root> --id team-20827.some-topic --approver NAME \
             --role team_software_lead|overall_software_lead [--team 20827]

# 驳回：candidate → rejected
ftckb reject <knowledge-root> --id team-20827.some-topic --approver NAME \
             --role team_software_lead|overall_software_lead [--team 20827]
```

`extract --output` 缺省为 `knowledge/teams/<team>/extracted-<yyyyMMdd-HHmmss>.yaml`（与知识根目录同构，
校验通过后可直接入库）；给 `--knowledge` 则用它做话题去重与 id 防撞。

## 2. 提取流水线（模型提议 + 主机强校验）

1. **仓库画像**：`RepositoryIndex.build(repo)` → supported 检查、sourceModules/markers/documents、文件清单（相对路径+行数，有界）。
2. **模型提议**：把画像 + 有界代码切片发给 provider，要求返回 JSON 候选数组，每项：
   `topic/title/instruction/rationale/confidence(high|medium|low)/evidence:[{file,symbol?,line?}]`。
   提示词明确**禁止提议**：一次性修补、被注释的代码、遗留 SDK 工件、依赖版本数字。
3. **主机强校验（确定性，不信任模型）**：
   - evidence.file 必须命中索引内相对路径（防越界/绝对路径）；symbol 必须真实出现在该文件；line 必须存在且非空；
   - commit 统一用仓库当前 HEAD SHA（jgit）；
   - topic 规范化：小写、空格/下划线→连字符，必须通过 `RuleIdentity.isCanonicalTopic`；
   - **去重**：与既有规则（任意状态）topic 相同 → 跳过并报告 “already covered by <id>”；
   - **id**：`team-<team>.<topic>`，与既有规则撞 id → 跳过；
   - **单点证据降级**：只有一条证据的候选 confidence 上限为 low，并在 YAML 注释标记 `# needs-stronger-evidence`；
   - 校验后证据为空的候选丢弃。
4. **写入**：schemaVersion 1（纯 Git 证据）、authority=team、applicability.teams=[<team>]、
   seasons 缺省 `[]`（全赛季生效；给 `--season` 则只适用该赛季）、status=candidate、无 approval。
   每条规则上方写 `# confidence: high|medium|low` 注释（YAML 注释合法、加载器忽略，不改 schema）。
   写完对输出文件跑一次 `RuleValidator` + 知识加载校验；不通过则整个文件不落盘。

## 3. 审批流（外科式 YAML 编辑 + 授权强校验）

- **定位**：扫描知识根目录，找到包含 `- id: <目标 id>` 的规则块（到下一个同级 `- id:` 为止）。
- **编辑**：`status: candidate` → `status: approved`（或 rejected），并插入 approval 块（approver/role/approvedAt=UTC now，team 规则带 team）。
- **授权**：`ApprovalPolicy.authorize` —— official/shared 规则只允许 `overall_software_lead`；team 规则只允许该队的 `team_software_lead`（`--team` 必须等于规则 applicable 队伍）。
- **原子性**：先写临时文件 → 加载+校验（0 violations）→ 再替换原文件；校验失败则原文件不动。
- **拒绝情形**：规则不存在 / 非 candidate / 角色不匹配 / 校验失败 —— 全部退出码 2，消息可机器读。

## 4. 安全与边界

- extract 只读仓库 + 只写 `--output` 指定文件；不碰仓库内文件；
- 模型输出全部按不可信数据处理（evidence 逐项主机校验后才采用）；
- 提取内容不包含凭据路径（复用 SafeEditPath/索引的安全相对路径逻辑）；
- 提取结果里的 instruction/rationale 是模型文本，责任人审批即代表知情确认。

## 5. 负例测试（对应路线图开放项）

离线脚本化 provider 固定以下场景，断言 extract 不会把它们提成规则：

- 遗留代码（已被新类取代的旧实现）；
- 一次性修补（特定 commit 的临时 fix）；
- 重复依赖（同一库两个版本同时出现）；
- 注释掉的代码。

测试落在 apps/knowledge-cli 的离线验收（无需网络），与现有 391 项并行。

## 6. 不在本期范围

- 规则审批历史/回滚 UI（长期项）；
- 跨仓库批量提取；
- 自动把 candidate 直接转 approved（必须人工审批）；
- schema 增加 confidence 字段（先走 YAML 注释，避免破坏契约）。
