# FTCLib 启发的 Command 专区实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use subagent-driven-development (recommended) or executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. 本次由 root manager 分配执行者；本计划作者不再委派。

**Goal:** 让自有轻量 Command 框架拥有可找到的指南、具体可审阅的专属规则及真实生效状态，避免被误导安装 FTCLib 或照搬其 API。

**Architecture:** 复用现有 command-based profile；ftclib-command 继续继承共同原则。新增知识指南与四个独立候选主题，随后由真实负责人审批。沿用现有确定性 resolve/check、guide 检索、项目 Skill 和审批工作流。

**Tech Stack:** Kotlin domain/knowledge/agent-runtime，YAML v4，kernel JSON v2，Python 项目接入，Markdown。

## Global Constraints

- 基线为 `5b652e8111769bfe259234d8f99e14f62aa549db`；实际工作前确认 HEAD 与脏文件，保留用户既有改动。
- 先复用并核验实际主知识库 Dashboard，再执行规则／代码流程；审计不得换知识分区冒充覆盖。
- 不新增 profile，不改变 CLI 2.1.0、YAML v4、kernel JSON v2、项目接入协议 v2。
- 不修改 BioBuzz，不把其未提交代码作固定证据，不自动修改已接入项目 pin。
- 新正式规则先 candidate／无 approval；既有 approved 的范围及实质内容不得偷偷改变。
- shared 规则的真实批准需要明确批准人及 overall_software_lead；测试用 fixture 角色只存在隔离测试中。
- 来源使用 FTCLib 固定 SHA `98e921bf9eac506c2bbe80a0cddf51ae33f1cbb6`；不把 vendor 当 FIRST official。
- 单元测试可以使用 Knowledge Bank 自身的 JUnit；目标机器人 TeamCode 示例不使用 JUnit／src/test／src/androidTest。
- 不自动 commit／push；交付含精确文件清单、实测收据、soft 项与未完成审批。

---

## 文件与责任分工

| 工作包 | Owner 职责 | 只写这些文件 |
| --- | --- | --- |
| A：命令专区 | 规则／教程作者 | 新建 `knowledge/guides/architecture/command-based.md`、`knowledge/shared/command-framework.yaml`；修正 `knowledge/guides/setup/ftclib.md`；新增 `modules/knowledge/src/test/kotlin/org/ftckb/knowledge/CommandBasedKnowledgeTest.kt`，必要的 standardizer 测试 |
| B：发现路径 | 接入／检索作者 | `.agents/skills/ftckb-integrate/assets/project-skill/SKILL.md`、必要的 `modules/agent-runtime/src/test/kotlin/org/ftckb/agent/KnowledgeRetrieverTest.kt`；只有有实测必要才改检索实现 |
| C：新手导航 | 已单独分配的新手作者 | `docs/getting-started.md`、`README.md`、`docs/project-integration.md`、`docs/dashboard.md` 导航；与 A 确认目标存在后再落链接，README／project-integration 计数与规则作者协调 |
| D：最终知识工件 | 规则作者持有文件，验收 owner 检查 | `fixtures/kernel/validate-ok.json` 及因真实知识输出变化而受影响的 kernel fixtures；AGENTS、kernel-contract 动态计数。不得并发编辑新手作者文件 |
| E：审批与持续进度 | root manager／真实审批人 | 展示审批包、安排真实批准；planner 维护自己的 spec／plan／report |

A、B、C 可并行准备；B、C 的最终导航核验依赖 A 的指南落地。D 在本批全部变更稳定后执行。不同 owner 不同时改 README 或既有 Skill。planner 独占本 spec／plan／progress，不修改 A—D 文件。

### Task 1：完成可独立使用的命令指南和旧 FTCLib 教程修正

**Files:** Create `knowledge/guides/architecture/command-based.md`; Modify `knowledge/guides/setup/ftclib.md`.

