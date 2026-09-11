# Global Team Rules and TeamChina Standards Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 global 规则在内核、CLI、Agent 接入和知识数据中真实生效，并把 20827／16093 经验与 TeamChina 写作标准合并为可审计的全局规则。

**Architecture:** Domain 继续作为唯一裁决源，YAML v4 显式区分 source authority、policy level 与 applicability；CLI kernel JSON v2 将 profile、排除、覆盖和冲突原因交给所有消费者。知识迁移只增加 3 条命令式规则，并用固定 Git 证据与迁移报告保存所有冲突和审批变化。

**Tech Stack:** Kotlin/JVM、JDK 21、Gradle、JUnit Jupiter、SnakeYAML Engine、Jackson、JGit、Python 3.10+、unittest、jsonschema>=4.18,<5、YAML/JSON/Markdown。

## Global Constraints

- 设计依据：`docs/superpowers/specs/2026-09-11-team-rules-global-teamchina-design.md`；TeamChina 固定提交为 `9be3eb7776f35d71e60cec4cb47be6bd7892acee`。
- 裁决顺序固定为 `OFFICIAL > GLOBAL > LOCAL > SHARED`；TeamChina 优先只处理写作标准冲突，不覆盖官方 API、比赛规则或安全事实。
- 保留 `Hardwares` 容器标准；允许 TeamChina 覆盖 m 前缀、统一 BRAKE、固定注释模板和固定 telemetry 分隔符。
- 两队 8 条规则去除 team 限制但保留 `2025-2026`；6 条 approved 获得新的 overall-lead 审批，2 条 candidate 不转正。
- YAML schemaVersion=4；kernel JSON schemaVersion=2；新项目接入配置 schemaVersion/kernelSchemaVersion/integrationVersion 均为 2。
- `command-based` 规则只在对应 profile 生效；generic 项目不能收到命令架构要求。
- 仓库发布版本为 `V0.4.0`；CLI 因机器契约发生破坏性升级而使用 `2.0.0`。两者分别说明，不能混写。
- 直接使用当前 `main`，不创建分支或 worktree；保留用户已有 `.DS_Store`、docs-site 计划和 `dsh-client-ui-tokyo-night-dsh/`。
- 不调用模型 API、不使用历史 DeepSeek key、不修改机器人项目、不自动 push/tag/release。
- Kotlin 延续仓库格式：赋值与运算符两侧不加空格。
- 每个任务使用 TDD、只暂存列明文件并形成可审查本地提交；提交前运行任务测试和 `git diff --check`。
- Gradle 命令使用 Android Studio JDK 21：`JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home'`，不得把该机器路径写入仓库。
- 软件测试、CLI 检查和 JSON Schema 验证不等于 Robot Controller、Driver Station 或真机验证。

---

## File Map

| 边界 | 文件职责 |
| --- | --- |
| Domain | `RuleProfiles.kt` 规范化 profile；`RuleResolver.kt` 执行适用性、四级优先级、排除／覆盖／冲突解释 |
| YAML | `RuleYamlCodec.kt` 严格读取 v4，并兼容 v1-v3 |
| CLI contract | `ProfileArguments.kt` 统一 profile 参数；`KernelJson.kt` 统一 schema v2 输出；`Main.kt` 与 `CheckCommand.kt` 只做命令编排 |
| Runtime | `KnowledgeRetriever.kt`、`SessionRuntime.kt`、Chat/Serve/Eval/IDE 设置传递同一 profile 上下文 |
| Integration | `integration_contract.py` 处理项目协议版本与 profile；integrate/project/verify 只调用确定性 kernel |
| Knowledge | `knowledge/global/team-coding-standards.yaml` 是唯一新全局文件；原 team YAML 删除；迁移报告保留旧审批与冲突 |
| Public contract | README、AGENTS、handbook、kernel schema、fixtures 和 Skill 同步 V0.4.0／kernel v2 |

## Task 0: Record the V0.3.1 baseline

**Files:** Read only; do not modify source files.

**Interfaces:** Produces the exact starting HEAD, dirty paths, rule counts, active IDs, schema version and baseline test result used by later migration assertions.

- [ ] **Step 1: Record Git and existing user files**

Run:

```bash
git status --short --branch
git rev-parse HEAD
git diff --cached --name-only
```

Expected: HEAD includes design commit `1bb2fd0` plus any factual amendment; only the design is ours, and unrelated untracked files remain untouched.

- [ ] **Step 2: Run the existing v1 kernel snapshot**

Run:

```bash
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew :apps:knowledge-cli:installDist --console=plain
apps/knowledge-cli/build/install/ftckb/bin/ftckb validate knowledge --json
apps/knowledge-cli/build/install/ftckb/bin/ftckb resolve knowledge --team 20827 --season 2025-2026 --json
apps/knowledge-cli/build/install/ftckb/bin/ftckb resolve knowledge --team 16093 --season 2025-2026 --json
```

Expected before implementation: schemaVersion=1, validate reports 43 rules, 20827 has six more active team-style rules than 16093, and both resolve calls have `conflicts:[]`.

- [ ] **Step 3: Run focused baseline tests**

Run:

```bash
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew :modules:domain:test :modules:knowledge:test :apps:knowledge-cli:test --console=plain
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s tests/integration -v
```

Expected: record exact pass/fail/skip counts. Any baseline failure must be separated from new regressions before proceeding.

## Task 1: Finish profile normalization and deterministic global resolution

**Files:**
- Modify: `modules/domain/src/main/kotlin/org/ftckb/domain/RuleProfiles.kt`
- Modify: `modules/domain/src/main/kotlin/org/ftckb/domain/RuleResolver.kt`
- Test: `modules/domain/src/test/kotlin/org/ftckb/domain/RuleProfilesTest.kt`
- Test: `modules/domain/src/test/kotlin/org/ftckb/domain/RuleResolverTest.kt`

**Interfaces:** Consumes existing `PolicyLevel`, `EffectivePolicyLevel`, `RulePolicy.level`, `RuleApplicability.profiles`; produces `RuleContext(team,season,profiles)`, `ExcludedRule`, `OverriddenRule`, the expanded `RuleConflict`, and `ResolutionResult`.

- [ ] **Step 1: Write failing command-profile and priority tests**

Add these assertions to the existing tests:

