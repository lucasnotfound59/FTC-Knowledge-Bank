# Evidence Schema v2 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add backward-compatible schema v2 documents with strictly typed Git and official-web evidence.

**Architecture:** The domain module owns a sealed evidence model and validates each subtype without network access. The knowledge module dispatches decoding by schema version and the v2 `type` discriminator. Repository loading, approval, and resolution remain format-agnostic.

**Tech Stack:** Kotlin/JVM 2.4.10, Java 21, SnakeYAML Engine 3.0.1, JUnit 5.14.3, Gradle Wrapper.

## Global Constraints

- Schema v1 remains supported unchanged.
- Schema v2 requires `type: git` or `type: web` on every evidence item.
- Validation is local; no runtime or test web requests.
- Web evidence requires an absolute HTTPS URL without credentials, nonblank title/publisher/section, and ISO `YYYY-MM-DD` access date.
- Git evidence retains repository/commit/file/symbol-or-line validation.
- Unknown fields/types and unsafe values fail closed.
- Approval, applicability, precedence, and candidate behavior do not change.
- Use JDK 21 and repository Kotlin style with no spaces around operators or equals signs.

## File Structure

- `modules/domain/.../RuleModels.kt`: sealed evidence model.
- `modules/domain/.../RuleValidation.kt`: subtype validation.
- `modules/knowledge/.../RuleYamlCodec.kt`: v1/v2 strict decoding.
- Corresponding domain/knowledge tests: compatibility and rejection coverage.
- `knowledge/schema/examples/`: v1 Git and v2 web examples.
- `README.md`: public schema reference.

---

### Task 1: Add typed evidence domain models

**Files:**
- Modify: `modules/domain/src/main/kotlin/org/ftckb/domain/RuleModels.kt:3-16`
- Modify: `modules/domain/src/test/kotlin/org/ftckb/domain/RuleValidatorTest.kt`
- Modify: `modules/domain/src/test/kotlin/org/ftckb/domain/RuleResolverTest.kt:8`

**Interfaces:**
- Produces: `sealed interface RuleEvidence`
- Produces: `GitRuleEvidence(repository:String,commit:String,file:String,symbol:String?=null,line:Int?=null)`
- Produces: `WebRuleEvidence(url:String,title:String,publisher:String,accessedAt:LocalDate,section:String,version:String?=null,product:String?=null,sku:String?=null)`
- Preserves: immutable `KnowledgeRule.evidence:List<RuleEvidence>`.

- [ ] **Step 1: Write failing construction and snapshot tests**

Replace test fixture calls to `RuleEvidence(...)` with `GitRuleEvidence(...)`. Add `import java.time.LocalDate` and this test to `RuleValidatorTest.kt`:

```kotlin
@Test
fun `web evidence is part of the immutable evidence snapshot`() {
    val web=WebRuleEvidence(
        url="https://docs.example.org/tool",
        title="Tool documentation",
        publisher="Example",
        accessedAt=LocalDate.parse("2026-08-13"),
        section="Installation",
        version="2.0"
    )
    val mutable=mutableListOf<RuleEvidence>(web)
    val rule=KnowledgeRule(
        id="shared.web-snapshot",topic="web-snapshot",title="Web snapshot",
        instruction="Keep web evidence stable.",
        rationale="Validated evidence must not change through aliases.",
        status=RuleStatus.CANDIDATE,authority=RuleAuthority.SHARED,
        applicability=RuleApplicability(),evidence=mutable
    )

    mutable.clear()

    assertEquals(listOf(web),rule.evidence)
}
```

In `RuleResolverTest.kt` use:

```kotlin
private val evidence=GitRuleEvidence("repo","abcdef1","TeamCode/build.gradle",line=1)
```

- [ ] **Step 2: Run tests and confirm missing-type failure**

```bash
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew :modules:domain:test
```

Expected: Kotlin compilation fails on unresolved `GitRuleEvidence` and `WebRuleEvidence`.

- [ ] **Step 3: Implement the sealed model**

Replace the old evidence data class in `RuleModels.kt` and import `LocalDate`:

```kotlin
sealed interface RuleEvidence

data class GitRuleEvidence(
    val repository:String,
    val commit:String,
    val file:String,
    val symbol:String?=null,
    val line:Int?=null
):RuleEvidence

data class WebRuleEvidence(
    val url:String,
    val title:String,
    val publisher:String,
    val accessedAt:LocalDate,
    val section:String,
    val version:String?=null,
    val product:String?=null,
    val sku:String?=null
):RuleEvidence
```

Run `rg -n 'RuleEvidence\(' modules apps` and replace every remaining constructor with `GitRuleEvidence(`; do not change `List<RuleEvidence>` type annotations.

- [ ] **Step 4: Verify and commit**

```bash
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew :modules:domain:test
git diff --check
git add modules/domain/src/main/kotlin/org/ftckb/domain/RuleModels.kt modules/domain/src/test/kotlin/org/ftckb/domain/RuleValidatorTest.kt modules/domain/src/test/kotlin/org/ftckb/domain/RuleResolverTest.kt
git commit -m "feat: add typed rule evidence models"
```

Expected: tests pass and `git diff --check` is silent.

---

### Task 2: Validate Git and web evidence

**Files:**
- Modify: `modules/domain/src/main/kotlin/org/ftckb/domain/RuleValidation.kt:1-65`
- Modify: `modules/domain/src/test/kotlin/org/ftckb/domain/RuleValidatorTest.kt`

**Interfaces:**
- Consumes the Task 1 evidence subtypes.
- Produces deterministic violation fields such as `evidence[0].url`.

- [ ] **Step 1: Add failing web validation tests**

Add a helper:

```kotlin
private fun candidateWithEvidence(item:RuleEvidence)=KnowledgeRule(
    id="shared.web-evidence",topic="web-evidence",title="Web evidence",
    instruction="Use official web evidence.",
    rationale="Vendor documentation is not always stored in Git.",
    status=RuleStatus.CANDIDATE,authority=RuleAuthority.SHARED,
    applicability=RuleApplicability(),evidence=listOf(item)
)
```

Add tests:

```kotlin
@Test
fun `accepts complete https web evidence`() {
    val evidence=WebRuleEvidence(
        "https://docs.limelightvision.io/docs/docs-limelight/apis/ftc-programming#key-concepts",
        "FTC Java and Blockly Programming Guide","Limelight Vision",
        LocalDate.parse("2026-08-13"),"Key Concepts",
        "Current documentation","Limelight 3A"
    )
    assertTrue(RuleValidator.validate(candidateWithEvidence(evidence)).isEmpty())
}

@Test
fun `rejects unsafe or incomplete web evidence`() {
    val canonical=WebRuleEvidence(
        "https://docs.example.org/tool","Tool documentation","Example",
        LocalDate.parse("2026-08-13"),"Installation"
    )
    val cases=listOf(
        canonical.copy(url="http://docs.example.org/tool") to "web URL must be absolute HTTPS without credentials",
        canonical.copy(url="/tool") to "web URL must be absolute HTTPS without credentials",
        canonical.copy(url="https://user:pass@docs.example.org/tool") to "web URL must be absolute HTTPS without credentials",
        canonical.copy(title=" ") to "title must not be blank",
        canonical.copy(publisher=" ") to "publisher must not be blank",
        canonical.copy(section=" ") to "section must not be blank",
        canonical.copy(version=" ") to "version must not be blank when present",
        canonical.copy(product=" ") to "product must not be blank when present",
        canonical.copy(sku=" ") to "sku must not be blank when present"
    )
    cases.forEach { (evidence,message) ->
        assertEquals(listOf(message),RuleValidator.validate(candidateWithEvidence(evidence)).map { it.message })
    }
}
```

- [ ] **Step 2: Verify failure**

```bash
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew :modules:domain:test --tests 'org.ftckb.domain.RuleValidatorTest'
```

Expected: compilation or assertion failure because validation is Git-only.

- [ ] **Step 3: Implement subtype validation**

Import `java.net.URI`. Replace the evidence loop with:

```kotlin
rule.evidence.forEachIndexed { index,evidence ->
    when (evidence) {
        is GitRuleEvidence -> validateGitEvidence(index,evidence,::reject)
        is WebRuleEvidence -> validateWebEvidence(index,evidence,::reject)
    }
}
```

