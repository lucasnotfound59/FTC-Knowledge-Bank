package org.ftckb.cli

import com.fasterxml.jackson.databind.json.JsonMapper
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.io.StringReader
import java.nio.file.Files
import java.nio.file.Path
import org.eclipse.jgit.api.Git
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class WorkModeAcceptanceTest {
    private val mapper=JsonMapper.builder().build()
    private val knowledgeRoot=Path.of("..","..","knowledge")
    private val fixtures=Path.of("..","..","fixtures","kernel")
    private val architectureIds=setOf("global.command-responsibilities","shared.ftclib-command-candidate")

    private fun run(args:List<String>):Pair<Int,String> {
        val out=ByteArrayOutputStream()
        val code=runCli(args,PrintStream(out),StringReader("").buffered())
        return code to out.toString()
    }

    private fun resolveArgs(vararg extra:String)=listOf(
        "resolve",knowledgeRoot.toString(),"--team","20827","--season","2025-2026","--profile","ftclib-command"
    )+extra

    private fun checkArgs(root:Path,knowledge:Path,vararg extra:String)=listOf(
        "check",root.toString(),"--knowledge",knowledge.toString(),
        "--team","20827","--season","2025-2026","--generic-profile"
    )+extra

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

    private fun twoCheckPatch(root:Path):Path {
        val patch=root.resolve("two-checks.patch")
        Files.writeString(patch,"""
            diff --git a/build.common.gradle b/build.common.gradle
            --- a/build.common.gradle
            +++ b/build.common.gradle
            @@ -1 +1,2 @@
            -// sdk
            +// sdk
            +// agent change
            diff --git a/TeamCode/Vision.java b/TeamCode/Vision.java
            --- a/TeamCode/Vision.java
            +++ b/TeamCode/Vision.java
            @@ -1 +1 @@
            -class Vision { void run() { } }
            +class Vision { void run() { getLatestResult(); } }
        """.trimIndent()+"\n")
        return patch
    }

    @Test
    fun `resolve test mode excludes only the two architecture mandate rules`() {
        val (normalCode,normalOut)=run(resolveArgs("--json"))
        assertEquals(0,normalCode,normalOut)
        val (testCode,testOut)=run(resolveArgs("--work-mode","test","--json"))
        assertEquals(0,testCode,testOut)
        assertSchemaValid(testOut)
        val normal=mapper.readTree(normalOut)
        val test=mapper.readTree(testOut)

        assertNull(normal.get("workMode"))
        assertEquals("test",test["workMode"].asText())
        assertEquals(30,normal["activeRules"].size())
        assertEquals(28,test["activeRules"].size())
        val normalIds=normal["activeRules"].map { it["id"].asText() }.toSet()
        val testIds=test["activeRules"].map { it["id"].asText() }.toSet()
        assertEquals(normalIds-architectureIds,testIds)
        val excluded=test["excludedRules"].filter { it["ruleId"].asText() in architectureIds }
        assertEquals(architectureIds.sorted(),excluded.map { it["ruleId"].asText() })
        excluded.forEach { rule ->
            assertEquals(listOf("work-mode-test"),rule["reasons"].map { it.asText() },rule.toString())
        }
        listOf("global.command-live-input","global.command-requirements-cleanup","global.test-utility-layout")
            .forEach { id -> assertTrue(id in testIds,id) }
    }

    @Test
    fun `resolve dev mode reports the mode without changing resolution`() {
        val (normalCode,normalOut)=run(resolveArgs("--json"))
        val (devCode,devOut)=run(resolveArgs("--work-mode","dev","--json"))

        assertEquals(0,normalCode,normalOut)
        assertEquals(0,devCode,devOut)
        assertSchemaValid(devOut)
        val normal=mapper.readTree(normalOut)
        val dev=mapper.readTree(devOut)
        assertEquals("dev",dev["workMode"].asText())
        assertEquals(
            normal["activeRules"].map { it["id"].asText() },
            dev["activeRules"].map { it["id"].asText() }
        )
        assertEquals(
            normal["excludedRules"].map { it["ruleId"].asText() },
            dev["excludedRules"].map { it["ruleId"].asText() }
        )
    }

    @Test
    fun `resolve test mode keeps profile exclusion reasons for inapplicable architecture rules`() {
        val (code,out)=run(
            listOf("resolve",knowledgeRoot.toString(),"--team","20827","--season","2025-2026",
                "--generic-profile","--work-mode","test","--json")
        )

        assertEquals(0,code,out)
        assertSchemaValid(out)
        val node=mapper.readTree(out)
        val excluded=node["excludedRules"].filter { it["ruleId"].asText() in architectureIds }
        assertEquals(
            listOf(
                "global.command-responsibilities" to listOf("profile","work-mode-test"),
                "shared.ftclib-command-candidate" to listOf("profile","work-mode-test")
            ),
            excluded.map { it["ruleId"].asText() to it["reasons"].map { reason -> reason.asText() } }
        )
    }

    @Test
    fun `resolve normal mode stays byte compatible including an explicit normal work mode`() {
        val (omittedCode,omitted)=run(resolveArgs("--json"))
        val (explicitCode,explicit)=run(resolveArgs("--work-mode","normal","--json"))

        assertEquals(0,omittedCode)
        assertEquals(0,explicitCode)
        assertEquals(omitted,explicit)
        assertEquals(Files.readString(fixtures.resolve("resolve-ok.json")),run(
            listOf("resolve",knowledgeRoot.toString(),"--team","20827","--season","2025-2026",
                "--generic-profile","--json")
        ).second)
    }

    @Test
    fun `resolve work mode usage errors are rejected before loading knowledge`() {
        listOf(
            listOf("--work-mode","fast") to "invalid value for --work-mode: expected normal|test|dev\n",
            listOf("--work-mode") to "resolve options must be flag-value pairs\n",
            listOf("--work-mode","") to "empty value for --work-mode\n",
            listOf("--work-mode","--season") to "invalid value for --work-mode: --season\n",
            listOf("--work-mode","test","--work-mode","dev") to "duplicate resolve option: --work-mode\n"
        ).forEach { (extra,expected) ->
            val out=ByteArrayOutputStream()
            val code=runCli(
                listOf("resolve","does-not-exist","--team","20827","--season","2025-2026","--generic-profile")+extra,
                PrintStream(out)
            )

            assertEquals(64,code,"extra=$extra")
            assertEquals(expected,out.toString(),"extra=$extra")
        }
    }

    @Test
    fun `check dev converts every hard finding to location rich soft and exits zero`(@TempDir root:Path) {
        val repo=writeRepo(root)
        val patch=twoCheckPatch(root)
        val (normalCode,normalOut)=run(checkArgs(repo,writeKnowledge(root),"--diff",patch.toString(),"--json"))
        assertEquals(1,normalCode,normalOut)
        val normal=mapper.readTree(normalOut)
        assertNull(normal.get("workMode"))
        assertEquals(3,normal["violations"].size())
        assertEquals(1,normal["soft"].size())

        val (devCode,devOut)=run(checkArgs(repo,writeKnowledge(root),"--diff",patch.toString(),"--work-mode","dev","--json"))
        assertEquals(0,devCode,devOut)
        assertSchemaValid(devOut)
        val dev=mapper.readTree(devOut)

        assertEquals("dev",dev["workMode"].asText())
        assertTrue(dev["ok"].booleanValue())
        assertEquals(0,dev["violations"].size())
        val soft=dev["soft"]
        assertEquals(4,soft.size())
        val converted=soft.filter { it["ruleId"].asText()!="shared.soft-behavior-rule" }
        assertEquals(
            listOf("official.keep-customizations-in-teamcode","shared.limelight-check-result-validity"),
            converted.map { it["ruleId"].asText() }.distinct()
        )
        converted.forEach { item -> assertTrue(item["note"].asText().startsWith("work-mode=dev; downgraded hard check="),item.toString()) }
        val pathNote=converted.first { it["ruleId"].asText()=="official.keep-customizations-in-teamcode" }["note"].asText()
        assertTrue(pathNote.contains("check=path-forbidden"),pathNote)
        assertTrue(pathNote.contains("path=build.common.gradle"),pathNote)
        assertTrue(pathNote.contains("line=1"),pathNote)
        assertTrue(pathNote.contains("pattern=build.common.gradle"),pathNote)
        assertTrue(pathNote.contains("detail=SDK reserved file"),pathNote)
        val regexNote=converted.first { it["ruleId"].asText()=="shared.limelight-check-result-validity" &&
            it["note"].asText().contains("regex-forbidden") }["note"].asText()
        assertTrue(regexNote.contains("path=TeamCode/Vision.java"),regexNote)
        assertTrue(converted.any { it["note"].asText().contains("check=regex-required") },converted.toString())
        val preserved=soft.first { it["ruleId"].asText()=="shared.soft-behavior-rule" }
        assertEquals("Run Build after dependency changes.",preserved["note"].asText())

        val (secondCode,secondOut)=run(checkArgs(repo,writeKnowledge(root),"--diff",patch.toString(),"--work-mode","dev","--json"))
        assertEquals(0,secondCode)
        assertEquals(devOut,secondOut)
    }

    @Test
    fun `check dev text output reports every downgraded soft notice`(@TempDir root:Path) {
        val repo=writeRepo(root)
        val patch=twoCheckPatch(root)
        val (code,out)=run(checkArgs(repo,writeKnowledge(root),"--diff",patch.toString(),"--work-mode","dev"))

        assertEquals(0,code,out)
        assertTrue(out.startsWith("work-mode=dev\n"),out)
        assertTrue(out.contains("soft rule=official.keep-customizations-in-teamcode: work-mode=dev; downgraded hard check=path-forbidden"),out)
        assertTrue(out.contains("soft rule=shared.limelight-check-result-validity: work-mode=dev; downgraded hard check=regex-forbidden"),out)
        assertTrue(out.contains("soft rule=shared.limelight-check-result-validity: work-mode=dev; downgraded hard check=regex-required"),out)
        assertTrue(out.contains("soft rule=shared.soft-behavior-rule"),out)
        assertFalse(out.contains("violation rule="),out)
        assertTrue(out.contains("check=pass violations=0 soft=4"),out)
    }

    @Test
    fun `check dev requires an explicit scoped diff`(@TempDir root:Path) {
        val repo=writeRepo(root)
        val knowledge=writeKnowledge(root)
        val (code,out)=run(checkArgs(repo,knowledge,"--work-mode","dev","--json"))

        assertEquals(64,code,out)
        assertSchemaValid(out)
        val node=mapper.readTree(out)
        assertEquals("usage",node["error"]["code"].asText())
        assertEquals("check --work-mode dev requires --diff FILE",node["error"]["message"].asText())
        assertNull(node.get("workMode"))
        val (textCode,text)=run(checkArgs(repo,knowledge,"--work-mode","dev"))
        assertEquals(64,textCode)
        assertEquals("check --work-mode dev requires --diff FILE\n",text)
    }

    @Test
    fun `check test mode still enforces the test layout hard rule`(@TempDir root:Path) {
        val repo=writeRepo(root)
        val patch=root.resolve("layout.patch")
        Files.writeString(patch,"""
            diff --git a/TeamCode/src/test/java/org/firstinspires/ftc/teamcode/DriveTest.java b/TeamCode/src/test/java/org/firstinspires/ftc/teamcode/DriveTest.java
            new file mode 100644
            --- /dev/null
            +++ b/TeamCode/src/test/java/org/firstinspires/ftc/teamcode/DriveTest.java
            @@ -0,0 +1,1 @@
            +class DriveTest {}
        """.trimIndent()+"\n")
        val args=listOf(
            "check",repo.toString(),"--knowledge",knowledgeRoot.toString(),
            "--team","20827","--season","2025-2026","--generic-profile","--diff",patch.toString()
        )

        val (normalCode,normalOut)=run(args+listOf("--json"))
        assertEquals(1,normalCode,normalOut)
        assertEquals(
            listOf("global.test-utility-layout"),
            mapper.readTree(normalOut)["violations"].map { it["ruleId"].asText() }
        )
        val (testCode,testOut)=run(args+listOf("--work-mode","test","--json"))
        assertEquals(1,testCode,testOut)
        assertSchemaValid(testOut)
        val test=mapper.readTree(testOut)
        assertEquals("test",test["workMode"].asText())
        assertFalse(test["ok"].booleanValue())
        assertEquals(listOf("global.test-utility-layout"),test["violations"].map { it["ruleId"].asText() })

        val junitPatch=root.resolve("junit-import.patch")
        Files.writeString(junitPatch,"""
            diff --git a/TeamCode/src/main/java/org/firstinspires/ftc/teamcode/Vision.java b/TeamCode/src/main/java/org/firstinspires/ftc/teamcode/Vision.java
            --- a/TeamCode/src/main/java/org/firstinspires/ftc/teamcode/Vision.java
            +++ b/TeamCode/src/main/java/org/firstinspires/ftc/teamcode/Vision.java
            @@ -1 +1,2 @@
             package org.firstinspires.ftc.teamcode;
            +import org.junit.Test;
        """.trimIndent()+"\n")
        val junitArgs=listOf(
            "check",repo.toString(),"--knowledge",knowledgeRoot.toString(),
            "--team","20827","--season","2025-2026","--generic-profile","--diff",junitPatch.toString()
        )
        val (junitCode,junitOut)=run(junitArgs+listOf("--work-mode","test","--json"))
        assertEquals(1,junitCode,junitOut)
        assertSchemaValid(junitOut)
        val junit=mapper.readTree(junitOut)
        assertEquals("test",junit["workMode"].asText())
        assertEquals(
            listOf("regex-forbidden"),
            junit["violations"].map { it["check"].asText() }
        )
        assertEquals(listOf("global.test-utility-layout"),junit["violations"].map { it["ruleId"].asText() })
    }

    @Test
    fun `check work mode usage errors are rejected before loading knowledge`(@TempDir root:Path) {
        listOf(
            listOf("--work-mode","fast") to "invalid value for --work-mode: expected normal|test|dev\n",
            listOf("--work-mode") to "check options must be flag-value pairs\n",
            listOf("--work-mode","") to "invalid value for --work-mode: expected normal|test|dev\n",
            listOf("--work-mode","test","--work-mode","dev") to "duplicate check option: --work-mode\n"
        ).forEach { (extra,expected) ->
            val out=ByteArrayOutputStream()
            val code=runCli(
                listOf("check","does-not-exist","--knowledge","missing","--team","20827","--season","2025-2026",
                    "--generic-profile")+extra,
                PrintStream(out)
            )

            assertEquals(64,code,"extra=$extra")
            assertEquals(expected,out.toString(),"extra=$extra")
        }
    }

    @Test
    fun `dev mode keeps invalid knowledge and resolver conflicts as errors`(@TempDir root:Path) {
        val repo=writeRepo(root)
        val patch=twoCheckPatch(root)
        val validKnowledge=writeKnowledge(root)
        val badPatch=root.resolve("bad.patch")
        Files.writeString(badPatch,"not a patch\n")
        val (diffCode,diffOut)=run(
            checkArgs(repo,validKnowledge,"--diff",badPatch.toString(),"--work-mode","dev","--json")
        )
        assertEquals(2,diffCode,diffOut)
        assertSchemaValid(diffOut)
        assertEquals("load-error",mapper.readTree(diffOut)["error"]["code"].asText())

        val invalid=Files.createDirectories(root.resolve("invalid-knowledge"))
        Files.writeString(invalid.resolve("rules.yaml"),"not-a-map")
        val (loadCode,loadOut)=run(
            checkArgs(repo,invalid,"--diff",patch.toString(),"--work-mode","dev","--json")
        )
        assertEquals(2,loadCode,loadOut)
        assertSchemaValid(loadOut)
        assertEquals("load-error",mapper.readTree(loadOut)["error"]["code"].asText())

        val conflict=Files.createDirectories(root.resolve("conflict-knowledge"))
        Files.writeString(conflict.resolve("rules.yaml"),"""
            schemaVersion: 1
            rules:
              - id: official.first
                topic: same-topic
                title: First
                instruction: First rule.
                rationale: Because.
                status: approved
                authority: official
                applicability: {}
                evidence:
                  - repository: owner/repo
                    commit: abcdef1
                    file: README.md
                    line: 1
                approval:
                  approver: overall-lead
                  role: overall_software_lead
                  approvedAt: 2026-08-14T00:00:00Z
              - id: official.second
                topic: same-topic
                title: Second
                instruction: Second rule.
                rationale: Because.
                status: approved
                authority: official
                applicability: {}
                evidence:
                  - repository: owner/repo
                    commit: abcdef1
                    file: README.md
                    line: 1
                approval:
                  approver: overall-lead
                  role: overall_software_lead
                  approvedAt: 2026-08-14T00:00:00Z
        """.trimIndent()+"\n")
        val (conflictCode,conflictOut)=run(
            checkArgs(repo,conflict,"--diff",patch.toString(),"--work-mode","dev","--json")
        )
        assertEquals(2,conflictCode,conflictOut)
        assertSchemaValid(conflictOut)
        val conflictNode=mapper.readTree(conflictOut)
        assertEquals("conflict",conflictNode["error"]["code"].asText())
        assertNull(conflictNode.get("workMode"))
    }

    @Test
    fun `check normal mode stays byte compatible including an explicit normal work mode`(@TempDir root:Path) {
        val repo=writeRepo(root)
        val knowledge=writeKnowledge(root)
        val (omittedCode,omitted)=run(checkArgs(repo,knowledge,"--json"))
        val (explicitCode,explicit)=run(checkArgs(repo,knowledge,"--work-mode","normal","--json"))

        assertEquals(0,omittedCode,omitted)
        assertEquals(0,explicitCode,explicit)
        assertEquals(omitted,explicit)
        assertFalse(omitted.contains("workMode"),omitted)
    }

    private fun assertSchemaValid(json:String) {
        val schema=Path.of("..","..","docs","kernel-contract.schema.json").toAbsolutePath().normalize()
        val process=ProcessBuilder(
            "python3","-c",
            "import json,jsonschema,sys; jsonschema.validate(json.load(sys.stdin),json.load(open(sys.argv[1])))",
            schema.toString()
        ).redirectErrorStream(true).start()
        process.outputStream.bufferedWriter().use { it.write(json) }
        val diagnostic=process.inputStream.bufferedReader().readText()
        assertEquals(0,process.waitFor(),"$diagnostic\n$json")
    }
}