```kotlin
@Test fun `ftclib is command based and simple opmode is incompatible`() {
    assertEquals(setOf("command-based","ftclib-command"),RuleProfiles.normalize(setOf("ftclib-command")))
    assertThrows(RuleContextException::class.java) {
        RuleProfiles.normalize(setOf("simple-opmode","command-based"))
    }
}

@Test fun `global beats local and keeps unrelated topics`() {
    val global=rule("shared.global","naming",RuleAuthority.SHARED).copy(policyLevel=PolicyLevel.GLOBAL)
    val local=rule("team.local","naming",RuleAuthority.TEAM,setOf("20827"),team)
    val other=rule("shared.other","telemetry",RuleAuthority.SHARED)
    val result=RuleResolver.resolve(listOf(local,other,global),RuleContext("20827","2025-2026",emptySet()))
    assertEquals(listOf("shared.global","shared.other"),result.activeRules.map { it.id })
    assertEquals(listOf("team.local"),result.overriddenRules.map { it.ruleId })
}
```

- [ ] **Step 2: Run the RED tests**

Run:

```bash
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew :modules:domain:test --tests '*RuleProfilesTest' --tests '*RuleResolverTest' --console=plain
```

Expected: failure because `command-based`, profile-aware `RuleContext`, and explanation outputs are not implemented.

- [ ] **Step 3: Implement normalized profiles**

Replace the `RuleProfiles` object body with:

```kotlin
object RuleProfiles {
    val supported:Set<String> =setOf("rookiebot","simple-opmode","command-based","ftclib-command")

    fun normalize(profiles:Set<String>?):Set<String> {
        if (profiles==null) throw RuleContextException("context-required","Select a project profile or explicitly select generic")
        val unknown=profiles-supported
        if (unknown.isNotEmpty()) throw RuleContextException("invalid-context","Unknown profiles: ${unknown.sorted().joinToString()}")
        val normalized=profiles.toSortedSet()
        if ("rookiebot" in normalized) normalized+="simple-opmode"
        if ("ftclib-command" in normalized) normalized+="command-based"
        if ("simple-opmode" in normalized && "command-based" in normalized) {
            throw RuleContextException("invalid-context","simple-opmode and command-based profiles are mutually exclusive")
        }
        return Collections.unmodifiableSet(normalized)
    }
}
```

- [ ] **Step 4: Replace resolver models and selection core**

Use these public types in `RuleResolver.kt`:

```kotlin
class RuleContext(val team:String?,val season:String?,profiles:Set<String>?=null) {
    val profiles:Set<String>?=profiles?.let { Collections.unmodifiableSet(LinkedHashSet(it)) }
}
data class ExcludedRule(val ruleId:String,val reasons:List<String>)
data class OverriddenRule(val ruleId:String,val topic:String,val winnerIds:List<String>,val effectiveLevel:EffectivePolicyLevel)
data class RuleConflict(
    val topic:String,val effectiveLevel:EffectivePolicyLevel,val ruleIds:Set<String>,
    val authorities:Map<String,RuleAuthority>
)
data class ResolutionResult(
    val activeRules:List<KnowledgeRule>,val conflicts:List<RuleConflict>,val profiles:Set<String>,
    val excludedRules:List<ExcludedRule>,val overriddenRules:List<OverriddenRule>
)
```

After existing context and rule validation, normalize `context.profiles`; collect reasons from `status`, `team`, `season`, `profile`; group applicable rules by topic; choose the maximum `RulePolicy.level(rule).rank`; emit one active winner or one conflict; emit every lower-level rule as `OverriddenRule`. Sort active/conflicts/excluded/overridden and every nested ID/reason list deterministically.

- [ ] **Step 5: Add edge-case tests and run GREEN**

Tests must cover official>global, global>local, same effective-level conflict across different authorities, candidate global exclusion, season exclusion, command profile matching, missing profile `context-required`, invalid context, and 100 shuffled input lists producing equal results.

Run:

```bash
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew :modules:domain:test --console=plain
git diff --check -- modules/domain
```

Expected: all domain tests pass; no whitespace errors.

- [ ] **Step 6: Commit the domain slice**

```bash
git add -- modules/domain/src/main/kotlin/org/ftckb/domain/RuleProfiles.kt modules/domain/src/main/kotlin/org/ftckb/domain/RuleResolver.kt modules/domain/src/test/kotlin/org/ftckb/domain/RuleProfilesTest.kt modules/domain/src/test/kotlin/org/ftckb/domain/RuleResolverTest.kt
git commit -m "feat(domain): resolve global rules by project profile"
```

## Task 2: Decode strict YAML v4 without breaking v1-v3

**Files:**
- Modify: `modules/knowledge/src/main/kotlin/org/ftckb/knowledge/RuleYamlCodec.kt`
- Test: `modules/knowledge/src/test/kotlin/org/ftckb/knowledge/RuleYamlCodecTest.kt`

**Interfaces:** `RuleYamlCodec.decode(text:String):List<KnowledgeRule>` accepts schema versions 1-4. Only v4 may declare `policyLevel`, `profiles`, and `reviewTriggers`; v4 requires explicit teams/seasons/profiles.

- [ ] **Step 1: Add a failing v4 fixture**

```kotlin
private fun v4Rule()="""
schemaVersion: 4
rules:
  - id: shared.v4
    topic: v4-test
    title: Test
    instruction: Test instruction
    rationale: Test rationale
    status: candidate
    authority: shared
    policyLevel: global
    applicability:
      teams: []
      seasons: ["2025-2026"]
      profiles: [command-based]
    evidence:
      - type: git
        repository: fixture/repo
        commit: abcdef1
        file: TeamCode/Test.java
        line: 1
""".trimIndent()

@Test fun `decodes explicit v4 policy and profile`() {
    val rule=RuleYamlCodec.decode(v4Rule()).single()
    assertEquals(PolicyLevel.GLOBAL,rule.policyLevel)
    assertEquals(setOf("command-based"),rule.applicability.profiles)
}
```

- [ ] **Step 2: Run RED**

Run:

```bash
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew :modules:knowledge:test --tests '*RuleYamlCodecTest' --console=plain
```

Expected: `unsupported schemaVersion`.

- [ ] **Step 3: Implement v4 decoding**

Extend the allowed version to `1..4`; add `policyLevel` and `reviewTriggers` to rule keys; add `profiles` to applicability keys. For v4 require the applicability map and all three lists, then construct:

```kotlin
val authority=RuleAuthority.valueOf(map.string("authority").uppercase())
val policyLevel=if (schemaVersion==4)
    PolicyLevel.valueOf(map.string("policyLevel").uppercase())
else RulePolicy.legacy(authority)
val profiles=if (schemaVersion==4) applicability.stringSet("profiles") else emptySet()
```

