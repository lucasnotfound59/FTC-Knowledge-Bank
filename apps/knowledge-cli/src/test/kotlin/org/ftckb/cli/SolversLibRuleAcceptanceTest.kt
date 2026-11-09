package org.ftckb.cli

import com.fasterxml.jackson.databind.json.JsonMapper
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.io.StringReader
import java.nio.file.Files
import java.nio.file.Path
import org.ftckb.domain.*
import org.ftckb.knowledge.FileKnowledgeRepository
import org.ftckb.standardizer.Standardizer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class SolversLibRuleAcceptanceTest {
    private val root=Path.of("..","..").normalize()
    private val source="TeamCode/src/main/java/org/firstinspires/ftc/teamcode/"
    private val mandates=setOf("global.solverslib-production-runtime","global.solverslib-production-imports")
    private val guidance=setOf("shared.solverslib-dependencies","shared.solverslib-lifecycle",
        "shared.solverslib-requirements","shared.solverslib-composition")
    private val allIds=mandates+guidance
    private fun rules()=FileKnowledgeRepository.load(root.resolve("knowledge")).also {
        assertTrue(it.violations.isEmpty(),it.violations.toString())
    }.rules
    private fun resolve(profile:String="command-based",mode:WorkMode=WorkMode.NORMAL,
        team:String="20827",season:String="2025-2026")=RuleResolver.resolve(rules(),
        RuleContext(team,season,if(profile=="generic") emptySet() else setOf(profile),mode))
    private fun evaluate(path:String,vararg lines:String,mode:WorkMode=WorkMode.NORMAL)=Standardizer.evaluate(
        resolve(mode=mode).activeRules,listOf(Standardizer.DiffChange(path,lines.mapIndexed { i,s -> i+1 to s })))

    @Test
    fun `six approved rules are scoped only to command profiles across teams and seasons`() {
        val all=rules()
        assertEquals(58,all.size)
        assertEquals(52,all.count { it.status==RuleStatus.APPROVED })
        val added=all.filter { it.id in allIds }
        assertEquals(allIds,added.map { it.id }.toSet())
        added.forEach {
            assertEquals(RuleStatus.APPROVED,it.status,it.id)
            assertEquals(RuleAuthority.SHARED,it.authority,it.id)
            assertEquals(setOf("command-based"),it.applicability.profiles,it.id)
            assertTrue(it.applicability.teams.isEmpty() && it.applicability.seasons.isEmpty(),it.id)
            assertTrue(it.evidence.isNotEmpty(),it.id)
        }
        for(team in listOf("20827","16093","99999")) for(season in listOf("2025-2026","2026-2027")) {
            for(profile in listOf("command-based","ftclib-command")) {
                val result=resolve(profile,team=team,season=season)
                assertTrue(result.conflicts.isEmpty(),result.conflicts.toString())
                assertTrue(result.activeRules.map { it.id }.containsAll(allIds),"$team $season $profile")
            }
            for(profile in listOf("generic","simple-opmode","rookiebot")) {
                assertTrue(resolve(profile,team=team,season=season).activeRules.none { it.id in allIds },profile)
            }
        }
    }

    @Test
    fun `global formal mandates override both old framework choices without conflicts`() {
        val result=resolve("ftclib-command")
        assertTrue(result.conflicts.isEmpty())
        assertTrue(result.activeRules.none { it.id in setOf("shared.command-runtime-boundary","shared.ftclib-command-candidate") })
        assertTrue(result.overriddenRules.any { it.ruleId=="shared.command-runtime-boundary" })
        assertTrue(result.overriddenRules.any { it.ruleId=="shared.ftclib-command-candidate" })
        assertEquals(resolve().activeRules.map { it.id },result.activeRules.map { it.id })
    }

    @Test
    fun `explicit test exempts architecture while restoring runtime choice and preserving safety`() {
        val result=resolve("ftclib-command",WorkMode.TEST)
        assertTrue(result.activeRules.none { it.id in mandates })
        assertTrue(result.activeRules.map { it.id }.containsAll(guidance+"shared.command-runtime-boundary"))
        for(id in mandates) assertEquals(listOf("work-mode-test"),result.excludedRules.single { it.ruleId==id }.reasons)
        assertTrue(evaluate(source+"Probe.java","import com.arcrobotics.ftclib.command.CommandBase;",mode=WorkMode.TEST)
            .violations.none { it.ruleId in mandates })
        assertTrue(evaluate("TeamCode/src/test/java/Probe.java","import org.junit.Test;",mode=WorkMode.TEST)
            .violations.any { it.ruleId=="global.test-utility-layout" })
    }

    @Test
    fun `formal checks reject old command references in Java Kotlin and multiline additions`() {
        for(path in listOf(source+"Main.java",source+"Main.kt","TeamCode/src/main/kotlin/Main.kt")) {
            for(line in listOf("import com.arcrobotics.ftclib.command.CommandBase;",
                "class Main extends com.arcrobotics.ftclib.command.CommandOpMode {}",
                "import static com.arcrobotics.ftclib.command.CommandScheduler.getInstance;")) {
                assertTrue(evaluate(path,line).violations.any { it.ruleId in mandates },"$path $line")
            }
        }
        assertTrue(evaluate(source+"Main.java","import com.arcrobotics.","ftclib.command.CommandBase;")
            .violations.any { it.ruleId in mandates })
    }

    @Test
    fun `formal checks reject Groovy Kotlin and root FTCLib core dependencies`() {
        for(path in listOf("TeamCode/build.gradle","TeamCode/build.gradle.kts","build.dependencies.gradle")) {
            for(line in listOf("implementation 'org.ftclib.ftclib:core:2.1.1'",
                "api(\"org.ftclib.ftclib:core:2.1.1\")",
                "implementation group: 'org.ftclib.ftclib', name: 'core', version: '2.1.1'")) {
                assertTrue(evaluate(path,line).violations.any { it.ruleId in mandates },"$path $line")
            }
        }
    }

    @Test
    fun `SolversLib and import free utilities pass while actual runtime still needs review`() {
        for((path,line) in listOf(source+"Main.java" to "import com.seattlesolvers.solverslib.command.CommandBase;",
            source+"utils/Units.java" to "class Units { static double inches(double cm) { return cm / 2.54; } }",
            "TeamCode/build.gradle" to "implementation 'org.solverslib:core:0.3.6'",
            "TeamCode/build.gradle" to "// implementation 'org.ftclib.ftclib:core:2.1.1'")) {
            val outcome=evaluate(path,line)
            assertTrue(outcome.violations.isEmpty(),outcome.violations.toString())
            assertTrue(outcome.soft.any { it.first=="global.solverslib-production-runtime" })
        }
    }

    @Test
    fun `SolversLib guidance triggers on relevant changes and remains in test`() {
        val relevant=listOf(Standardizer.DiffChange(source+"Main.java",listOf(
            1 to "import com.seattlesolvers.solverslib.command.CommandOpMode;",
            2 to "class Move extends CommandBase { }",
            3 to "new SequentialCommandGroup(new WaitCommand(1000));")))
        for(mode in listOf(WorkMode.NORMAL,WorkMode.TEST)) {
            val out=Standardizer.evaluate(resolve(mode=mode).activeRules,relevant)
            assertTrue(out.soft.map { it.first }.containsAll(guidance),out.soft.toString())
        }
        assertTrue(evaluate(source+"utils/Units.java","class Units {} ").soft.none { it.first in guidance })
        val removal=Standardizer.evaluate(resolve().activeRules,
            listOf(Standardizer.DiffChange("TeamCode/build.gradle",emptyList())))
        assertTrue(removal.soft.any { it.first=="shared.solverslib-dependencies" })
    }

    @Test
    fun `CLI normal blocks old runtime test exempts it and dev preserves finding`(@TempDir tmp:Path) {
        val path=source+"Main.java"
        val patch=tmp.resolve("change.diff")
        Files.writeString(patch,"diff --git a/$path b/$path\nnew file mode 100644\n--- /dev/null\n+++ b/$path\n@@ -0,0 +1 @@\n+import com.arcrobotics.ftclib.command.CommandBase;\n")
        val mapper=JsonMapper.builder().build()
        for((mode,exit) in listOf("normal" to 1,"test" to 0,"dev" to 0)) {
            val out=ByteArrayOutputStream()
            val code=runCli(listOf("check",root.toString(),"--knowledge",root.resolve("knowledge").toString(),
                "--team","20827","--season","2025-2026","--profile","command-based",
                "--work-mode",mode,"--diff",patch.toString(),"--json"),PrintStream(out),StringReader("").buffered())
            assertEquals(exit,code,out.toString())
            val json=mapper.readTree(out.toString())
            if(mode=="normal") assertTrue(json["violations"].any { it["ruleId"].asText() in mandates })
            if(mode=="dev") assertTrue(json["soft"].any { it.toString().contains("downgraded hard") && it.toString().contains("global.solverslib-production-imports") })
        }
    }
}
