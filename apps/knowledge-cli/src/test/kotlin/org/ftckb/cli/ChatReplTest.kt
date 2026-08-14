package org.ftckb.cli

import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.io.StringReader
import java.nio.file.Files
import java.nio.file.Path
import org.ftckb.agent.AgentAnswer
import org.ftckb.agent.AnswerClaim
import org.ftckb.agent.CitationValidationException
import org.ftckb.agent.ClaimKind
import org.ftckb.model.ModelMessage
import org.ftckb.model.ModelProvider
import org.ftckb.model.ModelProviderException
import org.ftckb.model.ModelRequest
import org.ftckb.model.ModelResponse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ChatReplTest {
    @Test
    fun `continuous Ask chat answers reports status and saves only explicitly`(@TempDir root:Path) {
        val savePath=root.resolve("session.md")
        val session=FakeAskChatSession(
            AgentAnswer(
                listOf(AnswerClaim(ClaimKind.CODE_OBSERVATION,"motor may be null",listOf("CODE:C1"))),
                null
            ),
            ChatStatus(Path.of("fixture-repo"),"20827","2025-2026","fake","offline-model"),
            savePath
        )
        val input=BufferedReader(StringReader("""
            为什么 SampleTeleOp 可能空指针？
            /status
            /save $savePath
            /exit
        """.trimIndent()+"\n"))
        val output=ByteArrayOutputStream()

        val code=ChatRepl(session,input,PrintStream(output)).run()

        assertEquals(0,code)
        assertEquals(listOf("为什么 SampleTeleOp 可能空指针？"),session.questions)
        val text=output.toString()
        assertTrue(text.contains("代码观察 [CODE:C1]"))
        assertTrue(text.contains("repository=fixture-repo"))
        assertTrue(text.contains("team=20827"))
        assertTrue(text.contains("provider=fake model=offline-model"))
        assertTrue(text.contains("saved=$savePath"))
        assertEquals(1,text.lineSequence().count { it.startsWith("saved=") })
        assertFalse(text.contains("automatic"))
    }

    @Test
    fun `production Ask chat runs end to end offline with follow-up context and explicit save`(@TempDir root:Path) {
        val repository=Path.of("..","..","fixtures","agent","ask-repo").normalize()
        val knowledge=Path.of("..","..","knowledge").normalize()
        val config=root.resolve("config.yaml")
        Files.writeString(config,"""
            defaultProvider: fake
            providers:
              fake:
                baseUrl: https://example.invalid/v1
                model: offline-model
                apiKeyEnv: FTC_KB_FAKE_KEY
        """.trimIndent())
        val saved=root.resolve("session.md")
        val exactSecret=listOf("sensitive","test","value").joinToString("-")
        val provider=ScriptedFakeProvider(exactSecret)
        val launcher=ProductionChatLauncher(
            environment={ name -> if (name=="FTC_KB_FAKE_KEY") exactSecret else null },
            providerCreator={ profile,resolver ->
                assertEquals(exactSecret,resolver.get(profile.apiKeyEnv))
                provider
            }
        )
        val input=BufferedReader(StringReader("""
            为什么 SampleTeleOp 可能空指针？
            这个类在哪个文件？ $exactSecret
            /status
            /save $saved
            /exit
        """.trimIndent()+"\n"))
        val output=ByteArrayOutputStream()

        val code=launcher.run(
            ChatOptions(repository,knowledge,"20827","2025-2026","fake",config),
            input,
            PrintStream(output)
        )

        assertEquals(0,code)
        val text=output.toString()
        assertTrue(text.contains("代码观察 [CODE:C1]"))
        assertTrue(text.contains("已批准规则 [RULE:R1]"))
        assertTrue(text.contains("TeamCode/src/main/java/example/SampleTeleOp.java"))
        assertTrue(text.contains("saved=$saved"))
        assertTrue(text.contains("[REDACTED]"))
        assertFalse(text.contains(exactSecret))
        assertTrue(provider.requests.count { it.messages.first().content.startsWith("Answer only as JSON") }>=2)
        assertTrue(provider.requests.any { request ->
            request.messages.any { it.content.contains("Conversation context (not evidence)") }
        })
        assertTrue(provider.requests.none { request -> request.messages.any { exactSecret in it.content } })
        assertTrue(Files.readString(saved).contains("[REDACTED]"))
        assertFalse(Files.readString(saved).contains(exactSecret))
    }

    @Test
    fun `Ask loop keeps running after controlled failures and rejects edit commands`() {
        val questions=mutableListOf<String>()
        val sensitiveDiagnostic=listOf("sensitive","diagnostic","value").joinToString("-")
        val session=object:AskChatSession {
            override fun ask(question:String):AgentAnswer {
                questions+=question
                return when (question) {
                    "provider failure" -> throw ModelProviderException.RateLimited()
                    "citation failure" -> throw CitationValidationException("unknown citation: $sensitiveDiagnostic")
                    else -> AgentAnswer(listOf(AnswerClaim(ClaimKind.MODEL_INFERENCE,"still running",emptyList())),null)
                }
            }

            override fun status()=ChatStatus(Path.of("repo"),"20827","2025-2026","fake","offline")

            override fun save(path:Path?):Path=error("save must not be called")
        }
        val input=BufferedReader(StringReader(listOf(
            "",
            "provider failure",
            "citation failure",
            "/save bad\u0000path",
            "recovered",
            "/mode edit",
            "/undo",
            "/discard",
            "/diff",
            "/commit",
            "/mode ask",
            "/help",
            ""
        ).joinToString("\n")))
        val output=ByteArrayOutputStream()

        val code=ChatRepl(session,input,PrintStream(output)).run()

        assertEquals(0,code)
        assertEquals(listOf("provider failure","citation failure","recovered"),questions)
        val lines=output.toString().lines()
        assertTrue(lines.contains("model provider error: request failed"))
        assertTrue(lines.contains("citation validation error: response citations are invalid"))
        assertTrue(lines.contains("save error: unable to save session"))
        assertFalse(output.toString().contains(sensitiveDiagnostic))
        assertTrue(lines.contains("模型推断: still running"))
        assertEquals(5,lines.count { it=="not available in Ask Core" })
        assertTrue(lines.contains("mode=ask"))
        assertTrue(lines.any { it.startsWith("commands: /help") })
    }

    @Test
    fun `production startup checks environment key before repository files`(@TempDir root:Path) {
        val config=root.resolve("config.yaml")
        Files.writeString(config,"""
            defaultProvider: fake
            providers:
              fake:
                baseUrl: https://example.invalid/v1
                model: offline-model
                apiKeyEnv: FTC_KB_MISSING_KEY
        """.trimIndent())
        val output=ByteArrayOutputStream()
        val launcher=ProductionChatLauncher(
            environment={ null },
            providerCreator={ _,_ -> error("provider must not be created") }
        )

        val code=launcher.run(
            ChatOptions(Path.of("missing-repository"),Path.of("missing-knowledge"),"20827","2025-2026","fake",config),
            BufferedReader(StringReader("")),
            PrintStream(output)
        )

        assertEquals(2,code)
        assertEquals(
            "error starting chat: missing API key environment variable: FTC_KB_MISSING_KEY\n",
            output.toString()
        )
    }

    @Test
    fun `Ask save cannot write into the indexed FTC repository`(@TempDir root:Path) {
        val repository=root.resolve("repository")
        writeFtcRepository(repository)
        val config=root.resolve("config.yaml")
        writeFakeConfig(config,"FTC_KB_FAKE_KEY")
        val destination=repository.resolve("session.md")
        val output=ByteArrayOutputStream()
        val launcher=ProductionChatLauncher(
            environment={ "fake-secret" },
            providerCreator={ _,_ -> ModelProvider { error("provider must not be called") } }
        )

        val code=launcher.run(
            ChatOptions(
                repository,Path.of("..","..","knowledge").normalize(),"20827","2025-2026","fake",config
            ),
            BufferedReader(StringReader("/save ${destination.toAbsolutePath()}\n/exit\n")),
            PrintStream(output)
        )

        assertEquals(0,code)
        assertFalse(Files.exists(destination))
        assertTrue(output.toString().contains("save error: unable to save session"))
    }

    @Test
    fun `relative save paths stay in the sessions directory`(@TempDir root:Path) {
        val repository=root.resolve("repository")
        writeFtcRepository(repository)
        val config=root.resolve("config.yaml")
        writeFakeConfig(config,"FTC_KB_FAKE_KEY")
        val sessions=root.resolve("sessions")
        val output=ByteArrayOutputStream()
        val launcher=ProductionChatLauncher(
            environment={ "fake-secret" },
            providerCreator={ _,_ -> ModelProvider { error("provider must not be called") } },
            sessionsDirectory={ sessions }
        )

        val code=launcher.run(
            ChatOptions(
                repository,Path.of("..","..","knowledge").normalize(),"20827","2025-2026","fake",config
            ),
            BufferedReader(StringReader("/save relative.md\n/exit\n")),
            PrintStream(output)
        )

        assertEquals(0,code)
        assertTrue(Files.isRegularFile(sessions.resolve("relative.md")))
        assertTrue(output.toString().contains("saved=${sessions.resolve("relative.md").toAbsolutePath()}"))
    }

    private class FakeAskChatSession(
        private val answer:AgentAnswer,
        private val chatStatus:ChatStatus,
        private val savedPath:Path
    ):AskChatSession {
        val questions=mutableListOf<String>()

        override fun ask(question:String):AgentAnswer {
            questions+=question
            return answer
        }

        override fun status():ChatStatus=chatStatus

        override fun save(path:Path?):Path {
            assertEquals(savedPath,path)
            return savedPath
        }
    }

    private fun writeFakeConfig(path:Path,environmentName:String) {
        Files.writeString(path,"""
            defaultProvider: fake
            providers:
              fake:
                baseUrl: https://example.invalid/v1
                model: offline-model
                apiKeyEnv: $environmentName
        """.trimIndent())
    }

    private fun writeFtcRepository(root:Path) {
        val teamCode=root.resolve("TeamCode")
        val source=teamCode.resolve("src/main/java/example")
        Files.createDirectories(source)
        Files.writeString(root.resolve("settings.gradle"),"include ':TeamCode'\n")
        Files.writeString(
            teamCode.resolve("build.gradle"),
            "dependencies { implementation 'org.firstinspires.ftc:RobotCore:10.3.0' }\n"
        )
        Files.writeString(source.resolve("SampleTeleOp.java"),"@TeleOp public class SampleTeleOp {}\n")
    }

    private class ScriptedFakeProvider(private val sensitiveAnswerText:String):ModelProvider {
        val requests=mutableListOf<ModelRequest>()

        override fun complete(request:ModelRequest):ModelResponse {
            requests+=request.copy(messages=request.messages.map(ModelMessage::copy))
            val system=request.messages.first().content
            return when {
                system.startsWith("Return exactly one JSON object") -> ModelResponse("""
                    {
                      "concepts":["SampleTeleOp"],
                      "symbols":["SampleTeleOp"],
                      "pathGlobs":[],
                      "ruleTopics":["build-customization-location"],
                      "guideTopics":[]
                    }
                """.trimIndent())
                system.startsWith("Answer only as JSON") -> ModelResponse("""
                    {
                      "claims":[
                        {
                          "kind":"code_observation",
                          "text":"SampleTeleOp may dereference motor before initialization in TeamCode/src/main/java/example/SampleTeleOp.java. $sensitiveAnswerText",
                          "citations":["CODE:C1"]
                        },
                        {
                          "kind":"approved_rule",
                          "text":"Keep legacy build customizations in TeamCode/build.gradle.",
                          "citations":["RULE:R1"]
                        }
                      ]
                    }
                """.trimIndent())
                else -> error("unexpected fake provider request")
            }
        }
    }
}
