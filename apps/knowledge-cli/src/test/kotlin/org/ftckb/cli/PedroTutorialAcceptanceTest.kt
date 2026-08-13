package org.ftckb.cli

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PedroTutorialAcceptanceTest {
    private val repositoryRoot=Path.of("..","..").normalize()
    private val sourcePath=repositoryRoot.resolve("knowledge/examples/pedro/SafePedroAuto.java")
    private val guidePath=repositoryRoot.resolve("knowledge/guides/tools/pedro-pathing.md")

    private fun source()=Files.readString(sourcePath)

    private fun methodBody(java:String,method:String):String {
        val signature=Regex("""public\s+void\s+$method\s*\(\s*\)\s*\{""").find(java)
            ?: error("missing lifecycle method $method")
        val open=java.indexOf('{',signature.range.first)
        var depth=0
        for (index in open until java.length) {
            when (java[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth==0) return java.substring(open+1,index)
                }
            }
        }
        error("unclosed lifecycle method $method")
    }

    private fun configureFieldNames(java:String):Set<String> {
        val block=java.substringAfter("CONFIGURE HERE START").substringBefore("CONFIGURE HERE END")
        return Regex("""private\s+static\s+final\s+[A-Za-z0-9_<>]+\s+([A-Z][A-Z0-9_]*)\s*=""")
            .findAll(block).map { it.groupValues[1] }.toSet()
    }

    private fun privateMethodBody(java:String,method:String):String {
        val signature=Regex("""private\s+(?:boolean|void)\s+$method\s*\([^)]*\)\s*\{""").find(java)
            ?: error("missing private method $method")
        val open=java.indexOf('{',signature.range.first)
        var depth=0
        for (index in open until java.length) {
            when (java[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth==0) return java.substring(open+1,index)
                }
            }
        }
        error("unclosed private method $method")
    }

    @Test
    fun `canonical auto is iterative and excludes command frameworks`() {
        val java=source()
        assertTrue("public class SafePedroAuto extends OpMode" in java)
        assertTrue("@Autonomous" in java)
        setOf("init","init_loop","start","loop","stop").forEach { method ->
            assertTrue(Regex("""@Override\s+public\s+void\s+$method\s*\(\s*\)""").containsMatchIn(java),method)
        }
        assertFalse("LinearOpMode" in java)
        assertFalse("com.arcrobotics" in java)
        assertFalse("CommandOpMode" in java)
        assertFalse("CommandScheduler" in java)
    }

    @Test
    fun `canonical auto defaults to locked config check`() {
        val java=source()
        assertTrue("CONFIGURATION_COMPLETE=false" in java)
        assertTrue("TEST_STAGE=TestStage.CONFIG_CHECK" in java)
        assertTrue("private boolean safetyLocked=true" in java)
        assertFalse(Regex("""follower\.(followPath|update)\s*\(""").containsMatchIn(methodBody(java,"init")))
        assertFalse("servo.setPosition" in methodBody(java,"init"))
        assertFalse("servo.setPosition" in methodBody(java,"init_loop"))
        assertTrue(methodBody(java,"start").trimStart().startsWith("if (safetyLocked)"))
        assertTrue(methodBody(java,"loop").trimStart().startsWith("if (safetyLocked)"))
    }

    @Test
    fun `stage capabilities and states are explicit`() {
        val java=source()
        assertTrue("CONFIG_CHECK(false,false)" in java)
        assertTrue("SERVO_ONLY(false,true)" in java)
        assertTrue("SHORT_DRIVE(true,false)" in java)
        assertTrue("FULL_AUTO(true,true)" in java)
        setOf("PRELOAD_CLOSED","DRIVE_TO_SCORE","RELEASE","RELEASE_WAIT","DRIVE_TO_PARK","DONE","SAFETY_STOP","STOPPED")
            .forEach { assertTrue(it in java,it) }
    }

    @Test
    fun `motion is non blocking and confined to guarded gateways`() {
        val java=source()
        assertFalse("Thread.sleep" in java)
        assertFalse(Regex("""\bsleep\s*\(""").containsMatchIn(java))
        assertFalse(Regex("""\b(while|do)\b""").containsMatchIn(methodBody(java,"loop")))
        assertEquals(1,Regex("""follower\.followPath\s*\(""").findAll(java).count())
        assertEquals(1,Regex("""servo\.setPosition\s*\(""").findAll(java).count())
        assertEquals(1,Regex("""follower\.update\s*\(""").findAll(java).count())
        assertTrue("private boolean commandPath(" in java)
        assertTrue("private boolean commandServo(" in java)
        assertTrue("private void updateFollowerIfAllowed(" in java)
        assertTrue("follower.followPath" in privateMethodBody(java,"commandPath"))
        assertTrue("servo.setPosition" in privateMethodBody(java,"commandServo"))
        assertTrue("follower.update" in privateMethodBody(java,"updateFollowerIfAllowed"))
        assertTrue("follower.isBusy()" in java)
        assertTrue("follower.breakFollowing()" in methodBody(java,"stop"))
        assertFalse("servo.setPosition" in methodBody(java,"stop"))
    }

    @Test
    fun `configuration contract is complete and robot neutral`() {
        val java=source()
        assertEquals(
            setOf(
                "CONFIGURATION_COMPLETE","TEST_STAGE","SERVO_NAME","SERVO_CLOSED_POSITION",
                "SERVO_OPEN_POSITION","START_POSE","SCORE_POSE","SHORT_TEST_POSE","PARK_POSE",
                "RELEASE_WAIT_SECONDS","SHORT_DRIVE_MAX_POWER","FULL_AUTO_MAX_POWER"
            ),
            configureFieldNames(java)
        )
        setOf("20827","TopAuto","BottomAuto","Shooter","Intake","Gate","Ejector","Transfer","Turret")
            .forEach { assertFalse(it in java,it) }
    }

    @Test
    fun `all validation categories are aggregated`() {
        val java=source()
        setOf(
            "CONFIGURATION_INCOMPLETE","SERVO_NAME_MISSING_OR_SENTINEL","NON_FINITE_NUMBER",
            "SERVO_POSITION_OUT_OF_RANGE","SERVO_POSITIONS_IDENTICAL","WAIT_DURATION_OUT_OF_RANGE",
            "POWER_OUT_OF_RANGE","POSE_INVALID","ROUTE_POSES_IDENTICAL","FOLLOWER_INIT_FAILED",
            "SERVO_INIT_FAILED","STAGE_RESOURCE_UNAVAILABLE"
        ).forEach { assertTrue(it in java,it) }
        assertTrue("EnumSet<ValidationIssue> validationIssues" in java)
        assertTrue("for (ValidationIssue issue: validationIssues)" in java)
    }
}
