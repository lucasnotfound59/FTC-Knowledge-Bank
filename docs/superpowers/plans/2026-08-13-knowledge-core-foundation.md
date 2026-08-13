# Knowledge Core Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a runnable, tested Kotlin knowledge core that loads version-controlled FTC rules from YAML, validates evidence and approvals, resolves official/shared/team precedence, blocks conflicts, and exposes a local validation CLI.

**Architecture:** This is the first vertical slice of the approved MVP. `modules/domain` owns immutable domain types and policies; `modules/knowledge` owns YAML I/O and directory loading; `apps/knowledge-cli` provides an IDE-independent executable acceptance surface. No module imports IntelliJ APIs, calls a model, modifies robot repositories, or uses the network at runtime.

**Tech Stack:** JDK 21 toolchain, Gradle 9.4.0, Kotlin/JVM 2.4.10, SnakeYAML Engine 3.0.1, JUnit Jupiter 5.14.3.

## Global Constraints

- FIRST official constraints cannot be overridden by shared or team rules.
- Repository-derived patterns remain `candidate` until an authorized lead approves them.
- Shared rules require an overall software lead; team rules require the matching team's software lead.
- Teams 20827 and 16093 share common rules and may define team-specific overrides.
- Season configuration is data, not a rule authority, and cannot weaken policy.
- All source evidence records repository, commit, relative file path, and a symbol or line.
- Core modules must not import IntelliJ APIs.
- Runtime code performs no network requests.
- Use Kotlin formatting without spaces around assignment or arithmetic operators where the formatter permits (`x=1`, `a+b`).

---

## File Map

```text
settings.gradle.kts                                  # Gradle module registry
build.gradle.kts                                     # shared repositories and test setup
gradle/libs.versions.toml                            # locked dependency versions
.gitignore                                           # build and local brainstorm artifacts
modules/domain/build.gradle.kts                      # pure Kotlin domain module
modules/domain/src/main/kotlin/org/ftckb/domain/RuleModels.kt
modules/domain/src/main/kotlin/org/ftckb/domain/RuleValidation.kt
modules/domain/src/main/kotlin/org/ftckb/domain/ApprovalPolicy.kt
modules/domain/src/main/kotlin/org/ftckb/domain/RuleResolver.kt
modules/domain/src/test/kotlin/org/ftckb/domain/      # unit tests for domain policy
modules/knowledge/build.gradle.kts                   # YAML dependency
modules/knowledge/src/main/kotlin/org/ftckb/knowledge/RuleYamlCodec.kt
modules/knowledge/src/main/kotlin/org/ftckb/knowledge/FileKnowledgeRepository.kt
modules/knowledge/src/test/kotlin/org/ftckb/knowledge/ # YAML and directory loading tests
apps/knowledge-cli/build.gradle.kts                  # application entry point
apps/knowledge-cli/src/main/kotlin/org/ftckb/cli/Main.kt
apps/knowledge-cli/src/test/kotlin/org/ftckb/cli/MainTest.kt
knowledge/official/rules.yaml                        # initial official constraints
knowledge/shared/rules.yaml                          # initial shared candidates
knowledge/teams/20827/rules.yaml                     # 20827 profile candidates
knowledge/teams/16093/rules.yaml                     # 16093 profile candidates
knowledge/schema/examples/rule-example.yaml.example  # documented canonical record
```

---

### Task 1: Bootstrap the Kotlin multi-project build

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `gradle/libs.versions.toml`
- Create: `modules/domain/build.gradle.kts`
- Create: `modules/domain/src/test/kotlin/org/ftckb/domain/BuildSmokeTest.kt`
- Modify: `.gitignore`
- Create through Gradle: `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, `gradle/wrapper/gradle-wrapper.properties`

**Interfaces:**
- Produces: Gradle project `:modules:domain`, JDK 21 test toolchain, JUnit Platform test runtime.

- [ ] **Step 1: Add the failing smoke test**

```kotlin
package org.ftckb.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BuildSmokeTest {
    @Test
    fun `domain module runs on Java 21`() {
        assertEquals(21,Runtime.version().feature())
    }
}
```

- [ ] **Step 2: Create the Gradle settings and version catalog**

`settings.gradle.kts`:

```kotlin
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { mavenCentral() }
}

rootProject.name="FTC-Knowledge-Bank"
include(":modules:domain")
```

`gradle/libs.versions.toml`:

```toml
[versions]
kotlin = "2.4.10"
junit = "5.14.3"
snakeyaml = "3.0.1"

[libraries]
junit-bom = { module = "org.junit:junit-bom", version.ref = "junit" }
junit-jupiter = { module = "org.junit.jupiter:junit-jupiter" }
snakeyaml-engine = { module = "org.snakeyaml:snakeyaml-engine", version.ref = "snakeyaml" }

[plugins]
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
```

- [ ] **Step 3: Configure the root and domain builds**

`build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
}

subprojects {
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
}
```

`modules/domain/build.gradle.kts`:

```kotlin
plugins { alias(libs.plugins.kotlin.jvm) }

kotlin { jvmToolchain(21) }

dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
}
```

- [ ] **Step 4: Ignore generated and local-only files**

Append to `.gitignore`:

```gitignore
.gradle/
.idea/
.superpowers/
**/build/
out/
```

- [ ] **Step 5: Generate the wrapper and run the smoke test**

Run:

```bash
gradle wrapper --gradle-version 9.4.0
./gradlew :modules:domain:test --tests org.ftckb.domain.BuildSmokeTest
```

Expected: `BUILD SUCCESSFUL`; `BuildSmokeTest` passes on Java 21.

- [ ] **Step 6: Commit the build foundation**

```bash
git add .gitignore settings.gradle.kts build.gradle.kts gradle modules/domain gradlew gradlew.bat
git commit -m "build: bootstrap Kotlin knowledge core"
```

---

### Task 2: Define rule models and lifecycle validation

**Files:**
- Create: `modules/domain/src/main/kotlin/org/ftckb/domain/RuleModels.kt`
- Create: `modules/domain/src/main/kotlin/org/ftckb/domain/RuleValidation.kt`
- Create: `modules/domain/src/test/kotlin/org/ftckb/domain/RuleValidatorTest.kt`

**Interfaces:**
- Produces: `KnowledgeRule`, `RuleEvidence`, `Approval`, `RuleStatus`, `RuleAuthority`, `RuleApplicability`, `RuleViolation`, `RuleValidator.validate(KnowledgeRule)`.

- [ ] **Step 1: Write failing lifecycle and evidence tests**

```kotlin
package org.ftckb.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RuleValidatorTest {
    private val evidence=RuleEvidence(
        repository="xiaokai-lyk/FTC20827-2026Decode",
        commit="118c28e137334bbbea510d77f1fa384e8b1b5779",
        file="TeamCode/src/main/java/org/firstinspires/ftc/teamcode/Hardwares.java",
        symbol="Hardwares"
    )

    @Test
    fun `approved rule requires approval metadata`() {
        val rule=KnowledgeRule(
            id="shared.hardware-access",
            topic="hardware-access",
            title="Centralize hardware access",
            instruction="Access configured devices through the team hardware layer.",
            rationale="Keeps names and initialization in one place.",
            status=RuleStatus.APPROVED,
            authority=RuleAuthority.SHARED,
            applicability=RuleApplicability(),
            evidence=listOf(evidence)
        )

        assertEquals(listOf("approved rule requires approval"),RuleValidator.validate(rule).map { it.message })
    }

    @Test
    fun `candidate requires evidence but not approval`() {
        val rule=KnowledgeRule(
            id="candidate.ftclib-command",
            topic="command-framework",
            title="Use FTCLib Command",
            instruction="Use FTCLib Command for scheduled robot actions.",
            rationale="Both reference repositories use FTCLib Command.",
            status=RuleStatus.CANDIDATE,
            authority=RuleAuthority.SHARED,
            applicability=RuleApplicability(seasons=setOf("2025-2026")),
            evidence=listOf(evidence)
        )

        assertTrue(RuleValidator.validate(rule).isEmpty())
    }

    @Test
    fun `team rule requires at least one team`() {
        val rule=KnowledgeRule(
            id="team.hardware-layer",
            topic="hardware-access",
            title="Use Hardwares",
            instruction="Use Hardwares for configured devices.",
            rationale="Team-specific architecture.",
            status=RuleStatus.CANDIDATE,
            authority=RuleAuthority.TEAM,
            applicability=RuleApplicability(),
            evidence=listOf(evidence)
        )

        assertEquals(listOf("team rule requires an applicable team"),RuleValidator.validate(rule).map { it.message })
    }
}
```

- [ ] **Step 2: Run the test to confirm missing types**

Run: `./gradlew :modules:domain:test --tests org.ftckb.domain.RuleValidatorTest`

Expected: compilation fails because `KnowledgeRule` and related types do not exist.

- [ ] **Step 3: Implement immutable domain values**

`RuleModels.kt`:

```kotlin
package org.ftckb.domain

import java.time.Instant

enum class RuleStatus { CANDIDATE,APPROVED,DEPRECATED,REJECTED }
enum class RuleAuthority { OFFICIAL,SHARED,TEAM }
enum class ApproverRole { OVERALL_SOFTWARE_LEAD,TEAM_SOFTWARE_LEAD }

data class RuleEvidence(
    val repository:String,
    val commit:String,
    val file:String,
    val symbol:String?=null,
    val line:Int?=null
)

data class RuleApplicability(
    val teams:Set<String> = emptySet(),
    val seasons:Set<String> = emptySet()
)

data class Approval(
    val approver:String,
    val role:ApproverRole,
    val team:String?=null,
    val approvedAt:Instant
)

data class KnowledgeRule(
    val id:String,
    val topic:String,
    val title:String,
    val instruction:String,
    val rationale:String,
    val status:RuleStatus,
    val authority:RuleAuthority,
    val applicability:RuleApplicability,
    val evidence:List<RuleEvidence>,
    val approval:Approval?=null,
    val supersedes:String?=null,
    val positiveExample:String?=null,
    val negativeExample:String?=null
)
```

- [ ] **Step 4: Implement deterministic validation**

`RuleValidation.kt`:

```kotlin
package org.ftckb.domain

data class RuleViolation(val ruleId:String,val field:String,val message:String)

object RuleValidator {
    private val idPattern=Regex("^[a-z0-9]+(?:[.-][a-z0-9]+)*$")
    private val commitPattern=Regex("^[0-9a-fA-F]{7,64}$")

