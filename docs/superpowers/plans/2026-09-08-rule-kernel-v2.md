# Rule Kernel v2 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现明确赛季范围、官方／全局／本地／共享优先级、项目 profile 和可解释人工复核，并让所有入口一致使用 kernel v2。

**Architecture:** Kotlin domain 负责范围、验证、优先级和裁决解释，standardizer 仅检查 diff 并生成人工复核线索。CLI 输出版本化契约；Python 安装器根据固定源码的能力声明选择 v1 或 v2。已有聊天、网页与 IDE 入口复用同一个上下文模型，不各写一套裁决逻辑。

**Tech Stack:** Kotlin/JVM、JDK 21 工具链、Gradle 9.4.0、JUnit Jupiter、SnakeYAML Engine、Jackson、JGit、Python 3.10+、unittest、jsonschema>=4.18,<5。

## 执行进度（2026-09-09）

- Task 0 基线记录完成；Task 1 已实现、独立审查通过并提交为 `7429a3c`。
- Task 1 的 domain 33 项测试于 2026-09-08 全部通过；本次按用户要求直接提交／推送，不重新运行测试。
- Task 2–10 和独立安装进度／超时计划尚未实施；现有 CLI JSON 契约仍为 v1，正式规则未迁移。
- 用户本次明确授权推送当前成果，覆盖下述默认不推送约束；不创建 tag、不合并到 main，不代表后续语义迁移已获审批。

## Global Constraints

- 设计依据：`docs/superpowers/specs/2026-09-08-rule-applicability-priority-design.md`；用户于 2026-09-08 确认详细设计。
- 裁决顺序为：官方规则 > 明确标记的全局规范 > 队伍／项目本地规范。普通共享经验不自动成为全局规范。
- 知识 YAML schemaVersion=4；核心 JSON schemaVersion=2；新版项目的 schemaVersion、kernelSchemaVersion、integrationVersion 均为 2。
- `seasons: []` 明确表示不限赛季，不表示跨季真机验证；旧规则不批量清空 seasons。
- 首批 profile：rookiebot、simple-opmode、ftclib-command；rookiebot 隐含 simple-opmode；两种架构互斥。
- 直接使用用户当前分支；不用 worktree／新分支。本环境的执行技能名为 subagent-driven-development、executing-plans，用户的当前分支要求优先于隔离默认值。
- 不新增模型调用、API key、联网知识检索或机器人部署。测试使用固定 fake/scripted provider，不调用历史密钥。
- 不自动推送或发布 tag，不修改 `/Users/xinlu/Documents/GitHub/FTC2026-RookieBot`。本地检查点提交仅包含本任务已验证文件，目标安装器仍不自动 commit/push。
- 修改前 resolve，交付前 check；知识文件改变后 validate。硬检查通过不等于人工复核或真机验证通过。
- 不吞掉协议、规则冲突或缺失上下文错误；候选和被排除规则不能冒充生效规则。
- 保留用户 staged/unstaged/untracked 工作。网站任务可能并行提交，执行时重新记录 HEAD，不沿用旧 HEAD 假设。
- 代码沿用仓库不在赋值／运算符两侧加空格的风格。
- 用户指定 README 版本标记为 V0.3.1；后续完整 feat 递增次版本，patch 递增补丁版本，大更新递增主版本。内部任务／检查点提交不各自冒充新功能发布；版本文字不等同 tag 已发布。

---

## 执行约定与文件边界

所有路径相对于仓库根 `/Users/xinlu/Documents/ChatGPT/FTC Knowledge bank`；命令也从该目录运行。运行前用 JDK 21+ 设置本机 JAVA_HOME，但不能把个人 JDK／SDK 路径提交到共享文件。

本文的代码块是待实现的函数、字段、断言和调用点，不是已经运行通过的代码。保留现有文件中的无关逻辑；不可把局部片段当作整个文件覆盖。每个 Step 是一个可单独记录的动作，重复 RED/GREEN 命令时分别保存输出。缺少类／函数引起的编译失败仅证明新增接口不存在；行为性测试须在最小接口存在后再确认一次失败。

每个任务结束先运行该任务测试，再执行 `git diff --check`；只暂存该任务明确列出的文件。共享文件存在用户改动时使用可审查的局部暂存；无法分离就不提交并说明，不能 `git add .`。任务中的 commit 消息是建议的本地检查点，不是推送授权。

| 边界 | 文件及职责 |
| --- | --- |
| domain | `RuleModels.kt` 扩展不可变规则数据；新增 `RulePolicy.kt`、`RuleProfiles.kt`；`RuleValidation.kt` 校验；`RuleResolver.kt` 唯一裁决入口 |
| knowledge | `RuleYamlCodec.kt` 支持 v4 并兼容 v1–v3，不引入另一套 YAML 解析器 |
| standardizer | `Standardizer.kt` 保留 diff 收集；新增 `RuleReview.kt` 负责人工触发与覆盖说明 |
| CLI | `ProfileArguments.kt` 统一参数处理；`KernelJson.kt` 统一所有机器输出；Main/Check/Chat/Serve/Extract/Approval/Eval 对接 |
| 会话与 IDE | KnowledgeRetriever、SessionRuntime、ChatStatus、FtckbSettings/Dialog/Service 传递 profile 并拒绝冲突 |
| Python 接入 | 新增 `integration_contract.py` 处理版本能力和 profile；project/integrate/verify 保留路径、pin、托管摘要保护 |
| 数据与契约 | knowledge 全量格式迁移、限定的语义变更审批记录、v1 历史 schema/fixtures、v2 schema/fixtures |
| 文档与测试 | docs 契约／用户说明、AGENTS/CLAUDE、Skill、scripts、Kotlin/Python/隔离真实 CLI 测试 |

任务 1–4 是库内部能力，任务 5–7 接通消费方，任务 8 迁移规则，任务 9–10 闭合安装和验收。任务完成仅代表其指定测试通过；全部闭环和旧版本隔离测试通过前，不发布部分升级版本。

## Task 0: 记录不变性基线

**Files:** Read `AGENTS.md`、批准的设计、`todolist.md`；不修改源码。

**Interfaces:** Consumes 当前 Git 状态与现有 v1 CLI；Produces 本轮执行日志中的 HEAD、用户变更路径、v1 resolve/check 输出。

- [x] 记录 `git status --short --branch`、`git rev-parse HEAD`、`git diff --cached --name-only`。不能清理网站、README、.node-version 或系统生成文件。
- [x] 执行现有产物：

```bash
apps/knowledge-cli/build/install/ftckb/bin/ftckb validate knowledge --json
apps/knowledge-cli/build/install/ftckb/bin/ftckb resolve knowledge --team 20827 --season 2025-2026 --json
apps/knowledge-cli/build/install/ftckb/bin/ftckb check . --knowledge knowledge --team 20827 --season 2025-2026 --json
```

预期基线 schemaVersion=1；之前的 43 条总规则、37 条 active 只是快照，以本次实际输出为准。缺少产物时先 `./gradlew :apps:knowledge-cli:installDist --console=plain`。

- [x] 运行现有定向测试并记录已有失败／跳过，不把基线问题归因于新代码：

```bash
./gradlew :modules:domain:test :modules:knowledge:test :modules:standardizer:test :modules:agent-runtime:test :apps:knowledge-cli:test --console=plain
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s tests/integration -v
```

## Task 1: 不可变层级、profile 和规则验证

**Files:** Create `modules/domain/src/main/kotlin/org/ftckb/domain/RulePolicy.kt`、`RuleProfiles.kt`；Modify 同目录 `RuleModels.kt`、`RuleValidation.kt`；Test `modules/domain/src/test/kotlin/org/ftckb/domain/RulePolicyTest.kt`、`RuleProfilesTest.kt`、`RuleValidatorTest.kt`、`RuleResolverTest.kt`。

**Interfaces:** Produces PolicyLevel、EffectivePolicyLevel、RuleReviewTrigger、RuleProfiles.normalize、RulePolicy.level；KnowledgeRule 新增 policyLevel/reviewTriggers，RuleApplicability 新增 profiles。所有集合构造及 copy 都快照，equals/hashCode/toString 纳入新字段。

- [x] 新增 profile 规范化 RED 测试（完整新文件）：

```kotlin
package org.ftckb.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class RuleProfilesTest {
    @Test fun normalization() {
        assertEquals(setOf("rookiebot","simple-opmode"),RuleProfiles.normalize(setOf("rookiebot")))
        assertEquals(emptySet<String>(),RuleProfiles.normalize(emptySet()))
        assertThrows(RuleContextException::class.java) { RuleProfiles.normalize(null) }
        assertThrows(RuleContextException::class.java) { RuleProfiles.normalize(setOf("rookiebto")) }
        assertThrows(RuleContextException::class.java) {
            RuleProfiles.normalize(setOf("rookiebot","ftclib-command"))
        }
    }
}
```

