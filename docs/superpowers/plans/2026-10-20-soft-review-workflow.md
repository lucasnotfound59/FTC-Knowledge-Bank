# Soft Review Workflow Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `subagent-driven-development` (recommended) or `executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. 用户已选择 manager 协作；经理负责协调，执行者只写所属工作包文件。用户授权与仓库要求优先：不自动 commit/push，不再次要求同范围批准。

**Goal:** 在 Dashboard 为真实 soft/dev 提醒提供独立、持久化、可追溯的逐项复核，并关联被检查输入版本。

**Architecture:** 保留 events.jsonl 原始检查，新增同分区 reviews.jsonl 声明历史；后端以源审计与复核历史生成待办投影。producer 将实际检查输入摘要附在 Standardizer 阶段，后端根据后续可比较检查判断陈旧；前端只显示后端投影。

**Tech Stack:** Kotlin/JVM 21、现有 Jackson/JUnit/JGit、JDK HttpServer、原生 HTML/CSS/JS；不新增外部依赖。

## Global Constraints

- 设计为 `docs/superpowers/specs/2026-10-20-soft-review-workflow-design.md`，任何实现细化须保持其中的安全/兼容边界。
- 不修改 kernel JSON v2、CLI stdout/退出码、rule resolution/Standardizer 规则判定、真实 knowledge 文件或项目接入协议 v2。
- 不自动 commit/push；保护已有 `.DS_Store`、`dsh-client-ui-tokyo-night-dsh/`、`media/` 与其他未跟踪文档。
- 真实 Dashboard 使用同知识根及审计根；样例写入只用临时知识副本与审计根，token 不进入报告。
- 复核不是规则审批、机器合规、RC/DS 或真机安全证明。dev 降级项永远保留其原类型。
- 已批准第一版只关联检查快照；外部编辑需重跑检查才能被观察。不会通过日志里任意路径读取用户文件。
- 普通 soft 没有精确位置，必须如实展示规则级提醒。
- 提交结论为 verified/needs_changes/not_applicable；理由/复核人必填，链接可选；不允许批量通过。
- 已确定 QA 上下文为 20827 / 2025-2026 / generic / normal，仅用于平台诊断。2026-10-20 实际 resolve 返回 schemaVersion=2、26 active、0 conflicts，收据 `/tmp/ftckb-soft-review-resolve.json`。

## 依赖与文件所有权

| 工作包 | 写入范围 | 依赖 |
| --- | --- | --- |
| A：审计扩展与复核存储 | `modules/audit/src/main/kotlin/org/ftckb/audit/`、对应 `src/test/` | 无；先完成公共接口 |
| B：实际检查输入摘要 | `modules/standardizer/`、`KernelAudit.kt`、`CheckCommand.kt`、`SessionAudit.kt`、`StandardizerEditGate.kt` 及对应 producer 测试 | A 的 AuditCodeVersion/Stage 接口 |
| C：Dashboard 后端投影/API | `DashboardReviews.kt`、`DashboardServer.kt`、`DashboardJson.kt`、`DashboardReviewTest.kt` | A；可与 B 并行；Dashboard producer 归 C |
| D：Dashboard UI/最终文档 | `apps/knowledge-cli/src/main/resources/dashboard/{index.html,app.js,styles.css}`、`docs/dashboard.md`、`README.md`、`AGENTS.md` 的功能说明 | C 的接口；可先用固定接口实现，最终连真服务 |

不要并行改同一文件。A 冻结接口后告知 B/C；C 在 `DashboardServer.kt` 处理手动 patch 的摘要与可选 predecessor 参数；B 不写此文件。最终集成者运行组合测试和浏览器验收。无需修改规则数；新增测试数量必须来自最终 XML。

## 公共契约（先实现，再集成）

在 A 的 `Review.kt`/`Audit.kt` 定义以下形状；附加字段全部有默认值以兼容已有构造调用。序列化 wire 值为小写下划线，revision 是 Long。

