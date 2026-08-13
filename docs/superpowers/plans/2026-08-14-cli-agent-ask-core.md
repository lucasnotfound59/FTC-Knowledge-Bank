# FTC CLI Agent Phase 1: Ask Core Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver a continuous, read-only FTC command-line chat that works through baseline OpenAI-compatible Chat Completions, retrieves only local code and approved knowledge, and emits verified citations.

**Architecture:** Add provider-neutral model types, a Java 21 HTTP adapter, a safe FTC repository index, and a host-controlled two-stage retrieval runtime. Extend the existing CLI with a test-injectable REPL; the model plans retrieval and writes answers, while Kotlin owns all file access, rule resolution, context limits, and citation validation.

**Tech Stack:** Kotlin 2.4.10, JDK 21 `java.net.http.HttpClient`, Gradle 9.4 wrapper, SnakeYAML Engine 3.0.1, Jackson Databind 2.22.0, Eclipse JGit 7.7.0.202606012155-r for `.gitignore` matching, SLF4J NOP 2.0.18, JUnit 5.14.3.

## Global Constraints

- The first client is command-line continuous chat; do not add Android Studio APIs.
- The model compatibility floor is non-streaming `/chat/completions` with system/user/assistant text messages.
- Support named OpenAI, DeepSeek, and custom profiles through `baseUrl`, `model`, and `apiKeyEnv`; do not hard-code a provider brand in the runtime.
- API keys come only from environment lookup and never appear in diagnostics, fixtures, prompts, or saved sessions.
- Send only selected repository fragments, not the full repository.
- Do not access live web pages, execute shell commands, modify target files, or add embeddings/vector storage.
- Resolve approved rules with the existing `RuleResolver`; Markdown guides are explanatory evidence, never policy.
- Mark answer claims as approved rule, code observation, model inference, or insufficient evidence.
- Retry malformed retrieval JSON and invalid answer citations once; use deterministic retrieval fallback after a second malformed plan.
- Conversation history is memory-only unless `/save` is entered.
- Follow repository Kotlin style: omit spaces around operators and equals signs.
- Use TDD and keep each task's commit limited to the listed files.

---

## File and Module Map

### Build files

- `settings.gradle.kts`: register four new Phase 1 modules.
- `gradle/libs.versions.toml`: pin Jackson Databind `2.22.0`.
- `modules/model-provider/build.gradle.kts`: provider-neutral models plus strict YAML profile loading.
- `modules/model-provider-openai-compatible/build.gradle.kts`: HTTP and JSON adapter.
- `modules/repository-analysis/build.gradle.kts`: safe FTC repository detection and local indexing.
- `modules/agent-runtime/build.gradle.kts`: retrieval, citations, conversation, and Ask orchestration.
- `apps/knowledge-cli/build.gradle.kts`: depend on the four modules.

### Provider files

- `modules/model-provider/src/main/kotlin/org/ftckb/model/ModelTypes.kt`: messages, requests, responses, usage, and provider interface.
- `modules/model-provider/src/main/kotlin/org/ftckb/model/ProviderConfig.kt`: strict profile model and secret resolver.
- `modules/model-provider/src/main/kotlin/org/ftckb/model/ProviderConfigLoader.kt`: YAML decoder for `${user.home}/.ftckb/config.yaml`.
- `modules/model-provider-openai-compatible/src/main/kotlin/org/ftckb/model/openai/HttpTransport.kt`: injectable HTTP boundary.
- `modules/model-provider-openai-compatible/src/main/kotlin/org/ftckb/model/openai/ChatCompletionsProvider.kt`: request/response adapter.
- `modules/model-provider-openai-compatible/src/main/kotlin/org/ftckb/model/openai/ProviderFactory.kt`: profile plus environment to provider construction.

### Repository files

- `modules/repository-analysis/src/main/kotlin/org/ftckb/repository/RepositoryModels.kt`: detection, indexed document, fragment, and query types.
- `modules/repository-analysis/src/main/kotlin/org/ftckb/repository/FtcProjectDetector.kt`: evidence-based FTC support detection.
- `modules/repository-analysis/src/main/kotlin/org/ftckb/repository/GitIgnoreRules.kt`: Git-compatible ignore evaluation without running a process.
- `modules/repository-analysis/src/main/kotlin/org/ftckb/repository/SafeRepositoryWalker.kt`: normalized, bounded text traversal.
- `modules/repository-analysis/src/main/kotlin/org/ftckb/repository/RepositoryIndex.kt`: index, lexical ranking, and refresh.

### Runtime files