Decode triggers only in v4:

```kotlin
val reviewTriggers=if ("reviewTriggers" !in map) emptyList() else {
    require(schemaVersion==4) { "$name.reviewTriggers requires schemaVersion 4" }
    map.requiredList("reviewTriggers").also {
        require(it.isNotEmpty()) { "$name.reviewTriggers must not be empty when present" }
    }.mapIndexed { index,value ->
        val trigger=value.asMap("$name.reviewTriggers[$index]")
        trigger.rejectUnknownFields(setOf("paths","addedLinePatterns"),"$name.reviewTriggers[$index]")
        RuleReviewTrigger(
            trigger.requiredList("paths").map { it as? String ?: error("paths values must be strings") },
            trigger.requiredList("addedLinePatterns").map { it as? String ?: error("addedLinePatterns values must be strings") }
        )
    }
}
```

Pass `authority`, `policyLevel`, `profiles`, and `reviewTriggers` exactly once to `KnowledgeRule`; keep v3 checks and v2 evidence behavior unchanged.

- [ ] **Step 4: Complete negative and compatibility tests**

Cover v4 missing applicability/teams/seasons/profiles, invalid enum, unknown field, null/wrong types, empty trigger list, invalid trigger values, v3 using v4-only fields, v1 legacy policy mapping, and schemaVersion 5 rejection. Rename the existing “v4 unsupported” test to “v5 unsupported”.

- [ ] **Step 5: Run GREEN and commit**

```bash
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew :modules:domain:test :modules:knowledge:test --console=plain
git diff --check -- modules/knowledge
git add -- modules/knowledge/src/main/kotlin/org/ftckb/knowledge/RuleYamlCodec.kt modules/knowledge/src/test/kotlin/org/ftckb/knowledge/RuleYamlCodecTest.kt
git commit -m "feat(knowledge): decode explicit v4 policy rules"
```

Expected: domain and knowledge tests pass.

## Task 3: Publish profile-aware kernel JSON v2

**Files:**
- Create: `apps/knowledge-cli/src/main/kotlin/org/ftckb/cli/ProfileArguments.kt`
- Create: `apps/knowledge-cli/src/test/kotlin/org/ftckb/cli/ProfileArgumentsTest.kt`
- Create: `docs/kernel-contract.v1.schema.json`
- Modify: `apps/knowledge-cli/src/main/kotlin/org/ftckb/cli/Main.kt`
- Modify: `apps/knowledge-cli/src/main/kotlin/org/ftckb/cli/CheckCommand.kt`
- Modify: `apps/knowledge-cli/src/main/kotlin/org/ftckb/cli/KernelJson.kt`
- Modify: `apps/knowledge-cli/src/test/kotlin/org/ftckb/cli/KernelJsonAcceptanceTest.kt`
- Modify: `apps/knowledge-cli/src/test/kotlin/org/ftckb/cli/CheckAcceptanceTest.kt`
- Modify: `apps/knowledge-cli/src/test/kotlin/org/ftckb/cli/MainTest.kt`
- Modify: `docs/kernel-contract.schema.json`

**Interfaces:** `ProfileArguments.extract(args)` returns non-profile args plus nullable selection; `--generic-profile` means explicit empty set. `KernelJson.resolveJson(team,season,result)` and `KernelJson.checkJson(team,season,profiles,outcome)` are the only success serializers.

- [ ] **Step 1: Preserve the old schema and write RED contract tests**

Copy the current schema byte-for-byte to `docs/kernel-contract.v1.schema.json`. Add tests asserting missing profile returns exit 2 with error code `context-required`, while `--generic-profile` succeeds with schemaVersion 2 and `profiles:[]`.

Run:

```bash
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew :apps:knowledge-cli:test --tests '*KernelJsonAcceptanceTest' --console=plain
```

Expected: RED because profile flags and v2 do not exist.

- [ ] **Step 2: Add the shared argument parser**

Create:

```kotlin
package org.ftckb.cli

import org.ftckb.domain.RuleProfiles

data class ProfileSelection(val remaining:List<String>,val profiles:Set<String>?) {
    fun requiredProfiles():Set<String> =RuleProfiles.normalize(profiles)
}

object ProfileArguments {
    fun extract(args:List<String>):ProfileSelection {
        val remaining=mutableListOf<String>()
        val profiles=linkedSetOf<String>()
        var generic=false
        var index=0
        while (index<args.size) when (val value=args[index++]) {
            "--generic-profile" -> { require(!generic) { "duplicate --generic-profile" }; generic=true }
            "--profile" -> {
                require(index<args.size && args[index].isNotBlank() && !args[index].startsWith("--")) { "--profile requires a value" }
                profiles+=args[index++]
            }
            else -> remaining+=value
        }
        require(!generic || profiles.isEmpty()) { "--profile and --generic-profile are mutually exclusive" }
        return ProfileSelection(remaining,if (generic || profiles.isNotEmpty()) profiles else null)
    }
}
```

- [ ] **Step 3: Wire resolve and check**

For resolve/check: parse profile flags before existing pair validation, call `requiredProfiles()`, catch `RuleContextException` as exit 2 using its code, and construct `RuleContext(team,season,profiles)`. Validate accepts neither profile flag. Help text must show `[--profile NAME ... | --generic-profile]`.

- [ ] **Step 4: Centralize kernel JSON v2**

Set `KernelJson.SCHEMA_VERSION=2`. Serialize rules with `policyLevel`, applicability profiles, checks, evidence and reviewTriggers. Resolve output must contain normalized `profiles`, `activeRules`, `excludedRules`, `overriddenRules`, and conflicts shaped as:

```json
{
  "topic":"naming-conventions",
  "effectiveLevel":"global",
  "ruleIds":["global.a","global.b"],
  "authorities":{"global.a":"shared","global.b":"shared"}
}
```

Move check serialization from `CheckCommand.kt` into `KernelJson.checkJson`; include schemaVersion, command, team, season, sorted profiles, ok, violations and soft. Ensure `errorJson` includes `command:"check"` for check failures.

- [ ] **Step 5: Upgrade the JSON Schema**

Make schemaVersion `const:2`; add profile enum `rookiebot|simple-opmode|command-based|ftclib-command`; require the new resolve arrays and rule fields; add error codes `context-required` and `invalid-context`. Require success branches not to contain `error`; retain check violation and evidence definitions.

