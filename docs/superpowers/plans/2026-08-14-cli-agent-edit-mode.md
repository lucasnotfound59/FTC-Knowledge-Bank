# FTC CLI Agent Phase 2: Edit Mode Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add session-authorized editing on the user's current branch with strict structured operations, transactional writes, Agent-specific diffs, `/undo`, `/discard`, and guarded `/commit`.

**Architecture:** The model emits JSON edit plans but never writes files. `agent-runtime` validates and applies text operations against hashes and safe paths; `tooling-git` inspects the named current branch, renders before/after Agent diffs, and provides an explicit local-only commit boundary. First-touch snapshots preserve pre-existing dirty content.

**Tech Stack:** Phase 1 stack, reusing Eclipse JGit 7.7.0.202606012155-r and SLF4J NOP 2.0.18, plus java-diff-utils 4.17 and JUnit 5.14.3.

## Global Constraints

- Keep every Phase 1 provider, Ask, privacy, retrieval, and citation test green.
- `/mode edit` is the only write authorization; model text cannot change mode.
- Edit the named current branch directly; never create or switch branches.
- Existing dirty files are editable and `/discard` restores their exact first-touch content.
- Do not ask for per-batch confirmation after Edit is enabled.
- `TeamCode/**` is ordinary scope; other permitted text changes receive a project-level warning.
- Protect `.git/**`, `.env*`, `local.properties`, keystores, binaries, escaped symlinks, and external paths.
- Validate the whole batch before writing; retry invalid model JSON once; never leave partial writes.
- No arbitrary shell, Gradle, deployment, automatic commit, push, pull, merge, rebase, or history rewrite.
- Follow repository Kotlin no-space style and TDD.

---

## File and Module Map

- `gradle/libs.versions.toml`: add JGit, java-diff-utils, and SLF4J NOP.
- `settings.gradle.kts`: register `:modules:tooling-git`.
- `modules/tooling-git/`: named-branch inspection, Agent diff, guarded commit.
- `modules/agent-runtime/src/main/kotlin/org/ftckb/agent/edit/EditModels.kt`: edit protocol and result types.
- `modules/agent-runtime/src/main/kotlin/org/ftckb/agent/edit/EditPlanParser.kt`: strict Jackson decoder.
- `modules/agent-runtime/src/main/kotlin/org/ftckb/agent/edit/SafeEditPath.kt`: path and protected-file policy.
- `modules/agent-runtime/src/main/kotlin/org/ftckb/agent/edit/FileEditEngine.kt`: virtual validation and transactional writes.
- `modules/agent-runtime/src/main/kotlin/org/ftckb/agent/edit/EditHistory.kt`: first-touch baseline, undo, discard.
- `modules/agent-runtime/src/main/kotlin/org/ftckb/agent/edit/EditAgent.kt`: retrieval/model/validation/apply loop.
- `modules/agent-runtime/src/main/kotlin/org/ftckb/agent/SessionController.kt`: Ask/Edit authority owner.
- `apps/knowledge-cli/src/main/kotlin/org/ftckb/cli/ChatRepl.kt`: enable Edit commands.

---

### Task 1: Define and parse the strict edit protocol

**Files:**
- Create: `modules/agent-runtime/src/main/kotlin/org/ftckb/agent/edit/EditModels.kt`
- Create: `modules/agent-runtime/src/main/kotlin/org/ftckb/agent/edit/EditPlanParser.kt`
- Test: `modules/agent-runtime/src/test/kotlin/org/ftckb/agent/edit/EditPlanParserTest.kt`

**Interfaces:**
- Consumes: Phase 1 `ModelJson`.
- Produces: `EditPlanParser.parse(String):EditPlan` and sealed `EditOperation` values.

- [ ] **Step 1: Write failing parser tests**

Cover valid create/replace/delete/move and reject unknown fields, blank reasons, duplicate destinations, missing preconditions, malformed SHA-256, more than 24 operations, and path syntax containing absolute roots, `..`, or backslashes.