```kotlin
data class AuditCodeVersion(
    val kind:String,
    val subjectId:String?,
    val inputDigest:String,
    val head:String?=null,
    val predecessorInvocationId:String?=null,
    val schemaVersion:Int=1
)
// AuditStage 末尾加：val codeVersion:AuditCodeVersion?=null

data class ReviewTarget(
    val itemId:String,
    val invocationId:String,
    val sourceDigest:String
)
data class ReviewInput(
    val reviewId:String,
    val target:ReviewTarget,
    val expectedRevision:Long,
    val decision:String,
    val reason:String,
    val evidenceUrls:List<String>,
    val reviewer:String
)
data class ReviewRecord(
    val reviewId:String,
    val target:ReviewTarget,
    val revision:Long,
    val decision:String,
    val reason:String,
    val evidenceUrls:List<String>,
    val reviewer:String,
    val createdAt:String,
    val sanitized:Boolean=false,
    val schemaVersion:Int=1
)
data class ReviewHistory(val items:List<ReviewRecord>,val revision:String,val health:AuditHealth)
data class ReviewWriteResult(val record:ReviewRecord,val replayed:Boolean)
class ReviewProblem(val code:String):RuntimeException(code)
interface ReviewStore {
    fun history(itemId:String):ReviewHistory
    fun health():AuditHealth
    fun record(input:ReviewInput,validateSource:(ReviewTarget)->Boolean):ReviewWriteResult
}
// FileReviewStore(baseDirectory:Path, knowledgeRoot:Path,
//     diagnostics:(String)->Unit={ System.err.println(it) }):ReviewStore
// ReviewIdentity.itemId(record:AuditRecord, stageIndex:Int, hit:AuditHit):String
// ReviewIdentity.sourceDigest(record:AuditRecord, stageIndex:Int, hit:AuditHit):String
// ReviewFingerprint.sha256(parts:List<Any?>):String — canonical Jackson JSON UTF-8
```

`ReviewIdentity` 使用已安全解码的持久化源结构生成摘要；不得对数据库存的是截断文本、前端发的是原文本的两种内容混用。`sourceDigest` 不含复核本身。`ReviewFingerprint` 必须在 map/object 场景固定键顺序；列表序列保留本次实际检查顺序。

## Task A：追加存储与兼容审计模型

**Files:** 新建 `modules/audit/src/main/kotlin/org/ftckb/audit/{Review.kt,ReviewCodec.kt,FileReviewStore.kt,ReviewIdentity.kt}`；修改同目录 `{Audit.kt,AuditCodec.kt}`；新建 `modules/audit/src/test/kotlin/org/ftckb/audit/{FileReviewStoreTest.kt,ReviewIdentityTest.kt}` 并补充 `FileAuditStoreTest.kt`。

**Interfaces:** 生产公共契约；消费 AuditRecord/AuditHealth，禁止依赖 CLI 或 Standardizer。为 FileReviewStore 明确固定的分区路径/锁路径，同 FileAuditStore 规范化算法。

- [ ] 先添加有业务含义的测试：写 verified revision=1；以 expectedRevision=1 改为 needs_changes revision=2；两条历史保留；重开 store 后相等；旧审计无 codeVersion 正常读取。

```kotlin
val input=ReviewInput(UUID.randomUUID().toString(),target,0,"verified","已查看证据",emptyList(),"tester")
val first=store.record(input) { it==target }
assertEquals(1L,first.record.revision)
assertEquals(first.record,store.record(input) { it==target }.record)
assertTrue(store.record(input) { it==target }.replayed)
assertEquals(1,store.history(target.itemId).items.size)
```

测试中的 `target` 用 ReviewIdentity 从完整 fixture record 构造，不使用任意自造 itemId 代替源校验。并发测试用两个 FileReviewStore 实例、CountDownLatch 同时提交不同 reviewId/同 expectedRevision，断言只有一次成功与另一次 conflict；增加独立 JVM 写入测试沿用 FileAuditStoreTest 的 subprocess classpath 模式。

- [ ] 运行：`./gradlew :modules:audit:test --tests '*Review*Test' --tests '*FileAuditStoreTest'`，新增测试在实现前应因缺类/行为失败；记录失败原因。
- [ ] 实现 Codec 的 canonical UUID/hash/enum/长度/URL/控制字符校验，JSON 严格重复字段检测、未知字段拒绝；用现有敏感值模式遮盖姓名/理由并返回 sanitized。URL 用 java.net.URI 验证绝对 http(s)、host、无 userinfo 和敏感 query/fragment 键，不访问网络。
- [ ] 实现 `reviews.lock` 的 JVM ReentrantLock + FileChannel lock；锁内 refresh→幂等→CAS→validateSource→append+newline+force。`ReviewProblem` 使用 `invalid_input`、`conflict`、`source_changed`、`storage_unavailable` 四种 code。相同 reviewId 不同内容为 conflict；不要把 expectedRevision/服务端 timestamp 当作幂等业务内容差异。
- [ ] 实现有界增量回放和 health：完整有效记录递增 revision；无效 line/重复 revision/截断/替换/不完整尾部/读取失败分别警告；健康异常禁写，读取仍展示确认可读历史。持久化失败不能生成成功对象。读取缺文件初始为空且健康；以前存在后删除为异常。
- [ ] AuditCodec 对 codeVersion 新字段做限定校验，解码缺字段为 null；FileAuditStore 无生命周期变化。保留原 events.jsonl，不把 REVIEW 加入 AuditEventType。
- [ ] 增加坏 URL/过长/重复 JSON 键、幂等内容冲突、注入 force 失败后再次 force 成功才准幂等返回、最终编码超过64KiB、多字节文字及URL编码敏感键、sourceChanged、损坏尾部、非法未来 schema、日志截断与只读目录失败测试；重跑上述命令通过后向 B/C 公布接口。

