package org.firstinspires.ftc.teamcode.examples;

import com.pedropathing.api.Paths;
import com.pedropathing.follower.Follower;
import com.pedropathing.math.Pose;
import com.pedropathing.paths.Path;
import com.qualcomm.robotcore.eventloop.opmode.Autonomous;
import com.qualcomm.robotcore.eventloop.opmode.OpMode;
import com.qualcomm.robotcore.hardware.Servo;
import com.qualcomm.robotcore.util.ElapsedTime;
import java.util.EnumSet;
import org.firstinspires.ftc.teamcode.pedro.Constants;

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
    private static final double SHORT_DRIVE_MAX_PATH_SPEED=0.20;
    private static final double FULL_AUTO_MAX_PATH_SPEED=Double.NaN;
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

    private enum LastServoCommand {
        NONE,CLOSED,OPEN
    }

    private enum ValidationIssue {
        CONFIGURATION_INCOMPLETE,SERVO_NAME_MISSING_OR_SENTINEL,NON_FINITE_NUMBER,
        SERVO_POSITION_OUT_OF_RANGE,SERVO_POSITIONS_IDENTICAL,WAIT_DURATION_OUT_OF_RANGE,
        PATH_SPEED_OUT_OF_RANGE,POSE_INVALID,ROUTE_POSES_IDENTICAL,FOLLOWER_INIT_FAILED,
        SERVO_INIT_FAILED,STAGE_RESOURCE_UNAVAILABLE
    }

    private final EnumSet<ValidationIssue> validationIssues=EnumSet.noneOf(ValidationIssue.class);
    private final ElapsedTime stateTimer=new ElapsedTime();
    private boolean safetyLocked=true;
    private String runtimeFailure="none";
    private AutoState autoState=AutoState.SAFETY_STOP;
    private LastServoCommand lastServoCommand=LastServoCommand.NONE;
    private Follower follower;
    private Servo servo;
    private Path scorePath;
    private Path shortDrivePath;
    private Path parkPath;

    @Override
    public void init() {
        validateStaticConfiguration();
        if (validationIssues.isEmpty()) initializeResourcesForSelectedStage();
        safetyLocked=!validationIssues.isEmpty();
        if (!safetyLocked) transitionTo(AutoState.DONE);
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
                    transitionTo(AutoState.DONE);
                    break;
                case SERVO_ONLY:
                    transitionTo(AutoState.PRELOAD_CLOSED);
                    commandServo(SERVO_CLOSED_POSITION,LastServoCommand.CLOSED);
                    break;
                case SHORT_DRIVE:
                    transitionTo(AutoState.DRIVE_TO_PARK);
                    commandPath(shortDrivePath,SHORT_DRIVE_MAX_PATH_SPEED);
                    break;
                case FULL_AUTO:
                    transitionTo(AutoState.PRELOAD_CLOSED);
                    if (commandServo(SERVO_CLOSED_POSITION,LastServoCommand.CLOSED)) {
                        transitionTo(AutoState.DRIVE_TO_SCORE);
                        commandPath(scorePath,FULL_AUTO_MAX_PATH_SPEED);
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
            emitTelemetrySafely();
            return;
        }
        try {
            updateFollowerIfAllowed();
            if (TEST_STAGE==TestStage.SERVO_ONLY) updateServoTest();
            else if (TEST_STAGE==TestStage.SHORT_DRIVE) updateShortDriveTest();
            else if (TEST_STAGE==TestStage.FULL_AUTO) updateFullAuto();
        } catch (RuntimeException exception) {
            enterSafetyStop(exception.getClass().getSimpleName()+": "+exception.getMessage());
        } finally {
            emitTelemetrySafely();
        }
    }

    @Override
    public void stop() {
        safetyLocked=true;
        transitionTo(AutoState.STOPPED);
        stopFollowingBestEffort();
    }

    private void validateStaticConfiguration() {
        validationIssues.clear();
        if (!CONFIGURATION_COMPLETE) validationIssues.add(ValidationIssue.CONFIGURATION_INCOMPLETE);
        if (SERVO_NAME==null||SERVO_NAME.trim().isEmpty()||SERVO_NAME.startsWith("YOUR_"))
            validationIssues.add(ValidationIssue.SERVO_NAME_MISSING_OR_SENTINEL);

        double[] numbers={SERVO_CLOSED_POSITION,SERVO_OPEN_POSITION,RELEASE_WAIT_SECONDS,
            SHORT_DRIVE_MAX_PATH_SPEED,FULL_AUTO_MAX_PATH_SPEED};
        for (double value:numbers) if (!Double.isFinite(value))
            validationIssues.add(ValidationIssue.NON_FINITE_NUMBER);
        validatePose(START_POSE);
        validatePose(SCORE_POSE);
        validatePose(SHORT_TEST_POSE);
        validatePose(PARK_POSE);

        if (!inClosedUnitRange(SERVO_CLOSED_POSITION)||!inClosedUnitRange(SERVO_OPEN_POSITION))
            validationIssues.add(ValidationIssue.SERVO_POSITION_OUT_OF_RANGE);
        if (Double.compare(SERVO_CLOSED_POSITION,SERVO_OPEN_POSITION)==0)
            validationIssues.add(ValidationIssue.SERVO_POSITIONS_IDENTICAL);
        if (!(RELEASE_WAIT_SECONDS>=0.05&&RELEASE_WAIT_SECONDS<=5.0))
            validationIssues.add(ValidationIssue.WAIT_DURATION_OUT_OF_RANGE);
        if (!inSpeedFractionRange(SHORT_DRIVE_MAX_PATH_SPEED)||SHORT_DRIVE_MAX_PATH_SPEED>0.30||
            !inSpeedFractionRange(FULL_AUTO_MAX_PATH_SPEED))
            validationIssues.add(ValidationIssue.PATH_SPEED_OUT_OF_RANGE);
        if (samePosition(START_POSE,SCORE_POSE)||samePosition(START_POSE,SHORT_TEST_POSE)||
            samePosition(START_POSE,PARK_POSE)||samePosition(SCORE_POSE,PARK_POSE))
            validationIssues.add(ValidationIssue.ROUTE_POSES_IDENTICAL);
    }

    private void validatePose(Pose pose) {
        if (pose==null||!Double.isFinite(pose.x())||!Double.isFinite(pose.y())||
            !Double.isFinite(pose.heading())) {
            validationIssues.add(ValidationIssue.POSE_INVALID);
            validationIssues.add(ValidationIssue.NON_FINITE_NUMBER);
        }
    }

    private void initializeResourcesForSelectedStage() {
        boolean checkAllResources=TEST_STAGE==TestStage.CONFIG_CHECK;
        if (TEST_STAGE.driveAllowed||checkAllResources) {
            try {
                follower=Constants.create(hardwareMap);
                follower.setPose(START_POSE);
                buildPaths();
            } catch (RuntimeException exception) {
                validationIssues.add(ValidationIssue.FOLLOWER_INIT_FAILED);
            }
        }
        if (TEST_STAGE.servoAllowed||checkAllResources) {
            try {
                servo=hardwareMap.get(Servo.class,SERVO_NAME);
            } catch (RuntimeException exception) {
                validationIssues.add(ValidationIssue.SERVO_INIT_FAILED);
            }
        }
        if (((TEST_STAGE.driveAllowed||checkAllResources)&&follower==null)||
            ((TEST_STAGE.servoAllowed||checkAllResources)&&servo==null))
            validationIssues.add(ValidationIssue.STAGE_RESOURCE_UNAVAILABLE);
    }

    private void buildPaths() {
        scorePath=buildLine(START_POSE,SCORE_POSE);
        shortDrivePath=buildLine(START_POSE,SHORT_TEST_POSE);
        parkPath=buildLine(SCORE_POSE,PARK_POSE);
    }

    private Path buildLine(Pose start,Pose end) {
        return Paths.line(start,end).linear(start,end);
    }

    private void updateServoTest() {
        if (autoState==AutoState.PRELOAD_CLOSED&&stateTimer.seconds()>=RELEASE_WAIT_SECONDS) {
            transitionTo(AutoState.RELEASE);
            if (commandServo(SERVO_OPEN_POSITION,LastServoCommand.OPEN)) transitionTo(AutoState.DONE);
        }
    }

    private void updateShortDriveTest() {
        if (autoState==AutoState.DRIVE_TO_PARK&&!follower.isBusy()&&finishPathOutput("short drive"))
            transitionTo(AutoState.DONE);
    }

    private void updateFullAuto() {
        switch (autoState) {
            case DRIVE_TO_SCORE:
                if (!follower.isBusy()) {
                    if (!finishPathOutput("score")) break;
                    transitionTo(AutoState.RELEASE);
                    if (commandServo(SERVO_OPEN_POSITION,LastServoCommand.OPEN)) {
                        transitionTo(AutoState.RELEASE_WAIT);
                    }
                }
                break;
            case RELEASE_WAIT:
                if (stateTimer.seconds()>=RELEASE_WAIT_SECONDS) {
                    transitionTo(AutoState.DRIVE_TO_PARK);
                    commandPath(parkPath,FULL_AUTO_MAX_PATH_SPEED);
                }
                break;
            case DRIVE_TO_PARK:
                if (!follower.isBusy()&&finishPathOutput("park")) transitionTo(AutoState.DONE);
                break;
            case DONE:
                break;
            default:
                enterSafetyStop("unexpected full-auto state "+autoState);
        }
    }

    private boolean commandPath(Path path,double maxPathSpeed) {
        if (safetyLocked||!TEST_STAGE.driveAllowed||follower==null||path==null||
            !inSpeedFractionRange(maxPathSpeed)) {
            enterSafetyStop("drive command rejected");
            return false;
        }
        if (!applyPathSpeedLimit(maxPathSpeed)) return false;
        follower.follow(path);
        return true;
    }

    private boolean applyPathSpeedLimit(double maxPathSpeed) {
        try {
            Constants.foresightConfig.maxPathSpeed.set(maxPathSpeed);
            return true;
        } catch (RuntimeException exception) {
            enterSafetyStop("path speed limit failed: "+exception.getClass().getSimpleName());
            return false;
        }
    }

    private boolean commandServo(double position,LastServoCommand command) {
        if (safetyLocked||!TEST_STAGE.servoAllowed||servo==null||!inClosedUnitRange(position)) {
            enterSafetyStop("servo command rejected");
            return false;
        }
        servo.setPosition(position);
        lastServoCommand=command;
        return true;
    }

    private void updateFollowerIfAllowed() {
        if (safetyLocked||!TEST_STAGE.driveAllowed||follower==null) return;
        follower.update();
    }

    private void transitionTo(AutoState next) {
        if (autoState==next) return;
        autoState=next;
        stateTimer.reset();
    }

    private void enterSafetyStop(String reason) {
        runtimeFailure=reason==null?"unknown runtime failure":reason;
        safetyLocked=true;
        transitionTo(AutoState.SAFETY_STOP);
        stopFollowingBestEffort();
    }

    private boolean finishPathOutput(String phase) {
        if (!stopFollowingBestEffort()) {
            enterSafetyStop(phase+" follower stop failed");
            return false;
        }
        return true;
    }

    private boolean stopFollowingBestEffort() {
        if (follower==null) return false;
        try {
            follower.stop();
            // Pedro 3 stop() switches the follower to IDLE; update() flushes the
            // drivetrain stop so no earlier motion command keeps running.
            follower.update();
            return true;
        } catch (RuntimeException ignored) {
            // Follower.update() refreshes localization before it reaches IDLE's
            // drivetrain stop. If localization fails, stop the drivetrain directly.
            try {
                follower.drivetrain.stop();
            } catch (RuntimeException stopFailure) {
                // Software cannot prove that hardware power was removed.
            }
            return false;
        }
    }

    private void emitTelemetrySafely() {
        try {
            emitTelemetry();
        } catch (RuntimeException exception) {
            enterSafetyStop("telemetry "+exception.getClass().getSimpleName()+": "+exception.getMessage());
        }
    }

    private void emitTelemetry() {
        telemetry.addData("configuration complete",CONFIGURATION_COMPLETE);
        telemetry.addData("test stage",TEST_STAGE);
        telemetry.addData("auto state",autoState);
        telemetry.addData("safety locked",safetyLocked);
        telemetry.addData("runtime failure",runtimeFailure);
        for (ValidationIssue issue: validationIssues) telemetry.addLine("CONFIG: "+issue);
        if (follower!=null) {
            telemetry.addData("x (in)",follower.pose().x());
            telemetry.addData("y (in)",follower.pose().y());
            telemetry.addData("heading (rad)",follower.pose().heading());
            telemetry.addData("follower busy",follower.isBusy());
        }
        telemetry.addData("last servo command",lastServoCommand);
        telemetry.addData("state elapsed (s)",stateTimer.seconds());
        telemetry.update();
    }

    private static boolean inClosedUnitRange(double value) {
        return Double.isFinite(value)&&value>=0&&value<=1;
    }

    private static boolean inSpeedFractionRange(double value) {
        return Double.isFinite(value)&&value>0&&value<=1;
    }

    private static boolean samePosition(Pose first,Pose second) {
        if (first==null||second==null) return false;
        return Math.hypot(first.x()-second.x(),first.y()-second.y())<1e-6;
    }
}
