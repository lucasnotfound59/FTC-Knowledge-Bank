# Pedro Pathing Newcomer Auto Tutorial Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver one canonical, compile-verified Pedro Pathing Java Auto and a Chinese tutorial that teaches a newcomer what to fill in, how to obtain each value, and how to unlock robot motion safely in four stages.

**Architecture:** Keep `knowledge/shared/tools/pedro-pathing.yaml` as the machine-rule layer and expand `knowledge/guides/tools/pedro-pathing.md` as the human-learning layer. Store the only complete Auto in `knowledge/examples/pedro/SafePedroAuto.java`; fast Kotlin/JUnit source-contract tests protect its safety architecture, while an isolated Android fixture compiles that same file against pinned FTC SDK and Pedro artifacts.

**Tech Stack:** Java 8 FTC OpMode source, FTC SDK 11.2.0, Pedro Pathing 2.1.2, Android Gradle Plugin 8.7.0, Gradle 8.9 fixture, Kotlin/JUnit 5 acceptance tests, Markdown, YAML schema v2.

## Global Constraints

- Implement the approved design at `docs/superpowers/specs/2026-08-13-pedro-newcomer-auto-tutorial-design.md`.
- Pin FIRST FTC SDK tag `v11.2`, commit `4ed7c4666aec265a6fd9e674ca40462e9dfe4bf8`, artifact version `11.2.0`.
- Pin Pedro Pathing tag `v2.1.2`, commit `96df977d30329eef57c226cf1e6854026f4dfe4f`, artifact `com.pedropathing:ftc:2.1.2`.
- Record Pedro Quickstart snapshot `d3aea9ca3c5b4c09eded8580229b86996480ee89`. It uses Pedro 2.1.2 but FTC SDK 11.1.0; do not describe FTC 11.2 compatibility as an upstream Pedro guarantee.
- The isolated fixture verifies only the canonical core Auto against FTC 11.2.0 + Pedro 2.1.2. It does not claim that Panels, all tuners, or the complete Quickstart have been verified on FTC 11.2.
- Use AGP 8.7.0, Gradle 8.9, compile SDK 30, minimum SDK 24, and Java source/target 8 in the fixture, matching FIRST v11.2 build tooling.
- Run this repository's Kotlin tests with JDK 21. The Android fixture may run on the Android Studio JBR 21.
- The canonical example depends only on FTC SDK, Pedro, Java standard library, and the team's `Constants.createFollower(HardwareMap)` entry point. Do not add FTCLib.
- Use iterative `OpMode`, named enum states, non-blocking timers, `Follower.isBusy()`, and `Follower.breakFollowing()`.
- Call the power value a maximum motor-power proportion, not a linear speed limit.
- Keep `CONFIGURATION_COMPLETE=false` and `TEST_STAGE=CONFIG_CHECK` in committed source.
- Do not include 20827 coordinates, mechanisms, constants, subsystem imports, or match strategy.
- Treat 20827 commit `118c28e137334bbbea510d77f1fa384e8b1b5779` as a non-normative architecture case only.
- Preserve the three approved Pedro shared rules and their approval metadata.
- Do not claim hardware behavior is verified unless all four physical stages are actually run and recorded on a robot.
- Keep the complete Java example in exactly one tracked file; the guide may link to it and quote short excerpts but may not duplicate the class.

## File Structure

- `knowledge/examples/pedro/SafePedroAuto.java`: canonical newcomer Auto and single source of truth.
- `knowledge/guides/tools/pedro-pathing.md`: complete Chinese install, tune, configure, test, troubleshoot, and 20827 migration guide.
- `apps/knowledge-cli/src/test/kotlin/org/ftckb/cli/PedroTutorialAcceptanceTest.kt`: offline source, rule, parameter-table, provenance, and guide contract tests.
- `apps/knowledge-cli/src/test/kotlin/org/ftckb/cli/KnowledgeGuideAcceptanceTest.kt`: strengthen local Markdown link and fragment validation.
- `fixtures/pedro-compile/settings.gradle`: isolated Android plugin resolution.
- `fixtures/pedro-compile/build.gradle`: compile the canonical source directly without copying it.
- `fixtures/pedro-compile/gradle.properties`: one pinned version matrix consumed by the fixture and acceptance tests.
- `fixtures/pedro-compile/src/main/AndroidManifest.xml`: minimal Android library manifest.
- `fixtures/pedro-compile/src/main/java/org/firstinspires/ftc/teamcode/pedroPathing/Constants.java`: compile-only adapter for the tutorial's team `Constants` contract.
- `fixtures/pedro-compile/gradlew`, `gradlew.bat`, `gradle/wrapper/*`: byte-for-byte wrapper from FIRST v11.2.
- `build.gradle.kts`: root `verifyPedroExampleCompile` and `verifyPedroRelease` gates.
- `README.md`: expose the canonical example and release verification command.
- `todolist.md`: mark this Pedro newcomer vertical as delivered while leaving physical robot verification and other tool work open.

---

### Task 1: Add the fail-closed canonical Auto under source-contract tests

**Files:**
- Create: `knowledge/examples/pedro/SafePedroAuto.java`
- Create: `apps/knowledge-cli/src/test/kotlin/org/ftckb/cli/PedroTutorialAcceptanceTest.kt`

