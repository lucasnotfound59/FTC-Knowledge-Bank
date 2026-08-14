package example;

import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode;
import com.qualcomm.robotcore.eventloop.opmode.TeleOp;
import com.qualcomm.robotcore.hardware.DcMotor;

@TeleOp(name="Sample TeleOp")
public final class SampleTeleOp extends LinearOpMode {
    private DcMotor motor;

    @Override
    public void runOpMode() {
        waitForStart();
        motor.setPower(1.0);
    }
}