- [ ] **Step 6: Complete argument/contract tests and GREEN**

Cover duplicate generic, missing profile value, profile+generic, unknown profile, command normalization, usage/load/invalid-knowledge/conflict/check-hard outputs, deterministic array ordering, and schema validation of every output.

Run and commit:

```bash
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew :apps:knowledge-cli:test --tests '*ProfileArgumentsTest' --tests '*KernelJsonAcceptanceTest' --tests '*CheckAcceptanceTest' --tests '*MainTest' --console=plain
git diff --check -- apps/knowledge-cli docs/kernel-contract.schema.json docs/kernel-contract.v1.schema.json
git add -- apps/knowledge-cli/src/main/kotlin/org/ftckb/cli/ProfileArguments.kt apps/knowledge-cli/src/main/kotlin/org/ftckb/cli/Main.kt apps/knowledge-cli/src/main/kotlin/org/ftckb/cli/CheckCommand.kt apps/knowledge-cli/src/main/kotlin/org/ftckb/cli/KernelJson.kt apps/knowledge-cli/src/test/kotlin/org/ftckb/cli/ProfileArgumentsTest.kt apps/knowledge-cli/src/test/kotlin/org/ftckb/cli/KernelJsonAcceptanceTest.kt apps/knowledge-cli/src/test/kotlin/org/ftckb/cli/CheckAcceptanceTest.kt apps/knowledge-cli/src/test/kotlin/org/ftckb/cli/MainTest.kt docs/kernel-contract.schema.json docs/kernel-contract.v1.schema.json
git commit -m "feat(cli): publish profile-aware kernel v2"
```

Expected: selected CLI tests pass and all emitted JSON validates.

## Task 4: Propagate one rule context through chat, web, eval and Android Studio

**Files:**
- Modify: `modules/agent-runtime/src/main/kotlin/org/ftckb/agent/KnowledgeRetriever.kt`
- Modify: `modules/session-shell/src/main/kotlin/org/ftckb/session/SessionRuntime.kt`
- Modify: `modules/session-shell/src/main/kotlin/org/ftckb/session/AskChatSession.kt`
- Modify: `apps/knowledge-cli/src/main/kotlin/org/ftckb/cli/ChatOptions.kt`
- Modify: `apps/knowledge-cli/src/main/kotlin/org/ftckb/cli/ProductionChatLauncher.kt`
- Modify: `apps/knowledge-cli/src/main/kotlin/org/ftckb/cli/ServeOptions.kt`
- Modify: `apps/knowledge-cli/src/main/kotlin/org/ftckb/cli/ServeCommand.kt`
- Modify: `apps/knowledge-cli/src/main/kotlin/org/ftckb/cli/ServeApi.kt`
- Modify: `apps/knowledge-cli/src/main/kotlin/org/ftckb/cli/EvalCommand.kt`
- Modify: `apps/knowledge-cli/src/main/resources/web/index.html`
- Modify: `apps/android-studio-plugin/src/main/kotlin/org/ftckb/intellij/FtckbSettings.kt`
- Modify: `apps/android-studio-plugin/src/main/kotlin/org/ftckb/intellij/FtckbSettingsDialog.kt`
- Modify: `apps/android-studio-plugin/src/main/kotlin/org/ftckb/intellij/FtckbService.kt`
- Modify: `modules/agent-runtime/src/test/kotlin/org/ftckb/agent/KnowledgeRetrieverTest.kt`
- Modify: `modules/agent-runtime/src/test/kotlin/org/ftckb/agent/AskAgentTest.kt`
- Modify: `modules/agent-runtime/src/test/kotlin/org/ftckb/agent/ContextRetrieverFallbackTest.kt`
- Modify: `modules/agent-runtime/src/test/kotlin/org/ftckb/agent/SessionControllerTest.kt`
- Modify: `modules/agent-runtime/src/test/kotlin/org/ftckb/agent/AnswerGeneratorTest.kt`
- Modify: `modules/agent-runtime/src/test/kotlin/org/ftckb/agent/RetrievalPlannerTest.kt`
- Modify: `modules/agent-runtime/src/test/kotlin/org/ftckb/agent/edit/EditAgentTest.kt`
- Modify: `apps/knowledge-cli/src/test/kotlin/org/ftckb/cli/ChatReplTest.kt`
- Modify: `apps/knowledge-cli/src/test/kotlin/org/ftckb/cli/EditReplAcceptanceTest.kt`
- Modify: `apps/knowledge-cli/src/test/kotlin/org/ftckb/cli/HostileRepositoryAcceptanceTest.kt`
- Modify: `apps/knowledge-cli/src/test/kotlin/org/ftckb/cli/MainTest.kt`
- Modify: `apps/knowledge-cli/src/test/kotlin/org/ftckb/cli/ServeAcceptanceTest.kt`
- Modify: `apps/knowledge-cli/src/test/kotlin/org/ftckb/cli/AgentQualityEvaluationTest.kt`
- Modify: `fixtures/agent/eval/cases.yaml`

**Interfaces:** `KnowledgeRetriever(...,ruleProfiles:Set<String>?)` refuses missing context and conflicts. `SessionRuntime` stores normalized `ruleProfiles`; Chat/Serve/IDE explicitly choose generic or named profiles.

- [ ] **Step 1: Write RED retrieval tests**

```kotlin
@Test fun `missing profile and conflicts fail before model use`() {
    writeKnowledge()
    assertThrows(IllegalArgumentException::class.java) {
        KnowledgeRetriever(tempDir.resolve("knowledge"),"20827","2025-2026")
    }
    val retriever=KnowledgeRetriever(
        tempDir.resolve("knowledge"),"20827","2025-2026",ruleProfiles=emptySet()
    )
    assertNotNull(retriever)
}
```

Add a two-rule same-topic fixture and assert construction fails before any fake provider call.

- [ ] **Step 2: Implement the retrieval boundary**

Add `ruleProfiles:Set<String>?=null` after `guideLimits`. Resolve with `RuleContext(team,season,ruleProfiles)`, require conflicts empty with topic/IDs in the message, and expose only active rules. Do not catch profile errors and replace them with generic.

- [ ] **Step 3: Extend session and CLI options**

Add `ruleProfiles:Set<String>` to `ChatOptions`/`ServeOptions`; parse them through `ProfileArguments`. Add `initialRuleProfiles:Set<String>` to `SessionRuntime`, store an immutable normalized set, pass it to every `KnowledgeRetriever`, preserve it across provider changes, and require an explicit next set when repository context changes.