Use this canonical assertion:

```kotlin
val plan=EditPlanParser.parse("""
  {"summary":"Guard result.","operations":[{
    "kind":"replace",
    "path":"TeamCode/src/main/java/example/Vision.java",
    "expectedSha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
    "oldText":"use(result);",
    "newText":"if(result!=null && result.isValid()) use(result);",
    "reason":"Avoid an absent result.",
    "citations":["CODE:C1","RULE:R1"]
  }]}
""".trimIndent())
assertTrue(plan.operations.single() is ReplaceText)
```

- [ ] **Step 2: Run the focused test to verify RED**

```bash
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
./gradlew :modules:agent-runtime:test --tests 'org.ftckb.agent.edit.EditPlanParserTest'
```

Expected: compilation fails because edit types do not exist.

- [ ] **Step 3: Implement the exact operation types**

```kotlin
data class EditPlan(val summary:String,val operations:List<EditOperation>)
sealed interface EditOperation { val reason:String; val citations:List<String> }
data class CreateText(
    val path:String,val expectedAbsent:Boolean,val content:String,
    override val reason:String,override val citations:List<String>
):EditOperation
data class ReplaceText(
    val path:String,val expectedSha256:String,val oldText:String,val newText:String,
    override val reason:String,override val citations:List<String>
):EditOperation
data class DeleteText(
    val path:String,val expectedSha256:String,
    override val reason:String,override val citations:List<String>
):EditOperation
data class MoveText(
    val sourcePath:String,val destinationPath:String,val expectedSha256:String,
    val destinationExpectedAbsent:Boolean,
    override val reason:String,override val citations:List<String>
):EditOperation
```

- [ ] **Step 4: Implement strict Jackson decoding**

Branch on `kind` and reject every unknown key. Require non-blank summary/reason up to 2,000 characters, paths up to 512 characters, exactly 64 lowercase hex hash characters, create/move absence flags equal to literal `true`, and no more than 16 citations per operation. Syntax parsing does not resolve the filesystem.

- [ ] **Step 5: Run runtime tests and commit**

```bash
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew :modules:agent-runtime:test
git add modules/agent-runtime/src/main/kotlin/org/ftckb/agent/edit \
  modules/agent-runtime/src/test/kotlin/org/ftckb/agent/edit
git commit -m "feat: define structured Agent edit plans"
```

Expected: tests PASS and the commit contains only protocol/parser files.

---

### Task 2: Validate paths and apply batches transactionally

**Files:**
- Create: `modules/agent-runtime/src/main/kotlin/org/ftckb/agent/edit/SafeEditPath.kt`
- Create: `modules/agent-runtime/src/main/kotlin/org/ftckb/agent/edit/FileEditEngine.kt`
- Test: `modules/agent-runtime/src/test/kotlin/org/ftckb/agent/edit/SafeEditPathTest.kt`
- Test: `modules/agent-runtime/src/test/kotlin/org/ftckb/agent/edit/FileEditEngineTest.kt`

**Interfaces:**
- Consumes: `EditPlan`.
- Produces: `SafeEditPath.resolve(String):ResolvedEditPath`, `preview(EditPlan):ValidatedEditBatch`, and `apply(ValidatedEditBatch):AppliedEditBatch`.

- [ ] **Step 1: Write failing safety and rollback tests**

Reject `.git/config`, `.env`, `.env.local`, `local.properties`, `.jks`, `.keystore`, NUL, absolute Unix/Windows paths, `..`, outside symlinks, binary files, and symlinked parents. Inject a failure on the second write of a two-file batch and assert the first file returns byte-for-byte to its pre-batch state.

Prove a dirty file remains editable:

```kotlin
Files.writeString(file,"user change\n")
val batch=engine.preview(EditPlan("edit",listOf(
    ReplaceText("TeamCode/Test.java",sha256("user change\n"),"user change","user + agent","reason",emptyList())
)))
engine.apply(batch)
assertEquals("user + agent\n",Files.readString(file))
```

