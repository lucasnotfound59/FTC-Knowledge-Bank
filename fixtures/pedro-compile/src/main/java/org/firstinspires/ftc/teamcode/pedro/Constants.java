package org.firstinspires.ftc.teamcode.pedro;

import com.pedropathing.algorithm.Foresight;
import com.pedropathing.algorithm.ForesightConfig;
import com.pedropathing.controllers.Controller;
import com.pedropathing.follower.Follower;
import com.pedropathing.math.Matrix;
import com.pedropathing.math.Vector2D;
import com.pedropathing.revhub.drivetrains.Mecanum;
import com.pedropathing.revhub.drivetrains.MecanumConfig;
import com.pedropathing.revhub.localizers.PinpointConfig;
import com.pedropathing.revhub.localizers.PinpointLocalizer;
import com.qualcomm.hardware.gobilda.GoBildaPinpointDriver;
import com.qualcomm.robotcore.hardware.DcMotorSimple;
import com.qualcomm.robotcore.hardware.HardwareMap;

/**
 * Compile adapter for the canonical Pedro 3 tutorial example.
 *
 * <p>This file is <strong>not a robot configuration and must not be deployed</strong>. It exists
 * only so that {@code knowledge/examples/pedro/SafePedroAuto.java} is compiled against a real
 * Pedro 3 localizer + drivetrain + Foresight follower contract in an isolated fixture.
 *
 * <p>The shape follows the official Pedro 3 Constants page
 * (<a href="https://pedropathing.com/docs/pathing/tuning/constants">pedropathing.com/docs/pathing/tuning/constants</a>),
 * including its complete example {@code create(HardwareMap)}. Every hardware name, offset,
 * direction, pod selection and controller gain below is an <strong>unvalidated sample value
 * copied from that official page</strong>; none of them is measured or tuned for any robot.
 * A real project must re-measure drivetrain, localizer and Foresight values on the current
 * robot (see the tutorial's parameter dictionary) before it configures or drives anything.
 */
public final class Constants {
    private Constants() {}

    public static MecanumConfig drivetrainConfig = new MecanumConfig(
        c -> {
            c.frontLeftName.set("lf");
            c.backLeftName.set("lr");
            c.frontRightName.set("rf");
            c.backRightName.set("rr");
            c.frontLeftDirection.set(DcMotorSimple.Direction.REVERSE);
            c.backLeftDirection.set(DcMotorSimple.Direction.REVERSE);
            c.frontRightDirection.set(DcMotorSimple.Direction.FORWARD);
            c.backRightDirection.set(DcMotorSimple.Direction.FORWARD);
            c.manualBrakeMode.set(true);
        }
    );

    public static PinpointConfig localizerConfig = new PinpointConfig(
        c -> {
            c.name.set("pinpoint");
            c.xPodOffset.set(2.187);
            c.yPodOffset.set(-4.572);
            c.xPodDirection.set(GoBildaPinpointDriver.EncoderDirection.FORWARD);
            c.yPodDirection.set(GoBildaPinpointDriver.EncoderDirection.FORWARD);
        }
    );

    public static ForesightConfig foresightConfig = new ForesightConfig(
        c -> {
            Controller primaryTranslationalForward = Controller.proportional(0.3);
            Controller secondaryTranslationalForward = Controller.proportional(0.1);
            Controller primaryTranslationalLateral = Controller.proportional(0.3);
            Controller secondaryTranslationalLateral = Controller.proportional(0.1);
            c.forwardTranslational.set(Controller.piecewise(secondaryTranslationalForward).put(2.5,primaryTranslationalForward));
            c.strafeTranslational.set(Controller.piecewise(secondaryTranslationalLateral).put(2.5,primaryTranslationalLateral));
            c.coast.set(Controller.proportionalFeedforward(0.010978350889324107));
            c.brake.set(Controller.proportionalFeedforward(0.008731598255925491));
            c.headingFeedback.set(Controller.proportional(5.258721785960744));
            c.headingBrakeCoefficients.set(Vector2D.cartesian(0.05642143125655298,0.0063829525363003695));
            c.linearBrakeCoefficients.set(Matrix.diag(0.10605894992901523,0.08719146175596092));
            c.quadraticBrakeCoefficients.set(Matrix.diag(0.0014663966976606565,0.0013837064502458813));
            c.maxAchievableForwardVelocity.set(72.72923108818539);
            c.maxAchievableStrafeVelocity.set(52.34323936525474);
            c.naturalForwardDeceleration.set(85.01144677379789);
            c.naturalStrafeDeceleration.set(104.49787535782846);
        }
    );

    /** Official v3 shape: wire an actual localizer, drivetrain and follower algorithm. */
    public static Follower create(HardwareMap hardwareMap) {
        return new Follower(
            new PinpointLocalizer(hardwareMap,localizerConfig),
            new Mecanum(hardwareMap,drivetrainConfig),
            new Foresight(foresightConfig)
        );
    }
}
