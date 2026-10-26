# FTCLib 启发的 Command 架构专区设计

日期：2026-10-23。基线提交：`5b652e8111769bfe259234d8f99e14f62aa549db`。

## 目标与授权边界

用户要求持续改进 FTC Knowledge Bank，第一优先是适配“参考 FTCLib Command-Based 职责分离、由队伍自有实现、没有 FTCLib 运行依赖”的专属规则区，并改善教程。交付应当让使用者知道选哪个 profile、Agent 到哪里取证、哪些规则实际生效，以及如何验证自己的实现。

本设计不修改 BioBuzz，不自动改变已接入项目 pin，不假定编译等于部署或真机通过。BioBuzz 的当前未提交改动仅用于发现需求，不作为公开、固定 Git 证据。用户已授权设计与可逆改进；新增正式规则的批准人和角色仍须按现有 ApprovalPolicy 明确声明。

## 当前证据

主知识库 Dashboard 已由环境负责人确认连接到本仓库 `knowledge/`，实际规则 48 条；平台 QA 使用 20827／2025-2026／generic／normal，不能代替任何机器人的批准。CLI `validate` 返回 schemaVersion=2、ok=true、ruleCount=48、violations=[]。

本设计随后执行了真实 CLI resolve。下表两队 20827、20287 的结果相同，所有调用均 schemaVersion=2、退出 0、无冲突、无 stderr 审计诊断。

| 赛季 | generic | command-based | ftclib-command |
| --- | ---: | ---: | ---: |
| 2025-2026 | 26 | 29 | 30 |
| 2026-2027 | 17 | 17 | 17 |

2025-2026 的 command-based 增加 `global.command-responsibilities`、`global.command-live-input`、`global.command-requirements-cleanup`；ftclib-command 再增加 `shared.ftclib-command-candidate`。后一个 ID 含 candidate，但其实际 status 是 approved，不能从 ID 猜状态。2026-2027 中这些规则均因赛季范围排除，当前没有命令规则生效。simple-opmode／rookiebot 的 2026-2027 对照分别为 17／29 条，没有命令规则。

本地原始矩阵位于 `/private/tmp/ftckb-command-area-matrix-20261023/`；它是本次调查收据，不是长期安装输入。复现命令：

```bash
apps/knowledge-cli/build/install/ftckb/bin/ftckb resolve knowledge --team 20287 --season 2026-2027 --profile command-based --json
```

## 两种方案

| 方案 | 收益 | 代价与边界 |
| --- | --- | --- |
| A：沿用 command-based，新增专区指南与独立规则主题组（推荐） | 已有 Kotlin、Python、CLI、Dashboard 与集成流程都理解该 profile；自有实现无需额外选择；FTCLib 用户可以继承共同原则 | 自有 scheduler 专属建议必须有明确条件，不能误要求 FTCLib 使用者重写 vendor 运行时；旧赛季审批不会自动扩大 |
| B：新增 ftclib-inspired-command 并继承 command-based | 可在未来把“实现自有 scheduler”的规范单独启用 | 必须同步 profile 归一化、全部入口、Python 集成验证、模板和测试；旧 pin 会拒绝新名称；仍不解决现有三条规则的赛季范围问题 |

root 已接受 A 作为本批实施方案。只有将来出现一组不能以清晰条件表达、且与普通 command-based 互斥的实际约束时，再讨论 B。本批不增加新 profile，不改变 profile 推导，不从依赖猜架构。

## 用户与 Agent 入口

新增 `knowledge/guides/architecture/command-based.md`，标题同时包含 “Command-Based”“FTCLib 启发”“自有实现”，使内置 guide 检索能按标题找到它。指南开头给出选择表：

| 用户已决定采用的代码组织 | 明确选择 |
| --- | --- |
| 自有 Command／Subsystem／scheduler，包括参考 FTCLib 的轻量框架 | command-based |
| 明确使用 FTCLib 的 command 运行时及其 API | ftclib-command（内核会包含 command-based） |
| 普通直接编写的 OpMode | simple-opmode；采用现有 RookieBot 教程时为 rookiebot |
| 尚未采用命名架构约束 | 显式 generic；它不是自动探测 |

