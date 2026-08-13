# Pedro Pathing, goBILDA, and Limelight Guides Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add twelve evidence-backed candidate rules and Chinese newcomer guides for Pedro Pathing, selected goBILDA motor/servo SKUs, and Limelight 3A.

**Architecture:** Each topic has one schema v2 candidate-rule file and one Chinese guide. Rules remain narrow and enforceable; guides carry concepts, parameter dictionaries, setup, Java examples, validation, and troubleshooting. Existing guide acceptance tests expand to cover all six guides and nineteen new candidates.

**Tech Stack:** YAML schema v2, Markdown, FTC Java examples, Kotlin/JUnit acceptance tests, Gradle Wrapper.

## Global Constraints

- Execute Evidence Schema v2 and FTC Setup Guides plans first.
- All twelve rules are shared candidates without approval.
- Use only first-party Pedro Pathing, goBILDA, Limelight, and linked official sample/Javadoc sources.
- Record `accessedAt: 2026-08-13`; do not call it a version.
- If a page has no visible version, write “页面未标明版本” and scope by access date.
- Never generalize a goBILDA SKU's numeric values to a product family.
- Distinguish manufacturer specifications, library defaults/examples, measured values, and tuned values.
- Never publish copied PID, offsets, camera pose, servo endpoints, or robot coordinates as universal defaults.
- State coordinate origin, axes, angle units, and positive rotation before pose examples.
- Limelight control/localization code must check validity and an explicit freshness policy.
- Java examples must compile against the documented API or be replaced with a first-party current example.
- No candidate may appear as active.
- Use Chinese prose with bilingual term definitions on first use.

## File Structure

- `knowledge/shared/tools/pedro-pathing.yaml`: three pathing rules.
- `knowledge/shared/tools/gobilda-motors-servos.yaml`: four exact-SKU rules.
- `knowledge/shared/tools/limelight.yaml`: five camera/data rules.
- `knowledge/guides/tools/pedro-pathing.md`: configuration/tuning/coordinate tutorial.
- `knowledge/guides/tools/gobilda-motors-servos.md`: specification-to-code tutorial.
- `knowledge/guides/tools/limelight-3a.md`: setup/pipeline/result/localization tutorial.
- `apps/knowledge-cli/src/test/kotlin/org/ftckb/cli/KnowledgeGuideAcceptanceTest.kt`: extend rule-guide mappings.

---

### Task 1: Add twelve tool candidate rules

**Files:**
- Create: `knowledge/shared/tools/pedro-pathing.yaml`
- Create: `knowledge/shared/tools/gobilda-motors-servos.yaml`
- Create: `knowledge/shared/tools/limelight.yaml`
- Modify: `apps/knowledge-cli/src/test/kotlin/org/ftckb/cli/KnowledgeGuideAcceptanceTest.kt`

**Interfaces:** Twelve new IDs; repository total becomes 23 rules, only one active.

- [ ] **Step 1: Extend the failing acceptance map**

Add these entries to the existing `expected` map:

```kotlin
"guides/tools/pedro-pathing.md" to setOf(
    "shared.pedro-tune-current-robot",
    "shared.pedro-localization-before-follower",
    "shared.pedro-explicit-coordinate-conversion"
),
"guides/tools/gobilda-motors-servos.md" to setOf(
    "shared.gobilda-identify-exact-sku",
    "shared.gobilda-use-output-shaft-encoder-resolution",
    "shared.gobilda-separate-stall-and-operating-values",
    "shared.gobilda-servo-mode-and-pwm-range"
),
"guides/tools/limelight-3a.md" to setOf(
    "shared.limelight-check-result-validity",
    "shared.limelight-enforce-freshness-policy",
    "shared.limelight-synchronize-pipeline-dependent-reads",
    "shared.limelight-configure-camera-pose",
    "shared.limelight-back-up-before-os-update"
)
```

Run the focused test and expect missing guide/ID failure.

- [ ] **Step 2: Create schema v2 rules from this exact matrix**

Every row becomes one YAML rule with `status: candidate`, `authority: shared`, empty teams/seasons, and the given fields:

| id | topic | title | instruction | rationale |
|---|---|---|---|---|
| shared.pedro-tune-current-robot | pedro-robot-specific-tuning | Tune values on the current robot | Treat follower, drivetrain, and localizer values as measurements or tuning outputs for the current robot; do not copy another robot's values as validated configuration. | Pedro constants include robot-specific mass, drivetrain, localizer, velocity, controller, and path-completion values. |
| shared.pedro-localization-before-follower | pedro-localization-readiness | Validate localization before path following | Configure and validate the selected localizer before relying on follower tuning or autonomous path accuracy. | Follower control depends on accurate robot pose feedback. |
| shared.pedro-explicit-coordinate-conversion | pedro-coordinate-conversion | Convert coordinate systems explicitly | State source and destination coordinate systems and convert explicitly when exchanging poses between Pedro Pathing and FTC-standard or vision coordinates. | Pedro documents a coordinate convention that differs from the FTC SDK standard. |
| shared.gobilda-identify-exact-sku | gobilda-specification-identity | Identify the exact goBILDA SKU | Identify the exact goBILDA product and SKU before applying gear ratio, speed, torque, current, encoder, voltage, PWM, or travel specifications. | Products in the same family can have different electrical and mechanical values. |
| shared.gobilda-use-output-shaft-encoder-resolution | gobilda-encoder-conversion | Convert from output-shaft encoder resolution | Use the documented output-shaft encoder resolution for the exact motor SKU and include every external transmission ratio when converting ticks to mechanism motion. | Motor encoder counts describe the motor output shaft, not an arbitrary downstream mechanism. |
| shared.gobilda-separate-stall-and-operating-values | gobilda-load-specification | Separate stall and operating values | Label no-load and stall specifications distinctly and do not present stall values as continuous operating ratings. | The manufacturer publishes these as different operating conditions. |
| shared.gobilda-servo-mode-and-pwm-range | gobilda-servo-configuration | Record servo mode and PWM range | Record exact servo SKU, mode, documented PWM range, voltage, direction, and travel before defining software endpoints or interpreting commands. | A dual-mode servo interprets PWM as position in one mode and speed in continuous mode. |
| shared.limelight-check-result-validity | limelight-result-validity | Check Limelight result validity | Check that the latest Limelight result exists and is valid before using target or pose fields. | The FTC API can return no usable target result. |
| shared.limelight-enforce-freshness-policy | limelight-result-freshness | Enforce a Limelight freshness policy | Define and enforce a task-appropriate maximum result age before using Limelight data in closed-loop control or localization. | A valid result can still be too old for the current control decision. |
| shared.limelight-synchronize-pipeline-dependent-reads | limelight-pipeline-synchronization | Synchronize pipeline-dependent reads | Treat pipeline switching as asynchronous and verify the reported pipeline before consuming data that depends on the new pipeline. | The pipeline switch call is fire-and-forget. |
| shared.limelight-configure-camera-pose | limelight-field-localization-setup | Configure camera pose for localization | Configure camera pose relative to the robot before field localization and provide current robot orientation before consuming MegaTag 2 results. | Field pose depends on camera extrinsics and MegaTag 2 fuses external orientation. |
| shared.limelight-back-up-before-os-update | limelight-update-backup | Back up before updating LimelightOS | Back up Limelight pipelines and scripts before flashing or upgrading LimelightOS. | The official quick start warns that an OS flash erases them. |

Use these evidence mappings, duplicating the full web object per rule rather than relying on YAML anchors:

- Pedro constants: `https://pedropathing.com/docs/pathing/constants`, title `Constants`, publisher `Pedro Pathing`, section `The Constants file`.
- Pedro localization: `https://pedropathing.com/docs/pathing/tuning/localization`, title `Localization`, publisher `Pedro Pathing`, section `Localization Test`.
- Pedro coordinates: `https://pedropathing.com/docs/pathing/reference/coordinates`, title `Coordinates`, publisher `Pedro Pathing`, section `Coordinates`.
- goBILDA motor: exact 5203 19.2:1 product URL, title copied from the page, publisher `goBILDA`, section `Specs`, product `5203 Series Yellow Jacket Planetary Gear Motor`, SKU `5203-2402-0019`.
- goBILDA servo: exact 2000 Series 5-turn dual-mode torque servo URL, page title, publisher `goBILDA`, section `Specs`, product `2000 Series 5-Turn Dual Mode Servo (25-2, Torque)`, SKU `2000-0025-0502`.
- Limelight programming: `https://docs.limelightvision.io/docs/docs-limelight/apis/ftc-programming`, title `FTC Java & Blockly Programming Guide`, publisher `Limelight Vision`; sections respectively `Getting and Using Results`, `Is The Data Fresh?`, `Pipeline Management`, and `Where's My Robot? (MegaTag 2)`; product `Limelight 3A`.
- Limelight update: `https://docs.limelightvision.io/docs/docs-limelight/getting-started/limelight-3a`, title `Limelight 3A Quick-Start`, publisher `Limelight Vision`, section `Updating LimelightOS`, product `Limelight 3A`.

The goBILDA identity rule cites both motor and servo pages. Encoder and load rules cite the motor page. Servo configuration cites the servo page.

- [ ] **Step 3: Add temporary guides with title and related IDs, then validate**

Create the three guide paths with only a title and related-rule list so the acceptance test can load them. These are not committed until Tasks 2-4 replace them.

```bash
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew :apps:knowledge-cli:test --tests 'org.ftckb.cli.KnowledgeGuideAcceptanceTest'
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew :apps:knowledge-cli:run --args="validate knowledge" --quiet
```

Expected: acceptance test passes and validation prints `validation=ok rules=23`.

---

### Task 2: Write Pedro Pathing guide

**Files:**
- Replace: `knowledge/guides/tools/pedro-pathing.md`

**Interfaces:** Cites all three Pedro IDs and direct Pedro sources.

- [ ] **Step 1: Re-verify official pages and record version scope**

Use Introduction, Installation, Constants, Setup, Localization, Tuning, Coordinates, Path Builder, Detecting Path Completion, Example Auto, and Troubleshooting pages. At the top write:

```markdown
> 核验日期：2026-08-13。Pedro Pathing 网页未显示统一的软件版本号；依赖版本必须以队伍工程中实际固定的版本为准。
```

Do not mix the old `FConstants/LConstants` API with the current `FollowerBuilder` API in one runnable example.

- [ ] **Step 2: Write concepts and parameter dictionary**

Include exactly these categories:

| Category | Examples | Classification |
|---|---|---|
| Follower constants | mass, braking or PIDF parameters, centripetal scaling | measured/tuned, robot-specific |
| Drivetrain constants | motor names/directions, max power, x/y velocity | configured/measured |
| Localizer constants | hardware name, pod offsets, encoder directions | measured/configured |
| Path constraints | completion t-value, velocity, pose/heading tolerance, timeout | policy/tuned |

State `mass` in kilograms and `maxPower` in [0,1] because official Setup documents those. For every other numeric field, copy its unit/meaning from the current official page; if no unit is stated, write “官方此页未明确单位” instead of inferring.

- [ ] **Step 3: Write ordered setup and minimum current-API Java example**

Order: dependency/version → motor names/directions → robot mass → localizer configuration → localization test → forward/lateral velocity tuners → heading → choose predictive braking OR PIDF route → path constraints → slow physical test.

Use a minimal autonomous structure based on current official examples:

```java
private Follower follower;
private PathChain path;

@Override
public void init() {
    follower=Constants.createFollower(hardwareMap);
    path=follower.pathBuilder()
        .addPath(new BezierLine(
            new Pose(START_X,START_Y,START_HEADING_RADIANS),
            new Pose(END_X,END_Y,END_HEADING_RADIANS)
        ))
        .setLinearHeadingInterpolation(
            START_HEADING_RADIANS,
            END_HEADING_RADIANS
        )
        .build();
}

@Override
public void start() {
    follower.setStartingPose(
        new Pose(START_X,START_Y,START_HEADING_RADIANS)
    );
    follower.followPath(path);
}

@Override
public void loop() {
    follower.update();
    telemetry.addData("x",follower.getPose().getX());
    telemetry.addData("y",follower.getPose().getY());
    telemetry.update();
}
```

