package org.ftckb.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class RuleValidatorTest {
    private val evidence=RuleEvidence(
        repository="xiaokai-lyk/FTC20827-2026Decode",
        commit="118c28e137334bbbea510d77f1fa384e8b1b5779",
        file="TeamCode/src/main/java/org/firstinspires/ftc/teamcode/Hardwares.java",
        symbol="Hardwares"
    )

    @Test
    fun `approved rule requires approval metadata`() {
        val rule=KnowledgeRule(
            id="shared.hardware-access",
            topic="hardware-access",
            title="Centralize hardware access",
            instruction="Access configured devices through the team hardware layer.",
            rationale="Keeps names and initialization in one place.",
            status=RuleStatus.APPROVED,
            authority=RuleAuthority.SHARED,
            applicability=RuleApplicability(),
            evidence=listOf(evidence)
        )

        assertEquals(listOf("approved rule requires approval"),RuleValidator.validate(rule).map { it.message })
    }

    @Test
    fun `candidate requires evidence but not approval`() {
        val rule=KnowledgeRule(
            id="candidate.ftclib-command",
            topic="command-framework",
            title="Use FTCLib Command",
            instruction="Use FTCLib Command for scheduled robot actions.",
            rationale="Both reference repositories use FTCLib Command.",
            status=RuleStatus.CANDIDATE,
            authority=RuleAuthority.SHARED,
            applicability=RuleApplicability(seasons=setOf("2025-2026")),
            evidence=listOf(evidence)
        )

        assertTrue(RuleValidator.validate(rule).isEmpty())
    }

    @Test
    fun `team rule requires at least one team`() {
        val rule=KnowledgeRule(
            id="team.hardware-layer",
            topic="hardware-access",
            title="Use Hardwares",
            instruction="Use Hardwares for configured devices.",
            rationale="Team-specific architecture.",
            status=RuleStatus.CANDIDATE,
            authority=RuleAuthority.TEAM,
            applicability=RuleApplicability(),
            evidence=listOf(evidence)
        )

        assertEquals(listOf("team rule requires an applicable team"),RuleValidator.validate(rule).map { it.message })
    }

    @Test
    fun `approved team rule rejects overall lead approval`() {
        val rule=KnowledgeRule(
            id="team.hardware-layer",
            topic="hardware-access",
            title="Use Hardwares",
            instruction="Use Hardwares for configured devices.",
            rationale="Team-specific architecture.",
            status=RuleStatus.APPROVED,
            authority=RuleAuthority.TEAM,
            applicability=RuleApplicability(teams=setOf("20827")),
            evidence=listOf(evidence),
            approval=Approval("overall",ApproverRole.OVERALL_SOFTWARE_LEAD,approvedAt=Instant.EPOCH)
        )

        assertEquals(listOf("approval is not authorized for rule authority and teams"),RuleValidator.validate(rule).map { it.message })
    }
}
