# Limelight Triggered Soft Reviews Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace two over-broad Limelight hard checks with conditional soft review prompts that never block unrelated Java changes.

**Architecture:** Keep the existing kernel JSON v2 `soft` shape and YAML v4 `reviewTriggers` model. Extend only `Standardizer.evaluate`: rules with hard `checks` behave exactly as before; rules without checks and without triggers remain unconditional soft guidance; rules without checks and with triggers enter `soft` only when their path and added-line trigger matches. Migrate the two Limelight rules to that existing representation and publish the behavior as repository V0.5.0.

**Tech Stack:** Kotlin/JVM 21, JUnit 5, SnakeYAML Engine, Jackson, Gradle application distribution, YAML v4 rules, Markdown documentation.

## Global Constraints

- Work on the user's current `main` branch; do not create or switch branches/worktrees.
- Preserve `.DS_Store`, `apps/.DS_Store`, `docs/superpowers/plans/2026-09-08-docs-site.md`, and `dsh-client-ui-tokyo-night-dsh/`; never stage them.
- Hard findings enter `violations` and make `check` return 1; triggered soft findings enter `soft`, keep exit 0, and must be reported to the user.
- A soft trigger requests model/human review; it does not prove that Java control flow violates validity or freshness guidance.
- The two Limelight rules remain approved and retain their IDs, instructions, evidence, applicability, and 2026-08-13 approvals.
- Repository version becomes V0.5.0. CLI remains 2.0.0, YAML remains v4, kernel JSON remains v2, and integration protocol remains v2.
- Rule totals remain 46: 40 approved and 6 candidate.
- No API/model call, dependency upgrade, tag, push, Android Studio UI action, deployment, or robot validation.

---

### Task 1: Make reviewTriggers drive conditional soft output

**Files:**
- Modify: `modules/standardizer/src/main/kotlin/org/ftckb/standardizer/Standardizer.kt`
- Modify: `modules/standardizer/src/test/kotlin/org/ftckb/standardizer/StandardizerTest.kt`

**Interfaces:**
- Consumes: `KnowledgeRule.reviewTriggers:List<RuleReviewTrigger>`, each trigger containing `paths:List<String>` and `addedLinePatterns:List<String>`.
- Produces: unchanged `Standardizer.Outcome(violations,soft)`. `soft` remains `List<Pair<String,String>>`, deterministically sorted by rule ID and containing at most one entry per rule.

- [ ] **Step 1: Add a rule helper and failing trigger tests**

Add imports for `KnowledgeRule`, `RuleApplicability`, `RuleAuthority`, `RuleReviewTrigger`, and `RuleStatus` to `StandardizerTest.kt`, then add this helper:

```kotlin
private fun softRule(id:String,triggers:List<RuleReviewTrigger> =emptyList())=KnowledgeRule(
    id=id,
    topic=id.substringAfter('.').replace('.','-'),
    title=id,
    instruction="Review $id",
    rationale="Test conditional soft review.",
    status=RuleStatus.APPROVED,
    authority=RuleAuthority.SHARED,
    applicability=RuleApplicability(),
    evidence=emptyList(),
    reviewTriggers=triggers
)
```

Add tests covering all semantics:

```kotlin
@Test
fun `review triggers emit soft only for matching added lines`() {
    val always=softRule("shared.always")
    val triggered=softRule("shared.limelight",listOf(RuleReviewTrigger(
        listOf("**/*.java"),
        listOf("\\bLLResult\\b","\\.getLatestResult\\s*\\(")
    )))
    val unrelated=listOf(Standardizer.DiffChange(
        "TeamCode/src/main/java/example/DriveSubsystem.java",listOf(10 to "motor.setPower(power);")
    ))
    val relevant=listOf(Standardizer.DiffChange(
        "TeamCode/src/main/java/example/Vision.java",listOf(7 to "LLResult result=limelight.getLatestResult();")
    ))

    assertEquals(listOf("shared.always"),Standardizer.evaluate(listOf(triggered,always),unrelated).soft.map { it.first })
    assertEquals(
        listOf("shared.always","shared.limelight"),
        Standardizer.evaluate(listOf(triggered,always),relevant).soft.map { it.first }
    )
}

@Test
fun `path-only triggers and duplicate matches emit one sorted soft entry`() {
    val pathOnly=softRule("shared.z-rule",listOf(RuleReviewTrigger(listOf("TeamCode/**"),emptyList())))
    val repeated=softRule("shared.a-rule",listOf(
        RuleReviewTrigger(listOf("**/*.java"),listOf("LLResult")),
        RuleReviewTrigger(listOf("**/Vision.java"),listOf("getLatestResult"))
    ))
    val changes=listOf(Standardizer.DiffChange(
        "TeamCode/src/main/java/example/Vision.java",
        listOf(1 to "LLResult result=limelight.getLatestResult();")
    ))

    assertEquals(
        listOf("shared.a-rule","shared.z-rule"),
        Standardizer.evaluate(listOf(pathOnly,repeated),changes).soft.map { it.first }
    )
}
```