Add inside `RuleValidator`:

```kotlin
private fun validateGitEvidence(index:Int,evidence:GitRuleEvidence,reject:(String,String)->Unit) {
    if (evidence.repository.isBlank()) reject("evidence[$index].repository","repository must not be blank")
    if (!commitPattern.matches(evidence.commit)) reject("evidence[$index].commit","commit must be a Git SHA")
    if (!isSafeEvidencePath(evidence.file)) {
        reject("evidence[$index].file","file must be a safe repository relative path using / separators")
    }
    if (evidence.symbol.isNullOrBlank() && evidence.line==null) {
        reject("evidence[$index]","evidence requires a symbol or line")
    }
    if (evidence.line!=null && evidence.line<1) reject("evidence[$index].line","line must be positive")
}

private fun validateWebEvidence(index:Int,evidence:WebRuleEvidence,reject:(String,String)->Unit) {
    if (!isSafeWebUrl(evidence.url)) {
        reject("evidence[$index].url","web URL must be absolute HTTPS without credentials")
    }
    if (evidence.title.isBlank()) reject("evidence[$index].title","title must not be blank")
    if (evidence.publisher.isBlank()) reject("evidence[$index].publisher","publisher must not be blank")
    if (evidence.section.isBlank()) reject("evidence[$index].section","section must not be blank")
    if (evidence.version?.isBlank()==true) reject("evidence[$index].version","version must not be blank when present")
    if (evidence.product?.isBlank()==true) reject("evidence[$index].product","product must not be blank when present")
    if (evidence.sku?.isBlank()==true) reject("evidence[$index].sku","sku must not be blank when present")
}

private fun isSafeWebUrl(value:String):Boolean=runCatching {
    val uri=URI(value)
    uri.isAbsolute && uri.scheme.equals("https",ignoreCase=true) &&
        !uri.host.isNullOrBlank() && uri.userInfo==null
}.getOrDefault(false)
```

- [ ] **Step 4: Verify and commit**

```bash
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew :modules:domain:test
git diff --check
git add modules/domain/src/main/kotlin/org/ftckb/domain/RuleValidation.kt modules/domain/src/test/kotlin/org/ftckb/domain/RuleValidatorTest.kt
git commit -m "feat: validate web rule evidence"
```

Expected: all domain tests pass.

---

### Task 3: Decode schema v1 and v2 strictly

**Files:**
- Modify: `modules/knowledge/src/main/kotlin/org/ftckb/knowledge/RuleYamlCodec.kt:3-101`
- Modify: `modules/knowledge/src/test/kotlin/org/ftckb/knowledge/RuleYamlCodecTest.kt`

**Interfaces:**
- Produces `decode(text:String)` accepting versions 1 and 2.
- v1 evidence has no type and becomes `GitRuleEvidence`.
- v2 evidence requires `type: git|web`.

- [ ] **Step 1: Write failing codec tests**

Change the existing unsupported-version input from 2 to 3. Add imports for `LocalDate`, `GitRuleEvidence`, and `WebRuleEvidence`, then add:

```kotlin
@Test
fun `decodes schema two git and web evidence`() {
    val yaml="""
        schemaVersion: 2
        rules:
          - id: shared.typed-evidence
            topic: typed-evidence
            title: Typed evidence
            instruction: Use typed evidence.
            rationale: Sources need distinct validation.
            status: candidate
            authority: shared
            applicability: {}
            evidence:
              - type: git
                repository: owner/repo
                commit: abcdef1
                file: TeamCode/build.gradle
                line: 1
              - type: web
                url: https://docs.example.org/tool
                title: Tool documentation
                publisher: Example
                accessedAt: 2026-08-13
                section: Installation
                version: "2.0"
                product: Example Tool
                sku: EX-200
    """.trimIndent()

    val evidence=RuleYamlCodec.decode(yaml).single().evidence

    assertEquals(GitRuleEvidence("owner/repo","abcdef1","TeamCode/build.gradle",line=1),evidence[0])
    assertEquals(
        WebRuleEvidence(
            "https://docs.example.org/tool","Tool documentation","Example",
            LocalDate.parse("2026-08-13"),"Installation","2.0","Example Tool","EX-200"
        ),
        evidence[1]
    )
}
```

