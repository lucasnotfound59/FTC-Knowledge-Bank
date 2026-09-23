package org.ftckb.cli

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.json.JsonMapper
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.io.StringReader
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import org.ftckb.domain.ApproverRole
import org.ftckb.domain.GitRuleEvidence
import org.ftckb.domain.KnowledgeRule
import org.ftckb.domain.PolicyLevel
import org.ftckb.domain.RuleAuthority
import org.ftckb.domain.RuleCheckKind
import org.ftckb.domain.RuleStatus
import org.ftckb.domain.WebRuleEvidence
import org.ftckb.knowledge.FileKnowledgeRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class VendorDependencyRuleAcceptanceTest {
    private val root=Path.of("..","..").normalize()
    private val mapper=JsonMapper.builder().build()
    private val vendorRuleId="global.vendor-documented-build-dependencies"
    private val officialRuleId="official.keep-customizations-in-teamcode"
    private val pedroQuickstartCommit="b4312385b7d0cc5e8dd263ec3927c9ef0cb48f36"
    private val designCommit="46108502eb6c963b06b255da0f8a2dd152615128"
    private val dependencyPatch=patch("build.dependencies.gradle",listOf(
        "repositories {",
        "    maven { url 'https://repo.dairy.foundation/releases/' }",
        "}",
        "dependencies {",
        "    implementation 'com.pedropathing:revhub:3.0.0'",
        "    implementation 'com.pedropathing:tuning:1.0.0'",
        "}"
    ))

    private fun rules():List<KnowledgeRule> {
        val loaded=FileKnowledgeRepository.load(root.resolve("knowledge"))
        assertTrue(loaded.violations.isEmpty(),loaded.violations.joinToString())
        return loaded.rules
    }

    private fun patch(path:String,lines:List<String>)="""
        diff --git a/$path b/$path
        new file mode 100644
        --- /dev/null
        +++ b/$path
        @@ -0,0 +1,${lines.size} @@
    """.trimIndent()+"\n"+lines.joinToString(separator="\n",postfix="\n") { "+$it" }

    private fun modifiedPatch(path:String,removed:String,added:List<String>)="""
        diff --git a/$path b/$path
        --- a/$path
        +++ b/$path
        @@ -1 +1,${added.size} @@
        -$removed
    """.trimIndent()+"\n"+added.joinToString(separator="\n",postfix="\n") { "+$it" }

    private fun deletedPatch(path:String,removed:String)="""
        diff --git a/$path b/$path
        deleted file mode 100644
        --- a/$path
        +++ /dev/null
        @@ -1 +0,0 @@
        -$removed
    """.trimIndent()+"\n"

    private fun checkPatch(root:Path,patchText:String):Pair<Int,JsonNode> {
        val file=root.resolve("change.patch")
        Files.writeString(file,patchText)
        val out=ByteArrayOutputStream()
        val code=runCli(listOf(
            "check",root.toString(),"--knowledge",this.root.resolve("knowledge").toString(),
            "--team","20827","--season","2025-2026","--generic-profile",
            "--diff",file.toString(),"--json"
        ),PrintStream(out),StringReader("").buffered())
        return code to mapper.readTree(out.toString())
    }

    private fun checkPatchText(root:Path,patchText:String):Pair<Int,String> {
        val file=root.resolve("change.patch")
        Files.writeString(file,patchText)
        val out=ByteArrayOutputStream()
        val code=runCli(listOf(
            "check",root.toString(),"--knowledge",this.root.resolve("knowledge").toString(),
            "--team","20827","--season","2025-2026","--generic-profile",
            "--diff",file.toString(),"--json"
        ),PrintStream(out),StringReader("").buffered())
        return code to out.toString()
    }

    private fun resolve(profile:List<String>,team:String="20827"):JsonNode {
        val out=ByteArrayOutputStream()
        val code=runCli(
            listOf(
                "resolve",root.resolve("knowledge").toString(),"--team",team,"--season","2025-2026","--json"
            )+profile,
            PrintStream(out)
        )
        assertEquals(0,code,out.toString())
        return mapper.readTree(out.toString())
    }

    private fun softIds(json:JsonNode)=json["soft"].map { it["ruleId"].asText() }

    private fun vendorSofts(json:JsonNode)=softIds(json).filter { it==vendorRuleId }

    @Test
    fun `official hard rule protects only build common gradle with unchanged governance`() {
        val rule=rules().single { it.id==officialRuleId }

        assertEquals("build-customization-location",rule.topic)
        assertEquals(RuleStatus.APPROVED,rule.status)
        assertEquals(RuleAuthority.OFFICIAL,rule.authority)
        assertEquals(emptySet<String>(),rule.applicability.teams)
        assertEquals(emptySet<String>(),rule.applicability.seasons)
        assertEquals(
            listOf(RuleCheckKind.PATH_FORBIDDEN to "build.common.gradle"),
            rule.checks.map { it.kind to it.pattern }
        )
        assertEquals(
            listOf(GitRuleEvidence(
                "FIRST-Tech-Challenge/FtcRobotController",
                "26cd1fdd2a3c4b26173d9ff33a3279c27d1c7ad1",
                "build.common.gradle",
                symbol="build.common.gradle"
            )),
            rule.evidence
        )
        assertNotNull(rule.approval)
        assertEquals("overall-software-lead",rule.approval!!.approver)
        assertEquals(ApproverRole.OVERALL_SOFTWARE_LEAD,rule.approval!!.role)
        assertEquals(Instant.parse("2026-08-13T00:00:00Z"),rule.approval!!.approvedAt)
        assertEquals(emptyList<Any>(),rule.reviewTriggers)
    }

    @Test
    fun `vendor rule is approved shared global cross season cross profile soft metadata`() {
        val rule=rules().single { it.id==vendorRuleId }

        assertEquals("root-dependency-evidence",rule.topic)
        assertEquals(RuleStatus.APPROVED,rule.status)
        assertEquals(RuleAuthority.SHARED,rule.authority)
        assertEquals(PolicyLevel.GLOBAL,rule.policyLevel)
        assertEquals(emptySet<String>(),rule.applicability.teams)
        assertEquals(emptySet<String>(),rule.applicability.seasons)
        assertEquals(emptySet<String>(),rule.applicability.profiles)
        assertNotNull(rule.approval)
        assertEquals("lucasnotfound59",rule.approval!!.approver)
        assertEquals(ApproverRole.OVERALL_SOFTWARE_LEAD,rule.approval!!.role)
        assertEquals(Instant.parse("2026-09-17T13:07:17Z"),rule.approval!!.approvedAt)
        assertEquals(emptyList<Any>(),rule.checks)
        assertEquals(
            listOf(
                WebRuleEvidence(
                    url="https://pedropathing.com/docs/pathing/installation",
                    title="Pedro Pathing Installation",
                    publisher="Pedro Pathing",
                    accessedAt=LocalDate.parse("2026-09-17"),
                    section="Manual Installation"
                ),
                WebRuleEvidence(
                    url="https://github.com/Pedro-Pathing/PedroPathing/releases/tag/v3.0.0",
                    title="Pedro Pathing v3.0.0 Release",
                    publisher="Pedro Pathing",
                    accessedAt=LocalDate.parse("2026-09-17"),
                    section="Release",
                    version="v3.0.0"
                ),
                GitRuleEvidence(
                    "Pedro-Pathing/Quickstart",
                    pedroQuickstartCommit,
                    "build.dependencies.gradle",
                    symbol="com.pedropathing:revhub:3.0.0"
                ),
                GitRuleEvidence(
                    "lucasnotfound59/FTC-Knowledge-Bank",
                    designCommit,
                    "docs/superpowers/specs/2026-09-17-vendor-documented-build-dependencies-design.md",
                    line=1
                )
            ),
            rule.evidence
        )
        assertEquals(1,rule.reviewTriggers.size)
        assertEquals(listOf("build.dependencies.gradle"),rule.reviewTriggers[0].paths)
        assertEquals(emptyList<String>(),rule.reviewTriggers[0].addedLinePatterns)
        listOf(
            "第一方官方文档 URL","固定 commit","精确版本","diff 与官方步骤的逐项对应",
            "不联网","不能验证证据真伪","不代表部署"
        ).forEach { assertTrue(rule.instruction.contains(it),"instruction: $it") }
        listOf("博客","论坛","其他队伍代码","模型回答").forEach {
            assertTrue(rule.instruction.contains(it),"instruction: $it")
        }
    }

    @Test
    fun `build common gradle diff is still an official hard violation with exit one`(@TempDir temp:Path) {
        val (code,json)=checkPatch(
            temp,
            modifiedPatch("build.common.gradle","// sdk",listOf("// sdk","// agent change"))
        )

        assertEquals(1,code,json.toString())
        assertEquals(listOf(officialRuleId),json["violations"].map { it["ruleId"].asText() }.distinct())
        assertEquals(emptyList<String>(),vendorSofts(json))
    }

    @Test
    fun `pedro three dependency patch triggers one vendor soft without the official hard violation`(@TempDir temp:Path) {
        val (code,json)=checkPatch(temp,dependencyPatch)

        assertEquals(0,code,json.toString())
        assertTrue(json["ok"].booleanValue())
        assertEquals(emptyList<String>(),json["violations"].map { it["ruleId"].asText() })
        assertEquals(listOf(vendorRuleId),vendorSofts(json))
    }

    @Test
    fun `dependency file deletion rename and modification each trigger exactly one soft`(@TempDir temp:Path) {
        val rename="""
            diff --git a/build.dependencies.gradle b/archive/build.dependencies.gradle
            similarity index 100%
            rename from build.dependencies.gradle
            rename to archive/build.dependencies.gradle
        """.trimIndent()+"\n"
        val patches=listOf(
            deletedPatch("build.dependencies.gradle","// pinned dependencies"),
            rename,
            modifiedPatch("build.dependencies.gradle","// pinned dependencies",listOf(
                "// pinned dependencies",
                "// dependency bump"
            ))
        )
        for (patchText in patches) {
            val (code,json)=checkPatch(temp,patchText)
            assertEquals(0,code,json.toString())
            assertEquals(listOf(vendorRuleId),vendorSofts(json),json.toString())
        }
    }

    @Test
    fun `unrelated Java and TeamCode diffs do not trigger the vendor soft`(@TempDir temp:Path) {
        val (code,json)=checkPatch(temp,patch(
            "TeamCode/src/main/java/org/firstinspires/ftc/teamcode/subsystems/Drive.java",
            listOf("class Drive {}")
        ))

        assertEquals(0,code,json.toString())
        assertEquals(emptyList<String>(),json["violations"].map { it["ruleId"].asText() })
        assertEquals(emptyList<String>(),vendorSofts(json))
    }

    @Test
    fun `repeated hits deduplicate by rule id and output stays deterministic`(@TempDir temp:Path) {
        val repeated="""
            diff --git a/build.dependencies.gradle b/build.dependencies.gradle
            --- a/build.dependencies.gradle
            +++ b/build.dependencies.gradle
            @@ -1 +1,2 @@
             // pinned dependencies
            +// first hit
            diff --git a/build.dependencies.gradle b/build.dependencies.gradle
            --- a/build.dependencies.gradle
            +++ b/build.dependencies.gradle
            @@ -1 +1,2 @@
             // pinned dependencies
            +// second hit
        """.trimIndent()+"\n"

        val first=checkPatchText(temp,repeated)
        val second=checkPatchText(temp,repeated)

        assertEquals(0,first.first,first.second)
        assertEquals(first.second,second.second)
        val node=mapper.readTree(first.second)
        assertEquals(listOf(vendorRuleId),vendorSofts(node))
        assertEquals(softIds(node),softIds(node).sorted())
    }

    @Test
    fun `resolve json exposes the vendor rule with empty checks and a path only trigger`() {
        val node=resolve(listOf("--generic-profile"))
        val rule=node["activeRules"].single { it["id"].asText()==vendorRuleId }

        assertEquals("approved",rule["status"].asText())
        assertEquals("global",rule["policyLevel"].asText())
        assertEquals(0,rule["checks"].size())
        assertEquals(1,rule["reviewTriggers"].size())
        assertEquals(listOf("build.dependencies.gradle"),rule["reviewTriggers"][0]["paths"].map { it.asText() })
        assertEquals(0,rule["reviewTriggers"][0]["addedLinePatterns"].size())
        listOf("teams","seasons","profiles").forEach { field ->
            assertEquals(0,rule["applicability"][field].size(),field)
        }
        assertEquals(emptyList<String>(),node["conflicts"].map { it["topic"].asText() })
    }

    @Test
    fun `knowledge totals profile counts and real kernel fixtures stay consistent`() {
        val all=rules()
        assertEquals(48,all.size)
        assertEquals(42,all.count { it.status==RuleStatus.APPROVED })
        assertEquals(6,all.count { it.status==RuleStatus.CANDIDATE })

        val expectedCounts=listOf(
            listOf("--generic-profile") to 26,
            listOf("--profile","command-based") to 29,
            listOf("--profile","rookiebot") to 38,
            listOf("--profile","ftclib-command") to 30
        )
        for ((selection,count) in expectedCounts) {
            val first=resolve(selection,"20827")
            val second=resolve(selection,"16093")
            assertEquals(count,first["activeRules"].size(),selection.toString())
            assertTrue(first["activeRules"].any { it["id"].asText()==vendorRuleId },selection.toString())
            assertEquals(
                first["activeRules"].map { it["id"].asText() },
                second["activeRules"].map { it["id"].asText() },
                selection.toString()
            )
            assertEquals(emptyList<String>(),first["conflicts"].map { it["topic"].asText() })
        }

        val validateOut=ByteArrayOutputStream()
        assertEquals(0,runCli(listOf("validate",root.resolve("knowledge").toString(),"--json"),PrintStream(validateOut)))
        assertEquals(
            Files.readString(root.resolve("fixtures/kernel/validate-ok.json")),
            validateOut.toString()
        )
        val resolveOut=ByteArrayOutputStream()
        assertEquals(0,runCli(
            listOf(
                "resolve",root.resolve("knowledge").toString(),"--team","20827","--season","2025-2026",
                "--generic-profile","--json"
            ),
            PrintStream(resolveOut)
        ))
        assertEquals(
            Files.readString(root.resolve("fixtures/kernel/resolve-ok.json")),
            resolveOut.toString()
        )
    }

    @Test
    fun `Pedro guide uses version three APIs with historical version two provenance`() {
        val guide=Files.readString(root.resolve("knowledge/guides/tools/pedro-pathing.md"))

        listOf(
            "Pedro Pathing 3 新生 Auto 教程",
            "https://pedropathing.com/docs/pathing/installation",
            "https://repo.dairy.foundation/releases/",
            "com.pedropathing:revhub:3.0.0",
            "com.pedropathing:tuning:1.0.0",
            "v3.0.0",
            pedroQuickstartCommit,
            "global.vendor-documented-build-dependencies",
            "## 历史来源：Pedro 2.1.2",
            "Paths.line(start,end).linear(start,end)",
            "Constants.create(HardwareMap)"
        ).forEach { phrase -> assertTrue(phrase in guide,phrase) }
        assertTrue("条件式 soft" in guide)
        assertTrue("第一方来源、固定版本与实际 diff 逐项对应" in guide)
        assertTrue("版本组合的编译结果不是官方兼容保证" in guide)
    }
}
