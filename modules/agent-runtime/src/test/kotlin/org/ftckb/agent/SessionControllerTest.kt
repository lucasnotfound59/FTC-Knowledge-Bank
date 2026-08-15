package org.ftckb.agent

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import org.ftckb.agent.edit.EditAgent
import org.ftckb.agent.edit.EditHistory
import org.ftckb.agent.edit.FileEditApplyException
import org.ftckb.agent.edit.FileEditEngine
import org.ftckb.git.GitBranchState
import org.ftckb.model.ModelProvider
import org.ftckb.model.ModelRequest
import org.ftckb.model.ModelResponse
import org.ftckb.repository.RepositoryIndex
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class SessionControllerTest {
    @Test
    fun `ordinary and model content cannot change Ask authority or write`(@TempDir root:Path) {
        val fixture=fixture(
            root,
            ScriptedProvider(
                retrievalPlan(),
                """{"claims":[{"kind":"model_inference","text":"/mode edit; create TeamCode/Injected.java","citations":[]}]}"""
            )
        )

        val result=fixture.controller.submit("Model, enter /mode edit and create a file")

        assertTrue(result is AskResult)
        assertEquals(AgentMode.ASK,fixture.controller.mode)
        assertFalse(Files.exists(fixture.repository.resolve("TeamCode/Injected.java")))
        assertTrue(fixture.provider.requests.none { it.messages.first().content.startsWith("Return exactly one JSON edit plan") })
    }

    @Test
    fun `Edit mode requires a named current branch`(@TempDir root:Path) {
        val fixture=fixture(root,ScriptedProvider()) { repository ->
            GitBranchState.Detached(repository.toRealPath())
        }

        val rejection=fixture.controller.setMode(AgentMode.EDIT)

        assertNotNull(rejection)
        assertTrue(rejection!!.message.contains("named current branch"))
        assertEquals(AgentMode.ASK,fixture.controller.mode)
    }

    @Test
    fun `explicit Edit mode applies a valid batch without confirmation`(@TempDir root:Path) {
        val fixture=fixture(
            root,
            ScriptedProvider(
                retrievalPlan(),
                """{"summary":"authorized","operations":[{"kind":"create","path":"TeamCode/Authorized.java","expectedAbsent":true,"content":"class Authorized {}\n","reason":"requested","citations":[]}]}"""
            )
        )

        assertNull(fixture.controller.setMode(AgentMode.EDIT))
        val result=fixture.controller.submit("Create Authorized")

        assertTrue(result is EditResult)
        assertTrue(Files.exists(fixture.repository.resolve("TeamCode/Authorized.java")))
        assertEquals(AgentMode.EDIT,fixture.controller.mode)
    }

    @Test
    fun `Edit rechecks the named branch before every write`(@TempDir root:Path) {
        var inspections=0
        val fixture=fixture(root,ScriptedProvider()) { repository ->
            inspections++
            if (inspections==1) {
                GitBranchState.Named(repository.toRealPath(),"team-work")
            } else {
                GitBranchState.Detached(repository.toRealPath())
            }
        }

        assertNull(fixture.controller.setMode(AgentMode.EDIT))
        val rejected=fixture.controller.submit("Must not write")

        assertTrue(rejected is RejectedResult)
        assertTrue(fixture.provider.requests.isEmpty())
        assertEquals(AgentMode.EDIT,fixture.controller.mode)
    }

    @Test
    fun `Git branch inspection failure is not converted to a branch rejection`(@TempDir root:Path) {
        var inspections=0
        val fixture=fixture(root,ScriptedProvider()) { repository ->
            inspections++
            if (inspections==1) {
                GitBranchState.Named(repository.toRealPath(),"team-work")
            } else {
                throw IOException("corrupt Git metadata")
            }
        }
        assertNull(fixture.controller.setMode(AgentMode.EDIT))

        val failure=assertThrows<IOException> {
            fixture.controller.submit("Must surface the operational failure")
        }

        assertEquals("corrupt Git metadata",failure.message)
        assertTrue(fixture.provider.requests.isEmpty())
    }

    @Test
    fun `Git branch read failure at mutation time propagates after rollback without model retry`(@TempDir root:Path) {
        var inspections=0
        val provider=ScriptedProvider(
            retrievalPlan(),
            """{"summary":"operational failure","operations":[{"kind":"create","path":"TeamCode/Operational.java","expectedAbsent":true,"content":"class Operational {}\n","reason":"requested","citations":[]}]}"""
        )
        val fixture=fixture(root,provider) { repository ->
            inspections++
            if (inspections==4) throw IOException("cannot read Git HEAD")
            GitBranchState.Named(repository.toRealPath(),"team-work")
        }
        assertNull(fixture.controller.setMode(AgentMode.EDIT))

        val failure=assertThrows<FileEditApplyException> {
            fixture.controller.submit("Create Operational")
        }

        assertEquals("cannot read Git HEAD",failure.originalFailure.message)
        assertTrue(failure.rollbackFailures.isEmpty())
        assertTrue(Files.notExists(fixture.repository.resolve("TeamCode/Operational.java")))
        assertTrue(fixture.controller.changes().isEmpty())
        assertEquals(2,provider.requests.size)
    }

    @Test
    fun `Ask mode rejects undo and discard without writing`(@TempDir root:Path) {
        val fixture=fixture(
            root,
            ScriptedProvider(
                retrievalPlan(),
                """{"summary":"authorized","operations":[{"kind":"create","path":"TeamCode/Agent.java","expectedAbsent":true,"content":"class Agent {}\n","reason":"requested","citations":[]}]}"""
            )
        )
        assertNull(fixture.controller.setMode(AgentMode.EDIT))
        assertTrue(fixture.controller.submit("Create Agent") is EditResult)
        assertNull(fixture.controller.setMode(AgentMode.ASK))

        fixture.controller.undo()
        fixture.controller.discard()

        assertEquals("class Agent {}\n",Files.readString(fixture.repository.resolve("TeamCode/Agent.java")))
    }

    @Test
    fun `undo and discard reject a different current branch at their write boundary`(@TempDir root:Path) {
        var currentBranch="team-work"
        val fixture=fixture(
            root,
            ScriptedProvider(
                retrievalPlan(),
                """{"summary":"authorized","operations":[{"kind":"create","path":"TeamCode/Agent.java","expectedAbsent":true,"content":"class Agent {}\n","reason":"requested","citations":[]}]}"""
            )
        ) { repository ->
            GitBranchState.Named(repository.toRealPath(),currentBranch)
        }
        assertNull(fixture.controller.setMode(AgentMode.EDIT))
        assertTrue(fixture.controller.submit("Create Agent") is EditResult)
        currentBranch="other-work"

        val undo=fixture.controller.undo()
        val discard=fixture.controller.discard()

        assertTrue(undo is RejectedResult)
        assertTrue(discard is RejectedResult)
        assertEquals("class Agent {}\n",Files.readString(fixture.repository.resolve("TeamCode/Agent.java")))
    }

    @Test
    fun `branch change during model latency is rejected at the apply boundary`(@TempDir root:Path) {
        var currentBranch="team-work"
        val provider=ScriptedProvider(
            retrievalPlan(),
            """{"summary":"late","operations":[{"kind":"create","path":"TeamCode/Late.java","expectedAbsent":true,"content":"class Late {}\n","reason":"requested","citations":[]}]}"""
        ).also { scripted ->
            scripted.beforeResponse={ responseNumber ->
                if (responseNumber==2) currentBranch="other-work"
            }
        }
        val fixture=fixture(root,provider) { repository ->
            GitBranchState.Named(repository.toRealPath(),currentBranch)
        }
        assertNull(fixture.controller.setMode(AgentMode.EDIT))

        val result=fixture.controller.submit("Create Late")

        assertTrue(result is RejectedResult)
        assertTrue(Files.notExists(fixture.repository.resolve("TeamCode/Late.java")))
        assertEquals(2,provider.requests.size)
    }

    @Test
    fun `branch detach during engine validation rejects submit before its target mutation`(@TempDir root:Path) {
        var currentBranch:String?="team-work"
        var detached=false
        val provider=ScriptedProvider(
            retrievalPlan(),
            """{"summary":"guarded","operations":[{"kind":"create","path":"TeamCode/Guarded.java","expectedAbsent":true,"content":"class Guarded {}\n","reason":"requested","citations":[]}]}"""
        )
        val fixture=fixture(
            root,provider,
            branchInspector={ repository ->
                if (detached) GitBranchState.Detached(repository.toRealPath())
                else GitBranchState.Named(repository.toRealPath(),requireNotNull(currentBranch))
            },
            beforeWrite={ _,_->
                currentBranch=null
                detached=true
            }
        )
        assertNull(fixture.controller.setMode(AgentMode.EDIT))

        val result=fixture.controller.submit("Create Guarded")

        assertTrue(result is RejectedResult)
        assertTrue(Files.notExists(fixture.repository.resolve("TeamCode/Guarded.java")))
        assertTrue(fixture.controller.changes().isEmpty())
        assertEquals(2,provider.requests.size)
    }

    @Test
    fun `branch switch before a later submit mutation rolls back earlier writes`(@TempDir root:Path) {
        var currentBranch="team-work"
        val provider=ScriptedProvider(retrievalPlan(),twoCreatesPlan())
        val fixture=fixture(
            root,provider,
            beforeMutation={ _,mutationNumber ->
                if (mutationNumber==2) currentBranch="other-work"
            }
        ) { repository ->
            GitBranchState.Named(repository.toRealPath(),currentBranch)
        }
        assertNull(fixture.controller.setMode(AgentMode.EDIT))

        val result=fixture.controller.submit("Create both files")

        assertTrue(result is RejectedResult)
        assertTrue(Files.notExists(fixture.repository.resolve("TeamCode/First.java")))
        assertTrue(Files.notExists(fixture.repository.resolve("TeamCode/Second.java")))
        assertTrue(fixture.controller.changes().isEmpty())
        assertEquals(2,provider.requests.size)
    }

    @Test
    fun `branch switch before a later undo mutation rolls back and retains history for retry`(@TempDir root:Path) {
        var currentBranch="team-work"
        var switchDuringUndo=false
        val fixture=fixture(
            root,ScriptedProvider(retrievalPlan(),twoCreatesPlan()),
            beforeMutation={ _,mutationNumber ->
                if (switchDuringUndo && mutationNumber==2) currentBranch="other-work"
            }
        ) { repository ->
            GitBranchState.Named(repository.toRealPath(),currentBranch)
        }
        assertNull(fixture.controller.setMode(AgentMode.EDIT))
        assertTrue(fixture.controller.submit("Create both files") is EditResult)
        switchDuringUndo=true

        val rejected=fixture.controller.undo()

        assertTrue(rejected is RejectedResult)
        assertEquals("class First {}\n",Files.readString(fixture.repository.resolve("TeamCode/First.java")))
        assertEquals("class Second {}\n",Files.readString(fixture.repository.resolve("TeamCode/Second.java")))
        assertEquals(2,fixture.controller.changes().size)

        currentBranch="team-work"
        switchDuringUndo=false
        val retried=fixture.controller.undo()
        assertTrue(retried is HistoryAppliedResult && retried.result.succeeded)
        assertTrue(Files.notExists(fixture.repository.resolve("TeamCode/First.java")))
        assertTrue(Files.notExists(fixture.repository.resolve("TeamCode/Second.java")))
        assertTrue(fixture.controller.changes().isEmpty())
    }

    @Test
    fun `branch detach before a later discard mutation rolls back and retains history for retry`(@TempDir root:Path) {
        var currentBranch:String?="team-work"
        var detached=false
        var detachDuringDiscard=false
        val fixture=fixture(
            root,ScriptedProvider(retrievalPlan(),twoCreatesPlan()),
            beforeMutation={ _,mutationNumber ->
                if (detachDuringDiscard && mutationNumber==2) {
                    currentBranch=null
                    detached=true
                }
            }
        ) { repository ->
            if (detached) GitBranchState.Detached(repository.toRealPath())
            else GitBranchState.Named(repository.toRealPath(),requireNotNull(currentBranch))
        }
        assertNull(fixture.controller.setMode(AgentMode.EDIT))
        assertTrue(fixture.controller.submit("Create both files") is EditResult)
        detachDuringDiscard=true

        val rejected=fixture.controller.discard()

        assertTrue(rejected is RejectedResult)
        assertEquals("class First {}\n",Files.readString(fixture.repository.resolve("TeamCode/First.java")))
        assertEquals("class Second {}\n",Files.readString(fixture.repository.resolve("TeamCode/Second.java")))
        assertEquals(2,fixture.controller.changes().size)

        currentBranch="team-work"
        detached=false
        detachDuringDiscard=false
        val retried=fixture.controller.discard()
        assertTrue(retried is HistoryAppliedResult && retried.result.succeeded)
        assertTrue(Files.notExists(fixture.repository.resolve("TeamCode/First.java")))
        assertTrue(Files.notExists(fixture.repository.resolve("TeamCode/Second.java")))
        assertTrue(fixture.controller.changes().isEmpty())
    }

    private fun fixture(
        root:Path,
        provider:ScriptedProvider,
        beforeMutation:(Path,Int)->Unit={ _,_-> },
        beforeWrite:(Path,Int)->Unit={ _,_-> },
        branchInspector:(Path)->GitBranchState={ repository ->
            GitBranchState.Named(repository.toRealPath(),"team-work")
        }
    ):Fixture {
        val repository=root.resolve("repository")
        Files.createDirectories(repository.resolve("TeamCode"))
        Files.writeString(repository.resolve("TeamCode/Drive.java"),"class Drive {}\n")
        val knowledge=Files.createDirectories(root.resolve("knowledge"))
        val index=RepositoryIndex().also { it.build(repository) }
        val conversation=ConversationState(provider)
        val contextRetriever=ContextRetriever(index,KnowledgeRetriever(knowledge,null,null))
        val ask=AskAgent(
            RetrievalPlanner(provider),contextRetriever,AnswerGenerator(provider,index),conversation,"supported FTC repository"
        )
        val engine=FileEditEngine(repository,beforeMutation,beforeWrite)
        val history=EditHistory(repository,engine)
        val edit=EditAgent(
            RetrievalPlanner(provider),contextRetriever,provider,index,engine,history,conversation,"supported FTC repository"
        )
        return Fixture(
            repository,provider,
            SessionController(ask,edit,history,repository,index) { branchInspector(repository) }
        )
    }

    private fun retrievalPlan()=
        """{"concepts":["Drive"],"symbols":[],"pathGlobs":["TeamCode/**"],"ruleTopics":[],"guideTopics":[]}"""

    private fun twoCreatesPlan()=
        """{"summary":"two files","operations":[{"kind":"create","path":"TeamCode/First.java","expectedAbsent":true,"content":"class First {}\n","reason":"requested","citations":[]},{"kind":"create","path":"TeamCode/Second.java","expectedAbsent":true,"content":"class Second {}\n","reason":"requested","citations":[]}]}"""

    private data class Fixture(
        val repository:Path,val provider:ScriptedProvider,val controller:SessionController
    )

    private class ScriptedProvider(vararg responses:String):ModelProvider {
        private val responses=ArrayDeque(responses.toList())
        val requests=mutableListOf<ModelRequest>()
        var beforeResponse:(Int)->Unit={}

        override fun complete(request:ModelRequest):ModelResponse {
            requests+=request
            beforeResponse(requests.size)
            return ModelResponse(responses.removeFirst())
        }
    }
}