`ChatStatus` and Serve status JSON include sorted profiles. Configure requests accept only a string array; `[]` is generic and missing/null is invalid. Preflight new knowledge/profile state before mutating provider or conversation state.

- [ ] **Step 4: Add explicit UI choices**

Web and Android Studio expose `generic`, `rookiebot`, `simple-opmode`, `command-based`, `ftclib-command`. Store `generic` as an explicit empty set at runtime. Android settings use:

```kotlin
fun selectedRuleProfiles(value:String):Set<String> =when (val text=value.trim()) {
    "generic" -> emptySet()
    "" -> RuleProfiles.normalize(null)
    else -> RuleProfiles.normalize(setOf(text))
}
```

Never infer a command profile merely because a dependency or class name exists.

- [ ] **Step 5: Version eval cases and update all constructors**

Set eval case schemaVersion to 2 and require `profiles` in every case; existing five fixtures use `profiles:[]`. Update all tests and call sites to pass an explicit `emptySet()` when they mean generic. Keep fake/scripted providers only.

- [ ] **Step 6: Run module tests and commit**

```bash
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew :modules:agent-runtime:test :modules:session-shell:test :apps:knowledge-cli:test --tests '*ChatReplTest' --tests '*ServeAcceptanceTest' --tests '*AgentQualityEvaluationTest' :apps:android-studio-plugin:compileKotlin --console=plain
git diff --check -- modules/agent-runtime modules/session-shell apps/knowledge-cli apps/android-studio-plugin fixtures/agent/eval/cases.yaml
git add -- modules/agent-runtime/src/main/kotlin/org/ftckb/agent/KnowledgeRetriever.kt modules/session-shell/src/main/kotlin/org/ftckb/session/SessionRuntime.kt modules/session-shell/src/main/kotlin/org/ftckb/session/AskChatSession.kt apps/knowledge-cli/src/main/kotlin/org/ftckb/cli/ChatOptions.kt apps/knowledge-cli/src/main/kotlin/org/ftckb/cli/ProductionChatLauncher.kt apps/knowledge-cli/src/main/kotlin/org/ftckb/cli/ServeOptions.kt apps/knowledge-cli/src/main/kotlin/org/ftckb/cli/ServeCommand.kt apps/knowledge-cli/src/main/kotlin/org/ftckb/cli/ServeApi.kt apps/knowledge-cli/src/main/kotlin/org/ftckb/cli/EvalCommand.kt apps/knowledge-cli/src/main/resources/web/index.html apps/android-studio-plugin/src/main/kotlin/org/ftckb/intellij/FtckbSettings.kt apps/android-studio-plugin/src/main/kotlin/org/ftckb/intellij/FtckbSettingsDialog.kt apps/android-studio-plugin/src/main/kotlin/org/ftckb/intellij/FtckbService.kt modules/agent-runtime/src/test/kotlin/org/ftckb/agent/KnowledgeRetrieverTest.kt modules/agent-runtime/src/test/kotlin/org/ftckb/agent/AskAgentTest.kt modules/agent-runtime/src/test/kotlin/org/ftckb/agent/ContextRetrieverFallbackTest.kt modules/agent-runtime/src/test/kotlin/org/ftckb/agent/SessionControllerTest.kt modules/agent-runtime/src/test/kotlin/org/ftckb/agent/AnswerGeneratorTest.kt modules/agent-runtime/src/test/kotlin/org/ftckb/agent/RetrievalPlannerTest.kt modules/agent-runtime/src/test/kotlin/org/ftckb/agent/edit/EditAgentTest.kt apps/knowledge-cli/src/test/kotlin/org/ftckb/cli/ChatReplTest.kt apps/knowledge-cli/src/test/kotlin/org/ftckb/cli/EditReplAcceptanceTest.kt apps/knowledge-cli/src/test/kotlin/org/ftckb/cli/HostileRepositoryAcceptanceTest.kt apps/knowledge-cli/src/test/kotlin/org/ftckb/cli/MainTest.kt apps/knowledge-cli/src/test/kotlin/org/ftckb/cli/ServeAcceptanceTest.kt apps/knowledge-cli/src/test/kotlin/org/ftckb/cli/AgentQualityEvaluationTest.kt fixtures/agent/eval/cases.yaml
git commit -m "feat(runtime): propagate explicit rule profiles"
```

Expected: runtime and selected CLI tests pass; Android plugin compiles. This is not an IDE interaction test.

## Task 5: Upgrade pinned project integration to protocol v2

**Files:**
- Create: `.agents/skills/ftckb-integrate/assets/integration.json`
- Create: `.agents/skills/ftckb-integrate/scripts/integration_contract.py`
- Modify: `.agents/skills/ftckb-integrate/scripts/integrate.py`
- Modify: `.agents/skills/ftckb-integrate/scripts/project.py`
- Modify: `.agents/skills/ftckb-integrate/scripts/verify.py`
- Modify: `.agents/skills/ftckb-integrate/SKILL.md`
- Modify: `.agents/skills/ftckb-integrate/assets/project-skill/SKILL.md`
- Modify: `.agents/skills/ftckb-integrate/assets/AGENTS.block.md`
- Test: `tests/integration/test_integration.py`

**Interfaces:** v2 project config contains `profiles`; `profile_args(config)` returns repeated `--profile` flags or `--generic-profile`; wrapper validates the configured kernelSchemaVersion rather than hard-coded 1.

- [ ] **Step 1: Add RED integration tests**

Test new install with `profiles:[]`, `--profile command-based`, missing choice, unknown profile, mutually exclusive simple/command profiles, v1 pin retention, explicit v1→v2 upgrade, and kernel response whose profiles differ from config.

Add direct helper assertions before installer fixtures:

```python
def test_profile_args_are_explicit(self):
    self.assertEqual(["--generic-profile"],profile_args({"profiles":[]}))
    self.assertEqual(["--profile","command-based"],profile_args({"profiles":["command-based"]}))
    with self.assertRaises(IntegrationError):
        normalize_profiles(None)
    with self.assertRaises(IntegrationError):
        normalize_profiles(["simple-opmode","command-based"])
```

- [ ] **Step 2: Create the capability manifest**

```json
{
  "projectSchemaVersion":2,
  "kernelSchemaVersion":2,
  "integrationVersion":2
}
```

- [ ] **Step 3: Create shared protocol helpers**

`integration_contract.py` exports `IntegrationError`, `versions(config)`, `capabilities(manifest)`, `normalize_profiles(values)`, and:

