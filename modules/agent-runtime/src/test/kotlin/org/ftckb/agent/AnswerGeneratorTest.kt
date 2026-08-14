package org.ftckb.agent

import java.nio.file.Path
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import org.ftckb.domain.Approval
import org.ftckb.domain.ApproverRole
import org.ftckb.domain.KnowledgeRule
import org.ftckb.domain.RuleApplicability
import org.ftckb.domain.RuleAuthority
import org.ftckb.domain.RuleStatus
import org.ftckb.model.ModelProvider
import org.ftckb.model.ModelRequest
import org.ftckb.model.ModelResponse
import org.ftckb.repository.RepositoryIndex
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class AnswerGeneratorTest {
    @TempDir
    lateinit var tempDir:Path

    @Test
    fun `accepts current code and rule citations while allowing uncited inference`() {
        val index=RepositoryIndex()
        val context=context(index)
        val provider=ScriptedProvider("""
            {"claims":[
              {"kind":"code_observation","text":"The null check is missing.","citations":["CODE:C1"]},
              {"kind":"model_inference","text":"Adding a guard is likely safest.","citations":[]}
            ]}
        """.trimIndent())

        val answer=AnswerGenerator(provider,index).generate(AnswerInput("What should change?",null,context))

        assertEquals(
            listOf(
                AnswerClaim(ClaimKind.CODE_OBSERVATION,"The null check is missing.",listOf("CODE:C1")),
                AnswerClaim(ClaimKind.MODEL_INFERENCE,"Adding a guard is likely safest.",emptyList())
            ),
            answer.claims
        )
    }

    @Test
    fun `retries an invented citation once`() {
        val index=RepositoryIndex()
        val context=context(index)
        val provider=ScriptedProvider(
            """{"claims":[{"kind":"code_observation","text":"Missing.","citations":["CODE:C99"]}]}""",
            """{"claims":[{"kind":"code_observation","text":"Missing.","citations":["CODE:C1"]}]}"""
        )

        val answer=AnswerGenerator(provider,index).generate(AnswerInput("What should change?",null,context))

        assertEquals(listOf("CODE:C1"),answer.claims.single().citations)
        assertEquals(2,provider.requests.size)
    }

    @Test
    fun `accepts an approved rule only with its current rule citation`() {
        val index=RepositoryIndex()
        val context=context(index,approvedRule())
        val provider=ScriptedProvider(
            """{"claims":[{"kind":"approved_rule","text":"Check the value first.","citations":["RULE:R1"]}]}"""
        )

        val answer=AnswerGenerator(provider,index).generate(AnswerInput("What does policy require?",null,context))

        assertEquals(ClaimKind.APPROVED_RULE,answer.claims.single().kind)
        assertEquals(listOf("RULE:R1"),answer.claims.single().citations)
    }

    @Test
    fun `rejects a second invented citation`() {
        val index=RepositoryIndex()
        val context=context(index)
        val provider=ScriptedProvider(
            """{"claims":[{"kind":"code_observation","text":"Missing.","citations":["CODE:C99"]}]}""",
            """{"claims":[{"kind":"code_observation","text":"Missing.","citations":["CODE:C98"]}]}"""
        )

        assertThrows(CitationValidationException::class.java) {
            AnswerGenerator(provider,index).generate(AnswerInput("What should change?",null,context))
        }
        assertEquals(2,provider.requests.size)
    }

    @Test
    fun `rejects a code citation whose indexed hash has changed`() {
        val index=RepositoryIndex()
        val context=context(index)
        tempDir.resolve("TeamCode/Drive.java").writeText("class Drive { void run() { guarded(); } }")
        index.refresh(setOf("TeamCode/Drive.java"))
        val provider=ScriptedProvider(
            """{"claims":[{"kind":"code_observation","text":"Missing.","citations":["CODE:C1"]}]}""",
            """{"claims":[{"kind":"code_observation","text":"Missing.","citations":["CODE:C1"]}]}"""
        )

        assertThrows(CitationValidationException::class.java) {
            AnswerGenerator(provider,index).generate(AnswerInput("What should change?",null,context))
        }
        assertEquals(2,provider.requests.size)
    }

    @Test
    fun `sends evidence payload within the context retrieval cap`() {
        val repositoryRoot=Files.createDirectories(tempDir.resolve("bounded-repository"))
        val source=repositoryRoot.resolve("TeamCode/Drive.java")
        source.parent.createDirectories()
        source.writeText("class Drive {}")
        val knowledgeRoot=Files.createDirectories(tempDir.resolve("empty-knowledge"))
        val index=RepositoryIndex()
        index.build(repositoryRoot)
        val cap=80
        val context=ContextRetriever(index,KnowledgeRetriever(knowledgeRoot,null,null),cap)
            .retrieve(RetrievalIntent(setOf("drive"),emptySet(),emptySet(),emptySet(),emptySet()))
        val provider=ScriptedProvider(
            """{"claims":[{"kind":"model_inference","text":"Inspect the drive class.","citations":[]}]}"""
        )

        AnswerGenerator(provider,index).generate(AnswerInput("What is Drive?",null,context))

        val payload=provider.requests.single().messages.single { it.role.name=="USER" }.content.substringAfter("Evidence:\n")
        assertTrue(context.evidence.isNotEmpty())
        assertEquals(context.estimatedCharacters,payload.length)
        assertTrue(payload.length<=cap)
    }

    @Test
    fun `does not open an outside-root guide symlink`() {
        val knowledgeRoot=Files.createDirectories(tempDir.resolve("guide-knowledge"))
        val guidesRoot=Files.createDirectories(knowledgeRoot.resolve("guides"))
        guidesRoot.resolve("inside.md").writeText("# Safe\n\ninside-secret")
        val outside=tempDir.resolve("outside.md")
        outside.writeText("# Leaked\n\noutside-secret")
        Files.createSymbolicLink(guidesRoot.resolve("outside.md"),outside)

        val guides=KnowledgeRetriever(knowledgeRoot,null,null)
            .retrieveGuides(RetrievalIntent(setOf("secret"),emptySet(),emptySet(),emptySet(),emptySet()))

        assertEquals(listOf("guides/inside.md"),guides.map { it.path })
        assertTrue(guides.none { "outside-secret" in it.text })
    }

    @Test
    fun `skips a named-pipe markdown entry before opening it`() {
        val knowledgeRoot=Files.createDirectories(tempDir.resolve("fifo-knowledge"))
        val guidesRoot=Files.createDirectories(knowledgeRoot.resolve("guides"))
        guidesRoot.resolve("inside.md").writeText("# Safe\n\ninside-secret")
        val fifo=guidesRoot.resolve("blocked.md")
        val process=runCatching { ProcessBuilder("mkfifo",fifo.toString()).start() }.getOrNull()
        val fifoProcess=process ?: run {
            assumeTrue(false,"mkfifo is unavailable")
            return
        }
        assumeTrue(fifoProcess.waitFor(5,TimeUnit.SECONDS) && fifoProcess.exitValue()==0,"mkfifo is unavailable")

        val guides=KnowledgeRetriever(knowledgeRoot,null,null)
            .retrieveGuides(RetrievalIntent(setOf("secret"),emptySet(),emptySet(),emptySet(),emptySet()))

        assertEquals(listOf("guides/inside.md"),guides.map { it.path })
    }

    private fun context(index:RepositoryIndex,rule:KnowledgeRule=rule()):ContextPack {
        val file=tempDir.resolve("TeamCode/Drive.java")
        file.parent.createDirectories()
        file.writeText("class Drive { void run() { result.toString(); } }")
        val snapshot=index.build(tempDir)
        val document=snapshot.documents.getValue("TeamCode/Drive.java")
        return ContextPack(
            listOf(
                CodeEvidence("CODE:C1",document.path,1,1,document.sha256,document.text),
                RuleEvidenceItem("RULE:R1",rule)
            ),
            document.text.length
        )
    }

    private fun rule()=KnowledgeRule(
        "shared.null-check","null-check","Check nullable values","Check values before use.","Avoid crashes.",
        RuleStatus.CANDIDATE,RuleAuthority.SHARED,RuleApplicability(),emptyList()
    )

    private fun approvedRule()=KnowledgeRule(
        "shared.null-check","null-check","Check nullable values","Check values before use.","Avoid crashes.",
        RuleStatus.APPROVED,RuleAuthority.SHARED,RuleApplicability(),emptyList(),
        Approval("overall-lead",ApproverRole.OVERALL_SOFTWARE_LEAD,null,Instant.EPOCH)
    )

    private class ScriptedProvider(vararg responses:String):ModelProvider {
        private val queue=ArrayDeque(responses.toList())
        val requests=mutableListOf<ModelRequest>()

        override fun complete(request:ModelRequest):ModelResponse {
            requests+=request
            return ModelResponse(queue.removeFirst())
        }
    }
}