**Interfaces:** Consumes 现有 profile 与 resolve 输出。Produces 稳定入口 `knowledge/guides/architecture/command-based.md`，供新手页和运行时 Skill 链接。

- [ ] **Step 1：核对第一方证据。** 打开 spec 中完整 SHA 的 CommandScheduler／CommandOpMode，逐项核对 symbol。记录来源类型、完整 URL、v2.1.1、访问日期。取不到的文件标明未核验，不能填编造 symbol。
- [ ] **Step 2：写指南开头与职责示例。** 使用 spec 的 profile 选择表，明确自有实现不需要 FTCLib dependency，也不强制包名／singleton／DSL／CommandOpMode 基类。正文包含职责、一次完整调度轨迹及 INIT／START／STOP 边界。
- [ ] **Step 3：写最小行为验证表。** 覆盖实时输入、全部 requirements、default 让出与恢复、初始化失败、callback 重入、逐项 cleanup、输出限制在归一化之后。全资源空闲门控只写为自有 scheduler 政策；vendor 多资源 default 必须说明固定版本实际抢占行为，建议单注册资源 default 或应用层协调。给出可观察的事件顺序和失败例；纯逻辑检查不写成机器人已验证。
- [ ] **Step 4：修复旧教程的三项误导。** “统一采用 subsystem + CommandOpMode”改成明确 ftclib-command 场景；规则状态及生效范围取自实际 resolve；SmokeTest 放到 `TeamCode/src/main/java/org/firstinspires/ftc/teamcode/tests/` 且 package 一致。新增到命令指南的相对链接。
- [ ] **Step 5：修复来源可读性。** 对本次跳转异常的 docs.ftclib.org 链接不再声称当前核验成功；使用实际读取过的固定 GitHub 源码。明确 v2.1.1 reset 仅清 singleton、不调用 cancel/end；cancelAll 多命令可能因 ConcurrentModificationException 中断，不能作为全量安全 cleanup 保证。更强要求是本库拟议工程政策，在项目适配层补足，不默认改 vendor。保留版本边界，不顺带编造 SDK 12 兼容性或当前维护状态。
- [ ] **Step 6：进行可读性核验。** 从“我有一个自有 Command 框架”出发，沿指南找到选择 profile、resolve、映射已有接口和行为验证，确认没有把四条待审规则称为 active。检查本地相对链接存在且 fragment 对应实际标题。

这一工作包单独可交付；规则审批尚未到达不妨碍指南使用。

### Task 2：建立四条具体候选与受影响知识测试

**Files:** Create `knowledge/shared/command-framework.yaml`; Create `modules/knowledge/src/test/kotlin/org/ftckb/knowledge/CommandBasedKnowledgeTest.kt`.

**Interfaces:** Consumes Task 1 第一方证据和 spec 四条 ID／topic。Produces 四条可校验、可审批的 v4 候选；不改变现有批准状态。