    fun validate(rule:KnowledgeRule):List<RuleViolation> = buildList {
        fun reject(field:String,message:String)=add(RuleViolation(rule.id,field,message))
        if (!idPattern.matches(rule.id)) reject("id","invalid rule id")
        if (rule.topic.isBlank()) reject("topic","topic must not be blank")
        if (rule.title.isBlank()) reject("title","title must not be blank")
        if (rule.instruction.isBlank()) reject("instruction","instruction must not be blank")
        if (rule.rationale.isBlank()) reject("rationale","rationale must not be blank")
        if (rule.evidence.isEmpty()) reject("evidence","rule requires evidence")
        if (rule.status==RuleStatus.APPROVED && rule.approval==null) reject("approval","approved rule requires approval")
        if (rule.status!=RuleStatus.APPROVED && rule.approval!=null) reject("approval","only approved rule may contain approval")
        if (rule.authority==RuleAuthority.TEAM && rule.applicability.teams.isEmpty()) reject("applicability.teams","team rule requires an applicable team")
        rule.evidence.forEachIndexed { index,evidence ->
            if (evidence.repository.isBlank()) reject("evidence[$index].repository","repository must not be blank")
            if (!commitPattern.matches(evidence.commit)) reject("evidence[$index].commit","commit must be a Git SHA")
            if (evidence.file.isBlank() || evidence.file.startsWith("/") || ".." in evidence.file.split('/')) reject("evidence[$index].file","file must be a safe relative path")
            if (evidence.symbol.isNullOrBlank() && evidence.line==null) reject("evidence[$index]","evidence requires a symbol or line")
            if (evidence.line!=null && evidence.line<1) reject("evidence[$index].line","line must be positive")
        }
    }
}
```

- [ ] **Step 5: Run tests and commit**

Run: `./gradlew :modules:domain:test`

Expected: `BUILD SUCCESSFUL`; all four domain tests pass.

```bash
git add modules/domain
git commit -m "feat: define knowledge rule model"
```

---

### Task 3: Implement approval authorization and rule resolution

**Files:**
- Create: `modules/domain/src/main/kotlin/org/ftckb/domain/ApprovalPolicy.kt`
- Create: `modules/domain/src/main/kotlin/org/ftckb/domain/RuleResolver.kt`
- Modify: `modules/domain/src/main/kotlin/org/ftckb/domain/RuleValidation.kt`
- Modify: `modules/domain/src/test/kotlin/org/ftckb/domain/RuleValidatorTest.kt`
- Create: `modules/domain/src/test/kotlin/org/ftckb/domain/ApprovalPolicyTest.kt`
- Create: `modules/domain/src/test/kotlin/org/ftckb/domain/RuleResolverTest.kt`

**Interfaces:**
- Consumes: `KnowledgeRule`, `Approval`, `RuleAuthority`, `RuleStatus`.
- Produces: `Approver`, `ApprovalPolicy.authorize`, `RuleContext`, `RuleConflict`, `ResolutionResult`, `RuleResolver.resolve`.

- [ ] **Step 1: Write failing authorization tests**

```kotlin
package org.ftckb.domain

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ApprovalPolicyTest {
    @Test
    fun `overall lead approves shared rule`() {
        assertTrue(ApprovalPolicy.authorize(RuleAuthority.SHARED,setOf(),Approver("lucas",ApproverRole.OVERALL_SOFTWARE_LEAD)))
    }

    @Test
    fun `matching team lead approves only own team rule`() {
        val lead=Approver("lead-20827",ApproverRole.TEAM_SOFTWARE_LEAD,"20827")
        assertTrue(ApprovalPolicy.authorize(RuleAuthority.TEAM,setOf("20827"),lead))
        assertFalse(ApprovalPolicy.authorize(RuleAuthority.TEAM,setOf("16093"),lead))
        assertFalse(ApprovalPolicy.authorize(RuleAuthority.SHARED,setOf(),lead))
    }
}
```

Append this test to `RuleValidatorTest` so authorization is enforced during file validation, not only when a UI calls the policy directly:

```kotlin
@Test
fun `approved team rule rejects overall lead approval`() {
    val rule=KnowledgeRule(
        id="team.hardware-layer",
        topic="hardware-access",
        title="Use Hardwares",
        instruction="Use Hardwares for configured devices.",
        rationale="Team-specific architecture.",
        status=RuleStatus.APPROVED,
        authority=RuleAuthority.TEAM,
        applicability=RuleApplicability(teams=setOf("20827")),
        evidence=listOf(evidence),
        approval=Approval("overall",ApproverRole.OVERALL_SOFTWARE_LEAD,approvedAt=java.time.Instant.EPOCH)
    )

    assertEquals(listOf("approval is not authorized for rule authority and teams"),RuleValidator.validate(rule).map { it.message })
}
```

- [ ] **Step 2: Write failing precedence and conflict tests**

```kotlin
package org.ftckb.domain