指南提供一次可追踪的调度过程：INIT 装配、START 后周期执行、default 让出资源、占用命令结束、default 恢复、STOP／异常清理。使用普通 Java 伪代码展示职责，而不发布一个新的完整运行库。概念接口名是示例，必须映射项目现有接口，不能因此强加包名、singleton、CommandOpMode 基类或 DSL。

指南列出人工可复核的最小行为：连续输入改变时下一周期使用新值；自有 scheduler 的 default 在全部 requirements 空闲时恢复；相互冲突的命令按明确政策处理；中断与异常仍尝试全部已拥有机构的安全输出；一个 cleanup 抛错不阻止独立 cleanup；停止后的旧实例不会复活。FTCLib v2.1.1 的多资源 default 没有前述全资源空闲保证，使用者应优先让 default 只占注册资源，或在应用层明确协调，不能默认要求重写 vendor scheduler。驱动示例解释归一化之后再乘输出限制，否则限幅可能被归一化抵消。这是教学内容，不新增独立底盘硬规则。

发现路径应同时覆盖：README／新手页导航、旧 FTCLib 安装教程的架构选择链接、生成的项目运行时 Skill。在未来选定的新 commit 中，Skill 仅在用户已选择 command-based／ftclib-command 且任务涉及命令结构时指向该指南；规则生效结论仍取自 resolve。现有旧 pin 不会自动获得本次指南，升级必须继续使用显式固定版本流程。

## 候选规则包

新增一个专属文件：`knowledge/shared/command-framework.yaml`。拟定四条，使用 schemaVersion=4、authority=shared、policyLevel=shared、teams=[]、seasons=[]、profiles=[command-based]、status=candidate、无 approval；空赛季范围是本次待审提案中的明确选择，不是沿用旧批准。

| 拟定 ID／topic | 指令的实际要求 |
| --- | --- |
| shared.command-runtime-boundary／command-runtime-boundary | 在已选择命令架构的任务中先确认运行时归属并复用项目接口；参考 FTCLib 不要求安装 FTCLib 或照搬包名、singleton、基类、DSL。采用 vendor 运行时则按其明确版本使用 API，不能混用两套 scheduler。 |
| shared.command-scheduler-lifecycle／command-scheduler-lifecycle | 对已采用调度的 OpMode 明确唯一 owner、装配和周期调用时机；结束、中断和异常路径都尝试取消命令与安全输出；独立 cleanup 逐项尝试，保留原故障。该建议不要求换基类。 |
| shared.command-default-resumption／command-default-resumption | 使用 default command 时声明其全部资源；自有 scheduler 仅在全部资源空闲时恢复 default；FTCLib 等 vendor 使用者核对固定版本语义，优先令 default 只占注册资源或在应用层协调多资源，不能假定库已有全资源门控或要求重写 vendor；连续操控读取实时输入，结束或中断恢复安全输出。 |
| shared.command-scheduler-reentrancy／command-scheduler-reentrancy | 仅在实现或修改自有 scheduler 时，明确 callback 中 schedule/cancel 的时序，避免遍历集合被修改；初始化失败释放其资源，结束不重复调用；用事件轨迹验证重入、失败与清理行为。 |

四个 topic 不与旧三条同名；不修改旧条目的 status、approval、赛季或 evidence。不能通过另换 topic 或降低层级绕过已有高优先级要求。未来若扩展旧三条的范围，必须单独呈现范围 diff 并获得新审批。

这些要求是共享工程约定及对第一方实现的有限解释，不是 FIRST 官方规则。vendor 源码仅支持其中实际展示的语义；异常安全、全量 cleanup 等更强要求应在 rationale 中明确标成拟议工程政策，不声称 FTCLib 已提供保证。独立复核用固定 v2.1.1 源码及最小接口 stub 实测确认：多资源 default 可能中断其他 requirement 上的可中断命令；reset 仅令 singleton 引用为 null，不调用 cancel/end，旧 scheduler 引用仍可保留状态；cancelAll 遍历集合时调用修改该集合的 cancel，多命令场景可能抛 ConcurrentModificationException 并提前结束清理。适配层应逐项核对取消及机构安全输出，不能把一次 reset/cancelAll 当全量清理证明；不因此默认修改 vendor 源码。复现位于本机 `/private/tmp/ftckb-review-vendor-default/`，不提交为公开运行时依赖。

