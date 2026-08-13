package org.ftckb.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class MainTest {
    private val knowledgeRoot=Path.of("..","..","knowledge").normalize()

    @Test
    fun `validate reports rule counts`() {
        val output=ByteArrayOutputStream()
        val code=runCli(listOf("validate",knowledgeRoot.toString()),PrintStream(output))
        assertEquals(0,code)
        assertTrue(output.toString().contains("validation=ok"))
    }

    @Test
    fun `resolve prints active IDs for team and season`() {
        val output=ByteArrayOutputStream()
        val code=runCli(listOf("resolve",knowledgeRoot.toString(),"--team","20827","--season","2025-2026"),PrintStream(output))
        assertEquals(0,code)
        val text=output.toString()
        assertTrue(text.contains("official.keep-customizations-in-teamcode"))
        assertFalse(text.contains("shared.ftclib-command-candidate"))
        assertFalse(text.contains("team-20827.hardware-layer-candidate"))
        assertFalse(text.contains("team-16093.fsm-candidate"))
    }

    @Test
    fun `unknown command is rejected before loading root`() {
        val output=ByteArrayOutputStream()
        val code=runCli(listOf("unknown","does-not-exist"),PrintStream(output))
        assertEquals(64,code)
        assertEquals("unknown command: unknown\n",output.toString())
    }

    @Test
    fun `resolve missing flags is rejected before loading root`() {
        val output=ByteArrayOutputStream()
        val code=runCli(listOf("resolve","does-not-exist"),PrintStream(output))
        assertEquals(64,code)
        assertEquals("missing --team\n",output.toString())
    }

    @Test
    fun `validate rejects trailing arguments before loading root`() {
        val output=ByteArrayOutputStream()
        val code=runCli(listOf("validate","does-not-exist","extra"),PrintStream(output))
        assertEquals(64,code)
        assertEquals("validate accepts exactly one knowledge root\n",output.toString())
    }

    @Test
    fun `resolve rejects odd option arguments before loading root`() {
        val output=ByteArrayOutputStream()
        val code=runCli(listOf("resolve","does-not-exist","--team","20827","--season"),PrintStream(output))
        assertEquals(64,code)
        assertEquals("resolve options must be flag-value pairs\n",output.toString())
    }

    @Test
    fun `resolve rejects unknown options before loading root`() {
        val output=ByteArrayOutputStream()
        val code=runCli(
            listOf("resolve","does-not-exist","--team","20827","--season","2025-2026","--extra","x"),
            PrintStream(output)
        )
        assertEquals(64,code)
        assertEquals("unknown resolve option: --extra\n",output.toString())
    }

    @Test
    fun `resolve rejects duplicate options before loading root`() {
        val output=ByteArrayOutputStream()
        val code=runCli(
            listOf("resolve","does-not-exist","--team","20827","--season","2025-2026","--team","16093"),
            PrintStream(output)
        )
        assertEquals(64,code)
        assertEquals("duplicate resolve option: --team\n",output.toString())
    }

    @Test
    fun `resolve rejects empty option values before loading root`() {
        val output=ByteArrayOutputStream()
        val code=runCli(
            listOf("resolve","does-not-exist","--team","","--season","2025-2026"),
            PrintStream(output)
        )
        assertEquals(64,code)
        assertEquals("empty value for --team\n",output.toString())
    }

    @Test
    fun `resolve rejects noncanonical team values before loading root`() {
        listOf(" \t","team-20827").forEach { team ->
            val output=ByteArrayOutputStream()
            val code=runCli(
                listOf("resolve","does-not-exist","--team",team,"--season","2025-2026"),
                PrintStream(output)
            )

            assertEquals(64,code,"team=$team")
            assertEquals("invalid value for --team: expected digits only\n",output.toString(),"team=$team")
        }
    }

    @Test
    fun `resolve rejects noncanonical season values before loading root`() {
        listOf(" \t","2025-26").forEach { season ->
            val output=ByteArrayOutputStream()
            val code=runCli(
                listOf("resolve","does-not-exist","--team","20827","--season",season),
                PrintStream(output)
            )

            assertEquals(64,code,"season=$season")
            assertEquals("invalid value for --season: expected YYYY-YYYY\n",output.toString(),"season=$season")
        }
    }

    @Test
    fun `resolve rejects flag as value before loading root`() {
        val output=ByteArrayOutputStream()
        val code=runCli(
            listOf("resolve","does-not-exist","--team","--season","--season","2025-2026"),
            PrintStream(output)
        )
        assertEquals(64,code)
        assertEquals("invalid value for --team: --season\n",output.toString())
    }

    @Test
    fun `resolve rejects flag as value with real root`() {
        val output=ByteArrayOutputStream()
        val code=runCli(
            listOf("resolve",knowledgeRoot.toString(),"--team","--season","--season","2025-2026"),
            PrintStream(output)
        )
        assertEquals(64,code)
        assertEquals("invalid value for --team: --season\n",output.toString())
    }

    @Test
    fun `resolve accepts reversed flag order`() {
        val output=ByteArrayOutputStream()
        val code=runCli(
            listOf("resolve",knowledgeRoot.toString(),"--season","2025-2026","--team","20827"),
            PrintStream(output)
        )
        assertEquals(0,code)
        assertEquals(
            """
            active official.keep-customizations-in-teamcode
            active shared.dashboard-pin-stable-dependency
            active shared.dependency-verify-sync-build-run
            active shared.ftc-sdk-pin-release
            active shared.ftc-sdk-preserve-build-tooling
            active shared.ftc-sdk-separate-toolchain-versions
            active shared.ftclib-check-current-prerequisites
            active shared.ftclib-pin-module-versions
            active shared.gobilda-identify-exact-sku
            active shared.gobilda-separate-stall-and-operating-values
            active shared.gobilda-servo-mode-and-pwm-range
            active shared.gobilda-use-output-shaft-encoder-resolution
            active shared.limelight-back-up-before-os-update
            active shared.limelight-check-result-validity
            active shared.limelight-configure-camera-pose
            active shared.limelight-enforce-freshness-policy
            active shared.limelight-synchronize-pipeline-dependent-reads
            active shared.pedro-explicit-coordinate-conversion
            active shared.pedro-localization-before-follower
            active shared.pedro-tune-current-robot
            """.trimIndent()+"\n",
            output.toString()
        )
    }

    @Test
    fun `resolve missing season is rejected before loading root`() {
        val output=ByteArrayOutputStream()
        val code=runCli(listOf("resolve","does-not-exist","--team","20827"),PrintStream(output))
        assertEquals(64,code)
        assertEquals("missing --season\n",output.toString())
    }

    @Test
    fun `invalid YAML is a controlled load failure`(@TempDir root:Path) {
        Files.writeString(root.resolve("invalid.yaml"),"not-a-map")
        val output=ByteArrayOutputStream()
        val code=runCli(listOf("validate",root.toString()),PrintStream(output))
        assertEquals(2,code)
        assertEquals("error loading knowledge: root must be a map\n",output.toString())
    }

    @Test
    fun `validation violations return exit two`(@TempDir root:Path) {
        Files.writeString(root.resolve("rules.yaml"),"""
            schemaVersion: 1
            rules:
              - id: shared.invalid-commit
                topic: test-topic
                title: Test rule
                instruction: Test instruction.
                rationale: Test rationale.
                status: candidate
                authority: shared
                applicability:
                  teams: []
                  seasons: []
                evidence:
                  - repository: owner/repository
                    commit: invalid
                    file: TeamCode/Test.java
                    symbol: Test
        """.trimIndent())
        val output=ByteArrayOutputStream()
        val code=runCli(listOf("validate",root.toString()),PrintStream(output))
        assertEquals(2,code)
        assertEquals(
            "error rule=shared.invalid-commit field=evidence[0].commit message=commit must be a Git SHA\n",
            output.toString()
        )
    }

    @Test
    fun `resolution conflicts return exit two`(@TempDir root:Path) {
        Files.writeString(root.resolve("rules.yaml"),"""
            schemaVersion: 1
            rules:
              - id: shared.one
                topic: conflicting-topic
                title: First rule
                instruction: First instruction.
                rationale: First rationale.
                status: approved
                authority: shared
                applicability:
                  teams: []
                  seasons: []
                evidence:
                  - repository: owner/repository
                    commit: abcdef1
                    file: TeamCode/First.java
                    symbol: First
                approval:
                  approver: overall-software-lead
                  role: overall_software_lead
                  approvedAt: 2026-08-13T00:00:00Z
              - id: shared.two
                topic: conflicting-topic
                title: Second rule
                instruction: Second instruction.
                rationale: Second rationale.
                status: approved
                authority: shared
                applicability:
                  teams: []
                  seasons: []
                evidence:
                  - repository: owner/repository
                    commit: abcdef2
                    file: TeamCode/Second.java
                    symbol: Second
                approval:
                  approver: overall-software-lead
                  role: overall_software_lead
                  approvedAt: 2026-08-13T00:00:00Z
        """.trimIndent())
        val output=ByteArrayOutputStream()
        val code=runCli(
            listOf("resolve",root.toString(),"--team","20827","--season","2025-2026"),
            PrintStream(output)
        )
        assertEquals(2,code)
        assertEquals("conflict topic=conflicting-topic rules=shared.one,shared.two\n",output.toString())
    }

    @Test
    fun `resolve rejects a team topic that differs from an official topic only by trailing whitespace`(@TempDir root:Path) {
        Files.writeString(root.resolve("rules.yaml"),"""
            schemaVersion: 1
            rules:
              - id: official.deploy
                topic: deployment-safety
                title: Official deployment safety
                instruction: Keep deployment safe.
                rationale: Official constraints cannot be overridden.
                status: approved
                authority: official
                applicability: {}
                evidence:
                  - repository: owner/repository
                    commit: abcdef1
                    file: README.md
                    line: 1
                approval:
                  approver: overall-software-lead
                  role: overall_software_lead
                  approvedAt: 2026-08-13T00:00:00Z
              - id: team.deploy
                topic: "deployment-safety "
                title: Team deployment rule
                instruction: Replace the official constraint.
                rationale: This must never become a separate topic.
                status: approved
                authority: team
                applicability:
                  teams: ["20827"]
                  seasons: [2025-2026]
                evidence:
                  - repository: owner/repository
                    commit: abcdef2
                    file: TeamCode/Deploy.java
                    symbol: Deploy
                approval:
                  approver: lead-20827
                  role: team_software_lead
                  team: "20827"
                  approvedAt: 2026-08-13T00:00:00Z
        """.trimIndent())
        val output=ByteArrayOutputStream()

        val code=runCli(
            listOf("resolve",root.toString(),"--team","20827","--season","2025-2026"),
            PrintStream(output)
        )

        assertEquals(2,code)
        assertEquals(
            "error rule=team.deploy field=topic message=topic must be a canonical slug\n",
            output.toString()
        )
    }
}