- `modules/agent-runtime/src/main/kotlin/org/ftckb/agent/RetrievalModels.kt`: validated intent and context evidence types.
- `modules/agent-runtime/src/main/kotlin/org/ftckb/agent/ModelJson.kt`: fenced/JSON-object extraction through Jackson.
- `modules/agent-runtime/src/main/kotlin/org/ftckb/agent/RetrievalPlanner.kt`: first model call and fallback.
- `modules/agent-runtime/src/main/kotlin/org/ftckb/agent/KnowledgeRetriever.kt`: approved rule and Markdown guide retrieval.
- `modules/agent-runtime/src/main/kotlin/org/ftckb/agent/ContextRetriever.kt`: combine code, rules, and guides under budget.
- `modules/agent-runtime/src/main/kotlin/org/ftckb/agent/AnswerGenerator.kt`: second model call and citation validation.
- `modules/agent-runtime/src/main/kotlin/org/ftckb/agent/Conversation.kt`: recent turns, rolling summary, and redacted save.
- `modules/agent-runtime/src/main/kotlin/org/ftckb/agent/AskAgent.kt`: one-turn orchestration.

### CLI files

- `apps/knowledge-cli/src/main/kotlin/org/ftckb/cli/Main.kt`: route `chat` without changing `validate`/`resolve` behavior.
- `apps/knowledge-cli/src/main/kotlin/org/ftckb/cli/ChatOptions.kt`: strict command option parsing.
- `apps/knowledge-cli/src/main/kotlin/org/ftckb/cli/AskChatSession.kt`: testable REPL boundary.
- `apps/knowledge-cli/src/main/kotlin/org/ftckb/cli/ChatRepl.kt`: terminal loop and Ask commands.
- `apps/knowledge-cli/src/main/kotlin/org/ftckb/cli/ProductionChatLauncher.kt`: production dependency assembly.

---

### Task 1: Establish the provider-neutral contract and strict profile configuration

**Files:**
- Modify: `settings.gradle.kts`
- Modify: `gradle/libs.versions.toml`
- Create: `modules/model-provider/build.gradle.kts`
- Create: `modules/model-provider/src/main/kotlin/org/ftckb/model/ModelTypes.kt`
- Create: `modules/model-provider/src/main/kotlin/org/ftckb/model/ProviderConfig.kt`
- Create: `modules/model-provider/src/main/kotlin/org/ftckb/model/ProviderConfigLoader.kt`
- Test: `modules/model-provider/src/test/kotlin/org/ftckb/model/ProviderConfigLoaderTest.kt`

**Interfaces:**
- Consumes: no new internal interfaces.
- Produces: `ModelProvider.complete(ModelRequest):ModelResponse`, `ProviderConfigLoader.decode(String):ProviderConfig`, `ProviderConfig.profile(String):ProviderProfile`, and `SecretResolver.get(String):String?`.

- [ ] **Step 1: Write the failing configuration tests**

Create `ProviderConfigLoaderTest.kt` with these complete cases:

```kotlin
package org.ftckb.model

import java.net.URI
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ProviderConfigLoaderTest {
    @Test
    fun `decodes a strict deepseek profile`() {
        val config=ProviderConfigLoader.decode("""
            defaultProvider: deepseek
            providers:
              deepseek:
                baseUrl: https://api.deepseek.com
                model: deepseek-chat
                apiKeyEnv: DEEPSEEK_API_KEY
                timeoutSeconds: 90
                maxOutputTokens: 4096
                maxTokensParameter: max_tokens
                jsonMode: false
        """.trimIndent())

        assertEquals("deepseek",config.defaultProvider)
        assertEquals(
            ProviderProfile(
                "deepseek",URI("https://api.deepseek.com"),"deepseek-chat","DEEPSEEK_API_KEY",
                90,4096,MaxTokensParameter.MAX_TOKENS,false
            ),
            config.profile("deepseek")
        )
    }

    @Test
    fun `rejects unknown profile fields`() {
        val error=assertThrows(IllegalStateException::class.java) {
            ProviderConfigLoader.decode("""
                defaultProvider: custom
                providers:
                  custom:
                    baseUrl: https://example.com/v1
                    model: model
                    apiKeyEnv: CUSTOM_KEY
                    unsafeExtraBody: true
            """.trimIndent())
        }
        assertEquals("providers.custom contains unknown fields: unsafeExtraBody",error.message)
    }

    @Test
    fun `rejects non-https provider roots`() {
        val error=assertThrows(IllegalArgumentException::class.java) {
            ProviderProfile("x",URI("http://example.com"),"m","KEY",90,4096,null,false)
        }
        assertEquals("provider baseUrl must use HTTPS without credentials",error.message)
    }
}
```

- [ ] **Step 2: Register the module and run the focused test to verify RED**

Add `include(":modules:model-provider")` to `settings.gradle.kts`. Add Jackson `2.22.0`, JGit `7.7.0.202606012155-r`, and SLF4J `2.0.18` versions plus their library aliases to the catalog; JGit is consumed in Task 3. Create the model-provider build file with Kotlin/JDK 21, SnakeYAML, and JUnit dependencies.