import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RuleResolverTest {
    private val evidence=RuleEvidence("repo","abcdef1","TeamCode/build.gradle",line=1)
    private val overall=Approval("overall",ApproverRole.OVERALL_SOFTWARE_LEAD,approvedAt=Instant.EPOCH)
    private val team=Approval("lead-20827",ApproverRole.TEAM_SOFTWARE_LEAD,"20827",Instant.EPOCH)

    private fun rule(id:String,topic:String,authority:RuleAuthority,teams:Set<String> = emptySet(),approval:Approval=overall)=KnowledgeRule(
        id=id,topic=topic,title=id,instruction=id,rationale="test",status=RuleStatus.APPROVED,
        authority=authority,applicability=RuleApplicability(teams=teams,seasons=setOf("2025-2026")),
        evidence=listOf(evidence),approval=approval
    )

    @Test
    fun `team rule overrides shared rule for matching team`() {
        val result=RuleResolver.resolve(
            listOf(
                rule("shared.pathing","pathing",RuleAuthority.SHARED),
                rule("team.pathing","pathing",RuleAuthority.TEAM,setOf("20827"),team)
            ),
            RuleContext("20827","2025-2026")
        )

        assertEquals(listOf("team.pathing"),result.activeRules.map { it.id })
        assertTrue(result.conflicts.isEmpty())
    }

    @Test
    fun `official rule cannot be overridden`() {
        val result=RuleResolver.resolve(
            listOf(
                rule("official.safe","deployment-safety",RuleAuthority.OFFICIAL),
                rule("team.unsafe","deployment-safety",RuleAuthority.TEAM,setOf("20827"),team)
            ),
            RuleContext("20827","2025-2026")
        )

        assertEquals(listOf("official.safe"),result.activeRules.map { it.id })
    }

    @Test
    fun `same authority same topic blocks resolution`() {
        val result=RuleResolver.resolve(
            listOf(
                rule("shared.one","naming",RuleAuthority.SHARED),
                rule("shared.two","naming",RuleAuthority.SHARED)
            ),
            RuleContext("20827","2025-2026")
        )

        assertTrue(result.activeRules.isEmpty())
        assertEquals(listOf(setOf("shared.one","shared.two")),result.conflicts.map { it.ruleIds })
    }

    @Test
    fun `candidate is never active`() {
        val candidate=rule("shared.candidate","naming",RuleAuthority.SHARED).copy(status=RuleStatus.CANDIDATE,approval=null)
        assertTrue(RuleResolver.resolve(listOf(candidate),RuleContext("20827","2025-2026")).activeRules.isEmpty())
    }
}
```

- [ ] **Step 3: Run tests to verify missing policies**

Run: `./gradlew :modules:domain:test`

Expected: compilation fails for `ApprovalPolicy`, `RuleResolver`, and related types.

- [ ] **Step 4: Implement approval authorization**

`ApprovalPolicy.kt`:

```kotlin
package org.ftckb.domain

data class Approver(val id:String,val role:ApproverRole,val team:String?=null)

object ApprovalPolicy {
    fun authorize(authority:RuleAuthority,teams:Set<String>,approver:Approver):Boolean = when (authority) {
        RuleAuthority.OFFICIAL,RuleAuthority.SHARED -> approver.role==ApproverRole.OVERALL_SOFTWARE_LEAD
        RuleAuthority.TEAM -> approver.role==ApproverRole.TEAM_SOFTWARE_LEAD && approver.team!=null && teams==setOf(approver.team)
    }
}
```

Update the end of `RuleValidator.validate` in `RuleValidation.kt` to validate the stored approval through the same policy:

```kotlin
if (rule.status==RuleStatus.APPROVED && rule.approval!=null) {
    val approval=rule.approval
    val approver=Approver(approval.approver,approval.role,approval.team)
    if (!ApprovalPolicy.authorize(rule.authority,rule.applicability.teams,approver)) {
        reject("approval","approval is not authorized for rule authority and teams")
    }
}
```

- [ ] **Step 5: Implement applicability, precedence, and conflicts**

`RuleResolver.kt`:

```kotlin
package org.ftckb.domain

data class RuleContext(val team:String?,val season:String?)
data class RuleConflict(val topic:String,val authority:RuleAuthority,val ruleIds:Set<String>)
data class ResolutionResult(val activeRules:List<KnowledgeRule>,val conflicts:List<RuleConflict>)

object RuleResolver {
    private val priority=mapOf(RuleAuthority.OFFICIAL to 3,RuleAuthority.TEAM to 2,RuleAuthority.SHARED to 1)

    fun resolve(rules:List<KnowledgeRule>,context:RuleContext):ResolutionResult {
        val applicable=rules.filter { rule ->
            rule.status==RuleStatus.APPROVED &&
                (rule.applicability.teams.isEmpty() || context.team in rule.applicability.teams) &&
                (rule.applicability.seasons.isEmpty() || context.season in rule.applicability.seasons)
        }
        val active=mutableListOf<KnowledgeRule>()
        val conflicts=mutableListOf<RuleConflict>()
        applicable.groupBy { it.topic }.toSortedMap().forEach { (topic,topicRules) ->
            val winningPriority=topicRules.maxOf { priority.getValue(it.authority) }
            val winners=topicRules.filter { priority.getValue(it.authority)==winningPriority }.sortedBy { it.id }
            if (winners.size==1) active+=winners.single()
            else conflicts+=RuleConflict(topic,winners.first().authority,winners.map { it.id }.toSet())
        }
        return ResolutionResult(active.sortedBy { it.id },conflicts.sortedBy { it.topic })
    }
}
```

- [ ] **Step 6: Run tests and commit**

Run: `./gradlew :modules:domain:test`

Expected: `BUILD SUCCESSFUL`; approval, precedence, and conflict tests pass.

```bash
git add modules/domain
git commit -m "feat: resolve approved team rules"
```

---

### Task 4: Load and validate rules from YAML

**Files:**
- Modify: `settings.gradle.kts`
- Create: `modules/knowledge/build.gradle.kts`
- Create: `modules/knowledge/src/main/kotlin/org/ftckb/knowledge/RuleYamlCodec.kt`
- Create: `modules/knowledge/src/main/kotlin/org/ftckb/knowledge/FileKnowledgeRepository.kt`
- Create: `modules/knowledge/src/test/kotlin/org/ftckb/knowledge/RuleYamlCodecTest.kt`
- Create: `modules/knowledge/src/test/kotlin/org/ftckb/knowledge/FileKnowledgeRepositoryTest.kt`

**Interfaces:**
- Consumes: all domain rule values and `RuleValidator`.
- Produces: `RuleYamlCodec.decode(String):List<KnowledgeRule>`, `KnowledgeLoadResult`, `FileKnowledgeRepository.load(Path)`.

- [ ] **Step 1: Register the knowledge module**

Add to `settings.gradle.kts`:

```kotlin
include(":modules:knowledge")
```

Create `modules/knowledge/build.gradle.kts`:

```kotlin
plugins { alias(libs.plugins.kotlin.jvm) }

