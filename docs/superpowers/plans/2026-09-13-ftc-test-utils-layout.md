# FTC Test and Utils Layout Policy Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a cross-season global hard rule that keeps FTC robot-side tests and utilities in canonical `teamcode/tests` and `teamcode/utils` packages, removes contradictory RookieBot JUnit guidance, and publishes repository V0.6.0.

**Architecture:** Use only existing YAML v4 `path-forbidden` and `regex-forbidden` checks so the kernel and CLI contracts remain unchanged. Migrate the two contradictory RookieBot rules first, add one independently evidenced global rule with deterministic diff checks, then regenerate real kernel fixtures and update all current public counts. This plan starts only after `2026-09-13-limelight-triggered-soft.md` is complete and V0.5.0 behavior is green.

**Tech Stack:** YAML v4, Kotlin/JVM 21, existing JUnit 5 acceptance suite for the Knowledge Bank itself, Jackson, Gradle application distribution, Markdown documentation.

## Global Constraints

- Work on the user's current `main` branch; do not create or switch branches/worktrees.
- Correct robot-side directories are exactly `TeamCode/src/main/java/org/firstinspires/ftc/teamcode/tests/` and `TeamCode/src/main/java/org/firstinspires/ftc/teamcode/utils/`, peer to `subsystems/` and `commands/`.
- Target FTC TeamCode projects do not use JUnit, `TeamCode/src/test/`, or `TeamCode/src/androidTest/`; this does not remove the Knowledge Bank's own Kotlin/JUnit test infrastructure.
- `global.test-utility-layout` is approved, authority `shared`, policyLevel `global`, with empty teams/seasons/profiles and approval time `2026-09-13T09:12:38Z`.
- Evidence for the new maintainer policy is commit `6aa385d75484b22b3f73c3b493a695e753ccc76e`, file `docs/superpowers/specs/2026-09-13-ftc-test-utils-layout-design.md`, line 8. Do not claim TeamChina, RookieBot, FIRST, or the FTC SDK originally required this layout.
- The new rule is hard only where existing path/regex checks can decide reliably. Do not add filename-wide semantic guesses or a new check kind.
- Repository version becomes V0.6.0. CLI remains 2.0.0, YAML remains v4, kernel JSON remains v2, and project integration protocol remains v2.
- Final totals are 47 rules: 41 approved and 6 candidate. For both teams in season 2025-2026: generic 25, command-based 28, rookiebot 37, ftclib-command 29. Five active rules have hard checks.
- Preserve `.DS_Store`, `apps/.DS_Store`, `docs/superpowers/plans/2026-09-08-docs-site.md`, and `dsh-client-ui-tokyo-night-dsh/`; never stage them.
- No API/model call, dependency upgrade, tag, push, Android Studio UI action, deployment, or robot validation.

---

### Task 1: Remove contradictory RookieBot JUnit guidance

**Files:**
- Modify: `knowledge/shared/practices/rookiebot-tutorial.yaml`
- Modify: `knowledge/guides/practices/rookiebot-tutorial.md`
- Modify: `apps/knowledge-cli/src/test/kotlin/org/ftckb/cli/TeamChinaRuleMigrationAcceptanceTest.kt`
- Create: `apps/knowledge-cli/src/test/kotlin/org/ftckb/cli/TestUtilityLayoutAcceptanceTest.kt`

**Interfaces:**
- Consumes: the approved V0.6.0 design commit and existing YAML v4 rule model.
- Produces: the same 12 RookieBot rule IDs, with only `shared.rookiebot-java-imports` and `shared.rookiebot-verification-evidence` re-approved at the new timestamp and free of JUnit/source-set instructions.

- [ ] **Step 1: Add RED migration assertions**

Create the acceptance test with this base:

```kotlin
package org.ftckb.cli

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import org.ftckb.domain.ApproverRole
import org.ftckb.domain.KnowledgeRule
import org.ftckb.domain.RuleStatus
import org.ftckb.knowledge.FileKnowledgeRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TestUtilityLayoutAcceptanceTest {
    private val root=Path.of("..","..").normalize()
    private val reapproved=setOf(
        "shared.rookiebot-java-imports",
        "shared.rookiebot-verification-evidence"
    )

    private fun rules():List<KnowledgeRule> {
        val loaded=FileKnowledgeRepository.load(root.resolve("knowledge"))
        assertTrue(loaded.violations.isEmpty(),loaded.violations.joinToString())
        return loaded.rules
    }

    @Test
    fun `RookieBot guidance no longer recommends JUnit test source sets`() {
        val selected=rules().filter { it.id in reapproved }
        assertEquals(reapproved,selected.map { it.id }.toSet())
        val forbidden=listOf("org.junit","junit.","testDebugUnitTest","TeamCode/src/test","单元测试")
        selected.forEach { rule ->
            assertEquals(RuleStatus.APPROVED,rule.status,rule.id)
            assertNotNull(rule.approval,rule.id)
            assertEquals("lucasnotfound59",rule.approval!!.approver,rule.id)
            assertEquals(ApproverRole.OVERALL_SOFTWARE_LEAD,rule.approval!!.role,rule.id)
            assertEquals(Instant.parse("2026-09-13T09:12:38Z"),rule.approval!!.approvedAt,rule.id)
            val current=listOf(rule.title,rule.instruction,rule.positiveExample.orEmpty(),rule.negativeExample.orEmpty())
                .joinToString("\n")
            forbidden.forEach { assertFalse(current.contains(it,ignoreCase=true),"${rule.id}: $it") }
        }
        val guide=Files.readString(root.resolve("knowledge/guides/practices/rookiebot-tutorial.md"))
        forbidden.forEach { assertFalse(guide.contains(it,ignoreCase=true),"guide: $it") }
        assertTrue(guide.contains("teamcode/tests/"))
        assertTrue(guide.contains("teamcode/utils/"))
        assertTrue(guide.contains(":TeamCode:assembleDebug"))
    }
}
```

- [ ] **Step 2: Run RED**

```bash
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
  ./gradlew :apps:knowledge-cli:test \
  --tests '*TestUtilityLayoutAcceptanceTest' --console=plain
```

Expected: assertions fail on current `org.junit.Assert`, `TeamCode/src/test`, `testDebugUnitTest`, old approval timestamps, and missing canonical directory guidance.

- [ ] **Step 3: Replace only the JUnit-bearing parts of the Java import rule**

Within `shared.rookiebot-java-imports`, preserve ID/topic/status/authority/policyLevel/applicability and use these exact current values:

```yaml
title: "使用正确的项目类与 FTC API 导入"
instruction: "适用范围：采用 RookieBot 新手教程约定的项目；不要求其他项目更换架构。项目的 Constants 必须解析为本项目的类，不能误导入 com.sun.tools.javac.util.Constants。FTC SDK、Pedro 与队伍类必须从实际依赖和项目 package 导入；出现 Android Studio 红线时检查第一条具体报错、import 和 Gradle 配置，并编译生产源集，不要仅靠反复自动补全 imports。"
rationale: "同名项目类与工具链内部类可能被 IDE 错误导入，导致方法缺失或构建失败。"
approval:
  approver: "lucasnotfound59"
  role: overall_software_lead
  approvedAt: 2026-09-13T09:12:38Z
evidence:
  - type: git
    repository: "OLeslieO/FTC2026-RookieBot"
    commit: "558141588e2a0eb766e195ab71df3c188e942891"
    file: "TeamCode/src/main/java/org/firstinspires/ftc/teamcode/Hardwares.java"
    symbol: "Constants"
    line: 14
  - type: git
    repository: "lucasnotfound59/FTC-Knowledge-Bank"
    commit: "6aa385d75484b22b3f73c3b493a695e753ccc76e"
    file: "docs/superpowers/specs/2026-09-13-ftc-test-utils-layout-design.md"
    line: 35
positiveExample: "import org.firstinspires.ftc.teamcode.pedroPathing.Constants;"
negativeExample: "import com.sun.tools.javac.util.Constants;"
```