Run:

```bash
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
./gradlew :modules:model-provider:test --tests 'org.ftckb.model.ProviderConfigLoaderTest'
```

Expected: compilation fails because `ProviderConfigLoader`, `ProviderProfile`, and related types do not exist.

- [ ] **Step 3: Implement the provider-neutral types**

Create `ModelTypes.kt` with these exact public types:

```kotlin
package org.ftckb.model

enum class MessageRole { SYSTEM,USER,ASSISTANT }

data class ModelMessage(val role:MessageRole,val content:String)
data class ModelRequest(val messages:List<ModelMessage>,val maxOutputTokens:Int)
data class TokenUsage(val inputTokens:Int?,val outputTokens:Int?)
data class ModelResponse(val content:String,val usage:TokenUsage?=null)

fun interface ModelProvider {
    fun complete(request:ModelRequest):ModelResponse
}

sealed class ModelProviderException(message:String,cause:Throwable?=null):RuntimeException(message,cause) {
    class Authentication:ModelProviderException("model provider authentication failed")
    class RateLimited:ModelProviderException("model provider rate limit reached")
    class Transport(cause:Throwable):ModelProviderException("model provider transport failed",cause)
    class Protocol(message:String):ModelProviderException(message)
}
```

Create `ProviderConfig.kt` with immutable profile validation:

```kotlin
package org.ftckb.model

import java.net.URI

enum class MaxTokensParameter { MAX_TOKENS,MAX_COMPLETION_TOKENS }

data class ProviderProfile(
    val name:String,
    val baseUrl:URI,
    val model:String,
    val apiKeyEnv:String,
    val timeoutSeconds:Int=90,
    val maxOutputTokens:Int=4096,
    val maxTokensParameter:MaxTokensParameter?=null,
    val jsonMode:Boolean=false
) {
    init {
        require(name.matches(Regex("[a-z0-9][a-z0-9.-]*"))) { "invalid provider name" }
        require(baseUrl.scheme.equals("https",true) && !baseUrl.host.isNullOrBlank() && baseUrl.userInfo==null) {
            "provider baseUrl must use HTTPS without credentials"
        }
        require(model.isNotBlank()) { "provider model must not be blank" }
        require(apiKeyEnv.matches(Regex("[A-Z_][A-Z0-9_]*"))) { "invalid apiKeyEnv" }
        require(timeoutSeconds in 1..300) { "timeoutSeconds must be between 1 and 300" }
        require(maxOutputTokens in 1..131072) { "maxOutputTokens must be between 1 and 131072" }
    }
}

data class ProviderConfig(val defaultProvider:String,val providers:Map<String,ProviderProfile>) {
    fun profile(name:String=defaultProvider)=providers[name] ?: error("unknown provider profile: $name")
}

fun interface SecretResolver {
    fun get(name:String):String?
}
```

- [ ] **Step 4: Implement strict YAML decoding**

In `ProviderConfigLoader.kt`, use SnakeYAML `LoadSettings.builder().setAllowDuplicateKeys(false)` and reject every root/profile key outside this set:

```kotlin
private val profileKeys=setOf(
    "baseUrl","model","apiKeyEnv","timeoutSeconds","maxOutputTokens","maxTokensParameter","jsonMode"
)
```

Map `max_tokens` to `MAX_TOKENS`, `max_completion_tokens` to `MAX_COMPLETION_TOKENS`, and absence to `null`. Require a non-empty providers map and require `defaultProvider` to name an existing profile. Reuse the strict map/string/integer/boolean helper style in `RuleYamlCodec.kt`; do not share its private helpers or accept unknown fields.

- [ ] **Step 5: Run provider module tests and the existing suite**

Run:

```bash
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew \
  :modules:model-provider:test :modules:domain:test :modules:knowledge:test
```

Expected: all tests PASS.

- [ ] **Step 6: Commit Task 1**

```bash
git add settings.gradle.kts gradle/libs.versions.toml modules/model-provider
git commit -m "feat: define model provider configuration"
```

---

### Task 2: Implement the baseline OpenAI-compatible HTTP adapter

**Files:**
- Create: `modules/model-provider-openai-compatible/build.gradle.kts`
- Create: `modules/model-provider-openai-compatible/src/main/kotlin/org/ftckb/model/openai/HttpTransport.kt`
- Create: `modules/model-provider-openai-compatible/src/main/kotlin/org/ftckb/model/openai/ChatCompletionsProvider.kt`
- Create: `modules/model-provider-openai-compatible/src/main/kotlin/org/ftckb/model/openai/ProviderFactory.kt`
- Test: `modules/model-provider-openai-compatible/src/test/kotlin/org/ftckb/model/openai/ChatCompletionsProviderTest.kt`
- Test resource: `modules/model-provider-openai-compatible/src/test/resources/openai-success.json`
- Test resource: `modules/model-provider-openai-compatible/src/test/resources/deepseek-success.json`