```python
def profile_args(config):
    profiles=normalize_profiles(config.get("profiles"))
    return [part for profile in profiles for part in ("--profile",profile)] if profiles else ["--generic-profile"]
```

Normalization accepts only a unique string list from the four supported names, adds implied profiles for validation, rejects simple-opmode plus command-based, but preserves the user-selected list in project YAML.

- [ ] **Step 4: Wire integrate/project/verify**

`integrate.py` adds repeatable `--profile` and exclusive `--generic-profile`; new installs require one choice and write protocol version 2. Upgrades without a profile preserve an already valid v2 choice; upgrading v1 to v2 requires explicit selection. `project.py` appends `profile_args(config)` to resolve/check, verifies response schema against `kernelSchemaVersion`, and checks returned normalized profiles. `verify.py` reports protocol versions and profiles.

No script may track `main`, reset target files, commit, push, install hooks, or write API keys.

- [ ] **Step 5: Update managed instructions and run GREEN**

Document the new config and CLI flags in both Skills and the AGENTS block. Then run:

```bash
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s tests/integration -v
git diff --check -- .agents/skills/ftckb-integrate tests/integration
git add -- .agents/skills/ftckb-integrate/assets/integration.json .agents/skills/ftckb-integrate/scripts/integration_contract.py .agents/skills/ftckb-integrate/scripts/integrate.py .agents/skills/ftckb-integrate/scripts/project.py .agents/skills/ftckb-integrate/scripts/verify.py .agents/skills/ftckb-integrate/SKILL.md .agents/skills/ftckb-integrate/assets/project-skill/SKILL.md .agents/skills/ftckb-integrate/assets/AGENTS.block.md tests/integration/test_integration.py
git commit -m "feat(integration): support profile-aware kernel v2"
```

Expected: all Python integration tests pass, including dry-run immutability and pinned-commit checks.

## Task 6: Migrate the two teams and add TeamChina standards

**Files:**
- Create: `knowledge/global/team-coding-standards.yaml`
- Create: `docs/rule-migrations/2026-09-11-team-rules-global-teamchina.md`
- Create: `apps/knowledge-cli/src/test/kotlin/org/ftckb/cli/TeamChinaRuleMigrationAcceptanceTest.kt`
- Modify: `knowledge/shared/rules.yaml`
- Modify: `knowledge/shared/practices/rookiebot-tutorial.yaml`
- Delete: `knowledge/teams/20827/rules.yaml`
- Delete: `knowledge/teams/20827/style-rules.yaml`
- Delete: `knowledge/teams/16093/rules.yaml`

**Interfaces:** Replaces 8 team-scoped IDs with 8 global IDs and adds 3 command rules. Expected repository total becomes 46 rules: 40 approved and 6 candidate.

- [ ] **Step 1: Write migration RED tests before changing YAML**

```kotlin
@Test fun `both teams receive the same global standards`() {
    val loaded=FileKnowledgeRepository.load(Path.of("..","..","knowledge"))
    assertTrue(loaded.violations.isEmpty())
    val a=RuleResolver.resolve(loaded.rules,RuleContext("20827","2025-2026",emptySet()))
    val b=RuleResolver.resolve(loaded.rules,RuleContext("16093","2025-2026",emptySet()))
    assertEquals(a.activeRules.map { it.id },b.activeRules.map { it.id })
    assertTrue(a.activeRules.any { it.id=="global.hardware-container" })
    assertTrue(a.activeRules.none { it.id.startsWith("team-") })
}

@Test fun `command rules require command profile`() {
    val loaded=FileKnowledgeRepository.load(Path.of("..","..","knowledge"))
    val generic=RuleResolver.resolve(loaded.rules,RuleContext("20827","2025-2026",emptySet()))
    val command=RuleResolver.resolve(loaded.rules,RuleContext("20827","2025-2026",setOf("command-based")))
    assertEquals(0,generic.activeRules.count { it.id.startsWith("global.command-") })
    assertEquals(3,command.activeRules.count { it.id.startsWith("global.command-") })
}
```

Also assert total=46, approved=40, candidate=6; all 11 migrated/new rules have global policy, empty teams, season exactly 2025-2026 and fixed Git evidence; the two candidate IDs never activate. Generic excludes every `shared.rookiebot-*` rule and `shared.ftclib-command-candidate`; rookiebot activates exactly the 12 RookieBot rules; ftclib-command activates the FTCLib rule and the three generic command standards without activating RookieBot.

- [ ] **Step 2: Create the v4 file with exact IDs**

Use these IDs and statuses:

| New ID | Status | Profile | Supersedes/source |
| --- | --- | --- | --- |
| `global.hardware-access-candidate` | candidate | generic | `team-20827.hardware-layer-candidate` |
| `global.hardware-container` | approved | generic | `team-20827.hardware-container` |
| `global.motor-configuration` | approved | generic | `team-20827.motor-init-safety` |
| `global.constants-centralized` | approved | generic | `team-20827.constants-centralized` |
| `global.documentation-intent` | approved | generic | `team-20827.chinese-javadoc` |
| `global.telemetry-organization` | approved | generic | `team-20827.telemetry-multiple` |
| `global.naming-conventions` | approved | generic | `team-20827.naming-conventions` |
| `global.mechanism-state-machine-candidate` | candidate | generic | `team-16093.fsm-candidate` |
| `global.command-responsibilities` | approved | command-based | TeamChina `TeleOpSolo` / subsystem classes |
| `global.command-live-input` | approved | command-based | TeamChina `DriveCommand` |
| `global.command-requirements-cleanup` | approved | command-based | TeamChina `DriveCommand` |

Each rule uses `authority: shared`, `policyLevel: global`, `teams: []`, `seasons: ["2025-2026"]`, and explicit `profiles`. Candidate rules have no approval. Approved rules use a fresh UTC timestamp, `approver:"lucasnotfound59"`, `role:overall_software_lead`; the migration report records that this is the 2026-09-11 global-scope approval and separately preserves every old 2026-08-27 team approval.

- [ ] **Step 3: Encode the approved instructions**

The effective Chinese instructions must say exactly these policies, while rationales may add source-backed explanation:

```text
global.hardware-container: 所有硬件引用集中在一个 Hardwares 容器类中，并按 Sensors、Motors、Servos 等职责分组；子系统接收该容器，不直接从 HardwareMap 获取设备。
global.motor-configuration: 每个电机必须在机构初始化阶段明确配置方向、运行模式和 ZeroPowerBehavior；BRAKE 或 FLOAT 应按机构需要选择，不得无依据统一套用。
global.constants-centralized: 调参与机构参数集中存放并使用能表达用途或单位的名称；可以使用 enum、static final 或配置对象，不在逻辑代码中散落魔数。
global.documentation-intent: 为子系统、工具类和不明显的机构逻辑编写中文说明，重点解释用途、单位、有效范围和安全理由；不强制固定 Javadoc 或分节模板。
global.telemetry-organization: 项目使用 FtcDashboard 时，通过 MultipleTelemetry 同时提供 Driver Station 与 Dashboard 遥测，并按机构或用途组织关键数据；不强制固定分隔符。
global.naming-conventions: 类使用 PascalCase，字段、方法和硬件配置名使用表达机构语义的 lowerCamelCase；不强制电机字段使用 m 前缀。本赛季自动类继续使用 <Side><Color>[Mini] 与 Base 抽象类的组合命名，不据此推断未来赛季路线。
global.command-responsibilities: 命令式项目中，OpMode 负责生命周期与输入绑定，Subsystem 封装机构能力，Command 协调动作与调度。
global.command-live-input: 需要连续响应手柄的 Command 通过 Supplier 在执行时读取输入，不在创建 Command 时固定一次性快照。
global.command-requirements-cleanup: 控制 SubsystemBase 的 CommandBase 显式声明 requirements；产生持续机构输出的命令在结束或中断时恢复安全输出。
```

- [ ] **Step 4: Attach exact evidence and write the conflict ledger**

Use old commits unchanged. TeamChina evidence uses repository `OLeslieO/FGC2026-TeamChina`, commit `9be3eb7776f35d71e60cec4cb47be6bd7892acee`, and exact files/symbols: `Hardwares.java:Hardwares`, `subsystems/Shooter.java:init`, `subsystems/Constants.java:Constants`, `subsystems/Intake.java:Intake`, `opmodes/TeleOpSolo.java:TeleOpSolo`, `commands/DriveCommand.java:DriveCommand`, `commands/DriveCommand.java:end`.

The Markdown ledger contains the four confirmed conflicts, the retained `Hardwares` exception, the non-conflicts for FSM/constants, the reason device values were excluded, old/new IDs, old/new approval metadata, and pinned source links.

- [ ] **Step 5: Encode the existing architecture scopes**

Migrate `knowledge/shared/practices/rookiebot-tutorial.yaml` to v4 with `policyLevel:local` and `profiles:[rookiebot]` for all 12 rules. Migrate `knowledge/shared/rules.yaml` to v4 with `policyLevel:shared` and `profiles:[ftclib-command]`. Preserve IDs, instructions, statuses, evidence, seasons and existing overall-lead approvals; this is a scope-narrowing representation of their existing text, not a new approval or a promotion to global.

- [ ] **Step 6: Delete old loadable team files, run GREEN and commit**

```bash
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew :apps:knowledge-cli:test --tests '*TeamChinaRuleMigrationAcceptanceTest' --console=plain
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew :apps:knowledge-cli:installDist --console=plain
apps/knowledge-cli/build/install/ftckb/bin/ftckb validate knowledge --json
apps/knowledge-cli/build/install/ftckb/bin/ftckb resolve knowledge --team 20827 --season 2025-2026 --generic-profile --json
apps/knowledge-cli/build/install/ftckb/bin/ftckb resolve knowledge --team 16093 --season 2025-2026 --generic-profile --json
apps/knowledge-cli/build/install/ftckb/bin/ftckb resolve knowledge --team 20827 --season 2025-2026 --profile command-based --json
apps/knowledge-cli/build/install/ftckb/bin/ftckb resolve knowledge --team 20827 --season 2025-2026 --profile rookiebot --json
apps/knowledge-cli/build/install/ftckb/bin/ftckb resolve knowledge --team 20827 --season 2025-2026 --profile ftclib-command --json
git diff --check -- knowledge docs/rule-migrations apps/knowledge-cli/src/test/kotlin/org/ftckb/cli/TeamChinaRuleMigrationAcceptanceTest.kt
git add -- knowledge/global/team-coding-standards.yaml knowledge/shared/rules.yaml knowledge/shared/practices/rookiebot-tutorial.yaml knowledge/teams/20827/rules.yaml knowledge/teams/20827/style-rules.yaml knowledge/teams/16093/rules.yaml docs/rule-migrations/2026-09-11-team-rules-global-teamchina.md apps/knowledge-cli/src/test/kotlin/org/ftckb/cli/TeamChinaRuleMigrationAcceptanceTest.kt
git commit -m "feat(knowledge): globalize reviewed team standards"
```

Expected: validate `ok:true`, total 46; generic active IDs equal for both teams; command profile adds exactly three active rules; conflicts empty.

## Task 7: Update V0.4.0 docs, fixtures and public contract

**Files:**
- Modify: `README.md`
- Modify: `AGENTS.md`
- Modify: `docs/kernel-contract.md`
- Modify: `docs/standardizer-check.md`
- Modify: `docs/handbook/approval.md`
- Modify: `docs/handbook/contributing.md`
- Modify: `docs/handbook/resolution.md`
- Modify: `docs/handbook/rule-schema.md`
- Modify: `docs/project-integration.md`
- Modify: `fixtures/kernel/*.json`
- Modify: `apps/knowledge-cli/src/main/kotlin/org/ftckb/cli/Main.kt`
- Modify: `apps/knowledge-cli/src/test/kotlin/org/ftckb/cli/CliDocumentationAcceptanceTest.kt`
- Modify: `apps/knowledge-cli/src/test/kotlin/org/ftckb/cli/KernelJsonAcceptanceTest.kt`
- Modify: `apps/knowledge-cli/src/test/kotlin/org/ftckb/cli/MainTest.kt`
- Modify: `apps/knowledge-cli/src/test/kotlin/org/ftckb/cli/PolicyAcceptanceTest.kt`
- Modify: `apps/knowledge-cli/src/test/kotlin/org/ftckb/cli/KnowledgeGuideAcceptanceTest.kt`
- Modify: `apps/knowledge-cli/src/test/kotlin/org/ftckb/cli/PedroTutorialAcceptanceTest.kt`

**Interfaces:** Publicly documents repository V0.4.0, CLI 2.0.0, YAML v4 and kernel JSON v2; external agents receive copyable profile-aware commands.

- [ ] **Step 1: Add failing documentation/fixture assertions**

