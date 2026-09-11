package org.ftckb.knowledge

import java.time.LocalDate
import org.ftckb.domain.GitRuleEvidence
import org.ftckb.domain.PolicyLevel
import org.ftckb.domain.RuleAuthority
import org.ftckb.domain.RuleCheckKind
import org.ftckb.domain.RuleReviewTrigger
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
    fun `rejects unsupported schema version five`() {
        val exception=assertThrows(IllegalArgumentException::class.java) {
            RuleYamlCodec.decode("schemaVersion: 5\nrules: []")
        }

        assertEquals("unsupported schemaVersion",exception.message)
    }

    @Test
    fun `decodes explicit v4 policy and profile`() {
        val rule=RuleYamlCodec.decode(v4Rule()).single()
        assertEquals(PolicyLevel.GLOBAL,rule.policyLevel)
        assertEquals(setOf("command-based"),rule.applicability.profiles)
    }

    @Test
    fun `decodes v4 review triggers`() {
        val rule=RuleYamlCodec.decode(v4Rule().replace(
            "    evidence:",
            "    reviewTriggers:\n      - paths: [\"TeamCode/**/*.java\"]\n        addedLinePatterns: [\"TODO\"]\n    evidence:"
        )).single()

        assertEquals(
            listOf(RuleReviewTrigger(listOf("TeamCode/**/*.java"),listOf("TODO"))),
            rule.reviewTriggers
        )
    }

    @Test
    fun `v4 requires explicit applicability map and lists`() {
        val invalidDocuments=listOf(
            v4Rule().replace("    applicability:\n      teams: []\n      seasons: [\"2025-2026\"]\n      profiles: [command-based]\n", "") to
                "applicability must be a map",
            v4Rule().replace("      teams: []\n", "") to "teams must be a list",
            v4Rule().replace("      seasons: [\"2025-2026\"]\n", "") to
                "seasons must be a list",
            v4Rule().replace("      profiles: [command-based]\n", "") to
                "profiles must be a list"
        )

        invalidDocuments.forEach { (yaml,message) ->
            val exception=assertThrows(IllegalStateException::class.java) { RuleYamlCodec.decode(yaml) }
            assertEquals(message,exception.message)
        }
    }

    @Test
    fun `v4 rejects invalid policy and unknown fields`() {
        val invalidDocuments=listOf(
            v4Rule().replace("policyLevel: global","policyLevel: null") to "policyLevel must be a string",
            v4Rule().replace("policyLevel: global","policyLevel: unsupported") to
                "No enum constant org.ftckb.domain.PolicyLevel.UNSUPPORTED",
            v4Rule().replace("    evidence:","    typo: ignored\n    evidence:") to
                "rules[0] contains unknown fields: typo",
            v4Rule().replace("      profiles: [command-based]","      profiles: [42]") to
                "profiles values must be strings"
        )

        invalidDocuments.forEach { (yaml,message) ->
            val exception=assertThrows(RuntimeException::class.java) { RuleYamlCodec.decode(yaml) }
            assertEquals(message,exception.message)
        }
    }

    @Test
    fun `v4 rejects invalid review triggers`() {
        val invalidDocuments=listOf(
            v4Rule().replace("    evidence:","    reviewTriggers: []\n    evidence:") to
                "rules[0].reviewTriggers must not be empty when present",
            v4Rule().replace("    evidence:","    reviewTriggers:\n      - paths: [42]\n        addedLinePatterns: [TODO]\n    evidence:") to
                "paths values must be strings",
            v4Rule().replace("    evidence:","    reviewTriggers:\n      - paths: [paths]\n        addedLinePatterns: [42]\n    evidence:") to
                "addedLinePatterns values must be strings",
            v4Rule().replace("    evidence:","    reviewTriggers:\n      - paths: [paths]\n    evidence:") to
                "addedLinePatterns must be a list",
            v4Rule().replace("    evidence:","    reviewTriggers:\n      - paths: [paths]\n        addedLinePatterns: [patterns]\n        typo: ignored\n    evidence:") to
                "rules[0].reviewTriggers[0] contains unknown fields: typo"
        )

        invalidDocuments.forEach { (yaml,message) ->
            val exception=assertThrows(RuntimeException::class.java) { RuleYamlCodec.decode(yaml) }
            assertEquals(message,exception.message)
        }
    }

    @Test
    fun `v1 through v3 reject v4-only fields`() {
        val policyLevelYaml=legacyRule("shared").replace("schemaVersion: 1","schemaVersion: 3")
            .replace("    applicability: {}","    policyLevel: global\n    applicability: {}")
        val profilesYaml=legacyRule("shared").replace("schemaVersion: 1","schemaVersion: 3")
            .replace("    applicability: {}","    applicability:\n      profiles: [command-based]")
        val triggerYaml=legacyRule("shared").replace("schemaVersion: 1","schemaVersion: 3")
            .replace("    evidence: []","    reviewTriggers:\n      - paths: [paths]\n        addedLinePatterns: [patterns]\n    evidence: []")

        assertEquals("rules[0].policyLevel requires schemaVersion 4",assertThrows(IllegalStateException::class.java) {
            RuleYamlCodec.decode(policyLevelYaml)
        }.message)
        assertEquals("rules[0].applicability.profiles requires schemaVersion 4",assertThrows(IllegalStateException::class.java) {
            RuleYamlCodec.decode(profilesYaml)
        }.message)
        assertEquals("rules[0].reviewTriggers requires schemaVersion 4",assertThrows(IllegalArgumentException::class.java) {
            RuleYamlCodec.decode(triggerYaml)
        }.message)
    }

    @Test
    fun `legacy schema versions map authority to policy level`() {
        val expected=listOf(
            "official" to PolicyLevel.GLOBAL,
            "shared" to PolicyLevel.SHARED,
            "team" to PolicyLevel.LOCAL
        )

        expected.forEach { (authority,policyLevel) ->
            val rule=RuleYamlCodec.decode(legacyRule(authority)).single()
            assertEquals(policyLevel,rule.policyLevel)
            assertEquals(emptySet<String>(),rule.applicability.profiles)
        }
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
                "accessedAt must use YYYY-MM-DD",
            typedCandidate("type: web\nurl: https://example.org\ntitle: Example\npublisher: Example\naccessedAt: +10000-01-01\nsection: Test") to
                "accessedAt must use YYYY-MM-DD",
            typedCandidate("type: web\nurl: https://example.org\ntitle: Example\npublisher: Example\naccessedAt: 2026-8-3\nsection: Test") to
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
    fun `decodes schema three checks on rules`() {
        val rules=RuleYamlCodec.decode("""
            schemaVersion: 3
            rules:
              - id: shared.example-checked
                topic: example-checked
                title: Checked rule
                instruction: Keep it checked.
                rationale: Enforcement.
                status: approved
                authority: shared
                applicability: {}
                evidence:
                  - type: git
                    repository: owner/repo
                    commit: abcdef1
                    file: TeamCode/Example.java
                    symbol: Example
                approval:
                  approver: overall-lead
                  role: overall_software_lead
                  approvedAt: 2026-08-13T00:00:00Z
                checks:
                  - kind: path-forbidden
                    pattern: build.common.gradle
                    note: SDK reserved
                  - kind: regex-required
                    pattern: "\\.isValid\\(\\)"
                    appliesTo: "**/*.java"
                    note: Validity check required
        """.trimIndent())
        val rule=rules.single()
        assertEquals(2,rule.checks.size)
        assertEquals(RuleCheckKind.PATH_FORBIDDEN,rule.checks[0].kind)
        assertEquals("build.common.gradle",rule.checks[0].pattern)
        assertEquals(null,rule.checks[0].appliesTo)
        assertEquals(RuleCheckKind.REGEX_REQUIRED,rule.checks[1].kind)
        assertEquals("**/*.java",rule.checks[1].appliesTo)
    }

    @Test
    fun `checks require schema version three`() {
        val yaml="""
            schemaVersion: 2
            rules:
              - id: shared.checked-too-early
                topic: checked-too-early
                title: Too early
                instruction: Nope.
                rationale: Nope.
                status: candidate
                authority: shared
                applicability: {}
                evidence:
                  - type: git
                    repository: owner/repo
                    commit: abcdef1
                    file: TeamCode/Example.java
                checks:
                  - kind: path-forbidden
                    pattern: build.gradle
                    note: Nope
        """.trimIndent()
        assertThrows(RuntimeException::class.java) { RuleYamlCodec.decode(yaml) }
    }

    @Test
    fun `schema three without checks decodes to an empty check list`() {
        val rules=RuleYamlCodec.decode("""
            schemaVersion: 3
            rules:
              - id: shared.plain
                topic: plain
                title: Plain rule
                instruction: Plain.
                rationale: Plain.
                status: approved
                authority: shared
                applicability: {}
                evidence:
                  - type: git
                    repository: owner/repo
                    commit: abcdef1
                    file: TeamCode/Example.java
                approval:
                  approver: overall-lead
                  role: overall_software_lead
                  approvedAt: 2026-08-13T00:00:00Z
        """.trimIndent())
        assertEquals(0,rules.single().checks.size)
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

    private fun v4Rule()="""
        schemaVersion: 4
        rules:
          - id: shared.v4
            topic: v4-test
            title: Test
            instruction: Test instruction
            rationale: Test rationale
            status: candidate
            authority: shared
            policyLevel: global
            applicability:
              teams: []
              seasons: ["2025-2026"]
              profiles: [command-based]
            evidence:
              - type: git
                repository: fixture/repo
                commit: abcdef1
                file: TeamCode/Test.java
                line: 1
    """.trimIndent()

    private fun legacyRule(authority:String)="""
        schemaVersion: 1
        rules:
          - id: legacy.rule
            topic: legacy
            title: Legacy
            instruction: Legacy instruction
            rationale: Legacy rationale
            status: candidate
            authority: $authority
            applicability: {}
            evidence: []
    """.trimIndent()
}