- [x] RED：`./gradlew :modules:domain:test --tests '*RuleProfilesTest' --console=plain`。
- [x] 实现 `RuleProfiles.kt`：

```kotlin
package org.ftckb.domain

import java.util.Collections

class RuleContextException(val code:String,message:String):IllegalArgumentException(message)

object RuleProfiles {
    val supported:Set<String> =setOf("rookiebot","simple-opmode","ftclib-command")

    fun normalize(profiles:Set<String>?):Set<String> {
        if (profiles==null) throw RuleContextException("context-required","Select a project profile or explicitly select generic")
        val unknown=profiles-supported
        if (unknown.isNotEmpty()) throw RuleContextException("invalid-context","Unknown profiles: ${unknown.sorted().joinToString()}")
        val normalized=profiles.toSortedSet()
        if ("rookiebot" in normalized) normalized+="simple-opmode"
        if (setOf("simple-opmode","ftclib-command").all { it in normalized }) {
            throw RuleContextException("invalid-context","simple-opmode and ftclib-command are mutually exclusive")
        }
        return Collections.unmodifiableSet(normalized)
    }
}
```

- [x] 实现层级的独立函数，保持 source authority 与层级独立：

```kotlin
package org.ftckb.domain

enum class PolicyLevel { GLOBAL,LOCAL,SHARED }
enum class EffectivePolicyLevel(val rank:Int) { SHARED(1),LOCAL(2),GLOBAL(3),OFFICIAL(4) }

object RulePolicy {
    fun legacy(authority:RuleAuthority):PolicyLevel=when (authority) {
        RuleAuthority.OFFICIAL -> PolicyLevel.GLOBAL
        RuleAuthority.TEAM -> PolicyLevel.LOCAL
        RuleAuthority.SHARED -> PolicyLevel.SHARED
    }
    fun level(rule:KnowledgeRule):EffectivePolicyLevel=when {
        rule.authority==RuleAuthority.OFFICIAL -> EffectivePolicyLevel.OFFICIAL
        rule.policyLevel==PolicyLevel.GLOBAL -> EffectivePolicyLevel.GLOBAL
        rule.policyLevel==PolicyLevel.LOCAL -> EffectivePolicyLevel.LOCAL
        else -> EffectivePolicyLevel.SHARED
    }
}
```

- [x] 给 RuleModels 增加字段和快照类；沿用已有 immutableSetSnapshot/immutableListSnapshot：

```kotlin
class RuleReviewTrigger(paths:List<String>,addedLinePatterns:List<String>) {
    val paths:List<String> =immutableListSnapshot(paths)
    val addedLinePatterns:List<String> =immutableListSnapshot(addedLinePatterns)
    override fun equals(other:Any?):Boolean=this===other || other is RuleReviewTrigger &&
        paths==other.paths && addedLinePatterns==other.addedLinePatterns
    override fun hashCode():Int=31*paths.hashCode()+addedLinePatterns.hashCode()
    override fun toString():String="RuleReviewTrigger(paths=$paths, addedLinePatterns=$addedLinePatterns)"
}
```

KnowledgeRule 构造尾部增加 `val policyLevel:PolicyLevel=RulePolicy.legacy(authority)` 和输入 `reviewTriggers:List<RuleReviewTrigger> =emptyList()`，公开只读快照；RuleApplicability 构造和 copy 增加 `profiles:Set<String> =emptySet()`。copy 的默认值使用 this.policyLevel/this.reviewTriggers/this.profiles，不能重新按来源推断而丢失显式值。

- [x] 给 RuleValidator 添加以下校验分支及对应测试；保留原 evidence/approval/check 校验：

```kotlin
if (rule.authority==RuleAuthority.TEAM && rule.policyLevel!=PolicyLevel.LOCAL) reject("policyLevel","team rules require local")
if (rule.authority==RuleAuthority.OFFICIAL && rule.policyLevel!=PolicyLevel.GLOBAL) reject("policyLevel","official rules require global")
if (rule.policyLevel==PolicyLevel.LOCAL && rule.applicability.teams.isEmpty() && rule.applicability.profiles.isEmpty()) {
    reject("applicability","local rules require teams or profiles")
}
runCatching { RuleProfiles.normalize(rule.applicability.profiles) }.exceptionOrNull()?.let {
    reject("applicability.profiles",it.message ?: "invalid profiles")
}
rule.reviewTriggers.forEachIndexed { index,trigger ->
    if (trigger.paths.isEmpty()) reject("reviewTriggers[$index].paths","paths must not be empty")
    trigger.paths.forEach { glob ->
        if (glob.isBlank() || runCatching { FileSystems.getDefault().getPathMatcher("glob:$glob") }.isFailure) {
            reject("reviewTriggers[$index].paths","invalid path glob")
        }
    }
    trigger.addedLinePatterns.forEach { pattern ->
        if (pattern.isBlank() || runCatching { Regex(pattern) }.isFailure) {
            reject("reviewTriggers[$index].addedLinePatterns","invalid regex")
        }
    }
}
```

`RuleIdentity.isCanonicalSeason` 改为：

```kotlin
fun isCanonicalSeason(value:String):Boolean {
    if (!seasonPattern.matches(value)) return false
    val first=value.substring(0,4).toInt()
    val last=value.substring(5,9).toInt()
    return last==first+1
}
```

- [x] GREEN：`./gradlew :modules:domain:test --console=plain`。加入 4 个优先级比较、team 自提 global、local 无范围、错误 profile、非连续赛季和新集合构造／copy 后原输入被修改的断言。
- [x] 本地检查点：`feat(domain): model policy levels and explicit project profiles`。

## Task 2: YAML v4 严格字段与旧格式兼容

**Files:** Modify `modules/knowledge/src/main/kotlin/org/ftckb/knowledge/RuleYamlCodec.kt`；Test `modules/knowledge/src/test/kotlin/org/ftckb/knowledge/RuleYamlCodecTest.kt`。

**Interfaces:** Consumes Task 1 模型；Produces 原接口 `RuleYamlCodec.decode(text:String):List<KnowledgeRule>`，支持版本 1–4，拒绝把 v4 字段塞进旧版本。

- [ ] 在现有测试类加入最小 fixture 和遗漏范围的 RED 测试：

```kotlin
private fun v4Rule(seasons:String="seasons: []")="""
schemaVersion: 4
rules:
  - id: shared.v4
    topic: v4-test
    title: Test
    instruction: Test instruction
    rationale: Test rationale
    status: candidate
    authority: shared
    policyLevel: shared
    applicability:
      teams: []
      $seasons
      profiles: []
    evidence:
      - type: git
        repository: fixture/repo
        commit: abcdef1
        file: TeamCode/Test.java
        line: 1
""".trimIndent()

@Test fun `v4 requires explicit season choice`() {
    assertThrows(IllegalStateException::class.java) { RuleYamlCodec.decode(v4Rule("")) }
    assertEquals(emptySet<String>(),RuleYamlCodec.decode(v4Rule()).single().applicability.seasons)
}
```

- [ ] RED：`./gradlew :modules:knowledge:test --tests '*RuleYamlCodecTest' --console=plain`。
- [ ] 修改根版本检查为 `require(schemaVersion in 1..4)`；ruleKeys 加入 policyLevel/reviewTriggers，applicability 允许 profiles。旧格式出现这三个新字段均拒绝，不静默忽略。v4 的 applicability 用必填 map，四个必填位置执行：

```kotlin
if (schemaVersion==4) {
    map.string("policyLevel")
    listOf("teams","seasons","profiles").forEach { applicability.requiredList(it) }
}
val policyLevel=if (schemaVersion==4) PolicyLevel.valueOf(map.string("policyLevel").uppercase())
    else RulePolicy.legacy(RuleAuthority.valueOf(map.string("authority").uppercase()))
val profiles=if (schemaVersion==4) applicability.stringSet("profiles") else emptySet()
val reviewTriggers=if ("reviewTriggers" !in map) emptyList() else {
    require(schemaVersion==4) { "$name.reviewTriggers requires schemaVersion 4" }
    val values=map.requiredList("reviewTriggers")
    require(values.isNotEmpty()) { "$name.reviewTriggers must not be empty when present" }
    values.mapIndexed { index,value ->
        val trigger=value.asMap("$name.reviewTriggers[$index]")
        trigger.rejectUnknownFields(setOf("paths","addedLinePatterns"),"$name.reviewTriggers[$index]")
        fun strings(key:String)=trigger.requiredList(key).map { it as? String ?: error("$key values must be strings") }
        RuleReviewTrigger(strings("paths"),strings("addedLinePatterns"))
    }
}
```