**Interfaces:**
- Produces: `public class SafePedroAuto extends OpMode` in package `org.firstinspires.ftc.teamcode.examples`.
- Consumes: `org.firstinspires.ftc.teamcode.pedroPathing.Constants.createFollower(HardwareMap):Follower`.
- Produces: compile-time enums `TestStage`, `AutoState`, and `ValidationIssue`; motion gateways `commandPath`, `commandServo`, and `updateFollowerIfAllowed`.
- Later tasks read configuration fields between `CONFIGURE HERE START` and `CONFIGURE HERE END`.

- [ ] **Step 1: Write the failing source-contract test fixture**

Create `PedroTutorialAcceptanceTest.kt` with repository paths and helpers that make missing canonical source fail first:

```kotlin
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
```

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```bash
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
./gradlew :apps:knowledge-cli:test \
  --tests 'org.ftckb.cli.PedroTutorialAcceptanceTest'
```

Expected: FAIL because `knowledge/examples/pedro/SafePedroAuto.java` does not exist.

- [ ] **Step 3: Implement the canonical source with these exact public and safety contracts**

Create the Java file. Use this field/state shape; implementation must keep all motion in the three named gateways:

```java
package org.firstinspires.ftc.teamcode.examples;

import com.pedropathing.follower.Follower;
import com.pedropathing.geometry.BezierLine;
import com.pedropathing.geometry.Pose;
import com.pedropathing.paths.PathChain;
import com.qualcomm.robotcore.eventloop.opmode.Autonomous;
import com.qualcomm.robotcore.eventloop.opmode.OpMode;
import com.qualcomm.robotcore.hardware.Servo;
import com.qualcomm.robotcore.util.ElapsedTime;
import java.util.EnumSet;
import org.firstinspires.ftc.teamcode.pedroPathing.Constants;

@Autonomous(name="Safe Pedro Auto",group="Tutorial")
public class SafePedroAuto extends OpMode {
    // CONFIGURE HERE START
    private static final boolean CONFIGURATION_COMPLETE=false;
    private static final TestStage TEST_STAGE=TestStage.CONFIG_CHECK;
    private static final String SERVO_NAME="YOUR_SERVO_NAME";
    private static final double SERVO_CLOSED_POSITION=Double.NaN;
    private static final double SERVO_OPEN_POSITION=Double.NaN;
    private static final Pose START_POSE=new Pose(Double.NaN,Double.NaN,Double.NaN);
    private static final Pose SCORE_POSE=new Pose(Double.NaN,Double.NaN,Double.NaN);
    private static final Pose SHORT_TEST_POSE=new Pose(Double.NaN,Double.NaN,Double.NaN);
    private static final Pose PARK_POSE=new Pose(Double.NaN,Double.NaN,Double.NaN);
    private static final double RELEASE_WAIT_SECONDS=Double.NaN;
    private static final double SHORT_DRIVE_MAX_POWER=0.20;
    private static final double FULL_AUTO_MAX_POWER=Double.NaN;
    // CONFIGURE HERE END

    private enum TestStage {
        CONFIG_CHECK(false,false),SERVO_ONLY(false,true),SHORT_DRIVE(true,false),FULL_AUTO(true,true);
        final boolean driveAllowed;
        final boolean servoAllowed;
        TestStage(boolean driveAllowed,boolean servoAllowed) {
            this.driveAllowed=driveAllowed;
            this.servoAllowed=servoAllowed;
        }
    }

    private enum AutoState {
        PRELOAD_CLOSED,DRIVE_TO_SCORE,RELEASE,RELEASE_WAIT,DRIVE_TO_PARK,DONE,SAFETY_STOP,STOPPED
    }

    private enum ValidationIssue {
        CONFIGURATION_INCOMPLETE,SERVO_NAME_MISSING_OR_SENTINEL,NON_FINITE_NUMBER,
        SERVO_POSITION_OUT_OF_RANGE,SERVO_POSITIONS_IDENTICAL,WAIT_DURATION_OUT_OF_RANGE,
        POWER_OUT_OF_RANGE,POSE_INVALID,ROUTE_POSES_IDENTICAL,FOLLOWER_INIT_FAILED,
        SERVO_INIT_FAILED,STAGE_RESOURCE_UNAVAILABLE
    }

    private final EnumSet<ValidationIssue> validationIssues=EnumSet.noneOf(ValidationIssue.class);
    private final ElapsedTime stateTimer=new ElapsedTime();
    private boolean safetyLocked=true;
    private String runtimeFailure="none";
    private AutoState autoState=AutoState.SAFETY_STOP;
    private Follower follower;
    private Servo servo;
    private PathChain scorePath;
    private PathChain shortDrivePath;
    private PathChain parkPath;

    @Override
    public void init() {
        validateStaticConfiguration();
        if (validationIssues.isEmpty()) initializeResourcesForSelectedStage();
        safetyLocked=!validationIssues.isEmpty();
        if (!safetyLocked) autoState=AutoState.DONE;
        emitTelemetry();
    }

    @Override
    public void init_loop() {
        emitTelemetry();
    }

    @Override
    public void start() {
        if (safetyLocked) {
            enterSafetyStop("configuration safety lock");
            return;
        }
        try {
            switch (TEST_STAGE) {
                case CONFIG_CHECK:
                    autoState=AutoState.DONE;
                    break;
                case SERVO_ONLY:
                    autoState=AutoState.PRELOAD_CLOSED;
                    if (commandServo(SERVO_CLOSED_POSITION)) stateTimer.reset();
                    break;
                case SHORT_DRIVE:
                    autoState=AutoState.DRIVE_TO_PARK;
                    commandPath(shortDrivePath,SHORT_DRIVE_MAX_POWER);
                    break;
                case FULL_AUTO:
                    autoState=AutoState.PRELOAD_CLOSED;
                    if (commandServo(SERVO_CLOSED_POSITION)) {
                        autoState=AutoState.DRIVE_TO_SCORE;
                        commandPath(scorePath,FULL_AUTO_MAX_POWER);
                    }
                    break;
                default:
                    enterSafetyStop("unknown test stage");
            }
        } catch (RuntimeException exception) {
            enterSafetyStop(exception.getClass().getSimpleName()+": "+exception.getMessage());
        }
    }

    @Override
    public void loop() {
        if (safetyLocked) {
            emitTelemetry();
            return;
        }
        try {
            updateFollowerIfAllowed();
            if (TEST_STAGE==TestStage.SERVO_ONLY) updateServoTest();
            else if (TEST_STAGE==TestStage.SHORT_DRIVE) updateShortDriveTest();
            else if (TEST_STAGE==TestStage.FULL_AUTO) updateFullAuto();
        } catch (RuntimeException exception) {
            enterSafetyStop(exception.getClass().getSimpleName()+": "+exception.getMessage());
        }
        emitTelemetry();
    }

    @Override
    public void stop() {
        if (follower!=null) follower.breakFollowing();
        safetyLocked=true;
        autoState=AutoState.STOPPED;
    }

    private void validateStaticConfiguration() {
        validationIssues.clear();
        if (!CONFIGURATION_COMPLETE) validationIssues.add(ValidationIssue.CONFIGURATION_INCOMPLETE);
        if (SERVO_NAME.trim().isEmpty() || SERVO_NAME.startsWith("YOUR_"))
            validationIssues.add(ValidationIssue.SERVO_NAME_MISSING_OR_SENTINEL);

        double[] numbers={SERVO_CLOSED_POSITION,SERVO_OPEN_POSITION,RELEASE_WAIT_SECONDS,
            SHORT_DRIVE_MAX_POWER,FULL_AUTO_MAX_POWER};
        for (double value:numbers) if (!Double.isFinite(value))
            validationIssues.add(ValidationIssue.NON_FINITE_NUMBER);
        validatePose(START_POSE);
        validatePose(SCORE_POSE);
        validatePose(SHORT_TEST_POSE);
        validatePose(PARK_POSE);

        if (!inClosedUnitRange(SERVO_CLOSED_POSITION) || !inClosedUnitRange(SERVO_OPEN_POSITION))
            validationIssues.add(ValidationIssue.SERVO_POSITION_OUT_OF_RANGE);
        if (Double.compare(SERVO_CLOSED_POSITION,SERVO_OPEN_POSITION)==0)
            validationIssues.add(ValidationIssue.SERVO_POSITIONS_IDENTICAL);
        if (!(RELEASE_WAIT_SECONDS>=0.05 && RELEASE_WAIT_SECONDS<=5.0))
            validationIssues.add(ValidationIssue.WAIT_DURATION_OUT_OF_RANGE);
        if (!(SHORT_DRIVE_MAX_POWER>0 && SHORT_DRIVE_MAX_POWER<=0.30) ||
            !(FULL_AUTO_MAX_POWER>0 && FULL_AUTO_MAX_POWER<=1.0))
            validationIssues.add(ValidationIssue.POWER_OUT_OF_RANGE);
        if (samePosition(START_POSE,SCORE_POSE) || samePosition(START_POSE,SHORT_TEST_POSE) ||
            samePosition(START_POSE,PARK_POSE) || samePosition(SCORE_POSE,PARK_POSE))
            validationIssues.add(ValidationIssue.ROUTE_POSES_IDENTICAL);
    }

    private void validatePose(Pose pose) {
        if (pose==null || !Double.isFinite(pose.getX()) || !Double.isFinite(pose.getY()) ||
            !Double.isFinite(pose.getHeading())) {
            validationIssues.add(ValidationIssue.POSE_INVALID);
            validationIssues.add(ValidationIssue.NON_FINITE_NUMBER);
        }
    }

    private void initializeResourcesForSelectedStage() {
        boolean checkAllResources=TEST_STAGE==TestStage.CONFIG_CHECK;
        if (TEST_STAGE.driveAllowed || checkAllResources) {
            try {
                follower=Constants.createFollower(hardwareMap);
                follower.setStartingPose(START_POSE);
                buildPaths();
            } catch (RuntimeException exception) {
                validationIssues.add(ValidationIssue.FOLLOWER_INIT_FAILED);
            }
        }
        if (TEST_STAGE.servoAllowed || checkAllResources) {
            try {
                servo=hardwareMap.get(Servo.class,SERVO_NAME);
            } catch (RuntimeException exception) {
                validationIssues.add(ValidationIssue.SERVO_INIT_FAILED);
            }
        }
        if (((TEST_STAGE.driveAllowed || checkAllResources) && follower==null) ||
            ((TEST_STAGE.servoAllowed || checkAllResources) && servo==null))
            validationIssues.add(ValidationIssue.STAGE_RESOURCE_UNAVAILABLE);
    }

    private void buildPaths() {
        scorePath=buildLine(START_POSE,SCORE_POSE);
        shortDrivePath=buildLine(START_POSE,SHORT_TEST_POSE);
        parkPath=buildLine(SCORE_POSE,PARK_POSE);
    }

    private PathChain buildLine(Pose start,Pose end) {
        return follower.pathBuilder()
            .addPath(new BezierLine(start,end))
            .setLinearHeadingInterpolation(start.getHeading(),end.getHeading())
            .build();
    }

    private void updateServoTest() {
        if (autoState==AutoState.PRELOAD_CLOSED && stateTimer.seconds()>=RELEASE_WAIT_SECONDS) {
            autoState=AutoState.RELEASE;
            if (commandServo(SERVO_OPEN_POSITION)) autoState=AutoState.DONE;
        }
    }

    private void updateShortDriveTest() {
        if (autoState==AutoState.DRIVE_TO_PARK && !follower.isBusy()) autoState=AutoState.DONE;
    }

    private void updateFullAuto() {
        switch (autoState) {
            case DRIVE_TO_SCORE:
                if (!follower.isBusy()) {
                    autoState=AutoState.RELEASE;
                    if (commandServo(SERVO_OPEN_POSITION)) {
                        stateTimer.reset();
                        autoState=AutoState.RELEASE_WAIT;
                    }
                }
                break;
            case RELEASE_WAIT:
                if (stateTimer.seconds()>=RELEASE_WAIT_SECONDS) {
                    autoState=AutoState.DRIVE_TO_PARK;
                    commandPath(parkPath,FULL_AUTO_MAX_POWER);
                }
                break;
            case DRIVE_TO_PARK:
                if (!follower.isBusy()) autoState=AutoState.DONE;
                break;
            case DONE:
                break;
            default:
                enterSafetyStop("unexpected full-auto state "+autoState);
        }
    }

    private boolean commandPath(PathChain path,double maxPower) {
        if (safetyLocked || !TEST_STAGE.driveAllowed || follower==null || path==null) {
            enterSafetyStop("drive command rejected");
            return false;
        }
        follower.followPath(path,maxPower,false);
        return true;
    }

    private boolean commandServo(double position) {
        if (safetyLocked || !TEST_STAGE.servoAllowed || servo==null || !inClosedUnitRange(position)) {
            enterSafetyStop("servo command rejected");
            return false;
        }
        servo.setPosition(position);
        return true;
    }

    private void updateFollowerIfAllowed() {
        if (safetyLocked || !TEST_STAGE.driveAllowed || follower==null) return;
        follower.update();
    }

    private void enterSafetyStop(String reason) {
        runtimeFailure=reason==null ? "unknown runtime failure" : reason;
        if (follower!=null) follower.breakFollowing();
        safetyLocked=true;
        autoState=AutoState.SAFETY_STOP;
    }

    private void emitTelemetry() {
        telemetry.addData("configuration complete",CONFIGURATION_COMPLETE);
        telemetry.addData("test stage",TEST_STAGE);
        telemetry.addData("auto state",autoState);
        telemetry.addData("safety locked",safetyLocked);
        telemetry.addData("runtime failure",runtimeFailure);
        for (ValidationIssue issue: validationIssues) telemetry.addLine("CONFIG: "+issue);
        if (follower!=null) {
            telemetry.addData("x (in)",follower.getPose().getX());
            telemetry.addData("y (in)",follower.getPose().getY());
            telemetry.addData("heading (rad)",follower.getPose().getHeading());
            telemetry.addData("follower busy",follower.isBusy());
        }
        telemetry.addData("state elapsed (s)",stateTimer.seconds());
        telemetry.update();
    }

    private static boolean inClosedUnitRange(double value) {
        return Double.isFinite(value) && value>=0 && value<=1;
    }

    private static boolean samePosition(Pose first,Pose second) {
        if (first==null || second==null) return false;
        return Math.hypot(first.getX()-second.getX(),first.getY()-second.getY())<1e-6;
    }
}
```

