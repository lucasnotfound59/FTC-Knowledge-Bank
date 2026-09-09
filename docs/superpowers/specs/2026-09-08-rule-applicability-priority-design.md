# 规则适用范围与全局优先级设计

日期：2026-09-08。状态更新于 2026-09-09：用户已确认详细设计，实施计划已编写；第一项 domain 模型与校验已实现，完整 v2 闭环与正式规则迁移尚未完成。

## 1. 已确认的目标

- 队员提交规则时，必须明确表达仅指定赛季适用的限制；跨赛季复用不能靠 Agent 猜测。
- 裁决顺序为：官方规则 > 明确标记的全局规范 > 队伍／项目本地规范。普通共享经验不自动成为全局规范。
- 其余设计围绕真实接入报告的问题展开：Limelight 误报、架构要求冲突、无关 soft 提示、首次安装缺少进度。
- 保持确定性 `resolve → 修改 → check`，不新增模型调用、API key 或联网检索 Agent。
- 直接使用用户当前分支，保护已有改动；不擅自修改机器人项目、不自动推送。

本轮分成两个交付切片：先完成规则模型、裁决和检测闭环，再独立改善安装进度与超时。第二个切片不改变规则语义。

## 2. 当前依据与问题边界

代码基线为 `62307d876335b4e337e6e36e628e727e405c691f`。编写设计时，当前分支为 `codex/ftckb-docs-site`，存在其他网站／README 改动；本设计不包含这些改动。

已读取外部项目 `FTC2026-RookieBot/docs/ftckb-integration-test-2026-09-08.md`。报告的机器人基线为 `558141588e2a0eb766e195ab71df3c188e942891`，team=20827，season=2025-2026。报告显示接入成功、软件测试和构建通过，但 Limelight 硬检查使最终合规检查失败；这些机器人测试结果是报告证据，本轮未重新执行。

本轮直接运行当前 CLI 的 `resolve knowledge --team 20827 --season 2025-2026 --json`，得到 schemaVersion=1、37 条 activeRules、conflicts=[]。这不证明自然语言规则没有矛盾。

源码确认的限制：

- `RuleResolver` 仅按 approved、team、season 筛选，然后按同 topic 的 OFFICIAL > TEAM > SHARED 选择；没有项目架构条件。
- `seasons: []` 表示不限赛季；现有 YAML 还允许遗漏 seasons，因此可能把遗漏误当成不限赛季。
- 12 条 RookieBot 规则的项目限制写在 instruction 中，机器适用条件仍然为空。
- FTCLib Command 与 RookieBot 简单 OpMode 要求采用不同 topic，现有冲突检测不能发现它们的语义冲突。
- Limelight 两条 regex-required 对所有 Java 新增行生效，跨文件搜索任意一个关键字；既会误报，也不能证明每次视觉读取受到保护。
- `check` 对无 checks 的规则一律返回 soft，未区分本次变更是否相关。
- Python 安装器捕获 Git 输出，网络命令没有显式超时；CLI 构建已经能显示 Gradle 输出。

## 3. 方案选择

| 方案 | 收益与代价 | 决定 |
| --- | --- | --- |
| 增加明确层级、项目条件和人工复核触发器 | 保留已有模型与审批来源，解决当前报告暴露的问题；需一次版本化升级 | 采用 |
| 直接改成 OFFICIAL > SHARED > TEAM | 改动少，但会把共享教程整体提升为全局规范，仍解决不了适用性和跨 topic 冲突 | 不采用 |
| 引入完整 Java 语义分析或模型裁判 | 可以探索更精细的检查，但成本、适用边界和验证需求远超本轮 | 后续独立研究，不作为本轮门禁 |

## 4. 区分来源、层级、适用范围、检测结果

这四个维度不能混为一谈：

