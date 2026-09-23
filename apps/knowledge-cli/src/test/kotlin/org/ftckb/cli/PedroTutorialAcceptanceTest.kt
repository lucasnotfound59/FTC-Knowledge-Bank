package org.ftckb.cli

import java.nio.file.Files
import java.nio.file.FileVisitOption
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Properties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

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

    private fun gitOutput(root:Path,vararg arguments:String):String {
        val command=listOf("git","-C",root.toAbsolutePath().normalize().toString())+arguments
        val process=ProcessBuilder(command).redirectErrorStream(true).start()
        val output=process.inputStream.bufferedReader().use { it.readText() }
        val exitCode=process.waitFor()
        check(exitCode==0) { "${command.joinToString(" ")} failed ($exitCode): $output" }
        return output
    }

    private fun lexicalAbsolute(path:Path,base:Path?=null):Path {
        val resolved=if(path.isAbsolute) path else (base?:Path.of("").toAbsolutePath()).resolve(path)
        return resolved.toAbsolutePath().normalize()
    }

    private fun registeredGitWorktreeRoots(root:Path):Set<Path> {
        val lexicalRoot=lexicalAbsolute(root)
        return gitOutput(lexicalRoot,"worktree","list","--porcelain","-z")
            .split('\u0000')
            .filter { it.startsWith("worktree ") }
            .map { Path.of(it.removePrefix("worktree ")) }
            .map { lexicalAbsolute(it,lexicalRoot) }
            .filter(Files::isDirectory)
            .toSet()
    }

    private fun isInNestedRegisteredWorktree(
        candidate:Path,
        currentRoot:Path,
        registeredWorktreeRoots:Set<Path>
    ):Boolean {
        val lexicalRoot=lexicalAbsolute(currentRoot)
        val lexicalCandidate=lexicalAbsolute(candidate,lexicalRoot)
        // Exclude only worktrees nested inside the current walk root. A root that
        // merely CONTAINS the current root (e.g. running the suite from the nested
        // cli-agent worktree while the main checkout also registers it) must not
        // swallow every candidate under the current root.
        return registeredWorktreeRoots
            .map { lexicalAbsolute(it,lexicalRoot) }
            .filter { it!=lexicalRoot && it.startsWith(lexicalRoot) }
            .any(lexicalCandidate::startsWith)
    }

    private fun trackedSafePedroSources(root:Path)=gitOutput(root,"ls-files","-z")
        .split('\u0000')
        .filter { it.substringAfterLast('/')=="SafePedroAuto.java" }

    private fun markdownLinkTargets(markdown:String)=Regex("""\[[^]]+]\(([^)\s]+)\)""")
        .findAll(markdown)
        .map { it.groupValues[1] }
        .toSet()

    private fun markdownHeadingSlugs(markdown:String)=markdown.lineSequence()
        .mapNotNull { line -> Regex("""^#{1,6}\s+(.+?)\s*#*\s*$""").matchEntire(line) }
        .map { match ->
            match.groupValues[1]
                .lowercase()
                .replace(Regex("""[^\p{L}\p{N}\s_-]"""),"")
                .trim()
                .replace(Regex("""\s+"""),"-")
        }
        .toSet()

    private fun assertMarkdownLinkResolves(markdown:String,target:String) {
        assertTrue(target in markdownLinkTargets(markdown),"missing Markdown link to $target")
        val relativePath=target.substringBefore('#')
        val fragment=target.substringAfter('#',"")
        val resolved=repositoryRoot.resolve(relativePath).normalize()
        assertTrue(Files.isRegularFile(resolved),"link target does not exist: $relativePath")
        if(fragment.isNotEmpty()) {
            val slugs=markdownHeadingSlugs(Files.readString(resolved))
            assertTrue(fragment in slugs,"fragment #$fragment does not match a heading in $relativePath")
        }
    }

    private fun safePedroSources(root:Path,registeredWorktreeRoots:Set<Path>):List<String> {
        val lexicalRoot=lexicalAbsolute(root)
        return Files.walk(lexicalRoot,FileVisitOption.FOLLOW_LINKS).use { paths ->
            paths.filter(Files::isRegularFile)
                .filter { it.fileName.toString()=="SafePedroAuto.java" }
                .filter { "/build/" !in it.toString().replace('\\','/') }
                .filter { !isInNestedRegisteredWorktree(it,lexicalRoot,registeredWorktreeRoots) }
                .map { lexicalRoot.relativize(lexicalAbsolute(it)).toString().replace('\\','/') }
                .toList()
        }
    }

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
                "com.pedropathing.api.Paths",
                "com.pedropathing.follower.Follower",
                "com.pedropathing.math.Pose",
                "com.pedropathing.paths.Path",
                "com.qualcomm.robotcore.eventloop.opmode.Autonomous",
                "com.qualcomm.robotcore.eventloop.opmode.OpMode",
                "com.qualcomm.robotcore.hardware.Servo",
                "com.qualcomm.robotcore.util.ElapsedTime",
                "java.util.EnumSet",
                "org.firstinspires.ftc.teamcode.pedro.Constants"
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
    fun `canonical auto uses only the Pedro 3 api surface`() {
        val java=source()
        setOf(
            "com.pedropathing.ftc","FollowerBuilder","FollowerConstants","PathChain","BezierLine",
            "com.pedropathing.geometry","followPath","pathBuilder","setStartingPose","getPose",
            "getX()","getY()","getHeading()","breakFollowing","PoseConverter"
        ).forEach { legacy -> assertFalse(legacy in java,legacy) }
        assertTrue("Constants.create(hardwareMap)" in java)
        assertTrue("Paths.line(start,end).linear(start,end)" in java)
        assertTrue("follower.setPose(START_POSE)" in java)
        assertTrue("follower.follow(path)" in java)
        assertTrue("Constants.foresightConfig.maxPathSpeed.set(maxPathSpeed)" in java)
    }

    @Test
    fun `canonical auto defaults to locked config check`() {
        val java=source()
        assertTrue("CONFIGURATION_COMPLETE=false" in java)
        assertTrue("TEST_STAGE=TestStage.CONFIG_CHECK" in java)
        assertTrue("private boolean safetyLocked=true" in java)
        assertFalse(Regex("""follower\.(follow|update)\s*\(""").containsMatchIn(methodBody(java,"init")))
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
        assertTrue("if(TEST_STAGE.driveAllowed||checkAllResources){" in compact(initialize.substringBefore("Constants.create")))
        assertTrue("if(TEST_STAGE.servoAllowed||checkAllResources){" in compact(initialize.substringBefore("hardwareMap.get")))
        setOf("init","init_loop").forEach { lifecycle ->
            val body=methodBody(java,lifecycle)
            setOf("follower.follow","follower.update","servo.setPosition").forEach { command ->
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
    fun `all state transitions use one timer resetting gateway`() {
        val java=source()
        val transition=privateMethodBody(java,"transitionTo")

        assertTrue("if(autoState==next)return;" in compact(transition))
        assertBefore(transition,"autoState=next","stateTimer.reset()")
        assertEquals(2,Regex("""\bautoState\s*=(?!=)""").findAll(java).count())
        assertTrue("private AutoState autoState=AutoState.SAFETY_STOP;" in java)
        assertTrue("autoState=next;" in transition)
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
        assertEquals(1,Regex("""follower\.follow\s*\(""").findAll(java).count())
        assertEquals(1,Regex("""servo\.setPosition\s*\(""").findAll(java).count())
        assertEquals(2,Regex("""follower\.update\s*\(""").findAll(java).count())
        assertEquals(1,Regex("""\.follow\s*\(""").findAll(java).count())
        assertEquals(1,Regex("""\.setPosition\s*\(""").findAll(java).count())
        assertEquals(1,Regex("""telemetry\.update\s*\(""").findAll(java).count())
        assertTrue("private boolean commandPath(" in java)
        assertTrue("private boolean commandServo(" in java)
        assertTrue("private void updateFollowerIfAllowed(" in java)
        assertTrue("if(safetyLocked||!TEST_STAGE.driveAllowed||follower==null||path==null||!inSpeedFractionRange(maxPathSpeed))" in compact(commandPath.substringBefore("follower.follow(")))
        assertEquals(1,Regex("""Constants\.foresightConfig\.maxPathSpeed\.set\s*\(""").findAll(java).count())
        assertTrue("if(safetyLocked||!TEST_STAGE.servoAllowed||servo==null||!inClosedUnitRange(position))" in compact(commandServo.substringBefore("servo.setPosition")))
        assertTrue("if(safetyLocked||!TEST_STAGE.driveAllowed||follower==null)return;" in compact(updateFollower.substringBefore("follower.update")))
        assertEquals(setOf("follower"),Regex("""\bFollower\s+([A-Za-z_][A-Za-z0-9_]*)""").findAll(java).map { it.groupValues[1] }.toSet())
        assertEquals(setOf("servo"),Regex("""\bServo\s+([A-Za-z_][A-Za-z0-9_]*)""").findAll(java).map { it.groupValues[1] }.toSet())
        setOf("turn","holdPoint","startTeleopDrive","setTeleOpMovementVectors","setPower",
            "setVelocity","setMotorPowers","setDrivePowers","followPath","pathBuilder",
            "setStartingPose","breakFollowing").forEach { api ->
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
    fun `telemetry reports only the last successful servo gateway command`() {
        val java=source()
        val commandServo=privateMethodBody(java,"commandServo")

        assertTrue("private enum LastServoCommand" in java)
        setOf("NONE","CLOSED","OPEN").forEach { assertTrue(it in java,it) }
        assertTrue("private LastServoCommand lastServoCommand=LastServoCommand.NONE;" in java)
        assertBefore(commandServo,"servo.setPosition(position)","lastServoCommand=command")
        assertEquals(2,Regex("""\blastServoCommand\s*=(?!=)""").findAll(java).count())
        assertTrue("telemetry.addData(\"last servo command\",lastServoCommand);" in java)
    }

    @Test
    fun `safety transitions precede best effort follower cancellation`() {
        val java=source()
        val safetyStop=privateMethodBody(java,"enterSafetyStop")
        val stop=methodBody(java,"stop")

        assertBefore(safetyStop,"safetyLocked=true","stopFollowingBestEffort()")
        assertBefore(safetyStop,"transitionTo(AutoState.SAFETY_STOP)","stopFollowingBestEffort()")
        assertBefore(safetyStop,"safetyLocked=true","transitionTo(AutoState.SAFETY_STOP)")
        assertBefore(stop,"safetyLocked=true","stopFollowingBestEffort()")
        assertBefore(stop,"transitionTo(AutoState.STOPPED)","stopFollowingBestEffort()")
        assertBefore(stop,"safetyLocked=true","transitionTo(AutoState.STOPPED)")

        val cancellation=privateMethodBody(java,"stopFollowingBestEffort")
        assertTrue("try {" in cancellation)
        assertTrue("catch (RuntimeException" in cancellation)
        assertEquals(1,Regex("""follower\.stop\s*\(""").findAll(java).count())
        assertTrue("follower.stop()" in cancellation)
        assertBefore(cancellation,"follower.stop()","follower.update()")
        assertTrue("follower.drivetrain.stop()" in cancellation)
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
                "RELEASE_WAIT_SECONDS","SHORT_DRIVE_MAX_PATH_SPEED","FULL_AUTO_MAX_PATH_SPEED"
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
            "PATH_SPEED_OUT_OF_RANGE","POSE_INVALID","ROUTE_POSES_IDENTICAL","FOLLOWER_INIT_FAILED",
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
        assertEquals("34",properties.getProperty("compileSdk"))
        assertEquals("24",properties.getProperty("minSdk"))
        assertEquals("11.2.1",properties.getProperty("ftcSdkVersion"))
        assertEquals("v11.2.1",properties.getProperty("ftcSdkTag"))
        assertEquals("26cd1fdd2a3c4b26173d9ff33a3279c27d1c7ad1",properties.getProperty("ftcSdkCommit"))
        assertEquals("3.0.0",properties.getProperty("pedroVersion"))
        assertEquals("1.0.0",properties.getProperty("pedroTuningVersion"))
        assertEquals("fa5a07c7761ed01f943ef37ea29b71f30138b5d1",properties.getProperty("pedroCommit"))
        assertEquals("b4312385b7d0cc5e8dd263ec3927c9ef0cb48f36",properties.getProperty("pedroQuickstartCommit"))
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
        val fixtureSettings=Files.readString(fixtureRoot.resolve("settings.gradle"))
        val canonicalSources=safePedroSources(
            repositoryRoot,
            registeredGitWorktreeRoots(repositoryRoot)
        )
        val trackedSources=trackedSafePedroSources(repositoryRoot)

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
        assertTrue("implementation\"com.pedropathing:revhub:${'$'}{pedroVersion}\"" in fixtureBuild)
        assertTrue("implementation\"com.pedropathing:tuning:${'$'}{pedroTuningVersion}\"" in fixtureBuild)
        assertFalse("com.pedropathing:ftc:" in fixtureBuild)
        assertTrue("maven { url 'https://repo.dairy.foundation/releases/' }" in fixtureSettings)
        assertEquals(2,Regex("maven \\{ url 'https://repo.dairy.foundation/releases/' }").findAll(fixtureSettings).count())
        assertEquals(listOf("knowledge/examples/pedro/SafePedroAuto.java"),canonicalSources)
        assertEquals(listOf("knowledge/examples/pedro/SafePedroAuto.java"),trackedSources)
        assertFalse("pedro-compile" in Files.readString(repositoryRoot.resolve("settings.gradle.kts")))
        assertEquals("11.2.1",properties.getProperty("ftcSdkVersion"))
        assertEquals("3.0.0",properties.getProperty("pedroVersion"))
        assertEquals("1.0.0",properties.getProperty("pedroTuningVersion"))
    }

    @Test
    fun `fixture constants adapter wires a real pedro three follower`() {
        val adapter=Files.readString(
            fixtureRoot.resolve("src/main/java/org/firstinspires/ftc/teamcode/pedro/Constants.java")
        )
        val compactAdapter=compact(adapter)
        assertFalse("returnnull;" in compactAdapter)
        assertFalse("FollowerBuilder" in adapter)
        setOf(
            "newFollower(",
            "newPinpointLocalizer(hardwareMap,localizerConfig)",
            "newMecanum(hardwareMap,drivetrainConfig)",
            "newForesight(foresightConfig)"
        ).forEach { fragment -> assertTrue(fragment in compactAdapter,fragment) }
        assertTrue("mustnotbedeployed" in compactAdapter)
        assertTrue("unvalidatedsamplevalue" in compactAdapter)
        assertTrue("https://pedropathing.com/docs/pathing/tuning/constants" in adapter)
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
        assertEquals(listOf("参数","填什么 / 如何获得","单位或范围 / 验证"),rows.first())
        val documented=rows.drop(1).map { row ->
            assertEquals(3,row.size,row.joinToString())
            row.drop(1).forEach { assertTrue(it.isNotBlank(),row.joinToString()) }
            row.first().removeSurrounding("`")
        }
        assertEquals(configureFieldNames(java),documented.toSet())
        assertEquals(documented.size,documented.toSet().size)
    }

    @Test
    fun `guide links the canonical example without duplicating the class`() {
        val guide=Files.readString(guidePath)
        val compactGuide=compact(guide)
        assertTrue("../../examples/pedro/SafePedroAuto.java" in guide)
        assertFalse("public class SafePedroAuto" in guide)
        setOf("CONFIG_CHECK","SERVO_ONLY","SHORT_DRIVE","FULL_AUTO").forEach {
            assertTrue(it in guide,it)
        }
        setOf(
            "privatevoidupdateFollowerIfAllowed(){",
            "if(safetyLocked||!TEST_STAGE.driveAllowed||follower==null)return;",
            "follower.update();",
            "if(autoState==AutoState.DRIVE_TO_PARK&&!follower.isBusy()&&finishPathOutput(\"shortdrive\"))",
            "follower.follow(path);",
            "Paths.line(start,end).linear(start,end)"
        ).forEach { assertTrue(it in compactGuide,it) }
        assertTrue("follower.stop()" in guide)
        assertTrue("isBusy()==false` 不等于电机零输出" in guide)
        assertTrue("stateTimer" in guide)
        assertTrue("lastServoCommand" in guide)
    }

    @Test
    fun `config check is static and delegates movement validation to localization test`() {
        val guide=Files.readString(guidePath)
        val configCheck=guide.substringAfter("### 1. CONFIG_CHECK")
            .substringBefore("### 2. SERVO_ONLY")

        assertTrue("静态校验与资源构造" in configCheck)
        assertTrue("静态起始 pose" in configCheck)
        assertTrue("随后另用官方 Localization Test 手推确认定位轴/尺度" in configCheck)
        assertFalse("follower.update()" in configCheck)
    }

    @Test
    fun `route B gives exact official dependency edits without module ambiguity`() {
        val guide=Files.readString(guidePath)
        val routeB=guide.substringAfter("### Route B — current FIRST v11.2.1 team project")
            .substringBefore("## 坐标系")

        assertTrue("build.dependencies.gradle" in routeB)
        assertTrue("maven { url 'https://repo.dairy.foundation/releases/' }" in routeB)
        assertTrue("implementation 'com.pedropathing:revhub:3.0.0'" in routeB)
        assertTrue("implementation 'com.pedropathing:tuning:1.0.0'" in routeB)
        assertTrue("只有复制并使用 tuners/Panels 时" in routeB)
        assertTrue("不在核心编译 fixture 的范围内" in routeB)
        assertTrue("compile SDK 34" in routeB)
        assertFalse("TeamCode/build.gradle" in routeB)
        assertFalse("com.pedropathing:ftc:" in routeB)
        assertFalse("mymaven.bylazar.com" in routeB)
    }

    @Test
    fun `route A separates snapshot build from configured hardware deployment`() {
        val guide=Files.readString(guidePath)
        val routeA=guide.substringAfter("### Route A — official Quickstart snapshot")
            .substringBefore("### Route B — current FIRST v11.2.1 team project")
        val snapshot=routeA.indexOf("checkout 上表固定 commit")
        val placeholder=routeA.indexOf("返回 null 的占位实现")
        val configure=routeA.indexOf("完成自己的 MecanumConfig")
        val build=routeA.indexOf("Gradle Sync/build 后")
        val approval=routeA.indexOf("获得队员复核后")
        val localization=routeA.indexOf("Localization Test")

        listOf(snapshot,placeholder,configure,build,approval,localization).forEach {
            assertTrue(it>=0,"missing Route A separation cue")
        }
        assertTrue(snapshot<placeholder)
        assertTrue(placeholder<configure)
        assertTrue(configure<build)
        assertTrue(build<approval)
        assertTrue(approval<localization)
    }

    @Test
    fun `pinpoint configuration uses version three fields and measured scale`() {
        val guide=Files.readString(guidePath)
        val pinpoint=guide.substringAfter("## Pinpoint 完整新生流程")
            .substringBefore("## 官方调参顺序")

        setOf(
            "Pinpoint AutoTuner","Localization Test",
            "xPodOffset","yPodOffset","xPodDirection","yPodDirection",
            "ticksPerUnit","encoderResolutionUnit","globalDistanceUnit",
            "已知距离", "前推 x 增、左移 y 增"
        ).forEach { assertTrue(it in pinpoint,it) }
        assertTrue("不能假定永远是 ticks/mm" in pinpoint)
        assertFalse("customEncoderResolution=(" in pinpoint)
    }

    @Test
    fun `guide uses exact driver station telemetry labels and has no localization typo`() {
        val guide=Files.readString(guidePath)
        setOf(
            "CONFIG:","configuration complete","test stage","auto state",
            "safety locked","runtime failure","x (in)","y (in)",
            "heading (rad)","follower busy","last servo command","state elapsed (s)"
        ).forEach { assertTrue(it in guide,it) }
        assertTrue("当前状态时长" in guide)
        assertTrue("不反映舵机实际位置" in guide)
        assertFalse("y 墤" in guide)
    }

    @Test
    fun `guide states version and provenance boundaries`() {
        val guide=Files.readString(guidePath)
        setOf(
            "Pedro requirement","beginner safety convention","20827-inspired pattern","robot-specific value",
            "11.2.1","3.0.0","26cd1fdd2a3c4b26173d9ff33a3279c27d1c7ad1",
            "fa5a07c7761ed01f943ef37ea29b71f30138b5d1",
            "b4312385b7d0cc5e8dd263ec3927c9ef0cb48f36",
            "96df977d30329eef57c226cf1e6854026f4dfe4f","d3aea9ca3c5b4c09eded8580229b86996480ee89"
        ).forEach { assertTrue(it in guide,it) }
        assertTrue("Java 编译通过" in guide)
        assertTrue("版本组合的编译结果不是官方兼容保证" in guide)
        assertFalse("Pedro 官方保证兼容 FTC 11.2.1" in guide)
    }

    @Test
    fun `20827 is cited only as a pinned non normative architecture case`() {
        val guide=Files.readString(guidePath)
        val commit="118c28e137334bbbea510d77f1fa384e8b1b5779"
        assertTrue(commit in guide)
        setOf("TopAutoBase","BottomAutoBase","TopAutoRed","TopAutoBlue","Constants.create","XKCommandOpmode")
            .forEach { assertTrue(it in guide,it) }
        assertTrue("非规范" in guide)
        assertTrue("不是 Pedro 3 API 证据" in guide)
    }

    @Test
    fun `path speed fraction is not presented as motor power cap`() {
        val guide=Files.readString(guidePath)
        val java=source()
        assertTrue("不是电机功率上限" in guide)
        assertTrue("不是 20% 电机功率硬上限" in guide)
        assertTrue("若队伍需要真正的电机功率硬限制" in guide)
        assertTrue("SHORT_DRIVE_MAX_PATH_SPEED=0.20" in compact(java))
        assertFalse("MAX_POWER" in java)
    }

    @Test
    fun `each completed path flushes holding output before the next state`() {
        val java=source()
        val shortDrive=privateMethodBody(java,"updateShortDriveTest")
        val fullAuto=privateMethodBody(java,"updateFullAuto")
        val finish=privateMethodBody(java,"finishPathOutput")
        val stop=privateMethodBody(java,"stopFollowingBestEffort")
        assertTrue("finishPathOutput(\"short drive\")" in shortDrive)
        assertTrue("finishPathOutput(\"score\")" in fullAuto)
        assertTrue("finishPathOutput(\"park\")" in fullAuto)
        assertBefore(fullAuto,"finishPathOutput(\"score\")","transitionTo(AutoState.RELEASE)")
        assertBefore(fullAuto,"finishPathOutput(\"park\")","transitionTo(AutoState.DONE)")
        assertTrue("if (!stopFollowingBestEffort())" in finish)
        assertTrue("enterSafetyStop(" in finish)
        assertBefore(stop,"follower.stop()","follower.update()")
        assertTrue("follower.drivetrain.stop()" in stop)
    }

    @Test
    fun `current cross tool guidance does not suggest a removed pose converter`() {
        val limelight=Files.readString(repositoryRoot.resolve("knowledge/guides/tools/limelight-3a.md"))
        val integration=limelight.substringAfter("### 与 Pedro Pathing 联用")
            .substringBefore("## 分层验收")
        assertTrue("Pedro 3 坐标教程" in integration)
        assertTrue("显式转换并在已知点验证" in integration)
        assertTrue("旧版 `PoseConverter` 不能直接作为 Pedro 3 API 使用" in integration)
        assertFalse("使用官方 PoseConverter" in integration)
    }

    @Test
    fun `rookiebot current import example uses the pedro three tutorial package`() {
        val loaded=org.ftckb.knowledge.FileKnowledgeRepository.load(repositoryRoot.resolve("knowledge"))
        assertTrue(loaded.violations.isEmpty(),loaded.violations.joinToString())
        val rule=loaded.rules.single { it.id=="shared.rookiebot-java-imports" }
        assertTrue("teamcode.pedro.Constants" in (rule.positiveExample?:""))
        assertFalse("teamcode.pedroPathing.Constants" in (rule.positiveExample?:""))
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
                org.ftckb.domain.RuleContext(team,"2025-2026",emptySet())
            )
            assertEquals(
                pedroIds,
                result.activeRules.map { it.id }.filter { it in pedroIds }.toSet(),
                team
            )
        }
    }

    @Test
    fun `readme exposes tutorial example and release verification`() {
        val readme=Files.readString(repositoryRoot.resolve("README.md"))
        assertMarkdownLinkResolves(
            readme,
            "knowledge/guides/tools/pedro-pathing.md#safepedroauto-参数字典"
        )
        assertMarkdownLinkResolves(
            readme,
            "knowledge/guides/tools/pedro-pathing.md#四阶段实车测试清单"
        )
        assertMarkdownLinkResolves(readme,"knowledge/examples/pedro/SafePedroAuto.java")
        assertTrue("verifyPedroRelease" in readme)
        assertTrue("ANDROID_HOME" in readme||"ANDROID_SDK_ROOT" in readme)
        assertTrue("默认锁定" in readme)
        assertTrue("不代表部署或实机验证通过" in readme)
        assertTrue("当前没有已完成的 Pedro 实机验证记录" in readme)
    }

    @Test
    fun `source uniqueness excludes registered worktrees not directory names`(@TempDir root:Path) {
        val ordinaryArchive=root.resolve(".worktrees/archive/SafePedroAuto.java")
        val registeredRoot=root.resolve(".worktrees/registered-checkout")
        val registeredCopy=registeredRoot.resolve("knowledge/examples/pedro/SafePedroAuto.java")
        Files.createDirectories(ordinaryArchive.parent)
        Files.createDirectories(registeredCopy.parent)
        Files.writeString(ordinaryArchive,"ordinary duplicate")
        Files.writeString(registeredCopy,"registered worktree duplicate")

        assertEquals(
            setOf(
                ".worktrees/archive/SafePedroAuto.java",
                ".worktrees/registered-checkout/knowledge/examples/pedro/SafePedroAuto.java"
            ),
            safePedroSources(root,emptySet()).toSet()
        )
        assertEquals(
            listOf(".worktrees/archive/SafePedroAuto.java"),
            safePedroSources(root,setOf(registeredRoot))
        )
    }

    @Test
    fun `registered worktree membership uses lexical candidate paths`() {
        val root=Path.of("repository")
        val registeredRoot=root.resolve("registered-checkout")
        val registeredCandidate=registeredRoot.resolve("knowledge/examples/pedro/SafePedroAuto.java")
        val fileAlias=root.resolve("aliases/SafePedroAuto.java")
        val directoryAliasCandidate=root.resolve("aliases/copied-tree/knowledge/examples/pedro/SafePedroAuto.java")
        val registeredRoots=setOf(registeredRoot)

        assertTrue(isInNestedRegisteredWorktree(registeredCandidate,root,registeredRoots))
        assertFalse(isInNestedRegisteredWorktree(fileAlias,root,registeredRoots))
        assertFalse(isInNestedRegisteredWorktree(directoryAliasCandidate,root,registeredRoots))
    }

    @Test
    fun `source uniqueness retains symlinks outside registered worktrees`(@TempDir root:Path) {
        val registeredRoot=root.resolve(".worktrees/registered-checkout")
        val registeredCopy=registeredRoot.resolve("knowledge/examples/pedro/SafePedroAuto.java")
        val ordinaryAlias=root.resolve(".worktrees/archive/SafePedroAuto.java")
        Files.createDirectories(registeredCopy.parent)
        Files.createDirectories(ordinaryAlias.parent)
        Files.writeString(registeredCopy,"registered worktree source")
        assertFalse(isInNestedRegisteredWorktree(ordinaryAlias,root,setOf(registeredRoot)))
        assertTrue(isInNestedRegisteredWorktree(registeredCopy,root,setOf(registeredRoot)))
        try {
            Files.createSymbolicLink(ordinaryAlias,registeredCopy)
        } catch (_:UnsupportedOperationException) {
            return
        } catch (_:java.nio.file.FileSystemException) {
            return
        } catch (_:SecurityException) {
            return
        }

        assertEquals(
            listOf(".worktrees/archive/SafePedroAuto.java"),
            safePedroSources(root,setOf(registeredRoot))
        )
    }

    @Test
    fun `source uniqueness traverses directory symlink aliases outside registered worktrees`(@TempDir root:Path) {
        val registeredRoot=root.resolve("registered-checkout")
        val registeredCopy=registeredRoot.resolve("knowledge/examples/pedro/SafePedroAuto.java")
        val ordinaryAlias=root.resolve("aliases/copied-tree")
        Files.createDirectories(registeredCopy.parent)
        Files.createDirectories(ordinaryAlias.parent)
        Files.writeString(registeredCopy,"registered worktree source")
        try {
            Files.createSymbolicLink(ordinaryAlias,registeredRoot)
        } catch (_:UnsupportedOperationException) {
            return
        } catch (_:java.nio.file.FileSystemException) {
            return
        } catch (_:SecurityException) {
            return
        }

        assertEquals(
            listOf("aliases/copied-tree/knowledge/examples/pedro/SafePedroAuto.java"),
            safePedroSources(root,setOf(registeredRoot))
        )
    }
}