- [ ] **Step 2: Run the tests and observe RED**

```bash
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
  ./gradlew :modules:standardizer:test --tests '*StandardizerTest*' --console=plain
```

Expected: the unrelated case incorrectly contains `shared.limelight`, demonstrating that current no-check rules are always soft; ordering/deduplication assertions may also fail.

- [ ] **Step 3: Implement trigger matching without changing hard checks**

In `Standardizer.evaluate`, replace the unconditional no-check branch with:

```kotlin
if (rule.checks.isEmpty()) {
    if (rule.reviewTriggers.isEmpty() || reviewTriggered(rule,changes,matchers)) {
        soft+=rule.id to rule.instruction
    }
} else {
    rule.checks.forEach { check -> evaluateCheck(rule.id,check,changes,matchers,violations) }
}
```

Add this private helper inside `Standardizer`:

```kotlin
private fun reviewTriggered(
    rule:KnowledgeRule,
    changes:List<DiffChange>,
    matchers:(String)->java.nio.file.PathMatcher?
):Boolean=rule.reviewTriggers.any { trigger ->
    val regexes=trigger.addedLinePatterns.mapNotNull { pattern ->
        runCatching { Regex(pattern) }.getOrNull()
    }
    trigger.paths.any { glob ->
        val matcher=matchers(glob)
        matcher!=null && changes.any { change ->
            matcher.matches(Path.of(change.path)) && when {
                trigger.addedLinePatterns.isEmpty() -> true
                regexes.isEmpty() -> false
                else -> change.addedLines.any { (_,text) -> regexes.any { it.containsMatchIn(text) } }
            }
        }
    }
}
```

Return unique, sorted soft entries:

```kotlin
return Outcome(violations,soft.distinctBy { it.first }.sortedBy { it.first })
```

Do not modify `evaluateCheck`; all existing path/regex hard behavior and violation output remain unchanged.

- [ ] **Step 4: Run focused and module tests GREEN**

```bash
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
  ./gradlew :modules:standardizer:test --console=plain
git diff --check -- modules/standardizer
```

Expected: all standardizer tests pass; unrelated Java produces no triggered soft, matching Limelight lines do, path-only triggers work, duplicate matches produce one entry, existing diff parsing tests remain green.

- [ ] **Step 5: Commit Task 1**

```bash
git add -- modules/standardizer/src/main/kotlin/org/ftckb/standardizer/Standardizer.kt \
  modules/standardizer/src/test/kotlin/org/ftckb/standardizer/StandardizerTest.kt
git commit -m "feat(standardizer): trigger soft reviews from diff"
```

---

### Task 2: Migrate the two approved Limelight rules from hard to triggered soft

**Files:**
- Modify: `knowledge/shared/tools/limelight.yaml`
- Create: `docs/rule-migrations/2026-09-13-limelight-triggered-soft.md`
- Create: `apps/knowledge-cli/src/test/kotlin/org/ftckb/cli/LimelightSoftRuleAcceptanceTest.kt`

**Interfaces:**
- Consumes: Task 1 conditional soft semantics.
- Produces: both target rules with `checks=[]` and non-empty `reviewTriggers`; all five Limelight rules encoded as valid YAML v4 shared policy rules.

- [ ] **Step 1: Add repository-level RED acceptance tests**

Create `LimelightSoftRuleAcceptanceTest.kt`. Load `../../knowledge`, resolve generic team `20827` / season `2025-2026`, and define:

```kotlin
private val targetIds=setOf(
    "shared.limelight-check-result-validity",
    "shared.limelight-enforce-freshness-policy"
)
```

Add a metadata test that requires, for both rules:

```kotlin
assertEquals(RuleStatus.APPROVED,rule.status)
assertEquals(RuleAuthority.SHARED,rule.authority)
assertEquals(PolicyLevel.SHARED,rule.policyLevel)
assertNotNull(rule.approval)
assertTrue(rule.evidence.isNotEmpty())
assertTrue(rule.checks.isEmpty())
assertTrue(rule.reviewTriggers.isNotEmpty())
assertEquals(emptySet<String>(),rule.applicability.teams)
assertEquals(emptySet<String>(),rule.applicability.seasons)
assertEquals(emptySet<String>(),rule.applicability.profiles)
```

Add CLI tests using actual knowledge plus temporary unified patches. For an unrelated patch adding
`motor.setPower(power);` to `DriveSubsystem.java`, assert exit 0, `violations` contains neither target ID,
and filtered `soft` is empty. For a relevant patch adding
`LLResult result=limelight.getLatestResult();` to `Vision.java`, assert exit 0, no target violations, and:

```kotlin
assertEquals(targetIds.sorted(),node["soft"].map { it["ruleId"].asText() }.filter { it in targetIds })
```

The relevant patch should contain both `LLResult` and `getLatestResult()` so the test also proves multiple pattern matches do not duplicate a rule's soft entry.

- [ ] **Step 2: Run RED against current YAML**

```bash
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
  ./gradlew :apps:knowledge-cli:test --tests '*LimelightSoftRuleAcceptanceTest' --console=plain
```

Expected: metadata fails because current rules have hard checks/no review triggers; unrelated `DriveSubsystem.java` returns two hard violations and exit 1.

- [ ] **Step 3: Migrate the Limelight file to YAML v4**

Set the file root to:

```yaml
schemaVersion: 4
```

For every one of the five existing rules, preserve all content and add:

```yaml
policyLevel: shared
applicability:
  teams: []
  seasons: []
  profiles: []
```

For only the two target rules, delete `checks` and add this exact trigger:

```yaml
reviewTriggers:
  - paths: ["**/*.java"]
    addedLinePatterns:
      - '\b(?:Limelight3A|LLResult)\b'
      - '\.(?:getLatestResult|getBotpose(?:_[A-Za-z0-9]+)?|getTargetTimestamp|getStaleness)\s*\('
```

Do not change IDs, topics, instructions, rationales, status, authority, approvals, or evidence. Do not add triggers to pipeline synchronization, camera pose, or OS backup rules in this task.

- [ ] **Step 4: Record the governance change**

Create `docs/rule-migrations/2026-09-13-limelight-triggered-soft.md` containing:

- the two exact rule IDs;
- old enforcement (`regex-required`, `**/*.java`, exit 1 false positives);
- new enforcement (`checks:[]`, conditional `reviewTriggers`, exit 0 soft prompt);
- the 2026-09-13 user decision defining hard versus soft;
- confirmation that original approvals/evidence/status remain intact;
- explicit statement that trigger matching requests review and does not prove a violation;
- no robot-specific parameter or unsupported safety conclusion was introduced.

- [ ] **Step 5: Run knowledge and CLI GREEN**

```bash
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
  ./gradlew :modules:knowledge:test :modules:standardizer:test --console=plain
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
  ./gradlew :apps:knowledge-cli:test \
  --tests '*LimelightSoftRuleAcceptanceTest' --console=plain
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
  ./gradlew :apps:knowledge-cli:installDist --console=plain
apps/knowledge-cli/build/install/ftckb/bin/ftckb validate knowledge --json
apps/knowledge-cli/build/install/ftckb/bin/ftckb resolve knowledge \
  --team 20827 --season 2025-2026 --generic-profile --json
git diff --check -- knowledge/shared/tools/limelight.yaml \
  docs/rule-migrations/2026-09-13-limelight-triggered-soft.md \
  apps/knowledge-cli/src/test/kotlin/org/ftckb/cli/LimelightSoftRuleAcceptanceTest.kt
```

Expected: validation `ok:true`, ruleCount 46, resolution has 24 generic active rules, no conflicts, and the two target active rules have empty checks plus the declared triggers.

- [ ] **Step 6: Commit Task 2**

```bash
git add -- knowledge/shared/tools/limelight.yaml \
  docs/rule-migrations/2026-09-13-limelight-triggered-soft.md \
  apps/knowledge-cli/src/test/kotlin/org/ftckb/cli/LimelightSoftRuleAcceptanceTest.kt
git commit -m "fix(knowledge): soften Limelight result checks"
```

---

### Task 3: Publish V0.5.0 documentation and regenerate the real resolve fixture

