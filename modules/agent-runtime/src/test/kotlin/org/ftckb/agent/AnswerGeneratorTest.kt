package org.ftckb.agent

import java.nio.file.Path
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.nio.file.attribute.PosixFilePermission
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
import org.junit.jupiter.api.Assertions.assertFalse
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
    fun `rejects a code citation when the file mutates during the provider response`() {
        val index=RepositoryIndex()
        val context=context(index)
        val provider=MutatingProvider({
            tempDir.resolve("TeamCode/Drive.java").writeText("class Drive { void run() { guarded(); } }")
        })

        assertThrows(CitationValidationException::class.java) {
            AnswerGenerator(provider,index).generate(AnswerInput("What should change?",null,context))
        }
        assertEquals(2,provider.requests.size)
    }

    @Test
    fun `rejects a code citation when the file is deleted during the provider response`() {
        val index=RepositoryIndex()
        val context=context(index)
        val provider=MutatingProvider({ Files.deleteIfExists(tempDir.resolve("TeamCode/Drive.java")) })

        assertThrows(CitationValidationException::class.java) {
            AnswerGenerator(provider,index).generate(AnswerInput("What should change?",null,context))
        }
        assertEquals(2,provider.requests.size)
    }

    @Test
    fun `validates a current citation for a literal filename containing glob metacharacters`() {
        val index=RepositoryIndex()
        val path="TeamCode/Drive[Primary]{A}.java"
        val context=context(index,path=path)
        val provider=ScriptedProvider(
            """{"claims":[{"kind":"code_observation","text":"Current.","citations":["CODE:C1"]}]}"""
        )

        val answer=AnswerGenerator(provider,index).generate(AnswerInput("What should change?",null,context))

        assertEquals(listOf("CODE:C1"),answer.claims.single().citations)
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
        val cap=256
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

    @Test
    fun `guide scanning enforces aggregate traversal limits`() {
        val knowledgeRoot=Files.createDirectories(tempDir.resolve("bounded-guide-knowledge"))
        val guidesRoot=Files.createDirectories(knowledgeRoot.resolve("guides"))
        guidesRoot.resolve("one.md").writeText("# Drive one\nfirst")
        guidesRoot.resolve("two.md").writeText("# Drive two\nsecond")
        val retriever=KnowledgeRetriever(
            knowledgeRoot,null,null,GuideTraversalLimits(maxFiles=1)
        )

        assertThrows(GuideTraversalException::class.java) {
            retriever.retrieveGuides(RetrievalIntent(setOf("drive"),emptySet(),emptySet(),emptySet(),emptySet()))
        }
    }

    @Test
    fun `guide scanning skips an unreadable markdown entry and keeps readable evidence`() {
        val knowledgeRoot=Files.createDirectories(tempDir.resolve("unreadable-guide-knowledge"))
        val guidesRoot=Files.createDirectories(knowledgeRoot.resolve("guides"))
        guidesRoot.resolve("safe.md").writeText("# Drive safe\nreadable drive evidence")
        val blocked=guidesRoot.resolve("blocked.md")
        blocked.writeText("# Drive blocked\nunreadable drive evidence")
        assumeTrue(Files.getFileStore(guidesRoot).supportsFileAttributeView("posix"))
        Files.setPosixFilePermissions(blocked,emptySet<PosixFilePermission>())
        assumeTrue(
            runCatching { Files.newByteChannel(blocked).use { } }.isFailure,
            "filesystem still permits reading mode 000 files"
        )
        try {
            val guides=KnowledgeRetriever(knowledgeRoot,null,null)
                .retrieveGuides(RetrievalIntent(setOf("drive"),emptySet(),emptySet(),emptySet(),emptySet()))

            assertEquals(listOf("guides/safe.md"),guides.map { it.path })
        } finally {
            Files.setPosixFilePermissions(
                blocked,setOf(PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_WRITE)
            )
        }
    }

    private fun context(
        index:RepositoryIndex,
        rule:KnowledgeRule=rule(),
        path:String="TeamCode/Drive.java"
    ):ContextPack {
        val file=tempDir.resolve(path)
        file.parent.createDirectories()
        file.writeText("class Drive { void run() { result.toString(); } }")
        val snapshot=index.build(tempDir)
        val document=snapshot.documents.getValue(path)
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

    @Test
    fun `answer prompt never hardcodes a concrete citation id`() {
        val provider=ScriptedProvider(
            """{"claims":[{"kind":"model_inference","text":"No evidence here.","citations":[]}]}"""
        )

        AnswerGenerator(provider,RepositoryIndex()).generate(AnswerInput("question",null,ContextPack(emptyList(),0)))

        val system=provider.requests.single().messages.first().content
        assertTrue(system.startsWith("Answer only as JSON"))
        assertFalse(system.contains("\"citations\":[\"CODE:C1\"]"))
        assertTrue(system.contains("Copy citation IDs verbatim"))
        val user=provider.requests.single().messages.last().content
        assertTrue(user.contains("Available citation IDs:"))
    }

    private class ScriptedProvider(vararg responses:String):ModelProvider {
        private val queue=ArrayDeque(responses.toList())
        val requests=mutableListOf<ModelRequest>()

        override fun complete(request:ModelRequest):ModelResponse {
            requests+=request
            return ModelResponse(queue.removeFirst())
        }
    }

    private class MutatingProvider(private val mutate:()->Unit):ModelProvider {
        val requests=mutableListOf<ModelRequest>()

        override fun complete(request:ModelRequest):ModelResponse {
            requests+=request
            mutate()
            return ModelResponse(
                """{"claims":[{"kind":"code_observation","text":"Missing.","citations":["CODE:C1"]}]}"""
            )
        }
    }
}