- `authority`：保留 official/shared/team，表示规则来源与审批职责。
- 新增 `policyLevel`：global/local/shared，表示发生同主题冲突时的裁决层级。
- `applicability`：判断这条规则是否适用于当前队伍、赛季和项目 profile。
- `checks` 与人工复核结果：表达可确定性检测的硬违规或需要人工判断的事项。全局规则不因此自动具备语义检测能力。

有效优先级固定如下，不允许用户任意填写数值权重：

| 条件 | 有效优先级 |
| --- | --- |
| authority=official | 4，官方 |
| 非官方且 policyLevel=global | 3，全局 |
| 非官方且 policyLevel=local | 2，本地 |
| 非官方且 policyLevel=shared | 1，普通共享 |

同一 topic 的最高层级只有一条时生效；最高层级有多条时报告冲突，不能用文件顺序、规则 ID、时间或模型判断偷偷选一条。不同 topic 的规则继续并存。优先级低的规则不会让其余不冲突的本地规则失效。

`authority=team` 只允许 `policyLevel=local`，仍要求明确的 team 范围。shared 来源可承载经审批的全局规范，也可承载具有明确项目范围的本地规范或普通共享经验。任何 local 规则必须有非空 teams 或 profiles 限制，不能把实际无本地范围的规则伪装成 local。official 固定使用 global 标记，但有效等级始终高于非官方 global。

普通 shared 层级不是“已验证通过”的同义词，也不自动取消原有 checks 的硬违规性质。层级只决定同主题裁决；检测强度由明确的检查定义及其可靠性决定。本轮仅针对两条不可靠的 Limelight 检查撤销其硬检查实现。

### 审批与信任边界

- 保留现有审批角色：official/shared 来源由 overall_software_lead 审批，team 来源由对应 team_software_lead 审批。
- 声明或提升到 global 必须走 overall_software_lead 审批；团队规则不能只改一个字段自行提权。
- candidate 不因设置了层级、项目或赛季而自动批准。
- 已批准规则的来源证据与历史审批记录不能被伪造。纯格式迁移保留记录；扩大范围、提升层级或实质改写指令须重新审批，不能沿用旧时间戳声称新语义已经获批。
- 审批字段是本地协作流程，不是登录鉴权或密码学认证。能修改仓库的人仍能改配置／规则；本轮不把 CLI 宣称为不可绕过的权限系统。

## 5. 赛季提交与跨赛季复用

继续使用 `applicability.seasons`，不新增日期计算器或动态的 current-season 值：

- `seasons: ["2025-2026"]`：仅该赛季可生效。
- `seasons: []`：明确声明不限赛季；不代表已在每一届机器人和 SDK 上验证。
- 多个赛季填写多个具体值；每个值必须为连续两年的 YYYY-YYYY，例如 2025-2026。拒绝 2025-2027、中文“本赛季”等含糊值。
- 新版规则格式必须显式填写 applicability 和 seasons，遗漏为加载／校验失败，不能自动变成空数组。
- 提交模板解释何时限定赛季：比赛流程、场地路线、当届机制、特定版本组合及尚未跨季复核的行为；一般注释、组织方式也只有在确实无赛季限制时才能填空数组。

生成候选规则的 `extract` 必须由用户明确选择 `--season YYYY-YYYY` 或 `--all-seasons`，二者互斥；不能在未提供范围时默默输出跨赛季候选。模型不能扩大用户给定的范围。人工 YAML 提交通过必填字段表达同一选择。

`candidates` 列表和批准前摘要显示 team、seasons、policyLevel、profiles，避免审核者只看到标题就批准。现有批准角色及 candidate → approved 流程保留。

旧规则不批量清空 seasons。第一轮先保留现有赛季范围；中文注释、常量集中等可单独提出跨季扩展并走重新审批。硬件方向、BRAKE、Dashboard 选择、自动命名与架构规则不能仅因听起来通用就取消限制。

跨季排除只是不进入 activeRules，不删除历史知识。Agent 可作为带版本说明的历史参考阅读，但不能把它当成本赛季已生效规范或自动晋升候选。