**Files:**
- Modify: `README.md`
- Modify: `AGENTS.md`
- Modify: `todolist.md`
- Modify: `docs/project-integration.md`
- Modify: `docs/kernel-contract.md`
- Modify: `docs/standardizer-check.md`
- Modify: `docs/cli-agent.md`
- Modify: `docs/handbook/rule-schema.md`
- Modify: `docs/handbook/troubleshooting.md`
- Modify: `docs/website/integration-and-checks.md`
- Modify: `knowledge/guides/tools/limelight-3a.md`
- Modify: `fixtures/kernel/resolve-ok.json`
- Modify: `apps/knowledge-cli/src/test/kotlin/org/ftckb/cli/CliDocumentationAcceptanceTest.kt`
- Modify: `apps/knowledge-cli/src/test/kotlin/org/ftckb/cli/KernelJsonAcceptanceTest.kt`
- Modify: `apps/knowledge-cli/src/test/kotlin/org/ftckb/cli/KnowledgeGuideAcceptanceTest.kt`

**Interfaces:**
- Consumes: actual Task 1/2 CLI behavior.
- Produces: repository version V0.5.0 documentation; real `resolve-ok.json` reflecting empty Limelight checks and populated reviewTriggers. No JSON Schema or CLI version change.

- [ ] **Step 1: Add RED documentation and fixture assertions**

Update `CliDocumentationAcceptanceTest.release documents...` to require `**版本：V0.5.0**` and the unchanged axes `CLI 2.0.0`, `YAML v4`, `kernel JSON v2`, `项目接入协议 v2`.

Add assertions across the listed current user documents that require the phrases/concepts:

```text
条件式 soft
reviewTriggers
退出码 0
Agent 必须向用户报告
4 条生效规则带硬检查
```

Also reject stale claims that the two Limelight rules remain `regex-required`, apply hard checks to every Java file, or are candidate.

In `KnowledgeGuideAcceptanceTest`, require the Limelight guide to state that all five rules are approved and that validity/freshness are triggered soft guidance; reject `这些规则当前都是 candidate`.

In `KernelJsonAcceptanceTest`, inspect `fixtures/kernel/resolve-ok.json` by ID and assert the two target rules have `checks.size()==0` and `reviewTriggers.size()==1`.

- [ ] **Step 2: Run RED before documentation/fixture updates**

```bash
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
  ./gradlew :apps:knowledge-cli:test \
  --tests '*CliDocumentationAcceptanceTest' \
  --tests '*KernelJsonAcceptanceTest' \
  --tests '*KnowledgeGuideAcceptanceTest' --console=plain
```

Expected: V0.5.0, conditional soft documentation, guide status, and stale resolve fixture assertions fail.

- [ ] **Step 3: Update version and user-facing semantics**

Apply these exact boundaries throughout the listed documents:

- repository V0.5.0; other four version axes unchanged;
- four active hard-check rules remain: official build-file protection, FTC SDK release pinning, FTC build-tool preservation, and Dashboard stable-version pinning;
- the two Limelight rules are approved conditional soft guidance;
- a review trigger match enters `soft` and keeps exit 0; the Agent must show it to the user;
- a non-match emits no Limelight soft item;
- soft means manual review required, not machine-proven violation and not robot validation;
- remove the completed Limelight false-positive items from open TODOs or mark them complete with the V0.5.0 behavior.

In `docs/handbook/rule-schema.md` and `docs/kernel-contract.md`, define the execution rule precisely: no-check/no-trigger remains unconditional soft; no-check/trigger is conditional soft; checks remain hard. In `docs/website/integration-and-checks.md`, also correct the adjacent stale `kernel schemaVersion` statement to 2.

- [ ] **Step 4: Regenerate resolve-ok from the built CLI**

Run:

```bash
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
  ./gradlew :apps:knowledge-cli:installDist --console=plain
apps/knowledge-cli/build/install/ftckb/bin/ftckb resolve knowledge \
  --team 20827 --season 2025-2026 --generic-profile --json
```

Capture stdout in a temporary file, verify exit 0/schemaVersion 2/profiles empty/24 active/no conflicts, then replace `fixtures/kernel/resolve-ok.json` with that exact one-line stdout using `apply_patch`. Do not hand-edit only the two rule objects and do not alter the other nine fixtures.

- [ ] **Step 5: Run documentation and fixture GREEN**

```bash
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
  ./gradlew :apps:knowledge-cli:test \
  --tests '*CliDocumentationAcceptanceTest' \
  --tests '*KernelJsonAcceptanceTest' \
  --tests '*KnowledgeGuideAcceptanceTest' --console=plain
git diff --check -- README.md AGENTS.md todolist.md docs \
  knowledge/guides/tools/limelight-3a.md fixtures/kernel/resolve-ok.json \
  apps/knowledge-cli/src/test/kotlin/org/ftckb/cli
```