## Task B：让摘要绑定实际被检查的输入

**Files:** 修改 `modules/standardizer/src/main/kotlin/org/ftckb/standardizer/Standardizer.kt`；对应 `StandardizerTest.kt` 或新建 `CheckedInputTest.kt`；修改 `apps/knowledge-cli/src/main/kotlin/org/ftckb/cli/{KernelAudit.kt,CheckCommand.kt}` 和 `KernelAuditAcceptanceTest.kt`；修改 `modules/session-shell/src/main/kotlin/org/ftckb/session/{SessionAudit.kt,StandardizerEditGate.kt}` 及 `StandardizerEditGateTest.kt`、`RequestAuditTest.kt`。

**Interfaces:** 消费 AuditCodeVersion 与 ReviewFingerprint；产出 Standardizer `CapturedWorktree(changes:List<DiffChange>,patches:List<String>,headBefore:String?,headAfter:String?,repositoryIdentity:String)` 与 `captureWorktree(root:Path):CapturedWorktree`。`worktreeChanges(root)` 继续委托并返回 `.changes`，保证原调用保持兼容。不要将 audit 模块反向依赖加到 Standardizer。

- [ ] 在 Standardizer 测试先添加 staged/unstaged 同时变化、删除、重命名、mode/binary 变化的捕获测试，确认返回 changes 与旧行为一致、patches 包含完整 diff 信息。
- [ ] 用现有 JGit DiffFormatter 让 diffChanges 的原始 bytes 仅渲染一次，然后解析同一文本；采集前后读 HEAD，return 同时带 actual changes 和 patches。binary diff 占位文本不能代表内容；CapturedWorktree 再加 `contentDigests:List<List<String?>>`，每项为 path/oldMode/newMode/oldContentHash/newContentHash，缺文件用 null；覆盖 binary、tracked/untracked，来自同一捕获输入，producer 摘要必须包括此列表。无法稳定采集则版本为 unknown，不阻断原检查。不要在 evaluate 后重新从磁盘读补丁伪造输入版本。
- [ ] `CheckCommand` 中 supplied patch 只读取一次，worktree 使用捕获对象；在不改变解析/错误/输出路径的前提下，计算摘要并传 `KernelAudit.checked(evaluated,dev,codeVersion)`。版本构造失效只记录 null 和诊断，不能改变 kernel 返回。sha256 必须含 kind、仓库身份、headBefore/headAfter、完整 patch 列表，不能只散列 DiffChange.addedLines。

```kotlin
val version=runCatching {
    require(capture.headBefore==capture.headAfter)
    AuditCodeVersion("worktree",ReviewFingerprint.sha256(listOf(capture.repositoryIdentity)),
        ReviewFingerprint.sha256(listOf("worktree-v1",capture.repositoryIdentity,
            capture.headBefore,capture.headAfter,capture.patches,capture.contentDigests)),capture.headAfter)
}.getOrNull()
```

- [ ] SessionAudit.stage 增加默认 codeVersion=null 的尾参数。StandardizerEditGate.inspect 从 immutable preview 的每项 path/before/after 构造摘要并附 Standardizer stage；使用 repositoryBaseline 已有数据得到 subjectId/head，不再次读取预览正文。预览 kind 必须是 edit_preview，与工作区版本不相等。
- [ ] 增加 producer 测试：两次不同删除内容、同路径不同binary/untracked内容摘要不同；同输入摘要稳定；CLI supplied patch 标正确 kind；旧 stdout/退出码字节不变；audit 日志没有 raw diff、before/after 正文或 token；session 失败仍保持原 gate 行为。
- [ ] 运行：`./gradlew :modules:standardizer:test :modules:session-shell:test :apps:knowledge-cli:test --tests '*KernelAuditAcceptanceTest' --tests '*KernelAuditCompatibilityTest' --tests '*CheckAcceptanceTest' --tests '*WorkModeAcceptanceTest'`。若 Gradle 多项目 `--tests` 对空匹配报错，分为前两模块 test 和 CLI filtered test 两条命令，不修改 Gradle 配置。

