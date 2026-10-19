# FTC Knowledge Bank Dashboard Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. The user has already selected manager-led delegated execution and approved the full design; do not ask another execution-choice question. Do not commit or push.

**Goal:** Deliver the complete local Chinese dashboard, durable CLI/request audit, and mandatory Standardizer checks before built-in Agent edits are written.

**Architecture:** Keep deterministic rules in domain/knowledge/standardizer. Add an independent audit module and knowledge workspace service; a no-key dashboard HTTP server consumes those services. Agent-runtime receives injectable prewrite hooks, while session-shell assembles request snapshots, tracing, and the Standardizer adapter without importing CLI/HTTP into agent-runtime.

**Tech Stack:** Existing Kotlin/JVM 21, Gradle, JUnit Jupiter, Jackson, SnakeYAML Engine, JGit, JDK HttpServer, plain local HTML/CSS/JavaScript. No frontend build system or new database dependency.

## Global Constraints

- Approved design: `docs/superpowers/specs/2026-10-19-dashboard-design.md`, 2026-10-19. Current implementation base is `0c133c4`; the existing 48-rule validation passed before this implementation, which is not evidence for later changes.
- `既有 kernel JSON v2、stdout 和退出码保持兼容，审计错误通过独立诊断路径报告。`
- `只监听 127.0.0.1，生成每次启动的 token；所有读取数据和写入 API 要求 token，写入仅允许 POST，校验同源请求，禁止跨站访问。`
- `页面每两秒轮询轻量摘要，只有版本变化才重载规则或调用增量。记录列表分页，不在每次轮询读取全部日志。失联保留最后一次数据并明确标记过期。`
- `normal 是默认模式，test/dev 仅由用户显式选择，不保存为项目默认。`
- Preserve all unrelated user work: root/apps `.DS_Store`, `dsh-client-ui-tokyo-night-dsh/`, and `docs/superpowers/plans/2026-09-08-docs-site.md`. No automatic commit/push or real rule-content changes.
- One write owner per file/package. Implementers may inspect other packages; interface changes must be announced to their consumers before edits. The root manager delegates integration and does not implement code.
- Use real temporary knowledge/FTC Git repositories in mutation tests and browser QA. No fixture history may be presented as production audit history.
- Approval roles are declarations checked by `ApprovalPolicy`, not authenticated identities. No rule authoritativeness may be inferred from folders or ID prefixes.
- No prompt, completion, raw diff, API key, bearer token, or arbitrary exception body in persisted audit. Store bounded rule/hit snapshots and classified errors.
- Software checks do not establish Robot Controller, Driver Station, or physical robot success.
- User's latest testing instruction: do not rerun the complete suite for ordinary changes; reserve full-suite reruns for major-version updates. The root has already completed the 482-test baseline in this session. Reuse that baseline and run affected-module tests plus critical HTTP/browser flows after the implementation; do not present the baseline as testing the new code.

---

## Dispatch map and dependency order

| Package / owner | Exclusive write ownership | Depends on | Deliverable |
| --- | --- | --- | --- |
| A — audit | New `modules/audit/**`; `settings.gradle.kts` | Existing domain only | Durable cross-process storage, incremental index and public audit types |
| B — knowledge workspace | `modules/knowledge/**` | Existing domain | Immutable source snapshots, safe rule preview/save/approval service |
| C — Agent hooks | `modules/agent-runtime/**` | Existing domain/knowledge/tooling-git; B snapshot constructor | Request-safe retrieval and injectable prewrite gate; no Standardizer dependency |
| D — dashboard server | New `apps/knowledge-cli/src/main/kotlin/org/ftckb/cli/Dashboard*.kt`; corresponding `Dashboard*Test.kt` | A/B interfaces | No-key localhost HTTP API and launcher; hand entrypoint signature to E |
| E1 — kernel_audit | `Main.kt`, `CheckCommand.kt`; new `KernelAudit*.kt` and `KernelAudit*Test.kt` only | A/B, D launcher | Kernel command audit, byte/exit compatibility, dashboard CLI registration |
| E2 — dashboard_integration | Other existing CLI Kotlin files/tests, especially ChatRepl/Serve/ProductionChatLauncher; `modules/session-shell/**`; `apps/knowledge-cli/build.gradle.kts` | A/B/C public seams | Chat/serve tracing, request refresh, prewrite adapter and dependency wiring |
| F — frontend | New `apps/knowledge-cli/src/main/resources/dashboard/**` | API contract below | Complete Chinese dashboard and local resource UI |
| G — verification/docs | New integration/browser test scripts and documented validation report; README/AGENTS/docs only after other owners finish | All | Combined regression, browser exercise, truthful documentation |

A, B, C, D and F may start concurrently against the contracts below. E1 may prepare compatibility captures immediately and implement after the seams land. A alone registers its module; E2 adds consumer dependencies. C owns all agent-runtime build changes if needed. D must not edit Main.kt, ServeApi.kt, or resources. E1 and E2 are separate owners and must not edit each other's paths. G checks integration after packages finish, using targeted tests and critical flows; do not repeat the complete suite. References to E in shared contract prose mean E1 for deterministic CLI integration and E2 for session/request integration.

## Shared contracts — freeze these names before implementation

These are the public seam declarations, not a requirement to put every declaration in one file. Kotlin packages, property names, and method signatures are shared contracts. Consumers must not introduce private lookalikes. Constructors can add optional test seams while keeping these public calls valid.

### A. Audit storage and wire vocabulary

Package `org.ftckb.audit`, module `:modules:audit`, depending on domain and Jackson only. Default base directory: `Path.of(System.getProperty("user.home"), ".ftckb", "audit")`. `FTCKB_AUDIT_DIR` overrides the base directory for every CLI command and server. Constructor injection takes precedence in tests. A partition is `<base>/<sha256(canonical knowledge root UTF-8)>/events.jsonl`; parameter errors without a discoverable root go into `<base>/unscoped/events.jsonl`. Dashboard shows its selected partition and the explicitly labeled unscoped partition; it must not attribute unscoped calls to this library.

