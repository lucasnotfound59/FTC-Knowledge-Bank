package org.ftckb.cli

import java.nio.file.Files
import java.nio.file.Path
import org.ftckb.domain.RuleStatus
import org.ftckb.knowledge.FileKnowledgeRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KnowledgeGuideAcceptanceTest {
    private val root=Path.of("..","..","knowledge").normalize()

    @Test
    fun `setup rules are valid candidates and guides cite them`() {
        val expected=mapOf(
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
            )
        )
        val loaded=FileKnowledgeRepository.load(root)
        assertTrue(loaded.violations.isEmpty(),loaded.violations.joinToString())
        val byId=loaded.rules.associateBy { it.id }

        expected.forEach { (guidePath,ids) ->
            val guide=Files.readString(root.resolve(guidePath))
            ids.forEach { id ->
                assertEquals(RuleStatus.CANDIDATE,byId.getValue(id).status,id)
                assertTrue(id in guide,"$guidePath must cite $id")
            }
        }
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
