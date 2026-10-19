package org.ftckb.knowledge

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.ftckb.domain.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class KnowledgeWorkspaceTest {
    @TempDir lateinit var temp:Path
    private fun root():Path=Files.createDirectory(temp.resolve("knowledge")).toRealPath()
    private fun rule(id:String="shared.one",instruction:String="Keep it safe",status:String="candidate")="""
        schemaVersion: 4
        rules:
          - id: $id
            topic: ${id.substringAfterLast('.')}
            title: Rule 中文 😀
            instruction: $instruction
            rationale: Safety rationale
            status: $status
            authority: shared
            policyLevel: shared
            applicability:
              teams: []
              seasons: []
              profiles: []
            evidence:
              - type: git
                repository: https://example.com/repo
                commit: abc1234
                file: src/Main.java
                line: 1
    """.trimIndent()+"\n"
    private val lead=Approver("lead",ApproverRole.OVERALL_SOFTWARE_LEAD)
    private fun put(root:Path,text:String=rule(),name:String="rules.yaml"):Path=Files.writeString(root.resolve(name),text)
    private fun edit(workspace:KnowledgeWorkspace,text:String):RuleMutationPreview=workspace.preview(RuleMutationRequest(
        RuleMutationAction.EDIT,"shared.one",workspace.snapshot().version,text))

    @Test fun `snapshot hashes exact bytes and retains malformed files without stale fallback`() {
        val root=root();val file=put(root)
        val workspace=KnowledgeWorkspace(root)
        val first=workspace.snapshot()
        assertTrue(first.valid);assertEquals(1,first.rules.size);assertTrue(first.sources.getValue("shared.one").ruleYaml.contains("😀"))
        Files.writeString(file,Files.readString(file)+"# comment\n")
        assertNotEquals(first.version,workspace.snapshot().version)
        Files.writeString(file,"rules: [broken")
        val invalid=workspace.snapshot()
        assertFalse(invalid.valid);assertEquals(1,invalid.fileDigests.size);assertTrue(invalid.rules.isEmpty());assertEquals("rules.yaml",invalid.loadErrors.single().path)
        assertEquals("Keep it safe",first.rules.single().instruction)
        assertThrows(UnsupportedOperationException::class.java) { (first.rules as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (first.fileDigests as MutableMap).clear() }
    }

    @Test fun `editing one rule preserves other rule bytes and surrounding comments`() {
        val root=root()
        val suffix="\n# untouched next comment\n"+rule("shared.two",instruction="Second rule").substringAfter("rules:\n")+"# final comment\n"
        val original="# header\n"+rule()+suffix
        val file=put(root,original)
        val workspace=KnowledgeWorkspace(root)
        val source=workspace.snapshot().sources.getValue("shared.one")
        val preview=edit(workspace,source.ruleYaml.replace("Keep it safe","New safe instruction"))
        assertTrue(preview.canApply,preview.toString())
        workspace.apply(preview.previewId)
        val updated=Files.readString(file)
        assertEquals(original.replace("Keep it safe","New safe instruction"),updated)
        assertTrue(workspace.snapshot().valid)
    }

    @Test fun `full library errors and duplicate IDs prevent applying valid single rule edit`() {
        val root=root();put(root);put(root,rule("shared.two").replace("title: Rule 中文 😀","title: ''"),"other.yml")
        val workspace=KnowledgeWorkspace(root)
        val preview=edit(workspace,rule(instruction="changed"))
        assertFalse(preview.canApply);assertTrue(preview.violations.any { it.ruleId=="shared.two" })
        assertCode("invalid_rule") { workspace.apply(preview.previewId) }
        put(root,rule(),"other.yml")
        assertFalse(workspace.snapshot().sources.containsKey("shared.one"))
        assertCode("invalid_rule") { edit(workspace,rule()) }
    }

    @Test fun `create chooses safe candidate path and requires explicit candidate status`() {
        val root=root();val workspace=KnowledgeWorkspace(root)
        val preview=workspace.preview(RuleMutationRequest(RuleMutationAction.CREATE,"shared.one",workspace.snapshot().version,rule()))
        assertTrue(preview.canApply);assertEquals("candidates/shared.one.yaml",preview.targetPath)
        workspace.apply(preview.previewId)
        assertTrue(Files.exists(root.resolve(preview.targetPath)))
        assertCode("invalid_rule") { workspace.preview(RuleMutationRequest(RuleMutationAction.CREATE,"../bad",workspace.snapshot().version,rule())) }
        assertCode("invalid_rule") { workspace.preview(RuleMutationRequest(RuleMutationAction.CREATE,"shared.two",workspace.snapshot().version,rule("shared.two",status="approved"))) }
        val flow="schemaVersion: 4\nrules: [{id: shared.two, topic: two, title: t, instruction: i, rationale: r, status: candidate, authority: shared, policyLevel: shared, applicability: {teams: [], seasons: [], profiles: []}, evidence: [{type: git, repository: repo, commit: abc1234, file: Main.java, line: 1}]}]\n"
        assertCode("invalid_rule") { workspace.preview(RuleMutationRequest(RuleMutationAction.CREATE,"shared.two",workspace.snapshot().version,flow)) }
    }

    @Test fun `approval uses policy declared identity and generated timestamp and substantive edit resets it`() {
        val root=root();put(root);val workspace=KnowledgeWorkspace(root)
        assertCode("approval_denied") { workspace.preview(RuleMutationRequest(RuleMutationAction.APPROVE,"shared.one",workspace.snapshot().version,approver=Approver("",ApproverRole.OVERALL_SOFTWARE_LEAD))) }
        assertCode("approval_denied") { workspace.preview(RuleMutationRequest(RuleMutationAction.APPROVE,"shared.one",workspace.snapshot().version,approver=Approver("team",ApproverRole.TEAM_SOFTWARE_LEAD,"20827"))) }
        val start=Instant.now()
        val approve=workspace.preview(RuleMutationRequest(RuleMutationAction.APPROVE,"shared.one",workspace.snapshot().version,approver=lead))
        assertTrue(approve.canApply,approve.toString());workspace.apply(approve.previewId)
        val approved=workspace.snapshot().rules.single()
        assertEquals(RuleStatus.APPROVED,approved.status);assertFalse(approved.approval!!.approvedAt.isBefore(start))
        val document=workspace.snapshot().sources.getValue("shared.one").ruleYaml
        val commentOnly=edit(workspace,document+"# editor comment\n")
        assertFalse(commentOnly.approvalReset)
        val modified=edit(workspace,document.replace("Keep it safe","A revised rule"))
        assertTrue(modified.approvalReset);assertTrue(modified.canApply,modified.toString())
        workspace.apply(modified.previewId)
        assertEquals(RuleStatus.CANDIDATE,workspace.snapshot().rules.single().status);assertNull(workspace.snapshot().rules.single().approval)
    }

    @Test fun `ordinary edits cannot rename promote or forge approval`() {
        val root=root();put(root);val workspace=KnowledgeWorkspace(root)
        assertCode("invalid_rule") { edit(workspace,rule("shared.other")) }
        assertCode("invalid_rule") { edit(workspace,rule(status="approved")) }
        assertCode("invalid_rule") { edit(workspace,"schemaVersion: 4\nrules: []\n") }
    }

    @Test fun `valid full library replacement can repair a validation-invalid current rule`() {
        val root=root();put(root,rule().replace("title: Rule 中文 😀","title: ''"));val workspace=KnowledgeWorkspace(root)
        assertFalse(workspace.snapshot().valid)
        val preview=edit(workspace,rule())
        assertTrue(preview.canApply,preview.toString())
        workspace.apply(preview.previewId)
        assertTrue(workspace.snapshot().valid)
    }

    @Test fun `reject also checks approval policy and only candidates`() {
        val root=root();put(root);val workspace=KnowledgeWorkspace(root)
        assertCode("approval_denied") { workspace.preview(RuleMutationRequest(RuleMutationAction.REJECT,"shared.one",workspace.snapshot().version)) }
        val reject=workspace.preview(RuleMutationRequest(RuleMutationAction.REJECT,"shared.one",workspace.snapshot().version,approver=lead))
        workspace.apply(reject.previewId)
        assertEquals(RuleStatus.REJECTED,workspace.snapshot().rules.single().status)
        assertCode("approval_denied") { workspace.preview(RuleMutationRequest(RuleMutationAction.APPROVE,"shared.one",workspace.snapshot().version,approver=lead)) }
    }

    @Test fun `whole library CAS catches unrelated modification and target digest catches modification`() {
        val root=root();val file=put(root);val other=put(root,rule("shared.two"),"other.yml");val workspace=KnowledgeWorkspace(root)
        val preview=edit(workspace,rule(instruction="changed"))
        Files.writeString(other,Files.readString(other)+"# external change\n")
        assertCode("stale_preview") { workspace.apply(preview.previewId) }
        assertEquals("Keep it safe",workspace.snapshot().rules.first { it.id=="shared.one" }.instruction)
        val second=edit(workspace,rule(instruction="changed"))
        Files.writeString(file,Files.readString(file)+"# target changed\n")
        assertCode("stale_preview") { workspace.apply(second.previewId) }
        assertCode("stale_preview") { workspace.apply("unknown") }
    }

    @Test fun `a no-op preview can only be applied once`() {
        val root=root();put(root);val workspace=KnowledgeWorkspace(root)
        val preview=edit(workspace,workspace.snapshot().sources.getValue("shared.one").ruleYaml)
        assertTrue(preview.canApply)
        workspace.apply(preview.previewId)
        assertCode("stale_preview") { workspace.apply(preview.previewId) }
    }

    @Test fun `symbolic link root ancestor leaf and destination are rejected`() {
        val root=root();val file=put(root);val workspace=KnowledgeWorkspace(root)
        val preview=edit(workspace,rule(instruction="changed"))
        val outside=Files.writeString(temp.resolve("outside.yaml"),rule()).toRealPath()
        Files.delete(file);Files.createSymbolicLink(file,outside)
        assertCode("unsafe_path") { workspace.apply(preview.previewId) };assertEquals(rule(),Files.readString(outside))
        assertCode("unsafe_path") { workspace.snapshot() }
        val alias=Files.createSymbolicLink(temp.toRealPath().resolve("alias"),root)
        assertCode("unsafe_path") { KnowledgeWorkspace(alias).snapshot() }
        assertCode("unsafe_path") { KnowledgeWorkspace(alias.resolve("nested")).snapshot() }
    }

    @Test fun `same root lease serializes independent workspace instances and detects stale version`() {
        val root=root();put(root);val one=KnowledgeWorkspace(root);val two=KnowledgeWorkspace(root)
        val preview=two.preview(RuleMutationRequest(RuleMutationAction.EDIT,"shared.one",two.snapshot().version,rule(instruction="changed")))
        val pool=Executors.newSingleThreadExecutor()
        try {
            val lease=one.acquireSnapshotLease(one.snapshot().version)
            val future=pool.submit<RuleMutationResult> { two.apply(preview.previewId) }
            assertThrows(java.util.concurrent.TimeoutException::class.java) { future.get(100,TimeUnit.MILLISECONDS) }
            lease.close();future.get(5,TimeUnit.SECONDS)
            assertCode("stale_preview") { one.acquireSnapshotLease(preview.baseVersion) }
        } finally { pool.shutdownNow() }
    }

    @Test fun `preview context uses resolver and does not infer incomplete context`() {
        val root=root();put(root);val workspace=KnowledgeWorkspace(root)
        val preview=workspace.preview(RuleMutationRequest(RuleMutationAction.EDIT,"shared.one",workspace.snapshot().version,rule(),context=RuleContext("20827","2025-2026",emptySet())))
        assertTrue(preview.canApply);assertNotNull(preview.resolution)
        val incomplete=workspace.preview(RuleMutationRequest(RuleMutationAction.EDIT,"shared.one",workspace.snapshot().version,rule(),context=RuleContext(null,null,null)))
        assertFalse(incomplete.canApply);assertTrue(incomplete.loadErrors.isNotEmpty())
    }

    @Test fun `cross process lease shares lock despite different JVM temporary directories`() {
        val root=root();put(root)
        val directory=temp.toRealPath()
        val leaseReady=directory.resolve("lease-ready")
        val applyReady=directory.resolve("apply-ready")
        val release=directory.resolve("release")
        val classpath=listOf(KnowledgeWorkspaceProcessMain::class.java,KnowledgeWorkspace::class.java,
            KnowledgeRule::class.java,kotlin.jvm.internal.Intrinsics::class.java,
            org.snakeyaml.engine.v2.api.Load::class.java).map { Path.of(it.protectionDomain.codeSource.location.toURI()).toString() }
            .distinct().joinToString(java.io.File.pathSeparator)
        fun start(action:String,ready:Path,tmpName:String):Process {
            val childTmp=Files.createDirectory(directory.resolve(tmpName))
            return ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),
                "-Djava.io.tmpdir=$childTmp","-cp",classpath,KnowledgeWorkspaceProcessMain::class.java.name,
                action,root.toString(),ready.toString(),release.toString()).redirectErrorStream(true).start()
        }
        fun awaitReady(file:Path,process:Process) {
            val deadline=System.nanoTime()+10_000_000_000L
            while(!Files.exists(file)) {
                if(!process.isAlive) throw AssertionError("child failed: "+process.inputStream.bufferedReader().readText())
                check(System.nanoTime()<deadline) { "child did not become ready" };Thread.sleep(10)
            }
        }
        val holder=start("lease",leaseReady,"first-tmp")
        var writer:Process?=null
        try {
            awaitReady(leaseReady,holder)
            writer=start("apply",applyReady,"second-tmp")
            awaitReady(applyReady,writer)
            assertFalse(writer.waitFor(150,TimeUnit.MILLISECONDS),"apply must remain blocked by the other JVM's lease")
            Files.writeString(release,"release")
            assertTrue(holder.waitFor(10,TimeUnit.SECONDS));assertEquals(0,holder.exitValue(),holder.inputStream.bufferedReader().readText())
            assertTrue(writer.waitFor(10,TimeUnit.SECONDS));assertEquals(0,writer.exitValue(),writer.inputStream.bufferedReader().readText())
            assertEquals("Cross process edit",KnowledgeWorkspace(root).snapshot().rules.single().instruction)
        } finally { holder.destroyForcibly();writer?.destroyForcibly() }
    }

    @Test fun `concurrent decoder calls do not share YAML constructor state`() {
        val pool=Executors.newFixedThreadPool(6)
        try {
            val jobs=(1..60).map { value -> pool.submit<String> {
                val id="shared.rule-$value"
                repeat(10) {
                    val decoded=RuleYamlCodec.decode(rule(id,instruction="Instruction $value"))
                    assertEquals(id,decoded.single().id);assertEquals("Instruction $value",decoded.single().instruction)
                }
                id
            } }
            jobs.forEach { it.get(10,TimeUnit.SECONDS) }
        } finally { pool.shutdownNow() }
    }

    private fun assertCode(code:String,body:()->Unit) { assertEquals(code,assertThrows(KnowledgeMutationException::class.java,body).code) }
}