这里显式空 reviewTriggers 属于格式错误；没有该字段保持旧的项目级提醒。checks 的 v3 下限保持不变，v4 evidence 继续使用带 type 的分支。

- [ ] 增加 v1 official/team/shared 的默认映射，v2 web evidence、v3 checks 保留，v4 null/错误类型/未知字段/空触发器、重复键和 `schemaVersion: 5` 拒绝测试。修正旧测试“v4 不受支持”为“v5 不受支持”。
- [ ] GREEN：`./gradlew :modules:domain:test :modules:knowledge:test --console=plain`。
- [ ] 本地检查点：`feat(knowledge): add explicit v4 rule decoding`。

## Task 3: 确定性裁决和解释输出

**Files:** Modify `modules/domain/src/main/kotlin/org/ftckb/domain/RuleResolver.kt`；Test `modules/domain/src/test/kotlin/org/ftckb/domain/RuleResolverTest.kt`。

**Interfaces:** `RuleContext(team:String?,season:String?,profiles:Set<String>?=null)` 的 profiles 必须快照，null=未选择，emptySet=明确通用。新增 ExcludedRule、OverriddenRule；ResolutionResult 含 normalized profiles。冲突改为有效层级和 ruleId→authority 映射。

RuleContext 的完整替换定义（文件增加 `import java.util.Collections`）：

```kotlin
class RuleContext(val team:String?,val season:String?,profiles:Set<String>?=null) {
    val profiles:Set<String>?=profiles?.let { Collections.unmodifiableSet(LinkedHashSet(it)) }
}
```

- [ ] 扩展现有 `rule()` 测试 helper 使用 Task 1 copy，增加 RED 断言：

```kotlin
@Test fun `global beats local without dropping unrelated topics`() {
    val global=rule("shared.global","naming",RuleAuthority.SHARED).copy(policyLevel=PolicyLevel.GLOBAL)
    val local=rule("team.local","naming",RuleAuthority.TEAM,setOf("20827"),team)
    val other=rule("shared.other","telemetry",RuleAuthority.SHARED)
    val result=RuleResolver.resolve(listOf(local,other,global),RuleContext("20827","2025-2026",emptySet()))
    assertEquals(listOf("shared.global","shared.other"),result.activeRules.map { it.id })
    assertEquals(listOf("team.local"),result.overriddenRules.map { it.ruleId })
}
```

- [ ] RED：`./gradlew :modules:domain:test --tests '*RuleResolverTest' --console=plain`。
- [ ] 固定输出模型：

```kotlin
data class ExcludedRule(val ruleId:String,val reasons:List<String>)
data class OverriddenRule(val ruleId:String,val topic:String,val winnerIds:List<String>,val effectiveLevel:EffectivePolicyLevel)
data class RuleConflict(val topic:String,val effectiveLevel:EffectivePolicyLevel,val ruleIds:Set<String>,val authorities:Map<String,RuleAuthority>)
data class ResolutionResult(
    val activeRules:List<KnowledgeRule>,val conflicts:List<RuleConflict>,val profiles:Set<String>,
    val excludedRules:List<ExcludedRule>,val overriddenRules:List<OverriddenRule>
)
```

- [ ] 保留已有全部 rule validation，然后使用以下裁决核心替换 priority map 分组逻辑：

```kotlin
val profiles=RuleProfiles.normalize(context.profiles)
val excluded=mutableListOf<ExcludedRule>()
val applicable=rules.sortedBy { it.id }.filter { rule ->
    val reasons=buildList {
        if (rule.status!=RuleStatus.APPROVED) add("status")
        if (rule.applicability.teams.isNotEmpty() && context.team !in rule.applicability.teams) add("team")
        if (rule.applicability.seasons.isNotEmpty() && context.season !in rule.applicability.seasons) add("season")
        if (!profiles.containsAll(rule.applicability.profiles)) add("profile")
    }.sorted()
    if (reasons.isNotEmpty()) excluded+=ExcludedRule(rule.id,reasons)
    reasons.isEmpty()
}
val active=mutableListOf<KnowledgeRule>()
val conflicts=mutableListOf<RuleConflict>()
val overridden=mutableListOf<OverriddenRule>()
applicable.groupBy { it.topic }.toSortedMap().forEach { (topic,group) ->
    val level=group.map(RulePolicy::level).maxBy { it.rank }
    val winners=group.filter { RulePolicy.level(it)==level }.sortedBy { it.id }
    val winnerIds=winners.map { it.id }
    if (winners.size==1) active+=winners.single()
    else conflicts+=RuleConflict(topic,level,winnerIds.toSet(),winners.associate { it.id to it.authority })
    group.filter { it.id !in winnerIds }.forEach {
        overridden+=OverriddenRule(it.id,topic,winnerIds,level)
    }
}
return ResolutionResult(active.sortedBy { it.id },conflicts,profiles,excluded,overridden.sortedBy { it.ruleId })
```

- [ ] 修改 domain 原有调用显式传 emptySet；缺少 profile 的专门用例保留 null 并断言 context-required。增加跨来源 local 同级冲突、candidate 即使 global 也不生效、profile 全包含、跨赛季排除、100 次输入 shuffle 输出相等的测试。
- [ ] GREEN：`./gradlew :modules:domain:test --console=plain`。候选校验先于筛选，非法规则不能因为“不适用”而隐藏。
- [ ] 本地检查点：`feat(domain): resolve scoped priorities with explanations`。

## Task 4: 定位人工复核及实际检测覆盖

**Files:** Create `modules/standardizer/src/main/kotlin/org/ftckb/standardizer/RuleReview.kt`、`modules/standardizer/src/test/kotlin/org/ftckb/standardizer/RuleReviewTest.kt`；Modify `Standardizer.kt`、`StandardizerTest.kt`。

**Interfaces:** 保留 DiffChange/Violation；新增 SoftNotice(ruleId,note,path,line,reason)、ReviewCoverage(ruleId,state,hardChecksEvaluated,hardChecksSkipped,boundary)；Outcome.soft 改为 List<SoftNotice>，增加 reviewCoverage。

RuleReview.kt 中的完整输出类型：

```kotlin
data class SoftNotice(val ruleId:String,val note:String,val path:String?,val line:Int?,val reason:String)
data class ReviewCoverage(
    val ruleId:String,val state:String,val hardChecksEvaluated:Int,val hardChecksSkipped:Int,val boundary:String
)
```

Standardizer.Outcome 替换为 `data class Outcome(val violations:List<Violation>,val soft:List<SoftNotice>,val reviewCoverage:List<ReviewCoverage>)`。

- [ ] 建立局部 candidate fixture 并写 RED：standardizer 单测只测执行，不替代 resolver 的审批校验。

```kotlin
private fun visionRule()=KnowledgeRule(
    id="shared.vision",topic="vision",title="Vision",instruction="Review validity and freshness",rationale="test",
    status=RuleStatus.CANDIDATE,authority=RuleAuthority.SHARED,applicability=RuleApplicability(),
    evidence=listOf(GitRuleEvidence("fixture/repo","abcdef1","TeamCode/Vision.java",line=1)),
    reviewTriggers=listOf(RuleReviewTrigger(listOf("**/*.java"),listOf("\\bLLResult\\b","\\.getLatestResult\\s*\\(")))
)

@Test fun `matching keywords never certify either file`() {
    val changes=listOf(
        Standardizer.DiffChange("TeamCode/A.java",listOf(8 to "LLResult r=cam.getLatestResult();",9 to "r.isValid();")),
        Standardizer.DiffChange("TeamCode/B.java",listOf(17 to "LLResult r=cam.getLatestResult();"))
    )
    val result=Standardizer.evaluate(listOf(visionRule()),changes)
    assertTrue(result.violations.isEmpty())
    assertEquals(listOf(8,17),result.soft.map { it.line })
    assertEquals("manual-review-required",result.reviewCoverage.single().state)
}
```

- [ ] RED：`./gradlew :modules:standardizer:test --tests '*RuleReviewTest' --console=plain`。
- [ ] 实现独立的触发函数；生产调用来自已验证规则，不能 catch 正则错误后返回通过：