Do not alter any other RookieBot rule in this step.

- [ ] **Step 4: Replace only the JUnit-bearing parts of the verification rule**

Within `shared.rookiebot-verification-evidence`, preserve ID/topic/status/authority/policyLevel/applicability and use:

```yaml
title: "分别报告构建、机器人测试和真机验证"
instruction: "适用范围：采用 RookieBot 新手教程约定的项目；不要求其他项目更换架构。交付 RookieBot 变更时运行 :TeamCode:assembleDebug，并报告所验证的提交或工作区、命令、结果与实际覆盖。机器人侧测试、诊断和校准 OpMode 放在 teamcode/tests，复用工具类放在 teamcode/utils；只有实际部署并运行的 OpMode 才能报告为机器人测试通过。区分 Android Studio 索引、静态检查、Gradle 构建、部署和真机运行，不能把其中一项表述成另一项。"
rationale: "软件构建、机器人侧测试与真机验证具有不同证据边界；目录清晰不能替代实际部署和运行。"
approval:
  approver: "lucasnotfound59"
  role: overall_software_lead
  approvedAt: 2026-09-13T09:12:38Z
evidence:
  - type: git
    repository: "OLeslieO/FTC2026-RookieBot"
    commit: "558141588e2a0eb766e195ab71df3c188e942891"
    file: "TeamCode/src/main/java/org/firstinspires/ftc/teamcode/examples/README.md"
    line: 1
  - type: git
    repository: "lucasnotfound59/FTC-Knowledge-Bank"
    commit: "6aa385d75484b22b3f73c3b493a695e753ccc76e"
    file: "docs/superpowers/specs/2026-09-13-ftc-test-utils-layout-design.md"
    line: 96
positiveExample: |-
  ./gradlew :TeamCode:assembleDebug
  报告：构建结果；tests OpMode 是否已部署并运行；是否完成真机机构验证。
negativeExample: "仅看到 BUILD SUCCESSFUL 就声称 tests OpMode 或机器人已经运行成功。"
```

- [ ] **Step 5: Rewrite the guide's verification section**

Replace its JUnit/import/test command paragraphs with this content while retaining the surrounding RookieBot tutorial:

```markdown
机器人侧测试、诊断和校准 OpMode 放在
`TeamCode/src/main/java/org/firstinspires/ftc/teamcode/tests/`；可复用工具类放在
`TeamCode/src/main/java/org/firstinspires/ftc/teamcode/utils/`。两个目录都在 `teamcode/` 内，
与 `subsystems/`、`commands/` 平级。不要建立 TeamCode JUnit、`src/test` 或 `src/androidTest` 测试。

项目类和 FTC API 必须使用实际 package；例如项目 `Constants` 不得自动补全成
`com.sun.tools.javac.util.Constants`。先阅读 Android Studio 的第一条具体报错，再检查 import 与 Gradle。

```bash
./gradlew :TeamCode:assembleDebug
```

报告必须分别说明构建、部署、tests OpMode 运行和真机机构验证。未实际部署运行的 OpMode 不得写成已通过。
```

- [ ] **Step 6: Update the old approval regression and run GREEN**

In `TeamChinaRuleMigrationAcceptanceTest.architecture profiles isolate RookieBot and FTCLib rules`, replace the single RookieBot approval expectation with:

```kotlin
val changedRookieRules=setOf(
    "shared.rookiebot-java-imports",
    "shared.rookiebot-verification-evidence"
)
val expectedApproval=when {
    rule.id in changedRookieRules -> Instant.parse("2026-09-13T09:12:38Z")
    isRookie -> Instant.parse("2026-09-06T16:03:37Z")
    else -> Instant.parse("2026-09-06T03:06:57.152813Z")
}
assertEquals(expectedApproval,rule.approval!!.approvedAt,rule.id)
```

Run:

```bash
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
  ./gradlew :modules:knowledge:test --console=plain
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
  ./gradlew :apps:knowledge-cli:test \
  --tests '*TestUtilityLayoutAcceptanceTest' \
  --tests '*TeamChinaRuleMigrationAcceptanceTest' --console=plain
git diff --check -- knowledge/shared/practices/rookiebot-tutorial.yaml \
  knowledge/guides/practices/rookiebot-tutorial.md \
  apps/knowledge-cli/src/test/kotlin/org/ftckb/cli
```

Expected: knowledge validation and both acceptance classes pass; all other RookieBot approval timestamps stay unchanged.

- [ ] **Step 7: Commit Task 1**

```bash
git add -- knowledge/shared/practices/rookiebot-tutorial.yaml \
  knowledge/guides/practices/rookiebot-tutorial.md \
  apps/knowledge-cli/src/test/kotlin/org/ftckb/cli/TeamChinaRuleMigrationAcceptanceTest.kt \
  apps/knowledge-cli/src/test/kotlin/org/ftckb/cli/TestUtilityLayoutAcceptanceTest.kt
git commit -m "fix(knowledge): retire RookieBot JUnit guidance"
```

---

### Task 2: Add the approved global hard layout rule

**Files:**
- Create: `knowledge/global/test-utility-layout.yaml`
- Create: `docs/rule-migrations/2026-09-13-test-utils-layout.md`
- Modify: `apps/knowledge-cli/src/test/kotlin/org/ftckb/cli/TestUtilityLayoutAcceptanceTest.kt`
- Modify: `apps/knowledge-cli/src/test/kotlin/org/ftckb/cli/TeamChinaRuleMigrationAcceptanceTest.kt`

**Interfaces:**
- Consumes: current `RuleCheckKind.PATH_FORBIDDEN` and `REGEX_FORBIDDEN` semantics; Task 1's acceptance class.
- Produces: active rule `global.test-utility-layout`, deterministic hard violations for known wrong paths/JUnit additions, and no violation for canonical directories.

- [ ] **Step 1: Extend acceptance tests RED**

Add imports for `ByteArrayOutputStream`, `PrintStream`, `StringReader`, Jackson `JsonMapper`, `RuleAuthority`, `PolicyLevel`, and `GitRuleEvidence`. Add:

```kotlin
private val mapper=JsonMapper.builder().build()
private val layoutRuleId="global.test-utility-layout"

private fun patch(path:String,line:String)="""
    diff --git a/$path b/$path
    new file mode 100644
    --- /dev/null
    +++ b/$path
    @@ -0,0 +1 @@
    +$line
""".trimIndent()+"\n"

private fun checkPatch(root:Path,path:String,line:String):Pair<Int,com.fasterxml.jackson.databind.JsonNode> {
    val file=root.resolve("change.patch")
    Files.writeString(file,patch(path,line))
    val out=ByteArrayOutputStream()
    val code=runCli(listOf(
        "check",root.toString(),"--knowledge",this.root.resolve("knowledge").toString(),
        "--team","20827","--season","2025-2026","--generic-profile",
        "--diff",file.toString(),"--json"
    ),PrintStream(out),StringReader("").buffered())
    return code to mapper.readTree(out.toString())
}
```

Add a metadata test requiring exact ID/topic/status/authority/policy/applicability, approval timestamp, spec commit/path/line, nonempty checks, and empty reviewTriggers. Add a parameterized loop (ordinary JUnit loop is enough) for these hard cases:

```kotlin
val forbidden=listOf(
    "TeamCode/src/test/java/org/firstinspires/ftc/teamcode/DriveTest.java" to "class DriveTest {}",
    "TeamCode/src/androidTest/java/org/firstinspires/ftc/teamcode/DriveTest.java" to "class DriveTest {}",
    "TeamCode/src/main/java/tests/DriveTest.java" to "class DriveTest {}",
    "TeamCode/src/main/java/org/firstinspires/ftc/utils/AngleUtils.java" to "class AngleUtils {}",
    "TeamCode/src/main/java/org/firstinspires/ftc/teamcode/tests/DriveTest.java" to "import org.junit.Test;",
    "TeamCode/build.gradle" to "testImplementation 'junit:junit:4.13.2'"
)
```

