package org.ftckb.cli

import java.nio.file.Files
import java.nio.file.Path
import org.ftckb.domain.RuleAuthority
import org.ftckb.domain.RuleContext
import org.ftckb.domain.RuleResolver
import org.ftckb.domain.RuleStatus
import org.ftckb.knowledge.FileKnowledgeRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KnowledgeGuideAcceptanceTest {
    private val root=Path.of("..","..","knowledge").normalize()
    private val expected=mapOf(
        "guides/setup/android-studio-ftc-sdk.md" to setOf(
            "shared.ftc-sdk-pin-release",
            "shared.ftc-sdk-preserve-build-tooling",
            "shared.ftc-sdk-separate-toolchain-versions"
        ),
        "guides/setup/ftclib.md" to setOf(
            "shared.ftclib-check-current-prerequisites",
            "shared.ftclib-pin-module-versions"
        ),
        "guides/setup/ftc-dashboard.md" to setOf(
            "shared.dashboard-pin-stable-dependency",
            "shared.dependency-verify-sync-build-run"
        ),
        "guides/tools/pedro-pathing.md" to setOf(
            "shared.pedro-tune-current-robot",
            "shared.pedro-localization-before-follower",
            "shared.pedro-explicit-coordinate-conversion"
        ),
        "guides/tools/gobilda-motors-servos.md" to setOf(
            "shared.gobilda-identify-exact-sku",
            "shared.gobilda-use-output-shaft-encoder-resolution",
            "shared.gobilda-separate-stall-and-operating-values",
            "shared.gobilda-servo-mode-and-pwm-range"
        ),
        "guides/tools/limelight-3a.md" to setOf(
            "shared.limelight-check-result-validity",
            "shared.limelight-enforce-freshness-policy",
            "shared.limelight-synchronize-pipeline-dependent-reads",
            "shared.limelight-configure-camera-pose",
            "shared.limelight-back-up-before-os-update"
        )
    )

    @Test
    fun `guide rules have exact candidate governance and never resolve active`() {
        val loaded=FileKnowledgeRepository.load(root)
        assertTrue(loaded.violations.isEmpty(),loaded.violations.joinToString())
        val byId=loaded.rules.associateBy { it.id }
        val expectedIds=expected.values.flatten().toSet()

        assertEquals(19,expectedIds.size)
        assertEquals(expectedIds,loaded.rules.map { it.id }.filter { it in expectedIds }.toSet())

        expected.forEach { (guidePath,ids) ->
            val guide=Files.readString(root.resolve(guidePath))
            ids.forEach { id ->
                val rule=byId.getValue(id)
                assertEquals(RuleStatus.CANDIDATE,rule.status,id)
                assertEquals(RuleAuthority.SHARED,rule.authority,id)
                assertNull(rule.approval,id)
                assertTrue(id in guide,"$guidePath must cite $id")
            }
            assertTrue("## 相关规则" in guide,"$guidePath must explain governing rules")
            assertTrue("## 官方来源" in guide,"$guidePath must list official sources")
            assertTrue("安全" in guide,"$guidePath must state safety or misuse boundaries")
        }

        val resolution=RuleResolver.resolve(loaded.rules,RuleContext("20827","2025-2026"))
        assertTrue(resolution.conflicts.isEmpty(),resolution.conflicts.joinToString())
        assertEquals(listOf("official.keep-customizations-in-teamcode"),resolution.activeRules.map { it.id })
        assertTrue(resolution.activeRules.none { it.id in expectedIds })
    }

    @Test
    fun `relative links in knowledge guides resolve`() {
        val linkPattern=Regex("""\[[^]]+]\(([^)]+)\)""")
        Files.walk(root.resolve("guides")).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".md") }.forEach { guide ->
                linkPattern.findAll(Files.readString(guide)).forEach { match ->
                    val link=match.groupValues[1]
                    if (!link.startsWith("http://") && !link.startsWith("https://") &&
                        !link.startsWith("#") && !link.startsWith("mailto:")) {
                        val target=guide.parent.resolve(link.substringBefore('#')).normalize()
                        assertTrue(Files.exists(target),"$guide has missing link $link")
                    }
                }
            }
        }
    }
}