```kotlin
fun notices(rule:KnowledgeRule,changes:List<Standardizer.DiffChange>):List<SoftNotice> {
    if (rule.reviewTriggers.isEmpty()) return if (rule.checks.isEmpty())
        listOf(SoftNotice(rule.id,rule.instruction,null,null,"project-review")) else emptyList()
    return changes.groupBy { it.path }.toSortedMap().mapNotNull { (path,items) ->
        val added=items.flatMap { it.addedLines }.distinct().sortedBy { it.first }
        var matched=false
        val hitLines=mutableListOf<Int>()
        rule.reviewTriggers.forEach { trigger ->
            val matches=trigger.paths.any { FileSystems.getDefault().getPathMatcher("glob:$it").matches(Path.of(path)) }
            if (matches) {
                if (trigger.addedLinePatterns.isEmpty()) matched=true
                else added.forEach { (line,text) ->
                    if (trigger.addedLinePatterns.any { Regex(it).containsMatchIn(text) }) {
                        matched=true
                        hitLines+=line
                    }
                }
            }
        }
        if (!matched) null else SoftNotice(rule.id,rule.instruction,path,hitLines.minOrNull(),"diff-trigger")
    }
}
```

将此函数放入 `object RuleReview`，文件导入 KnowledgeRule、FileSystems、Path，三个输出类型置于同包顶层。所有 soft 都是未完成的人工审阅；不新增 matched→passed 的状态。

- [ ] 每条硬 check 独立评估，reviewTriggers 不影响硬检查输入。Standardizer 新增以下函数；PATH_* 总是评估，REGEX_* 仅有适用新增行时评估，不能将“没有命中 forbidden pattern”当作未评估：

```kotlin
private fun isCheckApplicable(check:RuleCheck,changes:List<DiffChange>):Boolean=when (check.kind) {
    RuleCheckKind.PATH_FORBIDDEN,RuleCheckKind.PATH_REQUIRED -> true
    RuleCheckKind.REGEX_REQUIRED,RuleCheckKind.REGEX_FORBIDDEN -> {
        val matcher=check.appliesTo?.let { FileSystems.getDefault().getPathMatcher("glob:$it") }
        changes.any { it.addedLines.isNotEmpty() && (matcher==null || matcher.matches(Path.of(it.path))) }
    }
}
```

Coverage 的 evaluated/skipped 按 check 数累计，用下列状态函数：

```kotlin
fun coverageState(hasChecks:Boolean,hasNotices:Boolean):String=when {
    hasChecks && hasNotices -> "hard-and-manual"
    hasChecks -> "hard-check-only"
    hasNotices -> "manual-review-required"
    else -> "not-triggered"
}
```

boundary 固定写明“Diff-only checks; manual triggers are heuristic. No control-flow or robot validation.”，不输出随机时间。

- [ ] 加入无关底盘 Java、纯删除仅路径触发 line=null、同文件多触发去重、已有关键字仍提示、封装读取／仅删除保护未触发但 boundary 存在、硬规则不受触发器关闭影响的测试。
- [ ] GREEN：`./gradlew :modules:standardizer:test --console=plain`，原 staged/unstaged/untracked/重命名/删除测试全部保持通过。
- [ ] 本地检查点：`feat(standardizer): report scoped manual review and coverage`。

## Task 5: CLI profile 参数和 kernel JSON v2

**Files:** Create `apps/knowledge-cli/src/main/kotlin/org/ftckb/cli/ProfileArguments.kt`、`apps/knowledge-cli/src/test/kotlin/org/ftckb/cli/ProfileArgumentsTest.kt`、`docs/kernel-contract.v1.schema.json`；Modify `Main.kt`、`CheckCommand.kt`、`KernelJson.kt`、`docs/kernel-contract.schema.json`、`KernelJsonAcceptanceTest.kt`、`CheckAcceptanceTest.kt`、`MainTest.kt`（Kotlin 路径均位于已有 cli 包）。

**Interfaces:** `ProfileArguments.extract(args):ProfileSelection` 提取重复 --profile 或 --generic-profile，返回 remaining/profiles；`requiredProfiles()` 调用 RuleProfiles.normalize。`KernelJson.resolveJson(team,season,result:ResolutionResult)` 与 `checkJson(team,season,profiles,outcome)` 统一生成 JSON，CheckCommand 不再手写版本号。

- [ ] 将当前 v1 JSON Schema 原样归档为 `docs/kernel-contract.v1.schema.json`；不能直接改旧 fixture 的 schemaVersion 数字冒充 v2 输出。
- [ ] 新增 RED 测试：

```kotlin
@Test fun `resolve missing profile fails with JSON context error`() {
    val out=ByteArrayOutputStream()
    val code=runCli(listOf("resolve","../../knowledge","--team","20827","--season","2025-2026","--json"),PrintStream(out))
    val node=mapper.readTree(out.toString())
    assertEquals(2,code)
    assertEquals(2,node["schemaVersion"].asInt())
    assertEquals("resolve",node["command"].asText())
    assertEquals("context-required",node["error"]["code"].asText())
}
```