## 6. 项目适用条件与架构冲突

扩展 RuleContext 和 applicability，增加 `profiles` 字符串集合。profile 表示用户明确采用的项目约定，不是扫描出某个依赖就强制采用的架构。

首批内置 profile 为 `rookiebot`、`simple-opmode`、`ftclib-command`。`rookiebot` 隐含 `simple-opmode`，规范化上下文补入该值。simple-opmode 与 ftclib-command 在这一版是互斥的项目约定；混合架构项目不能被 Agent 擅自归到其中之一，应先确认是否需要后续的路径级 profile 扩展。

匹配语义固定为：teams 列表内任一值匹配，seasons 列表内任一值匹配，profiles 中全部值都必须存在于规范化项目上下文；各维度之间是 AND。空列表不限制该维度。拼写错误或不支持的 profile 报配置错误，不能当作没有规则。

项目配置增加 profiles。安装／升级时显示建议及识别证据，由用户确认；已有明确配置可以复用。不能仅从 README 示例、库依赖或当前日期猜测。命令行使用可重复的 `--profile`，通用项目用显式的 `--generic-profile` 表达空集合；二者互斥。新版 resolve/check 缺少 profile 选择时返回 `context-required`，而不是漏掉项目规则后宣称成功。

规则迁移：

- 12 条 `shared.rookiebot-*` 规则均增加 profiles=[rookiebot]，把原有文字限制变成机器条件；不扩展到所有 FTC 项目。
- `shared.rookiebot-simple-opmode` 使用 local 层级。
- RookieBot 其余专属约定使用 local 层级，保留 shared 来源和原审批职责。
- `shared.ftclib-command-candidate` 限定 profiles=[ftclib-command]；它当前实际为 approved，ID 的 candidate 后缀不作为状态判据，本轮不为改名字破坏引用。其新适用范围须按上述审批要求确认后再以 approved 交付。
- 上述 FTCLib 规则与 RookieBot simple-opmode 规则统一采用 topic=opmode-architecture，作为一组明确的架构选择。hardware-groups 保留独立 topic，但受 RookieBot profile 约束。
- 合法的 RookieBot 上下文不激活 FTCLib 架构要求；同时选择两个互斥架构时先报配置错误，不进入裁决。

这份迁移清单不是自动批准操作：12 条 RookieBot 规则提升到 local、FTCLib 适用范围变更，以及两条 Limelight 硬检查撤销，均在落地前向有权维护者展示具体差异并记录确认。不批量覆盖原审批时间；未完成审批的语义变更不能带着旧审批记录发布。等价地把既有文字范围编码为 profiles，与真正扩大范围／提升层级分别记录。

这解决已知的具体矛盾，不承诺检测任意中文／英文指令之间的语义冲突。后续发现其他互斥规范时，应规范 topic、拆分复合指令或增加明确适用条件，而不是交给模型临时覆盖。

## 7. 检测与人工复核

### 硬检查保持确定性

保留现有四种 checks 和默认 diff 范围：HEAD→index 与 HEAD→工作区的并集，含非忽略 untracked；路径检查覆盖删除、重命名触及的路径，regex 检查只检查新增行。

本轮不把通用 regex-required 改造成语义分析器。它仍然只表示“本次适用新增行中出现了某个模式”，不能用于宣称每个调用点都有安全保护。新增加的硬检查必须同时有违规、正常和无关变更测试。

### Limelight 改为明确的人工复核

删除两条 Limelight 规则中对 `**/*.java` 的 regex-required 实现，保留规则指令、来源证据和已知适用语义。不要求人为加入 `.isValid()` 或 `getTargetTimestamp` 字符串过关。

增加可选 `reviewTriggers`，只用于决定何时显示人工复核提示，不能关闭或缩小同一规则已有的硬 checks：

