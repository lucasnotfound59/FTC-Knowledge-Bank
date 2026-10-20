package org.ftckb.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import org.ftckb.audit.*
import org.ftckb.knowledge.FileKnowledgeRepository
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class KernelAuditAcceptanceTest {
    @TempDir lateinit var temp:Path
    private val context=listOf("--team","20827","--season","2025-2026","--generic-profile")
    private val base get()=temp.resolve("audit")
    private fun knowledge():Path {
        val root=Files.createDirectories(temp.resolve("knowledge"))
        Files.writeString(root.resolve("rules.yaml"),"""
            schemaVersion: 3
            rules:
              - id: official.audit-rule
                topic: audit-topic
                title: Audit rule
                instruction: Review all changes.
                rationale: Protect SDK files.
                status: approved
                authority: official
                applicability: {}
                evidence:
                  - type: git
                    repository: owner/repo
                    commit: abcdef1
                    file: build.common.gradle
                    symbol: build.common.gradle
                approval:
                  approver: lead
                  role: overall_software_lead
                  approvedAt: 2026-08-13T00:00:00Z
                checks:
                  - kind: path-forbidden
                    pattern: build.common.gradle
                    note: SDK file is protected.
              - id: shared.audit-soft
                topic: audit-soft
                title: Review change
                instruction: Review each change manually.
                rationale: Requires review.
                status: approved
                authority: shared
                applicability: {}
                evidence:
                  - type: git
                    repository: owner/repo
                    commit: abcdef1
                    file: example.txt
                    symbol: example
                approval:
                  approver: lead
                  role: overall_software_lead
                  approvedAt: 2026-08-13T00:00:00Z
        """.trimIndent()+"\n")
        return root
    }
    private fun invoke(args:List<String>,dir:Path=base,diagnostics:(String)->Unit={}):Pair<Int,String> {
        val out=ByteArrayOutputStream()
        return runCli(args,PrintStream(out),auditDirectory=dir,auditDiagnostics=diagnostics) to out.toString()
    }
    private fun record(root:Path?)=FileAuditStore(base,root).page().items.single()
    private fun stage(record:AuditRecord,name:String)=record.stages.last { it.name==name }
    private fun patch(name:String="build.common.gradle"):Path=Files.writeString(temp.resolve("change.patch"),"""
        diff --git a/$name b/$name
        --- a/$name
        +++ b/$name
        @@ -1 +1 @@
        -old
        +NEVER_PERSIST_RAW_PATCH
    """.trimIndent()+"\n")
    private fun check(root:Path,patch:Path)=listOf("check",temp.toString(),"--knowledge",root.toString())+context+listOf("--diff",patch.toString(),"--json")

    @Test fun `hard check is completed blocked with exact hit and no patch content`() {
        val root=knowledge()
        assertEquals(1,invoke(check(root,patch())).first)
        val record=record(root)
        assertEquals(AuditRunStatus.COMPLETED,record.status)
        assertEquals(1,record.exitCode)
        assertEquals(AuditStageStatus.PASSED,stage(record,"diff_read").status)
        val checked=stage(record,"standardizer")
        assertEquals(AuditStageStatus.BLOCKED,checked.status)
        val hard=checked.hits.single { it.kind=="hard" }
        assertEquals("official.audit-rule",hard.ruleId)
        assertEquals("path-forbidden",hard.check)
        assertEquals("build.common.gradle",hard.path)
        assertEquals(1,hard.line)
        assertTrue(record.knowledgeVersion!!.startsWith("rules-sha256:"))
        assertEquals(2,record.ruleSnapshots.size)
        assertFalse(Files.readString(FileAuditStore(base,root).partitionPath.resolve("events.jsonl")).contains("NEVER_PERSIST_RAW_PATCH"))
    }
    @Test fun `dev check keeps downgraded locations and original soft`() {
        val root=knowledge()
        assertEquals(0,invoke(check(root,patch())+listOf("--work-mode","dev")).first)
        val record=record(root)
        val checked=stage(record,"standardizer")
        assertEquals("dev",record.metadata.context!!.workMode)
        assertEquals(AuditStageStatus.PASSED,checked.status)
        assertEquals("dev_downgrade",checked.category)
        assertEquals("build.common.gradle",checked.hits.single { it.kind=="dev_downgrade" }.path)
        assertEquals(1,checked.hits.count { it.kind=="soft" })
    }
    @Test fun `validate and resolve never claim checked diff`() {
        val root=knowledge()
        assertEquals(0,invoke(listOf("validate",root.toString(),"--json")).first)
        var record=record(root)
        assertEquals(AuditStageStatus.PASSED,stage(record,"knowledge_validate").status)
        assertEquals(AuditStageStatus.NOT_APPLICABLE,stage(record,"standardizer").status)
        assertEquals(0,invoke(listOf("resolve",root.toString())+context+"--json").first)
        record=FileAuditStore(base,root).page().items.first()
        assertEquals(AuditStageStatus.PASSED,stage(record,"resolve").status)
        assertEquals(AuditStageStatus.NOT_APPLICABLE,stage(record,"standardizer").status)
        assertEquals(0L,FileAuditStore(base,root).summary().diffChecked)
    }
    @Test fun `missing root usage is unscoped and later stages not run`() {
        assertEquals(64,invoke(listOf("resolve","--json")).first)
        val record=record(null)
        assertNull(record.metadata.knowledgeRoot)
        assertEquals(AuditRunStatus.FAILED,record.status)
        assertEquals(AuditStageStatus.BLOCKED,stage(record,"parameters").status)
        assertEquals(AuditStageStatus.NOT_RUN,stage(record,"knowledge_load").status)
        assertEquals(AuditStageStatus.NOT_APPLICABLE,stage(record,"standardizer").status)
    }
    @Test fun `known root parameter failure retains root but no load`() {
        val root=knowledge()
        assertEquals(2,invoke(listOf("resolve",root.toString(),"--team","20827","--season","2025-2026","--json")).first)
        val record=record(root)
        assertEquals(root.toRealPath().toString(),record.metadata.knowledgeRoot)
        assertEquals("usage",stage(record,"parameters").category)
        assertEquals(AuditStageStatus.NOT_RUN,stage(record,"knowledge_load").status)
    }
    @Test fun `malformed YAML and diff never persist raw exception bodies`() {
        val root=knowledge()
        Files.writeString(root.resolve("rules.yaml"),"rules: [NEVER_PERSIST_PARSER_SECRET\n")
        assertEquals(2,invoke(listOf("validate",root.toString(),"--json")).first)
        var record=record(root)
        assertEquals(AuditStageStatus.BLOCKED,stage(record,"knowledge_load").status)
        assertEquals(AuditStageStatus.NOT_RUN,stage(record,"knowledge_validate").status)
        knowledge()
        val malformed=Files.writeString(temp.resolve("bad.patch"),"NEVER_PERSIST_PARSER_SECRET\n")
        assertEquals(2,invoke(check(root,malformed)).first)
        record=FileAuditStore(base,root).page().items.first()
        assertEquals(AuditStageStatus.BLOCKED,stage(record,"diff_read").status)
        assertEquals(AuditStageStatus.NOT_RUN,stage(record,"standardizer").status)
        assertFalse(Files.readString(FileAuditStore(base,root).partitionPath.resolve("events.jsonl")).contains("NEVER_PERSIST_PARSER_SECRET"))
    }
    @Test fun `invalid knowledge and conflict preserve completed evidence`() {
        val root=knowledge()
        val source=root.resolve("rules.yaml")
        Files.writeString(source,Files.readString(source).replace("title: Audit rule","title: ''"))
        assertEquals(2,invoke(listOf("validate",root.toString(),"--json")).first)
        var record=record(root)
        assertEquals(AuditStageStatus.PASSED,stage(record,"knowledge_load").status)
        assertEquals("invalid_knowledge",stage(record,"knowledge_validate").category)
        knowledge()
        val original=Files.readString(source)
        val first=original.substringAfter("rules:\n").substringBefore("  - id: shared.audit-soft")
        Files.writeString(source,original+first.replace("official.audit-rule","official.audit-other"))
        assertEquals(2,invoke(check(root,patch())).first)
        record=FileAuditStore(base,root).page().items.first()
        assertEquals(AuditStageStatus.PASSED,stage(record,"knowledge_validate").status)
        assertEquals("conflict",stage(record,"resolve").category)
        assertEquals(setOf("official.audit-rule","official.audit-other"),stage(record,"resolve").hits.map { it.ruleId }.toSet())
        assertTrue(stage(record,"resolve").hits.all { it.detail=="topic=audit-topic; effectiveLevel=official" })
        assertFalse(stage(record,"resolve").hits.any { it.ruleId=="shared.audit-soft" })
        assertEquals(AuditStageStatus.NOT_RUN,stage(record,"diff_read").status)
    }
    @Test fun `storage failure warns only diagnostics and leaves exact kernel output`() {
        val root=knowledge()
        val args=listOf("validate",root.toString(),"--json")
        val expected=invoke(args)
        val file=Files.writeString(temp.resolve("not-directory"),"file")
        val diagnostics=mutableListOf<String>()
        assertEquals(expected,invoke(args,file,diagnostics::add))
        assertTrue(diagnostics.isNotEmpty())
        assertTrue(diagnostics.all { it.startsWith("ftckb audit:") })
    }
    @Test fun `help flag mixed into malformed check is audited as usage`() {
        assertEquals(64,invoke(listOf("check","--help","--json")).first)
        val record=record(null)
        assertEquals("check",record.metadata.command)
        assertEquals(AuditRunStatus.FAILED,record.status)
        assertEquals("usage",stage(record,"parameters").category)
        assertEquals(AuditStageStatus.NOT_RUN,stage(record,"standardizer").status)
    }
    @Test fun `semantic version distinguishes delimiter collisions in loaded fields`() {
        val rules=FileKnowledgeRepository.load(knowledge()).rules
        val rule=rules.first()
        val first=rule.copy(title="A",instruction="B, instruction=C")
        val second=rule.copy(title="A, instruction=B",instruction="C")
        assertEquals(first.toString(),second.toString())
        assertNotEquals(KernelAudit.version(listOf(first)),KernelAudit.version(listOf(second)))
        assertEquals(KernelAudit.version(rules),KernelAudit.version(rules.reversed()))
    }
    @Test fun `help and version produce no audit records`() {
        listOf(listOf("--help"),listOf("--version"),listOf("check","--help"),listOf("resolve","--help"),listOf("validate","--help")).forEach {
            assertEquals(0,invoke(it).first)
        }
        assertFalse(Files.exists(base))
    }
    @Test fun `two separate processes share audit partition through override`() {
        val root=knowledge()
        val binary=Path.of("build/install/ftckb/bin/ftckb").toAbsolutePath()
        val processes=(1..2).map {
            ProcessBuilder(binary.toString(),"validate",root.toString(),"--json").apply {
                environment()["FTCKB_AUDIT_DIR"]=base.toString()
                redirectError(temp.resolve("process-$it.stderr").toFile())
                redirectOutput(temp.resolve("process-$it.stdout").toFile())
            }.start()
        }
        processes.forEach { assertEquals(0,it.waitFor()) }
        val store=FileAuditStore(base,root)
        assertEquals(2L,store.summary().total)
        assertEquals(0L,store.summary().unfinished)
        assertTrue(store.page().items.all { it.status==AuditRunStatus.COMPLETED })
    }
    @Test fun `supplied patch versions bind complete input and preserve exact kernel output`() {
        val root=knowledge()
        val supplied=patch("example.txt")
        val first=invoke(check(root,supplied))
        val version=stage(record(root),"standardizer").codeVersion!!
        assertEquals("supplied_patch",version.kind)
        assertNull(version.head)
        assertNotNull(version.subjectId)
        assertEquals(64,version.inputDigest.length)
        assertEquals(first,invoke(check(root,supplied)))
        assertEquals(version,stage(FileAuditStore(base,root).page().items.first(),"standardizer").codeVersion)
        Files.writeString(supplied,Files.readString(supplied).replace("-old","-different deleted body"))
        assertEquals(first,invoke(check(root,supplied)))
        val changed=stage(FileAuditStore(base,root).page().items.first(),"standardizer").codeVersion!!
        assertEquals(version.subjectId,changed.subjectId)
        assertNotEquals(version.inputDigest,changed.inputDigest)
        assertFalse(Files.readString(FileAuditStore(base,root).partitionPath.resolve("events.jsonl")).contains("different deleted body"))
    }
    @Test fun `worktree versions distinguish binary and untracked bytes with stable output`() {
        val root=knowledge()
        val repository=Files.createDirectories(temp.resolve("repo"))
        org.eclipse.jgit.api.Git.init().setDirectory(repository.toFile()).call().use { git ->
            Files.write(repository.resolve("binary.bin"),byteArrayOf(0,1))
            git.add().addFilepattern(".").call()
            git.commit().setMessage("baseline").setAuthor("test","test@example.invalid").call()
            Files.write(repository.resolve("binary.bin"),byteArrayOf(0,2))
            Files.write(repository.resolve("untracked.bin"),byteArrayOf(0,3))
            val args=listOf("check",repository.toString(),"--knowledge",root.toString())+context+"--json"
            val first=invoke(args)
            val firstVersion=stage(record(root),"standardizer").codeVersion!!
            assertEquals("worktree",firstVersion.kind)
            assertEquals(git.repository.resolve("HEAD").name,firstVersion.head)
            assertEquals(first,invoke(args))
            assertEquals(firstVersion,stage(FileAuditStore(base,root).page().items.first(),"standardizer").codeVersion)
            Files.write(repository.resolve("binary.bin"),byteArrayOf(0,4))
            assertEquals(first,invoke(args))
            val binary=stage(FileAuditStore(base,root).page().items.first(),"standardizer").codeVersion!!
            assertNotEquals(firstVersion.inputDigest,binary.inputDigest)
            Files.write(repository.resolve("untracked.bin"),byteArrayOf(0,5))
            assertEquals(first,invoke(args))
            val untracked=stage(FileAuditStore(base,root).page().items.first(),"standardizer").codeVersion!!
            assertNotEquals(binary.inputDigest,untracked.inputDigest)
            assertEquals(firstVersion.subjectId,untracked.subjectId)
        }
    }
    @Test fun `supplied patch with unresolvable repository keeps independent version and succeeds`() {
        val root=knowledge()
        val args=check(root,patch("example.txt")).toMutableList().apply { this[1]=temp.resolve("absent-repository").toString() }
        assertEquals(0,invoke(args).first)
        val version=stage(record(root),"standardizer").codeVersion!!
        assertEquals("supplied_patch",version.kind)
        assertNull(version.subjectId)
    }

}
