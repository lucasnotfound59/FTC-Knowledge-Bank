# 候选规则完整审阅导出设计

日期：2026-10-23。状态：供本轮实现与独立复核使用；未实施产品代码、未批准任何真实候选。

实施计划：[Candidate Review Export Implementation Plan](../plans/2026-10-23-candidate-review-export.md)。

## 目标与已核对的缺口

让 Agent 用一个只读 CLI 命令取得可交给负责人审阅的完整候选字段，明确 candidate 状态、声明范围与来源快照，减少手工从 YAML 拼审批卡片时漏掉正文、赛季或 profile 的风险。

本次实际核验的 Dashboard 对应本仓库 `knowledge/`：页面、summary、rules 均返回 200，52 条规则、42 approved、10 candidate；复用主任务已有实例。没有公开其凭据或执行写操作。实际执行的 `candidates --help` 与 `candidates knowledge --json` 证实当前只有简表输出。

源码依据：

- `apps/knowledge-cli/src/main/kotlin/org/ftckb/cli/ApprovalCommand.kt`：`runCandidates` 的 JSON 为独立 `schemaVersion:1`，每项仅 id/topic/title/authority；失败仍是文本。旧解析器接受重复 `--json`。
- `apps/knowledge-cli/src/main/kotlin/org/ftckb/cli/DashboardServer.kt`：已有 `/rules`、`/rule` 详情与 preview/apply；不需要新增网页、HTTP API 或审批流程。
- `apps/knowledge-cli/src/main/kotlin/org/ftckb/cli/DashboardJson.kt`：`rule` 已覆盖全部 `KnowledgeRule` 语义字段及 checkMode。
- `modules/knowledge/src/main/kotlin/org/ftckb/knowledge/KnowledgeWorkspace.kt`：`snapshot()` 从一次文件字节捕获产生规则、校验、version 与 sources。合法的旧版或非 block YAML 可能没有安全提取的单条 source；不应再次读文件伪造同一快照的来源。
- `modules/domain/src/main/kotlin/org/ftckb/domain/ApprovalPolicy.kt`：official/shared 由 overall_software_lead 声明审批；team 规则只允许其单一匹配队伍的 team_software_lead。导出不改变此策略。
- 本轮临时四条 command 候选审批卡需要手工复制完整 YAML，是实际使用缺口。真实候选仍为 candidate。

## 取舍

| 方案 | 收益与代价 | 决定 |
| --- | --- | --- |
| 在现有 candidates 增加显式 `--details` | 沿用发现入口；默认输出不变；无需 Dashboard token 才能导出 | 采用 |
| 直接扩充现有 `--json` | 少一个选项，但扩大既有简表、影响脚本和终端使用 | 不采用 |
| 新增 Dashboard 导出按钮或独立审批服务 | Dashboard 已有详情；扩大前后端与认证面 | 不采用 |

## 命令与兼容边界

```bash
ftckb candidates knowledge
ftckb candidates knowledge --json
ftckb candidates knowledge --details
ftckb candidates knowledge --details --id shared.command-scheduler-lifecycle --json
```

没有 `--details` 时，保持现有成功输出、错误文本、排序、退出码和重复 `--json` 行为。只修改帮助文本以发现新功能。不要顺便修旧解析器。

详细模式要求首个参数为知识根目录，随后 `--details` 恰好一次、`--json` 至多一次、`--id RULE_ID` 至多一次；选项顺序任意。拒绝未知选项、多余位置参数、重复选项、缺值、空值及不合法 ID，退出 64。`--id` 只在详细模式有效；不新增 team/season/profile/work-mode 过滤，不推断项目上下文。

保留 `candidates --help` 为人类帮助入口。详细模式中混入 `--help` 按 usage/64 处理，不绕过参数检查。

## 数据与渲染

详细 JSON 使用独立标识：`format:"ftckb-candidate-review"`、`formatVersion:1`、`command:"candidates"`、`ok`。不使用 `schemaVersion`，不修改 kernel JSON v2、Dashboard API v1 或 YAML v4。

成功对象具有以下确定字段：

| 字段 | 精确定义 |
| --- | --- |
| `readOnly` | 固定 true；没有审批或写入操作 |
| `contextEvaluated` | 固定 false；没有执行 resolve 或判断对某项目是否适用 |
| `knowledge.root` | snapshot 的规范化实际根路径 |
| `knowledge.version` | 原样使用同一 snapshot.version；不是授权凭据或未来写入保证 |
| `candidates` | 只含 status=candidate，按 rule.id 排序；可为空 |
| `candidates[].rule` | 复用现有 DashboardJson.rule 的全部字段，不经 KernelJson，不截断正文 |
| `candidates[].source` | 有 source 时为 `{path,fileDigest}`；否则为 null |

