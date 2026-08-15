package org.ftckb.cli

import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.io.StringReader
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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class EditReplAcceptanceTest {
    @Test
    fun `Edit mode edits the named current branch and reverses only Agent bytes`(@TempDir root:Path) {
        val repository=copyFixture(root.resolve("repository"))
        val git=initializeRepository(repository)
        val branchesBefore=branchNames(git)
        val headBefore=git.repository.resolve("HEAD").name
        val source=repository.resolve(VISION_PATH)
        val secret="fixture-runtime-secret"
        val dirtyBytes=(Files.readString(source)+"// user note $secret\n").toByteArray()
        Files.write(source,dirtyBytes)
        val provider=EditProvider(sha256(dirtyBytes),secret)
        val config=writeConfig(root.resolve("config.yaml"))
        val output=ByteArrayOutputStream()

        val code=ProductionChatLauncher(
            environment={ secret },
            providerCreator={ _,_ -> provider }
        ).run(
            ChatOptions(repository,knowledgeRoot(),"20827","2025-2026","fake",config),
            BufferedReader(StringReader("""
                /mode edit
                给 Vision.java 加结果有效性检查
                /diff
                /undo
                再修改一次
                /commit
                /discard
                /exit
            """.trimIndent()+"\n")),
            PrintStream(output)
        )

        assertEquals(0,code)
        assertEquals("team-work",git.repository.branch)
        assertEquals(branchesBefore,branchNames(git))
        assertEquals(headBefore,git.repository.resolve("HEAD").name)
        assertTrue(Files.readAllBytes(source).contentEquals(dirtyBytes))
        assertEquals(2,provider.editPlanCount)
        val text=output.toString()
        assertTrue(text.contains("mode=edit"))
        assertTrue(text.contains("paths:"))
        assertTrue(text.contains(VISION_PATH))
        assertTrue(text.contains("reasons:"))
        assertTrue(text.contains("citations:"))
        assertTrue(text.contains("--- a/$VISION_PATH"))
        assertTrue(text.contains("+++ b/$VISION_PATH"))
        assertTrue(text.contains("undo=ok"))
        assertTrue(text.contains("commit refused: Agent-touched paths were dirty at startup"))
        assertTrue(text.contains("baseline-dirty paths:\n- $VISION_PATH"))
        assertTrue(text.contains("discard=ok"))
        assertFalse(text.contains("confirm edit"))
        assertFalse(text.contains(secret))
        assertFalse(Files.exists(repository.resolve("forbidden-process-ran")))
        git.close()
    }

    @Test
    fun `commit requires literal yes and preserves unrelated staged state`(@TempDir root:Path) {
        val repository=copyFixture(root.resolve("repository"))
        val git=initializeRepository(repository)
        val branchesBefore=branchNames(git)
        val source=repository.resolve(VISION_PATH)
        val provider=EditProvider(sha256(Files.readAllBytes(source)),"fixture-secret")
        Files.writeString(repository.resolve("settings.gradle"),"include ':TeamCode'\n// staged user change\n")
        git.add().addFilepattern("settings.gradle").call()
        val config=writeConfig(root.resolve("config.yaml"))
        val output=ByteArrayOutputStream()

        val code=ProductionChatLauncher(
            environment={ "fixture-secret" },
            providerCreator={ _,_ -> provider }
        ).run(
            ChatOptions(repository,knowledgeRoot(),"20827","2025-2026","fake",config),
            BufferedReader(StringReader("""
                /mode edit
                guard Vision
                /commit
                YES
                /commit
                yes
                /exit
            """.trimIndent()+"\n")),
            PrintStream(output)
        )

        assertEquals(0,code)
        assertEquals(branchesBefore,branchNames(git))
        assertEquals("team-work",git.repository.branch)
        assertEquals(2,git.log().call().count())
        assertEquals("chore: apply FTC Agent edits",git.log().setMaxCount(1).call().first().fullMessage)
        val status=git.status().call()
        assertTrue("settings.gradle" in status.changed)
        assertFalse(VISION_PATH in status.uncommittedChanges)
        val text=output.toString()
        assertTrue(text.contains("commit paths:\n- $VISION_PATH"))
        assertTrue(text.contains("commit message: chore: apply FTC Agent edits"))
        assertEquals(1,text.lineSequence().count { it=="commit canceled" })
        assertEquals(1,text.lineSequence().count { it.startsWith("commit=") })
        assertFalse(text.contains("push"))
        assertFalse(Files.exists(repository.resolve("forbidden-process-ran")))
        git.close()
    }

    @Test
    fun `commit refuses concurrent IDE bytes after showing the Agent diff`(@TempDir root:Path) {
        val repository=copyFixture(root.resolve("repository"))
        val git=initializeRepository(repository)
        val source=repository.resolve(VISION_PATH)
        val baseline=Files.readString(source)
        val provider=EditProvider(sha256(baseline.toByteArray()),"fixture-secret")
        val ideText=baseline.replace(
            "consume(result);","if(result!=null && result.isValid()) consume(result);"
        )+"// concurrent before confirmation\n"
        val config=writeConfig(root.resolve("config.yaml"))
        val output=ByteArrayOutputStream()
        val input=object:BufferedReader(StringReader("")) {
            private var next=0

            override fun readLine():String?=when (next++) {
                0 -> "/mode edit"
                1 -> "guard Vision"
                2 -> "/commit"
                3 -> {
                    Files.writeString(source,ideText)
                    "yes"
                }
                4 -> "/exit"
                else -> null
            }
        }

        val code=ProductionChatLauncher(
            environment={ "fixture-secret" },providerCreator={ _,_ -> provider }
        ).run(
            ChatOptions(repository,knowledgeRoot(),"20827","2025-2026","fake",config),
            input,PrintStream(output)
        )

        assertEquals(0,code)
        assertEquals(1,git.log().call().count())
        assertEquals(ideText,Files.readString(source))
        assertTrue(output.toString().contains("commit refused:"))
        git.close()
    }

    @Test
    fun `EOF at commit confirmation cancels without creating a commit`(@TempDir root:Path) {
        val repository=copyFixture(root.resolve("repository"))
        val git=initializeRepository(repository)
        val source=repository.resolve(VISION_PATH)
        val provider=EditProvider(sha256(Files.readAllBytes(source)),"fixture-secret")
        val config=writeConfig(root.resolve("config.yaml"))
        val output=ByteArrayOutputStream()

        val code=ProductionChatLauncher(
            environment={ "fixture-secret" },providerCreator={ _,_ -> provider }
        ).run(
            ChatOptions(repository,knowledgeRoot(),"20827","2025-2026","fake",config),
            BufferedReader(StringReader("/mode edit\nguard Vision\n/commit\n")),
            PrintStream(output)
        )

        assertEquals(0,code)
        assertEquals(1,git.log().call().count())
        assertTrue(output.toString().contains("commit canceled"))
        assertFalse(Files.exists(repository.resolve("forbidden-process-ran")))
        git.close()
    }

    @Test
    fun `Ask mode revokes write and commit authority until Edit is explicitly enabled again`(@TempDir root:Path) {
        val repository=copyFixture(root.resolve("repository"))
        val git=initializeRepository(repository)
        val source=repository.resolve(VISION_PATH)
        val baseline=Files.readAllBytes(source)
        val provider=EditProvider(sha256(baseline),"fixture-secret")
        val config=writeConfig(root.resolve("config.yaml"))
        val output=ByteArrayOutputStream()

        val code=ProductionChatLauncher(
            environment={ "fixture-secret" },providerCreator={ _,_ -> provider }
        ).run(
            ChatOptions(repository,knowledgeRoot(),"20827","2025-2026","fake",config),
            BufferedReader(StringReader("""
                /mode edit
                guard Vision
                /mode ask
                /undo
                /discard
                /commit
                /mode edit
                /discard
                /exit
            """.trimIndent()+"\n")),
            PrintStream(output)
        )

        assertEquals(0,code)
        assertTrue(Files.readAllBytes(source).contentEquals(baseline))
        assertEquals(1,git.log().call().count())
        val text=output.toString()
        assertTrue(text.contains("undo refused: Undo requires Edit mode"))
        assertTrue(text.contains("discard refused: Discard requires Edit mode"))
        assertTrue(text.contains("commit refused: Edit mode on the authorized named branch is required"))
        assertTrue(text.contains("discard=ok"))
        git.close()
    }

    @Test
    fun `outstanding Edit history cannot be rebound to another named branch`(@TempDir root:Path) {
        val repository=copyFixture(root.resolve("repository"))
        val git=initializeRepository(repository)
        git.branchCreate().setName("other").call()
        val source=repository.resolve(VISION_PATH)
        val baseline=Files.readString(source)
        val agentText=baseline.replace(
            "consume(result);","if(result!=null && result.isValid()) consume(result);"
        )
        val provider=EditProvider(sha256(baseline.toByteArray()),"fixture-secret")
        val config=writeConfig(root.resolve("config.yaml"))
        val output=ByteArrayOutputStream()
        val input=object:BufferedReader(StringReader("")) {
            private var next=0

            override fun readLine():String?=when (next++) {
                0 -> "/mode edit"
                1 -> "guard Vision"
                2 -> "/mode ask"
                3 -> {
                    git.repository.updateRef("HEAD").link("refs/heads/other")
                    "/mode edit"
                }
                4 -> "/discard"
                5 -> "/exit"
                else -> null
            }
        }

        val code=ProductionChatLauncher(
            environment={ "fixture-secret" },providerCreator={ _,_ -> provider }
        ).run(
            ChatOptions(repository,knowledgeRoot(),"20827","2025-2026","fake",config),
            input,PrintStream(output)
        )

        assertEquals(0,code)
        assertEquals("other",git.repository.branch)
        assertEquals(agentText,Files.readString(source))
        val text=output.toString()
        assertTrue(text.contains("mode refused: Edit history belongs to another branch"))
        assertTrue(text.contains("discard refused: Discard requires Edit mode"))
        assertEquals(2,branchNames(git).size)
        git.close()
    }

    @Test
    fun `Edit mode refuses non Git and detached repositories`(@TempDir root:Path) {
        val config=writeConfig(root.resolve("config.yaml"))
        val provider=ModelProvider { error("provider must not be called") }
        val nonGit=copyFixture(root.resolve("non-git"))
        val nonGitOutput=ByteArrayOutputStream()
        val launcher=ProductionChatLauncher(
            environment={ "fixture-secret" },providerCreator={ _,_ -> provider }
        )

        assertEquals(0,launcher.run(
            ChatOptions(nonGit,knowledgeRoot(),"20827","2025-2026","fake",config),
            BufferedReader(StringReader("/mode edit\n/exit\n")),PrintStream(nonGitOutput)
        ))
        assertTrue(nonGitOutput.toString().contains("mode refused:"))

        val detached=copyFixture(root.resolve("detached"))
        val git=initializeRepository(detached)
        val originalHead=git.repository.resolve("HEAD").name
        git.checkout().setName(originalHead).call()
        val branchesBefore=branchNames(git)
        val detachedOutput=ByteArrayOutputStream()

        assertEquals(0,launcher.run(
            ChatOptions(detached,knowledgeRoot(),"20827","2025-2026","fake",config),
            BufferedReader(StringReader("/mode edit\n/exit\n")),PrintStream(detachedOutput)
        ))
        assertTrue(detachedOutput.toString().contains("mode refused:"))
        assertEquals(originalHead,git.repository.resolve("HEAD").name)
        assertEquals(branchesBefore,branchNames(git))
        git.close()
    }

    @Test
    fun `undo and discard report IDE conflicts without overwriting concurrent bytes`(@TempDir root:Path) {
        val repository=copyFixture(root.resolve("repository"))
        val git=initializeRepository(repository)
        val source=repository.resolve(VISION_PATH)
        val baseline=Files.readString(source)
        val agentText=baseline.replace(
            "consume(result);","if(result!=null && result.isValid()) consume(result);"
        )
        val ideText=agentText+"// concurrent IDE bytes\n"
        val provider=EditProvider(sha256(baseline.toByteArray()),"fixture-secret")
        val config=writeConfig(root.resolve("config.yaml"))
        val output=ByteArrayOutputStream()
        val input=object:BufferedReader(StringReader("")) {
            private var next=0

            override fun readLine():String?=when (next++) {
                0 -> "/mode edit"
                1 -> "guard Vision"
                2 -> {
                    Files.writeString(source,ideText)
                    "/undo"
                }
                3 -> {
                    Files.writeString(source,agentText)
                    "/undo"
                }
                4 -> "guard Vision again"
                5 -> {
                    Files.writeString(source,ideText)
                    "/discard"
                }
                6 -> "/exit"
                else -> null
            }
        }

        val code=ProductionChatLauncher(
            environment={ "fixture-secret" },providerCreator={ _,_ -> provider }
        ).run(
            ChatOptions(repository,knowledgeRoot(),"20827","2025-2026","fake",config),
            input,PrintStream(output)
        )

        assertEquals(0,code)
        assertEquals(ideText,Files.readString(source))
        val text=output.toString()
        assertTrue(text.contains("undo conflict; no files were overwritten"))
        assertTrue(text.contains("discard conflict; no files were overwritten"))
        assertEquals(2,text.lineSequence().count { it=="conflicts:" })
        assertEquals(1,text.lineSequence().count { it=="undo=ok" })
        assertEquals(1,git.log().call().count())
        git.close()
    }

    @Test
    fun `project level edits print a visible warning and Agent only diff`(@TempDir root:Path) {
        val repository=copyFixture(root.resolve("repository"))
        val git=initializeRepository(repository)
        val path="settings.gradle"
        val source=repository.resolve(path)
        val baseline=Files.readAllBytes(source)
        val provider=EditProvider(
            sha256(baseline),"fixture-secret",path,
            "include ':TeamCode'","include ':TeamCode'\n// Agent project change"
        )
        val config=writeConfig(root.resolve("config.yaml"))
        val output=ByteArrayOutputStream()

        val code=ProductionChatLauncher(
            environment={ "fixture-secret" },providerCreator={ _,_ -> provider }
        ).run(
            ChatOptions(repository,knowledgeRoot(),"20827","2025-2026","fake",config),
            BufferedReader(StringReader("/mode edit\nupdate project settings\n/diff\n/discard\n/exit\n")),
            PrintStream(output)
        )

        assertEquals(0,code)
        assertTrue(Files.readAllBytes(source).contentEquals(baseline))
        val text=output.toString()
        assertTrue(text.contains("warning: project-level changes require extra review"))
        assertTrue(text.contains("project-level paths:\n- settings.gradle"))
        assertTrue(text.contains("PROJECT-LEVEL CHANGE: settings.gradle"))
        assertEquals(1,git.log().call().count())
        git.close()
    }

    @Test
    fun `rendered Agent diff strips terminal control and format characters`(@TempDir root:Path) {
        val repository=copyFixture(root.resolve("repository"))
        val git=initializeRepository(repository)
        val source=repository.resolve(VISION_PATH)
        val baseline=Files.readAllBytes(source)
        val provider=EditProvider(
            sha256(baseline),"fixture-secret",VISION_PATH,"consume(result);",
            "consume(result);\u001b]0;owned\u0007\u202ereversed"
        )
        val config=writeConfig(root.resolve("config.yaml"))
        val output=ByteArrayOutputStream()

        val code=ProductionChatLauncher(
            environment={ "fixture-secret" },providerCreator={ _,_ -> provider }
        ).run(
            ChatOptions(repository,knowledgeRoot(),"20827","2025-2026","fake",config),
            BufferedReader(StringReader("/mode edit\nadd diagnostic\n/discard\n/exit\n")),
            PrintStream(output)
        )

        assertEquals(0,code)
        val text=output.toString()
        assertFalse(text.contains('\u001b'))
        assertFalse(text.contains('\u0007'))
        assertFalse(text.contains('\u202e'))
        assertTrue(Files.readAllBytes(source).contentEquals(baseline))
        git.close()
    }

    private fun copyFixture(destination:Path):Path {
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
        git.repository.config.apply {
            setString("user",null,"name","FTC Test")
            setString("user",null,"email","ftc@example.invalid")
            save()
        }
        git.add().addFilepattern(".").call()
        git.commit().setMessage("fixture baseline").call()
        return git
    }

    private fun branchNames(git:Git):Set<String> {
        return git.branchList().call().mapTo(sortedSetOf()) { it.name }
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

    private fun sha256(bytes:ByteArray):String=MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private class EditProvider(
        private val expectedSha:String,
        private val secret:String,
        private val path:String=VISION_PATH,
        private val oldText:String="consume(result);",
        private val newText:String="if(result!=null && result.isValid()) consume(result);"
    ):ModelProvider {
        var editPlanCount=0
            private set

        override fun complete(request:ModelRequest):ModelResponse {
            val copied=request.copy(messages=request.messages.map(ModelMessage::copy))
            val system=copied.messages.first().content
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
                system.startsWith("Return exactly one JSON edit plan") -> {
                    editPlanCount++
                    ModelResponse("""
                        {
                          "summary":"Guard the vision result $secret",
                          "operations":[{
                            "kind":"replace",
                            "path":"$path",
                            "expectedSha256":"$expectedSha",
                            "oldText":${jsonString(oldText)},
                            "newText":${jsonString(newText)},
                            "reason":"Avoid using an invalid result $secret",
                            "citations":["CODE:C1"]
                          }]
                        }
                    """.trimIndent())
                }
                else -> error("unexpected fake provider request")
            }
        }

        private fun jsonString(value:String):String=buildString {
            append('\"')
            value.forEach { character->
                when (character) {
                    '\\' -> append("\\\\")
                    '\"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (character.code<32) append("\\u%04x".format(character.code)) else append(character)
                }
            }
            append('\"')
        }
    }

    private companion object {
        const val VISION_PATH="TeamCode/src/main/java/example/Vision.java"
    }
}