- 每个 trigger 包含 paths（非空 glob 数组）和 addedLinePatterns（正则数组）。
- paths 内任一 glob 匹配，且 addedLinePatterns 内任一模式命中新增行时触发；patterns 为空表示仅依据触及路径触发。
- 多个 trigger 为 OR；输出按 ruleId、path 去重，行号指向实际命中的新增行。只有路径触发、删除或无可定位新增行时 line=null，不拿文件首行假充证据。
- Limelight 首批使用明确设备／结果类型和结果读取调用作为启发式触发，例如 Limelight3A、LLResult 和 getLatestResult。匹配只是潜在视觉使用的线索。
- 命中后始终报告“需要人工复核结果有效性／时效性”，即使同一 diff 已有 isValid 或时间戳关键字，也不能自动判定通过。
- 两个文件分别命中时分别报告；不能用一个文件里的检查关键字消掉另一个文件的提示。

边界必须随结果输出：仅观察新增行和触及路径，没有完整控制流、变量类型和上下文证明。封装后的读取、仅删除保护条件或没有命中触发词的视觉改动可能漏掉提示；“未触发”不等于“视觉安全已验证”。可靠的使用点分析另做后续项目，不在本轮伪装完成。

### 减少无关 soft，不隐藏尚未检查的规则

无 checks 且无 reviewTriggers 的生效规则保留项目级 soft；有 reviewTriggers 的只对命中的变更生成定位提示。需要跨整个项目评审的事项不能随意加窄触发器来消声。

第一轮为 Limelight、依赖改动和明确的代码风格规则配置可解释的触发范围。Dashboard 规则在依赖／遥测相关变化时提示；没有 Dashboard 的项目不为了清空提示自动安装它。公开字段重命名可能影响其他代码时，Agent 报告未采用及兼容性原因，不擅自声称已符合。

完整规则仍可通过 resolve 看到。check 输出增加 reviewCoverage，按规则给出 hard-check-only、manual-review-required、not-triggered 或 hard-and-manual 状态及适用的检查边界；它不是通过证明。这里 hard 表示存在硬检查定义，不代表本次每项检查都匹配到输入；同时返回实际评估和因无适用变更跳过的检查数。无硬检查且无人工提示为 not-triggered；只有人工提示为 manual-review-required；有硬检查且有人工提示为 hard-and-manual；其余有硬检查的情况为 hard-check-only。soft 可附 path、line 和触发原因，保持稳定排序。

## 8. 裁决解释与接口升级

处理顺序为：加载／校验 → 状态与适用范围过滤 → 同 topic 优先级裁决 → 硬检查与人工复核 → 输出可解释结果。

resolve 保留 activeRules/conflicts，并增加：

- 规范化 profiles；activeRules 的 policyLevel 与完整 applicability。
- excludedRules：ruleId 和可枚举的排除原因，包括 status、team、season、profile；可同时列出多个不匹配原因。
- overriddenRules：被覆盖 ruleId、同 topic 的胜出规则或冲突组 IDs、对应有效层级。
- conflicts：topic、有效层级、ruleIds 和各来源 authority；不再假设同一层级的所有规则只有一种 authority。

不能把“低优先级未执行”“本赛季不适用”“人工未核实”统统写成通过。输出不加入当前时间等会破坏同输入同输出的字段，所有集合使用明确排序。

版本边界：

- 知识 YAML 提升到 schemaVersion=4，新字段只在 v4 可用；新解析器保留读取 v1–v3 的兼容层。
- 旧格式缺省映射为 official→global、team→local、shared→shared，保留其原有 team/season 和检查含义；不能自动提升普通 shared。
- 新格式必须显式写 policyLevel、applicability.teams、applicability.seasons、applicability.profiles。authoring／extract 默认产生 v4。
- v1 Git 证据迁移到 v4 时显式补上 type=git；不能只替换文件顶层版本号。reviewTriggers 显式存在时必须非空，glob／正则均须验证，避免拼写错误或空配置静默关闭人工提示。
- 核心 JSON 契约提升到 schemaVersion=2。虽然部分字段是新增，但优先级语义、必要上下文与冲突结构发生变化，不能继续宣称 v1 语义不变。
- 项目配置 schemaVersion、kernelSchemaVersion、integrationVersion 均为 2；配置继续使用 JSON 语法的 YAML 1.2，不引入新的 YAML 运行依赖。
- validate/resolve 退出码保持 0、2、64；check 保持 0=无硬违规、1=有硬违规、2=加载／校验／冲突／上下文失败、64=参数错误。`--json` 下所有失败路径仍是 JSON。
- soft 可以与 check exit 0 并存；exit 0 不能被描述成所有规则、实际运行和真机验证均已通过。

