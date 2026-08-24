package org.ftckb.cli

import com.fasterxml.jackson.databind.json.JsonMapper
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.io.StringReader
import java.nio.file.Files
import java.nio.file.Path
import org.eclipse.jgit.api.Git
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CheckAcceptanceTest {
    private val mapper=JsonMapper.builder().build()

    private fun writeKnowledge(root:Path):Path {
        val knowledge=Files.createDirectories(root.resolve("knowledge"))
        Files.writeString(knowledge.resolve("rules.yaml"),"""
            schemaVersion: 3
            rules:
              - id: official.keep-customizations-in-teamcode
                topic: build-customization-location
                title: Keep build customizations in TeamCode
                instruction: Put customizations in TeamCode/build.gradle.
                rationale: SDK reserves build.common.gradle.
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
                  approver: overall-lead
                  role: overall_software_lead
                  approvedAt: 2026-08-13T00:00:00Z
                checks:
                  - kind: path-forbidden
                    pattern: build.common.gradle
                    note: SDK reserved file
              - id: shared.limelight-check-result-validity
                topic: limelight-result-validity
                title: Check Limelight results
                instruction: Always check result validity.
                rationale: Invalid results crash.
                status: approved
                authority: shared
                applicability: {}
                evidence:
                  - type: git
                    repository: owner/repo
                    commit: abcdef1
                    file: TeamCode/Vision.java
                    symbol: run
                approval:
                  approver: overall-lead
                  role: overall_software_lead
                  approvedAt: 2026-08-13T00:00:00Z
                checks:
                  - kind: regex-required
                    pattern: "\\.isValid\\(\\)"
                    appliesTo: "**/*.java"
                    note: Validity check required
                  - kind: regex-forbidden
                    pattern: "getLatestResult\\(\\)\\s*;"
                    appliesTo: "**/*.java"
                    note: Use isValid before reading
              - id: shared.soft-behavior-rule
                topic: soft-behavior
                title: Verify build after changes
                instruction: Run Build after dependency changes.
                rationale: Machines cannot verify behavior.
                status: approved
                authority: shared
                applicability: {}
                evidence:
                  - type: git
                    repository: owner/repo
                    commit: abcdef1
                    file: TeamCode/Vision.java
                    symbol: run
                approval:
                  approver: overall-lead
                  role: overall_software_lead
                  approvedAt: 2026-08-13T00:00:00Z
        """.trimIndent()+"\n")
        return knowledge
    }

    private fun writeRepo(root:Path):Path {
        val repo=Files.createDirectories(root.resolve("repo"))
        Files.writeString(repo.resolve("build.common.gradle"),"// sdk\n")
        Files.writeString(repo.resolve("TeamCode").resolve("Vision.java").apply { parent.toFile().mkdirs() },
            "class Vision { void run() { } }\n")
        Git.init().setDirectory(repo.toFile()).setInitialBranch("team-work").call().use { git ->
            git.add().addFilepattern(".").call()
            git.commit().setMessage("fixture").call()
        }
        return repo
    }

    private fun runCheck(repo:Path,knowledge:Path,extra:List<String> =emptyList()):Pair<Int,String> {
        val out=ByteArrayOutputStream()
        val code=runCli(listOf("check",repo.toString(),"--knowledge",knowledge.toString(),
            "--team","20827","--season","2025-2026")+extra,PrintStream(out),StringReader("").buffered())
        return code to out.toString()
    }

    @Test
    fun `clean working tree passes with soft notes`(@TempDir root:Path) {
        val repo=writeRepo(root)
        val (code,out)=runCheck(repo,writeKnowledge(root))
        assertEquals(0,code,out)
        assertTrue(out.contains("check=pass"),out)
        assertTrue(out.contains("soft rule=shared.soft-behavior-rule"),out)
    }

    @Test
    fun `path forbidden violation fails with exit one`(@TempDir root:Path) {
        val repo=writeRepo(root)
        Files.writeString(repo.resolve("build.common.gradle"),"// sdk\n// agent change\n")
        val (code,out)=runCheck(repo,writeKnowledge(root),listOf("--json"))
        assertEquals(1,code,out)
        val node=mapper.readTree(out)
        assertTrue(!node["ok"].booleanValue())
        val violation=node["violations"][0]
        assertEquals("official.keep-customizations-in-teamcode",violation["ruleId"].asText())
        assertEquals("path-forbidden",violation["check"].asText())
        assertEquals("build.common.gradle",violation["path"].asText())
    }

    @Test
    fun `regex forbidden and required violations report path and line`(@TempDir root:Path) {
        val repo=writeRepo(root)
        Files.writeString(repo.resolve("TeamCode/Vision.java"),
            "class Vision { void run() { getLatestResult(); } }\n")
        val (code,out)=runCheck(repo,writeKnowledge(root),listOf("--json"))
        assertEquals(1,code,out)
        val node=mapper.readTree(out)
        val kinds=node["violations"].map { it["check"].asText() }
        assertTrue("regex-forbidden" in kinds,out)
        assertTrue("regex-required" in kinds,out)
    }

    @Test
    fun `compliant added lines pass`(@TempDir root:Path) {
        val repo=writeRepo(root)
        Files.writeString(repo.resolve("TeamCode/Vision.java"),
            "class Vision { void run() { if (getLatestResult().isValid()) { } } }\n")
        val (code,out)=runCheck(repo,writeKnowledge(root),listOf("--json"))
        assertEquals(0,code,out)
        val node=mapper.readTree(out)
        assertTrue(node["ok"].booleanValue())
        assertEquals(0,node["violations"].size())
    }

    @Test
    fun `diff patch file is checked instead of the working tree`(@TempDir root:Path) {
        val repo=writeRepo(root)
        val patch=root.resolve("change.patch")
        Files.writeString(patch,"""
            diff --git a/build.common.gradle b/build.common.gradle
            --- a/build.common.gradle
            +++ b/build.common.gradle
            @@ -1 +1,2 @@
            -// sdk
            +// sdk
            +// agent change
        """.trimIndent()+"\n")
        val (code,out)=runCheck(repo,writeKnowledge(root),listOf("--diff",patch.toString(),"--json"))
        assertEquals(1,code,out)
        val node=mapper.readTree(out)
        assertEquals("build.common.gradle",node["violations"][0]["path"].asText())
    }

    @Test
    fun `usage errors exit sixty four`() {
        val out=ByteArrayOutputStream()
        assertEquals(64,runCli(listOf("check","repo","--knowledge","k","--season","2025-2026"),PrintStream(out),StringReader("").buffered()))
        assertEquals("missing --team\n",out.toString())
    }
}