For every case assert exit 1 and at least one `violations.ruleId==layoutRuleId`. For canonical paths:

```kotlin
val allowed=listOf(
    "TeamCode/src/main/java/org/firstinspires/ftc/teamcode/tests/DriveTest.java" to "class DriveTest {}",
    "TeamCode/src/main/java/org/firstinspires/ftc/teamcode/utils/AngleUtils.java" to "class AngleUtils {}"
)
```

Assert exit 0 and no violation with the layout rule ID. Also assert an unrelated subsystem path passes this rule.

- [ ] **Step 2: Run RED**

```bash
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
  ./gradlew :apps:knowledge-cli:test \
  --tests '*TestUtilityLayoutAcceptanceTest' --console=plain
```

Expected: metadata cannot find the rule and every forbidden-path assertion fails.

- [ ] **Step 3: Create the exact YAML v4 rule**

Create `knowledge/global/test-utility-layout.yaml`:

```yaml
# 2026-09-13 user-approved cross-season project layout policy.
# Decision evidence: docs/superpowers/specs/2026-09-13-ftc-test-utils-layout-design.md.
schemaVersion: 4
rules:
  - id: global.test-utility-layout
    topic: test-utility-layout
    title: Keep robot tests and utilities in canonical TeamCode packages
    instruction: "机器人侧测试、诊断和校准 OpMode 必须放在 TeamCode/src/main/java/org/firstinspires/ftc/teamcode/tests/；可复用且无机构所有权的 helper、conversion 和 adapter 类必须放在同级 utils/。tests/ 与 utils/ 和 subsystems/、commands/ 平级，package 声明必须匹配目录；subsystem 或 command 不得伪装成 utils。目标 TeamCode 项目不创建或使用 JUnit、src/test 或 src/androidTest。"
    rationale: "固定目录让队员和 Agent 能区分可部署测试、复用工具、机构能力与命令编排；明确排除 JUnit 可避免同时维护两套测试入口。"
    status: approved
    approval:
      approver: "lucasnotfound59"
      role: overall_software_lead
      approvedAt: 2026-09-13T09:12:38Z
    authority: shared
    policyLevel: global
    applicability:
      teams: []
      seasons: []
      profiles: []
    evidence:
      - type: git
        repository: "lucasnotfound59/FTC-Knowledge-Bank"
        commit: "6aa385d75484b22b3f73c3b493a695e753ccc76e"
        file: "docs/superpowers/specs/2026-09-13-ftc-test-utils-layout-design.md"
        line: 8
    positiveExample: |-
      TeamCode/src/main/java/org/firstinspires/ftc/teamcode/tests/DriveDiagnostic.java
      TeamCode/src/main/java/org/firstinspires/ftc/teamcode/utils/AngleUtils.java
    negativeExample: |-
      TeamCode/src/main/java/tests/DriveDiagnostic.java
      TeamCode/src/test/java/org/firstinspires/ftc/teamcode/DriveTest.java
    checks:
      - kind: path-forbidden
        pattern: "TeamCode/src/{test,androidTest}/**"
        note: "TeamCode 不使用 JUnit test/androidTest source set；机器人侧测试放入 teamcode/tests"
      - kind: path-forbidden
        pattern: "TeamCode/src/main/java/{tests,utils}/**"
        note: "tests/utils 不能直接建立在 src/main/java 下；放入 org/firstinspires/ftc/teamcode"
      - kind: path-forbidden
        pattern: "TeamCode/src/main/java/org/firstinspires/ftc/{tests,utils}/**"
        note: "tests/utils 必须位于 teamcode 包内，不能与 teamcode 平级"
      - kind: path-forbidden
        pattern: "TeamCode/src/main/java/org/firstinspires/ftc/teamcode/{test,util,tool,tools}/**"
        note: "使用统一的复数目录名 tests 或 utils"
      - kind: regex-forbidden
        pattern: '(?i)^\s*import\s+(?:static\s+)?(?:org\.junit|junit\.)'
        appliesTo: "TeamCode/**/*.java"
        note: "目标 TeamCode 项目不使用 JUnit；改为 teamcode/tests 中的机器人侧 OpMode"
      - kind: regex-forbidden
        pattern: '(?i)\b(?:testImplementation|androidTestImplementation|testCompile|androidTestCompile)\b.*(?:org\.junit|junit:)'
        appliesTo: "TeamCode/build.gradle*"
        note: "目标 TeamCode 构建不添加 JUnit test dependency"
```

