package org.ftckb.cli

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.json.JsonMapper
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.io.StringReader
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import org.ftckb.domain.ApproverRole
import org.ftckb.domain.GitRuleEvidence
import org.ftckb.domain.KnowledgeRule
import org.ftckb.domain.PolicyLevel
import org.ftckb.domain.RuleAuthority
import org.ftckb.domain.RuleCheckKind
import org.ftckb.domain.RuleStatus
import org.ftckb.knowledge.FileKnowledgeRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TestUtilityLayoutAcceptanceTest {
    private val root=Path.of("..","..").normalize()
    private val mapper=JsonMapper.builder().build()
    private val layoutRuleId="global.test-utility-layout"
    private val reapproved=setOf(
        "shared.rookiebot-java-imports",
        "shared.rookiebot-verification-evidence"
    )

    private fun rules():List<KnowledgeRule> {
        val loaded=FileKnowledgeRepository.load(root.resolve("knowledge"))
        assertTrue(loaded.violations.isEmpty(),loaded.violations.joinToString())
        return loaded.rules
    }

    private fun patch(path:String,line:String)="""
        diff --git a/$path b/$path
        new file mode 100644
        --- /dev/null
        +++ b/$path
        @@ -0,0 +1 @@
        +$line
    """.trimIndent()+"\n"

    private fun checkPatch(root:Path,path:String,line:String):Pair<Int,JsonNode> {
        val file=root.resolve("change.patch")
        Files.writeString(file,patch(path,line))
        val out=ByteArrayOutputStream()
        val code=runCli(listOf(
            "check",root.toString(),"--knowledge",this.root.resolve("knowledge").toString(),
            "--team","20827","--season","2025-2026","--generic-profile",
            "--diff",file.toString(),"--json"
        ),PrintStream(out),StringReader("").buffered())
        return code to mapper.readTree(out.toString())
    }

    @Test
    fun `RookieBot guidance no longer recommends JUnit test source sets`() {
        val selected=rules().filter { it.id in reapproved }
        assertEquals(reapproved,selected.map { it.id }.toSet())
        val forbidden=listOf("org.junit","junit.","testDebugUnitTest","TeamCode/src/test","单元测试")
        selected.forEach { rule ->
            assertEquals(RuleStatus.APPROVED,rule.status,rule.id)
            assertNotNull(rule.approval,rule.id)
            assertEquals("lucasnotfound59",rule.approval!!.approver,rule.id)
            assertEquals(ApproverRole.OVERALL_SOFTWARE_LEAD,rule.approval!!.role,rule.id)
            assertEquals(Instant.parse("2026-09-13T09:12:38Z"),rule.approval!!.approvedAt,rule.id)
            val current=listOf(rule.title,rule.instruction,rule.positiveExample.orEmpty(),rule.negativeExample.orEmpty())
                .joinToString("\n")
            forbidden.forEach { assertFalse(current.contains(it,ignoreCase=true),"${rule.id}: $it") }
        }
        val guide=Files.readString(root.resolve("knowledge/guides/practices/rookiebot-tutorial.md"))
        forbidden.forEach { assertFalse(guide.contains(it,ignoreCase=true),"guide: $it") }
        assertTrue(guide.contains("使用正确的项目类与 FTC API 导入"))
        assertTrue(guide.contains("分别报告构建、机器人测试和真机验证"))
        assertFalse(guide.contains("使用正确的项目类与测试断言导入"))
        assertFalse(guide.contains("分别报告编译、测试和真机验证"))
        assertTrue(guide.contains("teamcode/tests/"))
        assertTrue(guide.contains("teamcode/utils/"))
        assertTrue(guide.contains(":TeamCode:assembleDebug"))
    }

    @Test
    fun `global test utility layout rule has approved cross season hard metadata`() {
        val rule=rules().single { it.id==layoutRuleId }
        assertEquals("test-utility-layout",rule.topic)
        assertEquals("Keep robot tests and utilities in canonical TeamCode packages",rule.title)
        assertEquals(RuleStatus.APPROVED,rule.status)
        assertEquals(RuleAuthority.SHARED,rule.authority)
        assertEquals(PolicyLevel.GLOBAL,rule.policyLevel)
        assertEquals(emptySet<String>(),rule.applicability.teams)
        assertEquals(emptySet<String>(),rule.applicability.seasons)
        assertEquals(emptySet<String>(),rule.applicability.profiles)
        assertNotNull(rule.approval)
        assertEquals("lucasnotfound59",rule.approval!!.approver)
        assertEquals(ApproverRole.OVERALL_SOFTWARE_LEAD,rule.approval!!.role)
        assertEquals(Instant.parse("2026-09-13T09:12:38Z"),rule.approval!!.approvedAt)
        assertEquals(
            listOf(GitRuleEvidence(
                "lucasnotfound59/FTC-Knowledge-Bank",
                "6aa385d75484b22b3f73c3b493a695e753ccc76e",
                "docs/superpowers/specs/2026-09-13-ftc-test-utils-layout-design.md",
                line=8
            )),
            rule.evidence
        )
        assertEquals(
            listOf(
                RuleCheckKind.PATH_FORBIDDEN to "TeamCode/src/{test,androidTest}/**",
                RuleCheckKind.PATH_FORBIDDEN to "TeamCode/src/main/java/{tests,utils}/**",
                RuleCheckKind.PATH_FORBIDDEN to "TeamCode/src/main/java/org/firstinspires/ftc/{tests,utils}/**",
                RuleCheckKind.PATH_FORBIDDEN to "TeamCode/src/main/java/org/firstinspires/ftc/teamcode/{test,util,tool,tools}/**",
                RuleCheckKind.REGEX_FORBIDDEN to "(?i)^\\s*import\\s+(?:static\\s+)?(?:org\\.junit|junit\\.)",
                RuleCheckKind.REGEX_FORBIDDEN to "(?i)\\b(?:testImplementation|androidTestImplementation|testCompile|androidTestCompile)\\b.*(?:org\\.junit|junit:)"
            ),
            rule.checks.map { it.kind to it.pattern }
        )
        assertEquals(emptyList<Any>(),rule.reviewTriggers)
    }

    @Test
    fun `global test utility layout blocks wrong TeamCode paths and JUnit additions`() {
        val forbidden=listOf(
            "TeamCode/src/test/java/org/firstinspires/ftc/teamcode/DriveTest.java" to "class DriveTest {}",
            "TeamCode/src/androidTest/java/org/firstinspires/ftc/teamcode/DriveTest.java" to "class DriveTest {}",
            "TeamCode/src/main/java/tests/DriveTest.java" to "class DriveTest {}",
            "TeamCode/src/main/java/org/firstinspires/ftc/utils/AngleUtils.java" to "class AngleUtils {}",
            "TeamCode/src/main/java/org/firstinspires/ftc/teamcode/tests/DriveTest.java" to "import org.junit.Test;",
            "TeamCode/build.gradle" to "testImplementation 'junit:junit:4.13.2'"
        )
        val allowed=listOf(
            "TeamCode/src/main/java/org/firstinspires/ftc/teamcode/tests/DriveTest.java" to "class DriveTest {}",
            "TeamCode/src/main/java/org/firstinspires/ftc/teamcode/utils/AngleUtils.java" to "class AngleUtils {}",
            "TeamCode/src/main/java/org/firstinspires/ftc/teamcode/subsystems/Drive.java" to "class Drive {}"
        )
        forbidden.forEach { (path,line) ->
            val (code,json)=checkPatch(Files.createTempDirectory("layout-forbidden"),path,line)
            assertEquals(1,code,"$path: $json")
            assertTrue(json["violations"].any { it["ruleId"].asText()==layoutRuleId },"$path: $json")
        }
        allowed.forEach { (path,line) ->
            val (code,json)=checkPatch(Files.createTempDirectory("layout-allowed"),path,line)
            assertEquals(0,code,"$path: $json")
            assertTrue(json["violations"].none { it["ruleId"].asText()==layoutRuleId },"$path: $json")
        }
    }
}