Before making the test green, tighten `methodBody` or motion-gateway assertions if an implementation could move a command outside the guarded gateway and still pass. Do not loosen the safety contract to accommodate the implementation.

- [ ] **Step 4: Run focused tests and verify GREEN**

Run the same focused command. Expected: all six `PedroTutorialAcceptanceTest` tests PASS.

- [ ] **Step 5: Review and commit Task 1**

```bash
git diff --check
git add -- knowledge/examples/pedro/SafePedroAuto.java \
  apps/knowledge-cli/src/test/kotlin/org/ftckb/cli/PedroTutorialAcceptanceTest.kt
git commit -m "feat: add safe Pedro Auto example"
```

Review gate: reject the task if any committed default can move hardware, if Servo commands occur during INIT/STOP, or if a motion command bypasses its gateway.

---

### Task 2: Compile the canonical source against pinned FTC and Pedro artifacts

**Files:**
- Create: `fixtures/pedro-compile/settings.gradle`
- Create: `fixtures/pedro-compile/build.gradle`
- Create: `fixtures/pedro-compile/gradle.properties`
- Create: `fixtures/pedro-compile/src/main/AndroidManifest.xml`
- Create: `fixtures/pedro-compile/src/main/java/org/firstinspires/ftc/teamcode/pedroPathing/Constants.java`
- Create: `fixtures/pedro-compile/gradlew`
- Create: `fixtures/pedro-compile/gradlew.bat`
- Create: `fixtures/pedro-compile/gradle/wrapper/gradle-wrapper.jar`
- Create: `fixtures/pedro-compile/gradle/wrapper/gradle-wrapper.properties`
- Modify: `build.gradle.kts`
- Modify: `apps/knowledge-cli/src/test/kotlin/org/ftckb/cli/PedroTutorialAcceptanceTest.kt`