**Interfaces:**
- Consumes: `ProviderProfile`, `SecretResolver`, `ModelProvider`, `ModelRequest`, `ModelResponse`.
- Produces: `ProviderFactory.create(profile,secretResolver,transport):ModelProvider` and injectable `HttpTransport.send(HttpExchange):HttpResult`.

- [ ] **Step 1: Write failing request and response contract tests**

Create a fake transport that records the request and returns fixture JSON. Assert all of the following in `ChatCompletionsProviderTest`:

```kotlin
val response=provider.complete(ModelRequest(
    listOf(ModelMessage(MessageRole.SYSTEM,"system"),ModelMessage(MessageRole.USER,"hello")),512
))

assertEquals("answer",response.content)
assertEquals(12,response.usage?.inputTokens)
assertEquals(7,response.usage?.outputTokens)
assertEquals(URI("https://api.deepseek.com/chat/completions"),transport.exchange.uri)
assertEquals("Bearer test-key",transport.exchange.headers["Authorization"])
assertEquals("deepseek-chat",mapper.readTree(transport.exchange.body)["model"].asText())
assertEquals(512,mapper.readTree(transport.exchange.body)["max_tokens"].asInt())
```

Add separate tests for `max_completion_tokens`, `response_format:{"type":"json_object"}`, HTTP 401, HTTP 429, HTTP 500, empty `choices`, and missing environment keys. Error assertions must contain no key value.

- [ ] **Step 2: Run focused tests to verify RED**

Register `:modules:model-provider-openai-compatible`, depend on `:modules:model-provider` and `libs.jackson.databind`, then run:

```bash
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
./gradlew :modules:model-provider-openai-compatible:test
```

Expected: compilation fails because the adapter classes do not exist.

- [ ] **Step 3: Implement the injectable HTTP boundary**

Create these exact transport types:

```kotlin
package org.ftckb.model.openai

import java.net.URI

data class HttpExchange(
    val uri:URI,val headers:Map<String,String>,val body:String,val timeoutSeconds:Int
)
data class HttpResult(val status:Int,val body:String)

fun interface HttpTransport {
    fun send(exchange:HttpExchange):HttpResult
}
```

Implement `JdkHttpTransport` with Java 21 `HttpClient`, `POST`, UTF-8 JSON, request timeout, response size enforcement at 4 MiB, and interruption preservation (`Thread.currentThread().interrupt()`). It must not follow redirects.

- [ ] **Step 4: Implement request encoding and response normalization**

`ChatCompletionsProvider` must:

1. normalize exactly one trailing slash from `baseUrl` before adding `/chat/completions`;
2. create JSON through Jackson nodes, never string concatenation;
3. send only `model`, `messages`, configured token field, optional `response_format`, and `stream:false`;
4. map roles to lowercase;
5. accept only `choices[0].message.content` as non-blank text;
6. read `usage.prompt_tokens` and `usage.completion_tokens` when present;
7. map 401/403 to `Authentication`, 429 to `RateLimited`, other non-2xx to a redacted `Protocol`, and I/O failures to `Transport`.

`ProviderFactory.create` resolves the API key once, rejects missing/blank values with `missing API key environment variable: NAME`, and passes the value only into the adapter constructor.

- [ ] **Step 5: Run adapter and provider tests**

```bash
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew \
  :modules:model-provider:test :modules:model-provider-openai-compatible:test
```

Expected: all tests PASS and test output contains no fixture key.

- [ ] **Step 6: Commit Task 2**

```bash
git add settings.gradle.kts modules/model-provider-openai-compatible
git commit -m "feat: add OpenAI compatible model adapter"
```

---

### Task 3: Detect and safely index an FTC repository

**Files:**
- Create: `modules/repository-analysis/build.gradle.kts`
- Create: `modules/repository-analysis/src/main/kotlin/org/ftckb/repository/RepositoryModels.kt`
- Create: `modules/repository-analysis/src/main/kotlin/org/ftckb/repository/FtcProjectDetector.kt`
- Create: `modules/repository-analysis/src/main/kotlin/org/ftckb/repository/GitIgnoreRules.kt`
- Create: `modules/repository-analysis/src/main/kotlin/org/ftckb/repository/SafeRepositoryWalker.kt`
- Create: `modules/repository-analysis/src/main/kotlin/org/ftckb/repository/RepositoryIndex.kt`
- Test: `modules/repository-analysis/src/test/kotlin/org/ftckb/repository/FtcProjectDetectorTest.kt`
- Test: `modules/repository-analysis/src/test/kotlin/org/ftckb/repository/RepositoryIndexTest.kt`