- [ ] **Step 4: Record migration and conflict provenance**

Create `docs/rule-migrations/2026-09-13-test-utils-layout.md` containing the exact rule ID, paths, approval timestamp and spec commit; the TeamChina `teleops/*Test.java` conflict; the two RookieBot JUnit passages replaced in Task 1; cross-season intent; the deterministic-check limitations; and a statement that the policy does not prove deployment or robot operation.

- [ ] **Step 5: Update global migration regression**

In `TeamChinaRuleMigrationAcceptanceTest`:

```kotlin
private val layoutRule="global.test-utility-layout"
```

- expect the set of global IDs to be `migrated.keys+commands+layoutRule` and global count 12;
- expect repository totals 47 rules, 41 approved, and 6 candidate;
- for `layoutRule`, expect empty seasons/profiles and null `supersedes`; keep the existing 11 expectations unchanged;
- expect `resolve(season="2026-2027")` to activate exactly `layoutRule` among `global.*` IDs;
- require its evidence repository/commit/file/line from Global Constraints;
- require its checks to be nonempty; continue requiring the prior 11 semantic rules to have empty checks.

Use explicit branches such as:

```kotlin
val isLayout=rule.id==layoutRule
assertEquals(if (isLayout) emptySet() else setOf("2025-2026"),rule.applicability.seasons,rule.id)
assertEquals(if (rule.id in commands) setOf("command-based") else emptySet(),rule.applicability.profiles,rule.id)
assertEquals(if (isLayout) null else migrated[rule.id],rule.supersedes,rule.id)
assertEquals(isLayout,rule.checks.isNotEmpty(),rule.id)
```

- [ ] **Step 6: Run GREEN and real CLI checks**

```bash
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
  ./gradlew :modules:knowledge:test --console=plain
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
  ./gradlew :apps:knowledge-cli:test \
  --tests '*TestUtilityLayoutAcceptanceTest' \
  --tests '*TeamChinaRuleMigrationAcceptanceTest' --console=plain
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
  ./gradlew :apps:knowledge-cli:installDist --console=plain
apps/knowledge-cli/build/install/ftckb/bin/ftckb validate knowledge --json
apps/knowledge-cli/build/install/ftckb/bin/ftckb resolve knowledge \
  --team 20827 --season 2025-2026 --generic-profile --json
git diff --check -- knowledge/global/test-utility-layout.yaml \
  docs/rule-migrations/2026-09-13-test-utils-layout.md \
  apps/knowledge-cli/src/test/kotlin/org/ftckb/cli
```

Expected: validate `ok:true` with 47 rules; generic resolve has 25 active rules and no conflicts; acceptance tests prove forbidden paths/JUnit exit 1 and canonical paths exit 0.

- [ ] **Step 7: Commit Task 2**

```bash
git add -- knowledge/global/test-utility-layout.yaml \
  docs/rule-migrations/2026-09-13-test-utils-layout.md \
  apps/knowledge-cli/src/test/kotlin/org/ftckb/cli/TestUtilityLayoutAcceptanceTest.kt \
  apps/knowledge-cli/src/test/kotlin/org/ftckb/cli/TeamChinaRuleMigrationAcceptanceTest.kt
git commit -m "feat(knowledge): enforce TeamCode test utility layout"
```

---