```kotlin
enum class AuditEventType { STARTED, SNAPSHOT, STAGE, FINISHED }
enum class AuditRunStatus { STARTED, COMPLETED, FAILED }
enum class AuditStageStatus { NOT_RUN, PASSED, BLOCKED, NOT_APPLICABLE }
data class AuditContext(val team:String?, val season:String?, val profiles:List<String>?, val workMode:String="normal")
data class AuditMetadata(
    val entrypoint:String, val command:String, val knowledgeRoot:String?,
    val context:AuditContext?=null, val parentInvocationId:String?=null,
    val model:String?=null
)
data class AuditRuleSnapshot(
    val id:String, val topic:String, val title:String, val instruction:String,
    val authority:String, val policyLevel:String, val teams:List<String>,
    val seasons:List<String>, val profiles:List<String>
)
data class AuditHit(
    val kind:String, val ruleId:String?, val check:String?, val path:String?,
    val line:Int?, val detail:String
)
data class AuditStage(
    val name:String, val status:AuditStageStatus, val category:String?=null,
    val detail:String?=null, val hits:List<AuditHit> = emptyList()
)
data class AuditEvent(
    val invocationId:String, val type:AuditEventType, val timestamp:String,
    val metadata:AuditMetadata?=null, val knowledgeVersion:String?=null,
    val ruleSnapshots:List<AuditRuleSnapshot> = emptyList(), val stage:AuditStage?=null,
    val status:AuditRunStatus?=null, val exitCode:Int?=null,
    val durationMillis:Long?=null, val inputTokens:Int?=null, val outputTokens:Int?=null
)
data class AuditRecord(
    val invocationId:String, val startedAt:String, val endedAt:String?,
    val metadata:AuditMetadata, val knowledgeVersion:String?,
    val ruleSnapshots:List<AuditRuleSnapshot>, val stages:List<AuditStage>,
    val status:AuditRunStatus, val exitCode:Int?, val durationMillis:Long?,
    val inputTokens:Int?, val outputTokens:Int?
)
data class AuditHealth(val ok:Boolean, val warnings:List<String>)
data class AuditPage(val items:List<AuditRecord>, val nextCursor:String?, val revision:String, val health:AuditHealth)
data class AuditSummary(
    val revision:String, val total:Long, val unfinished:Long,
    val diffChecked:Long, val diffBlocked:Long, val diffNotRun:Long,
    val diffNotApplicable:Long, val health:AuditHealth
)
interface AuditStore {
    fun append(event:AuditEvent):Boolean
    fun page(cursor:String?=null, limit:Int=50):AuditPage
    fun find(invocationId:String):AuditRecord?
    fun children(parentInvocationId:String):List<AuditRecord>
    fun summary():AuditSummary
}
class FileAuditStore(
    baseDirectory:Path, knowledgeRoot:Path?, diagnostics:(String)->Unit={ System.err.println(it) }
):AuditStore
```

Append records use `schemaVersion:1` and the fields above; enums serialize to lowercase (`not_run`, `not_applicable`). Timestamps are UTC `Instant.toString()`, IDs are UUIDs. Entry points: `cli`, `chat`, `serve`, `dashboard`; commands: `validate`, `resolve`, `check`, `ask`, `edit`, `undo`, `discard`, `model`, `rule_create`, `rule_edit`, `rule_approve`, `rule_reject`. Stages: `parameters`, `knowledge_load`, `knowledge_validate`, `resolve`, `diff_read`, `standardizer`, `path_guard`, `branch_guard`, `citation_guard`, `baseline_guard`, `write`, `restore`, `model`. Categories: `hard`, `soft`, `dev_downgrade`, `usage`, `load_error`, `invalid_knowledge`, `conflict`, `path_guard`, `branch_guard`, `citation_guard`, `stale_baseline`, `stale_knowledge`, `model_failure`, `storage_error`.

`append` never throws into the caller: a storage failure returns false and emits a sanitized diagnostic to the injected stderr sink. Healthy or failed audit writes never alter kernel stdout or exit codes. Lifecycle STARTED without FINISHED remains unfinished forever; do not invent an end event when reading. A hard violation can be a COMPLETED check with exit 1 and BLOCKED standardizer; operational/model errors end FAILED. Missing stage evidence is NOT_RUN, never PASSED. Aggregate diff counts refer only to recorded top-level invocations, excluding model child calls and polling. Diff checked means the standardizer actually executed; diffBlocked is a subset, not an extra denominator category. Expose `total`, checked, not-run, not-applicable, unfinished with definitions in UI.

### B. Immutable knowledge and mutation service

Package `org.ftckb.knowledge`; reuse `KnowledgeRule`, `RuleViolation`, `RuleContext`, `ResolutionResult`, `Approver` from domain.

```kotlin
data class KnowledgeSourceError(val path:String, val message:String)
data class RuleSource(val path:String, val fileDigest:String, val ruleYaml:String)
data class KnowledgeSnapshot(
    val root:Path, val version:String, val rules:List<KnowledgeRule>,
    val violations:List<RuleViolation>, val loadErrors:List<KnowledgeSourceError>,
    val sources:Map<String,RuleSource>, val fileDigests:Map<String,String>
) { val valid:Boolean get() = violations.isEmpty() && loadErrors.isEmpty() }
enum class RuleMutationAction { CREATE, EDIT, APPROVE, REJECT }
data class RuleMutationRequest(
    val action:RuleMutationAction, val ruleId:String, val expectedVersion:String,
    val ruleYaml:String?=null, val approver:Approver?=null, val context:RuleContext?=null
)
data class RuleMutationPreview(
    val previewId:String, val action:RuleMutationAction, val ruleId:String,
    val baseVersion:String, val targetPath:String, val beforeYaml:String?, val afterYaml:String,
    val violations:List<RuleViolation>, val loadErrors:List<KnowledgeSourceError>,
    val resolution:ResolutionResult?, val approvalReset:Boolean, val canApply:Boolean
)
data class RuleMutationResult(val ruleId:String, val version:String, val action:RuleMutationAction)
class KnowledgeWorkspace(root:Path) {
    fun snapshot():KnowledgeSnapshot
    fun preview(request:RuleMutationRequest):RuleMutationPreview
    fun apply(previewId:String):RuleMutationResult
    fun acquireSnapshotLease(expectedVersion:String):AutoCloseable
}
class KnowledgeMutationException(val code:String, message:String):RuntimeException(message)
```

`ruleYaml` is an exact standalone single-rule YAML document (`schemaVersion: 4` plus `rules:` sequence), so all present fields are editable without a lossy partial DTO. Detail `RuleSource.ruleYaml` provides this editor document; source bytes outside the target rule stay exact. `expectedVersion` is mandatory even for create; server chooses new candidate path `candidates/<safe rule id>.yaml`, rejects an existing target, and never accepts client file paths. Normal edit cannot change ID or promote status; substantive/ownership changes on APPROVED become CANDIDATE with approval removed. Approval/rejection only operate on candidates and both use ApprovalPolicy with an explicit nonblank approver. Approval timestamps are server generated.

Snapshot version hashes sorted relative YAML paths plus exact file bytes, not mtimes alone; snapshot data are immutable and derived from those same captured bytes. Reject root/ancestor/leaf symlinks and paths escaping the selected root. Include every `.yaml`/`.yml` that the existing repository loader considers; guides remain readable but are not rewritten. Report malformed documents without silently replacing current state with the last valid snapshot. Ambiguous source IDs are never writable.