kotlin { jvmToolchain(21) }

dependencies {
    implementation(project(":modules:domain"))
    implementation(libs.snakeyaml.engine)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
}
```

- [ ] **Step 2: Write the failing YAML decode test**

```kotlin
package org.ftckb.knowledge

import org.ftckb.domain.RuleAuthority
import org.ftckb.domain.RuleStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RuleYamlCodecTest {
    @Test
    fun `decodes canonical candidate rule`() {
        val yaml="""
            schemaVersion: 1
            rules:
              - id: shared.ftclib-command
                topic: command-framework
                title: Use FTCLib Command
                instruction: Use FTCLib Command for scheduled robot actions.
                rationale: Both reference repositories use the library.
                status: candidate
                authority: shared
                applicability:
                  teams: []
                  seasons: [2025-2026]
                evidence:
                  - repository: xiaokai-lyk/FTC20827-2026Decode
                    commit: 118c28e137334bbbea510d77f1fa384e8b1b5779
                    file: TeamCode/build.gradle
                    line: 28
        """.trimIndent()

        val rule=RuleYamlCodec.decode(yaml).single()
        assertEquals("shared.ftclib-command",rule.id)
        assertEquals(RuleStatus.CANDIDATE,rule.status)
        assertEquals(RuleAuthority.SHARED,rule.authority)
        assertEquals(setOf("2025-2026"),rule.applicability.seasons)
    }
}
```

- [ ] **Step 3: Implement strict YAML decoding**

Create `RuleYamlCodec.kt` with a `LoadSettings` instance that rejects duplicate keys and a manual decoder that rejects unknown schema versions. Use these public entry points and helpers:

```kotlin
package org.ftckb.knowledge

import java.time.Instant
import org.ftckb.domain.*
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings

object RuleYamlCodec {
    private val load=Load(LoadSettings.builder().setAllowDuplicateKeys(false).build())

    fun decode(text:String):List<KnowledgeRule> {
        val root=load.loadFromString(text).asMap("root")
        require(root.int("schemaVersion")==1) { "unsupported schemaVersion" }
        return root.list("rules").mapIndexed { index,value -> decodeRule(value.asMap("rules[$index]")) }
    }