Before publishing, compile names/signatures against the pinned current Pedro version. Label every uppercase value as a placeholder. Explain Pedro axes: +x and +y as documented, radians, counterclockwise positive. Show official `PoseConverter` for FTC/vision pose conversion rather than manual sign swaps.

- [ ] **Step 4: Add observable validation and troubleshooting**

Validation must confirm: forward increases expected x, left strafe increases expected y, heading sign is correct, static pose drift is bounded by team acceptance criteria, path begins at actual starting pose, and completion/timeout is visible in telemetry. Troubleshoot wrong axis/sign, mirrored path, oscillation, overshoot, early completion, never completing, motor fighting, and copied constants.

- [ ] **Step 5: Run linkage test**

Expected: all Pedro IDs exist in the finished guide.

---

### Task 3: Write goBILDA motor and servo guide

**Files:**
- Replace: `knowledge/guides/tools/gobilda-motors-servos.md`

**Interfaces:** Exact worked examples for motor SKU 5203-2402-0019 and servo SKU 2000-0025-0502 only.

- [ ] **Step 1: Re-verify both product pages and build exact spec tables**

Motor table must include manufacturer values:

```markdown
| 项目 | 规格 |
|---|---:|
| 额定电压 | 12 VDC |
| 减速比 | 19.2:1 |
| 12 V 空载转速 | 312 RPM |
| 12 V 空载电流 | 0.25 A |
| 12 V 堵转电流 | 9.2 A |
| 12 V 堵转转矩 | 24.3 kg·cm |
| 编码器类型 | relative quadrature, magnetic Hall effect |
| 编码器电压 | 3.3–5 VDC |
| 输出轴分辨率 | 537.7 PPR |
```

Servo table must include 4.8–7.4 V, position/default versus continuous mode, 500–2500 µs maximum position range, 900–2100 µs continuous range, 50 Hz, clockwise with increasing PWM, and 1800° maximum position-mode rotation. Verify every value from the page immediately before writing.

- [ ] **Step 2: Explain conversion without universal mechanism assumptions**

Use:

```text
motor_output_revolutions=encoder_ticks/537.7
mechanism_revolutions=motor_output_revolutions*(driver_teeth/driven_teeth)
mechanism_degrees=mechanism_revolutions*360
```

State that 537.7 PPR applies only to SKU 5203-2402-0019's output shaft. Add one labeled example with 1075.4 ticks = 2 motor-output revolutions, then use symbolic sprocket tooth counts rather than inventing a robot transmission.

- [ ] **Step 3: Add safe minimum Java examples**

Motor:

```java
DcMotorEx motor=hardwareMap.get(DcMotorEx.class,"YOUR_MOTOR_NAME");
motor.setMode(DcMotor.RunMode.STOP_AND_RESET_ENCODER);
motor.setMode(DcMotor.RunMode.RUN_USING_ENCODER);
double outputRevolutions=motor.getCurrentPosition()/537.7;
telemetry.addData("Output revolutions",outputRevolutions);
```

Servo midpoint test:

```java
Servo servo=hardwareMap.get(Servo.class,"YOUR_SERVO_NAME");
servo.setPosition(0.5);
```

Warn to disconnect linkages or establish a mechanically safe range before endpoint tests. Explain that FTC `Servo.setPosition(0..1)` is normalized and does not by itself prove which physical PWM endpoints the controller/servo combination uses. Continuous mode changes the command meaning from position to speed/direction.

- [ ] **Step 4: Add validation and troubleshooting**

Motor checks: exact label/SKU, wiring/polarity, encoder direction, counts for one marked output-shaft revolution, external ratio, no-load versus loaded behavior. Servo checks: exact mode set by programmer, voltage source, midpoint, small incremental moves, buzzing/mechanical limit, direction, and loss of holding position in continuous mode. Never recommend using stall current/torque continuously.

- [ ] **Step 5: Run linkage test**

Expected: four goBILDA IDs appear, all numeric specifications have units and direct product links.

---

### Task 4: Write Limelight 3A guide and close content acceptance

**Files:**
- Replace: `knowledge/guides/tools/limelight-3a.md`
- Modify: `README.md`
- Modify: `apps/knowledge-cli/src/test/kotlin/org/ftckb/cli/KnowledgeGuideAcceptanceTest.kt` if paths/IDs need final normalization only.