Preview validates the entire captured library with exactly one proposed file substitution, physically in a temporary copy or an equivalent captured-bytes full-library path that uses the same decoder/duplicate/RuleValidator rules. If context is supplied, invoke RuleResolver and include full resolution; unresolved context conflicts make canApply false. Without complete context, validation may pass but do not claim all contexts are conflict-free. Preview cache is bounded, expires after 10 minutes, and binds action, exact replacement bytes, full-library version and target digest. Apply accepts only preview ID, reacquires a cross-process lock outside YAML scanning, reloads/revalidates the entire snapshot, checks full version and target digest, and atomically replaces the single target; it never falls back to truncate/write. Same-directory temporary files use a non-YAML suffix and are cleaned on failure. Any stale/expired preview returns `stale_preview`; invalid inputs return `invalid_rule`, policy denial `approval_denied`, path failures `unsafe_path`, IO failures `write_failed`.

`acquireSnapshotLease` acquires the same per-canonical-root process mutex and OS lock as apply, then reloads and verifies the expected version. On failure it releases both locks before throwing. On success its returned AutoCloseable owns both until close. The caller must close on every path. This short lease covers the whole code-write batch, never model inference. Reentrant acquisition within the same thread must not deadlock; a shared per-root lock registry avoids OverlappingFileLockException between workspace instances in one JVM. Noncooperating external editors cannot be locked out; exact bytes/version are checked again at each mutation boundary and stale observations abort/rollback rather than claiming transactional control of arbitrary external software.

### C. Runtime prewrite injection and snapshot retrieval

Package `org.ftckb.agent.edit`. Do not import standardizer, audit, session-shell or CLI into agent-runtime.

```kotlin
data class EditGateNotice(
    val kind:String, val ruleId:String?, val check:String?, val path:String?,
    val line:Int?, val detail:String
)
data class EditGateResult(val allowed:Boolean, val notices:List<EditGateNotice> = emptyList())
interface EditPrewriteGate {
    fun inspect(preview:ValidatedEditBatch):EditGateResult
    fun beginWrite(preview:ValidatedEditBatch):AutoCloseable = AutoCloseable {}
    fun verifyCurrent(preview:ValidatedEditBatch)
}
class EditGateException(val code:String, val notices:List<EditGateNotice>, message:String):RuntimeException(message)
```

Add optional `prewriteGate:EditPrewriteGate?=null` to the end of EditAgent constructor for existing direct test/embedded consumers; production chat/serve assembly must always supply it. Run inspect on the accepted preview before history.applyAndRecord. A blocked decision throws EditGateException and writes nothing. Call beginWrite after inspect and wrap the whole history.applyAndRecord call in its lease's use block; E's implementation obtains the knowledge lease above. Combine verifyCurrent with the existing authorization guard so it executes at the engine's final mutation boundary as well as before apply. Preserve native path, citation, branch, content/permission race checks and rollback. Return every soft/dev notice in EditReport (append a default `gateNotices:List<EditGateNotice> = emptyList()` field) and existing human warnings as needed; structured notices are the stable UI/audit channel.

KnowledgeRetriever adds a companion factory `fromSnapshot(snapshot:KnowledgeSnapshot, context:RuleContext, guideLimits:GuideTraversalLimits=GuideTraversalLimits()):KnowledgeRetriever`. It validates snapshot validity, uses RuleResolver once for that snapshot/context, rejects conflicts, and never silently reloads different rules. Keep existing constructor source-compatible. A per-request graph may be reassembled around the same ConversationState, EditHistory, RepositoryIndex and Git branch state; do not use the existing reconfigure operation that rejects outstanding edits to refresh knowledge.

C also adds `@Synchronized fun SessionController.replaceAgents(askAgent:AskAgent, editAgent:EditAgent)`, changing only the two agent references. Preserve currentMode, authorizedBranch, historyBranch, history and repository index. E calls it after assembling a valid next-request graph. A failed refresh must reject the request, not proceed using stale agents. Existing FileEditEngine checks each touched file's before state at its own write boundary; verifyCurrent must not incorrectly compare every original before state after earlier files in the same batch have already changed.

Guard evidence must survive existing catch/rejection paths. Add this minimal observer in `org.ftckb.agent`; it has no audit dependency:

```kotlin
enum class RuntimeStageStatus { PASSED, BLOCKED, NOT_APPLICABLE }
data class RuntimeStageEvidence(
    val name:String, val status:RuntimeStageStatus,
    val category:String?=null, val detail:String?=null
)
fun interface RuntimeStageObserver { fun observe(evidence:RuntimeStageEvidence) }
```

Append optional `stageObserver:RuntimeStageObserver=RuntimeStageObserver {}` to EditAgent and SessionController constructors, and the engine if necessary for exact path observations. Emit bounded fixed evidence at actual citation/path/branch/recovery guard boundaries before generic EditValidationException/RejectedResult conversion. Add typed `EditPathException` in SafeEditPath to distinguish path rejection; do not classify by parsing exception prose. A generic model plan validation failure can remain unclassified without fabricating a path failure. Controller receives a stable E callback reading the current request scope; replaceAgents does not replace that callback. Observer exceptions cannot change edit behavior. E maps this evidence to AuditStage; absent observations remain NOT_RUN. A repair retry may produce multiple stage events, so historical event storage retains both failed and successful attempts even when the projected record shows the final state.

### D/F. Dashboard API contract

`DashboardOptions(knowledge:Path, port:Int=0, noBrowser:Boolean=false)`; `runDashboardCommand(args:List<String>, out:PrintStream):Int`; accepted syntax `ftckb dashboard --knowledge PATH [--port N] [--no-browser]`. Missing/invalid options exit 64. Startup/load failure exits 2; an invalid but readable library may start and display its validation errors. No provider config/key is loaded. Server binds literal 127.0.0.1, prints `dashboard=ok` and a URL with a fragment token `http://127.0.0.1:PORT/#token=...`; the frontend keeps it in session memory and removes it from URL immediately.

Every `/api/dashboard/*` request uses `Authorization: Bearer <token>`. JSON responses use `{apiVersion:1,ok:true,...payload}`; errors use `{apiVersion:1,ok:false,error:{code,message,violations?:[...]}}`. Enum strings use existing kernel conventions, except audit enums follow A. All reads/writes have `Cache-Control: no-store`. No query token or permissive CORS. Validate Host against actual loopback port; reject foreign Origin and `Sec-Fetch-Site: cross-site`. Every POST requires exactly the server Origin and `application/json`, bounded request body (1 MiB). HTTP clients in tests supply explicit Origin. Return 401 token failures, 403 origin/host failures, 405 method mismatch, 400 malformed request, 409 stale preview, 422 invalid rule/context or unauthorized role, 500 sanitized IO failure.