### Task 3: Publish V0.6.0 counts, contract documentation, and real fixtures

**Files:**
- Modify: `README.md`
- Modify: `AGENTS.md`
- Modify: `todolist.md`
- Modify: `docs/cli-agent.md`
- Modify: `docs/project-integration.md`
- Modify: `docs/kernel-contract.md`
- Modify: `docs/standardizer-check.md`
- Modify: `docs/handbook/installation.md`
- Modify: `docs/handbook/resolution.md`
- Modify: `docs/handbook/rule-schema.md`
- Modify: `docs/handbook/troubleshooting.md`
- Modify: `docs/website/integration-and-checks.md`
- Modify: `fixtures/kernel/validate-ok.json`
- Modify: `fixtures/kernel/resolve-ok.json`
- Modify: `apps/knowledge-cli/src/test/kotlin/org/ftckb/cli/CliDocumentationAcceptanceTest.kt`
- Modify: `apps/knowledge-cli/src/test/kotlin/org/ftckb/cli/KernelJsonAcceptanceTest.kt`

**Interfaces:**
- Consumes: V0.5.0 triggered soft behavior plus Task 2's real 47-rule knowledge tree.
- Produces: current V0.6.0 public documentation and CLI-generated v2 fixtures with 47 validated rules and 25 generic active rules.

- [ ] **Step 1: Add RED documentation/count assertions**

Update `CliDocumentationAcceptanceTest` to require:

```kotlin
assertTrue(readme.contains("**版本：V0.6.0**"))
assertTrue(readme.contains("47（41 已批准 + 6 候选）"))
```

Change the profile table expectations to:

```kotlin
listOf("generic" to 25,"command-based" to 28,"rookiebot" to 37,"ftclib-command" to 29)
```

Require current documentation to contain the two canonical paths, `global.test-utility-layout`, “5 条生效规则带硬检查”, exit 1, and the boundary that Knowledge Bank's own JUnit tests are not prohibited. Reject stale current values V0.5.0/46/40/24/27/36/28 where they are not explicitly historical.

In `KernelJsonAcceptanceTest`, require `validate-ok.json.ruleCount==47`; require `resolve-ok.json.activeRules.size()==25`; find `global.test-utility-layout` and assert approved/global/empty applicability/nonempty checks/exact evidence; retain the Limelight empty-check/trigger assertions from V0.5.0.

- [ ] **Step 2: Run RED**

```bash
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
  ./gradlew :apps:knowledge-cli:test \
  --tests '*CliDocumentationAcceptanceTest' \
  --tests '*KernelJsonAcceptanceTest' --console=plain
```

Expected: V0.6.0/count/path/fixture assertions fail against the V0.5.0 docs and 46-rule fixtures.

- [ ] **Step 3: Update every current public statement**

Across the listed files, publish these exact current facts:

```text
repository V0.6.0
CLI 2.0.0
YAML v4
kernel JSON v2
project integration protocol v2
47 total = 41 approved + 6 candidate
generic 25; command-based 28; rookiebot 37; ftclib-command 29
5 active rules carry hard checks
```

Document that `global.test-utility-layout` is cross-season and hard for deterministic wrong paths/JUnit additions; exact canonical paths; full Agent instruction still governs semantic file classification; check success is not deployment/robot proof. Mark the test-layout roadmap item complete. Preserve historical V0.4.0 and V0.5.0 statements when explicitly labeled historical; do not rewrite old migration/design records as current release docs.

- [ ] **Step 4: Regenerate both repository-backed fixtures**

Build the launcher, capture stdout from these commands into temporary files, validate schemaVersion/command/count/profile/conflicts, then replace the fixture content with the exact one-line stdout using `apply_patch`:

```bash
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
  ./gradlew :apps:knowledge-cli:installDist --console=plain
apps/knowledge-cli/build/install/ftckb/bin/ftckb validate knowledge --json
apps/knowledge-cli/build/install/ftckb/bin/ftckb resolve knowledge \
  --team 20827 --season 2025-2026 --generic-profile --json
```

