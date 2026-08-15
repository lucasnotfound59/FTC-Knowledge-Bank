package org.ftckb.cli

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.json.JsonMapper
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import org.ftckb.model.ModelMessage
import org.ftckb.model.ModelProvider
import org.ftckb.model.ModelRequest
import org.ftckb.model.ModelResponse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class AgentQualityEvaluationTest {
    @Test
    fun `rejects unknown eval fields`() {
        val exception=assertThrows(IllegalStateException::class.java) {
            EvalCasesCodec.decode("""
                schemaVersion: 1
                cases: []
                typo: ignored
            """.trimIndent())
        }
        assertEquals("root contains unknown fields: typo",exception.message)

        val turnException=assertThrows(IllegalStateException::class.java) {
            EvalCasesCodec.decode("""
                schemaVersion: 1
                cases:
                  - id: first
                    repository: fixtures/agent/ask-repo
                    team: "20827"
                    season: 2025-2026
                    turns:
                      - mode: ask
                        prompt: anything
                        typo: ignored
            """.trimIndent())
        }
        assertEquals("cases[0].turns[0] contains unknown fields: typo",turnException.message)
    }

    @Test
    fun `rejects duplicate eval case ids`() {
        val exception=assertThrows(IllegalStateException::class.java) {
            EvalCasesCodec.decode("""
                schemaVersion: 1
                cases:
                  - id: first
                    repository: fixtures/agent/ask-repo
                    team: "20827"
                    season: 2025-2026
                    turns:
                      - mode: ask
                        prompt: one
                  - id: first
                    repository: fixtures/agent/ask-repo
                    team: "20827"
                    season: 2025-2026
                    turns:
                      - mode: ask
                        prompt: two
            """.trimIndent())
        }
        assertEquals("duplicate eval case id",exception.message)
    }

    @Test
    fun `rejects edit criteria without explicit edit mode`() {
        val exception=assertThrows(IllegalStateException::class.java) {
            EvalCasesCodec.decode("""
                schemaVersion: 1
                cases:
                  - id: first
                    repository: fixtures/agent/edit-repo
                    team: "20827"
                    season: 2025-2026
                    turns:
                      - prompt: add a helper
                        requiredChangedPaths: [TeamCode/src/main/java/example/Helper.java]
            """.trimIndent())
        }
        assertEquals("cases[0].turns[0] requires explicit edit mode for requiredChangedPaths",exception.message)
    }

    @Test
    fun `rejects invalid teams seasons and claim kinds`() {
        assertEquals(
            "cases[0].team must contain digits only",
            assertThrows(IllegalStateException::class.java) {
                EvalCasesCodec.decode("""
                    schemaVersion: 1
                    cases:
                      - id: first
                        repository: fixtures/agent/ask-repo
                        team: "20A"
                        season: 2025-2026
                        turns:
                          - mode: ask
                            prompt: anything
                """.trimIndent())
            }.message
        )
        assertEquals(
            "cases[0].season must use YYYY-YYYY",
            assertThrows(IllegalStateException::class.java) {
                EvalCasesCodec.decode("""
                    schemaVersion: 1
                    cases:
                      - id: first
                        repository: fixtures/agent/ask-repo
                        team: "20827"
                        season: "2025"
                        turns:
                          - mode: ask
                            prompt: anything
                """.trimIndent())
            }.message
        )
        assertEquals(
            "cases[0].turns[0].requiredClaimKinds contains an unknown claim kind",
            assertThrows(IllegalStateException::class.java) {
                EvalCasesCodec.decode("""
                    schemaVersion: 1
                    cases:
                      - id: first
                        repository: fixtures/agent/ask-repo
                        team: "20827"
                        season: 2025-2026
                        turns:
                          - mode: ask
                            prompt: anything
                            requiredClaimKinds: [hallucination]
                """.trimIndent())
            }.message
        )
    }

    @Test
    fun `offline eval passes all scripted cases and writes a redacted report`(@TempDir root:Path) {
        val config=writeConfig(root.resolve("config.yaml"))
        val output=root.resolve("report.md")
        val secret="eval-secret-value"
        val provider=ScriptedEvalProvider(loadScriptedResponses())
        val command=EvalCommand(
            environment={ name -> if (name=="FTC_KB_FAKE_KEY") secret else null },
            providerCreator={ _,_ -> provider },
            workingDirectory=Path.of("..","..").normalize()
        )
        val captured=ByteArrayOutputStream()

        val code=command.run(
            listOf(
                "--cases",Path.of("..","..","fixtures","agent","eval","cases.yaml").toString(),
                "--knowledge",Path.of("..","..","knowledge").toString(),
                "--provider","fake",
                "--output",output.toString(),
                "--config",config.toString()
            ),
            PrintStream(captured)
        )

        assertEquals(0,code)
        assertTrue(captured.toString().contains("eval=5/5"))
        val report=Files.readString(output)
        assertTrue(report.contains("provider=fake model=offline-model"))
        listOf("limelight-validity","opmode-initialization","pedro-localizer-location","teleop-cleanup-edit","follow-up-same-class")
            .forEach { id -> assertTrue(report.contains("${id} - PASS"),id) }
        assertTrue(report.contains("branch"))
        assertFalse(report.contains(secret))
        assertFalse(report.contains("class SampleTeleOp"))
        assertFalse(report.contains("package example"))
        assertFalse(report.contains("waitForStart();"))
    }

    @Test
    fun `offline eval fails when a required claim kind is missing`(@TempDir root:Path) {
        val config=writeConfig(root.resolve("config.yaml"))
        val output=root.resolve("report.md")
        val secret="eval-secret-value"
        val provider=InferenceOnlyProvider()
        val command=EvalCommand(
            environment={ name -> if (name=="FTC_KB_FAKE_KEY") secret else null },
            providerCreator={ _,_ -> provider },
            workingDirectory=Path.of("..","..").normalize()
        )
        val captured=ByteArrayOutputStream()

        val code=command.run(
            listOf(
                "--cases",Path.of("..","..","fixtures","agent","eval","cases.yaml").toString(),
                "--knowledge",Path.of("..","..","knowledge").toString(),
                "--provider","fake",
                "--output",output.toString(),
                "--config",config.toString()
            ),
            PrintStream(captured)
        )

        assertEquals(2,code)
        assertTrue(captured.toString().contains("eval=0/5"))
        val report=Files.readString(output)
        assertTrue(report.contains("limelight-validity - FAIL"))
        assertTrue(report.contains("missing claim kind"))
        assertFalse(report.contains(secret))
    }

    @Test
    fun `eval reports missing fixture repositories`(@TempDir root:Path) {
        val config=writeConfig(root.resolve("config.yaml"))
        val output=root.resolve("report.md")
        val cases=root.resolve("cases.yaml")
        Files.writeString(cases,"""
            schemaVersion: 1
            cases:
              - id: missing-fixture
                repository: fixtures/agent/does-not-exist
                team: "20827"
                season: 2025-2026
                turns:
                  - mode: ask
                    prompt: anything
                    requiredClaimKinds: [model_inference]
        """.trimIndent())
        val command=EvalCommand(
            environment={ "fake-key" },
            providerCreator={ _,_ -> InferenceOnlyProvider() },
            workingDirectory=Path.of("..","..").normalize()
        )
        val captured=ByteArrayOutputStream()

        val code=command.run(
            listOf(
                "--cases",cases.toString(),
                "--knowledge",Path.of("..","..","knowledge").toString(),
                "--provider","fake",
                "--output",output.toString(),
                "--config",config.toString()
            ),
            PrintStream(captured)
        )

        assertEquals(2,code)
        assertTrue(captured.toString().contains("eval=0/1"))
        val report=Files.readString(output)
        assertTrue(report.contains("missing-fixture - FAIL"))
        assertTrue(report.contains("missing fixture repository"))
    }

    @Test
    fun `runCli dispatches the eval command`(@TempDir root:Path) {
        val config=writeConfig(root.resolve("config.yaml"))
        val output=root.resolve("report.md")
        val secret="eval-secret-value"
        val command=EvalCommand(
            environment={ name -> if (name=="FTC_KB_FAKE_KEY") secret else null },
            providerCreator={ _,_ -> ScriptedEvalProvider(loadScriptedResponses()) },
            workingDirectory=Path.of("..","..").normalize()
        )
        val captured=ByteArrayOutputStream()

        val code=runCli(
            listOf(
                "eval",
                "--cases",Path.of("..","..","fixtures","agent","eval","cases.yaml").toString(),
                "--knowledge",Path.of("..","..","knowledge").toString(),
                "--provider","fake",
                "--output",output.toString(),
                "--config",config.toString()
            ),
            PrintStream(captured),
            evalCommand=command
        )

        assertEquals(0,code)
        assertTrue(captured.toString().contains("eval=5/5"))
    }

    private fun writeConfig(path:Path):Path {
        Files.writeString(path,"""
            defaultProvider: fake
            providers:
              fake:
                baseUrl: https://example.invalid/v1
                model: offline-model
                apiKeyEnv: FTC_KB_FAKE_KEY
        """.trimIndent())
        return path
    }

    private fun loadScriptedResponses():List<ScriptedEntry> {
        val stream=javaClass.classLoader.getResourceAsStream("eval-scripted-responses.json")
            ?:error("missing eval-scripted-responses.json")
        val root=JsonMapper.builder().build().readTree(stream)
        return root.path("responses").map { node ->
            ScriptedEntry(
                node.path("prompt").asText(),
                node.path("plan"),
                node.takeIf { it.has("answer") }?.path("answer"),
                node.takeIf { it.has("edit") }?.path("edit")
            )
        }
    }

    private data class ScriptedEntry(val prompt:String,val plan:JsonNode,val answer:JsonNode?,val edit:JsonNode?)

    private class ScriptedEvalProvider(private val entries:List<ScriptedEntry>):ModelProvider {
        val requests=mutableListOf<ModelRequest>()

        override fun complete(request:ModelRequest):ModelResponse {
            requests+=request.copy(messages=request.messages.map(ModelMessage::copy))
            val content=request.messages.joinToString("\n") { it.content }
            val question=Regex("(?:Question|Edit request): (.*)").find(content)?.groupValues?.get(1)?.trim()
                ?:error("no question in scripted request")
            val entry=entries.firstOrNull { it.prompt==question }
                ?:error("no scripted entry for: ${question}")
            val system=request.messages.first().content
            return when {
                system.startsWith("Return exactly one JSON object") -> ModelResponse(entry.plan.toString())
                system.startsWith("Return exactly one JSON edit plan") ->
                    ModelResponse(entry.edit?.toString() ?: error("no edit script"))
                system.startsWith("Answer only as JSON") ->
                    ModelResponse(entry.answer?.toString() ?: error("no answer script"))
                else -> error("unexpected scripted request type")
            }
        }
    }

    private class InferenceOnlyProvider:ModelProvider {
        override fun complete(request:ModelRequest):ModelResponse {
            val system=request.messages.first().content
            return when {
                system.startsWith("Return exactly one JSON object") -> ModelResponse("""
                    {
                      "concepts":["SampleTeleOp"],
                      "symbols":["SampleTeleOp"],
                      "pathGlobs":[],
                      "ruleTopics":[],
                      "guideTopics":[]
                    }
                """.trimIndent())
                system.startsWith("Return exactly one JSON edit plan") -> ModelResponse("""
                    {
                      "summary":"Add a cleanup helper.",
                      "operations":[{
                        "kind":"create",
                        "path":"TeamCode/src/main/java/example/CleanupHelper.java",
                        "expectedAbsent":true,
                        "content":"package example;\n",
                        "reason":"Add the requested cleanup helper.",
                        "citations":["CODE:C1"]
                      }]
                    }
                """.trimIndent())
                system.startsWith("Answer only as JSON") -> ModelResponse(
                    """{"claims":[{"kind":"model_inference","text":"It might be unsafe.","citations":[]}]}"""
                )
                else -> error("unexpected scripted request type")
            }
        }
    }
}