旧项目继续运行其固定 commit 和 v1 包装器，不自动升级。v2 安装器支持识别旧配置：没有显式 --ref 时保持旧 pin 和旧协议，不改成 v2；明确升级到支持 v2 的 commit 时，才预览并写入 profiles、新配置、Skill 和托管块。

选中源码的集成能力由新增 `assets/integration.json` 声明 projectSchemaVersion、kernelSchemaVersion、integrationVersion；缺少声明的已支持旧版本按 v1 处理。安装器根据所选固定源码声明选择兼容路径，不把自己的版本号硬塞进旧源码配置。未知版本拒绝；不自动把 v2 降回 v1。所有版本／profile／模板检查必须在修改目标 Git 状态前完成。

同步改动的消费方包括：CLI resolve/check、KernelJson、JSON Schema、真实输出 fixtures、Python 包装器与验证器、安装／运行 Skill、AGENTS 和契约文档。`KnowledgeRetriever`、chat/serve 以及 Android Studio 插件等直接调用 RuleResolver 的入口必须传入同一规范化上下文并处理冲突；未提供 profile 或暂不支持新契约时明确报错，不能降级成空规则继续生成代码。插件适配不扩大为 IDE UI 重做。

## 9. 安装体验切片

沿用现有 Git 下载，不增加联网知识查询。这个切片在规则闭环之后单独实现与验证：

- 在 stderr 输出预检、解析固定版本、下载源码、准备 submodule、生成配置、构建 CLI、验证契约和项目检查等阶段；stdout 仍只有最终 JSON。
- 网络操作执行前立即显示阶段；长操作提供每 15 秒一次的仍在等待提示，不打印含凭据的 URL、完整环境或模型密钥。
- 增加正整数 `--network-timeout`，默认每次网络操作 300 秒。覆盖 ls-remote、clone、fetch、submodule 下载；本地 Git 检查使用独立的 30 秒上限，Gradle 构建不误用网络超时值。
- 超时终止该操作及其下载子进程，报告失败阶段、是否已修改目标、最后安全状态及可执行恢复建议。Windows 与 POSIX 的进程清理分别测试，不只杀掉 Python 等待。
- 非交互安装不无限等待凭据输入；用户使用已有 Git 凭据管理器完成认证后重试，不在参数或日志传递明文凭据。
- 不自动删除半成品、重置工作区或强制覆盖托管文件。构建失败可按现有机制重试；submodule 注册中断须先检查状态，不能承诺任何失败都能盲目重跑。
- --dry-run 允许明确披露的上游版本预检与临时下载，不构建、不改目标文件／index／Git 配置；重复运行仍保持幂等。

## 10. 迁移清单与实施顺序