Update acceptance tests to require README `V0.4.0`, total `46（40 已批准 + 6 候选）`, kernel schema 2, YAML v4 field tables, four priority levels, and all fixtures validating against the v2 schema.

Use concrete version assertions:

```kotlin
assertTrue(Files.readString(repoRoot.resolve("README.md")).contains("**版本：V0.4.0**"))
assertEquals(2,mapper.readTree(KernelJson.validateJson(46))["schemaVersion"].asInt())
```

- [ ] **Step 2: Update version and help output**

Set:

```kotlin
const val FTCKB_VERSION="2.0.0"
```

Help and docs show:

```bash
ftckb resolve knowledge --team 20827 --season 2025-2026 --generic-profile --json
ftckb check <repo-root> --knowledge knowledge --team 20827 --season 2025-2026 --profile command-based --json
```

Explain that repository V0.4.0, CLI 2.0.0, YAML v4, kernel JSON v2 and project integration v2 are distinct version axes.

- [ ] **Step 3: Rewrite contract and governance text**

Document authority versus policyLevel; OFFICIAL>GLOBAL>LOCAL>SHARED; explicit profile choice; candidate exclusion; season preservation; effective-level conflict; excluded/overridden arrays; exit codes; deterministic sorting; no API key requirement for resolve/check; and compilation versus robot validation.

Approval docs state that widening team→global needs a new overall-lead approval and preserves the old approval in a migration ledger.

- [ ] **Step 4: Regenerate and validate fixtures**

Regenerate validate/resolve/error/check fixtures from the built CLI, never by changing only version numbers. Include `profiles` and v2 conflict fields. Validate every fixture with `docs/kernel-contract.schema.json` through the existing acceptance test.

- [ ] **Step 5: Run docs tests and commit**

```bash
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew :apps:knowledge-cli:test --tests '*CliDocumentationAcceptanceTest' --tests '*KernelJsonAcceptanceTest' --tests '*MainTest' --console=plain
git diff --check -- README.md AGENTS.md docs fixtures/kernel apps/knowledge-cli/src/main/kotlin/org/ftckb/cli/Main.kt apps/knowledge-cli/src/test
git add -- README.md AGENTS.md docs/kernel-contract.md docs/standardizer-check.md docs/handbook/approval.md docs/handbook/contributing.md docs/handbook/resolution.md docs/handbook/rule-schema.md docs/project-integration.md fixtures/kernel/validate-ok.json fixtures/kernel/resolve-ok.json fixtures/kernel/resolve-conflict.json fixtures/kernel/error-usage.json fixtures/kernel/error-invalid-knowledge.json fixtures/kernel/check-pass.json fixtures/kernel/check-hard.json fixtures/kernel/check-error-usage.json fixtures/kernel/check-error-load.json fixtures/kernel/check-error-conflict.json apps/knowledge-cli/src/main/kotlin/org/ftckb/cli/Main.kt apps/knowledge-cli/src/test/kotlin/org/ftckb/cli/CliDocumentationAcceptanceTest.kt apps/knowledge-cli/src/test/kotlin/org/ftckb/cli/KernelJsonAcceptanceTest.kt apps/knowledge-cli/src/test/kotlin/org/ftckb/cli/MainTest.kt apps/knowledge-cli/src/test/kotlin/org/ftckb/cli/PolicyAcceptanceTest.kt apps/knowledge-cli/src/test/kotlin/org/ftckb/cli/KnowledgeGuideAcceptanceTest.kt apps/knowledge-cli/src/test/kotlin/org/ftckb/cli/PedroTutorialAcceptanceTest.kt
git commit -m "docs: release global rule contract v0.4.0"
```

Expected: documentation/fixture acceptance tests pass and version/count snapshots agree with real outputs.

## Task 8: Full regression, deterministic check and delivery audit

**Files:** Read-only verification. A failure returns to the owning task and its listed files; Task 8 does not introduce a catch-all patch.

**Interfaces:** Produces final evidence for all modules, integration, CLI contract and knowledge data; no push/tag/release.

- [ ] **Step 1: Run the complete Kotlin suite**

```bash
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew test :apps:android-studio-plugin:compileKotlin :apps:knowledge-cli:installDist --console=plain
```

Expected: all tests pass; only documented environment-specific skip is acceptable and must be counted.

- [ ] **Step 2: Run the complete Python integration suite**

```bash
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s tests/integration -v
```

Expected: all tests pass without network or model API use.

- [ ] **Step 3: Run real kernel smoke checks**

```bash
apps/knowledge-cli/build/install/ftckb/bin/ftckb validate knowledge --json
apps/knowledge-cli/build/install/ftckb/bin/ftckb resolve knowledge --team 20827 --season 2025-2026 --generic-profile --json
apps/knowledge-cli/build/install/ftckb/bin/ftckb resolve knowledge --team 16093 --season 2025-2026 --generic-profile --json
apps/knowledge-cli/build/install/ftckb/bin/ftckb resolve knowledge --team 20827 --season 2025-2026 --profile command-based --json
apps/knowledge-cli/build/install/ftckb/bin/ftckb resolve knowledge --team 20827 --season 2025-2026 --profile rookiebot --json
apps/knowledge-cli/build/install/ftckb/bin/ftckb resolve knowledge --team 20827 --season 2025-2026 --profile ftclib-command --json
```

Expected: validate 46; both generic results have identical active IDs and no conflicts; command result adds exactly three rules.

- [ ] **Step 4: Run scoped and full worktree checks**

Generate a temporary patch from `origin/main..HEAD` and run `ftckb check --diff` against it with `--generic-profile`; remove only that temporary file afterward. Then run the default full-worktree check. The scoped check must have no hard violation; the full check may report unrelated pre-existing untracked files and must identify them as baseline rather than hiding them. Do not stage user files merely to make the check quieter.

- [ ] **Step 5: Audit commits and repository state**

```bash
git diff --check
git status --short --branch
git log --oneline origin/main..HEAD
git show --stat --oneline HEAD
```

Expected: no task file remains unstaged, unrelated user files are untouched, commits are task-scoped, and branch is only ahead of origin. Do not push until the user explicitly asks.

- [ ] **Step 6: Report the verified boundary**

Do not create an empty verification commit. If any check fails, reopen the task responsible for that file, add a regression test there, and rerun Tasks 7-8. The final report lists exact test counts, commands, commit IDs, soft notices, unverified robot/IDE behavior and the four TeamChina conflicts.
