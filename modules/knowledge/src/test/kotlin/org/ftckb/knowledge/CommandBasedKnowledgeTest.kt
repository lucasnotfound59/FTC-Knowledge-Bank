package org.ftckb.knowledge

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import org.ftckb.domain.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CommandBasedKnowledgeTest {
    private val ids=setOf(
        "shared.command-runtime-boundary",
        "shared.command-scheduler-lifecycle",
        "shared.command-default-resumption",
        "shared.command-scheduler-reentrancy"
    )
    private fun knowledgeRoot():Path=generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .map { it.resolve("knowledge") }
        .first { Files.isRegularFile(it.resolve("shared/command-framework.yaml")) }
    private fun loaded()=FileKnowledgeRepository.load(knowledgeRoot()).also {
        assertTrue(it.violations.isEmpty(),it.violations.toString())
    }.rules
    private fun proposed()=loaded().filter { it.id in ids }.also {
        assertEquals(ids,it.map { rule -> rule.id }.toSet())
    }
    // Synthetic approval never leaves memory or changes the real knowledge files.
    private fun approved()=proposed().map { it.copy(
        status=RuleStatus.APPROVED,
        approval=Approval("fixture-reviewer",ApproverRole.OVERALL_SOFTWARE_LEAD,
            approvedAt=Instant.parse("2026-01-01T00:00:00Z"))
    ) }

    @Test
    fun `real approved command policies resolve without fabricated approval`() {
        val rules=loaded()
        val proposals=proposed()
        assertTrue(Files.readString(knowledgeRoot().resolve("shared/command-framework.yaml"))
            .lineSequence().any { it=="schemaVersion: 4" })
        proposals.forEach {
            assertEquals(RuleStatus.APPROVED,it.status)
            assertNotNull(it.approval)
            assertEquals(RuleAuthority.SHARED,it.authority)
            assertEquals(PolicyLevel.SHARED,it.policyLevel)
            assertEquals(RuleApplicability(profiles=setOf("command-based")),it.applicability)
            assertTrue(it.checks.isEmpty())
            assertTrue(it.reviewTriggers.isNotEmpty())
            assertTrue(it.reviewTriggers.all { trigger -> trigger.paths.all { path ->
                path.startsWith("TeamCode/src/main/java/") && path.endsWith(".java")
            } })
        }
        val result=RuleResolver.resolve(rules,RuleContext("20827","2026-2027",setOf("command-based")))
        assertTrue(result.conflicts.isEmpty())
        assertEquals(ids,result.activeRules.filter { it.id in ids }.map { it.id }.toSet())
    }

    @Test
    fun `unapproved copies remain excluded and never borrow real rule approvals`() {
        val candidates=proposed().map { it.copy(status=RuleStatus.CANDIDATE,approval=null) }
        val result=RuleResolver.resolve(candidates,RuleContext("20827","2026-2027",setOf("command-based")))
        assertTrue(result.activeRules.isEmpty())
        assertEquals(ids,result.excludedRules.map { it.ruleId }.toSet())
        assertTrue(result.excludedRules.all { it.reasons==listOf("status") })
    }

    @Test
    fun `isolated approval covers both seasons and FTCLib inheritance but no generic or simple architecture`() {
        val rules=approved()
        for (team in listOf("20827","20287")) for (season in listOf("2025-2026","2026-2027")) {
            for (profile in listOf("command-based","ftclib-command")) {
                val result=RuleResolver.resolve(rules,RuleContext(team,season,setOf(profile)))
                assertTrue(result.conflicts.isEmpty())
                assertEquals(ids,result.activeRules.map { it.id }.toSet())
                assertTrue("command-based" in result.profiles)
            }
            for (profiles in listOf(emptySet(),setOf("simple-opmode"),setOf("rookiebot"))) {
                val result=RuleResolver.resolve(rules,RuleContext(team,season,profiles))
                assertTrue(result.activeRules.isEmpty())
                assertEquals(ids,result.excludedRules.map { it.ruleId }.toSet())
                assertTrue(result.excludedRules.all { it.reasons==listOf("profile") })
            }
        }
    }

    @Test
    fun `explicit test mode preserves exactly the existing architecture exemptions and conditional safety proposals`() {
        val rules=loaded().filter { it.id !in ids }+approved()
        val result=RuleResolver.resolve(rules,RuleContext("20827","2025-2026",setOf("ftclib-command"),WorkMode.TEST))
        assertEquals(setOf("global.command-responsibilities","shared.ftclib-command-candidate"),
            result.excludedRules.filter { WorkMode.TEST_EXCLUSION_REASON in it.reasons }.map { it.ruleId }.toSet())
        assertEquals(ids,result.activeRules.filter { it.id in ids }.map { it.id }.toSet())
        assertTrue(result.conflicts.isEmpty())
    }
}