**Interfaces:**
- Consumes: only JDK filesystem APIs.
- Produces: `FtcProjectDetector.detect(Path):FtcProjectProfile`, `RepositoryIndex.build(Path):RepositorySnapshot`, `RepositoryIndex.search(LocalQuery,Int):List<SourceFragment>`, and `RepositoryIndex.refresh(Set<String>):RepositorySnapshot`.

- [ ] **Step 1: Write failing FTC detection and safety tests**

Use `@TempDir` to build one supported fixture containing `settings.gradle`, `TeamCode/build.gradle`, and an `@TeleOp` Java file. Assert:

```kotlin
val profile=FtcProjectDetector.detect(root)
assertTrue(profile.supported)
assertEquals(setOf("TeamCode"),profile.sourceModules)
assertTrue(profile.markers.any { it.kind==ProjectMarkerKind.OPMODE_ANNOTATION })
```

Add negative fixtures for a plain Kotlin project and an empty `TeamCode` directory. Add index tests proving that `.git`, `.gradle`, `build`, files over 1 MiB, `.env`, `local.properties`, a binary containing NUL, and a symlink outside the root are excluded. Add root and nested `.gitignore` files proving directory patterns, wildcard patterns, and `!` negation follow Git semantics.

- [ ] **Step 2: Run focused tests to verify RED**

Register `:modules:repository-analysis`, depend on JGit, and add SLF4J NOP at test/runtime. Then run:

```bash
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
./gradlew :modules:repository-analysis:test
```

Expected: compilation fails because repository types do not exist.

- [ ] **Step 3: Implement immutable repository models**

Create these public contracts in `RepositoryModels.kt`:

```kotlin
package org.ftckb.repository

import java.nio.file.Path

enum class ProjectMarkerKind { GRADLE_SETTINGS,TEAMCODE_MODULE,FTC_DEPENDENCY,OPMODE_ANNOTATION }
data class ProjectMarker(val kind:ProjectMarkerKind,val path:String,val detail:String)
data class FtcProjectProfile(val supported:Boolean,val sourceModules:Set<String>,val markers:List<ProjectMarker>)

data class IndexedDocument(
    val path:String,val sha256:String,val text:String,val lines:List<String>,val terms:Set<String>
)
data class RepositorySnapshot(
    val root:Path,val profile:FtcProjectProfile,val documents:Map<String,IndexedDocument>
)
data class LocalQuery(
    val terms:Set<String>,val symbols:Set<String> =emptySet(),val pathGlobs:Set<String> =emptySet()
)
data class SourceFragment(
    val path:String,val startLine:Int,val endLine:Int,val sha256:String,val text:String,val score:Int
)
```

- [ ] **Step 4: Implement bounded traversal, detection, and ranking**

`GitIgnoreRules` loads root and nested `.gitignore` files through JGit `IgnoreNode`, evaluates patterns relative to the directory that owns each ignore file, and applies lower-level rules after parent rules. It never opens a Git repository or runs `git`.

`SafeRepositoryWalker` must normalize the real root once, reject escaped symlinks, consult `GitIgnoreRules` before reading a file, limit regular files to 1 MiB, read strict UTF-8, reject NUL bytes, and allow only the extensions approved by the design. Protect case-insensitive basenames `.env`, `local.properties`, keystore extensions, and known output directories.

`FtcProjectDetector` requires at least two distinct marker kinds, one of which is `FTC_DEPENDENCY` or `OPMODE_ANNOTATION`; a directory name alone is insufficient.

`RepositoryIndex.search` scores exact path/symbol matches above term matches, returns stable ordering by descending score then path/start line, and creates at most 80-line fragments with five lines of context around hits. Hash files with SHA-256 over original UTF-8 bytes.

- [ ] **Step 5: Run repository tests and confirm deterministic results**

```bash
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
./gradlew :modules:repository-analysis:test
```

Expected: all tests PASS on repeated runs with identical result ordering.

- [ ] **Step 6: Commit Task 3**

```bash
git add settings.gradle.kts modules/repository-analysis
git commit -m "feat: index local FTC repositories safely"
```

---

### Task 4: Build two-stage retrieval and verified answer generation

**Files:**
- Create: `modules/agent-runtime/build.gradle.kts`
- Create: `modules/agent-runtime/src/main/kotlin/org/ftckb/agent/RetrievalModels.kt`
- Create: `modules/agent-runtime/src/main/kotlin/org/ftckb/agent/ModelJson.kt`
- Create: `modules/agent-runtime/src/main/kotlin/org/ftckb/agent/RetrievalPlanner.kt`
- Create: `modules/agent-runtime/src/main/kotlin/org/ftckb/agent/KnowledgeRetriever.kt`
- Create: `modules/agent-runtime/src/main/kotlin/org/ftckb/agent/ContextRetriever.kt`
- Create: `modules/agent-runtime/src/main/kotlin/org/ftckb/agent/AnswerGenerator.kt`
- Test: `modules/agent-runtime/src/test/kotlin/org/ftckb/agent/RetrievalPlannerTest.kt`
- Test: `modules/agent-runtime/src/test/kotlin/org/ftckb/agent/AnswerGeneratorTest.kt`