- [ ] **Step 2: Run tests to verify RED**

```bash
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew \
  :modules:agent-runtime:test --tests 'org.ftckb.agent.edit.SafeEditPathTest' \
  --tests 'org.ftckb.agent.edit.FileEditEngineTest'
```

Expected: compilation fails because the engine does not exist.

- [ ] **Step 3: Implement safe resolution and result types**

```kotlin
enum class EditScope { NORMAL,PROJECT_LEVEL }
data class ResolvedEditPath(val relative:String,val absolute:Path,val scope:EditScope)
sealed interface FileSnapshot {
    data object Missing:FileSnapshot
    data class Text(val content:String,val sha256:String):FileSnapshot
}
data class PlannedFileChange(
    val path:String,val before:FileSnapshot,val after:FileSnapshot,val scope:EditScope
)
data class ValidatedEditBatch(val summary:String,val changes:List<PlannedFileChange>)
data class AppliedEditBatch(val summary:String,val changes:List<PlannedFileChange>)
```

Resolve against `root.toRealPath()`, reject escaped links and protected basenames case-insensitively, allow only Phase 1 text extensions, and classify paths outside `TeamCode/**` as project-level.

- [ ] **Step 4: Implement virtual validation and transactional writes**

Apply all operations to an in-memory path→snapshot map before disk writes. Require current hashes, unique `oldText`, absent destinations, at most 24 files, at most 1 MiB per result, and at most 4 MiB total new text.

At apply time recheck preconditions, write temporary sibling files, preserve POSIX permissions when supported, and use atomic replacement where supported. On any failure restore all already-written paths and delete temp files. Surface original and rollback failures separately.

- [ ] **Step 5: Run tests and commit**

```bash
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew :modules:agent-runtime:test
git add modules/agent-runtime/src/main/kotlin/org/ftckb/agent/edit \
  modules/agent-runtime/src/test/kotlin/org/ftckb/agent/edit
git commit -m "feat: apply Agent edits transactionally"
```

Expected: all tests PASS and temp directories contain no leaked sibling files.

---

### Task 3: Inspect the current branch, render Agent diffs, and guard commits

**Files:**
- Modify: `settings.gradle.kts`
- Modify: `gradle/libs.versions.toml`
- Create: `modules/tooling-git/build.gradle.kts`
- Create: `modules/tooling-git/src/main/kotlin/org/ftckb/git/GitWorkspace.kt`
- Create: `modules/tooling-git/src/main/kotlin/org/ftckb/git/AgentDiffRenderer.kt`
- Create: `modules/tooling-git/src/main/kotlin/org/ftckb/git/GitCommitService.kt`
- Test: `modules/tooling-git/src/test/kotlin/org/ftckb/git/GitWorkspaceTest.kt`
- Test: `modules/tooling-git/src/test/kotlin/org/ftckb/git/AgentDiffRendererTest.kt`
- Test: `modules/tooling-git/src/test/kotlin/org/ftckb/git/GitCommitServiceTest.kt`

**Interfaces:**
- Consumes: repository path and explicit text snapshots.
- Produces: `GitWorkspace.inspect`, `AgentDiffRenderer.render`, and `GitCommitService.commit`.

- [ ] **Step 1: Write failing Git tests**

Initialize JGit repositories under `@TempDir`. Test named branch detection, detached HEAD, dirty-path union, and proof that inspection creates no branch. For a file already dirty relative to HEAD, pass before=`user change` and after=`user + agent`; assert the Agent diff shows only that transition. Test commit refusal when baseline-dirty paths intersect Agent-touched paths.

- [ ] **Step 2: Register exact dependencies and verify RED**

Add only the new diff version; JGit and SLF4J versions already exist from Phase 1:

```toml
java-diff-utils = "4.17"
```

Register `:modules:tooling-git`; add JGit and java-diff-utils implementation dependencies and SLF4J NOP runtime dependency.

