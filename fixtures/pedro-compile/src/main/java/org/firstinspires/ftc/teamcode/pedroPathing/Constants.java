package org.firstinspires.ftc.teamcode.pedroPathing;

import com.pedropathing.follower.Follower;
import com.pedropathing.follower.FollowerConstants;
import com.pedropathing.ftc.FollowerBuilder;
import com.qualcomm.robotcore.hardware.HardwareMap;

/**
 * Non-runnable compile adapter for the public Constants.createFollower contract.
 * This is not a set of robot constants and must not be deployed as one.
 */
public final class Constants {
    private Constants() {}

    public static Follower createFollower(HardwareMap hardwareMap) {
        return new FollowerBuilder(new FollowerConstants(),hardwareMap).build();
    }
}
