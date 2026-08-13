package org.ftckb.cli

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Properties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PedroTutorialAcceptanceTest {
    private val repositoryRoot=Path.of("..","..").normalize()
    private val sourcePath=repositoryRoot.resolve("knowledge/examples/pedro/SafePedroAuto.java")
    private val guidePath=repositoryRoot.resolve("knowledge/guides/tools/pedro-pathing.md")
    private val fixtureRoot=repositoryRoot.resolve("fixtures/pedro-compile")
    private fun source()=Files.readString(sourcePath)

    private fun fixtureProperties()=Properties().apply {
        Files.newBufferedReader(fixtureRoot.resolve("gradle.properties")).use(::load)
    }

    private fun sha256(path:Path)=MessageDigest.getInstance("SHA-256")
        .digest(Files.readAllBytes(path))
        .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2,'0') }

    private fun compact(java:String)=java.replace(Regex("""\s+"""),"")

    private fun imports(java:String)=Regex("""(?m)^import\s+([^;]+);$""")
        .findAll(java).map { it.groupValues[1] }.toSet()

    private fun assertBefore(text:String,first:String,second:String) {
        val firstIndex=text.indexOf(first)
        val secondIndex=text.indexOf(second)
        assertTrue(firstIndex>=0,"missing $first")
        assertTrue(secondIndex>=0,"missing $second")
        assertTrue(firstIndex<secondIndex,"$first must occur before $second")
    }

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

    private fun markdownTableRows(markdown:String,heading:String):List<List<String>> {
        val section=markdown.substringAfter("## $heading").substringBefore("\n## ")
        return section.lineSequence().filter { it.trim().startsWith("|") }
            .map { line -> line.trim().trim('|').split('|').map(String::trim) }
            .filterNot { row -> row.all { cell -> cell.matches(Regex("[-: ]+")) } }
            .toList()
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
        assertEquals(
            setOf(
                "com.pedropathing.follower.Follower",
                "com.pedropathing.geometry.BezierLine",
                "com.pedropathing.geometry.Pose",
                "com.pedropathing.paths.PathChain",
                "com.qualcomm.robotcore.eventloop.opmode.Autonomous",
                "com.qualcomm.robotcore.eventloop.opmode.OpMode",
                "com.qualcomm.robotcore.hardware.Servo",
                "com.qualcomm.robotcore.util.ElapsedTime",
                "java.util.EnumSet",
                "org.firstinspires.ftc.teamcode.pedroPathing.Constants"
            ),
            imports(java)
        )
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
    fun `config check validates both resources without moving hardware`() {
        val java=source()
        val initialize=privateMethodBody(java,"initializeResourcesForSelectedStage")
        val compactInitialize=compact(initialize)

        assertTrue("booleancheckAllResources=TEST_STAGE==TestStage.CONFIG_CHECK;" in compactInitialize)
        assertTrue("if(TEST_STAGE.driveAllowed||checkAllResources){" in compact(initialize.substringBefore("Constants.createFollower")))
        assertTrue("if(TEST_STAGE.servoAllowed||checkAllResources){" in compact(initialize.substringBefore("hardwareMap.get")))
        setOf("init","init_loop").forEach { lifecycle ->
            val body=methodBody(java,lifecycle)
            setOf("follower.followPath","follower.update","servo.setPosition").forEach { command ->
                assertFalse(command in body,"$command must not run during $lifecycle")
            }
        }
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
        val commandPath=privateMethodBody(java,"commandPath")
        val commandServo=privateMethodBody(java,"commandServo")
        val updateFollower=privateMethodBody(java,"updateFollowerIfAllowed")
        assertFalse("Thread.sleep" in java)
        assertFalse(Regex("""\bsleep\s*\(""").containsMatchIn(java))
        assertFalse(Regex("""\b(while|do)\b""").containsMatchIn(methodBody(java,"loop")))
        assertEquals(1,Regex("""follower\.followPath\s*\(""").findAll(java).count())
        assertEquals(1,Regex("""servo\.setPosition\s*\(""").findAll(java).count())
        assertEquals(1,Regex("""follower\.update\s*\(""").findAll(java).count())
        assertEquals(1,Regex("""\.followPath\s*\(""").findAll(java).count())
        assertEquals(1,Regex("""\.setPosition\s*\(""").findAll(java).count())
        assertEquals(2,Regex("""\.update\s*\(""").findAll(java).count())
        assertTrue("private boolean commandPath(" in java)
        assertTrue("private boolean commandServo(" in java)
        assertTrue("private void updateFollowerIfAllowed(" in java)
        assertTrue("if(safetyLocked||!TEST_STAGE.driveAllowed||follower==null||path==null)" in compact(commandPath.substringBefore("follower.followPath")))
        assertTrue("if(safetyLocked||!TEST_STAGE.servoAllowed||servo==null||!inClosedUnitRange(position))" in compact(commandServo.substringBefore("servo.setPosition")))
        assertTrue("if(safetyLocked||!TEST_STAGE.driveAllowed||follower==null)return;" in compact(updateFollower.substringBefore("follower.update")))
        assertEquals(setOf("follower"),Regex("""\bFollower\s+([A-Za-z_][A-Za-z0-9_]*)""").findAll(java).map { it.groupValues[1] }.toSet())
        assertEquals(setOf("servo"),Regex("""\bServo\s+([A-Za-z_][A-Za-z0-9_]*)""").findAll(java).map { it.groupValues[1] }.toSet())
        setOf("turn","holdPoint","startTeleopDrive","setTeleOpMovementVectors","setPower",
            "setVelocity","setMotorPowers","setDrivePowers").forEach { api ->
            assertFalse(Regex("""\.$api\s*\(""").containsMatchIn(java),api)
        }
        setOf("DcMotor","DcMotorEx","CRServo","Motor","MotorEx").forEach { type ->
            assertFalse(Regex("""\b$type\b""").containsMatchIn(java),type)
        }
        assertTrue("follower.isBusy()" in java)
        assertTrue("stopFollowingBestEffort()" in methodBody(java,"stop"))
        assertFalse("servo.setPosition" in methodBody(java,"stop"))
    }

    @Test
    fun `safety transitions precede best effort follower cancellation`() {
        val java=source()
        val safetyStop=privateMethodBody(java,"enterSafetyStop")
        val stop=methodBody(java,"stop")

        assertBefore(safetyStop,"safetyLocked=true","stopFollowingBestEffort()")
        assertBefore(safetyStop,"autoState=AutoState.SAFETY_STOP","stopFollowingBestEffort()")
        assertBefore(stop,"safetyLocked=true","stopFollowingBestEffort()")
        assertBefore(stop,"autoState=AutoState.STOPPED","stopFollowingBestEffort()")

        val cancellation=privateMethodBody(java,"stopFollowingBestEffort")
        assertTrue("try {" in cancellation)
        assertTrue("catch (RuntimeException" in cancellation)
        assertEquals(1,Regex("""follower\.breakFollowing\s*\(""").findAll(java).count())
        assertTrue("follower.breakFollowing()" in cancellation)
    }

    @Test
    fun `loop telemetry failures enter safety stop without recursive telemetry`() {
        val java=source()
        val loop=methodBody(java,"loop")
        val safeTelemetry=privateMethodBody(java,"emitTelemetrySafely")

        assertTrue("if(safetyLocked){emitTelemetrySafely();return;}" in compact(loop))
        assertTrue("finally{emitTelemetrySafely();}" in compact(loop))
        assertFalse("emitTelemetry();" in loop)
        assertTrue("try {" in safeTelemetry)
        assertTrue("catch (RuntimeException" in safeTelemetry)
        assertTrue("emitTelemetry();" in safeTelemetry)
        assertTrue("enterSafetyStop(" in safeTelemetry)
        assertFalse("emitTelemetrySafely(" in safeTelemetry)
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

    @Test
    fun `null servo name is aggregated before string access`() {
        val validation=compact(privateMethodBody(source(),"validateStaticConfiguration"))
        val nullGuard=validation.indexOf("SERVO_NAME==null")
        val stringAccess=validation.indexOf("SERVO_NAME.trim()")
        assertTrue(nullGuard>=0,"missing null servo-name guard")
        assertTrue(stringAccess>=0,"missing servo-name validation")
        assertTrue(nullGuard<stringAccess,"null guard must precede SERVO_NAME string access")
        assertTrue("SERVO_NAME==null||SERVO_NAME.trim().isEmpty()||SERVO_NAME.startsWith(\"YOUR_\")" in validation)
        assertTrue("validationIssues.add(ValidationIssue.SERVO_NAME_MISSING_OR_SENTINEL)" in validation)
    }

    @Test
    fun `compile fixture pins the reviewed release matrix`() {
        val properties=fixtureProperties()
        assertEquals("8.7.0",properties.getProperty("agpVersion"))
        assertEquals("30",properties.getProperty("compileSdk"))
        assertEquals("24",properties.getProperty("minSdk"))
        assertEquals("11.2.0",properties.getProperty("ftcSdkVersion"))
        assertEquals("v11.2",properties.getProperty("ftcSdkTag"))
        assertEquals("4ed7c4666aec265a6fd9e674ca40462e9dfe4bf8",properties.getProperty("ftcSdkCommit"))
        assertEquals("2.1.2",properties.getProperty("pedroVersion"))
        assertEquals("96df977d30329eef57c226cf1e6854026f4dfe4f",properties.getProperty("pedroCommit"))
        assertEquals("d3aea9ca3c5b4c09eded8580229b86996480ee89",properties.getProperty("pedroQuickstartCommit"))
        val build=Files.readString(fixtureRoot.resolve("build.gradle"))
        assertTrue("../../knowledge/examples/pedro" in build)
        assertFalse("SafePedroAuto.java" in Files.walk(fixtureRoot).use { paths ->
            paths.filter(Files::isRegularFile).map { it.fileName.toString() }.toList()
        })
    }

    @Test
    fun `compile fixture locks provenance source and pinned dependencies`() {
        val properties=fixtureProperties()
        val wrapperProperties=Files.readString(fixtureRoot.resolve("gradle/wrapper/gradle-wrapper.properties"))
        val fixtureBuild=compact(Files.readString(fixtureRoot.resolve("build.gradle")))
        val canonicalSources=Files.walk(repositoryRoot).use { paths ->
            paths.filter(Files::isRegularFile)
                .filter { it.fileName.toString()=="SafePedroAuto.java" }
                .map { repositoryRoot.relativize(it).toString() }
                .toList()
        }

        assertEquals("https\\://services.gradle.org/distributions/gradle-8.9-bin.zip",
            wrapperProperties.lineSequence().first { it.startsWith("distributionUrl=") }.substringAfter('='))
        mapOf(
            "gradlew" to "874d75d37bf38c810a8314e0b2f78a3c77fce9437963ae33cec8543d92662b61",
            "gradlew.bat" to "f4f428c5626b3d90cef3bd4e7fd3ad3ea5760442db8c09d586b5bfe031dbe5e3",
            "gradle/wrapper/gradle-wrapper.jar" to "96f793a18e056c23ffeec67c1f3bb8eccff5a4a407fc9ceac183527e7eedf4b6",
            "gradle/wrapper/gradle-wrapper.properties" to "ef02d8fe6df48d7e49abb80fea9caa3eb0fc562ee361380a480dabbba0ef07c5"
        ).forEach { (relativePath,expected) ->
            assertEquals(expected,sha256(fixtureRoot.resolve(relativePath)),relativePath)
        }
        assertTrue("sourceCompatibilityJavaVersion.VERSION_1_8" in fixtureBuild)
        assertTrue("targetCompatibilityJavaVersion.VERSION_1_8" in fixtureBuild)
        assertTrue("main.java.srcDirs+='../../knowledge/examples/pedro'" in fixtureBuild)
        assertTrue("implementation\"org.firstinspires.ftc:RobotCore:${'$'}{ftcSdkVersion}\"" in fixtureBuild)
        assertTrue("implementation\"org.firstinspires.ftc:Hardware:${'$'}{ftcSdkVersion}\"" in fixtureBuild)
        assertTrue("implementation\"com.pedropathing:ftc:${'$'}{pedroVersion}\"" in fixtureBuild)
        assertEquals(listOf("knowledge/examples/pedro/SafePedroAuto.java"),canonicalSources)
        assertFalse("pedro-compile" in Files.readString(repositoryRoot.resolve("settings.gradle.kts")))
        assertEquals("11.2.0",properties.getProperty("ftcSdkVersion"))
        assertEquals("2.1.2",properties.getProperty("pedroVersion"))
    }

    @Test
    fun `root compile tasks use delayed SDK validation and platform-safe wrapper commands`() {
        val rootBuild=compact(Files.readString(repositoryRoot.resolve("build.gradle.kts")))

        assertTrue("workingDir(layout.projectDirectory.dir(\"fixtures/pedro-compile\"))" in rootBuild)
        assertTrue("if(System.getProperty(\"os.name\").lowercase().contains(\"windows\")){commandLine(\"cmd\",\"/c\",\"gradlew.bat\",\"clean\",\"compileDebugJavaWithJavac\",\"--no-daemon\")}else{commandLine(\"./gradlew\",\"clean\",\"compileDebugJavaWithJavac\",\"--no-daemon\")}" in rootBuild)
        assertTrue("doFirst{valsdk=System.getenv(\"ANDROID_HOME\")?:System.getenv(\"ANDROID_SDK_ROOT\")?:error(\"SetANDROID_HOMEorANDROID_SDK_ROOTbeforeverifyPedroExampleCompile\")environment(\"ANDROID_HOME\",sdk)}" in rootBuild)
        assertTrue("tasks.register(\"verifyPedroRelease\")" in rootBuild)
        assertTrue("dependsOn(\":modules:domain:test\",\":modules:knowledge:test\",\":apps:knowledge-cli:test\",verifyPedroExampleCompile)" in rootBuild)
    }

    @Test
    fun `guide documents every configure field exactly once`() {
        val java=source()
        val guide=Files.readString(guidePath)
        val rows=markdownTableRows(guide,"SafePedroAuto 参数字典")
        assertEquals(listOf("参数","填什么","如何获得","单位或范围","如何验证"),rows.first())
        val documented=rows.drop(1).map { row ->
            assertEquals(5,row.size,row.joinToString())
            row.drop(1).forEach { assertTrue(it.isNotBlank(),row.joinToString()) }
            row.first().removeSurrounding("`")
        }
        assertEquals(configureFieldNames(java),documented.toSet())
        assertEquals(documented.size,documented.toSet().size)
    }

    @Test
    fun `guide links the canonical example without duplicating the class`() {
        val guide=Files.readString(guidePath)
        assertTrue("../../examples/pedro/SafePedroAuto.java" in guide)
        assertFalse("public class SafePedroAuto" in guide)
        setOf("CONFIG_CHECK","SERVO_ONLY","SHORT_DRIVE","FULL_AUTO").forEach {
            assertTrue(it in guide,it)
        }
    }

    @Test
    fun `guide states version and provenance boundaries`() {
        val guide=Files.readString(guidePath)
        setOf(
            "Pedro requirement","beginner safety convention","20827-inspired pattern","robot-specific value",
            "11.2.0","2.1.2","4ed7c4666aec265a6fd9e674ca40462e9dfe4bf8",
            "96df977d30329eef57c226cf1e6854026f4dfe4f","d3aea9ca3c5b4c09eded8580229b86996480ee89"
        ).forEach { assertTrue(it in guide,it) }
        assertTrue("FTC 11.1.0" in guide)
        assertTrue("本项目编译验证" in guide)
        assertFalse("Pedro 官方保证兼容 FTC 11.2" in guide)
    }

    @Test
    fun `pedro rules are approved shared and active for both teams`() {
        val loaded=org.ftckb.knowledge.FileKnowledgeRepository.load(repositoryRoot.resolve("knowledge"))
        assertTrue(loaded.violations.isEmpty(),loaded.violations.joinToString())
        val pedroIds=setOf(
            "shared.pedro-tune-current-robot",
            "shared.pedro-localization-before-follower",
            "shared.pedro-explicit-coordinate-conversion"
        )
        loaded.rules.filter { it.id in pedroIds }.forEach {
            assertEquals(org.ftckb.domain.RuleStatus.APPROVED,it.status,it.id)
            assertEquals(org.ftckb.domain.RuleAuthority.SHARED,it.authority,it.id)
            assertTrue(it.approval!=null,it.id)
            assertEquals(
                org.ftckb.domain.ApproverRole.OVERALL_SOFTWARE_LEAD,
                it.approval?.role,
                it.id
            )
        }
        for (team in listOf("20827","16093")) {
            val result=org.ftckb.domain.RuleResolver.resolve(
                loaded.rules,
                org.ftckb.domain.RuleContext(team,"2025-2026")
            )
            assertEquals(
                pedroIds,
                result.activeRules.map { it.id }.filter { it in pedroIds }.toSet(),
                team
            )
        }
    }
}