**Interfaces:** Covers Limelight 3A on Control Hub USB 3.0 and cites five rules.

- [ ] **Step 1: Write hardware and web setup**

Include official values: USB power 4.1–5.75 V, maximum 4 W, 640×480 at 90 FPS sensor capability, no built-in illumination, USB-C only. Setup: mount with at least two appropriate screws, connect computer, wait for boot, open Hardware Manager or `http://limelight.local:5801`, set team number, configure pipeline, connect to Control Hub blue USB 3.0 port, scan Robot Configuration, name device `limelight`.

State that flashing LimelightOS erases pipelines/scripts and require export/backup first.

- [ ] **Step 2: Write initialization, async pipeline, validity, and freshness example**

```java
private Limelight3A limelight;

@Override
public void init() {
    limelight=hardwareMap.get(Limelight3A.class,"limelight");
    limelight.setPollRateHz(100);
    limelight.pipelineSwitch(0);
    limelight.start();
}

@Override
public void loop() {
    LLResult result=limelight.getLatestResult();
    if (result==null || !result.isValid()) {
        telemetry.addLine("No valid Limelight target");
        telemetry.update();
        return;
    }

    long ageMs=result.getStaleness();
    long maximumAgeMs=YOUR_TASK_MAXIMUM_AGE_MS;
    if (ageMs>maximumAgeMs || result.getPipelineIndex()!=0) {
        telemetry.addData("Rejected result age (ms)",ageMs);
        telemetry.addData("Pipeline",result.getPipelineIndex());
        telemetry.update();
        return;
    }

    telemetry.addData("tx (deg)",result.getTx());
    telemetry.addData("ty (deg)",result.getTy());
    telemetry.addData("ta (%)",result.getTa());
    telemetry.update();
}
```

Label 100 Hz as the official example poll rate, not a guarantee of fresh 100 Hz frames. Label the age limit as task-specific; mention the official page's 100 ms freshness example only as an example.

- [ ] **Step 3: Add localization branch**

Before MegaTag 1/2: enable Full 3D, configure camera position relative to robot center, verify the FTC field coordinate system, and confirm field map/tag family. For MegaTag 2, update robot orientation before reading `getBotpose_MT2()`:

```java
limelight.updateRobotOrientation(robotYawDegrees);
LLResult result=limelight.getLatestResult();
if (result!=null && result.isValid() &&
    result.getStaleness()<=YOUR_LOCALIZATION_MAXIMUM_AGE_MS) {
    Pose3D pose=result.getBotpose_MT2();
    if (pose!=null) {
        telemetry.addData("x",pose.getPosition().x);
        telemetry.addData("y",pose.getPosition().y);
    }
}
```

Verify the current SDK signature and yaw unit from official Javadoc/sample before publication. Do not manually feed Limelight FTC poses into Pedro without the documented coordinate conversion.

- [ ] **Step 4: Add validation/troubleshooting and README links**

Validate device discovery, reported pipeline, validity, staleness, target angles, snapshots, camera pose, stationary field pose, and motion latency before closed-loop use. Troubleshoot no Ethernet Device, web UI unavailable, invalid result, wrong pipeline after switch, old results, mirrored pose, jumping field pose, missing tags, CPU neural performance, and erased pipelines after update.

Add README links to all three tool guides.

- [ ] **Step 5: Run final repository acceptance**

```bash
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew clean test --quiet
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew :apps:knowledge-cli:run --args="validate knowledge" --quiet
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew :apps:knowledge-cli:run --args="resolve knowledge --team 20827 --season 2025-2026" --quiet
git diff --check
```

Expected:

```text
validation=ok rules=23
active official.keep-customizations-in-teamcode
```

Manually verify all six guides use direct first-party links, every number has a unit, and no robot-specific calibration appears as a default.

- [ ] **Step 6: Commit tool knowledge**

```bash
git add knowledge/shared/tools knowledge/guides/tools apps/knowledge-cli/src/test/kotlin/org/ftckb/cli/KnowledgeGuideAcceptanceTest.kt README.md
git commit -m "docs: add FTC tool knowledge guides"
```
