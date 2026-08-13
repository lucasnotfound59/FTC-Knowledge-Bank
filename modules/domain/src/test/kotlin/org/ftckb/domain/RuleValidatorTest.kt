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

    @Test
    fun `topic must use its exact canonical slug`() {
        val canonical=KnowledgeRule(
            id="shared.deployment-safety",
            topic="deployment-safety",
            title="Deployment safety",
            instruction="Keep deployment safe.",
            rationale="Safety rules must not be bypassed.",
            status=RuleStatus.CANDIDATE,
            authority=RuleAuthority.SHARED,
            applicability=RuleApplicability(),
            evidence=listOf(evidence)
        )
        assertTrue(RuleValidator.validate(canonical).isEmpty())

        listOf(
            " deployment-safety",
            "deployment-safety ",
            "deployment safety",
            "deployment\tsafety",
            "Deployment-safety",
            "deployment\u00A0safety"
        ).forEach { topic ->
            assertEquals(
                listOf("topic must be a canonical slug"),
                RuleValidator.validate(canonical.copy(topic=topic)).map { it.message },
                "topic=$topic"
            )
        }
    }

    @Test
    fun `evidence file accepts only safe slash separated repository relative paths`() {
        val canonical=KnowledgeRule(
            id="shared.safe-evidence",
            topic="safe-evidence",
            title="Safe evidence",
            instruction="Use repository relative evidence paths.",
            rationale="Evidence must remain inside its repository.",
            status=RuleStatus.CANDIDATE,
            authority=RuleAuthority.SHARED,
            applicability=RuleApplicability(),
            evidence=listOf(evidence)
        )
        listOf(
            "TeamCode/src/main/Example.java",
            "README.md",
            "build.common.gradle"
        ).forEach { file ->
            assertTrue(
                RuleValidator.validate(canonical.copy(evidence=listOf(evidence.copy(file=file)))).isEmpty(),
                "file=$file"
            )
        }

        listOf(
            "..\\secret",
            "C:\\secret",
            "C:/secret",
            "\\\\server\\share",
            "/secret",
            "a//b",
            "./a",
            "a/../b",
            "a\u0000b"
        ).forEach { file ->
            assertEquals(
                listOf("file must be a safe repository relative path using / separators"),
                RuleValidator.validate(canonical.copy(evidence=listOf(evidence.copy(file=file)))).map { it.message },
                "file=$file"
            )
        }
    }

    @Test
    fun `approval approver must not be blank`() {
        val rule=KnowledgeRule(
            id="shared.blank-approver",
            topic="approval-identity",
            title="Approval identity",
            instruction="Record an approver identity.",
            rationale="Approvals must be attributable.",
            status=RuleStatus.APPROVED,
            authority=RuleAuthority.SHARED,
            applicability=RuleApplicability(),
            evidence=listOf(evidence),
            approval=Approval(" \t",ApproverRole.OVERALL_SOFTWARE_LEAD,approvedAt=Instant.EPOCH)
        )

        assertEquals(
            listOf("approver must not be blank"),
            RuleValidator.validate(rule).map { it.message }
        )
    }

    @Test
    fun `teams seasons and approval team require canonical identities`() {
        val candidate=KnowledgeRule(
            id="team.canonical-context",
            topic="canonical-context",
            title="Canonical context",
            instruction="Use canonical team and season identities.",
            rationale="Matching must be exact.",
            status=RuleStatus.CANDIDATE,
            authority=RuleAuthority.TEAM,
            applicability=RuleApplicability(teams=setOf("20827"),seasons=setOf("2025-2026")),
            evidence=listOf(evidence)
        )
        assertTrue(RuleValidator.validate(candidate).isEmpty())

        listOf(""," ","team-20827","２０８２７").forEach { team ->
            assertEquals(
                listOf("applicable team must contain digits only"),
                RuleValidator.validate(
                    candidate.copy(applicability=RuleApplicability(teams=setOf(team),seasons=setOf("2025-2026")))
                ).map { it.message },
                "team=$team"
            )
        }
        listOf(""," ","2025","2025/2026","２０２５-２０２６").forEach { season ->
            assertEquals(
                listOf("season must use YYYY-YYYY"),
                RuleValidator.validate(
                    candidate.copy(applicability=RuleApplicability(teams=setOf("20827"),seasons=setOf(season)))
                ).map { it.message },
                "season=$season"
            )
        }

        val approvedShared=candidate.copy(
            id="shared.canonical-approval-team",
            authority=RuleAuthority.SHARED,
            applicability=RuleApplicability(),
            status=RuleStatus.APPROVED,
            approval=Approval("overall-software-lead",ApproverRole.OVERALL_SOFTWARE_LEAD,approvedAt=Instant.EPOCH)
        )
        listOf(""," ","team-20827").forEach { team ->
            assertEquals(
                listOf("approval team must contain digits only"),
                RuleValidator.validate(approvedShared.copy(approval=approvedShared.approval!!.copy(team=team))).map { it.message },
                "approval team=$team"
            )
        }
    }

    @Test
    fun `knowledge rule snapshots mutable evidence at construction and copy`() {
        val constructorEvidence=mutableListOf(evidence)
        val constructed=KnowledgeRule(
            id="shared.evidence-snapshot",
            topic="evidence-snapshot",
            title="Evidence snapshot",
            instruction="Keep rule evidence stable.",
            rationale="Validated evidence must not change through aliases.",
            status=RuleStatus.CANDIDATE,
            authority=RuleAuthority.SHARED,
            applicability=RuleApplicability(),
            evidence=constructorEvidence
        )
        val copyEvidence=mutableListOf(evidence)
        val copied=constructed.copy(evidence=copyEvidence)
        assertTrue(RuleValidator.validate(constructed).isEmpty())
        assertTrue(RuleValidator.validate(copied).isEmpty())

        constructorEvidence.clear()
        copyEvidence.clear()

        assertEquals(listOf(evidence),constructed.evidence)
        assertEquals(listOf(evidence),copied.evidence)
        assertTrue(RuleValidator.validate(constructed).isEmpty())
        assertTrue(RuleValidator.validate(copied).isEmpty())
    }
}