**Interfaces:**
- Consumes: `ModelProvider`, `RepositorySnapshot`, `RepositoryIndex`, `FileKnowledgeRepository`, and `RuleResolver`.
- Produces: `RetrievalPlanner.plan(PlanningInput):RetrievalIntent`, `ContextRetriever.retrieve(RetrievalIntent):ContextPack`, and `AnswerGenerator.generate(AnswerInput):AgentAnswer`.

- [ ] **Step 1: Write failing planner fallback and citation tests**

Use a scripted provider whose queued outputs are fixed strings. Test malformed JSON followed by valid JSON, two malformed outputs followed by deterministic fallback, a valid answer with `[CODE:C1]` and `[RULE:R1]`, an invented citation followed by a corrected retry, and two invented-citation responses ending in `CitationValidationException`.

The central assertion is:

```kotlin
assertEquals(
    listOf(
        AnswerClaim(ClaimKind.CODE_OBSERVATION,"The null check is missing.",listOf("CODE:C1")),
        AnswerClaim(ClaimKind.MODEL_INFERENCE,"Adding a guard is likely safest.",emptyList())
    ),
    answer.claims
)
```

- [ ] **Step 2: Run focused tests to verify RED**

Register `:modules:agent-runtime`, depend on domain, knowledge, repository-analysis, model-provider, and Jackson, then run:

```bash
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
./gradlew :modules:agent-runtime:test
```

Expected: compilation fails because retrieval and answer types do not exist.

- [ ] **Step 3: Define the exact retrieval and answer contracts**

Create these types in `RetrievalModels.kt`:

```kotlin
data class RetrievalIntent(
    val concepts:Set<String>,val symbols:Set<String>,val pathGlobs:Set<String>,
    val ruleTopics:Set<String>,val guideTopics:Set<String>
)
data class PlanningInput(
    val question:String,val recentSummary:String?,val recentReferences:Set<String>,
    val repositorySummary:String
)
sealed interface EvidenceItem { val id:String }
data class CodeEvidence(
    override val id:String,val path:String,val startLine:Int,val endLine:Int,
    val sha256:String,val text:String
):EvidenceItem
data class RuleEvidenceItem(override val id:String,val rule:KnowledgeRule):EvidenceItem
data class GuideEvidence(
    override val id:String,val path:String,val heading:String,val text:String
):EvidenceItem
data class ContextPack(val evidence:List<EvidenceItem>,val estimatedCharacters:Int)
data class AnswerInput(val question:String,val priorContext:String?,val context:ContextPack)
enum class ClaimKind { APPROVED_RULE,CODE_OBSERVATION,MODEL_INFERENCE,INSUFFICIENT_EVIDENCE }
data class AnswerClaim(val kind:ClaimKind,val text:String,val citations:List<String>)
data class AgentAnswer(val claims:List<AnswerClaim>,val usage:TokenUsage?)
```

Validate all sets at maximum 12 values, each value at 1..120 characters, and globs as repository-relative patterns without `..`, absolute roots, or backslashes.

- [ ] **Step 4: Implement planner, local context retrieval, and answer validation**

`ModelJson` accepts either a JSON object body or one fenced `json` block and rejects trailing non-whitespace outside the fence. Use Jackson `JsonNode`; reject unknown fields explicitly.

`RetrievalPlanner` requests exactly the five intent arrays. After one repair retry, fallback tokenizes the user question plus recently referenced symbols, removes stop words, and produces concepts only.

`KnowledgeRetriever` loads rules once, rejects validation violations, resolves by team/season, selects only approved active rules matching requested topics/terms, and searches Markdown headings/text separately as guides.

`ContextRetriever` assigns IDs in stable order, caps total selected content at 48,000 characters, and never cuts a selected code fragment mid-line.

`AnswerGenerator` requests `{"claims":[{"kind":"code_observation","text":"The result is used without a guard.","citations":["CODE:C1"]}]}`. Enforce these rules:

- `approved_rule` has at least one current `RULE:*` ID;
- `code_observation` has at least one current `CODE:*` ID whose file hash still matches;
- `model_inference` and `insufficient_evidence` may have no citation;
- every supplied citation exists in the current pack;
- no blank claim text.