## Task C：后端事项投影与事务 API

**Files:** 新建 `apps/knowledge-cli/src/main/kotlin/org/ftckb/cli/DashboardReviews.kt` 和 `apps/knowledge-cli/src/test/kotlin/org/ftckb/cli/DashboardReviewTest.kt`；修改同目录 `DashboardServer.kt`、`DashboardJson.kt`。

**Interfaces:** 消费 AuditStore/ReviewStore/ReviewIdentity；为 DashboardServer 新增可注入 ReviewStore（默认同配置知识分区），保持已有参数构造可用。新路由严格依设计表。投影 item 至少返回 itemId/invocationId/sourceDigest/stageIndex/hit/originalStatus/codeVersion/versionState/latestReview/canReview/disabledReason；detail 追加 history；GET 列表追加 nextCursor/revision/auditHealth/reviewHealth。

- [ ] 新 HTTP 测试先创建真实 FileAuditStore fixture：COMPLETED + Standardizer soft，POST 一条声明后 GET detail，断言 events.jsonl 完整字节和原 summary.diffChecked/total 都不变，review history 变为 1。
- [ ] 实现纯投影：对每个原始 stage index 保序，过滤已执行 Standardizer hits，按 ReviewIdentity 去重；未完成/FAILED/审计健康异常禁写，COMPLETED exit=1 的 soft 可写但 hard 显示仍 blocked。旧 version=null 为 unknown。后台不把 source path 传给 Files/Git。
- [ ] 为投影生成绑定 audit cursor+record index+hit index 的严格 cursor；每次最多扫描固定 100 个 audit records，返回空 items 仍可带 nextCursor；过滤不能使未扫记录消失。被替换/截断的 audit revision 导致 cursor 过期，返回 409 要求重置。统计只描述本页，UI 不显示伪全量数字。
- [ ] 以已读取的新到旧审计记录建立版本索引，key 为 kind+subjectId+完整 context（team/season/规范化profiles/workMode），后续不同摘要令旧项 stale；排序依据审计追加序列。版本索引须从当前健康审计流增量构建并带已追上标记，不依赖用户已经浏览的页；扫描不足不能推断无后续变化，应先返回 unknown/待索引。直接打开历史detail也须查询此索引；禁止把当前页当完整历史。不能把无关联手动 patch 合并；不能因缺规则版本或不同版本格式认定有效。sourceDigest 校验按 detail 当前投影执行，source 缺失/变动为 source_changed。
- [ ] 复核 POST 从现有 audited(command) 分支单独分派，复核存储本身记录声明；否则未注册新 command 会导致 AuditCodec 失败，并污染检查计数。鉴权/Host/Origin/contentType/readBody 继续沿用；复核 body 上限另限 64 KiB，未知字段拒绝。
- [ ] 手动 check 接收可选 predecessorInvocationId；校验同分区、同上下文、前次 Dashboard supplied_patch，继承 subjectId；首次 subjectId=本次 invocationId 的摘要。predecessor 必须已完成且早于新调用，不允许自引用/环；允许历史分叉，同 lineage 的后续版本仍可使旧项 stale。散列实际 diff，附在 Standardizer stage。合法重检继续正常产生新的原检查和新的待办，不能复用 review 记录。
- [ ] 明确 error mapping：invalid_input→400/422，conflict/source_changed→409，source not found→404，storage_unavailable→503；读健康异常仍200附 warnings，canReview=false。
- [ ] 增加 API 测试：无 token/外源/错误 Host，未知字段、重复键、dangerous URL、空理由；同 ID retry、CAS conflict；dev 原类型保持；旧源unknown；未结束/FAILED禁止；跨分区/predecessor伪造拒绝；删除/改源hash后禁止；大量hits分页无遗漏；从未访问list直接打开旧detail及跨100条历史仍正确返回stale（索引追赶期间只允许unknown）；轮询不写；重启仍可读。
- [ ] 运行：`./gradlew :apps:knowledge-cli:test --tests '*DashboardReviewTest' --tests '*DashboardServerTest' --tests '*DashboardCommandTest'`。