- [ ] RED：`./gradlew :apps:knowledge-cli:test --tests '*KernelJsonAcceptanceTest' --console=plain`。同时修正 Task 3/4 类型变化造成的编译引用，不能把编译错误当作最终语义 RED 证据。
- [ ] 新增参数文件，完整处理不带值的 generic flag：

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
        while (index<args.size) {
            when (val value=args[index++]) {
                "--generic-profile" -> {
                    require(!generic) { "duplicate --generic-profile" }
                    generic=true
                }
                "--profile" -> {
                    require(index<args.size && args[index].isNotBlank() && !args[index].startsWith("--")) { "--profile requires a value" }
                    profiles+=args[index++]
                }
                else -> remaining+=value
            }
        }
        require(!generic || profiles.isEmpty()) { "--profile and --generic-profile are mutually exclusive" }
        return ProfileSelection(remaining,if (generic || profiles.isNotEmpty()) profiles else null)
    }
}
```

- [ ] Main/Check 的流程固定为：识别 help → 提取 profile flags → 原参数校验 → requiredProfiles → 加载 → resolve → 输出。参数格式错误返回 usage/64；RuleContextException 返回其 code/2；check 的错误也必须带 command=check。validate 不接受 profile，也不要求 profile。

KernelJson 统一 `SCHEMA_VERSION=2`；新增序列化的关键节点如下，使用已存在的 Jackson mapper/ObjectNode：

```kotlin
put("policyLevel",rule.policyLevel.name.lowercase())
putObject("applicability").apply {
    putArray("teams").apply { rule.applicability.teams.sorted().forEach { add(it) } }
    putArray("seasons").apply { rule.applicability.seasons.sorted().forEach { add(it) } }
    putArray("profiles").apply { rule.applicability.profiles.sorted().forEach { add(it) } }
}
putArray("reviewTriggers").apply {
    rule.reviewTriggers.forEach { trigger ->
        addObject().apply {
            putArray("paths").apply { trigger.paths.sorted().forEach { add(it) } }
            putArray("addedLinePatterns").apply { trigger.addedLinePatterns.sorted().forEach { add(it) } }
        }
    }
}
```

resolve 的新数组逐项按 Task 3 模型写字段：excludedRules(ruleId,reasons)、overriddenRules(ruleId,topic,winnerIds,effectiveLevel)、conflicts(topic,effectiveLevel,ruleIds,authorities)。authorities 为按 ruleId 排序的对象，值为小写来源；不要继续访问 conflict.authority。

check 的 soft 输出 ruleId/note/path/line/reason（path/line 无位置时显式 null），reviewCoverage 输出 Task 4 全部六个字段；硬违规保留现有形状。两种成功响应都输出排序后的 profiles。所有失败路径也使用 SCHEMA_VERSION，不保留散落的 `put("schemaVersion",1)`。

- [ ] JSON Schema 四个顶层分支升级为 const=2；成功分支增加 `not: {"required":["error"]}`，避免带 violations 的错误同时命中成功和 error 分支。error.code 增加 context-required/invalid-context。resolve 必填 profiles/excludedRules/overriddenRules，check 必填 profiles/reviewCoverage，rule 必填 policyLevel/profiles/checks/reviewTriggers；新增节点的完整字段表：

| definitions 节点 | required 及类型 |
| --- | --- |
| profiles | array of enum rookiebot/simple-opmode/ftclib-command，uniqueItems=true |
| excludedRule | ruleId:string；reasons:非空 enum status/team/season/profile 数组 |
| overriddenRule | ruleId:string；topic:string；winnerIds:非空 string 数组；effectiveLevel:official/global/local/shared |
| conflict | topic:string；effectiveLevel:同上；ruleIds:minItems=2 的唯一 string 数组；authorities:object，additionalProperties 为 official/shared/team 枚举 |
| reviewTrigger | paths:非空 string 数组；addedLinePatterns:string 数组 |
| soft | ruleId:string；note:string；path:string 或 null；line:正整数或 null；reason:project-review/diff-trigger |
| reviewCoverage | ruleId:string；state:四种 Task 4 状态；hardChecksEvaluated/Skipped:非负整数；boundary:string |

v2 schema 保留 evidence/check/checkViolation 既有定义；不能重新引入旧的 check.kind 连字符／下划线混淆。JSON Schema 不负责算连续年份和验证优先级正确性，这些由 domain 与契约一致性测试覆盖。

- [ ] 参数测试覆盖 generic+profile、重复 generic、缺失 value、未知 profile、互斥 profiles；JSON 测试覆盖三个命令的正常、usage、load-error、invalid-knowledge、conflict/context 错误和 check exit 1。无 profile 的旧成功测试改成显式 generic，而不是删除。
- [ ] GREEN：`./gradlew :apps:knowledge-cli:test --tests '*ProfileArgumentsTest' --tests '*KernelJsonAcceptanceTest' --tests '*CheckAcceptanceTest' --tests '*MainTest' --console=plain`。
- [ ] 本地检查点：`feat(cli): expose versioned profile-aware kernel contracts`。

## Task 6: 聊天、网页、评估和 IDE 统一上下文

**Files:** Modify `modules/agent-runtime/src/main/kotlin/org/ftckb/agent/KnowledgeRetriever.kt`、`modules/session-shell/src/main/kotlin/org/ftckb/session/SessionRuntime.kt`、`AskChatSession.kt`；CLI 的 `ChatOptions.kt`、`ProductionChatLauncher.kt`、`ChatRepl.kt`、`ServeOptions.kt`、`ServeCommand.kt`、`ServeApi.kt`、`EvalCommand.kt`；`apps/knowledge-cli/src/main/resources/web/index.html`；IDE 的 `FtckbSettings.kt`、`FtckbSettingsDialog.kt`、`FtckbService.kt`；Test `KnowledgeRetrieverTest.kt`、`ChatReplTest.kt`、`ServeAcceptanceTest.kt`、`AgentQualityEvaluationTest.kt`；Modify `fixtures/agent/eval/cases.yaml`。

**Interfaces:** KnowledgeRetriever 增加命名参数 `ruleProfiles:Set<String>?=null`（放在现有 guideLimits 之后，保持旧的第四个位置参数不变）；SessionRuntime 增加 initialRuleProfiles，并公开只读 ruleProfiles；reconfigureKnowledge 增加 nextProfiles；ChatStatus 增加 ruleProfiles。

- [ ] 在 KnowledgeRetrieverTest 的临时知识库 fixture 中构造两条同 topic 同级 approved 规则，断言构造失败；构造没有 profile 的 retriever 也必须失败。用计数 fake ModelProvider 断言这些错误发生时模型调用次数为 0。

先加入不依赖新 helper 的具体 RED（沿用本类 writeKnowledge/tempDir，并导入 assertThrows）：

```kotlin
@Test fun `missing profile never silently selects generic`() {
    writeKnowledge()
    assertThrows(IllegalArgumentException::class.java) {
        KnowledgeRetriever(tempDir.resolve("knowledge"),"20827","2025-2026")
    }
    val retriever=KnowledgeRetriever(tempDir.resolve("knowledge"),"20827","2025-2026",ruleProfiles=emptySet())
    assertTrue(retriever.retrieveRules(RetrievalIntent(emptySet(),emptySet(),emptySet(),emptySet(),emptySet())).isEmpty())
}
```
- [ ] RED：`./gradlew :modules:agent-runtime:test --tests '*KnowledgeRetrieverTest' --console=plain`。
- [ ] 将现有忽略冲突的单行替换为：

```kotlin
val resolved=RuleResolver.resolve(loaded.rules,RuleContext(team,season,ruleProfiles))
require(resolved.conflicts.isEmpty()) {
    "knowledge conflicts: "+resolved.conflicts.joinToString("; ") { "${it.topic}:${it.ruleIds.sorted().joinToString() }" }
}
activeRules=resolved.activeRules
```

KnowledgeRetriever 在构造时要求 profile；不能在 catch 中替换为 generic 或空 activeRules。

- [ ] ChatOptions/ServeOptions 增加 `val ruleProfiles:Set<String>`；共用 Task 5 参数提取器。两个 launcher 把 options.ruleProfiles 传给 SessionRuntime；构造异常转成人可读配置错误，不提示输入 API key 来解决规则错误。
- [ ] SessionRuntime 的规则加载必须先于 provider 创建／请求；改变知识配置时先构建所有下一状态，再赋值：

```kotlin
fun reconfigureKnowledge(knowledge:Path,nextTeam:String,nextSeason:String,nextProfiles:Set<String> =ruleProfiles) {
    val normalized=RuleProfiles.normalize(nextProfiles)
    val nextRetriever=try {
        KnowledgeRetriever(knowledge,nextTeam,nextSeason,ruleProfiles=normalized)
    } catch (_:Exception) {
        throw SessionAssemblyException.KnowledgeInvalid()
    }
    knowledgeRoot=knowledge
    knowledgeRetriever=nextRetriever
    team=nextTeam
    season=nextSeason
    ruleProfiles=normalized
    contextRetriever=ContextRetriever(repositoryIndex,knowledgeRetriever)
    rebuildAgents()
}
```

bootstrap/reconfigureRepository 必须保留本次选择；若目标 repository 改变，不把旧项目的 profile 自动认作新项目已确认的 profile：要求调用者同时显式提交 profiles，或拒绝更换仓库并给出提示。provider 变更、保存聊天不改变 profile。

- [ ] ServeApi 的 status 输出 profiles；configure 的 profiles 必须是 string 数组（空数组是显式 generic），提供 null、字符串或未知值时失败。对传入的新 team/season/knowledge/profiles 先完成规则预检再修改 provider，以免失败后产生半更新状态。Edit 模式或有未交付 Agent 修改时拒绝改变任一裁决上下文，而不只限制 repo/knowledge。现有 index.html 配置区增加 profile 选择，不做 UI 重设计：

```html
<label for="rule-profile">项目规则</label>
<select id="rule-profile">
  <option value="">请选择</option>
  <option value="generic">通用项目</option>
  <option value="rookiebot">RookieBot 教程</option>
  <option value="simple-opmode">简单 OpMode</option>
  <option value="ftclib-command">FTCLib Command</option>