## 硬检查与软复核

本批不增加硬 regex 检查。当前规范器没有 Java 类型解析／控制流／别名分析，不能靠一个 `finally`、`DoubleSupplier` 或 `getRequirements` 词证明语义正确，也不能因自有类名不同就判违规。

四条候选均提供限定 TeamCode Java 路径的 reviewTriggers，并由相关语句或 scheduler 路径请求软复核。trigger 未命中不能证明安全。作者需展示新增、无关修改、删除与重命名的实际触发边界；允许有界误报，不允许把 soft 写成机器证明的违规。待真实批准后，它们才可能出现在真实 check 的 soft 中；candidate 阶段必须准确报告 excluded status。

本批没有强制“所有代码都采用命令架构”的新要求。尤其 test 模式仍只豁免契约中现有两个架构条目；新安全条目不得重新迫使测试 OpMode 使用某个基类。实现验证可使用隔离的合成批准对象，不得给实际候选伪造批准。

## 第一方来源与证据失效

已通过 `git ls-remote` 核对 FTCLib 官方仓库 tag `v2.1.1` 指向完整提交 `98e921bf9eac506c2bbe80a0cddf51ae33f1cbb6`。证据是指定版本的源代码快照，不是对当前 FTC SDK 的兼容承诺：

- [FTCLib CommandScheduler 固定源码](https://github.com/FTCLib/FTCLib/blob/98e921bf9eac506c2bbe80a0cddf51ae33f1cbb6/core/src/main/java/com/arcrobotics/ftclib/command/CommandScheduler.java)：`run`、`schedule`、`setDefaultCommand`、`reset`、`cancelAll`，用于说明固定版本的实际调度、default 抢占和清理边界。
- [FTCLib CommandOpMode 固定源码](https://github.com/FTCLib/FTCLib/blob/98e921bf9eac506c2bbe80a0cddf51ae33f1cbb6/core/src/main/java/com/arcrobotics/ftclib/command/CommandOpMode.java)：`runOpMode`，用于说明生命周期示例；其实现没有 finally，不能据此证明异常路径清理。
- [FTCLib 仓库](https://github.com/FTCLib/FTCLib)：项目自己说明其来源及安装信息；它是库维护方来源，不能把 authority 标成 official。

2026-10-23 的实时网页核验中，旧 `docs.ftclib.org/ftclib/command-base/command-system/robot-and-commandopmode` 与 `command-scheduler` 链接跳转到无关博彩页面，目录页显示目录索引。旧指南作者应标记这些链接当次不可核验并改用固定第一方来源；不得据此编造项目维护状态或泛化所有 FTCLib 网站。GitBook 替代链接本次未能读取，不能标成已核验。

## 审批、版本与验收

ApprovalPolicy 对 shared 规则要求显式 `overall_software_lead`。最终批准包必须向用户呈现四个 ID、完整正文、teams=[]、seasons=[]、profiles=[command-based]、soft 范围与第一方证据，再由用户明确批准人和角色；不能从旧 YAML 的用户名或长期优化目标伪造本次身份。可在同一次明确声明中批量批准这些具体条目。批准前继续完成指南、发现路径、候选校验和工具改进，不把候选暂未启用称为目标完成。

本批方案保持 YAML v4、kernel JSON v2、CLI 2.1.0、项目接入协议 v2；没有 schema 或协议破坏。内容发布由维护者决定仓库版本，不能编造新 tag。若只新增四条候选，validate 预期从 48 变为 52、批准数仍 42；实际交付数字必须按最终内容重算。既有 kernel fixtures 的真实知识计数随新增候选更新。

验收包括：完整知识 validate；两队两赛季三 profile 的 resolve 与简单架构对照；候选不影响真实 active IDs；隔离批准后的 profile 继承、soft 触发、无关补丁不触发、test 安全语义；指南检索；所有链接及固定源码人工核验。末次变更后重跑相关模块与组合检查，不为文档变更重复全仓回归。真实部署、Driver Station 和真机尚未验证。