**Interfaces:**
- Produces: standalone `fixtures/pedro-compile` Android library build.
- Produces: root tasks `verifyPedroExampleCompile` and `verifyPedroRelease`.
- Compiles the canonical file in place through `sourceSets`; it never copies or regenerates `SafePedroAuto.java`.

- [ ] **Step 1: Add a failing version-matrix acceptance test**

Append this test and helper to `PedroTutorialAcceptanceTest`:

```kotlin
private val fixtureRoot=repositoryRoot.resolve("fixtures/pedro-compile")

@Test
fun `compile fixture pins the reviewed release matrix`() {
    val properties=java.util.Properties().apply {
        Files.newBufferedReader(fixtureRoot.resolve("gradle.properties")).use(::load)
    }
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
```

- [ ] **Step 2: Run focused tests and verify RED**

Expected: FAIL because `fixtures/pedro-compile/gradle.properties` does not exist.

- [ ] **Step 3: Add the isolated fixture text files**

`settings.gradle`:

```groovy
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id 'com.android.library' version providers.gradleProperty('agpVersion').get()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name='pedro-example-compile'
```

`gradle.properties`:

```properties
org.gradle.jvmargs=-Xmx1536m
android.useAndroidX=true
agpVersion=8.7.0
compileSdk=30
minSdk=24
ftcSdkVersion=11.2.0
ftcSdkTag=v11.2
ftcSdkCommit=4ed7c4666aec265a6fd9e674ca40462e9dfe4bf8
pedroVersion=2.1.2
pedroCommit=96df977d30329eef57c226cf1e6854026f4dfe4f
pedroQuickstartCommit=d3aea9ca3c5b4c09eded8580229b86996480ee89
```