Construct `AnswerGenerator` with the current `RepositoryIndex`; immediately before accepting a `CODE:*` claim, compare the evidence hash to the index's current hash for that path. `AnswerInput.priorContext` contains compact conversation text only and never counts as evidence.

- [ ] **Step 5: Run runtime tests and existing rule tests**

```bash
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew \
  :modules:agent-runtime:test :modules:domain:test :modules:knowledge:test
```

Expected: all tests PASS.

- [ ] **Step 6: Commit Task 4**

```bash
git add settings.gradle.kts modules/agent-runtime
git commit -m "feat: retrieve FTC context with verified citations"
```

---

### Task 5: Add continuous conversation, rolling context, and explicit redacted save

**Files:**
- Create: `modules/agent-runtime/src/main/kotlin/org/ftckb/agent/Conversation.kt`
- Create: `modules/agent-runtime/src/main/kotlin/org/ftckb/agent/AskAgent.kt`
- Test: `modules/agent-runtime/src/test/kotlin/org/ftckb/agent/ConversationTest.kt`
- Test: `modules/agent-runtime/src/test/kotlin/org/ftckb/agent/AskAgentTest.kt`

**Interfaces:**
- Consumes: `RetrievalPlanner`, `ContextRetriever`, `AnswerGenerator`.
- Produces: `AskAgent.ask(String):AgentAnswer`, `ConversationState.record(question:String,answer:AgentAnswer,referencedIds:Set<String>)`, `ConversationState.context():ConversationContext`, and `ConversationSaver.save(state:ConversationState,path:Path):Path`.

- [ ] **Step 1: Write failing multi-turn and save tests**

Test that a second question containing “刚才那个类” includes the previously cited path in `recentReferences`, that old turns are summarized after the configured character budget, and that summaries are never accepted as code evidence.

Test `/save` behavior through `ConversationSaver`:

```kotlin
val saved=saver.save(state,root.resolve("session.md"))
val text=Files.readString(saved)
assertTrue(text.contains("Provider: deepseek / deepseek-chat"))
assertFalse(text.contains("sk-secret-value"))
assertFalse(text.contains("Authorization:"))
assertThrows(FileAlreadyExistsException::class.java) { saver.save(state,saved) }
```

- [ ] **Step 2: Run focused tests to verify RED**

```bash
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew \
  :modules:agent-runtime:test --tests 'org.ftckb.agent.ConversationTest' \
  --tests 'org.ftckb.agent.AskAgentTest'
```

Expected: compilation fails because conversation types do not exist.

- [ ] **Step 3: Implement conversation state and orchestration**

Use these immutable boundary types:

```kotlin
data class ConversationTurn(val question:String,val answer:AgentAnswer,val referencedIds:Set<String>)
data class ConversationContext(
    val rollingSummary:String?,val recentTurns:List<ConversationTurn>,val recentReferences:Set<String>
)
```

Keep at most eight recent turns or 24,000 characters, whichever is reached first. When older turns roll out, call the model with a summary prompt that asks only for user goals, named files/symbols, decisions, and unresolved questions. Label the result internally as untrusted summary text; do not turn it into `EvidenceItem`.

`AskAgent.ask` performs plan → retrieve → answer → record exactly once per submitted question. If answer generation fails citation validation, do not append a fabricated assistant turn.

- [ ] **Step 4: Implement explicit save and redaction boundary**

`ConversationSaver` writes UTF-8 Markdown with provider/model name, timestamp, user questions, rendered claims, citations, and compact summaries. Default filename generation belongs to the CLI; this class takes an explicit path and uses `CREATE_NEW`.

Redact case-insensitive bearer authorization values, common `sk-` token forms, API-key assignments, and any exact secret values supplied to the redactor. Do not write retrieved code bodies.

- [ ] **Step 5: Run runtime tests**

```bash
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
./gradlew :modules:agent-runtime:test
```

Expected: all tests PASS.

- [ ] **Step 6: Commit Task 5**

```bash
git add modules/agent-runtime
git commit -m "feat: maintain redacted FTC chat sessions"
```

---

### Task 6: Expose the continuous Ask REPL and verify the end-to-end slice

**Files:**
- Modify: `apps/knowledge-cli/build.gradle.kts`
- Modify: `apps/knowledge-cli/src/main/kotlin/org/ftckb/cli/Main.kt`
- Create: `apps/knowledge-cli/src/main/kotlin/org/ftckb/cli/ChatOptions.kt`
- Create: `apps/knowledge-cli/src/main/kotlin/org/ftckb/cli/AskChatSession.kt`
- Create: `apps/knowledge-cli/src/main/kotlin/org/ftckb/cli/ChatRepl.kt`
- Create: `apps/knowledge-cli/src/main/kotlin/org/ftckb/cli/ProductionChatLauncher.kt`
- Modify: `apps/knowledge-cli/src/test/kotlin/org/ftckb/cli/MainTest.kt`
- Test: `apps/knowledge-cli/src/test/kotlin/org/ftckb/cli/ChatReplTest.kt`
- Test fixture: `fixtures/agent/ask-repo/settings.gradle`
- Test fixture: `fixtures/agent/ask-repo/TeamCode/build.gradle`
- Test fixture: `fixtures/agent/ask-repo/TeamCode/src/main/java/example/SampleTeleOp.java`

