package org.ftckb.cli

import java.net.URI
import java.net.URISyntaxException
import java.nio.file.Files
import java.nio.file.Path
import org.ftckb.domain.ApproverRole
import org.ftckb.domain.RuleAuthority
import org.ftckb.domain.RuleContext
import org.ftckb.domain.RuleResolver
import org.ftckb.domain.RuleStatus
import org.ftckb.knowledge.FileKnowledgeRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class KnowledgeGuideAcceptanceTest {
    private val root=Path.of("..","..","knowledge").normalize()
    private val repositoryRoot=root.parent.toAbsolutePath().normalize()
    private val linkPattern=Regex("""\[[^]]+]\(([^)]+)\)""")

    private fun headingSlugs(markdown:String):Set<String> {
        val counts=mutableMapOf<String,Int>()
        return markdown.lineSequence().mapNotNull { line ->
            val heading=Regex("""^#{1,6}\s+(.+?)\s*$""").matchEntire(line)?.groupValues?.get(1)
                ?: return@mapNotNull null
            val base=heading.lowercase()
                .replace(Regex("""[`*_~]"""),"")
                .replace(Regex("""[^\p{L}\p{N}\s-]"""),"")
                .trim().replace(Regex("""\s+"""),"-")
            val index=counts.getOrDefault(base,0)
            counts[base]=index+1
            if (index==0) base else "$base-$index"
        }.toSet()
    }

    private fun h2Section(markdown:String,heading:String)=markdown
        .substringAfter("## $heading")
        .substringBefore("\n## ")

    private fun assertLink(guide:Path,link:String,allowedRoot:Path) {
        if (Regex("""^[A-Za-z][A-Za-z0-9+.-]*:""").containsMatchIn(link)) {
            val uri=try {
                URI(link)
            } catch (exception:URISyntaxException) {
                throw AssertionError("$guide has invalid external link $link",exception)
            }
            assertTrue(
                uri.isAbsolute && uri.scheme.equals("https",ignoreCase=true) && !uri.host.isNullOrBlank(),
                "$guide has non-HTTPS or hostless link $link"
            )
            assertTrue(uri.userInfo==null,"$guide has URL user-info $link")
            return
        }
        val filePart=link.substringBefore('#')
        val fragment=link.substringAfter('#',"")
        assertTrue(!filePart.startsWith("/") && !filePart.startsWith("\\"),"$guide has absolute or UNC link $link")
        assertTrue(!Regex("""^[A-Za-z]:[\\/].*""").matches(filePart),"$guide has Windows drive link $link")
        val allowed=allowedRoot.toAbsolutePath().normalize()
        val target=if (filePart.isBlank()) {
            guide.toAbsolutePath().normalize()
        } else {
            guide.parent.resolve(filePart).toAbsolutePath().normalize()
        }
        assertTrue(target.startsWith(allowed),"$guide has link outside repository root $link")
        assertTrue(Files.exists(target),"$guide has missing link $link")
        assertTrue(target.toRealPath().startsWith(allowed.toRealPath()),"$guide has symlink outside repository root $link")
        if (fragment.isNotBlank() && target.toString().endsWith(".md")) {
            assertTrue(fragment in headingSlugs(Files.readString(target)),"$guide has missing fragment $link")
        }
    }

    private fun assertGuideLinks(guide:Path,allowedRoot:Path=guide.parent.toAbsolutePath().normalize()) {
        linkPattern.findAll(Files.readString(guide)).forEach { match ->
            assertLink(guide,match.groupValues[1],allowedRoot)
        }
    }

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
    fun `guide rules have approved shared governance and resolve active`() {
        val loaded=FileKnowledgeRepository.load(root)
        assertTrue(loaded.violations.isEmpty(),loaded.violations.joinToString())
        val byId=loaded.rules.associateBy { it.id }
        val expectedIds=expected.values.flatten().toSet()

        assertEquals(19,expectedIds.size)
        assertEquals(expectedIds,loaded.rules.map { it.id }.filter { it in expectedIds }.toSet())
        val expectedActiveIds=listOf("official.keep-customizations-in-teamcode")+expectedIds.sorted()
        val team20827ActiveIds=expectedActiveIds+listOf(
            "team-20827.chinese-javadoc","team-20827.constants-centralized","team-20827.hardware-container",
            "team-20827.motor-init-safety","team-20827.naming-conventions","team-20827.telemetry-multiple"
        ).sorted()

        expected.forEach { (guidePath,ids) ->
            val guide=Files.readString(root.resolve(guidePath))
            ids.forEach { id ->
                val rule=byId.getValue(id)
                assertEquals(RuleStatus.APPROVED,rule.status,id)
                assertEquals(RuleAuthority.SHARED,rule.authority,id)
                assertNotNull(rule.approval,id)
                assertEquals(ApproverRole.OVERALL_SOFTWARE_LEAD,rule.approval?.role,id)
                assertTrue(id in guide,"$guidePath must cite $id")
            }
            assertTrue("## 相关规则" in guide,"$guidePath must explain governing rules")
            assertTrue("## 官方来源" in guide,"$guidePath must list official sources")
            assertTrue("安全" in guide,"$guidePath must state safety or misuse boundaries")
        }

        for (team in listOf("20827","16093")) {
            val resolution=RuleResolver.resolve(loaded.rules,RuleContext(team,"2025-2026"))
            assertTrue(resolution.conflicts.isEmpty(),resolution.conflicts.joinToString())
            assertEquals(if (team=="20827") team20827ActiveIds else expectedActiveIds,resolution.activeRules.map { it.id },team)
        }
    }

    @Test
    fun `knowledge guide links resolve locally and external syntax is safe`() {
        Files.walk(root.resolve("guides")).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".md") }.forEach { guide ->
                assertGuideLinks(guide,repositoryRoot)
            }
        }
    }

    @Test
    fun `github style heading fragments support unicode formatting and duplicate suffixes`() {
        assertEquals(
            setOf("pedro-路径","重复-标题","重复-标题-1"),
            headingSlugs("# Pedro `路径`\n## 重复 *标题*\n### 重复 标题\n")
        )
    }

    @Test
    fun `20827 mapping is removable provenance while safety boundaries remain outside it`() {
        val guide=Files.readString(root.resolve("guides/tools/pedro-pathing.md"))
        val section=h2Section(guide,"20827-inspired advanced mapping")
        setOf(
            "observed team-code provenance","非规范","不是 Pedro 官方要求",
            "TopAutoBase","BottomAutoBase","TopAutoRed","TopAutoBlue",
            "Constants.createFollower","XKCommandOpmode","Supplier<PathChain>"
        ).forEach { assertTrue(it in section,it) }
        setOf("不得复制","四阶段最小 Auto","必须","先让").forEach {
            assertTrue(it !in section,"20827 removable section contains normative safety phrase: $it")
        }
        val safetyBoundary="不得复制其他机器人的 hardware names、servo positions、poses、offsets、directions、mass、velocity、PIDF、power 或 timeout。"
        val progressionBoundary="先让四阶段最小 Auto 在当前机器人通过，再单独设计和评审。"
        assertTrue(safetyBoundary in guide)
        assertTrue(progressionBoundary in guide)
        assertTrue(safetyBoundary !in section)
        assertTrue(progressionBoundary !in section)
    }

    @Test
    fun `same file and cross file fragments resolve while bad fragments fail`(@TempDir tempDir:Path) {
        val target=tempDir.resolve("target.md")
        val guide=tempDir.resolve("guide.md")
        Files.writeString(target,"# 目标 标题\n")
        Files.writeString(
            guide,
            "# 重复标题\n## 重复标题\n[同文件](#重复标题-1)\n[跨文件](target.md#目标-标题)\n"
        )
        assertGuideLinks(guide)

        Files.writeString(guide,"# 已有\n[坏锚点](target.md#不存在)\n")
        assertThrows(AssertionError::class.java) { assertGuideLinks(guide) }
    }

    @Test
    fun `external links must be absolute HTTPS without user info`(@TempDir tempDir:Path) {
        val guide=tempDir.resolve("guide.md")
        Files.writeString(guide,"[HTTP](http://example.com)\n")
        assertThrows(AssertionError::class.java) { assertGuideLinks(guide) }
        Files.writeString(guide,"[Credentials](https://user@example.com/path)\n")
        assertThrows(AssertionError::class.java) { assertGuideLinks(guide) }
        Files.writeString(guide,"[No host](https:/path)\n")
        assertThrows(AssertionError::class.java) { assertGuideLinks(guide) }
    }

    @Test
    fun `scheme classification is case insensitive and fail closed`(@TempDir tempDir:Path) {
        val guide=tempDir.resolve("guide.md")
        Files.writeString(guide,"# Guide\n")
        assertLink(guide,"https://example.com/path",tempDir)
        assertLink(guide,"HTTPS://example.com/path",tempDir)
        listOf(
            "http://example.com","HTTP://example.com","mailto:team@example.com",
            "file:local.txt","ftp:artifact","https:/path"
        ).forEach { link ->
            assertThrows(AssertionError::class.java) { assertLink(guide,link,tempDir) }
        }
    }

    @Test
    fun `local links reject absolute paths drives UNC and root escape`(@TempDir tempDir:Path) {
        val allowedRoot=tempDir.resolve("allowed")
        val guideDir=allowedRoot.resolve("a/b/c")
        Files.createDirectories(guideDir)
        val guide=guideDir.resolve("guide.md")
        val local=guideDir.resolve("target.md")
        Files.writeString(guide,"# Local heading\n")
        Files.writeString(local,"# Target heading\n")
        assertLink(guide,"target.md#target-heading",allowedRoot)
        assertLink(guide,"#local-heading",allowedRoot)

        val outside=tempDir.resolve("outside.md")
        Files.writeString(outside,"# Outside\n")
        val windowsSlash=guideDir.resolve("C:/target.md")
        Files.createDirectories(windowsSlash.parent)
        Files.writeString(windowsSlash,"# Windows\n")
        val windowsBackslash=guideDir.resolve("C:\\target.md")
        Files.writeString(windowsBackslash,"# Windows\n")
        val uncBackslash=guideDir.resolve("\\\\server\\share.md")
        Files.writeString(uncBackslash,"# UNC\n")
        listOf(
            outside.toString(),"C:/target.md","C:\\target.md",
            "/${outside.toString().trimStart('/')}","//${outside.toString().trimStart('/')}",
            "\\\\server\\share.md","../../../../outside.md"
        ).forEach { link ->
            assertThrows(AssertionError::class.java) { assertLink(guide,link,allowedRoot) }
        }
    }
}