`build.gradle`:

```groovy
plugins { id 'com.android.library' }

android {
    namespace 'org.ftckb.fixtures.pedro'
    compileSdk providers.gradleProperty('compileSdk').get().toInteger()
    defaultConfig { minSdk providers.gradleProperty('minSdk').get().toInteger() }
    compileOptions {
        sourceCompatibility JavaVersion.VERSION_1_8
        targetCompatibility JavaVersion.VERSION_1_8
    }
    sourceSets {
        main.java.srcDirs += '../../knowledge/examples/pedro'
    }
}

dependencies {
    implementation "org.firstinspires.ftc:RobotCore:${ftcSdkVersion}"
    implementation "org.firstinspires.ftc:Hardware:${ftcSdkVersion}"
    implementation "com.pedropathing:ftc:${pedroVersion}"
}
```

Minimal manifest:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android" />
```

Compile-only `Constants.java`:

```java
package org.firstinspires.ftc.teamcode.pedroPathing;

import com.pedropathing.follower.Follower;
import com.pedropathing.follower.FollowerConstants;
import com.pedropathing.ftc.FollowerBuilder;
import com.qualcomm.robotcore.hardware.HardwareMap;

public final class Constants {
    private Constants() {}

    public static Follower createFollower(HardwareMap hardwareMap) {
        return new FollowerBuilder(new FollowerConstants(),hardwareMap).build();
    }
}
```

Add a file header stating that this is a non-runnable compile adapter based on the public `Constants.createFollower` contract, not robot constants.

- [ ] **Step 4: Copy and verify the official Gradle 8.9 wrapper**

Fetch FIRST v11.2 into a temporary directory, verify commit `4ed7c4666aec265a6fd9e674ca40462e9dfe4bf8`, then mechanically copy `gradlew`, `gradlew.bat`, and `gradle/wrapper/` into the fixture. Verify these SHA-256 values before staging:

```text
gradlew                                      874d75d37bf38c810a8314e0b2f78a3c77fce9437963ae33cec8543d92662b61
gradlew.bat                                  f4f428c5626b3d90cef3bd4e7fd3ad3ea5760442db8c09d586b5bfe031dbe5e3
gradle/wrapper/gradle-wrapper.jar            96f793a18e056c23ffeec67c1f3bb8eccff5a4a407fc9ceac183527e7eedf4b6
gradle/wrapper/gradle-wrapper.properties     ef02d8fe6df48d7e49abb80fea9caa3eb0fc562ee361380a480dabbba0ef07c5
```

The wrapper properties must contain:

```properties
distributionUrl=https\://services.gradle.org/distributions/gradle-8.9-bin.zip
```

- [ ] **Step 5: Add root verification tasks**

Append to `build.gradle.kts`:

```kotlin
val verifyPedroExampleCompile=tasks.register<Exec>("verifyPedroExampleCompile") {
    group="verification"
    description="Compiles the canonical Pedro Auto against the pinned Android fixture"
    workingDir(layout.projectDirectory.dir("fixtures/pedro-compile"))
    val wrapper=if (System.getProperty("os.name").lowercase().contains("windows")) "gradlew.bat" else "./gradlew"
    commandLine(wrapper,"clean","compileDebugJavaWithJavac","--no-daemon")
    doFirst {
        val sdk=System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
            ?: error("Set ANDROID_HOME or ANDROID_SDK_ROOT before verifyPedroExampleCompile")
        environment("ANDROID_HOME",sdk)
    }
}

