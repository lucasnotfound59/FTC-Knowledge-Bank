package org.ftckb.cli

import java.io.ByteArrayOutputStream
import java.io.BufferedReader
import java.io.PrintStream
import java.io.StringReader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import org.eclipse.jgit.api.Git
import org.ftckb.model.ModelMessage
import org.ftckb.model.ModelProvider
import org.ftckb.model.ModelRequest
import org.ftckb.model.ModelResponse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ServeAcceptanceTest {
    private val mapper=com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
    private val client=HttpClient.newHttpClient()

    @Test
    fun `serve help prints usage without launching`() {
        val output=ByteArrayOutputStream()
        val code=runCli(listOf("serve","--help"),PrintStream(output),StringReader("").buffered())
        assertEquals(0,code)
        assertTrue(output.toString().startsWith("usage: knowledge-cli serve"))
    }

    @Test
    fun `serve parse failures exit sixty four`() {
        val cases=mapOf(
            listOf("serve","--knowledge","k","--team","20827","--season","2025-2026","--provider","fake","--extra","x") to
                "unknown serve option: --extra\n",
            listOf("serve","--knowledge","k","--team","20827","--season","2025-2026") to
                "missing --provider\n",
            listOf("serve","--knowledge","k","--team","20827","--season","2025-2026","--provider","fake","--provider","other") to
                "duplicate serve option: --provider\n",
            listOf("serve","--knowledge","k","--team","20827","--season","2025-2026","--provider","fake","--port","99999") to
                "invalid value for --port: expected 0-65535\n",
            listOf("serve","--knowledge","k","--team","20827","--season","2025-2026","--provider","fake","--port","abc") to
                "invalid value for --port: expected 0-65535\n",
            listOf("serve","--knowledge","k","--team","team-x","--season","2025-2026","--provider","fake") to
                "invalid value for --team: expected digits only\n",
            listOf("serve","--knowledge","k","--team","20827","--season","2025-26","--provider","fake") to
                "invalid value for --season: expected YYYY-YYYY\n"
        )
        cases.forEach { (args,expected) ->
            val output=ByteArrayOutputStream()
            val code=runCli(args,PrintStream(output),StringReader("").buffered(),serveCommand=ServeRunner { _,_ -> error("must not launch") })
            assertEquals(64,code,"args=$args")
            assertEquals(expected,output.toString(),"args=$args")
        }
    }

    @Test
    fun `serve rejects startup failures before binding`(@TempDir root:Path) {
        val output=ByteArrayOutputStream()
        val code=runCli(
            listOf("serve","--knowledge",root.resolve("missing").toString(),"--team","20827",
                "--season","2025-2026","--provider","fake","--config",root.resolve("missing.yaml").toString(),
                "--no-browser"),
            PrintStream(output),StringReader("").buffered(),
            serveCommand=ServeCommand(environment={ "fixture-secret" },secretPrompt={ null })
        )
        assertEquals(2,code)
        assertTrue(output.toString().startsWith("error starting serve: "),output.toString())
    }

    @Test
    fun `Ask mode web session answers questions and exposes the full API`(@TempDir root:Path) {
        val repository=writeFtcRepository(root.resolve("repo"))
        val config=writeConfig(root.resolve("config.yaml"))
        val provider=ScriptedFakeProvider()
        val output=ByteArrayOutputStream()
        var opened:String?=null
        val serve=ServeCommand(
            environment={ "fixture-secret" },
            providerCreator={ _,_ -> provider },
            sessionsDirectory={ root },
            secretPrompt={ null },
            browserOpener={ opened=it }
        )
        val options=ServeOptions(
            repository,knowledgeRoot(),"20827","2025-2026","fake",config,0,false
        )
        val codeHolder=IntArray(1) { -1 }
        val thread=Thread { codeHolder[0]=serve.run(options,PrintStream(output)) }
        thread.start()
        val base=waitForUrl(thread,output)
        val origin=base.substringBefore("?token=").trimEnd('/')
        val token=tokenOf(base)

        // token required
        val unauthorized=client.send(
            HttpRequest.newBuilder(URI.create("$origin/api/status")).GET().build(),
            HttpResponse.BodyHandlers.ofString()
        )
        assertEquals(401,unauthorized.statusCode())

        val status=json(client.send(HttpRequest.newBuilder(URI.create("$origin/api/status?token=$token")).GET().build(),HttpResponse.BodyHandlers.ofString()))
        assertTrue(status["ok"].booleanValue())
        assertEquals("ask",status["mode"].asText())
        assertEquals("20827",status["team"].asText())
        assertEquals("2025-2026",status["season"].asText())
        assertEquals("fake",status["provider"].asText())
        assertTrue(status["apiKeySet"].booleanValue())

        val ask=json(post("$origin/api/ask?token=$token","""{"question":"为什么 SampleTeleOp 可能空指针？"}"""))
        assertTrue(ask["ok"].booleanValue(),ask.toString())
        assertEquals(2,ask["claims"].size())
        assertTrue(ask["claims"][0]["text"].asText().contains("SampleTeleOp"))
        assertEquals("CODE:C1",ask["claims"][0]["citations"][0].asText())

        val diff=json(post("$origin/api/diff?token=$token","{}"))
        assertTrue(diff["ok"].booleanValue())
        assertEquals("",diff["diff"].asText())

        val save=json(post("$origin/api/save?token=$token","{}"))
        assertTrue(save["ok"].booleanValue())
        assertTrue(Files.exists(Path.of(save["savedPath"].asText())))

        val configured=json(post("$origin/api/configure?token=$token","""{"team":"16093"}"""))
        assertTrue(configured["ok"].booleanValue(),configured.toString())
        assertEquals("16093",configured["team"].asText())

        val cleared=json(post("$origin/api/clear?token=$token","{}"))
        assertTrue(cleared["ok"].booleanValue())
        assertEquals("ask",cleared["mode"].asText())

        val shutdown=json(post("$origin/api/shutdown?token=$token","{}"))
        assertTrue(shutdown["ok"].booleanValue())
        thread.join(10_000)
        assertEquals(0,codeHolder[0])
        assertEquals(base,opened)
        val text=output.toString()
        assertTrue(text.contains("serve=ok"),text)
        assertTrue(text.contains("token="),text)
        assertFalse(text.contains("fixture-secret"),text)
    }

    @Test
    fun `Edit mode submission writes files and supports undo and discard`(@TempDir root:Path) {
        val repository=copyEditFixture(root.resolve("repo"))
        val git=initializeRepository(repository)
        val source=repository.resolve(VISION_PATH)
        val provider=EditFakeProvider(sha256(Files.readAllBytes(source)),VISION_PATH)
        val config=writeConfig(root.resolve("config.yaml"))
        val output=ByteArrayOutputStream()
        val serve=ServeCommand(
            environment={ "fixture-secret" },
            providerCreator={ _,_ -> provider },
            sessionsDirectory={ root },
            secretPrompt={ null },
            browserOpener={ }
        )
        val options=ServeOptions(
            repository,knowledgeRoot(),"20827","2025-2026","fake",config,0,true
        )
        val codeHolder=IntArray(1) { -1 }
        val thread=Thread { codeHolder[0]=serve.run(options,PrintStream(output)) }
        thread.start()
        val base=waitForUrl(thread,output)
        val origin=base.substringBefore("?token=").trimEnd('/')
        val token=tokenOf(base)

        val mode=json(post("$origin/api/mode?token=$token","""{"mode":"edit"}"""))
        assertTrue(mode["ok"].booleanValue(),mode.toString())
        assertEquals("edit",mode["mode"].asText())

        val before=Files.readString(source)
        val submit=json(post("$origin/api/submit?token=$token","""{"message":"给 Vision.java 加结果有效性检查"}"""))
        assertTrue(submit["ok"].booleanValue(),submit.toString())
        assertEquals(setOf(VISION_PATH),submit["changedPaths"].map { it.asText() }.toSet())
        assertTrue(submit["diff"].asText().contains("--- a/$VISION_PATH"),submit["diff"].asText())
        assertTrue(Files.readString(source).contains("if(result!=null && result.isValid()) consume(result);"))
        assertFalse(Files.readString(source)==before)

        val statusAfter=json(client.send(HttpRequest.newBuilder(URI.create("$origin/api/status?token=$token")).GET().build(),HttpResponse.BodyHandlers.ofString()))
        assertTrue(statusAfter["hasChanges"].booleanValue())

        val undo=json(post("$origin/api/undo?token=$token","{}"))
        assertTrue(undo["ok"].booleanValue(),undo.toString())
        assertTrue(undo["succeeded"].booleanValue())
        assertEquals(before,Files.readString(source))

        val shutdown=json(post("$origin/api/shutdown?token=$token","{}"))
        assertTrue(shutdown["ok"].booleanValue())
        thread.join(10_000)
        assertEquals(0,codeHolder[0])
        git.close()
    }

    private fun json(response:HttpResponse<String>)=mapper.readTree(response.body())

    private fun post(url:String,body:String):HttpResponse<String> {
        val request=HttpRequest.newBuilder(URI.create(url))
            .header("Content-Type","application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        return client.send(request,HttpResponse.BodyHandlers.ofString())
    }

    private fun waitForUrl(thread:Thread,output:ByteArrayOutputStream):String {
        val deadline=System.currentTimeMillis()+10_000
        while (System.currentTimeMillis()<deadline) {
            val line=output.toString().lineSequence().firstOrNull { it.startsWith("url=") }
            if (line!=null) return line.removePrefix("url=")
            if (!thread.isAlive) break
            Thread.sleep(20)
        }
        error("server did not print url; output=${output}")
    }

    private fun tokenOf(base:String):String {
        val token=base.substringAfter("?token=","")
        assertTrue(token.isNotBlank(),"token missing from $base")
        return token
    }

    private fun writeFtcRepository(root:Path):Path {
        val teamCode=root.resolve("TeamCode")
        val source=teamCode.resolve("src/main/java/example")
        Files.createDirectories(source)
        Files.writeString(root.resolve("settings.gradle"),"include ':TeamCode'\n")
        Files.writeString(
            teamCode.resolve("build.gradle"),
            "dependencies { implementation 'org.firstinspires.ftc:RobotCore:10.3.0' }\n"
        )
        Files.writeString(source.resolve("SampleTeleOp.java"),"@TeleOp public class SampleTeleOp {}\n")
        return root
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

    private fun knowledgeRoot():Path=Path.of("..","..","knowledge").normalize()

    private fun copyEditFixture(destination:Path):Path {
        val source=Path.of("..","..","fixtures","agent","edit-repo").normalize()
        Files.walk(source).use { paths ->
            paths.forEach { path ->
                val target=destination.resolve(source.relativize(path).toString())
                if (Files.isDirectory(path)) Files.createDirectories(target)
                else Files.copy(path,target,StandardCopyOption.COPY_ATTRIBUTES)
            }
        }
        return destination
    }

    private fun initializeRepository(repository:Path):Git {
        val git=Git.init().setDirectory(repository.toFile()).setInitialBranch("team-work").call()
        git.add().addFilepattern(".").call()
        git.commit().setMessage("fixture baseline").call()
        return git
    }

    private fun sha256(bytes:ByteArray):String=MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private class ScriptedFakeProvider:ModelProvider {
        override fun complete(request:ModelRequest):ModelResponse {
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
                          "text":"SampleTeleOp may dereference motor before initialization in TeamCode/src/main/java/example/SampleTeleOp.java.",
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

    private class EditFakeProvider(
        private val expectedSha:String,
        private val path:String
    ):ModelProvider {
        override fun complete(request:ModelRequest):ModelResponse {
            val system=request.messages.first().content
            return when {
                system.startsWith("Return exactly one JSON object") -> ModelResponse("""
                    {
                      "concepts":["Vision"],
                      "symbols":["Vision"],
                      "pathGlobs":["$path"],
                      "ruleTopics":[],
                      "guideTopics":[]
                    }
                """.trimIndent())
                system.startsWith("Return exactly one JSON edit plan") -> ModelResponse("""
                    {
                      "summary":"Guard the vision result.",
                      "operations":[{
                        "kind":"replace",
                        "path":"$path",
                        "expectedSha256":"$expectedSha",
                        "oldText":"consume(result);",
                        "newText":"if(result!=null && result.isValid()) consume(result);",
                        "reason":"Avoid using an invalid result.",
                        "citations":["CODE:C1"]
                      }]
                    }
                """.trimIndent())
                else -> error("unexpected fake provider request")
            }
        }
    }

    companion object {
        const val VISION_PATH="TeamCode/src/main/java/example/Vision.java"
    }
}