Add this rejection test and helper for missing `type`, an unknown type, mixed subtype fields, and an invalid date:

```kotlin
@Test
fun `schema two requires known strict evidence types`() {
    val cases=listOf(
        typedCandidate("repository: owner/repo\ncommit: abcdef1\nfile: README.md\nline: 1") to
            "type must be a string",
        typedCandidate("type: video\nurl: https://example.org") to
            "unsupported evidence type: video",
        typedCandidate("type: git\nrepository: owner/repo\ncommit: abcdef1\nfile: README.md\nline: 1\nurl: https://example.org") to
            "rules[0].evidence[0] contains unknown fields: url",
        typedCandidate("type: web\nurl: https://example.org\ntitle: Example\npublisher: Example\naccessedAt: 2026-08-13\nsection: Test\ncommit: abcdef1") to
            "rules[0].evidence[0] contains unknown fields: commit",
        typedCandidate("type: web\nurl: https://example.org\ntitle: Example\npublisher: Example\naccessedAt: yesterday\nsection: Test") to
            "accessedAt must use YYYY-MM-DD"
    )

    cases.forEach { (yaml,message) ->
        val exception=assertThrows(IllegalStateException::class.java) { RuleYamlCodec.decode(yaml) }
        assertEquals(message,exception.message)
    }
}

private fun typedCandidate(evidence:String)="""
    schemaVersion: 2
    rules:
      - id: shared.test
        topic: test
        title: Test
        instruction: Test instruction.
        rationale: Test rationale.
        status: candidate
        authority: shared
        applicability: {}
        evidence:
          - ${evidence.replace("\n","\n            ")}
""".trimIndent()
```

Expected messages are:

```text
type must be a string
unsupported evidence type: video
rules[0].evidence[0] contains unknown fields: url
rules[0].evidence[0] contains unknown fields: commit
accessedAt must use YYYY-MM-DD
```

- [ ] **Step 2: Verify schema v2 failure**

```bash
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew :modules:knowledge:test --tests 'org.ftckb.knowledge.RuleYamlCodecTest'
```

Expected: schema v2 is rejected.

- [ ] **Step 3: Implement schema and evidence dispatch**

Change `decode`:

```kotlin
val schemaVersion=root.int("schemaVersion")
require(schemaVersion in 1..2) { "unsupported schemaVersion" }
return root.requiredList("rules").mapIndexed { index,value ->
    val name="rules[$index]"
    decodeRule(value.asMap(name),name,schemaVersion)
}
```

Pass `schemaVersion` into `decodeRule`, and decode evidence through:

```kotlin
private fun decodeEvidence(map:Map<String,Any?>,name:String,schemaVersion:Int):RuleEvidence {
    if (schemaVersion==1) {
        map.rejectUnknownFields(setOf("repository","commit","file","symbol","line"),name)
        return decodeGitEvidence(map)
    }
    return when (val type=map.string("type")) {
        "git" -> {
            map.rejectUnknownFields(setOf("type","repository","commit","file","symbol","line"),name)
            decodeGitEvidence(map)
        }
        "web" -> {
            map.rejectUnknownFields(
                setOf("type","url","title","publisher","accessedAt","section","version","product","sku"),
                name
            )
            WebRuleEvidence(
                url=map.string("url"),title=map.string("title"),publisher=map.string("publisher"),
                accessedAt=map.localDate("accessedAt"),section=map.string("section"),
                version=map.optionalString("version"),product=map.optionalString("product"),
                sku=map.optionalString("sku")
            )
        }
        else -> error("unsupported evidence type: $type")
    }
}

private fun decodeGitEvidence(map:Map<String,Any?>)=GitRuleEvidence(
    map.string("repository"),map.string("commit"),map.string("file"),
    map.optionalString("symbol"),map.optionalInt("line")
)

private fun Map<String,Any?>.localDate(key:String):LocalDate=runCatching {
    LocalDate.parse(string(key))
}.getOrElse { error("$key must use YYYY-MM-DD") }
```