- [ ] **Step 1：写四条完整候选。** ID、topic、中文 instruction、rationale、适用 teams=[]／seasons=[]／profiles=[command-based]、固定 evidence、无 checks、限定 TeamCode 的 reviewTriggers 按 spec 落地。新目录只负责这组规则，不搬动旧全局规则。
- [ ] **Step 2：验证实际候选加载与排除。** 在测试中加载真实知识目录，检查四个唯一 ID、v4 兼容、ApprovalPolicy 校验无误；实际 resolve 的 active 不包含这些未批条目，excluded reasons 包含 status。测试不把“永远 candidate”作为产品要求。
- [ ] **Step 3：验证审批后的范围，使用隔离对象。** 对加载出的四条复制对象，测试内显式 `copy(status=APPROVED,approval=Approval("fixture-reviewer",OVERALL_SOFTWARE_LEAD,approvedAt=固定测试时间))`；不写回真实 YAML。调用 RuleResolver 验证 command-based／ftclib-command 继承，而 generic／simple-opmode 不包含四条，且两个赛季均按提议范围生效。
- [ ] **Step 3a：核对继承后的运行时条件。** 对照最终候选与指南，确保“全部 requirements 空闲才恢复 default”仅约束自有 scheduler；vendor 的适配建议不要求使用者重写 scheduler。以独立复核固定源码的三个反例核对：多资源 default 抢占、reset 不调用 end、多命令 cancelAll 可能提前失败。不得把这些有限行为写成通用 FTCLib 版本结论。
- [ ] **Step 4：验证软触发边界。** 在隔离知识／Git fixture 中检查命令相关 Java 新增、无关 README、无关普通 Java、删除、重命名；断言没有新增 hard violations。逐条记录命中的 soft 与未命中的范围；不能依一个关键词断言语义安全。
- [ ] **Step 5：核对 test 工作模式契约。** 新候选只对已存在／正在实现的命令机制提出条件式安全要求，不强制测试 OpMode 选某基类。隔离测试中保留旧两个架构豁免 ID，不把新候选静默加进名单。若作者判断必须新增 architecture mandate，停止该扩展并交 root 重审设计。
- [ ] **Step 6：执行受影响检查。** `./gradlew :modules:knowledge:test --tests org.ftckb.knowledge.CommandBasedKnowledgeTest`；如 soft fixture 放在 standardizer 模块则运行对应测试类。随后运行 `apps/knowledge-cli/build/install/ftckb/bin/ftckb validate knowledge --json`，预期 schemaVersion=2、ok=true、violations=[]；仅四条新增时 ruleCount=52。

所有公开 “生效” 结论以真实 CLI 输出为准；测试合成批准只证明引擎行为。

### Task 3：让外部 Agent 和内置检索找到指南

**Files:** Modify `.agents/skills/ftckb-integrate/assets/project-skill/SKILL.md`; Test `modules/agent-runtime/src/test/kotlin/org/ftckb/agent/KnowledgeRetrieverTest.kt` only when needed.

**Interfaces:** Consumes Task 1 的真实指南路径。Produces 新 pin 安装时可以发现的指南导航；保持配置三个 v2 字段与 profile 集合不变。

- [ ] **Step 1：添加少量运行时指引。** 在 profile 说明后写明：当前明确选择 command-based／ftclib-command 且任务涉及命令结构时，读取固定知识库内 `knowledge/guides/architecture/command-based.md`；自有实现使用 command-based；候选及非当前赛季规则不算生效。不能依据 dependency 自动切换 profile。
- [ ] **Step 2：核对固定版本边界。** 新模板只随未来明确选定的新 commit 生成；本批不触碰任何机器人项目托管文件和 pin，不改 integrationVersion，不绕过 managedFiles hash 校验。
- [ ] **Step 3：按现有检索接口验证标题可发现。** 使用 `RetrievalIntent` 的 guideTopics／concepts 对 “Command-Based”“FTCLib”“自有实现” 的代表查询，确认至少一个返回 section.path 是 `guides/architecture/command-based.md`（以实际 GuideEvidence 返回值为准）。沿用现有 maxSections、读取预算和符号链接限制，不为这份指南扩预算。
- [ ] **Step 4：执行适用测试。** 若修改 Kotlin 测试，运行 `./gradlew :modules:agent-runtime:test --tests org.ftckb.agent.KnowledgeRetrieverTest`；对模板变化运行现有 `tests/integration/test_integration.py` 中 managed template／安装相关用例。若该脚本仅支持全文件，运行其原有标准入口并报告实际数量。

### Task 4：集成矩阵、工件和审批包

**Files:** Modify 已受实际输出影响的 fixtures／动态文档计数；Report 由验收 owner 写自己的验证收据，避免覆写 planner 文件。

**Interfaces:** Consumes A／B／C 的最终文件及相关测试收据。Produces 可 review diff、准确计数、CLI 核验、具体审批包和剩余风险。

