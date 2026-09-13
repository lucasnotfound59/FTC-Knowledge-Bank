package org.ftckb.cli

import com.fasterxml.jackson.databind.json.JsonMapper
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.io.StringReader
import java.nio.file.Files
import java.nio.file.Path
import org.ftckb.domain.PolicyLevel
import org.ftckb.domain.RuleAuthority
import org.ftckb.domain.RuleStatus
import org.ftckb.knowledge.FileKnowledgeRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class LimelightSoftRuleAcceptanceTest {
    private val root=Path.of("..","..")
    private val mapper=JsonMapper.builder().build()
    private val targetIds=setOf(
        "shared.limelight-check-result-validity",
        "shared.limelight-enforce-freshness-policy"
    )

    private fun rules()=FileKnowledgeRepository.load(root.resolve("knowledge")).also {
        assertTrue(it.violations.isEmpty(),it.violations.joinToString())
    }.rules

    private fun check(patch:Path):Pair<Int,String> {
        val out=ByteArrayOutputStream()
        val code=runCli(
            listOf(
                "check",patch.parent.toString(),"--knowledge",root.resolve("knowledge").toString(),
                "--team","20827","--season","2025-2026","--generic-profile",
                "--diff",patch.toString(),"--json"
            ),
            PrintStream(out),StringReader("").buffered()
        )
        return code to out.toString()
    }

    @Test
    fun `approved Limelight review rules retain metadata and are triggered soft rules`() {
        val rules=rules().filter { it.id in targetIds }

        assertEquals(targetIds,rules.map { it.id }.toSet())
        for (rule in rules) {
            assertEquals(RuleStatus.APPROVED,rule.status)
            assertEquals(RuleAuthority.SHARED,rule.authority)
            assertEquals(PolicyLevel.SHARED,rule.policyLevel)
            assertNotNull(rule.approval)
            assertTrue(rule.evidence.isNotEmpty())
            assertTrue(rule.checks.isEmpty())
            assertTrue(rule.reviewTriggers.isNotEmpty())
            assertEquals(emptySet<String>(),rule.applicability.teams)
            assertEquals(emptySet<String>(),rule.applicability.seasons)
            assertEquals(emptySet<String>(),rule.applicability.profiles)
        }
    }

    @Test
    fun `unrelated Java patch neither violates nor prompts Limelight review`(@TempDir temp:Path) {
        val patch=temp.resolve("unrelated.patch")
        Files.writeString(patch,"""
            diff --git a/TeamCode/src/main/java/example/DriveSubsystem.java b/TeamCode/src/main/java/example/DriveSubsystem.java
            new file mode 100644
            --- /dev/null
            +++ b/TeamCode/src/main/java/example/DriveSubsystem.java
            @@ -0,0 +1 @@
            +motor.setPower(power);
        """.trimIndent()+"\n")

        val (code,out)=check(patch)
        val node=mapper.readTree(out)
        assertEquals(0,code,out)
        assertFalse(node["violations"].map { it["ruleId"].asText() }.any { it in targetIds },out)
        assertEquals(emptyList<String>(),node["soft"].map { it["ruleId"].asText() }.filter { it in targetIds })
    }

    @Test
    fun `Limelight result patch produces one soft review per target rule`(@TempDir temp:Path) {
        val patch=temp.resolve("limelight.patch")
        Files.writeString(patch,"""
            diff --git a/TeamCode/src/main/java/example/Vision.java b/TeamCode/src/main/java/example/Vision.java
            new file mode 100644
            --- /dev/null
            +++ b/TeamCode/src/main/java/example/Vision.java
            @@ -0,0 +1 @@
            +LLResult result=limelight.getLatestResult();
        """.trimIndent()+"\n")

        val (code,out)=check(patch)
        val node=mapper.readTree(out)
        assertEquals(0,code,out)
        assertFalse(node["violations"].map { it["ruleId"].asText() }.any { it in targetIds },out)
        assertEquals(targetIds.sorted(),node["soft"].map { it["ruleId"].asText() }.filter { it in targetIds })
    }
}