Replace the inline evidence mapping in `decodeRule` with `decodeEvidence(item,evidenceName,schemaVersion)`.

- [ ] **Step 4: Verify and commit**

```bash
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew :modules:domain:test :modules:knowledge:test
git diff --check
git add modules/knowledge/src/main/kotlin/org/ftckb/knowledge/RuleYamlCodec.kt modules/knowledge/src/test/kotlin/org/ftckb/knowledge/RuleYamlCodecTest.kt
git commit -m "feat: decode schema v2 evidence"
```

Expected: v1 and v2 tests pass.

---

### Task 4: Prove mixed loading and document the schema

**Files:**
- Modify: `modules/knowledge/src/test/kotlin/org/ftckb/knowledge/FileKnowledgeRepositoryTest.kt`
- Create: `knowledge/schema/examples/web-rule-example.yaml.example`
- Modify: `knowledge/schema/examples/rule-example.yaml.example`
- Modify: `README.md`

**Interfaces:** Public schema examples and mixed-version repository compatibility.

- [ ] **Step 1: Add a mixed-version loading test**

Append to `FileKnowledgeRepositoryTest.kt`:

```kotlin
@Test
fun `loads schema one and schema two documents together`() {
    val root=Files.createTempDirectory("ftckb-mixed-schema")
    Files.writeString(root.resolve("legacy.yaml"),candidateRule("shared.legacy"))
    Files.writeString(root.resolve("web.yaml"),"""
        schemaVersion: 2
        rules:
          - id: shared.web
            topic: web-source
            title: Web source
            instruction: Use an official web source.
            rationale: Product documentation may not have a Git commit.
            status: candidate
            authority: shared
            applicability: {}
            evidence:
              - type: web
                url: https://docs.example.org/tool
                title: Tool documentation
                publisher: Example
                accessedAt: 2026-08-13
                section: Installation
    """.trimIndent())

    val result=FileKnowledgeRepository.load(root)

    assertEquals(listOf("shared.legacy","shared.web"),result.rules.map { it.id })
    assertEquals(emptyList<String>(),result.violations.map { it.message })
}
```

- [ ] **Step 2: Run the repository test**

```bash
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew :modules:knowledge:test --tests 'org.ftckb.knowledge.FileKnowledgeRepositoryTest'
```

Expected: pass after Task 3.

- [ ] **Step 3: Create the public v2 example**

Create `web-rule-example.yaml.example`:

```yaml
schemaVersion: 2
rules:
  - id: shared.example-web-rule
    topic: example-web-topic
    title: Human-readable title
    instruction: A concise, directly enforceable instruction.
    rationale: Why the team uses this rule.
    status: candidate
    authority: shared
    applicability:
      teams: []
      seasons: []
    evidence:
      - type: web
        url: https://docs.example.org/tool
        title: Official tool documentation
        publisher: Example Publisher
        accessedAt: 2026-08-13
        section: Installation
        version: "2.0"
        product: Example Tool
        sku: EX-200
```

Add a YAML comment to the existing example stating it is the supported legacy v1 Git form.

- [ ] **Step 4: Update README**

Document coexistence of v1/v2, both evidence field tables, strict subtype fields, HTTPS/no-credentials, and that `accessedAt` is verification date rather than product version. State that validation neither contacts URLs nor proves publisher authenticity. Link both examples.

- [ ] **Step 5: Run acceptance checks and commit**

```bash
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew clean test --quiet
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew :apps:knowledge-cli:run --args="validate knowledge" --quiet
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew :apps:knowledge-cli:run --args="resolve knowledge --team 20827 --season 2025-2026" --quiet
git diff --check
```

Expected CLI output before content plans:

```text
validation=ok rules=4
active official.keep-customizations-in-teamcode
```

Commit:

```bash
git add modules/knowledge/src/test/kotlin/org/ftckb/knowledge/FileKnowledgeRepositoryTest.kt knowledge/schema/examples/rule-example.yaml.example knowledge/schema/examples/web-rule-example.yaml.example README.md
git commit -m "docs: document typed evidence schema"
```
