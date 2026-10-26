package org.ftckb.standardizer

import java.nio.file.Files
import java.nio.file.Path
import org.ftckb.domain.*
import org.ftckb.knowledge.FileKnowledgeRepository
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CommandFrameworkReviewTest {
    private val prefix="TeamCode/src/main/java/org/firstinspires/ftc/teamcode/"
    private val ids=setOf(
        "shared.command-runtime-boundary","shared.command-scheduler-lifecycle",
        "shared.command-default-resumption","shared.command-scheduler-reentrancy"
    )
    private fun rules():List<KnowledgeRule> {
        val root=generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .map { it.resolve("knowledge") }
            .first { Files.isRegularFile(it.resolve("shared/command-framework.yaml")) }
        val loaded=FileKnowledgeRepository.load(root)
        assertTrue(loaded.violations.isEmpty(),loaded.violations.toString())
        val approved=loaded.rules.filter { it.id in ids }
        assertEquals(ids,approved.map { it.id }.toSet())
        assertTrue(approved.all { it.status==RuleStatus.APPROVED && it.approval!=null })
        return RuleResolver.resolve(approved,RuleContext("20827","2026-2027",setOf("command-based"))).activeRules
    }
    private fun check(path:String,vararg lines:String)=Standardizer.evaluate(rules(),listOf(
        Standardizer.DiffChange(path,lines.mapIndexed { index,line -> index+1 to line })
    )).also { assertTrue(it.violations.isEmpty(),it.violations.toString()) }
    private fun soft(path:String,vararg lines:String)=check(path,*lines).soft.map { it.first }.toSet()

    @Test
    fun `related Java additions request each specific review without hard violations`() {
        assertEquals(setOf("shared.command-runtime-boundary"),soft(prefix+"Robot.java","scheduler.schedule(command);"))
        assertEquals(setOf("shared.command-scheduler-lifecycle"),soft(prefix+"Robot.java","scheduler.run();"))
        assertEquals(setOf("shared.command-default-resumption"),soft(prefix+"Drive.java","addRequirements(drive);"))
        assertEquals(setOf("shared.command-scheduler-reentrancy"),soft(prefix+"Queue.java","pendingCancels.add(command);"))
        assertEquals(ids,soft(prefix+"framework/CommandScheduler.java","class CommandScheduler {}"))
    }

    @Test
    fun `ordinary Java docs and non TeamCode keyword matches do not request review`() {
        assertTrue(soft(prefix+"Drive.java","motor.setPower(power);").isEmpty())
        assertTrue(soft("README.md","CommandScheduler scheduler; scheduler.run();").isEmpty())
        assertTrue(soft("modules/example/CommandScheduler.java","pendingCancels.add(command);").isEmpty())
        assertTrue(soft(prefix+"tests/PlainSmokeTest.java","class PlainSmokeTest extends LinearOpMode {}").isEmpty())
    }

    @Test
    fun `path triggers intentionally cover scheduler deletion and rename while line triggers do not cover ordinary deletion`() {
        val scheduler=prefix+"framework/CommandScheduler.java"
        val deletion=Standardizer.parsePatch("""
            diff --git a/$scheduler b/$scheduler
            deleted file mode 100644
            --- a/$scheduler
            +++ /dev/null
            @@ -1 +0,0 @@
            -class CommandScheduler {}
        """.trimIndent()+"\n")
        val expected=setOf("shared.command-scheduler-lifecycle","shared.command-default-resumption","shared.command-scheduler-reentrancy")
        val deleted=Standardizer.evaluate(rules(),deletion)
        assertTrue(deleted.violations.isEmpty())
        assertEquals(expected,deleted.soft.map { it.first }.toSet())
        val renamed=prefix+"framework/Dispatcher.java"
        val rename=Standardizer.parsePatch("""
            diff --git a/$scheduler b/$renamed
            similarity index 100%
            rename from $scheduler
            rename to $renamed
        """.trimIndent()+"\n")
        assertEquals(expected,Standardizer.evaluate(rules(),rename).soft.map { it.first }.toSet())
        assertTrue(soft(prefix+"Robot.java").isEmpty())
        assertTrue(soft(prefix+"framework/Dispatcher.java").isEmpty())
    }

    @Test
    fun `test mode leaves plain testing OpMode alone but still requests command safety review`() {
        val active=RuleResolver.resolve(rules(),RuleContext("20827","2026-2027",setOf("command-based"),WorkMode.TEST)).activeRules
        val outcome=Standardizer.evaluate(active,listOf(Standardizer.DiffChange(prefix+"tests/PlainSmokeTest.java",
            listOf(1 to "class PlainSmokeTest extends LinearOpMode {}"))))
        assertTrue(outcome.violations.isEmpty())
        assertTrue(outcome.soft.isEmpty())
        val command=Standardizer.evaluate(active,listOf(Standardizer.DiffChange(prefix+"tests/RuntimeProbe.java",
            listOf(1 to "scheduler.run();"))))
        assertTrue(command.violations.isEmpty())
        assertEquals(listOf("shared.command-scheduler-lifecycle"),command.soft.map { it.first })
    }
}