## Task D：界面、说明与最终集成

**Files:** `apps/knowledge-cli/src/main/resources/dashboard/index.html`、`app.js`、`styles.css`；`docs/dashboard.md`、`README.md`、`AGENTS.md`；新建验收报告 `docs/superpowers/reports/2026-10-20-soft-review-validation.md`。设计/计划本身仅规划者维护。

**Interfaces:** 精确使用 C 的三条 reviews 路由及 item DTO。保留原 recordStatus 的原检测状态，旁边独立显示复核/版本状态，不能把 soft badge 换成机器“通过”。

- [ ] 新增复核导航/列表/筛选/下一页，复用现有 panel/table/form/badge 样式；审计 detail 的每项 soft/dev 增加跳转。不要重新设计已有导航或使用新 UI 依赖。
- [ ] 表单明确 label 与 maxlength：三状态、复核人、理由、最多8条链接；草稿展示完整摘要后保存。DOM 全部 textContent；链接 only validated http(s)，target=_blank/rel=noopener noreferrer。键盘可以完成选择、填写与保存，状态变更区域用 aria-live。
- [ ] 每次用户正式提交生成 UUID，网络失败重试保留同 UUID 与同内容；修改内容则生成新 UUID。成功后采用服务端 sanitized 内容和 revision；409 保留草稿并展示最新历史；断线/unknown/不可校验时如实显示限制，不清空草稿。
- [ ] 列表分别显示「待复核」「已核对」「需修改」「本次不适用」与「历史快照」「需重新复核」「版本未知」；unknown 不进入当前代码已核对统计；旧unknown项已填写verified/not_applicable后可离开默认待办，进入已记录结论，保留“历史复核，版本未知”，避免历史待办永远无法完成。新检测默认待复核；显示“代码修改后重新运行检查”。
- [ ] 更新 docs/README/AGENTS：原“没有证据证明soft已复核”改为“仅独立复核声明能说明指定历史快照有人声明复核；原检测仍保留”，补充 reviews.jsonl、三状态、身份声明、链接不抓取、缺版本/重检边界，不改版本号或声称已发布。
- [ ] 先运行 `node --check apps/knowledge-cli/src/main/resources/dashboard/app.js`，再执行最终组合：

```bash
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew \
  :modules:audit:test :modules:standardizer:test :modules:session-shell:test \
  :apps:knowledge-cli:test :apps:knowledge-cli:installDist
```

此组合是本次影响范围的最终验收；不运行无关 IDE/Pedro/机器人部署套件。失败分类记录真实错误，修复后重跑相关组合，不借用历史测试数。

- [ ] 临时目录复制真实 knowledge，仅对临时分区运行新安装 Dashboard。用真实 API 创建 normal soft、hard+soft、dev 提醒三种检查；浏览器实际执行：待办打开→写理由/链接→保存→刷新保留→改判历史两条；两个页面同revision冲突不丢草稿；新patch重检使旧声明 stale、新项 pending；旧日志unknown；断连保留草稿；源缺失禁写；390px布局与键盘操作；URL/XSS文本不执行。必须保留截图/精简结果收据，不记录 token。
- [ ] 核对安装 JAR 内 app.js 和源文件 SHA-256 相同；服务重启复核历史存在；源 events.jsonl 在复核操作前后字节相等。最终真实 Dashboard 仅检查连接/48规则；不要停止用户原服务，更新产物后如需新实例应说明旧服务仍是旧构建。
- [ ] 执行 `ftckb validate knowledge --json`（期待ok:true/ruleCount:48）与显式 QA 上下文 check 当前 task diff（normal）；确保包含新建文件而不混入用户既有 untracked。记录每条soft适用性，不把exit0当无提醒。
- [ ] `git diff --check` 并自审状态只含授权变更；在验收报告中填写最终测试数量、命令、浏览器证据、真实限制和未执行内容，再交独立 reviewer。无自动提交步骤。

## 计划自审

四个工作包覆盖持久化/不可变性、producer真实输入、事项身份、版本陈旧、API与UI；各自拥有独立可测边界。并发共享文件只允许 C 修改 DashboardServer，B 修改 session/CLI producer，A 修改 audit。所有新字段默认兼容旧数据；不存在依赖 kernel JSON 变化的任务。开放产品问题已按首版保守默认解决，不需追加范围审批。