    private fun decodeRule(map:Map<String,Any?>):KnowledgeRule {
        val applicability=map.optionalMap("applicability") ?: emptyMap()
        val approval=map.optionalMap("approval")?.let {
            Approval(
                approver=it.string("approver"),
                role=ApproverRole.valueOf(it.string("role").uppercase()),
                team=it.optionalString("team"),
                approvedAt=Instant.parse(it.string("approvedAt"))
            )
        }
        return KnowledgeRule(
            id=map.string("id"),topic=map.string("topic"),title=map.string("title"),
            instruction=map.string("instruction"),rationale=map.string("rationale"),
            status=RuleStatus.valueOf(map.string("status").uppercase()),
            authority=RuleAuthority.valueOf(map.string("authority").uppercase()),
            applicability=RuleApplicability(
                teams=applicability.stringSet("teams"),seasons=applicability.stringSet("seasons")
            ),
            evidence=map.list("evidence").map { value ->
                val item=value.asMap("evidence")
                RuleEvidence(item.string("repository"),item.string("commit"),item.string("file"),item.optionalString("symbol"),item.optionalInt("line"))
            },
            approval=approval,supersedes=map.optionalString("supersedes"),
            positiveExample=map.optionalString("positiveExample"),negativeExample=map.optionalString("negativeExample")
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun Any?.asMap(name:String)=this as? Map<String,Any?> ?: error("$name must be a map")
    private fun Map<String,Any?>.string(key:String)=this[key] as? String ?: error("$key must be a string")
    private fun Map<String,Any?>.optionalString(key:String)=this[key] as? String
    private fun Map<String,Any?>.int(key:String)=(this[key] as? Number)?.toInt() ?: error("$key must be an integer")
    private fun Map<String,Any?>.optionalInt(key:String)=(this[key] as? Number)?.toInt()
    private fun Map<String,Any?>.list(key:String)=this[key] as? List<*> ?: emptyList<Any?>()
    private fun Map<String,Any?>.optionalMap(key:String)=this[key]?.asMap(key)
    private fun Map<String,Any?>.stringSet(key:String)=list(key).map { it as? String ?: error("$key values must be strings") }.toSet()
}
```

- [ ] **Step 4: Run the codec test**

Run: `./gradlew :modules:knowledge:test --tests org.ftckb.knowledge.RuleYamlCodecTest`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Write the failing directory-load test**

```kotlin
package org.ftckb.knowledge

import java.nio.file.Files
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FileKnowledgeRepositoryTest {
    @Test
    fun `loads yaml files in stable path order and reports violations`() {
        val root=Files.createTempDirectory("ftckb-rules")
        Files.writeString(root.resolve("b.yaml"),invalidApprovedRule("b.rule"))
        Files.writeString(root.resolve("a.yaml"),invalidApprovedRule("a.rule"))

        val result=FileKnowledgeRepository.load(root)

        assertEquals(listOf("a.rule","b.rule"),result.rules.map { it.id })
        assertEquals(2,result.violations.size)
        assertEquals(listOf("approved rule requires approval","approved rule requires approval"),result.violations.map { it.message })
    }

    private fun invalidApprovedRule(id:String)="""
        schemaVersion: 1
        rules:
          - id: $id
            topic: test
            title: Test
            instruction: Test instruction.
            rationale: Test rationale.
            status: approved
            authority: shared
            applicability: {}
            evidence:
              - repository: owner/repo
                commit: abcdef1
                file: TeamCode/build.gradle
                line: 1
    """.trimIndent()
}
```

- [ ] **Step 6: Implement stable recursive loading**

`FileKnowledgeRepository.kt`:

```kotlin
package org.ftckb.knowledge

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.readText
import org.ftckb.domain.KnowledgeRule
import org.ftckb.domain.RuleValidator
import org.ftckb.domain.RuleViolation

data class KnowledgeLoadResult(val rules:List<KnowledgeRule>,val violations:List<RuleViolation>)

object FileKnowledgeRepository {
    fun load(root:Path):KnowledgeLoadResult {
        require(Files.isDirectory(root)) { "knowledge root is not a directory: $root" }
        val files=Files.walk(root).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.extension in setOf("yaml","yml") }.sorted().toList()
        }
        val rules=files.flatMap { RuleYamlCodec.decode(it.readText()) }
        val duplicateViolations=rules.groupBy { it.id }.filterValues { it.size>1 }.keys.sorted().map {
            RuleViolation(it,"id","duplicate rule id")
        }
        return KnowledgeLoadResult(rules,duplicateViolations+rules.flatMap(RuleValidator::validate))
    }
}
```

- [ ] **Step 7: Run module tests and commit**

Run: `./gradlew :modules:knowledge:test`

Expected: `BUILD SUCCESSFUL`; codec and repository tests pass.

```bash
git add settings.gradle.kts modules/knowledge
git commit -m "feat: load versioned YAML rules"
```

---

### Task 5: Add the validation CLI and canonical knowledge files

**Files:**
- Modify: `settings.gradle.kts`
- Create: `apps/knowledge-cli/build.gradle.kts`
- Create: `apps/knowledge-cli/src/main/kotlin/org/ftckb/cli/Main.kt`
- Create: `apps/knowledge-cli/src/test/kotlin/org/ftckb/cli/MainTest.kt`
- Create: `knowledge/schema/examples/rule-example.yaml.example`
- Create: `knowledge/official/rules.yaml`
- Create: `knowledge/shared/rules.yaml`
- Create: `knowledge/teams/20827/rules.yaml`
- Create: `knowledge/teams/16093/rules.yaml`

**Interfaces:**
- Consumes: `FileKnowledgeRepository`, `RuleResolver`, `RuleContext`.
- Produces: CLI commands `validate <knowledge-root>` and `resolve <knowledge-root> --team <number> --season <season>`; process exit codes 0 for success, 2 for validation/conflict failure, 64 for usage error.

- [ ] **Step 1: Register and configure the CLI module**

Add to `settings.gradle.kts`:

```kotlin
include(":apps:knowledge-cli")
```

Create `apps/knowledge-cli/build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin { jvmToolchain(21) }
application { mainClass.set("org.ftckb.cli.MainKt") }