</select>
```

提交时 generic 转 []，其余值转单元素数组，空选择不提交。status 中含 rookiebot 优先回显 rookiebot，再回显其他 profile；不能把未选择值变为 generic。

- [ ] IDE state 增加 `var ruleProfile=""`，snapshot/apply 均复制；设置面板增加一行文本输入“项目规则（generic / rookiebot / simple-opmode / ftclib-command）”。空值要求确认，不沿用 provider 名。转换调用：

```kotlin
fun selectedRuleProfiles(value:String):Set<String> =when (val text=value.trim()) {
    "" -> RuleProfiles.normalize(null)
    "generic" -> emptySet()
    else -> RuleProfiles.normalize(setOf(text))
}
```

把 helper 放在 FtckbSettings.kt 并由 dialog/service 共用。Service 的 Ask/Edit/检查三个入口都传相同集合，按 soft 新结构展示 line/reason，不将 coverage 当硬违规。

- [ ] EvalCase 增加 profiles 字段；eval YAML 独立升到 schemaVersion=2，每个 case 显式 profiles: []。EvalCasesCodec v2 必填 profiles，旧 v1 明确报需迁移；这里的版本独立于 kernel，不能只因两个都是 2 就混用 Schema。使用 `KnowledgeRetriever(values.knowledge,case.team,case.season,ruleProfiles=case.profiles)`，现有 5 场景的题目和预期证据不改变。
- [ ] 执行离线 GREEN：

```bash
./gradlew :modules:agent-runtime:test --tests '*KnowledgeRetrieverTest' --console=plain
./gradlew :modules:session-shell:test --console=plain
./gradlew :apps:knowledge-cli:test --tests '*ChatReplTest' --tests '*ServeAcceptanceTest' --tests '*AgentQualityEvaluationTest' --console=plain
./gradlew :apps:android-studio-plugin:compileKotlin --console=plain
```

以上命令分别运行模块，避免过滤到不属于该模块的测试；不可将“没有匹配的测试”当作通过。补充回归：无 profile/冲突不调用模型；网页配置失败保留原上下文；换 provider 保留 profile；换项目要求重新确认。IDE 编译不代表 UI 真机验收。
- [ ] 本地检查点：`feat(runtime): propagate rule profiles through all entry points`。

## Task 7: 候选提交明确赛季，审批显示范围

**Files:** Modify `apps/knowledge-cli/src/main/kotlin/org/ftckb/cli/ExtractCommand.kt`、`ApprovalCommand.kt`；Test `ExtractAcceptanceTest.kt`、`ApprovalAcceptanceTest.kt`；Modify `knowledge/schema/examples/rule-example.yaml.example`、`web-rule-example.yaml.example`。

**Interfaces:** ExtractOptions 的 season:String? 保留，只有显式 --all-seasons 才为 null；新生成 YAML 始终 v4/team/local，profiles 明确 []。若用户需要项目专用候选，先填写相应 profile，再审批；模型不能擅自扩大范围或更改 policyLevel。

- [ ] RED：给 runExtractCommand 注入不会联网的 ExtractRunner，断言未选范围或同时选两种范围均 exit 64，runner 没被调用：

```kotlin
@Test fun `extract requires one explicit season choice`() {
    var called=false
    val runner=ExtractRunner { _,_ -> called=true; 0 }
    val out=PrintStream(ByteArrayOutputStream())
    val base=listOf("--repo",".","--team","20827","--provider","fixture")
    assertEquals(64,runExtractCommand(base,out,runner))
    assertEquals(64,runExtractCommand(base+listOf("--season","2025-2026","--all-seasons"),out,runner))
    assertTrue(!called)
    assertEquals(0,runExtractCommand(base+"--all-seasons",out,runner))
}
```

- [ ] 运行 `./gradlew :apps:knowledge-cli:test --tests '*ExtractAcceptanceTest' --tests '*ApprovalAcceptanceTest' --console=plain` 记录 RED。
- [ ] 参数配对前移除单个 --all-seasons，拒绝重复；完成原 flag-value 校验后执行：

```kotlin
val allSeasons=args.count { it=="--all-seasons" }
if (allSeasons>1 || ("--season" in values)==(allSeasons==1)) {
    out.println("Choose exactly one of --season YYYY-YYYY or --all-seasons")
    return 64
}
```

候选 YAML renderer 输出 schemaVersion: 4、policyLevel: local、teams:[team]、seasons:[season] 或 []、profiles:[]，Git evidence 每项增加 type: git；保持 candidate 无 approval。主机校验使用实际选定季节构造规则，不只校验 season=null 的临时规则。

- [ ] candidates 的文本和 JSON 每条增加 policyLevel、applicability；approve 在落盘前输出同样的确认摘要，批准后输出范围不变。审核失败不得修改文件，继续复用原原子替换和审批授权逻辑。

```kotlin
out.println("scope rule=${rule.id} authority=${rule.authority.name.lowercase()} level=${rule.policyLevel.name.lowercase()}")
out.println("teams=${rule.applicability.teams.sorted()} seasons=${rule.applicability.seasons.sorted()} profiles=${rule.applicability.profiles.sorted()}")
```

在 runApproveReject 已有的 rule 变量解析与授权之后、写入之前使用该块；本函数的 target 是文件路径，不能误当规则对象。不用模型读取摘要决定能否审批。v4 模板显式列出所有范围字段，并写明 [] 的含义。
- [ ] GREEN 运行同一命令；断言生成后再 decode/validate、单赛季不会被模型变成 []、相应审批角色不能变更、非候选不能被重复批准。
- [ ] 本地检查点：`feat(authoring): require season scope and expose approval context`。

## Task 8: 有审计的规则迁移与报告回归

**Files:** Modify `knowledge/official/rules.yaml`、`knowledge/official/control-hub-led-blink-codes.yaml`、`knowledge/shared/rules.yaml`、`knowledge/shared/practices/rookiebot-tutorial.yaml`、`knowledge/shared/setup/android-studio-ftc-sdk.yaml`、`knowledge/shared/setup/ftc-dashboard.yaml`、`knowledge/shared/tools/limelight.yaml`、`knowledge/shared/tools/pedro-pathing.yaml`、`knowledge/shared/tools/gobilda-motors-servos.yaml`、`knowledge/teams/20827/rules.yaml`、`knowledge/teams/20827/style-rules.yaml`、`knowledge/teams/16093/rules.yaml`；Create `docs/rule-migrations/2026-09-08-kernel-v2.md`、`apps/knowledge-cli/src/test/kotlin/org/ftckb/cli/RuleMigrationAcceptanceTest.kt`；Modify `PolicyAcceptanceTest.kt`、`KnowledgeGuideAcceptanceTest.kt`、`PedroTutorialAcceptanceTest.kt`。

**Interfaces:** Produces 经有权维护者确认的 v4 数据；ID、总条数、无关 candidate/status/team/season/evidence 保持。不靠文档中写过 approved 就重新生成审批时间。

- [ ] 用旧知识 snapshot 和新期望写 RED：将 actual resolve 的 active IDs 与 profile 期望对照。通用项目不含任何 shared.rookiebot-*；rookiebot 含 12 条且不含 shared.ftclib-command-candidate；ftclib-command 含命令架构规则、不含 RookieBot；旧赛季绑定规则在新赛季仍排除。

新 RuleMigrationAcceptanceTest 中使用具体断言（导入 FileKnowledgeRepository、RuleResolver、RuleContext、Path 和 JUnit）：

```kotlin
@Test fun `rookiebot and command architecture are not simultaneously active`() {
    val loaded=FileKnowledgeRepository.load(Path.of("..","..","knowledge"))
    assertTrue(loaded.violations.isEmpty())
    val generic=RuleResolver.resolve(loaded.rules,RuleContext("20827","2025-2026",emptySet()))
    assertTrue(generic.activeRules.none { it.id.startsWith("shared.rookiebot-") })
    val rookie=RuleResolver.resolve(loaded.rules,RuleContext("20827","2025-2026",setOf("rookiebot")))
    assertTrue(rookie.conflicts.isEmpty())
    assertEquals(12,rookie.activeRules.count { it.id.startsWith("shared.rookiebot-") })
    assertTrue(rookie.activeRules.none { it.id=="shared.ftclib-command-candidate" })
}
```
- [ ] 先生成审阅文档，逐 ID 列出旧／新 policyLevel、topic、profiles、checks、审批状态。语义变更精确范围：

```text
shared.rookiebot-hardware-init
shared.rookiebot-hardware-groups
shared.rookiebot-simple-opmode
shared.rookiebot-step-comments
shared.rookiebot-servo-degrees
shared.rookiebot-template-activation
shared.rookiebot-pedro-complete-builder
shared.rookiebot-nonblocking-auto
shared.rookiebot-sdk-path-hygiene
shared.rookiebot-java-imports
shared.rookiebot-verification-evidence
shared.rookiebot-source-provenance
shared.ftclib-command-candidate
shared.limelight-check-result-validity
shared.limelight-enforce-freshness-policy
```

前 12 条 local + profiles:[rookiebot]；其中 simple-opmode 与 FTCLib 统一 topic=opmode-architecture。FTCLib 仍为 shared 层级，profiles:[ftclib-command]。最后两条撤销 regex-required，增加下方 reviewTriggers。

- [ ] 审批检查点：只有明确确认这份具体差异后才修改生产规则的实质语义。缺少确认时保存审阅文档、继续不依赖生产迁移的代码任务；不得把未批的新语义连同旧 approval 一起发布。按原 authority 所需角色记录实际 approver/时间，不自动填“系统批准”。无关 6 条候选不动。
- [ ] 进行等价格式迁移：各文件 schemaVersion:4；显式 policyLevel 和 profiles；v1 evidence 补 type:git；保留 season/team/status/evidence 值。所有非 RookieBot 的未提权 shared 默认 shared，不新增生产 global 提权规则。格式迁移和获批语义修改分开检查 diff。
- [ ] Limelight 两条使用以下触发定义，不再附旧硬 checks：

```yaml
    reviewTriggers:
      - paths: ["**/*.java"]
        addedLinePatterns:
          - '\b(?:Limelight3A|LLResult)\b'
          - '\.getLatestResult\s*\('