Replace only `fixtures/kernel/validate-ok.json` and `fixtures/kernel/resolve-ok.json`; do not hand-edit individual rule objects and do not alter the other eight synthetic fixtures.

- [ ] **Step 5: Run documentation and fixture GREEN**

```bash
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
  ./gradlew :apps:knowledge-cli:test \
  --tests '*CliDocumentationAcceptanceTest' \
  --tests '*KernelJsonAcceptanceTest' \
  --tests '*KnowledgeGuideAcceptanceTest' --console=plain
git diff --check -- README.md AGENTS.md todolist.md docs fixtures/kernel \
  apps/knowledge-cli/src/test/kotlin/org/ftckb/cli
```

Expected: selected tests pass; all 10 fixtures still validate against kernel JSON v2; version/count/profile/hard-soft statements agree with actual CLI output.

- [ ] **Step 6: Commit Task 3**

```bash
git add -- README.md AGENTS.md todolist.md docs/cli-agent.md \
  docs/project-integration.md docs/kernel-contract.md docs/standardizer-check.md \
  docs/handbook/installation.md docs/handbook/resolution.md \
  docs/handbook/rule-schema.md docs/handbook/troubleshooting.md \
  docs/website/integration-and-checks.md fixtures/kernel/validate-ok.json \
  fixtures/kernel/resolve-ok.json \
  apps/knowledge-cli/src/test/kotlin/org/ftckb/cli/CliDocumentationAcceptanceTest.kt \
  apps/knowledge-cli/src/test/kotlin/org/ftckb/cli/KernelJsonAcceptanceTest.kt
git commit -m "docs: release TeamCode layout policy v0.6.0"
```

---

### Task 4: Full V0.6.0 regression and delivery audit

**Files:** Read-only verification. Do not create an empty verification commit.

**Interfaces:** Produces final evidence for both V0.5.0 triggered soft behavior and V0.6.0 hard layout policy.

- [ ] **Step 1: Run complete Kotlin/JVM and Android plugin compile**

```bash
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
  ./gradlew test :apps:android-studio-plugin:compileKotlin \
  :apps:knowledge-cli:installDist --console=plain
```

Expected: all tests execute with zero failures/errors/skips and Android plugin compiles. Count fresh XML results; do not copy V0.4.0 totals.

- [ ] **Step 2: Run Python integration and smoke**

```bash
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s tests/integration -v
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' sh scripts/smoke.sh
```

Expected: ordinary Python tests pass with only the explicit opt-in real pinned-source test allowed to skip; smoke reports 9 passed, 0 failed.

- [ ] **Step 3: Verify knowledge, all profiles, hard layout cases, and Limelight soft cases**

Run validate and resolve for both teams with generic, command-based, rookiebot, and ftclib-command; assert 47 rules, counts 25/28/37/29, identical IDs by team, and no conflicts. Use explicitly named `/tmp` patch files and `ftckb check --diff --json` to assert:

- canonical `teamcode/tests` and `teamcode/utils`: exit 0, no layout violation;
- `src/test`, wrong-root `src/main/java/tests`, a JUnit import, and a JUnit dependency: exit 1 with `global.test-utility-layout`;
- unrelated `DriveSubsystem.java`: no Limelight hard/soft;
- `LLResult result=limelight.getLatestResult();`: both Limelight IDs exactly once in soft and exit 0.

Delete only the exact temporary patch files created by this step.

- [ ] **Step 4: Audit diff and branch**

```bash
git diff --check
git status --short --branch
git log --oneline origin/main..HEAD
```

Expected: no unstaged task files; only the four known user-owned untracked paths remain; all commits are task-scoped; branch remains unpushed.

- [ ] **Step 5: Report boundaries**

Report exact Kotlin test totals, Python result/skip, smoke result, 47/41/6 counts, four profile counts, hard/soft patch outcomes, conflicts, and the absence of Android Studio UI/RC/DS/deployment/robot validation. Do not tag or push until explicitly requested.