| Method and route | Request | Payload on success |
| --- | --- | --- |
| GET `/api/dashboard/summary` | none | `knowledge:{root,version,valid,ruleCount,approved,candidate,errors:[{path?,ruleId?,field?,message}]}`, `audit:{...AuditSummary}`, `unscopedAudit:{...AuditSummary}`, `serverTime` |
| GET `/api/dashboard/rules` | none | `version`, `rules:[RuleDto]`; current catalog is small; filter locally |
| GET `/api/dashboard/rule?id=...` | URL-encoded ID | `version`, `rule:RuleDto`, `source:{path,fileDigest,ruleYaml}` |
| POST `/api/dashboard/resolve` | `{context:ContextDto}` | `version`, `resolution:ResolutionDto` |
| POST `/api/dashboard/preview` | `{action,ruleId,expectedVersion,ruleYaml?,approver?,context?}` | `preview:PreviewDto` |
| POST `/api/dashboard/apply` | `{previewId}` | `ruleId`, `version`, `action` |
| GET `/api/dashboard/audit` | optional `cursor`, `limit` (1–100), `scope=knowledge|unscoped` | `items:[AuditRecord]`, `nextCursor`, `revision`, `health` |
| GET `/api/dashboard/audit/detail?id=...` | optional same `scope` | `record:AuditRecord`, `children:[AuditRecord]` |
| POST `/api/dashboard/validate` | `{}` | `version`, `valid`, `violations`, `loadErrors`, `invocationId` |
| POST `/api/dashboard/check` | `{context:ContextDto,diff:string}` | `version`, `passed`, `violations`, `soft`, `invocationId` |
| POST `/api/dashboard/shutdown` | `{}` | empty; server shuts down after response |

`RuleDto` has exactly the semantic properties emitted for a rule by KernelJson (id/topic/title/instruction/rationale/status/authority/policyLevel/applicability/evidence/approval/supersedes/positiveExample/negativeExample/checks/reviewTriggers), with absent nullable fields represented by null; add `checkMode: hard|conditional_soft|unconditional_soft`. D owns a separate serializer and does not modify KernelJson. `ContextDto={team:string,season:string,profiles:string[],workMode:"normal"|"test"|"dev"}`: profiles must be present, [] explicitly selects generic. Validate exact canonical team/season and RuleProfiles; never guess. `ResolutionDto={activeRules:RuleDto[],excludedRules,overriddenRules,conflicts,profiles}` uses existing resolver results without reinterpretation. `PreviewDto` is RuleMutationPreview with resolution serialized using ResolutionDto. Approval DTO is `{id,role:"overall_software_lead"|"team_software_lead",team?:string}`. Action strings are create/edit/approve/reject.

`check` checks only the explicitly supplied patch using Standardizer.parsePatch; no API may read an arbitrary repository/path. Empty explicit patch is valid but still may yield path-required checks; the UI labels it as a supplied-patch check. dev requires a diff field and preserves every downgraded finding in soft. `resolve`, manual validate/check and rule mutations create audit events; summary/catalog/detail/audit polling do not. Mutation audit records action and approved rule ID/summary, never the full edited YAML. Apply links the snapshot it actually changed; a failed apply is visible.

---

## Task A1: Durable event append and incremental audit index

**Files:**
- Create `modules/audit/build.gradle.kts`.
- Create `modules/audit/src/main/kotlin/org/ftckb/audit/AuditModels.kt`, `FileAuditStore.kt`, `AuditJson.kt`, `AuditPaths.kt`.
- Create `modules/audit/src/test/kotlin/org/ftckb/audit/FileAuditStoreTest.kt`, `AuditProcessWriter.kt`.
- Modify `settings.gradle.kts` to include `:modules:audit`.

**Interfaces:** Consume domain values only when forming snapshots; produce every A public type/method above. Add `AuditPaths.baseDirectory(environment:(String)->String?=System::getenv):Path` so all entrypoints use exactly the same override. Never resolve the audit base under knowledge YAML files by default.

- [ ] Add a minimal event fixture using the exact API and assert lifecycle projection:

```kotlin
val store = FileAuditStore(tempDir, knowledgeRoot)
val id = "00000000-0000-0000-0000-000000000001"
check(store.append(AuditEvent(id, AuditEventType.STARTED, "2026-10-19T00:00:00Z",
    metadata=AuditMetadata("cli", "check", knowledgeRoot.toString()))))
check(store.find(id)!!.status == AuditRunStatus.STARTED)
check(store.summary().unfinished == 1L)
check(store.append(AuditEvent(id, AuditEventType.STAGE, "2026-10-19T00:00:01Z",
    stage=AuditStage("standardizer", AuditStageStatus.BLOCKED, "hard"))))
check(store.append(AuditEvent(id, AuditEventType.FINISHED, "2026-10-19T00:00:02Z",
    status=AuditRunStatus.COMPLETED, exitCode=1, durationMillis=2000)))
check(FileAuditStore(tempDir, knowledgeRoot).find(id)!!.exitCode == 1)
```

- [ ] Run `./gradlew :modules:audit:test --tests '*FileAuditStoreTest'`; before implementation it must expose missing code or the failing assertion.
- [ ] Implement one JSONL event per line under process mutex + FileChannel lock, write the complete buffer and force before releasing. Set private POSIX permissions when supported, avoid symlink destinations. Bound event size; failure is diagnostic plus false, never silent success. Unknown schema versions and malformed complete lines are health warnings; an unterminated tail is ignored for projection and reported. Do not delete corrupt history; if appending after an interrupted tail, isolate it with a newline under the lock so future good events stay readable.
- [ ] Build an in-memory projection and index (by invocation and parent) by reading only bytes after the last processed offset. Partial trailing bytes stay buffered until completed. Detect truncate/replace via file identity/length and rebuild once with a health warning. List pages use stable opaque cursors based on starting invocation order; new events must not skip older records. Summary checks new length/offset, not a full log scan every two seconds.
- [ ] Add real subprocess tests: two JVM writers append at least 50 independent invocations each; all IDs exist exactly once and all lines parse. Add interrupted-tail, corrupt-middle, unwritable destination, restart, more than two pages, child lookup, distinct-root partitions and redacted diagnostic tests. Assertions are record counts/status and file preservation, not timing-only guesses.
- [ ] Run only A's targeted tests and hand E/D the real implementation paths and exact commands/results. Do not edit consumers or fabricate model histories.

## Task B1: Immutable source snapshots with full-library validation

**Files:**
- Create `modules/knowledge/src/main/kotlin/org/ftckb/knowledge/KnowledgeSnapshot.kt`, `KnowledgeWorkspace.kt`, `RuleSourceEditor.kt`, `KnowledgeWorkspaceLock.kt`.
- Modify `RuleYamlCodec.kt` only for source-span/one-rule serialization helpers needed by the workspace; retain existing decoder behavior.
- Create `modules/knowledge/src/test/kotlin/org/ftckb/knowledge/KnowledgeWorkspaceTest.kt`, `RuleSourceEditorTest.kt`.

