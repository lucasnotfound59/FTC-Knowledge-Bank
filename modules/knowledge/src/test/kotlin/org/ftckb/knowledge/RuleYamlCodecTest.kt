package org.ftckb.knowledge

import java.time.LocalDate
import org.ftckb.domain.GitRuleEvidence
import org.ftckb.domain.RuleAuthority
import org.ftckb.domain.RuleStatus
import org.ftckb.domain.WebRuleEvidence
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
            RuleYamlCodec.decode("schemaVersion: 3\nrules: []")
        }

        assertEquals("unsupported schemaVersion",exception.message)
    }

    @Test
    fun `decodes schema two git and web evidence`() {
        val yaml="""
            schemaVersion: 2
            rules:
              - id: shared.typed-evidence
                topic: typed-evidence
                title: Typed evidence
                instruction: Use typed evidence.
                rationale: Sources need distinct validation.
                status: candidate
                authority: shared
                applicability: {}
                evidence:
                  - type: git
                    repository: owner/repo
                    commit: abcdef1
                    file: TeamCode/build.gradle
                    line: 1
                  - type: web
                    url: https://docs.example.org/tool
                    title: Tool documentation
                    publisher: Example
                    accessedAt: 2026-08-13
                    section: Installation
                    version: "2.0"
                    product: Example Tool
                    sku: EX-200
        """.trimIndent()

        val evidence=RuleYamlCodec.decode(yaml).single().evidence

        assertEquals(GitRuleEvidence("owner/repo","abcdef1","TeamCode/build.gradle",line=1),evidence[0])
        assertEquals(
            WebRuleEvidence(
                "https://docs.example.org/tool","Tool documentation","Example",
                LocalDate.parse("2026-08-13"),"Installation","2.0","Example Tool","EX-200"
            ),
            evidence[1]
        )
    }

    @Test
    fun `schema two requires known strict evidence types`() {
        val cases=listOf(
            typedCandidate("repository: owner/repo\ncommit: abcdef1\nfile: README.md\nline: 1") to
                "type must be a string",
            typedCandidate("type: video\nurl: https://example.org") to
                "unsupported evidence type: video",
            typedCandidate("type: git\nrepository: owner/repo\ncommit: abcdef1\nfile: README.md\nline: 1\nurl: https://example.org") to
                "rules[0].evidence[0] contains unknown fields: url",
            typedCandidate("type: web\nurl: https://example.org\ntitle: Example\npublisher: Example\naccessedAt: 2026-08-13\nsection: Test\ncommit: abcdef1") to
                "rules[0].evidence[0] contains unknown fields: commit",
            typedCandidate("type: web\nurl: https://example.org\ntitle: Example\npublisher: Example\naccessedAt: yesterday\nsection: Test") to
                "accessedAt must use YYYY-MM-DD"
        )

        cases.forEach { (yaml,message) ->
            val exception=assertThrows(IllegalStateException::class.java) { RuleYamlCodec.decode(yaml) }
            assertEquals(message,exception.message)
        }
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

    @Test
    fun `distinguishes missing optional collections from invalid present collections`() {
        val invalidDocuments=listOf(
            "schemaVersion: 1" to "rules must be a list",
            "schemaVersion: 1\nrules: null" to "rules must be a list",
            "schemaVersion: 1\nrules: wrong" to "rules must be a list",
            candidateYaml(evidence=null) to "evidence must be a list",
            candidateYaml(evidence="null") to "evidence must be a list",
            candidateYaml(evidence="wrong") to "evidence must be a list",
            candidateYaml(applicability="null") to "applicability must be a map",
            candidateYaml(applicability="wrong") to "applicability must be a map",
            candidateYaml(applicability="{teams: null}") to "teams must be a list",
            candidateYaml(applicability="{teams: wrong}") to "teams must be a list",
            candidateYaml(applicability="{seasons: null}") to "seasons must be a list",
            candidateYaml(applicability="{seasons: wrong}") to "seasons must be a list",
            candidateYaml(extra="approval: null") to "approval must be a map"
        )

        invalidDocuments.forEach { (yaml,message) ->
            val exception=assertThrows(IllegalStateException::class.java) { RuleYamlCodec.decode(yaml) }
            assertEquals(message,exception.message)
        }
    }

    @Test
    fun `rejects non integral out of range and non numeric integers`() {
        val invalidDocuments=listOf(
            "schemaVersion: 1.5\nrules: []" to "schemaVersion must be an integer",
            "schemaVersion: 4294967297\nrules: []" to "schemaVersion must be an integer",
            candidateYamlWithLine("null") to "line must be an integer",
            candidateYamlWithLine("1.5") to "line must be an integer",
            candidateYamlWithLine("4294967297") to "line must be an integer",
            candidateYamlWithLine("\"28\"",symbol="test") to "line must be an integer"
        )

        invalidDocuments.forEach { (yaml,message) ->
            val exception=assertThrows(IllegalStateException::class.java) { RuleYamlCodec.decode(yaml) }
            assertEquals(message,exception.message)
        }
    }

    private fun candidateYaml(
        applicability:String="{}",
        evidence:String?="[]",
        extra:String=""
    )="""
        schemaVersion: 1
        rules:
          - id: shared.test
            topic: test
            title: Test
            instruction: Test instruction.
            rationale: Test rationale.
            status: candidate
            authority: shared
            applicability: $applicability
            ${evidence?.let { "evidence: $it" } ?: ""}
            $extra
    """.trimIndent()

    private fun candidateYamlWithLine(line:String,symbol:String?=null)="""
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
                ${symbol?.let { "symbol: $it" } ?: ""}
                line: $line
    """.trimIndent()

    private fun typedCandidate(evidence:String)="""
        schemaVersion: 2
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
              - ${evidence.replace("\n","\n                ")}
    """.trimIndent()
}