```bash
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew :modules:tooling-git:test
```

Expected: compilation fails because Git service types do not exist.

- [ ] **Step 3: Implement narrow Git contracts**

```kotlin
data class GitWorkspaceState(
    val repositoryRoot:Path,val branch:String?,val detached:Boolean,val dirtyPaths:Set<String>
)
data class TextChange(val path:String,val before:String?,val after:String?,val projectLevel:Boolean)
data class CommitRequest(
    val repositoryRoot:Path,val paths:Set<String>,val baselineDirtyPaths:Set<String>,val message:String
)
```

Bound repository discovery to the selected root. `dirtyPaths` is the union of added, changed, modified, removed, missing, untracked, and conflicting paths. Never create or checkout a branch.

Render stable unified diffs with three context lines from passed before/after snapshots, never from `HEAD`. Prefix project-level changes visibly.

Commit rejects detached HEAD, blank message, unsafe/empty paths, and baseline-dirty overlap. Stage exact safe paths and create one local commit; return full SHA and never push.

- [ ] **Step 4: Run tests and commit**

```bash
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew :modules:tooling-git:test
git add settings.gradle.kts gradle/libs.versions.toml modules/tooling-git
git commit -m "feat: inspect and diff the current Git branch"
```

Expected: all tests PASS and test repositories alone receive commits.

---

### Task 4: Track first-touch history and orchestrate Edit

**Files:**
- Create: `modules/agent-runtime/src/main/kotlin/org/ftckb/agent/edit/EditHistory.kt`
- Create: `modules/agent-runtime/src/main/kotlin/org/ftckb/agent/edit/EditAgent.kt`
- Create: `modules/agent-runtime/src/main/kotlin/org/ftckb/agent/SessionController.kt`
- Modify: `modules/agent-runtime/build.gradle.kts`
- Test: `modules/agent-runtime/src/test/kotlin/org/ftckb/agent/edit/EditHistoryTest.kt`
- Test: `modules/agent-runtime/src/test/kotlin/org/ftckb/agent/edit/EditAgentTest.kt`
- Test: `modules/agent-runtime/src/test/kotlin/org/ftckb/agent/SessionControllerTest.kt`

**Interfaces:**
- Consumes: Phase 1 retrieval/answers, edit engine, model provider, repository index, and Git workspace.
- Produces: session mode, submit, undo, discard, diff, and edit reports.

- [ ] **Step 1: Write failing history and orchestration tests**

Cover two batches on one initially dirty file, undo of only the latest batch, discard to exact original dirty bytes, create/move/delete, and concurrent IDE changes causing zero-write conflicts.

For orchestration verify Ask rejects edit application, Edit requires a named branch, valid batches apply without confirmation, invalid JSON/citations receive exactly one repair, conflicting rules cannot justify an edit, index refresh exposes new text, and saved conversation contains only compact edit summaries.

- [ ] **Step 2: Run tests to verify RED**

Add `:modules:tooling-git` to agent-runtime dependencies, then run:

```bash
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew \
  :modules:agent-runtime:test --tests 'org.ftckb.agent.edit.*' \
  --tests 'org.ftckb.agent.SessionControllerTest'
```

Expected: compilation fails because history/orchestration types do not exist.

- [ ] **Step 3: Implement history with conflict-safe reversal**

```kotlin
data class HistoryResult(val changedPaths:Set<String>,val conflicts:Set<String>) {
    val succeeded:Boolean get()=conflicts.isEmpty()
}
```

Keep first-touch snapshots, applied-batch stack, and current expected snapshots. Before undo/discard validate every affected current hash/absence, then restore the whole reversal transactionally. Do not clear state after conflicts. `changes()` returns first-touch→current `TextChange` values.

- [ ] **Step 4: Implement EditAgent and SessionController**

Execution order is fixed:

```text
retrieve → model JSON → parse → citation check → preview → apply → history.record → index.refresh
```

Repair parse/citation/preview failure once using bounded validation messages; never retry a successful write.

