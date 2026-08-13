package org.ftckb.knowledge

import org.ftckb.domain.RuleAuthority
import org.ftckb.domain.RuleStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class RuleYamlCodecTest {
    @Test
    fun `decodes canonical candidate rule`() {
        val yaml="""
            schemaVersion: 1
            rules:
              - id: shared.ftclib-command
                topic: command-framework
                title: Use FTCLib Command
                instruction: Use FTCLib Command for scheduled robot actions.
                rationale: Both reference repositories use the library.
                status: candidate
                authority: shared
                applicability:
                  teams: []
                  seasons: [2025-2026]
                evidence:
                  - repository: xiaokai-lyk/FTC20827-2026Decode
                    commit: 118c28e137334bbbea510d77f1fa384e8b1b5779
                    file: TeamCode/build.gradle
                    line: 28
        """.trimIndent()

        val rule=RuleYamlCodec.decode(yaml).single()
        assertEquals("shared.ftclib-command",rule.id)
        assertEquals(RuleStatus.CANDIDATE,rule.status)
        assertEquals(RuleAuthority.SHARED,rule.authority)
        assertEquals(setOf("2025-2026"),rule.applicability.seasons)
    }

    @Test
    fun `rejects unknown rule fields`() {
        val yaml="""
            schemaVersion: 1
            rules:
              - id: shared.test
                topic: test
                title: Test
                instruction: Test instruction.
                rationale: Test rationale.
                status: candidate
                authority: shared
                applicability: {}
                evidence: []
                typo: ignored
        """.trimIndent()

        val exception=assertThrows(IllegalStateException::class.java) { RuleYamlCodec.decode(yaml) }

        assertEquals("rules[0] contains unknown fields: typo",exception.message)
    }

    @Test
    fun `rejects unknown root fields`() {
        val yaml="""
            schemaVersion: 1
            rules: []
            typo: ignored
        """.trimIndent()

        val exception=assertThrows(IllegalStateException::class.java) { RuleYamlCodec.decode(yaml) }

        assertEquals("root contains unknown fields: typo",exception.message)
    }

    @Test
    fun `rejects unknown applicability fields`() {
        val yaml="""
            schemaVersion: 1
            rules:
              - id: shared.test
                topic: test
                title: Test
                instruction: Test instruction.
                rationale: Test rationale.
                status: candidate
                authority: shared
                applicability:
                  teams: []
                  typo: ignored
                evidence: []
        """.trimIndent()

        val exception=assertThrows(IllegalStateException::class.java) { RuleYamlCodec.decode(yaml) }

        assertEquals("rules[0].applicability contains unknown fields: typo",exception.message)
    }

    @Test
    fun `rejects unknown evidence fields`() {
        val yaml="""
            schemaVersion: 1
            rules:
              - id: shared.test
                topic: test
                title: Test
                instruction: Test instruction.
                rationale: Test rationale.
                status: candidate
                authority: shared
                applicability: {}
                evidence:
                  - repository: owner/repo
                    commit: abcdef1
                    file: TeamCode/build.gradle
                    line: 1
                    typo: ignored
        """.trimIndent()

        val exception=assertThrows(IllegalStateException::class.java) { RuleYamlCodec.decode(yaml) }

        assertEquals("rules[0].evidence[0] contains unknown fields: typo",exception.message)
    }

    @Test
    fun `rejects unknown approval fields`() {
        val yaml="""
            schemaVersion: 1
            rules:
              - id: shared.test
                topic: test
                title: Test
                instruction: Test instruction.
                rationale: Test rationale.
                status: approved
                authority: shared
                applicability: {}
                evidence: []
                approval:
                  approver: overall
                  role: overall_software_lead
                  approvedAt: 1970-01-01T00:00:00Z
                  typo: ignored
        """.trimIndent()

        val exception=assertThrows(IllegalStateException::class.java) { RuleYamlCodec.decode(yaml) }

        assertEquals("rules[0].approval contains unknown fields: typo",exception.message)
    }

    @Test
    fun `rejects unsupported schema versions`() {
        val exception=assertThrows(IllegalArgumentException::class.java) {
            RuleYamlCodec.decode("schemaVersion: 2\nrules: []")
        }

        assertEquals("unsupported schemaVersion",exception.message)
    }

    @Test
    fun `rejects duplicate keys`() {
        assertThrows(RuntimeException::class.java) {
            RuleYamlCodec.decode("schemaVersion: 1\nschemaVersion: 1\nrules: []")
        }
    }

    @Test
    fun `rejects arbitrary object tags`() {
        assertThrows(RuntimeException::class.java) {
            RuleYamlCodec.decode("!!java.net.URL [https://example.com]")
        }
    }

    @Test
    fun `rejects wrong optional scalar types`() {
        val yaml="""
            schemaVersion: 1
            rules:
              - id: shared.test
                topic: test
                title: Test
                instruction: Test instruction.
                rationale: Test rationale.
                status: candidate
                authority: shared
                applicability: {}
                evidence: []
                positiveExample: 42
        """.trimIndent()

        val exception=assertThrows(IllegalStateException::class.java) { RuleYamlCodec.decode(yaml) }

        assertEquals("positiveExample must be a string",exception.message)
    }
}