dependencies {
    implementation(project(":modules:domain"))
    implementation(project(":modules:knowledge"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
}
```

- [ ] **Step 2: Write failing CLI tests**

```kotlin
package org.ftckb.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MainTest {
    private val knowledgeRoot=Path.of("..","..","knowledge").normalize()

    @Test
    fun `validate reports rule counts`() {
        val output=ByteArrayOutputStream()
        val code=runCli(listOf("validate",knowledgeRoot.toString()),PrintStream(output))
        assertEquals(0,code)
        assertTrue(output.toString().contains("validation=ok"))
    }

    @Test
    fun `resolve prints active IDs for team and season`() {
        val output=ByteArrayOutputStream()
        val code=runCli(listOf("resolve",knowledgeRoot.toString(),"--team","20827","--season","2025-2026"),PrintStream(output))
        assertEquals(0,code)
        assertTrue(output.toString().contains("official.keep-customizations-in-teamcode"))
    }
}
```

- [ ] **Step 3: Implement the CLI without exiting inside testable code**

`Main.kt`:

```kotlin
package org.ftckb.cli

import java.io.PrintStream
import java.nio.file.Path
import kotlin.system.exitProcess
import org.ftckb.domain.RuleContext
import org.ftckb.domain.RuleResolver
import org.ftckb.knowledge.FileKnowledgeRepository

fun runCli(args:List<String>,out:PrintStream=System.out):Int {
    if (args.size<2) {
        out.println("usage: knowledge-cli <validate|resolve> <knowledge-root> [--team N --season S]")
        return 64
    }
    val loaded=FileKnowledgeRepository.load(Path.of(args[1]))
    if (loaded.violations.isNotEmpty()) {
        loaded.violations.sortedWith(compareBy({ it.ruleId },{ it.field })).forEach {
            out.println("error rule=${it.ruleId} field=${it.field} message=${it.message}")
        }
        return 2
    }
    return when (args[0]) {
        "validate" -> {
            out.println("validation=ok rules=${loaded.rules.size}")
            0
        }
        "resolve" -> {
            val options=args.drop(2).chunked(2).associate { pair -> pair[0] to pair.getOrElse(1) { "" } }
            val team=options["--team"] ?: return 64.also { out.println("missing --team") }
            val season=options["--season"] ?: return 64.also { out.println("missing --season") }
            val result=RuleResolver.resolve(loaded.rules,RuleContext(team,season))
            if (result.conflicts.isNotEmpty()) {
                result.conflicts.forEach { out.println("conflict topic=${it.topic} rules=${it.ruleIds.sorted().joinToString(",")}") }
                2
            } else {
                result.activeRules.forEach { out.println("active ${it.id}") }
                0
            }
        }
        else -> 64.also { out.println("unknown command: ${args[0]}") }
    }
}

fun main(args:Array<String>)=exitProcess(runCli(args.toList()))
```

- [ ] **Step 4: Add a canonical YAML example**

Store the canonical example at `knowledge/schema/examples/rule-example.yaml.example`. The `.example` suffix keeps documentation out of recursive YAML loading:

```yaml
schemaVersion: 1
rules:
  - id: shared.example-rule
    topic: example-topic
    title: Human-readable title
    instruction: A concise, directly enforceable instruction.
    rationale: Why the team uses this rule.
    status: approved
    authority: shared
    applicability:
      teams: []
      seasons: []
    evidence:
      - repository: owner/repository
        commit: abcdef1234567890
        file: TeamCode/src/main/java/example/Example.java
        symbol: Example
    approval:
      approver: overall-software-lead
      role: overall_software_lead
      approvedAt: 2026-08-13T00:00:00Z
```

- [ ] **Step 5: Add valid initial knowledge records**

`knowledge/official/rules.yaml`:

```yaml
schemaVersion: 1
rules:
  - id: official.keep-customizations-in-teamcode
    topic: build-customization-location
    title: Keep build customizations in TeamCode
    instruction: Put legacy FTC SDK build customizations in TeamCode/build.gradle instead of build.common.gradle.
    rationale: The official SDK reserves build.common.gradle for changes delivered with SDK updates.
    status: approved
    authority: official
    applicability:
      teams: []
      seasons: []
    evidence:
      - repository: FIRST-Tech-Challenge/FtcRobotController
        commit: 26cd1fdd2a3c4b26173d9ff33a3279c27d1c7ad1
        file: build.common.gradle
        symbol: build.common.gradle
    approval:
      approver: overall-software-lead
      role: overall_software_lead
      approvedAt: 2026-08-13T00:00:00Z
```

`knowledge/shared/rules.yaml`:

```yaml
schemaVersion: 1
rules:
  - id: shared.ftclib-command-candidate
    topic: command-framework
    title: Use FTCLib Command
    instruction: Use FTCLib Command for scheduled robot actions.
    rationale: Both reference repositories declare and use FTCLib Command.
    status: candidate
    authority: shared
    applicability:
      teams: []
      seasons: [2025-2026]
    evidence:
      - repository: xiaokai-lyk/FTC20827-2026Decode
        commit: 118c28e137334bbbea510d77f1fa384e8b1b5779
        file: TeamCode/build.gradle
        line: 28
      - repository: tqdmye/FTC2026-16093National
        commit: 3e6de8944081ef347fbb76b2f97c89b89b10b669
        file: TeamCode/build.gradle
        line: 29
```

`knowledge/teams/20827/rules.yaml`:

```yaml
schemaVersion: 1
rules:
  - id: team-20827.hardware-layer-candidate
    topic: hardware-access
    title: Access hardware through Hardwares
    instruction: Access configured robot devices through the Hardwares class.
    rationale: The 20827 repository centralizes configured devices in this class.
    status: candidate
    authority: team
    applicability:
      teams: ["20827"]
      seasons: [2025-2026]
    evidence:
      - repository: xiaokai-lyk/FTC20827-2026Decode
        commit: 118c28e137334bbbea510d77f1fa384e8b1b5779
        file: TeamCode/src/main/java/org/firstinspires/ftc/teamcode/Hardwares.java
        symbol: Hardwares
```

`knowledge/teams/16093/rules.yaml`:

```yaml
schemaVersion: 1
rules:
  - id: team-16093.fsm-candidate
    topic: mechanism-state-management
    title: Use explicit mechanism state machines
    instruction: Model multi-step mechanism behavior with explicit state machines.
    rationale: The 16093 repository contains shooter and intake state-machine implementations.
    status: candidate
    authority: team
    applicability:
      teams: ["16093"]
      seasons: [2025-2026]
    evidence:
      - repository: tqdmye/FTC2026-16093National
        commit: 3e6de8944081ef347fbb76b2f97c89b89b10b669
        file: TeamCode/src/main/java/org/firstinspires/ftc/teamcode/Subsystems/shooter/ShooterFSM.java
        symbol: ShooterFSM
```

- [ ] **Step 6: Run CLI tests and real validation**

Run:

```bash
./gradlew :apps:knowledge-cli:test
./gradlew :apps:knowledge-cli:run --args="validate knowledge"
./gradlew :apps:knowledge-cli:run --args="resolve knowledge --team 20827 --season 2025-2026"
```

Expected:

```text
validation=ok rules=4
active official.keep-customizations-in-teamcode
```

Candidate rules must not appear in active output.

- [ ] **Step 7: Commit the runnable knowledge slice**

```bash
git add settings.gradle.kts apps/knowledge-cli knowledge
git commit -m "feat: validate FTC knowledge rules"
```

---

### Task 6: Add end-to-end policy acceptance tests and documentation

**Files:**
- Create: `apps/knowledge-cli/src/test/kotlin/org/ftckb/cli/PolicyAcceptanceTest.kt`
- Modify: `README.md`
- Modify: `todolist.md`

**Interfaces:**
- Consumes: public CLI and checked-in knowledge directory.
- Produces: regression proof for candidate inactivity, authorized override resolution, and conflict exit behavior.

- [ ] **Step 1: Write an acceptance test for approval and override behavior**

```kotlin
package org.ftckb.cli

import java.nio.file.Files
import org.ftckb.domain.*
import org.ftckb.knowledge.FileKnowledgeRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PolicyAcceptanceTest {
    @Test
    fun `approved team override replaces shared rule but cannot replace official rule`() {
        val root=Files.createTempDirectory("ftckb-policy")
        Files.writeString(root.resolve("rules.yaml"),javaClass.getResource("/policy-acceptance.yaml")!!.readText())
        val loaded=FileKnowledgeRepository.load(root)
        assertTrue(loaded.violations.isEmpty())

        val result=RuleResolver.resolve(loaded.rules,RuleContext("20827","2025-2026"))

        assertEquals(setOf("team.pathing","official.deploy"),result.activeRules.map { it.id }.toSet())
        assertFalse(result.activeRules.any { it.id=="shared.pathing" || it.id=="team.deploy" })
        assertTrue(result.conflicts.isEmpty())
    }
}
```

Create `apps/knowledge-cli/src/test/resources/policy-acceptance.yaml`:

```yaml
schemaVersion: 1
rules:
  - id: shared.pathing
    topic: pathing
    title: Shared pathing choice
    instruction: Use the shared pathing library.
    rationale: Shared default for the test.
    status: approved
    authority: shared
    applicability:
      teams: []
      seasons: [2025-2026]
    evidence:
      - repository: owner/repo
        commit: abcdef1
        file: TeamCode/build.gradle
        line: 1
    approval:
      approver: overall
      role: overall_software_lead
      approvedAt: 2026-08-13T00:00:00Z
  - id: team.pathing
    topic: pathing
    title: Team pathing choice
    instruction: Use the 20827 pathing library.
    rationale: Team override for the test.
    status: approved
    authority: team
    applicability:
      teams: ["20827"]
      seasons: [2025-2026]
    evidence:
      - repository: owner/repo
        commit: abcdef1
        file: TeamCode/build.gradle
        line: 2
    approval:
      approver: lead-20827
      role: team_software_lead
      team: "20827"
      approvedAt: 2026-08-13T00:00:00Z
  - id: official.deploy
    topic: deployment-safety
    title: Official deployment safety
    instruction: Keep the official deployment safety rule.
    rationale: Official constraint for the test.
    status: approved
    authority: official
    applicability:
      teams: []
      seasons: []
    evidence:
      - repository: owner/repo
        commit: abcdef1
        file: README.md
        line: 1
    approval:
      approver: overall
      role: overall_software_lead
      approvedAt: 2026-08-13T00:00:00Z
  - id: team.deploy
    topic: deployment-safety
    title: Team deployment override
    instruction: Replace the official deployment rule.
    rationale: Lower-authority rule used to verify precedence.
    status: approved
    authority: team
    applicability:
      teams: ["20827"]
      seasons: []
    evidence:
      - repository: owner/repo
        commit: abcdef1
        file: README.md
        line: 2
    approval:
      approver: lead-20827
      role: team_software_lead
      team: "20827"
      approvedAt: 2026-08-13T00:00:00Z
```

- [ ] **Step 2: Run the test and confirm it passes only with full policy integration**

Run: `./gradlew :apps:knowledge-cli:test --tests org.ftckb.cli.PolicyAcceptanceTest`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Document local usage**

Add to `README.md`:

````markdown
## Knowledge Core (Foundation)

The first runnable slice validates version-controlled rule files and resolves the rules that apply to a team and season.

```bash
./gradlew :apps:knowledge-cli:run --args="validate knowledge"
./gradlew :apps:knowledge-cli:run --args="resolve knowledge --team 20827 --season 2025-2026"
```

Candidate rules are never active until an authorized software lead approves them. Validation errors and same-level conflicts return a non-zero exit code.
````

- [ ] **Step 4: Mark only completed roadmap items**

In `todolist.md`, change these items to `[x]` only after the full suite passes:

```markdown
- [x] 定义知识条目、候选规范、正式规范和弃用规范的数据格式；
- [x] 建立 `20827` 与 `16093` 队号档案；
```

- [ ] **Step 5: Run the complete verification suite**

Run:

```bash
./gradlew clean test
./gradlew :apps:knowledge-cli:run --args="validate knowledge"
git diff --check
```

Expected: all tests pass, CLI prints `validation=ok rules=4`, and `git diff --check` prints nothing.

- [ ] **Step 6: Commit the completed foundation slice**

```bash
git add apps/knowledge-cli/src/test README.md todolist.md
git commit -m "test: verify knowledge policy workflow"
```

---

## Completion Gate

Before starting repository analysis or model integration, verify:

```bash
./gradlew clean test
./gradlew :apps:knowledge-cli:run --args="validate knowledge"
./gradlew :apps:knowledge-cli:run --args="resolve knowledge --team 20827 --season 2025-2026"
git status --short
```

Required result:

- every automated test passes;
- checked-in YAML has zero validation errors;
- only the official deployment-safety rule is active because the other three records are candidates;
- no generated build output is tracked;
- no IntelliJ API dependency exists outside the future plugin module.

The next implementation plan will cover FTC repository detection and evidence-backed candidate extraction using this knowledge core.