**Interfaces:** Produce B contract including snapshot lease; do not change domain resolver/approval semantics. Preserve FileKnowledgeRepository.load and RuleYamlCodec.decode callers and legacy error behavior.

- [ ] Copy small real YAML documents into a JUnit @TempDir; use at least two rules in one file and another file with comments. Assert a snapshot's rules and version derive from identical captured bytes and a byte edit changes version even if file mtime is restored.
- [ ] Run `./gradlew :modules:knowledge:test --tests '*KnowledgeWorkspaceTest' --tests '*RuleSourceEditorTest'` and observe the initial failing cases.
- [ ] Capture each safe relative YAML path once into immutable bytes, compute sorted tree digest, decode those bytes, locate rule spans through parser marks/events rather than a regex matching `- id:` inside multiline examples, then perform duplicate-ID and RuleValidator checks on the whole rule set. Snapshot of malformed external YAML must report valid=false with current version and file error.
- [ ] Verify source spans with block scalars containing fake `- id:` text, quoted IDs, comments, CRLF, and duplicate IDs. Assert ambiguous/unsupported source shapes fail safely before a write, with an actionable error; do not silently reserialize the whole file.

## Task B2: Preview, approval reset, atomic save and concurrency

**Interfaces:** Consume B snapshots and existing `ApprovalPolicy.authorize(authority, teams, approver)`; produce preview/apply/lease from B.

- [ ] Add observable mutation cases: create candidate, edit candidate, edit approved rule clears approval, metadata/authority/policy/applicability edits reset approval, ordinary edit rejects ID change or status promotion, approval succeeds only for the declared allowed role/team, rejection changes only the targeted candidate, invalid YAML and duplicate IDs leave exact original bytes.
- [ ] For the approved edit case, assert the semantic effect, not just serialization:

```kotlin
val preview = workspace.preview(RuleMutationRequest(
    RuleMutationAction.EDIT, approvedId, workspace.snapshot().version, editedOneRuleYaml
))
check(preview.approvalReset && preview.canApply)
workspace.apply(preview.previewId)
val saved = workspace.snapshot().rules.single { it.id == approvedId }
check(saved.status == RuleStatus.CANDIDATE && saved.approval == null)
```

The test fixture defines approvedId and editedOneRuleYaml from an actual approved fixture before this assertion; do not use the production knowledge tree.

- [ ] Build bounded preview entries containing validated exact replacement bytes. For APPROVE/REJECT surgically change status/approval while preserving surrounding rules and comments. For EDIT preserve every byte outside the target source span; retain comments in the provided editor document. Use full-tree validation, not only RuleValidator on the edited rule.
- [ ] Acquire workspace lock, reread whole-library digest and target digest, validate again, create a same-directory temporary file with non-YAML suffix, force its bytes, perform ATOMIC_MOVE and clean it on failure. Refuse atomic replacement unavailable errors rather than falling back to destructive truncation. Approval timestamps are captured once in preview, not re-generated at apply.
- [ ] Test stale preview caused by changing a different rule file, stale target bytes, duplicate apply, expired preview, symlink parent/target, `../`/absolute paths in IDs, source parse failure, and injected write failure. All failure cases assert originals unchanged and no temp YAML left behind. Test a separate process cannot apply a rule mutation until a held snapshot lease closes; assert a thrown failure also releases the lease.
- [ ] Rerun only the targeted knowledge tests plus existing `RuleYamlCodecTest` and `FileKnowledgeRepositoryTest` if those files changed. Provide E/C the fromSnapshot-compatible types and D a real single-rule YAML response example.

## Task C1: Gate accepted edits and preserve request state

**Files:**
- Create `modules/agent-runtime/src/main/kotlin/org/ftckb/agent/edit/EditPrewriteGate.kt`.
- Create `modules/agent-runtime/src/main/kotlin/org/ftckb/agent/RuntimeStageObserver.kt`.
- Modify `modules/agent-runtime/src/main/kotlin/org/ftckb/agent/edit/EditAgent.kt`, `org/ftckb/agent/KnowledgeRetriever.kt`, `org/ftckb/agent/SessionController.kt`.
- Modify `modules/agent-runtime/src/main/kotlin/org/ftckb/agent/edit/SafeEditPath.kt` for typed path rejection, preserving existing messages and exception superclass compatibility.
- Modify/create `modules/agent-runtime/src/test/kotlin/org/ftckb/agent/edit/EditAgentTest.kt`, `EditPrewriteGateTest.kt`, `org/ftckb/agent/SessionControllerTest.kt`, `KnowledgeRetrieverTest.kt`.

**Interfaces:** Consume B KnowledgeSnapshot; produce C gate/notice/observer types, EditReport.gateNotices, KnowledgeRetriever.fromSnapshot and SessionController.replaceAgents. Do not implement an independent Standardizer or audit writer here.

- [ ] Add a rejecting fake gate and assert that an otherwise-valid model plan invokes inspect but never changes file bytes/history. A throwing beginWrite/verifyCurrent must have the same no-write/rollback result. Add a lease fake counting closes and assert exactly one close on success and every failure.
- [ ] Run `./gradlew :modules:agent-runtime:test --tests '*EditPrewriteGateTest' --tests '*EditAgentTest' --tests '*SessionControllerTest' --tests '*KnowledgeRetrieverTest'` to expose missing hooks before implementing.
- [ ] Wire this order without changing plan/citation/path semantics:

```kotlin
val decision = prewriteGate?.inspect(selected.preview)
if (decision != null && !decision.allowed) {
    throw EditGateException("hard_violation", decision.notices, "Edit blocked by rule checks")
}
val guard = { authorizationGuard(); prewriteGate?.verifyCurrent(selected.preview); Unit }
val applied = (prewriteGate?.beginWrite(selected.preview) ?: AutoCloseable {}).use {
    guard()
    history.applyAndRecord(selected.preview, guard)
}
```

Append decision notices to the returned EditReport. Exceptions thrown at the engine write boundary may be wrapped in FileEditApplyException; expose the classified gate failure to E only after checking rollback status. Do not report “no files changed” when rollback failures exist.