```

其余 3 条 Limelight 提醒使用同样视觉线索，只收窄提示出现时机，不改变指令。`shared.dependency-verify-sync-build-run` 采用 paths:["*.gradle","*.gradle.kts","**/*.gradle","**/*.gradle.kts","gradle/libs.versions.toml"]、addedLinePatterns:[]；已有硬依赖检查不受触发器控制。

team-20827.telemetry-multiple 的 reviewTriggers 使用 Java/Kotlin 路径和模式 `\b(?:telemetry|MultipleTelemetry|FtcDashboard)\b`；chinese-javadoc、constants-centralized、naming-conventions 采用 Java/Kotlin 路径且 patterns:[]，明确属于人工评审。给原无触发器的获批规则增加触发器会改变提示覆盖，须一起列入审阅文档，未确认就保留项目级 soft，不把它们作为“纯格式迁移”。

- [ ] 创建隔离临时 Git 的纯底盘 diff 与双视觉文件 diff；前者无 Limelight soft/hard，后者有逐文件 soft 且无那两条硬违规。检查硬路径违规仍 exit 1，不能把所有违规全降成 soft。
- [ ] GREEN：

```bash
./gradlew :apps:knowledge-cli:test --tests '*RuleMigrationAcceptanceTest' --tests '*PolicyAcceptanceTest' --tests '*KnowledgeGuideAcceptanceTest' --tests '*PedroTutorialAcceptanceTest' :apps:knowledge-cli:installDist --console=plain
apps/knowledge-cli/build/install/ftckb/bin/ftckb validate knowledge --json
apps/knowledge-cli/build/install/ftckb/bin/ftckb resolve knowledge --team 20827 --season 2025-2026 --profile rookiebot --json
apps/knowledge-cli/build/install/ftckb/bin/ftckb resolve knowledge --team 20827 --season 2026-2027 --profile rookiebot --json
```

预期 validate ok=true，两个 resolve conflicts=[]；active 数量按新 profile 实际生成，不能沿用旧 37 条断言。审计测试逐 ID 检查不应改变的字段。
- [ ] 本地检查点：`feat(knowledge): migrate reviewed rules to scoped v4 policies`，审批未完成时不得创建该完成提交。

## Task 9: 固定源码能力声明与 v1→v2 接入

**Files:** Create `.agents/skills/ftckb-integrate/scripts/integration_contract.py`、`.agents/skills/ftckb-integrate/assets/integration.json`、`tests/integration/test_contract_versions.py`；Modify `.agents/skills/ftckb-integrate/scripts/project.py`、`integrate.py`、`verify.py`、`tests/integration/test_integration.py`。

**Interfaces:** `integration_contract.IntegrationError` 替代 project 内定义、由 project 重新导出以兼容旧测试；`versions(config)->tuple[int,int,int]`、`capabilities(manifest)->tuple[int,int,int]`、`normalize_profiles(values)->list[str]`、`profile_args(config)->list[str]`。Python 仅验证协议和上下文，不做规则优先级裁决。

- [ ] 新测试文件导入 bundle/scripts，使用如下 RED；tuple 顺序固定为 projectSchemaVersion、kernelSchemaVersion、integrationVersion：

```python
import sys
import unittest
from pathlib import Path

sys.path.insert(0,str(Path(__file__).resolve().parents[2]/".agents/skills/ftckb-integrate/scripts"))
from integration_contract import IntegrationError,versions,capabilities,normalize_profiles,profile_args

class ContractVersionsTest(unittest.TestCase):
    def test_version_and_profile_selection(self):
        old={"schemaVersion":1,"kernelSchemaVersion":1,"integrationVersion":1}
        new={"schemaVersion":2,"kernelSchemaVersion":2,"integrationVersion":2,"profiles":["rookiebot"]}
        self.assertEqual((1,1,1),versions(old))
        self.assertEqual([],profile_args(old))
        self.assertEqual(["--profile","rookiebot","--profile","simple-opmode"],profile_args(new))
        self.assertEqual(["--generic-profile"],profile_args(new|{"profiles":[]}))
        with self.assertRaises(IntegrationError):
            versions(new|{"kernelSchemaVersion":1})
        with self.assertRaises(IntegrationError):
            normalize_profiles(["rookiebot","ftclib-command"])
        with self.assertRaises(IntegrationError):
            versions(old|{"schemaVersion":True})
```

- [ ] RED：`PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s tests/integration -p test_contract_versions.py -v`。
- [ ] 实现完整的纯协议模块：

```python
class IntegrationError(Exception):
    pass


def versions(config):
    fields=("schemaVersion","kernelSchemaVersion","integrationVersion")
    result=tuple(config.get(key) for key in fields)
    if any(type(value) is not int for value in result) or result not in ((1,1,1),(2,2,2)):
        raise IntegrationError("Unsupported or mixed project/kernel/integration versions")
    return result


def capabilities(manifest):
    if manifest is None:
        return (1,1,1)
    if not isinstance(manifest,dict) or set(manifest)!={"projectSchemaVersion","kernelSchemaVersion","integrationVersion"}:
        raise IntegrationError("Invalid integration capability manifest")
    return versions({"schemaVersion":manifest["projectSchemaVersion"],
                     "kernelSchemaVersion":manifest["kernelSchemaVersion"],
                     "integrationVersion":manifest["integrationVersion"]})


def normalize_profiles(values):
    if not isinstance(values,list) or any(not isinstance(value,str) for value in values):
        raise IntegrationError("profiles must be an explicitly selected string array")
    result=set(values)
    if result-{"rookiebot","simple-opmode","ftclib-command"}:
        raise IntegrationError("Unknown rule profile")
    if "rookiebot" in result:
        result.add("simple-opmode")
    if {"simple-opmode","ftclib-command"}<=result:
        raise IntegrationError("Incompatible rule profiles")
    return sorted(result)


def profile_args(config):
    if versions(config)==(1,1,1):
        return []
    profiles=normalize_profiles(config.get("profiles"))
    return [part for profile in profiles for part in ("--profile",profile)] if profiles else ["--generic-profile"]
```

manifest 完整内容：

```json
{"projectSchemaVersion":2,"kernelSchemaVersion":2,"integrationVersion":2}
```

- [ ] project.load_config 使用 versions，v2 强制 profiles 存在并规范化用于调用；不在只读加载中重写配置。继续校验 duplicate keys、安全路径、摘要和 pin。kernel 对 resolve/check 追加 profile_args，对 validate 不追加；期望版本改为 config.kernelSchemaVersion，v2 成功响应还要核对规范化 profiles 与配置相同。保留 JSON Schema + command + team/season + ok/数组 + exit code 的联合校验。
- [ ] integrate 在选定 pinned source 后、任何目标写入之前读取 `.agents/skills/ftckb-integrate/assets/integration.json`。调用 safe_path/no_duplicate_keys；v2 源必须包含 integration_contract.py。下列决策不得改变：

| 现状和请求 | 结果 |
| --- | --- |
| 已安装 v1、无 --ref、无新 profile | 重用旧 pin/模板/配置版本，不写 v2 字段，不追加新 CLI flags |
| 已安装 v1、无 --ref、却要求 profile | 明确要求升级，拒绝悄悄写新配置 |
| 新装或显式升级到 v2 | --profile 或 --generic-profile 必须明确；已安装 v2 可以复用已确认配置 |
| v2 配置 + 旧源码／未知 manifest | 预检失败，目标 files/index/config 不变 |
| v2 升 v2、未传 profile | 复用旧 profiles，不自动根据 README 改选 |

新增参数 `--profile` 使用 argparse append，`--generic-profile` 使用 store_true，mutually_exclusive_group 保证二者不同时出现；缺省值均为空选择而非 generic。dry-run 计划展示 config/kernel/integration 版本与旧／新 profiles。版本切换前重新检查托管摘要，保持已有文件冲突保护。

- [ ] 更新 verify：安装有效与 check 结果分离，输出 profiles 和协议版本；kernel v1 不强行要求 v2 coverage；v2 要求 coverage/schema 完整。失败恢复仍不执行 reset/delete。
- [ ] 将 Python 集成 fixture 分为 v1、v2 两组。v1 组使用归档的 v1 Schema、无 manifest 和 FAKE_KERNEL v1；v2 组带 manifest、新字段和 FAKE_KERNEL v2，不能只改返回数字。真实旧脚本的行为由 Task 10 固定源码用例验证，不拿新版兼容包装器假冒旧脚本。
- [ ] 集成 RED/GREEN 用例：

```python
def test_unknown_profile_is_rejected_before_target_mutation(self):
    before=snapshot(self.target)
    with self.assertRaises(project.IntegrationError):
        self.install(profiles=["unknown-profile"],generic_profile=False)
    self.assertEqual(before,snapshot(self.target))

def test_v2_repeat_keeps_confirmed_profiles(self):
    self.install(profiles=["rookiebot"],generic_profile=False)
    before=snapshot(self.target)
    code,plan=self.install(team=None,season=None,ref=None,repository=None,
                           profiles=None,generic_profile=False,dry_run=True)
    self.assertEqual(0,code)
    self.assertEqual([],plan["files"])
    self.assertEqual(before,snapshot(self.target))