Define:

```kotlin
enum class AgentMode { ASK,EDIT }
data class EditReport(
    val summary:String,val changedPaths:Set<String>,val projectLevelPaths:Set<String>,
    val diff:String,val reasons:List<String>,val citations:Set<String>
)
sealed interface SessionResult
data class AskResult(val answer:AgentAnswer):SessionResult
data class EditResult(val report:EditReport):SessionResult
data class RejectedResult(val message:String):SessionResult
```

Only the CLI command handler may call `setMode`; normal messages route to AskAgent or EditAgent according to current mode.

- [ ] **Step 5: Run tests and commit**

```bash
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew \
  :modules:agent-runtime:test :modules:tooling-git:test
git add modules/agent-runtime
git commit -m "feat: orchestrate reversible FTC code edits"
```

Expected: all tests PASS.

---

### Task 5: Enable Edit commands and verify the current-branch workflow

**Files:**
- Modify: `apps/knowledge-cli/build.gradle.kts`
- Modify: `apps/knowledge-cli/src/main/kotlin/org/ftckb/cli/ChatRepl.kt`
- Modify: `apps/knowledge-cli/src/main/kotlin/org/ftckb/cli/ProductionChatLauncher.kt`
- Test: `apps/knowledge-cli/src/test/kotlin/org/ftckb/cli/EditReplAcceptanceTest.kt`
- Create fixture files under: `fixtures/agent/edit-repo/`

**Interfaces:**
- Consumes: `SessionController`, `AgentDiffRenderer`, `GitCommitService`.
- Produces: `/mode edit`, `/undo`, `/discard`, `/diff`, and `/commit`.

- [ ] **Step 1: Write a failing end-to-end Edit REPL test**

Copy a fixture FTC repository to `@TempDir`, initialize branch `team-work`, and add an uncommitted user change. Feed:

```text
/mode edit
给 Vision.java 加结果有效性检查
/diff
/undo
再修改一次
/discard
/exit
```

Assert branch remains `team-work`, no branch is added, edits need no confirmation, undo restores one batch, discard restores original dirty bytes, no commit exists, and no command/Gradle process runs.

- [ ] **Step 2: Run the acceptance test to verify RED**

```bash
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
./gradlew :apps:knowledge-cli:test --tests 'org.ftckb.cli.EditReplAcceptanceTest'
```

Expected: test fails because Edit commands are unavailable.

- [ ] **Step 3: Implement command behavior**

`/mode edit` refuses non-Git and detached states; `/mode ask` removes write authority. After a successful edit print reasons, citations, paths, project-level warnings, and Agent-specific diff. `/undo` and `/discard` report conflicts without overwriting concurrent IDE changes. `/diff` shows first-touch→current changes.

`/commit` prints exact paths/message, reads literal `yes`, and otherwise cancels. It refuses baseline-dirty overlap and never pushes.

- [ ] **Step 4: Wire production dependencies and run all tests**

Capture baseline dirty paths at startup; keep API-key values out of renderable/session state.

```bash
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew clean test --no-daemon
git diff --check
```

Expected: `BUILD SUCCESSFUL` and no whitespace errors.

- [ ] **Step 5: Commit Task 5**

```bash
git add apps/knowledge-cli fixtures/agent/edit-repo
git commit -m "feat: expose current-branch Edit mode"
```

---

## Phase 2 Review Gate

```bash
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew clean test --no-daemon
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
./gradlew :apps:knowledge-cli:run --args='validate knowledge' --quiet
git diff --check
git status --short
```

Required outcomes:

- Ask and provider behavior are unchanged;
- Edit never creates or switches a branch;
- dirty files are editable and recover exactly;
- protected paths and escaped symlinks are unwritable;
- invalid output cannot cause partial writes;
- no per-batch confirmation occurs after `/mode edit`;
- `/commit` is explicit, guarded, local-only, and refuses mixed pre-session changes;
- no push, Gradle, shell, live-web, or robot tool exists.