- [ ] Add snapshot-backed KnowledgeRetriever construction with an immutable active rule list. Preserve guide root safety and existing constructor. Add synchronized controller agent replacement; replacing agents may not alter authorization/history fields. Tests enter edit mode on a named branch, perform one edit, replace agents, perform another edit, and undo both using existing history. A branch switch still blocks the second edit/undo.
- [ ] Capture RuntimeStageEvidence at the actual citation/path/branch/restore boundaries, including before exceptions are reduced to generic rejection. Test invalid citation, forbidden/symlink path, branch change, recovery conflict and successful undo; assert the correct fixed category and no false PASSED stage. Observer failures must not bypass or break guards. Existing model content never becomes persisted detail text.
- [ ] Reuse existing FileEditEngine beforeMutation/beforeWrite test injection to change knowledge or file bytes between preview and write; verify per-file checks and rollback survive. Include a two-file successful edit to catch erroneous whole-preview baseline rechecks after the first mutation.
- [ ] Rerun the targeted C tests once after final C changes; report unused/unobserved guard stages honestly for external consumers that intentionally instantiate EditAgent without a gate.

## Task D1: Authenticated dashboard API and no-key launcher

**Files:**
- Create `apps/knowledge-cli/src/main/kotlin/org/ftckb/cli/DashboardOptions.kt`, `DashboardCommand.kt`, `DashboardApi.kt`, `DashboardJson.kt`.
- Create `apps/knowledge-cli/src/test/kotlin/org/ftckb/cli/DashboardOptionsTest.kt`, `DashboardApiTest.kt`, `DashboardSecurityTest.kt`, `DashboardMutationTest.kt`.

**Interfaces:** Consume A/B plus existing RuleResolver/Standardizer; produce D/F HTTP contract and `runDashboardCommand`. E performs Main registration and dependencies, F owns static resources.

- [ ] Add HTTP tests with a temporary knowledge root, port 0 and injected audit base. Start with all provider env/config absent; assert listener is 127.0.0.1 and catalog/summary work. A readable invalid library still serves its current errors and disables dependent resolve/apply actions.
- [ ] Add security matrix: missing/wrong token (401), token only in query (401), foreign Origin/Host or cross-site fetch (403), write via GET (405), missing Origin on POST (403), wrong content type (400/415, consistently documented), >1 MiB body rejected before parsing, unknown field/action/context failures (400/422), unsafe source path rejected, and static path traversal rejected. Do not expose token through audit/error JSON.
- [ ] Serve only packaged dashboard index/css/js assets; use no-store API headers, text-safe JSON, restrictive CSP (`default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'`) and Referrer-Policy no-referrer. Fragment token is absent from HTTP requests/logging. Explicitly use IPv4 127.0.0.1 rather than InetAddress.getLoopbackAddress, which may choose IPv6.
- [ ] Implement routes from the frozen table. Validate all context fields including explicit profiles array; call domain resolver and existing Standardizer. Serialize rule metadata independently of KernelJson. Use source-provided errors with field paths; never claim full-context validity from a catalog-only load.
- [ ] Audit manual validation/resolution/check, mutation preview attempts and apply outcomes with corresponding categories and snapshots. Avoid double-counting preview as a completed file write: preview stage is separate, apply records the actual write transaction. Read routes append no events. `audit/detail` uses store.find and store.children.
- [ ] Test actual mutation preview/apply over HTTP (valid edit, automatic approval reset, role denial, stale preview 409, validation failure 422, external invalid YAML) and check outcomes hard/soft/dev/malformed diff. POST a real patch to test Standardizer, not precomputed UI results.
- [ ] Run only `./gradlew :apps:knowledge-cli:test --tests '*DashboardOptionsTest' --tests '*DashboardApiTest' --tests '*DashboardSecurityTest' --tests '*DashboardMutationTest'`; hand F one live server URL from a temporary library and payload examples. Do not store credentials in fixtures.

## Task E1 (kernel_audit): Existing CLI audit without changing the kernel contract

**Files:**
- Modify `apps/knowledge-cli/src/main/kotlin/org/ftckb/cli/Main.kt`, `apps/knowledge-cli/src/main/kotlin/org/ftckb/cli/CheckCommand.kt`.
- Create `apps/knowledge-cli/src/main/kotlin/org/ftckb/cli/KernelAudit.kt` and additional `KernelAudit*.kt` only as needed.
- Create `apps/knowledge-cli/src/test/kotlin/org/ftckb/cli/KernelAuditAcceptanceTest.kt`, `KernelAuditCompatibilityTest.kt`.
- Ask E2 to wire required dependencies; E1 must not edit `apps/knowledge-cli/build.gradle.kts`.
- Do not modify KernelJson, JSON Schema, old fixtures or versions to make differences disappear.

**Interfaces:** Consume A/B; register D entrypoint; optional constructor/function injection parameters are appended with defaults so existing tests/callers compile. Audit root selection is shared AuditPaths, not a new CLI flag that changes accepted kernel arguments.

- [ ] Before changing existing logic, capture stdout bytes and return codes for validate/resolve/check success and all current error families in temporary files or test resources derived from the base. Include normal default vs explicit normal, test, dev, missing root, invalid YAML/rule, conflict, invalid profile, missing context, malformed patch, staged/unstaged/untracked changes, hard and soft outcomes. Retain actual line endings; do not compare parsed JSON alone.
- [ ] Wrap top-level invocations in STARTED/finally FINISHED events and add explicit stage observations at actual execution points. Parameters failing before root discovery are unscoped. `validate` diff is not_applicable; `resolve` never claims diff checked; `check` marks diff_read/standardizer only after those operations actually execute. Capture immutable rule summary and source version matching the loaded rule set.
- [ ] Keep existing stdout construction, field order and returns untouched. Do not append audit IDs to kernel JSON. Use independent stderr diagnostics for storage failure; storage availability cannot change kernel status. Help/version do not become misleading check invocations. Add dashboard dispatch/help separately from kernel branches.
- [ ] Test an unwritable audit directory with a successful kernel operation: identical stdout bytes/exit, stderr warns. Test concurrent separate CLI processes with the same FTCKB_AUDIT_DIR and knowledge root; dashboard sees both. Test load/usage/conflict failures retain completed stages and leave later stages not_run.
- [ ] Run `./gradlew :apps:knowledge-cli:test --tests '*KernelAuditAcceptanceTest' --tests '*KernelAuditCompatibilityTest' --tests '*KernelJsonAcceptanceTest' --tests '*CheckAcceptanceTest' --tests '*WorkModeAcceptanceTest' --tests '*ProfileArgumentsTest'`. Once these pass, do not rerun unrelated module tests.

## Task E2 (dashboard_integration): Request refresh, model tracing, and production prewrite adapter