tasks.register("verifyPedroRelease") {
    group="verification"
    description="Runs all knowledge tests and the pinned Pedro Android compile fixture"
    dependsOn(
        ":modules:domain:test",
        ":modules:knowledge:test",
        ":apps:knowledge-cli:test",
        verifyPedroExampleCompile
    )
}
```

- [ ] **Step 6: Run fast and real compile verification**

```bash
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
./gradlew :apps:knowledge-cli:test --tests 'org.ftckb.cli.PedroTutorialAcceptanceTest'

JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
ANDROID_HOME="$HOME/Library/Android/sdk" \
./gradlew verifyPedroExampleCompile
```

Expected: focused tests PASS; Android task ends with `BUILD SUCCESSFUL` and includes `compileDebugJavaWithJavac`.

- [ ] **Step 7: Review and commit Task 2**

```bash
git diff --check
git add -- fixtures/pedro-compile build.gradle.kts \
  apps/knowledge-cli/src/test/kotlin/org/ftckb/cli/PedroTutorialAcceptanceTest.kt
git commit -m "test: compile Pedro Auto against pinned FTC SDK"
```

Review gate: confirm there is only one `SafePedroAuto.java`, the fixture is not included in `settings.gradle.kts`, and the ordinary root test task does not silently require Android SDK or network access.

---

### Task 3: Rewrite the Pedro guide around the canonical Auto and staged learning flow

**Files:**
- Modify: `knowledge/guides/tools/pedro-pathing.md`
- Modify: `apps/knowledge-cli/src/test/kotlin/org/ftckb/cli/PedroTutorialAcceptanceTest.kt`

**Interfaces:**
- Consumes: the twelve fields in the canonical `CONFIGURE HERE` block.
- Produces: one five-column table under heading `## SafePedroAuto 参数字典` whose first-column code values exactly equal those twelve fields.
- Produces: a relative link to `../../examples/pedro/SafePedroAuto.java`.

- [ ] **Step 1: Add failing guide-contract tests**

Append:

```kotlin
private fun markdownTableRows(markdown:String,heading:String):List<List<String>> {
    val section=markdown.substringAfter("## $heading").substringBefore("\n## ")
    return section.lineSequence().filter { it.trim().startsWith("|") }
        .map { line -> line.trim().trim('|').split('|').map(String::trim) }
        .filterNot { row -> row.all { cell -> cell.matches(Regex("[-: ]+")) } }
        .toList()
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
```

- [ ] **Step 2: Run focused tests and verify RED**

Expected: FAIL because the current guide embeds `PedroSmokeTest`, has no canonical link, no exact parameter table, and no four-stage flow.

- [ ] **Step 3: Replace the guide's version/install opening with a verified matrix**

Keep the useful coordinate, constants, tuning, validation, troubleshooting, safety, rule, and official-source content, but reorganize it into this exact beginner order:

1. scope and provenance labels;
2. version matrix and two installation routes;
3. coordinates;
4. four Constants categories;
5. all localizer choices in Pedro 2.1.2;
6. one complete beginner localizer path;
7. official tuning sequence;
8. canonical Auto parameter dictionary;
9. four-stage test checklist;
10. telemetry and troubleshooting;
11. 20827-inspired advanced mapping;
12. related rule IDs and sources.

The version table must say:

| Item | Pin | Meaning |
|---|---|---|
| FIRST FTC SDK | v11.2 / 11.2.0 / `4ed7c466...` | Current FIRST release used by this project's core-example compile fixture |
| Pedro library | v2.1.2 / `96df977...` | Stable Pedro API used by the example |
| Pedro Quickstart snapshot | `d3aea9ca...` | Pedro 2.1.2 upstream example/tuner snapshot; still based on FTC 11.1.0 |

State explicitly: FTC 11.2 + Pedro 2.1.2 is **本项目编译验证**, not an upstream Pedro compatibility guarantee; compile verification is not hardware verification.

- [ ] **Step 4: Document installation without mixing incompatible claims**

Provide two named routes:

- `Route A — official Quickstart snapshot`: checkout `d3aea9c...`; coherent upstream baseline, but FTC SDK 11.1.0.
- `Route B — current FIRST v11.2 team project`: add exact `com.pedropathing:ftc:2.1.2`; use this repository's fixture result only for the core example; separately validate copied tuners/Panels and the official manual instruction to use compile SDK 34.

For every Gradle edit, name the exact file and block. After each route include Sync, build, deploy, and minimal runtime observation. Do not claim that the FTC 11.2 compile SDK 30 probe validates Panels or all tuners.

- [ ] **Step 5: Document all localizer choices and one complete beginner flow**

List the v2.1.2 `FollowerBuilder` choices exactly:

- drive encoders;
- OTOS;
- Pinpoint;
- three-wheel + IMU;
- three-wheel;
- two-wheel;
- custom `Localizer` through `setLocalizer`.

Use Pinpoint as the single complete worked flow because both reference teams use that hardware. All numeric offsets, encoder directions, hardware names, and resolution choices remain sentinels or descriptive steps; do not copy either team's values. Include the five-column contract for every Pinpoint field introduced.

- [ ] **Step 6: Replace the embedded full smoke test with the canonical example link and excerpts**

Link:

```markdown
[完整的 `SafePedroAuto.java`](../../examples/pedro/SafePedroAuto.java)
```

Explain the lifecycle and show only short excerpts for:

- the `CONFIGURE HERE` marker;
- the `TestStage` capability matrix;
- `commandPath`/`commandServo` safety guards;
- `follower.update()` + enum state transitions; and
- `stop()` calling `breakFollowing()`.

Never paste `public class SafePedroAuto` into the guide.

- [ ] **Step 7: Add the exact five-column parameter dictionary**

Use one row each for:

```text
CONFIGURATION_COMPLETE
TEST_STAGE
SERVO_NAME
SERVO_CLOSED_POSITION
SERVO_OPEN_POSITION
START_POSE
SCORE_POSE
SHORT_TEST_POSE
PARK_POSE
RELEASE_WAIT_SECONDS
SHORT_DRIVE_MAX_POWER
FULL_AUTO_MAX_POWER
```

The final four cells in every row must answer: what value to enter, how to obtain it, official/tutorial unit or range, and an observable validation. Examples:

- Servo positions: normalized `[0,1]`, determined by mechanically safe incremental tests, not copied endpoints.
- Poses: Pedro field coordinates in inches and heading in radians, measured from the declared origin.
- release wait: tutorial bound `0.05–5.0 s`, measured against actual mechanism completion.
- short power: `(0,0.30]`; full power: `(0,1]`; both are motor-power proportions, not in/s.

- [ ] **Step 8: Add four physical-stage checklists and honest result labels**

Each stage needs prerequisites, allowed actions, telemetry to record, pass condition, reviewer/date fields, and an explicit statement that advancing requires editing `TEST_STAGE` and rebuilding.

Use result labels exactly:

- `内容已验证`
- `编译已验证`
- `硬件阶段未验证`
- `硬件四阶段已验证：<robot/reviewer/date>` only after real evidence exists.

- [ ] **Step 9: Run focused and existing guide tests**

```bash
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
./gradlew :apps:knowledge-cli:test \
  --tests 'org.ftckb.cli.PedroTutorialAcceptanceTest' \
  --tests 'org.ftckb.cli.KnowledgeGuideAcceptanceTest'
```

Expected: both classes PASS.

- [ ] **Step 10: Review and commit Task 3**

```bash
git diff --check
git add -- knowledge/guides/tools/pedro-pathing.md \
  apps/knowledge-cli/src/test/kotlin/org/ftckb/cli/PedroTutorialAcceptanceTest.kt
git commit -m "docs: add Pedro newcomer Auto tutorial"
```

Review gate: ask a reviewer to locate every value they would need to fill without reading Java internals. Reject if any row lacks a measurement source, unit/range, or observable validation.

---

### Task 4: Add the 20827 architecture mapping and strengthen provenance/link checks

**Files:**
- Modify: `knowledge/guides/tools/pedro-pathing.md`
- Modify: `apps/knowledge-cli/src/test/kotlin/org/ftckb/cli/PedroTutorialAcceptanceTest.kt`
- Modify: `apps/knowledge-cli/src/test/kotlin/org/ftckb/cli/KnowledgeGuideAcceptanceTest.kt`

**Interfaces:**
- Consumes: 20827 commit `118c28e137334bbbea510d77f1fa384e8b1b5779`.
- Produces: advanced mapping from beginner constructs to Base Auto, alliance subclasses, Follower factory, FTCLib scheduler, and dynamic PathChains.

- [ ] **Step 1: Add a failing provenance test**

Append:

```kotlin
@Test
fun `20827 is cited only as a pinned non normative architecture case`() {
    val guide=Files.readString(guidePath)
    val commit="118c28e137334bbbea510d77f1fa384e8b1b5779"
    assertTrue(commit in guide)
    setOf("TopAutoBase","BottomAutoBase","TopAutoRed","TopAutoBlue","Constants.createFollower","XKCommandOpmode")
        .forEach { assertTrue(it in guide,it) }
    assertTrue("非规范" in guide)
    assertTrue("不是 Pedro 官方要求" in guide)
}
```

- [ ] **Step 2: Run focused test and verify RED**

Expected: FAIL until the advanced mapping contains all pinned symbols and boundary language.

- [ ] **Step 3: Write the advanced mapping as a comparison table**

Use these exact mappings:

| Beginner example | 20827 case | Migration lesson |
|---|---|---|
| one `SafePedroAuto` | `TopAutoBase` / `BottomAutoBase` | move route flow into a reusable base only after one route is understood |
| Pose constants in one file | `TopAutoRed` / `TopAutoBlue` constructor parameters | thin alliance classes supply coordinates without duplicating state flow |
| `Constants.createFollower` | same centralized factory pattern | keep drivetrain/localizer construction out of match logic |
| enum state machine | integer `pathState` in the case study | retain named enum states for newcomer code; integer states are not required |
| direct Servo gateway | `XKCommandOpmode` + FTCLib scheduler | command framework is an optional mechanism-coordination upgrade |
| prebuilt paths | `Supplier<PathChain>` from current pose | dynamic return paths are advanced and require explicit current-pose reasoning |

Link to commit-pinned GitHub blob/tree URLs. Identify observed team code as provenance, not technical Pedro authority. Do not reproduce route coordinates or mechanism sequence.

- [ ] **Step 4: Strengthen local Markdown fragment checks**

Replace the existing link test's `startsWith("#")` skip with local-target and heading-fragment validation. Implement a deterministic GitHub-style subset slugger sufficient for repository headings:

```kotlin
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
```

For every non-HTTP link:

```kotlin
val filePart=link.substringBefore('#')
val fragment=link.substringAfter('#',"")
val target=if (filePart.isBlank()) guide else guide.parent.resolve(filePart).normalize()
assertTrue(Files.exists(target),"$guide has missing link $link")
if (fragment.isNotBlank() && target.toString().endsWith(".md")) {
    assertTrue(fragment in headingSlugs(Files.readString(target)),"$guide has missing fragment $link")
}
```

External URL syntax remains offline: require absolute HTTPS and no user-info; do not fetch network pages inside JUnit.

- [ ] **Step 5: Run guide and provenance tests**

Run both test classes. Expected: PASS, including all existing guide links and fragments.

- [ ] **Step 6: Review and commit Task 4**

```bash
git diff --check
git add -- knowledge/guides/tools/pedro-pathing.md \
  apps/knowledge-cli/src/test/kotlin/org/ftckb/cli/PedroTutorialAcceptanceTest.kt \
  apps/knowledge-cli/src/test/kotlin/org/ftckb/cli/KnowledgeGuideAcceptanceTest.kt
git commit -m "docs: map Pedro tutorial to 20827 Auto architecture"
```

Review gate: a reviewer must be able to delete the whole 20827 section without changing any official Pedro instruction or machine rule.

---

### Task 5: Expose the tutorial and close release acceptance

**Files:**
- Modify: `README.md`
- Modify: `todolist.md`
- Modify: `apps/knowledge-cli/src/test/kotlin/org/ftckb/cli/PedroTutorialAcceptanceTest.kt`

**Interfaces:**
- Produces: README links to the guide and canonical Java source plus exact fast/full verification commands.
- Produces: honest todo state that separates delivered content/compile work from pending robot testing.

- [ ] **Step 1: Add a failing discoverability test**

Append:

```kotlin
@Test
fun `readme exposes tutorial example and release verification`() {
    val readme=Files.readString(repositoryRoot.resolve("README.md"))
    assertTrue("knowledge/guides/tools/pedro-pathing.md" in readme)
    assertTrue("knowledge/examples/pedro/SafePedroAuto.java" in readme)
    assertTrue("verifyPedroRelease" in readme)
    assertTrue("ANDROID_HOME" in readme || "ANDROID_SDK_ROOT" in readme)
}
```

- [ ] **Step 2: Run focused test and verify RED**

Expected: FAIL because README currently links only the guide and does not expose the canonical source or release gate.

- [ ] **Step 3: Update README without duplicating the manual**

Under the current six-guide list, add:

- a direct link to `knowledge/examples/pedro/SafePedroAuto.java` labeled as default-locked and non-runnable until configured;
- the fast command for `PedroTutorialAcceptanceTest`;
- the full `verifyPedroRelease` command with JDK 21 and `ANDROID_HOME`/`ANDROID_SDK_ROOT` prerequisite;
- result semantics: content tests, Android compilation, and physical robot verification are three different claims.

Do not paste the twelve-parameter table or four checklists into README; link to the guide anchors.

- [ ] **Step 4: Update todolist with split completion state**

Mark complete only:

- pinned Pedro evidence and version matrix;
- newcomer parameter dictionary;
- canonical safe Java example;
- source-contract tests;
- FTC 11.2 + Pedro 2.1.2 compile fixture;
- 20827 non-normative architecture mapping.

Keep open:

- four-stage physical robot acceptance;
- Pedro upgrade compatibility lane;
- Limelight and goBILDA tutorials receiving equivalent runnable staged examples;
- full IDE/Agent integration.

- [ ] **Step 5: Run the complete release gate**

```bash
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
ANDROID_HOME="$HOME/Library/Android/sdk" \
./gradlew clean verifyPedroRelease

JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
./gradlew :apps:knowledge-cli:run --args='validate knowledge' --quiet

JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
./gradlew :apps:knowledge-cli:run \
  --args='resolve knowledge --team 20827 --season 2025-2026' --quiet
```

Expected:

- all root tests PASS;
- fixture `compileDebugJavaWithJavac` PASS;
- `validation=ok rules=23`;
- Pedro approved IDs appear in active output for 20827;
- existing official priority and conflict behavior remains unchanged.

- [ ] **Step 6: Perform final static and scope review**

```bash
test "$(find knowledge -name 'SafePedroAuto.java' -print | wc -l | tr -d ' ')" = "1"
rg -n 'Thread\.sleep|\bsleep\s*\(|LinearOpMode|CommandScheduler|com\.arcrobotics|20827' \
  knowledge/examples/pedro/SafePedroAuto.java
git diff --check
git status --short
```

Expected: exactly one canonical source; forbidden-source search prints nothing; only planned files changed.

Ask an independent code reviewer to check:

1. safety locks and all motion gateways;
2. FTC/Pedro API compatibility and version wording;
3. parameter-table equality with source fields;
4. no team values or normative claims leaked from 20827; and
5. honest separation of content, compile, and hardware verification.

Fix every Critical/Important finding and rerun the full release gate.

- [ ] **Step 7: Commit Task 5**

```bash
git add -- README.md todolist.md \
  apps/knowledge-cli/src/test/kotlin/org/ftckb/cli/PedroTutorialAcceptanceTest.kt
git commit -m "docs: publish Pedro newcomer tutorial workflow"
```

Final handoff must report exact test counts, fixture build result, knowledge rule count, active-rule output, commit SHAs, and whether any physical robot stage was actually verified.