**Interfaces:**
- Consumes: `AskAgent`, provider factory, repository index, and knowledge repository.
- Produces: `chat` CLI command and `ChatLauncher.run(ChatOptions,BufferedReader,PrintStream):Int`.

- [ ] **Step 1: Write failing option and REPL acceptance tests**

Preserve every existing `validate`/`resolve` assertion. Add tests for missing `--team`, invalid season, duplicate provider, and an unknown chat option returning exit `64` before loading files.

Use a fake `ChatLauncher` to prove `runCli` routes parsed options. Test `ChatRepl` through a fake `AskChatSession` with input:

```text
为什么 SampleTeleOp 可能空指针？
/status
/save SESSION_PATH
/exit
```

Assert the output contains the repository/team/provider status, a rendered `代码观察 [CODE:C1]`, the save path, and no automatic save message at exit.

- [ ] **Step 2: Run CLI tests to verify RED**

```bash
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
./gradlew :apps:knowledge-cli:test --tests 'org.ftckb.cli.ChatReplTest' \
  --tests 'org.ftckb.cli.MainTest'
```

Expected: tests fail because `chat` and REPL types do not exist.

- [ ] **Step 3: Implement strict chat option parsing and dependency injection**

Use this exact option model:

```kotlin
data class ChatOptions(
    val repository:Path,
    val knowledge:Path,
    val team:String,
    val season:String,
    val provider:String,
    val config:Path
)

fun interface ChatLauncher {
    fun run(options:ChatOptions,input:BufferedReader,out:PrintStream):Int
}

interface AskChatSession {
    fun ask(question:String):AgentAnswer
    fun status():ChatStatus
    fun save(path:Path?):Path
}

data class ChatStatus(
    val repository:Path,val team:String,val season:String,val provider:String,val model:String
)
```

Extend `runCli` with defaulted `input` and `chatLauncher` parameters so existing callers remain source-compatible. Parse `chat` before loading knowledge. Require `--knowledge`, `--team`, `--season`, and `--provider`; default `--repo` to the process working directory and `--config` to `${user.home}/.ftckb/config.yaml`.

- [ ] **Step 4: Implement the Ask-only terminal loop**

On startup, `ProductionChatLauncher` validates config/key, indexes the repository, rejects unsupported FTC roots, validates knowledge, and constructs `AskAgent`.

`ChatRepl` supports `/help`, `/mode ask`, `/status`, `/save [path]`, and `/exit`. `/mode edit`, `/undo`, `/discard`, `/diff`, and `/commit` must print `not available in Ask Core` rather than silently doing nothing. Render claim labels in Chinese and append verified citations exactly as returned.

EOF behaves like `/exit`. A blank line does not call the model. Provider and citation failures print one controlled diagnostic and keep the REPL alive.

- [ ] **Step 5: Run the complete Ask acceptance suite**

Add a `FakeModelProvider` integration test using the fixture repository and real knowledge files. It must exercise retrieval planning, local search, rule resolution, answer validation, follow-up context, and explicit save without HTTP.

Run:

```bash
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
./gradlew clean test --no-daemon
```

Expected: `BUILD SUCCESSFUL`; every old CLI and knowledge test remains green.

- [ ] **Step 6: Verify the real CLI help path without credentials**

```bash
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
./gradlew :apps:knowledge-cli:run --args='chat --help' --quiet
```

Expected: prints chat usage and exits `0` without reading a provider config or API key.

- [ ] **Step 7: Commit Task 6**

```bash
git add apps/knowledge-cli fixtures/agent/ask-repo
git commit -m "feat: add continuous FTC Ask chat"
```

---

## Phase 1 Review Gate

Before Phase 2 begins, verify:

```bash
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew clean test --no-daemon
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
./gradlew :apps:knowledge-cli:run --args='validate knowledge' --quiet
git diff --check
git status --short
```

Required outcomes:

- all tests pass;
- knowledge validation remains `validation=ok rules=23` unless separately approved knowledge changes have landed;
- no live network is required by tests;
- no target repository files can be modified;
- every approved-rule/code-observation claim has a verified current citation;
- `/save` is the only persistence path;
- the worktree contains only intentionally uncommitted work.

Phase 2 starts from this accepted Ask Core and adds Edit without weakening its provider, retrieval, privacy, or citation boundaries.