**Files:**
- Modify `modules/session-shell/build.gradle.kts`, `modules/session-shell/src/main/kotlin/org/ftckb/session/SessionRuntime.kt`, `AskChatSession.kt`, `SwapModelProvider.kt` as needed.
- Create `modules/session-shell/src/main/kotlin/org/ftckb/session/RequestAudit.kt`, `StandardizerEditGate.kt`.
- Modify `apps/knowledge-cli/src/main/kotlin/org/ftckb/cli/ProductionChatLauncher.kt`, `ChatRepl.kt`, `ServeCommand.kt`, `ServeApi.kt`.
- Create `modules/session-shell/src/test/kotlin/org/ftckb/session/RequestAuditTest.kt`, `StandardizerEditGateTest.kt`, `RequestKnowledgeRefreshTest.kt`.
- Extend targeted CLI `ServeAcceptanceTest.kt`, `EditReplAcceptanceTest.kt` or add `SessionAuditAcceptanceTest.kt`.

**Interfaces:** Consume A/B/C. Session-shell depends on audit and standardizer; agent-runtime remains independent of both. Preserve existing session APIs; production callers route user asks/submits/history commands through one request boundary so each actual request creates one parent ID. Prevent nested wrappers from double-counting the same request.

- [ ] Build one synchronized per-request execution boundary. At request start, capture current explicit context, refresh/revalidate a B snapshot, resolve it, rebuild retrieval/ask/edit agents around the existing conversation/index/history, then call controller.replaceAgents. If refresh is invalid/conflicted, return that failure before calling any model; never run the stale previous graph. Do not change provider/profile/team/season or edit mode as a side effect of a refresh.
- [ ] Give the request a new UUID, start audit before refresh, and set/clear request scope in try/finally. A serial session boundary or correctly propagated request scope must guarantee asynchronous serve requests do not cross-link. The provider wrapper starts a `model` child under that request, records success/failure and nullable input/output usage, then returns/throws unchanged. Distinguish retrieval/answer/edit/summary model phases when known; unknown phase remains `model`, never guess from prompt contents. Do not persist request/response messages.
- [ ] Adapt RuntimeStageObserver to AuditStage using the current request scope; the long-lived controller uses a stable callback and each new edit agent uses it too. Record actual guard failures before CLI/serve translates them to user-facing rejection. Do not infer path/citation/branch passes from generic success or parse exception text to guess a category.
- [ ] Implement StandardizerEditGate.inspect by rendering the exact ValidatedEditBatch before/after text via AgentDiffRenderer and parsing its real unified diff with Standardizer.parsePatch. The batch includes both source and destination of moves and deletion paths; do not evaluate only final nonempty contents. Evaluate all active rules from the same request snapshot, not only retrieved/cited rules. Record hard and soft hits plus rule summaries in the parent audit.
- [ ] beginWrite acquires B snapshot lease against request version. verifyCurrent checks observed knowledge version and relevant repository identity/branch/head baseline without re-reading current rules into the prior evaluation. FileEditEngine retains final touched-file before-state checks. On any stale condition abort, close lease, preserve rollback diagnostics, and require a new request/preview. Cooperating dashboard saves wait until lease closes.
- [ ] Existing chat/serve are normal mode unless an explicit user-facing per-request workMode is supplied. Do not infer test/dev from paths or natural-language filenames. If adding explicit mode input, give chat a one-shot `/work-mode normal|test|dev` command consumed by the next submitted request and serve an optional `workMode` on submit/ask only; default normal on each new request and never save it in settings. test uses the existing RuleContext behavior. dev must evaluate the explicit generated preview diff, preserve all downgraded hard hits and state that it is dev; it cannot relax a whole dirty checkout or a malformed/conflicted rule set. Coordinate exact optional UI exposure with the owner of existing serve UI; dashboard workMode is independently explicit in ContextDto.
- [ ] Return structured gate notices in serve edit JSON and print them in chat. Hard rejection must include rule/check/path/line/detail; soft is visible with no false claim of human review. If an exception occurs after partial mutation with rollback failure, audit and UI say so; do not collapse it into generic “nothing changed”.
- [ ] Wrap undo/discard in their own recovery records. Record actual branch/path/conflict checks, restore result and standardizer not_applicable (recovery), not a synthetic edit-check pass. Ask marks diff not_applicable and actual validate/resolve/model stages. Branch/path/citation rejections have explicit category; absent instrumentation stays not_run.
- [ ] Add deterministic fake-provider tests for: ask + two model child calls; model failure with finally cleanup; normal valid edit; hard blocked edit leaves exact bytes/history; soft edit succeeds and reports all notices; explicit test exemptions preserve safety rules; explicit dev downgrade retains locations; rule update between requests becomes active; invalid update rejects next request; knowledge update after preview aborts; two-file edit; unchanged request state preserves edit mode/history; restore success/conflict. Sentinels representing prompt, token, key and raw diff must not appear in persisted audit files.
- [ ] Run only `./gradlew :modules:session-shell:test --tests '*RequestAuditTest' --tests '*StandardizerEditGateTest' --tests '*RequestKnowledgeRefreshTest' :apps:knowledge-cli:test --tests '*SessionAuditAcceptanceTest' --tests '*ServeAcceptanceTest' --tests '*EditReplAcceptanceTest' --tests '*ChatReplTest'`. Use test selection only for classes actually created; do not treat a “no tests found” result as passing evidence.

## Task F1: Complete local Chinese dashboard

**Files:** Create `apps/knowledge-cli/src/main/resources/dashboard/index.html`, `dashboard.css`, `dashboard.js`. Split JS into additional same-directory files only if each has a focused responsibility and D serves them explicitly.

**Interfaces:** Consume D/F API exactly; no alternate mock backend or client-side priority resolver. Format rules/audit as text via textContent, createElement and explicit safe attributes. Never inject rule text as innerHTML.