1. 先添加 domain/codec 的新字段、验证与旧格式兼容测试，再实现 profile 规范化、优先级和解释输出。
2. 升级 kernel v2 输出与消费方，保证任何版本不匹配或上下文缺失均失败闭合；保留 v1 固定项目的原运行路径。
3. 添加 reviewTriggers 和 reviewCoverage，先用人工构造 diff 回归报告中的误报与跨文件问题，再迁移两条 Limelight 检查。
4. 将仓库现有规则转成 v4 显式字段。保留无关 candidate 状态、既有 team/season 和证据；RookieBot／FTCLib 的适用性按第 6 节整理。全局提权的候选清单单独呈现审批，本轮不把所有 shared 设成 global。
5. 更新规则提交模板、extract、候选／审批摘要、使用文档和接入模板；实际修改 Skill 时使用相应 Skill 编写规范。
6. 完成 Python v1→v2 显式升级和真实临时 FTC 项目端到端验证，再做安装体验切片。
7. 最终在隔离测试副本中重现报告场景；不写入或部署用户的 RookieBot 工作区。真实项目再次接入／升级另行获得授权。
8. 同步文档的真实规则数、测试数和已验证平台。未完成的 AST 语义检测、路径级混合架构支持记录为后续事项，不宣称已经支持。

实现过程中不修改现有 SDK/JDK 私有路径，不调用此前提供的任何 API key，不新建分支。其他任务对 README／网站的未提交改动保持原样；文档范围重叠时只做可分离修改，否则先协调。发布 commit/tag/push 与目标项目升级单独确认。

## 11. 验收标准

### 规则与提交

- v4 seasons 遗漏、非法年份、不连续年份、非法层级、未知 profile、团队规则自提 global 均失败。
- 指定赛季规则在换季后不生效；空 seasons 可跨季；candidate 始终不生效。
- 无关旧规则的 team、season、status 不因格式迁移改变；历史证据和审批记录不被伪造。
- extract 的 --season／--all-seasons 缺失或冲突时报参数错误；生成候选保留用户选择。

### 裁决

- 同 topic：官方胜过 global，global 胜过 local，local 胜过普通 shared；不影响其他 topic。
- 同最高层级有多条规则时稳定报告冲突，包括来源分别为 team/shared 的 local 规则。
- 输入文件顺序打乱，active/excluded/overridden/conflicts 的排序与内容保持一致。
- RookieBot profile 只激活对应架构约定；FTCLib profile 不激活 RookieBot 专属规则；互斥 profile 被拒绝。
- 缺少必要上下文时 CLI、Python、聊天检索和插件入口不能返回“已加载全部适用规则”。

### 检测

- 只有底盘初始化、混控或普通 Java 单测的 diff，不再产生那两条 Limelight 硬违规或无依据的视觉提示。
- 命中视觉读取线索时返回定位到真实命中行的人工复核提示；关键字已出现也不宣称安全通过。
- 两个文件的线索分别报告，一个文件的 isValid 不会消掉另一个文件的人工复核。
- 测试和文档明确覆盖仅删除保护／封装读取可能未触发的边界，不把未触发当作安全证据。
- 原有可靠硬检查继续失败于其违规 fixture；路径删除、重命名、staged 后工作区回改、非忽略 untracked 不回归。
- 硬检查无违规但存在 soft 时 exit 0；硬违规 exit 1；加载、冲突、上下文和协议错误不能混为通过。

### 接入与发布证据

- 新 CLI 的 validate/resolve/check 成功与每类失败输出均通过 v2 JSON Schema，退出码和 payload 一致。
- 固定 v1 项目不升级时保持不变；显式升级完整更新 pin、配置、Skill、托管摘要，重复 dry-run 无新修改。
- 版本不匹配、未知 profile、用户修改托管内容、网络失败和部分安装状态均有拒绝／恢复测试；stdout 不混入进度文字。
- 测试网络超时和子进程清理；未做原生 Windows 运行时只报告模拟／命令构造覆盖。
- 实际运行与输出分别记录：规则测试、CLI 测试、Python 接入测试、真实临时项目端到端、机器人软件构建、部署、真机。没有执行的项目明确写未验证。

## 12. 详细设计审阅点

总体方向与“官方 > 明确全局 > 本地”的含义，以及本文件中的 profile、v2 契约和启发式人工复核方案，均已获用户确认。实施分别见规则内核 v2 和安装进度／超时两份计划。具体规则语义变更仍遵守第 6 节的差异审批检查点。文档完成不等于代码已修改、规则已迁移、检查已通过或版本已发布。