Expected: all selected tests pass; all 10 fixtures validate against the unchanged v2 schema; version/count/status/soft semantics agree with actual output.

- [ ] **Step 6: Commit Task 3**

```bash
git add -- README.md AGENTS.md todolist.md docs/project-integration.md \
  docs/kernel-contract.md docs/standardizer-check.md docs/cli-agent.md \
  docs/handbook/rule-schema.md docs/handbook/troubleshooting.md \
  docs/website/integration-and-checks.md knowledge/guides/tools/limelight-3a.md \
  fixtures/kernel/resolve-ok.json \
  apps/knowledge-cli/src/test/kotlin/org/ftckb/cli/CliDocumentationAcceptanceTest.kt \
  apps/knowledge-cli/src/test/kotlin/org/ftckb/cli/KernelJsonAcceptanceTest.kt \
  apps/knowledge-cli/src/test/kotlin/org/ftckb/cli/KnowledgeGuideAcceptanceTest.kt
git commit -m "docs: release triggered soft reviews v0.5.0"
```

---

### Task 4: Full regression and delivery audit

**Files:** Read-only verification. Do not create an empty verification commit.

**Interfaces:** Produces final evidence that hard/soft behavior, knowledge, CLI, integration, fixtures, and documentation agree.

- [ ] **Step 1: Run the complete Kotlin/JVM and Android plugin compile suite**

```bash
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
  ./gradlew test :apps:android-studio-plugin:compileKotlin \
  :apps:knowledge-cli:installDist --console=plain
```

Expected: all tests pass with no launcher skip; Android plugin compiles. Count tests from fresh XML instead of copying the V0.4.0 total.

- [ ] **Step 2: Run Python integration and smoke**

```bash
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s tests/integration -v
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' sh scripts/smoke.sh
```

Expected: Python ordinary tests pass with only the explicitly opt-in real pinned-source test allowed to skip; smoke reports 9 passed, 0 failed.

- [ ] **Step 3: Verify real knowledge and triggered soft behavior**

```bash
apps/knowledge-cli/build/install/ftckb/bin/ftckb validate knowledge --json
apps/knowledge-cli/build/install/ftckb/bin/ftckb resolve knowledge \
  --team 20827 --season 2025-2026 --generic-profile --json
```

Create two explicitly named files under `/tmp` with `apply_patch`: `/tmp/ftckb-limelight-unrelated.patch`
adds `motor.setPower(power);` to `TeamCode/src/main/java/example/DriveSubsystem.java`, and
`/tmp/ftckb-limelight-relevant.patch` adds
`LLResult result=limelight.getLatestResult();` to `TeamCode/src/main/java/example/Vision.java`.
Use these exact unified patches:

```diff
diff --git a/TeamCode/src/main/java/example/DriveSubsystem.java b/TeamCode/src/main/java/example/DriveSubsystem.java
new file mode 100644
--- /dev/null
+++ b/TeamCode/src/main/java/example/DriveSubsystem.java
@@ -0,0 +1 @@
+motor.setPower(power);
```

```diff
diff --git a/TeamCode/src/main/java/example/Vision.java b/TeamCode/src/main/java/example/Vision.java
new file mode 100644
--- /dev/null
+++ b/TeamCode/src/main/java/example/Vision.java
@@ -0,0 +1 @@
+LLResult result=limelight.getLatestResult();
```

Run `ftckb check --diff` against each, parse the JSON, then delete only those two temporary files.
Expected:

- validate: 46 rules, no violations;
- resolve: 24 active, no conflicts;
- unrelated patch: exit 0, neither Limelight ID in violations or soft;
- relevant patch: exit 0, neither Limelight ID in violations, both IDs exactly once in soft.

- [ ] **Step 4: Check the branch diff and repository state**

```bash
git diff --check
git status --short --branch
git log --oneline origin/main..HEAD
```

Expected: no unstaged task files; only the four known user-owned untracked paths remain; commits are task-scoped; branch is ahead of origin and has not been pushed.

- [ ] **Step 5: Report validation boundaries**

Report exact test totals, Python skip, smoke result, rule counts, hard/soft results, conflicts, and remaining unverified Android Studio UI/RC/DS/deployment/robot behavior. Do not tag or push until the user explicitly asks.