- [ ] Build the four views with shared context, connection status, knowledge path/version and last success time. Match approved colors and hierarchy, system Chinese typography, monospace IDs/paths, responsive split-pane desktop and single-column narrow layout. Use labeled native inputs/buttons, visible focus and keyboard navigation, aria-live for meaningful errors/refresh notices, reduced motion support.
- [ ] Implement catalog search over ID/title/instruction/rationale and filters for status, authority, policyLevel, team/season/profile and hard/conditional/unconditional soft. Authority/policy/applicability have independent labels. Detail shows examples, evidence, approval, checks/triggers and source; links are allowed only for safe http/https evidence URLs and use noopener.
- [ ] Build one-rule YAML editor for complete field coverage with clear field guidance and separate approver/role/team controls. New candidate uses a valid schemaVersion 4 document skeleton. On preview display before/after text, exact validation errors, approval reset notice, resolution changes/conflicts and target path. Save is enabled only for current canApply preview. Any input edit invalidates preview; stale-save response retains user draft and requests a fresh preview. Approve/reject also require a preview before apply, with explicit role-declaration explanation.
- [ ] Context selection requires team, season and explicit generic/named profile selection. Without it show catalog counts only; never invent active counts. Render RuleResolver reasons/winner IDs/conflict IDs exactly with human Chinese labels. Work mode starts normal and is transient; any dev result retains prominent downgraded notices.
- [ ] Build audit list, pagination, selected record detail, stage trajectory and model children. Include source root/version/time, command/lifecycle/exit/duration, active snapshot details and hit locations. Use categories to distinguish hard, soft, dev, stale, usage, invalid knowledge, conflict, branch/path and model failures. An unfinished record says 未完成; absent checks say 未执行; prior snapshots are not replaced by current catalog metadata.
- [ ] Poll summary every 2000 ms using a single nonoverlapping in-flight request; abort/ignore outdated response after navigation or context change. When only audit revision changes, reload current page/detail; when knowledge version changes, reload catalog and invalidate previews. Do not fetch every history page on a poll. Failed polls retain last data and visibly mark stale/disconnected; successful reconnect clears status. Poll reads create no audit records.
- [ ] Show exact limitation text near coverage: “仅统计本版本记录的调用；审计启用前、其他固定版本、未接入进程和绕过 CLI 的行为不可观测。resolve 不代表已检查 diff，soft 不代表人工复核完成。” Show counts with definitions, not an invented global Agent compliance percentage. Label unscoped audit separately and never combine it into knowledge-specific claims.
- [ ] Check against live temporary D server: search/filter, full detail, context, new candidate, invalid preview, edit/save, approval reset, approve/reject, external edit refresh, check hard/soft/dev, audit details/children, pagination and disconnect/reconnect. Capture desktop and 390px screenshots and keyboard flow evidence. No test-only fake pass badges or preloaded audit data in shipped resources.

## Task G1: Targeted integration verification and user documentation

**Files:**
- Create `docs/dashboard.md` and `docs/superpowers/verification/2026-10-19-dashboard.md`.
- Modify README.md, AGENTS.md, docs/cli-agent.md, docs/standardizer-check.md only to document actual implemented behavior and current tested counts/limits; do not change kernel schema docs/fixtures when contract remains v2.
- Add a focused browser or HTTP smoke script under `scripts/` only if it is used for the acceptance run; avoid broad framework setup.

**Interfaces:** Consume completed A–F. Review is independent, but reviewers do not seize implementation ownership. Report concrete findings to the responsible owner and rerun only checks affected by fixes.

- [ ] Record implementation changes and package test evidence as they arrive. The pre-change 482-test baseline stays a separate historical baseline entry. Do not sum rerun counts or claim the full suite tested the final source.
- [ ] Build the distribution once after code integration (`./gradlew :apps:knowledge-cli:installDist`) and run `FTCKB_AUDIT_DIR=<temporary-directory> apps/knowledge-cli/build/install/ftckb/bin/ftckb validate knowledge --json`; expect `ok:true` and 48 rules if production rule files stayed unchanged. This validates actual rules and the current distribution without rerunning every test.
- [ ] Exercise the installed CLI with a temporary FTC repository and knowledge copy, then open the dashboard for that same copy. Run a real separate-process check that is hard-blocked, a soft case, a malformed-argument case, and a manual dashboard patch check; verify new records appear on polling with honest stage states. Verify legacy normal JSON bytes/exit via E's captured compatibility matrix.
- [ ] Run real-browser scenarios from F, including malicious rule text `<img src=x onerror=alert(1)>` rendered as text, token removal from address, keyboard-only edit/preview/save, narrow layout without clipped controls, and server stop/reconnect. Use temporary files only; browser coverage must be reported as actual interaction evidence, not screenshots of static fixtures.
- [ ] Security review checks server binding, API auth/Origin/Host, restricted routes/body sizes, source-path/symlink/CAS/atomic write, approval role declaration, audit redaction and full-cycle lock release. Performance review checks summary polling reads appended bytes, not whole history, and no overlapping requests.
- [ ] Recheck actual `git diff --stat` and relevant untracked files to ensure unrelated user work remains untouched. Document startup command, FTCKB_AUDIT_DIR behavior, no-key operation, rule lifecycle/reset/approval declaration, supplied-patch checking, request/model tracing, recovery transactions and observability limits.
- [ ] Final report contains implemented functions, exact targeted commands and pass/fail counts, browser flows and screenshots, known limitations/uncovered entrypoints, unchanged kernel v2 evidence, real knowledge validation, and no commit/push. Stop repeating tests once affected checks pass unless new edits or unresolved failures justify another targeted run.

## Review gates and integration risks

1. **A/B contracts before consumers merge:** real signatures, lock semantics and serialization examples must match this plan. A missed API field is corrected with owner coordination, not a consumer-only workaround.
2. **No false compliance:** every displayed stage is observed or explicitly not-run/not-applicable. Ask/resolve/restore/model child calls never inflate checked-diff coverage. Soft/dev are visible findings; storage failure remains visible.
3. **One snapshot per decision:** rules used for retrieval, resolver, gate and historical summaries originate from one request snapshot. Preview CAS includes the whole library. Cooperating mutations serialize against active write leases; arbitrary external editors remain outside that lock, requiring boundary rechecks and truthful residual limits.
4. **No lost edit state:** session refresh does not reset mode/branch authorization/history/conversation. Existing direct consumers without production wiring are not included in the claim that chat/serve writes are gated.
5. **No output drift:** exact old kernel stdout/exit snapshots decide compatibility. Cosmetic JSON reserialization, extra IDs or changed diagnostic wording are failures unless confined to the new dashboard API.
6. **Atomic knowledge mutation:** source span handling must survive block scalars and preserve unrelated bytes/comments. Invalid library state, unsafe paths, stale versions or IO failures do not truncate a rule file.
7. **Limited testing honored:** baseline is reused; package and critical integrated checks are sufficient for this change unless a specific failure requires broader affected coverage. No automatic full suite rerun.

## Spec coverage checklist

- [ ] Independent no-key dashboard, Chinese responsive UI and local assets: D/F/G.
- [ ] Rule search, complete metadata, create/edit/applicability, approval/rejection, reapproval reset, diff preview/full validation/CAS/atomic preservation: B/D/F.
- [ ] Explicit team/season/profile/work mode and deterministic resolver reasons/winners/conflicts: B/D/E/F.
- [ ] Cross-process CLI audit, request/model relationship, recoveries, unfinished/corrupt/writer failure state, history snapshots and truthful coverage: A/E/D/F.
- [ ] Mandatory production prewrite Standardizer, soft/dev visibility, stale-rule/baseline rejection and next-request reload: C/E/B.
- [ ] Two-second incremental polling, invalid external YAML state, disconnect/reconnect and pagination: A/B/D/F.
- [ ] Token/same-origin/loopback/path/text/secret safeguards and kernel v2 compatibility: A/B/D/E/F/G.
- [ ] Targeted tests, real browser flows, current knowledge validation, preserved untracked user work and no commit/push: all owners, consolidated by G.