- [ ] **Step 1：重算知识状态。** 以实际 load／validate 输出统计 approved／candidate；更新 AGENTS、README、kernel-contract、project-integration 等当前快照，不机械替换历史报告数字。
- [ ] **Step 2：更新真实 kernel fixture。** 用实际构建 CLI 重新生成受影响 fixture。仅加候选时，validate-ok 的 ruleCount 变化；resolve-ok／resolve-test 的 excludedRules 也变化，不能漏掉。不要手写成功 JSON 冒充真实输出。
- [ ] **Step 3：重跑最终 resolve 矩阵。** team={20827,20287} × season={2025-2026,2026-2027} × profile={generic,command-based,ftclib-command}，再跑 simple-opmode／rookiebot 对照。未批准候选下，active ID 集应等于设计基线，新增候选应准确进入 excludedRules。每份检查 schema、退出码、ok、conflicts、排序和审计诊断。
- [ ] **Step 4：对本次 allowlist diff 执行 normal check。** 文档与知识维护 context 明确记为平台 QA；不把它当机器人实际批准。报告实际 soft 的每一个 ID 和理由，不因 exit 0 省略。
- [ ] **Step 5：运行最终相关组合。** 受影响 knowledge／agent-runtime／CLI kernel acceptance，以及模板相关 Python integration 用例；最后源码变化如需重建，由环境 owner 串行构建，避免多人争用 Gradle。相关 suite 全部通过后停止重复测试。
- [ ] **Step 6：展示具体审批包。** 四个候选逐项列出正文、范围、证据、soft 边界，并说明准备批准后真实 cb／ftclib 两 profile 将继承。向用户收集或使用本轮明确给出的批准人和 overall_software_lead 声明；不能复制旧 YAML 作为本轮授权。
- [ ] **Step 7：有真实批准则完成激活验收。** 按现有 Dashboard 预览／保存或 approve 命令处理同一 candidate 条目；重新 validate 和矩阵、check，更新实际状态数字。若尚无真实批准，清楚记录待审项并继续独立工具／教程 backlog，不能宣布“规则区正式启用”。

实际审批卡片由规则作者直接从最终 YAML／Dashboard 展示，包含 ID、topic、title、instruction、rationale、authority、policyLevel、teams／seasons／profiles、evidence 及 reviewTriggers。角色固定要求 `overall_software_lead`；`FTCKB_APPROVER_ID` 只可来自本轮人类明确声明，不从旧 approval 推断。用户对这四条最终正文及全赛季范围明确批准后，可逐条执行：

```bash
apps/knowledge-cli/build/install/ftckb/bin/ftckb approve knowledge --id shared.command-runtime-boundary --approver "$FTCKB_APPROVER_ID" --role overall_software_lead
apps/knowledge-cli/build/install/ftckb/bin/ftckb approve knowledge --id shared.command-scheduler-lifecycle --approver "$FTCKB_APPROVER_ID" --role overall_software_lead
apps/knowledge-cli/build/install/ftckb/bin/ftckb approve knowledge --id shared.command-default-resumption --approver "$FTCKB_APPROVER_ID" --role overall_software_lead
apps/knowledge-cli/build/install/ftckb/bin/ftckb approve knowledge --id shared.command-scheduler-reentrancy --approver "$FTCKB_APPROVER_ID" --role overall_software_lead
```

每条预期退出 0；发生错误时停止剩余批准并查看实际 YAML／错误，不把部分成功宣称为四条全部批准。批准后 `validate` 与真实矩阵是独立必做步骤。

## 交付与自审

- [ ] Spec 每个要求有对应 Task；搜索 plan/spec 中占位、互相矛盾的 profile、虚假的批准身份与旧赛季泛化。
- [ ] 检查 `git diff --check` 和本次明确文件 allowlist，保留用户 pre-existing untracked 文件。
- [ ] 交付指南路径、具体候选／批准状态、真实测试数量、矩阵结果、soft，以及真机未验证边界。
- [ ] 不自动 commit／push，不把 `/private/tmp` 收据、Dashboard token 或 BioBuzz 改动纳入本仓库 diff。