rule 字段为 id/topic/title/instruction/rationale/status/authority/policyLevel/applicability/evidence/approval/supersedes/positiveExample/negativeExample/checks/reviewTriggers/checkMode。数组与可空字段保持现有 DashboardJson.rule 表示；候选 approval 必为 null。applicability 的 teams/seasons/profiles 导出声明集合并排序，不做 profile 继承展开。evidence/checks 的声明顺序保留。

source.path 是相对知识根目录路径，fileDigest 是包含该规则的整个文件摘要。它们只取自该 snapshot.sources；无 source 仍可输出完整语义规则，必须保留 null，不猜路径、不二次读文件。旧 YAML 规范化出的 policyLevel 和空 profiles 是解码后的语义值；导出不声称保留 YAML 注释、排版、confidence 注释或全部原文。v1 不导出 ruleYaml，不引入另一个 YAML 序列化器。

`--details --json` 输出一行完整 JSON。`--details` 输出中文只读提示、knowledge root/version/候选数量及每条卡片；卡片使用同一 `candidates[]` 对象的缩进 JSON，包含完整语义字段与来源摘要。不要为人类卡片再做摘要、裁剪 evidence 或另算范围。卡片明示：

> 本次为候选审阅导出，未批准、未裁决项目适用性。空 teams/seasons 表示该维度不限制，空 profiles 表示无架构要求；非空 profiles 是全部要求。checkMode 只描述规则若获批并生效后的检查形式。source 为 null 表示无法从本次快照安全定位单条来源。真实审批需用户明确批准最终正文与范围，并提供姓名和角色声明。

不生成可直接执行的 approve 命令，不填充姓名、角色或队号；需要批准时沿用现有显式审批与 Dashboard preview/apply。导出后若规则变动，须重新导出、重审并做当前预览；旧卡片本身不能保证当前内容未变化。

## 错误、边界与只读性

- 先获取一次 `KnowledgeWorkspace(root).snapshot()`，对完整库执行已有加载/校验结果判断；任何 loadErrors/violations 均使导出失败，不能忽略别处坏规则或重复 ID。
- 使用 `--id` 时：不存在为 `not-found`/2；存在但非 candidate 为 `not-candidate`/2，不能静默返回空列表冒充所选候选。
- 详细模式带 `--json` 的所有失败输出同一 format/formatVersion/command/ok=false 及 `error:{code,message}`；不得先打印部分卡片或异常堆栈。无 `--json` 时输出一条相应失败说明。
- 错误 code 为 usage、load-error、invalid-knowledge、not-found、not-candidate、output-too-large；usage 退出 64，其余退出 2。invalid-knowledge 可附原 snapshot 中排序后的 violations/loadErrors，不带候选正文。
- 渲染完成后、写 stdout 前，检查最终 UTF-8 输出（含结尾换行）不超过 1 MiB；超出返回 output-too-large/2，建议用 `--id`。不能截断或先输出部分候选。单条也超限时继续失败，不假装完整。
- 这是 stdout 大小上限，不声称限制现有 snapshot 的输入大小、内存或时间。不新增文件读取器、大小策略或扫描路径。
- 详细模式复用 snapshot 的 symlink/路径拒绝行为；与旧简表的读取差异在帮助/文档说明。不改 snapshot、解码器或原规则文件。
- 不写知识、approval、锁文件、审计 events/reviews；不生成时间戳或随机 ID；不调用模型、网络、resolver 或 Standardizer。相同根路径与文件字节得到相同输出。

## 实现归属与验收

单一产品 owner：`ftckb_command_rules`。新增 CandidateReviewExport.kt 与 CandidateReviewExportTest.kt；小范围修改 ApprovalCommand.kt、Main.kt。docs/cli-agent.md、docs/candidate-extraction.md 的候选段交同 owner，但写前经 root 协调已有文档改动。不要修改 installer、KnowledgeWorkspace、Dashboard、KernelJson、ApprovalPolicy、知识规则或其他 owner 文件。

独立复核由本设计 reviewer 承担；所有 Gradle/安装构建由 doctor 串行协调。新增测试覆盖完整字段、旧行为逐字节兼容、legacy source=null、来源摘要与快照一致、合法空结果与错误 ID 区分、错误 JSON、只读性、UTF-8 输出上限、顺序与重复运行确定性。真实库演示仅导出和比较 10 条 candidate，不执行真实 approve/reject。测试通过不代表 RC/DS 或真机验证。

本设计自查：没有新审批权限、身份认证、审批 token、动态生效预测或网络服务；默认简表兼容；范围事实来自原字段；引用的产品代码未修改；真实规则数与状态未改变。
