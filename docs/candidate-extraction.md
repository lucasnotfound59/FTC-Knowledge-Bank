# 候选规范自动提取与审批流（设计）

目标：把队伍代码仓库里的既有模式自动变成**待审批的候选规则**（status=candidate、无 approval、带 Git 证据），
由总软件负责人/队伍软件负责人审批后生效。这是知识库自动增长闭环：`提取 → 审批 → 裁决 → 被外部 Agent 消费`。

## 1. 命令

```bash
# 从仓库提取候选规则，写入 YAML 文件（不直接入库，先给负责人看）
ftckb extract --repo PATH --team 20827 [--season YYYY-YYYY] --provider deepseek \
             [--config PATH] [--knowledge PATH] [--output PATH] [--max-candidates 8]

# 列出候选规则简表（旧输出保持不变）
ftckb candidates <knowledge-root> [--json]

# 完整只读审阅导出，不裁决项目适用性或批准
ftckb candidates <knowledge-root> --details
ftckb candidates <knowledge-root> --details --id global.hardware-access-candidate --json

# 审批：candidate → approved（ApprovalPolicy 强校验）
ftckb approve <knowledge-root> --id team-20827.some-topic --approver NAME \
             --role team_software_lead|overall_software_lead [--team 20827]

# 驳回：candidate → rejected
ftckb reject <knowledge-root> --id team-20827.some-topic --approver NAME \
             --role team_software_lead|overall_software_lead [--team 20827]
```

`extract --output` 缺省为 `knowledge/teams/<team>/extracted-<yyyyMMdd-HHmmss>.yaml`（与知识根目录同构，
校验通过后可直接入库）；给 `--knowledge` 则用它做话题去重与 id 防撞。

### 完整候选审阅导出

AI 规则工作使用确定性 CLI，不依赖 Dashboard；人需要查看规则或已有审计时再按[新手入口](getting-started.md)打开。`--details` 为现有 candidates 的独立只读入口：不写规则、approval、锁文件或审计 events/reviews，不调用模型、网络、resolver 或 Standardizer；不验证证据真伪，也不决定某项目的适用性。

首个参数是知识根目录；之后 `--details` 恰好一次，`--id RULE_ID` 和 `--json` 各至多一次，顺序任意。`--id` 只在详细模式可用。重复／未知选项、多余位置参数、缺值、空值、非法 ID 或详细模式中混入 `--help` 均为 usage／64。查看帮助用 `ftckb candidates --help`。

详细 JSON 使用 `format:"ftckb-candidate-review"`、`formatVersion:1`、`command:"candidates"` 和 `ok`，**没有 schemaVersion**，不改变 kernel JSON v2、Dashboard API 或 YAML v4。成功输出包含 `readOnly:true`、`contextEvaluated:false`、`knowledge:{root,version}` 和按 rule.id 排序的 `candidates`。每张卡片包含：

- `rule`：id、topic、title、instruction、rationale、status、authority、policyLevel、applicability、evidence、approval、supersedes、positiveExample、negativeExample、checks、reviewTriggers、checkMode；正文不裁剪，null 与空数组保留。
- `source`：同一次快照可安全提取来源时为 `{path,fileDigest}`，否则为 null。path 相对知识根目录，fileDigest 是整份来源文件摘要；knowledge.version 是同一字节快照的版本，二者都不是审批授权或未来写入保证。

声明范围照原值导出并排序：空 teams/seasons 表示该维度不限制，空 profiles 表示无架构要求，非空 profiles 为全部要求。不会将 ftclib-command 自动展开为 command-based，也不据此宣称 candidate 已启用。checkMode 仅描述规则若获批并生效后的检查形式。

旧版或非 block YAML 即便可合法解码，也可能没有安全提取的单条 source；保留 null 并继续提供完整语义，不猜路径、不二次读取文件拼摘要。旧 YAML 的 policyLevel 与空 profiles 是解码后的语义值。v1 不导出 ruleYaml、confidence 注释、YAML 排版或其他原文。详细模式继承 KnowledgeWorkspace 的 symbolic link／路径拒绝行为；默认简表沿用旧加载行为。

无 `--json` 时先显示中文只读与范围提示、知识根路径、version 和数量，再逐条显示相同卡片对象的缩进 JSON。`--details --json` 成功与失败均为单行 JSON；默认 `candidates --json` 简表仍为旧 schemaVersion:1，错误仍可能是文本。

| 详细模式结果 | 退出码／error.code |
| --- | --- |
| 合法库没有候选 | 0，candidates=[] |
| ID 不存在／不是 candidate | 2，not-found／not-candidate |
| 根目录无法读取／路径拒绝 | 2，load-error |
| 完整库有坏 YAML、重复 ID 或无效规则 | 2，invalid-knowledge；不输出部分卡片 |
| 最终 UTF-8 stdout（含换行）超过 1 MiB | 2，output-too-large；用 --id 缩小范围，单条过大仍失败 |

输出上限在写 stdout 前检查，不截断卡片；它不限制现有 snapshot 的输入大小、内存或运行时间。导出不会生成时间戳、随机 ID、审批命令、姓名或角色。同根路径与文件字节重复导出得到相同结果。

真实批准仍须负责人明确批准最终正文及范围，并提供姓名和角色声明，使用现有显式审批命令或 Dashboard preview/apply。若导出后文件变化，须重新导出、重审并做当前预览；旧卡片不能保证当前内容未变。

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