```

这些方法加入 IntegrationTest；其 args helper 增加 profiles/generic_profile，与新 argparse dest 一致。v2 通用用例显式 generic_profile=True，专门测试“未选择”时为 False。

- [ ] GREEN：`PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s tests/integration -v`。同时覆盖错 team、错 profiles、JSON/退出码矛盾、v1 不升级、v1→v2 明确升级、v2 降级拒绝及所有已有文件安全用例。
- [ ] 本地检查点：`feat(integration): negotiate pinned v1 and v2 project contracts`。

## Task 10: 契约实例、Skill、操作文档和端到端验收

**Files:** Create `fixtures/kernel/v1/`（10 个原始 v1 JSON 的只读归档）、`fixtures/kernel/resolve-profile-error.json`、`resolve-overridden.json`、`check-manual-review.json`；Modify 当前 `fixtures/kernel/*.json`、`tests/integration/test_integration.py`、`docs/kernel-contract.md`、`docs/standardizer-check.md`、`docs/project-integration.md`、`docs/cli-agent.md`、`docs/android-studio-plugin.md`、`AGENTS.md`、`CLAUDE.md`、`todolist.md`、`scripts/smoke.sh`、`scripts/check-gate.sh`、安装 Skill、运行 Skill 和 `assets/AGENTS.block.md`。README/网站如与其他工作重叠，先协调后局部同步，不覆盖已发布内容。

**Interfaces:** 所有消费说明都使用实际 v2 schema；历史例子清楚标注 v1。Machine workflow 仍只使用 validate/resolve/check。Skill 遵循用户原分支、不擅改 pin、不用 API key 做规则检查。

- [ ] RED：新增真实 fixture Schema 对拍，v1 用归档 Schema，v2 用当前 Schema；所有 fixture 必须由真实 CLI 运行产生，而不是手改 schemaVersion。核对以下响应组合：

```text
validate-ok                exit 0, ruleCount, violations=[]
resolve-ok                 exit 0, profiles, active/excluded/overridden/conflicts
resolve-conflict           exit 2, conflicts 非空
resolve-profile-error      exit 2, context-required
resolve-overridden         exit 0, 被覆盖本地规则及胜出 global ID
check-pass                 exit 0, violations=[], soft/coverage
check-manual-review        exit 0, 文件定位 soft, manual-review-required
check-hard                 exit 1, 硬违规非空
error-usage                exit 64
error-invalid-knowledge    exit 2
check-error-usage           exit 64
check-error-load            exit 2
check-error-conflict        exit 2
```

沿用现有 fixture 的真实文件名，新增项用本任务声明的名字；历史归档保留原文件字节。检查错误输出必须含正确 command，不因为 schema 允许省略就漏写。

- [ ] 修改两份 Skill 前，读取 skill-creator 的 SKILL.md 与其要求的参考文件；安装入口遵循 ftckb-integrate 边界。必须写入以下用户可读流程：

```text
1. 从项目配置读取 team、season、profiles 和固定源码协议版本；不猜队号或架构。
2. resolve 非 0 或 conflicts 非空时停止生成，不丢弃冲突后继续。
3. 只把 activeRules 当生效规则；excluded/overridden 说明未执行的原因。
4. check 的硬违规阻止交付；soft 是待人工核实事项，不是自动通过。
5. reviewCoverage 只说明本次检查范围；not-triggered 不代表视觉安全。
6. 升级必须显式指定固定 tag/commit；不为绕过规则修改 submodule。
7. 使用当前分支，保留用户既有修改，不自动 commit/push；软件检查与真机验证分开报告。
```

不要在 Skill 里复制 policy 优先级代码。保留 v1 已固定项目可运行的说明和升级命令，新教程展示 --profile rookiebot 或 --generic-profile，不再使用无选择的命令。

- [ ] 更新 scripts/check-gate.sh 的接口为 `<repo> <knowledge> <team> <season> <profile-or-generic> [diff]`，必须明确提供第五项，不给旧调用默默默认 generic。POSIX shell 的参数装配片段：

```sh
if [ "$#" -lt 5 ] || [ "$#" -gt 6 ]; then
  echo "usage: check-gate.sh REPO KNOWLEDGE TEAM SEASON PROFILE_OR_GENERIC [DIFF]" >&2
  exit 64
fi
PROFILE=$5
DIFF=${6:-}
if [ "$PROFILE" = "generic" ]; then
  set -- --generic-profile
else
  set -- --profile "$PROFILE"
fi
if [ -n "$DIFF" ]; then
  set -- "$@" --diff "$DIFF"
fi
"$FTCKB" check "$ROOT" --knowledge "$KNOWLEDGE" --team "$TEAM" --season "$SEASON" "$@" --json
exit $?
```

这一检查在读取 `$1` 前进行；保持原 CLI 构建逻辑，文档说明 FTCKB 所在路径。smoke 的两个 resolve 分别明确 profile 或 generic，并验证准确退出码；不靠 stdout 中碰巧有 ok:true 忽略 CLI exit 2。

- [ ] 扩展真实 pinned-source 测试：保留 v1 固定源码 `62307d876335b4e337e6e36e628e727e405c691f` 的隔离旧项目验证；当前 v2 新源码只从 tracked 源文件和本轮明确的新文件复制进临时仓库，不复制用户密钥／网站未跟踪文件。新源码必须先纳入受审查文件列表；不能因新文件尚未 tracked 而漏进真实测试。

端到端新增断言：

```python
self.assertEqual(2,result["checks"]["resolve"]["output"]["schemaVersion"])
self.assertEqual(["rookiebot","simple-opmode"],result["checks"]["resolve"]["output"]["profiles"])
active={rule["id"] for rule in result["checks"]["resolve"]["output"]["activeRules"]}
self.assertIn("shared.rookiebot-simple-opmode",active)
self.assertNotIn("shared.ftclib-command-candidate",active)
```

为真实测试明确选择 rookiebot；再在同一临时目标写入只含底盘计算的 Java，check exit 0 且无 Limelight 误报；添加 LLResult 线索产生 soft；添加禁止路径仍 exit 1。最后重复 dry-run，目标文件／index／Git config 不变。

- [ ] 完整 GREEN，分别执行而非用一个模式过滤所有模块：

```bash
./gradlew :modules:domain:test :modules:knowledge:test :modules:standardizer:test :modules:model-provider:test :modules:model-provider-openai-compatible:test :modules:repository-analysis:test :modules:tooling-git:test :modules:agent-runtime:test :modules:session-shell:test :apps:knowledge-cli:test :apps:knowledge-cli:installDist --console=plain
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s tests/integration -v
FTCKB_REAL_INTEGRATION=1 PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s tests/integration -p test_integration.py -k test_real_cli_from_pinned_source -v
./gradlew :apps:android-studio-plugin:compileKotlin --console=plain
apps/knowledge-cli/build/install/ftckb/bin/ftckb validate knowledge --json
apps/knowledge-cli/build/install/ftckb/bin/ftckb resolve knowledge --team 20827 --season 2025-2026 --generic-profile --json
apps/knowledge-cli/build/install/ftckb/bin/ftckb check . --knowledge knowledge --team 20827 --season 2025-2026 --generic-profile --json
git diff --check
```

Skill 校验使用 skill-creator 指定的实际验证脚本，先定位再调用，不猜 Python 运行时路径。新旧配置、Kotlin/CLI、Python、Schema、Skill 和真实 CLI 均需记录证据；Windows/IDE UI/机器人未实测则明确未验证。不预填测试数，不把先前被跳过的测试算成功。

- [ ] 本地检查点：`docs(test): verify and document the v2 integration contract`。只提交本轮明确文件；推送、tag 和真实 RookieBot 升级不在本计划自动执行范围。

## 设计覆盖自检

| 设计要求 | 对应任务 |
| --- | --- |
| 层级、来源、授权不混淆 | 1、3、7、8 |
| 明确赛季、旧数据兼容、候选不自动批准 | 1、2、7、8 |
| profile、互斥架构、所有入口失败闭合 | 1、3、5、6、9 |
| Limelight 误报、跨文件误判、人工复核与漏检边界 | 4、8、10 |
| 解释输出、v2 schema、旧 pin 不静默升级 | 3、5、9、10 |
| 真实测试报告回归与硬门禁不退化 | 4、8、10 |
| 安装阶段进度／超时 | 独立计划 `2026-09-08-integration-progress-timeouts.md` |

## 完成条件与执行交接

所有复选框须有真实执行依据再勾选；规则审批未结束或任一 v2 消费方仍旧语义时，不宣称交付完成。此计划完成后再执行独立安装体验计划。用户可以选择 subagent-driven-development 的逐任务实现与审查，或 executing-plans 在当前任务中顺序执行；无论选哪种方式都沿用当前分支，不自动推送。
