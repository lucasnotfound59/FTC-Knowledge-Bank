package org.ftckb.agent.edit

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import org.ftckb.agent.ContextRetriever
import org.ftckb.agent.ConversationState
import org.ftckb.agent.KnowledgeRetriever
import org.ftckb.agent.RetrievalPlanner
import org.ftckb.agent.RuntimeStageEvidence
import org.ftckb.agent.RuntimeStageObserver
import org.ftckb.agent.RuntimeStageStatus
import org.ftckb.model.ModelProvider
import org.ftckb.model.ModelRequest
import org.ftckb.model.ModelResponse
import org.ftckb.repository.LocalQuery
import org.ftckb.repository.RepositoryIndex
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class EditAgentTest {
    @Test
    fun `valid edit records history refreshes index and persists only a compact summary`(@TempDir root:Path) {
        val repository=repository(root)
        val provider=ScriptedProvider(
            retrievalPlan(),
            """{"summary":"Add safety marker","operations":[{"kind":"create","path":"TeamCode/Safety.java","expectedAbsent":true,"content":"class Safety { String marker=\"fresh-index-text\"; }\n","reason":"Add the requested safety marker.","citations":["CODE:C1"]}]}"""
        )
        val index=RepositoryIndex().also { it.build(repository) }
        val engine=FileEditEngine(repository)
        val history=EditHistory(repository,engine)
        val conversation=ConversationState(provider)
        val agent=EditAgent(
            RetrievalPlanner(provider),
            ContextRetriever(index,KnowledgeRetriever(knowledge(root),null,null,ruleProfiles=emptySet())),
            provider,index,engine,history,conversation,"supported FTC repository"
        )

        val report=agent.edit("Add a safety marker")

        assertEquals(setOf("TeamCode/Safety.java"),report.changedPaths)
        assertTrue(report.diff.contains("+++ b/TeamCode/Safety.java"))
        assertEquals(
            listOf("TeamCode/Safety.java"),
            index.search(LocalQuery(setOf("fresh-index-text")),4).map { it.path }
        )
        assertEquals(setOf("TeamCode/Safety.java"),history.changes().mapTo(linkedSetOf()) { it.path })
        val savedTurn=conversation.context().recentTurns.single()
        assertTrue(savedTurn.answer.claims.single().text.contains("Add safety marker"))
        assertFalse(savedTurn.answer.claims.single().text.contains("fresh-index-text"))
        assertFalse(savedTurn.answer.claims.single().text.contains("Add the requested safety marker"))
        assertEquals(2,provider.requests.size)
    }

    @Test
    fun `invalid edit JSON receives exactly one bounded repair`(@TempDir root:Path) {
        val repository=repository(root)
        val provider=ScriptedProvider(
            retrievalPlan(),"not json",
            """{"summary":"repaired","operations":[{"kind":"create","path":"TeamCode/Repaired.java","expectedAbsent":true,"content":"class Repaired {}\n","reason":"repair","citations":["CODE:C1"]}]}"""
        )
        val agent=agent(root,repository,provider)

        val report=agent.edit("Create repaired file")

        assertEquals(setOf("TeamCode/Repaired.java"),report.changedPaths)
        assertEquals(3,provider.requests.size)
        val repair=provider.requests.last().messages.last().content.substringAfter("Repair the previous response: ")
        assertTrue(repair.isNotBlank())
        assertTrue(repair.length<=500)
    }

    @Test
    fun `invalid model plans emit bounded blocked stage evidence without writes`(@TempDir root:Path) {
        val repository=repository(root)
        val provider=ScriptedProvider(retrievalPlan(),"private invalid plan one","private invalid plan two")
        val stages=mutableListOf<RuntimeStageEvidence>()
        val agent=agent(root,repository,provider,observer=RuntimeStageObserver(stages::add))

        val failure=assertThrows(EditValidationException::class.java) { agent.edit("Create requested file") }

        assertEquals("model_failure",failure.category)
        assertEquals(List(2) {
            RuntimeStageEvidence("model",RuntimeStageStatus.BLOCKED,"model_failure","Edit plan validation failed")
        },stages)
        assertEquals(3,provider.requests.size)
        Files.list(repository.resolve("TeamCode")).use {
            assertEquals(listOf("Drive.java"),it.map { path->path.fileName.toString() }.toList())
        }
    }

    @Test
    fun `invalid citations receive one repair then reject with zero writes`(@TempDir root:Path) {
        val repository=repository(root)
        val invalid="""{"summary":"invalid","operations":[{"kind":"create","path":"TeamCode/Unsafe.java","expectedAbsent":true,"content":"class Unsafe {}\n","reason":"unsupported","citations":["RULE:R99"]}]}"""
        val provider=ScriptedProvider(retrievalPlan(),invalid,invalid)
        val stages=mutableListOf<RuntimeStageEvidence>()
        val agent=agent(root,repository,provider,observer=RuntimeStageObserver(stages::add))

        val failure=assertThrows(EditValidationException::class.java) { agent.edit("Create unsupported file") }
        assertEquals("citation_guard",failure.category)
        assertEquals(listOf(RuntimeStageStatus.BLOCKED,RuntimeStageStatus.BLOCKED),stages.map { it.status })
        assertTrue(stages.all { it.name=="citation_guard" })

        assertTrue(Files.notExists(repository.resolve("TeamCode/Unsafe.java")))
        assertEquals(3,provider.requests.size)
        assertTrue(provider.requests.last().messages.last().content.contains("Repair the previous response"))
    }

    @Test
    fun `preview failures receive one repair then reject protected paths with zero writes`(@TempDir root:Path) {
        val repository=repository(root)
        val invalid="""{"summary":"invalid","operations":[{"kind":"create","path":".env","expectedAbsent":true,"content":"secret=value\n","reason":"unsafe","citations":["CODE:C1"]}]}"""
        val provider=ScriptedProvider(retrievalPlan(),invalid,invalid)
        val stages=mutableListOf<RuntimeStageEvidence>()
        val agent=agent(root,repository,provider,observer=RuntimeStageObserver(stages::add))

        val failure=assertThrows(EditValidationException::class.java) { agent.edit("Create protected file") }
        assertEquals("path_guard",failure.category)
        assertEquals(2,stages.count { it.name=="citation_guard" && it.status==RuntimeStageStatus.PASSED })
        assertEquals(2,stages.count { it.name=="path_guard" && it.status==RuntimeStageStatus.BLOCKED })
        assertTrue(stages.none { it.name=="write" })

        assertTrue(Files.notExists(repository.resolve(".env")))
        assertEquals(3,provider.requests.size)
        assertTrue(provider.requests.last().messages.last().content.contains("edit path is protected"))
    }

    @Test
    fun `net zero previews receive exactly one repair then reject without history`(@TempDir root:Path) {
        val repository=repository(root)
        val unchanged="""{"summary":"unchanged","operations":[{"kind":"replace","path":"TeamCode/Drive.java","expectedSha256":"${sha256("class Drive {}\n")}","oldText":"class Drive {}","newText":"class Drive {}","reason":"No effective change.","citations":["CODE:C1"]}]}"""
        val provider=ScriptedProvider(retrievalPlan(),unchanged,unchanged)
        val index=RepositoryIndex().also { it.build(repository) }
        val engine=FileEditEngine(repository)
        val history=EditHistory(repository,engine)
        val agent=EditAgent(
            RetrievalPlanner(provider),
            ContextRetriever(index,KnowledgeRetriever(knowledge(root),null,null,ruleProfiles=emptySet())),
            provider,index,engine,history,ConversationState(provider),"supported FTC repository"
        )

        val failure=assertThrows(EditValidationException::class.java) { agent.edit("Make no effective change") }
        assertEquals("model_failure",failure.category)

        assertEquals("class Drive {}\n",Files.readString(repository.resolve("TeamCode/Drive.java")))
        assertTrue(history.changes().isEmpty())
        assertEquals(3,provider.requests.size)
        assertTrue(provider.requests.last().messages.last().content.contains("edit plan produces no file changes"))
    }

    @Test
    fun `apply failure is never sent back to the model for retry`(@TempDir root:Path) {
        val repository=repository(root)
        val provider=ScriptedProvider(
            retrievalPlan(),
            """{"summary":"write once","operations":[{"kind":"create","path":"TeamCode/Once.java","expectedAbsent":true,"content":"class Once {}\n","reason":"once","citations":["CODE:C1"]}]}"""
        )
        val index=RepositoryIndex().also { it.build(repository) }
        val engine=FileEditEngine(repository,beforeMutation={ _,_->throw IOException("synthetic write failure") })
        val history=EditHistory(repository,engine)
        val agent=EditAgent(
            RetrievalPlanner(provider),
            ContextRetriever(index,KnowledgeRetriever(knowledge(root),null,null,ruleProfiles=emptySet())),
            provider,index,engine,history,ConversationState(provider),"supported FTC repository"
        )

        assertThrows(FileEditApplyException::class.java) { agent.edit("Write once") }

        assertEquals(2,provider.requests.size)
        assertTrue(Files.notExists(repository.resolve("TeamCode/Once.java")))
        assertTrue(history.changes().isEmpty())
    }

    @Test
    fun `conflicting rules cannot authorize an edit`(@TempDir root:Path) {
        val repository=repository(root)
        val knowledge=Files.createDirectories(root.resolve("conflicting-knowledge/official"))
        Files.writeString(knowledge.resolve("rules.yaml"),conflictingRules())
        val invalid="""{"summary":"conflict","operations":[{"kind":"create","path":"TeamCode/Conflict.java","expectedAbsent":true,"content":"class Conflict {}\n","reason":"claimed conflict rule","citations":["RULE:R1"]}]}"""
        val provider=ScriptedProvider(
            """{"concepts":[],"symbols":[],"pathGlobs":[],"ruleTopics":["conflict-topic"],"guideTopics":[]}""",
            invalid,invalid
        )
        val index=RepositoryIndex().also { it.build(repository) }
        val engine=FileEditEngine(repository)
        val failure=assertThrows(IllegalArgumentException::class.java) {
            EditAgent(
                RetrievalPlanner(provider),
                ContextRetriever(index,KnowledgeRetriever(knowledge.parent,null,null,ruleProfiles=emptySet())),
                provider,index,engine,EditHistory(repository,engine),ConversationState(provider),"supported FTC repository"
            )
        }
        assertTrue(failure.message!!.contains("conflict-topic"))

        assertTrue(Files.notExists(repository.resolve("TeamCode/Conflict.java")))
        assertEquals(0,provider.requests.size)
    }

    @Test
    fun `history precondition failure cannot occur after a successful write`(@TempDir root:Path) {
        val repository=repository(root)
        val file=repository.resolve("TeamCode/Drive.java")
        val index=RepositoryIndex().also { it.build(repository) }
        val engine=FileEditEngine(repository)
        val history=EditHistory(repository,engine)
        history.applyAndRecord(engine.preview(EditPlan("first",listOf(
            ReplaceText(
                "TeamCode/Drive.java",sha256("class Drive {}\n"),
                "class Drive {}","class Drive { int agentOne; }","first",emptyList()
            )
        ))))
        Files.writeString(file,"class Drive { int ideChange; }\n")
        index.refresh(setOf("TeamCode/Drive.java"))
        val provider=ScriptedProvider(
            retrievalPlan(),
            """{"summary":"second","operations":[{"kind":"replace","path":"TeamCode/Drive.java","expectedSha256":"${sha256("class Drive { int ideChange; }\n")}","oldText":"int ideChange","newText":"int agentTwo","reason":"second","citations":["CODE:C1"]}]}"""
        )
        val agent=EditAgent(
            RetrievalPlanner(provider),
            ContextRetriever(index,KnowledgeRetriever(knowledge(root),null,null,ruleProfiles=emptySet())),
            provider,index,engine,history,ConversationState(provider),"supported FTC repository"
        )

        assertThrows(IllegalArgumentException::class.java) { agent.edit("Apply second batch") }

        assertEquals("class Drive { int ideChange; }\n",Files.readString(file))
        assertEquals("class Drive { int agentOne; }\n",history.changes().single().after)
        assertEquals(2,provider.requests.size)
    }

    @Test
    fun `blocked prewrite decision writes nothing and does not retry model`(@TempDir root:Path) {
        val repository=repository(root)
        val provider=ScriptedProvider(retrievalPlan(),createPlan())
        val notice=EditGateNotice("hard","rule.blocked","path-forbidden","TeamCode/Gated.java",null,"blocked path")
        var inspected=false
        val gate=object:EditPrewriteGate {
            override fun inspect(preview:ValidatedEditBatch):EditGateResult {
                inspected=true
                assertEquals("TeamCode/Gated.java",preview.changes.single().path)
                return EditGateResult(false,listOf(notice))
            }
            override fun verifyCurrent(preview:ValidatedEditBatch)=error("blocked preview must not be verified")
            override fun beginWrite(preview:ValidatedEditBatch):AutoCloseable=error("blocked preview must not acquire a lease")
        }
        val engine=FileEditEngine(repository)
        val history=EditHistory(repository,engine)
        val agent=agent(root,repository,provider,gate,engine,history)

        val failure=assertThrows(EditGateException::class.java) { agent.edit("Create gated") }

        assertTrue(inspected)
        assertEquals("blocked",failure.code)
        assertEquals(listOf(notice),failure.notices)
        assertTrue(Files.notExists(repository.resolve("TeamCode/Gated.java")))
        assertTrue(history.changes().isEmpty())
        assertEquals(2,provider.requests.size)
    }

    @Test
    fun `soft and dev notices survive successful edit under write lease`(@TempDir root:Path) {
        val repository=repository(root)
        val provider=ScriptedProvider(retrievalPlan(),createPlan())
        val notices=listOf(
            EditGateNotice("soft","rule.review",null,null,null,"review required"),
            EditGateNotice("dev_downgrade","rule.dev","regex-forbidden","TeamCode/Gated.java",1,"explicit dev")
        )
        var leaseOpen=false
        var verifications=0
        var inspected=0
        val gate=object:EditPrewriteGate {
            override fun inspect(preview:ValidatedEditBatch):EditGateResult {
                inspected++
                return EditGateResult(true,notices)
            }
            override fun beginWrite(preview:ValidatedEditBatch):AutoCloseable {
                leaseOpen=true
                return AutoCloseable { leaseOpen=false }
            }
            override fun verifyCurrent(preview:ValidatedEditBatch) {
                assertTrue(leaseOpen)
                verifications++
            }
        }
        val stages=mutableListOf<RuntimeStageEvidence>()
        val report=agent(root,repository,provider,gate,observer=RuntimeStageObserver(stages::add)).edit("Create gated")

        assertEquals(notices,report.gateNotices)
        assertEquals(listOf("citation_guard","path_guard","write"),stages.map { it.name })
        assertTrue(stages.all { it.status==RuntimeStageStatus.PASSED })
        assertEquals(1,inspected)
        assertEquals(2,verifications)
        assertFalse(leaseOpen)
        assertEquals("class Gated {}\n",Files.readString(repository.resolve("TeamCode/Gated.java")))
    }

    @Test
    fun `stale gate before apply closes lease without writes`(@TempDir root:Path) {
        val repository=repository(root)
        val provider=ScriptedProvider(retrievalPlan(),createPlan())
        var closed=false
        val gate=object:EditPrewriteGate {
            override fun inspect(preview:ValidatedEditBatch)=EditGateResult(true)
            override fun beginWrite(preview:ValidatedEditBatch)=AutoCloseable { closed=true }
            override fun verifyCurrent(preview:ValidatedEditBatch) {
                throw EditGateException("stale_knowledge",emptyList(),"request snapshot is stale")
            }
        }
        val engine=FileEditEngine(repository)
        val history=EditHistory(repository,engine)
        val failure=assertThrows(EditGateException::class.java) {
            agent(root,repository,provider,gate,engine,history).edit("Create gated")
        }

        assertEquals("stale_knowledge",failure.code)
        assertTrue(closed)
        assertTrue(Files.notExists(repository.resolve("TeamCode/Gated.java")))
        assertTrue(history.changes().isEmpty())
    }

    @Test
    fun `stale gate at final mutation boundary prevents write and cleans temporary file`(@TempDir root:Path) {
        val repository=repository(root)
        val provider=ScriptedProvider(retrievalPlan(),createPlan())
        var stale=false
        val engine=FileEditEngine(repository,beforeMutation={ _,_->stale=true })
        val history=EditHistory(repository,engine)
        val gate=object:EditPrewriteGate {
            override fun inspect(preview:ValidatedEditBatch)=EditGateResult(true)
            override fun verifyCurrent(preview:ValidatedEditBatch) {
                if (stale) throw EditGateException("stale_knowledge",emptyList(),"request snapshot is stale")
            }
        }
        val failure=assertThrows(FileEditApplyException::class.java) {
            agent(root,repository,provider,gate,engine,history).edit("Create gated")
        }

        assertTrue(failure.originalFailure is EditGateException)
        assertEquals("stale_knowledge",editFailureCategory(failure))
        assertTrue(failure.rollbackFailures.isEmpty())
        assertTrue(failure.cleanupFailures.isEmpty())
        assertTrue(Files.notExists(repository.resolve("TeamCode/Gated.java")))
        assertTrue(history.changes().isEmpty())
        Files.list(repository.resolve("TeamCode")).use { assertEquals(listOf("Drive.java"),it.map { path->path.fileName.toString() }.toList()) }
    }

    @Test
    fun `stale gate between files rolls back earlier mutations under same lease`(@TempDir root:Path) {
        val repository=repository(root)
        val provider=ScriptedProvider(retrievalPlan(),createPlan(twoFiles=true))
        var stale=false
        var leaseOpen=false
        var verifications=0
        val engine=FileEditEngine(repository,afterMutation={ _,_->stale=true })
        val history=EditHistory(repository,engine)
        val gate=object:EditPrewriteGate {
            override fun inspect(preview:ValidatedEditBatch)=EditGateResult(true)
            override fun beginWrite(preview:ValidatedEditBatch):AutoCloseable {
                leaseOpen=true
                return AutoCloseable { leaseOpen=false }
            }
            override fun verifyCurrent(preview:ValidatedEditBatch) {
                assertTrue(leaseOpen)
                verifications++
                if (stale) throw EditGateException("stale_knowledge",emptyList(),"request snapshot is stale")
            }
        }
        val failure=assertThrows(FileEditApplyException::class.java) {
            agent(root,repository,provider,gate,engine,history).edit("Create both")
        }

        assertTrue(failure.originalFailure is EditGateException)
        assertEquals(3,verifications)
        assertFalse(leaseOpen)
        assertTrue(failure.rollbackFailures.isEmpty())
        assertTrue(Files.notExists(repository.resolve("TeamCode/Gated.java")))
        assertTrue(Files.notExists(repository.resolve("TeamCode/Second.java")))
        assertTrue(history.changes().isEmpty())
        assertEquals(2,provider.requests.size)
    }

    private fun createPlan(twoFiles:Boolean=false):String {
        val second=if (twoFiles) ",{\"kind\":\"create\",\"path\":\"TeamCode/Second.java\",\"expectedAbsent\":true,\"content\":\"class Second {}\\n\",\"reason\":\"requested\",\"citations\":[\"CODE:C1\"]}" else ""
        return """{"summary":"gated","operations":[{"kind":"create","path":"TeamCode/Gated.java","expectedAbsent":true,"content":"class Gated {}\n","reason":"requested","citations":["CODE:C1"]}$second]}"""
    }

    private fun repository(root:Path):Path=root.resolve("repository").also { repository ->
        Files.createDirectories(repository.resolve("TeamCode"))
        Files.writeString(repository.resolve("TeamCode/Drive.java"),"class Drive {}\n")
    }

    private fun knowledge(root:Path):Path=root.resolve("knowledge").also(Files::createDirectories)

    private fun agent(
        root:Path,repository:Path,provider:ModelProvider,gate:EditPrewriteGate?=null,
        engine:FileEditEngine=FileEditEngine(repository),history:EditHistory=EditHistory(repository,engine),
        observer:RuntimeStageObserver=RuntimeStageObserver {}
    ):EditAgent {
        val index=RepositoryIndex().also { it.build(repository) }
        return EditAgent(
            RetrievalPlanner(provider),
            ContextRetriever(index,KnowledgeRetriever(knowledge(root),null,null,ruleProfiles=emptySet())),
            provider,index,engine,history,ConversationState(provider),"supported FTC repository",gate,observer
        )
    }

    private fun retrievalPlan()=
        """{"concepts":["Drive"],"symbols":[],"pathGlobs":["TeamCode/**"],"ruleTopics":[],"guideTopics":[]}"""

    private fun conflictingRules()="""
        schemaVersion: 1
        rules:
          - id: official.conflict-one
            topic: conflict-topic
            title: Conflict one
            instruction: Do one thing.
            rationale: Test conflict.
            status: approved
            authority: official
            applicability: {teams: [], seasons: []}
            evidence:
              - {repository: example/repo, commit: abcdef1, file: Source.java, symbol: Source}
            approval: {approver: lead, role: overall_software_lead, approvedAt: 2026-08-15T00:00:00Z}
          - id: official.conflict-two
            topic: conflict-topic
            title: Conflict two
            instruction: Do another thing.
            rationale: Test conflict.
            status: approved
            authority: official
            applicability: {teams: [], seasons: []}
            evidence:
              - {repository: example/repo, commit: abcdef2, file: Source.java, symbol: Source}
            approval: {approver: lead, role: overall_software_lead, approvedAt: 2026-08-15T00:00:00Z}
    """.trimIndent()

    private fun sha256(value:String):String=MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private class ScriptedProvider(vararg responses:String):ModelProvider {
        private val responses=ArrayDeque(responses.toList())
        val requests=mutableListOf<ModelRequest>()

        override fun complete(request:ModelRequest):ModelResponse {
            requests+=request
            return ModelResponse(responses.removeFirst())
        }
    }
}
