# Pedro Pathing 新生 Auto 教程

> 核验日期：2026-08-14。本教程的目标不是让机器人第一次上电就跑完整 Auto，而是让新队员知道每一个值填什么、从哪里测、单位是什么，以及看到什么才算通过。

## 适用范围与证据标签

本文只讲 FTC SDK、Pedro Pathing 2.1.2 和本仓库唯一的安全示例 [完整的 `SafePedroAuto.java`](../../examples/pedro/SafePedroAuto.java)。先完成 [FTC SDK 配置](../setup/android-studio-ftc-sdk.md)；Dashboard 是可选观察工具，可参考 [FTC Dashboard 教程](../setup/ftc-dashboard.md)。

读到一个结论或数值时，先判断它属于哪一类：

| 标签 | 含义 | 可以直接照抄吗 |
|---|---|---|
| `Pedro requirement` | Pedro 官方文档或 2.1.2 API 的要求 | 只能照做流程；仍需按当前硬件填值 |
| `beginner safety convention` | 本知识库为了让新人分阶段验证而加的安全约定 | 应保留；它不是 Pedro API 的强制写法 |
| `20827-inspired pattern` | 从 20827 的 Auto 结构中抽象出的高级组织思路 | 只学结构，不复制机器人参数 |
| `robot-specific value` | 硬件名、方向、位置、pose、质量、offset、速度或控制参数 | 不可以；必须在当前机器人上测量和评审 |

本文当前结果标签是：`内容已验证`、`编译已验证`、`硬件阶段未验证`。`编译已验证` 只说明 Java/API 能编译，不说明接线、方向、坐标、机构安全或实车路径正确。只有保存四阶段实车记录后，才可以写 `硬件四阶段已验证：<robot/reviewer/date>`。

## 版本矩阵与两条安装路线

| Item | Pin | Meaning |
|---|---|---|
| FIRST FTC SDK | v11.2 / 11.2.0 / `4ed7c4666aec265a6fd9e674ca40462e9dfe4bf8` | Current FIRST release used by this project's core-example compile fixture |
| Pedro library | v2.1.2 / `96df977d30329eef57c226cf1e6854026f4dfe4f` | Stable Pedro API used by the example |
| Pedro Quickstart snapshot | `d3aea9ca3c5b4c09eded8580229b86996480ee89` | Pedro 2.1.2 upstream example/tuner snapshot; still based on FTC 11.1.0 |

FTC 11.2 + Pedro 2.1.2 是**本项目编译验证**的组合，不是上游 Pedro 的兼容保证。fixture 使用 FIRST v11.2 默认的 compile SDK 30，只覆盖本仓库核心示例；compile verification is not hardware verification，也不覆盖 Panels、复制来的 tuner 或所有 Quickstart OpMode。

### Route A — official Quickstart snapshot

适合第一次学习 Pedro、希望 tuner 与 `Constants.java` 来自同一份上游快照的队员。

1. clone `https://github.com/Pedro-Pathing/Quickstart.git`，checkout `d3aea9ca3c5b4c09eded8580229b86996480ee89`。这是一套 coherent upstream baseline，但底层仍是 FTC 11.1.0。
2. 不要先替换它的版本。确认根目录 `build.dependencies.gradle` 的 `repositories {}` / `dependencies {}`、`TeamCode/build.gradle` 的 module dependencies，以及 `TeamCode/src/main/java/org/firstinspires/ftc/teamcode/pedroPathing/` 都属于这个 commit。
3. **构建快照**：未修改 snapshot 只做 Gradle Sync 和 build。最小观察是 Sync 无 dependency 错误、项目 build 成功；此时不要 deploy 或运行任何机器人 OpMode，因为上游 hardware names 与 localizer 配置还不是你的机器人。
4. **配置硬件**：按本教程的 Constants/Pinpoint 流程和 Pedro 官方 localizer 文档，配置当前机器人的 hardware names、localizer、offsets 和 directions；所有数值保留测量记录。
5. **评审**：让另一位队员逐项核对接线、硬件名、方向、offset、分辨率和测试边界，记录 reviewer approval。没有批准就停在这里。
6. **部署与最小运行**：获得批准后才 deploy，然后只运行 `Localization Test`，不运行路径。最小观察：Driver Station 能列出该 OpMode；Panels 或 FTC Dashboard 能显示 pose；手推向前 x 增加、向左 y 增加。否则停在定位排错，不进入 Auto。

### Route B — current FIRST v11.2 team project

适合已经在 FIRST v11.2 工程开发、只想引入 Pedro 2.1.2 的队伍。

1. 在工程根目录 `build.dependencies.gradle` 的 `repositories {}` 中加入 Pedro 官方安装页给出的 repository：

   ```groovy
   maven { url = "https://mymaven.bylazar.com/releases" }
   ```

   再在同一根目录 `build.dependencies.gradle` 的 `dependencies {}` 中加入核心 Pedro 依赖：

   ```groovy
   implementation 'com.pedropathing:ftc:2.1.2'
   ```

   只有复制并使用 Panels/tuners 时，才在这个 `dependencies {}` 中另外加入官方安装页当前列出的两项：

   ```groovy
   implementation 'com.pedropathing:telemetry:1.0.0'
   implementation 'com.bylazar:fullpanels:1.0.12'
   ```

   telemetry、FullPanels、复制来的 tuners are outside the core fixture scope；不要把本仓库核心示例的编译结果当成它们已验证。
2. 从上面的 Quickstart snapshot 复制 `TeamCode/src/main/java/org/firstinspires/ftc/teamcode/pedroPathing/Constants.java` 和所需 `Tuning.java`，保留来源 commit；放入当前工程同一路径后再按本机硬件修改。
3. 官方 manual route 还要求在 Android Studio Project Structure 中把 `FtcRobotController` 与 `TeamCode` 设为 compile SDK 34；对应 Gradle 配置在根目录 `build.common.gradle` 的 `android { compileSdkVersion ... }`。这与 FIRST v11.2 默认 compile SDK 30 不同，必须在独立分支 Sync、build 并完整验证，不能从本仓库 compile SDK 30 fixture 推断它已通过。
4. Gradle Sync，然后运行整个工程 build；不要只编译一个 Java 文件。
5. 连接 Control Hub 并部署。先确认 Driver Station 能列出 `Tuning` 与 `Safe Pedro Auto`，再执行 Localization Test。
6. 最小观察：无缺类/缺资源错误；pose 有更新；向前 x 增加、向左 y 增加；STOP 后不再输出运动命令。复制的 tuners、Dashboard/Panels 和实车仍要分别验证。

两条路线不要交叉摘取中间文件：要么以同一 Quickstart snapshot 学习，要么在 v11.2 工程里明确记录每个复制文件、artifact 与工具链差异。

## 坐标系

`Pedro requirement`：Pedro 场地图使用右手坐标系。图向右是 `+x`，向上是 `+y`；向右 heading 为 `0 rad`，向上为 `π/2 rad`，向左为 `π rad`，逆时针旋转为正。官方 12 ft × 12 ft 场图常写成 x/y `[0,144]` in。

写任何 pose 前，先在队伍记录中画出原点、`+x`、`+y`、`0 rad` 朝向和联盟侧。位置用 inch，heading 用 rad；卷尺测位置，角尺/场地线确认朝向，代码中用 `Math.toRadians(degrees)` 转换角度。把机器人放到声明的起点，telemetry 应显示同一 x/y/heading；手推向右、向上和逆时针转动时三个量应分别按约定增加。

外部视觉结果不得靠猜测交换 x/y。先声明来源坐标系，再转换：

```java
Pose ftcStandard=PoseConverter.pose2DToPose(ftcPose2d,InvertedFTCCoordinates.INSTANCE);
Pose pedroPose=ftcStandard.getAsCoordinateSystem(PedroCoordinates.INSTANCE);
```

`InvertedFTCCoordinates` 是特定来源/赛季的选择，不是永久默认。每次换视觉系统或赛季都重新确认来源坐标、单位、轴和旋转正方向。这是 `shared.pedro-explicit-coordinate-conversion` 的要求。

## Constants 的四类参数

Pedro 2.1.2 的 `Constants.java` 负责构造 `Follower`。四类值必须分开记录：

| 类别 | 填什么 | 如何获得 | 单位或范围 | 如何验证 |
|---|---|---|---|---|
| Follower constants | 完整比赛状态质量、自动 tuner/PIDF/向心参数 | 称重并按官方 tuner 顺序获得 | 质量 kg；其余按对应 tuner | 重复相同测试，误差与响应满足队伍阈值 |
| Drivetrain constants | 电机 hardware name、方向、最大功率、x/y velocity | 对照 RC Configuration，架空单轮确认方向，再运行 velocity tuners | `maxPower` `[0,1]`；velocity in/s | 低功率前进/横移方向正确，velocity 可重复 |
| Localizer constants | localizer 类型、hardware name、offset、编码器方向/分辨率 | 实物接线、RC Configuration、卷尺、官方 localizer tuner | 见所选 localizer；Pinpoint 完整契约见下文 | 手推前进 x 增、左移 y 增、逆时针 heading 增 |
| Path constraints | t-value、velocity、平移/航向误差、timeout | 先完成定位和 follower tuning，再按任务可靠性逐项设定 | t-value `[0,1]`；velocity in/s；timeout ms；heading rad | 记录 `isBusy` 退出时由哪个 constraint 放行 |

表中官方 example 值只用来展示 API。所有 `robot-specific value` 都必须来自当前机器人，对应 `shared.pedro-tune-current-robot`；定位没有先通过，就不能评价 follower，对应 `shared.pedro-localization-before-follower`。

## Pedro 2.1.2 的 localizer 选择

`FollowerBuilder` 在 2.1.2 中提供以下全部选择；只选一种，不要同时叠加：

- drive encoders：`.driveEncoderLocalizer(DriveEncoderConstants)`，无独立里程计硬件时可用，但打滑会进入定位；
- OTOS：`.OTOSLocalizer(OTOSConstants)`，使用 SparkFun OTOS；
- Pinpoint：`.pinpointLocalizer(PinpointConstants)`，使用 goBILDA Pinpoint；
- three-wheel + IMU：`.threeWheelIMULocalizer(ThreeWheelIMUConstants)`；
- three-wheel：`.threeWheelLocalizer(ThreeWheelConstants)`；
- two-wheel：`.twoWheelLocalizer(TwoWheelConstants)`；
- custom `Localizer`：先实现 Pedro `Localizer`，再通过 `.setLocalizer(localizer)` 注入。

新人先根据机器人现有传感器选择，不要按“看起来更高级”选。20827 与 16093 都使用 Pinpoint，所以本教程只把 Pinpoint 展开为一条完整 beginner flow；这不表示 Pinpoint 是 Pedro 的唯一或通用最佳选择。

## Pinpoint 完整新生流程

### 机械、接线与配置

1. 两个 odometry pod 必须分别测量 forward 与 lateral；forward pod 接 Pinpoint x port，strafe pod 接 y port。
2. Pinpoint 有端口/贴纸的一面朝上；在 Control Hub 上不要接 I2C port 0，因为内置 IMU 使用该端口。
3. 在 RC Configuration 给它一个明确且唯一的 hardware name；记录 pod 型号。未确认型号时不要猜 encoder resolution。
4. 定义机器人旋转中心。用卷尺测 forward pod 相对旋转中心的 y offset，以及 strafe pod 的 x offset；不要复制 20827、16093 或官方例子数值。

### Pinpoint 参数契约

| 字段 | 填什么 | 如何获得 | 单位或范围 | 如何验证 |
|---|---|---|---|---|
| `forwardPodY` | forward pod 相对旋转中心的有符号 y 偏移 | 按官方 offset 图从旋转中心量到 pod 测量线，或运行 Offsets Tuner | 使用 `distanceUnit`；本流程选 inch，符号按官方图 | 原地旋转后 x/y 不应出现系统性弧线漂移 |
| `strafePodX` | strafe pod 相对旋转中心的有符号 x 偏移 | 同上，测旋转中心到 strafe pod 测量线，或运行 Offsets Tuner | 使用 `distanceUnit`；本流程选 inch，符号按官方图 | 原地旋转后 x/y 漂移满足队伍预先阈值 |
| `distanceUnit` | `DistanceUnit.INCH` | 与队伍场地图和实测记录统一选择 | 本流程固定 inch | 手推已知 24 in，telemetry 位移应接近 24 in |
| `hardwareMapName` | RC Configuration 中 Pinpoint 的精确名称 | 在 Driver Station/RC 配置逐字符核对 | 非空字符串，区分大小写 | INIT 不出现 localizer/follower 初始化错误，pose 会更新 |
| `encoderResolution` | 实际 goBILDA odometry pod 对应的枚举 | 看 pod 型号/订单记录并对照 SDK enum | `GoBildaOdometryPods` 中与实物相同的一项 | 手推已知距离，比例误差可重复且满足阈值 |
| `customEncoderResolution` | 只有自定义 pod 才填每个已选距离单位对应的 encoder ticks，替代上一项 | 查 manufacturer CPR/gearing，并测 measured/effective wheel diameter，按下方公式计算 | 正数；`distanceUnit=INCH` 时 ticks/inch，`distanceUnit=MM` 时 ticks/mm | 用多个已知距离比较 Localization Test measured-vs-reported distance，比例误差须可重复且满足阈值 |
| `forwardEncoderDirection` | 让向前推动时 x 增加的方向枚举 | 运行 Localization Test，手推向前；反号就切换方向 | `FORWARD` 或 `REVERSED`，以当前 SDK enum 为准 | 向前推动时 x 单调增加 |
| `strafeEncoderDirection` | 让向左推动时 y 增加的方向枚举 | 运行 Localization Test，手推向左；反号就切换方向 | `FORWARD` 或 `REVERSED`，以当前 SDK enum 为准 | 向左推动时 y 单调增加 |
| `yawScalar` | 通常不设置；只有有重复证据时才填校正比例 | 多圈已知角旋转与 Pinpoint heading 对比 | 无量纲；默认校准优先 | 顺/逆时针多个角度都改善；否则删除该覆盖 |

在 `TeamCode/src/main/java/org/firstinspires/ftc/teamcode/pedroPathing/Constants.java` 中创建一份 `PinpointConstants`。下面的 `MEASURED_*` 和枚举选择都是 sentinel：

```java
PinpointConstants localizerConstants=new PinpointConstants()
    .forwardPodY(MEASURED_FORWARD_POD_Y_IN)
    .strafePodX(MEASURED_STRAFE_POD_X_IN)
    .distanceUnit(DistanceUnit.INCH)
    .hardwareMapName("YOUR_PINPOINT_NAME")
    .encoderResolution(YOUR_ACTUAL_POD_ENUM)
    .forwardEncoderDirection(YOUR_MEASURED_FORWARD_DIRECTION)
    .strafeEncoderDirection(YOUR_MEASURED_STRAFE_DIRECTION);
```

自定义 pod 时用 `.customEncoderResolution(MEASURED_CUSTOM_RESOLUTION)` **替代** `.encoderResolution(...)`。Pedro 2.1.2 的 Pinpoint localizer 会把这个数值和所选 `distanceUnit` 一起传给 FTC 11.2 Pinpoint driver，因此语义是 ticks per selected `distanceUnit`，并不总是 ticks/mm：选择 inch 就填 ticks/inch，选择 mm 才填 ticks/mm。

计算模板只保留符号，不复制机器人值。`encoderCPR` 表示 encoder 每转 ticks；如果 encoder 与 pod wheel 间有传动，则显式乘 manufacturer gear ratio。分母是用 measured/effective wheel diameter 算出的 pod-wheel circumference；也就是 encoder CPR / pod-wheel circumference：

```java
customEncoderResolution=(encoderCPR*gearRatio)/(Math.PI*effectivePodDiameter);
```

公式中的 `effectivePodDiameter` 必须与 `distanceUnit` 相同。计算后仍要用多个已知直线距离做 Localization Test measured-vs-reported distance 对比；有效直径可依据重复误差校正，但必须保留原始测量与 reviewer。除非完成表中 yaw 证据，不添加 `.yawScalar(...)`。然后只在 `createFollower(HardwareMap hardwareMap)` 的 `new FollowerBuilder(...)` 链上加入：

```java
.pinpointLocalizer(localizerConstants)
```

运行 `Tuning` → `Localization Test`。先架起/手推，不命令底盘：前推 x 增，左移 y 增，逆时针 heading 增；再手推已知直线距离与已知角度，记录误差和静止漂移。任何一项失败都留在定位阶段，不开始 follower tuning 或 Auto。

## 官方调参顺序

保持官方次序，保存每个 tuner 的输入、环境、电池电压、输出和 reviewer：

1. **Setup**：完整比赛状态称质量；核对 drivetrain hardware names 与 directions；若是 swerve，先完成 swerve constants。
2. **Localization**：完成上面的 localizer 配置与 Localization Test，证明轴、符号、尺度、heading 和漂移可接受。
3. **Velocity Tuners**：分别运行 forward 与 lateral velocity tuner，得到当前机器人 x/y velocity（in/s）。
4. **Heading Tuner**：调 heading PIDF，确认 heading 使用 rad、逆时针为正。
5. **二选一，不混用中间结果**：
   - predictive braking 路线：运行 automatic Predictive Braking Tuner，再确定 P；
   - PIDF 路线：依次运行 Zero Power Acceleration Tuners、translational/drive PIDF Tuners、Centripetal Force Tuner。
6. **Tests 与 constraints**：从低功率短直线开始，记录末端 velocity、平移误差、heading 误差、`isBusy` 与 timeout，再设置 path constraints。

如果 Localization Test 没通过，调 PIDF 只是让错误变得更危险。换轮胎、质量、传动、pod 安装或机器人后，相关 `robot-specific value` 必须重测。

## SafePedroAuto 参数字典

只编辑 [完整的 `SafePedroAuto.java`](../../examples/pedro/SafePedroAuto.java) 中两个 marker 之间的块：

```java
// CONFIGURE HERE START
// 按下表填写十二项；先保持安全锁，再分阶段重编译。
// CONFIGURE HERE END
```

| 参数 | 填什么 | 如何获得 | 单位或范围 | 如何验证 |
|---|---|---|---|---|
| `CONFIGURATION_COMPLETE` | 十二项都已填写、同伴复核后才改为 `true` | 对照本表逐项签字，不靠“能编译”判断 | boolean；默认 `false` | INIT telemetry 不再显示 `CONFIGURATION_INCOMPLETE`，且其他 validation issue 为空 |
| `TEST_STAGE` | 当前只允许执行的阶段 | 严格按 `CONFIG_CHECK` → `SERVO_ONLY` → `SHORT_DRIVE` → `FULL_AUTO` 晋级 | `TestStage` 四选一 | telemetry 的 `test stage` 与本次评审记录一致，未授权机构不运动 |
| `SERVO_NAME` | 负责 preload/release 的 Servo 精确 hardware name | 在 RC Configuration 与实际端口逐字符核对 | 非空字符串，不能保留 `YOUR_` sentinel | `CONFIG_CHECK` INIT 能取得 Servo，无 `SERVO_INIT_FAILED` |
| `SERVO_CLOSED_POSITION` | 机构安全夹持/预装位置 | 断开负载或架起机构，从中间值以小步测试，观察不顶死 | Servo normalized `[0,1]` | `SERVO_ONLY` 到位、无持续堵转/干涉，并由机械 reviewer 确认 |
| `SERVO_OPEN_POSITION` | 机构完成安全释放的位置 | 从安全中间值逐步移动到刚好可靠释放，不能复制端点 | Servo normalized `[0,1]`，且不同于 closed | `SERVO_ONLY` 可重复释放，线缆/限位无碰撞 |
| `START_POSE` | INIT 时机器人真实起点 x/y/heading | 从已声明 Pedro 原点用卷尺量 x/y，用场地线/角尺量 heading | x/y inch；heading rad | 把机器人放起点，telemetry pose 与填写值在队伍阈值内 |
| `SCORE_POSE` | 机构可以安全释放的得分 pose | 在场地图量候选点，手推机器人确认外廓和机构空间 | Pedro x/y inch；heading rad；不同于 start/park | 先手推，再低功率到点；末端误差和机构间隙通过评审 |
| `SHORT_TEST_POSE` | 从 start 出发的空场短直线终点 | 用卷尺选无障碍、小位移点，不经过机构/场地物 | Pedro x/y inch；heading rad；不同于 start | `SHORT_DRIVE` 只走该短线，方向正确并在安全区停止 |
| `PARK_POSE` | score 之后的安全停车 pose | 按本赛季场地与机器人外廓测量，确认整条线段无障碍 | Pedro x/y inch；heading rad；不同于 start/score | 先手推完整路线，再在 `FULL_AUTO` 记录最终 pose/误差 |
| `RELEASE_WAIT_SECONDS` | release 后让真实机构完成动作所需的最短可靠等待 | 慢动作视频/telemetry 测多次机构完成时间，取有依据的裕量 | tutorial bound `0.05–5.0 s` | `SERVO_ONLY` 和 `FULL_AUTO` 中动作每次完成，且不是无意义长等 |
| `SHORT_DRIVE_MAX_POWER` | 首次短路径的低功率上限 | 从 `0.10–0.20` 的保守候选开始，安全员观察制动距离后评审 | motor-power proportion `(0,0.30]`，不是 in/s | `SHORT_DRIVE` 无打滑/失控并能在预定安全区停止 |
| `FULL_AUTO_MAX_POWER` | 四阶段前三项通过后的完整路线功率上限 | 从已通过的低功率逐步增加，每次记录跟踪误差与停止距离 | motor-power proportion `(0,1]`，不是 in/s | 完整路线多次可重复，误差、安全距离和机构时序均达阈值 |

### 代码如何限制能力

`beginner safety convention`：阶段 enum 同时声明 drive/servo capability；更换 `TEST_STAGE` 后必须重新 build 与 deploy：

```java
CONFIG_CHECK(false,false),SERVO_ONLY(false,true),
SHORT_DRIVE(true,false),FULL_AUTO(true,true)
```

所有动作只能经过 guard gateway。被 safety lock、错误阶段、缺资源或非法值拒绝时进入安全停止：

```java
if(safetyLocked||!TEST_STAGE.driveAllowed||follower==null||path==null) { /* stop */ }
if(safetyLocked||!TEST_STAGE.servoAllowed||servo==null||!inClosedUnitRange(position)) { /* stop */ }
```

这是 iterative OpMode：只有当前 stage 允许 drive 且安全锁已解除时，`loop()` 才通过下面的 canonical gateway 更新 follower；短路径完成后由 enum state transition 进入 `DONE`：

```java
private void updateFollowerIfAllowed() {
    if (safetyLocked||!TEST_STAGE.driveAllowed||follower==null) return;
    follower.update();
}

private void updateShortDriveTest() {
    if (autoState==AutoState.DRIVE_TO_PARK&&!follower.isBusy()) autoState=AutoState.DONE;
}
```

完整 Auto 同样用 `!follower.isBusy()` 推进 `DRIVE_TO_SCORE`、`RELEASE`、`RELEASE_WAIT`、`DRIVE_TO_PARK`、`DONE`，不使用 `sleep` 或阻塞循环。STOP 先提交逻辑锁定和 `STOPPED` state，再 best-effort 取消 follower：

```java
@Override
public void stop() {
    safetyLocked=true;
    autoState=AutoState.STOPPED;
    stopFollowingBestEffort();
}

// stopFollowingBestEffort() 内；follower 为空时会先 return
try {
    follower.breakFollowing();
} catch (RuntimeException ignored) {
    // Logical stop state is already committed.
}
```

Driver Station STOP 和空场安全员仍是真实安全边界。

## 四阶段实车测试清单

每阶段都填写 robot、reviewer、date 和证据链接。**晋级必须编辑 `TEST_STAGE`，重新 build 并 deploy**；只在 Driver Station 重启旧 OpMode 不算换阶段。

### 1. CONFIG_CHECK

- 前置：机器人架起或驱动轮离地；机构卸载；急停人员就位；十二项已双人检查，`CONFIGURATION_COMPLETE=true`。
- 允许动作：无 drive、无 servo、无定位运动验证；只做 static config 校验、follower/path/servo resource construction，并设置起始 pose。
- 记录 telemetry：`CONFIG: ...`、`safety locked`、`test stage`、`auto state`、`runtime failure` 和 initial static pose/telemetry。
- 通过：没有 `CONFIG: ...` validation issue，resources 都能构造，初始静态 pose 与填写值一致，任何机构均不运动。SafePedroAuto 在本阶段不会更新 localizer；移动与方向验证必须在官方 `Localization Test` 中完成。
- Reviewer：`<name>`；Date：`<YYYY-MM-DD>`；Robot/evidence：`<robot/link>`。
- 结果：通过前写 `硬件阶段未验证`；保存证据后写 `内容已验证`（配置内容）并晋级。

### 2. SERVO_ONLY

- 前置：CONFIG_CHECK 证据已审；驱动轮离地或电机断能；机构周围清空，从机械安全位置开始。
- 允许动作：只允许 Servo closed → 等待 → open；drive capability 为 false。
- 记录 telemetry：`test stage`、`auto state`、`state elapsed (s)`、`safety locked`、`CONFIG: ...`；视频记录机构位置与声音。
- 通过：closed/open 均可重复到位，无顶死、碰撞、拉线或非预期驱动运动。
- Reviewer：`<name>`；Date：`<YYYY-MM-DD>`；Robot/evidence：`<robot/link>`。
- 结果：通过前 `硬件阶段未验证`；通过后编辑为 `SHORT_DRIVE`，重新 build/deploy。

### 3. SHORT_DRIVE

- 前置：Localization Test 已通过；空场短路线已手推；机构固定安全；机器人后方/侧方留足停止距离，安全员持 STOP。
- 允许动作：只允许 start → short-test path；Servo capability 为 false，power 不得超过 `0.30`。
- 记录 telemetry：`x (in)`、`y (in)`、`heading (rad)`、`follower busy`、`auto state`、`runtime failure`；另记电池电压、末端误差与停止位置。
- 通过：只沿预期方向走短直线，在安全区停止，无明显打滑/振荡，STOP 能结束跟随。
- Reviewer：`<name>`；Date：`<YYYY-MM-DD>`；Robot/evidence：`<robot/link>`。
- 结果：通过前 `硬件阶段未验证`；通过后才编辑为 `FULL_AUTO`，重新 build/deploy。

### 4. FULL_AUTO

- 前置：前三阶段均有同一机器人/配置的证据；完整路线手推通过；场地清空；机构装载方式与比赛一致；安全员与边界明确。
- 允许动作：closed → score path → release → wait → park path；不允许额外机构或未评审路径。
- 记录 telemetry：每个 enum state 的进入时间，以及 `x (in)`、`y (in)`、`heading (rad)`、`follower busy`、`state elapsed (s)`、`runtime failure`；视频和末端 pose/误差。
- 通过：连续多次完成正确时序，路径/机构均无碰撞，末端误差、释放可靠性和停车范围达到队伍预先阈值。
- Reviewer：`<name>`；Date：`<YYYY-MM-DD>`；Robot/evidence：`<robot/link>`。
- 结果：证据不足仍写 `硬件阶段未验证`；只有真实证据齐全才写 `硬件四阶段已验证：<robot/reviewer/date>`。

## Telemetry 与排障

Driver Station 上的精确 telemetry labels 是 `configuration complete`、`test stage`、`auto state`、`safety locked`、`runtime failure`、`x (in)`、`y (in)`、`heading (rad)`、`follower busy`、`state elapsed (s)`；每项校验问题显示为 `CONFIG: ...`。先读 `CONFIG: ...` 和 `runtime failure`，不要先加大功率或 PID：

| 现象 | 先观察 | 优先检查/动作 |
|---|---|---|
| INIT 被锁 | `CONFIG: ...`、`test stage`、resource init | 十二字段 sentinel、hardware name、Follower/Servo 是否存在；一次修一项 |
| 前推 x 不增 | `x (in)`、`y (in)`、`heading (rad)` | Pinpoint x port、forward direction、resolution；不要调 follower |
| 左移 y 反号 | y 与 pod 原始方向 | y port、strafe direction、offset 符号 |
| 原地旋转画弧 | x/y 随 heading 的轨迹 | 两个 pod offset、旋转中心、机械松动 |
| 路径镜像 | start pose 与坐标草图 | 原点/联盟变换、视觉来源转换；禁止手工猜符号 |
| 振荡/过冲 | pose 噪声、velocity、busy、末端误差 | 先定位与机械，再按所选 braking/PIDF 路线；不要混抄参数 |
| 提前/永不完成 | busy 变化、velocity、平移/heading 误差、timeout | 找出实际放行或阻塞的 constraint，再单独调整 |
| Servo 顶死 | 视频、声音、供电、commanded position | 立即 STOP；回到 SERVO_ONLY，以小步重测位置 |
| telemetry 自身报错 | `runtime failure` 与 `safety locked` | 代码会进入 safety stop；修复 telemetry 前不继续硬件测试 |

每次测试都保存 stage、Git commit、robot configuration、battery、电池电压、输入值、telemetry/video 和 reviewer。低误差 telemetry 不等于避障、机构安全或比赛可靠。

## 20827-inspired advanced mapping

本节只记录在队伍代码中观察到的结构来源（observed team-code provenance）。它是可整体删除的**非规范**案例：20827 仓库不是 Pedro 技术权威，下面的架构也**不是 Pedro 官方要求**；删除本节不会改变本文任何 Pedro 官方指令、安全约定或 machine rule。

案例固定到 20827 commit [`118c28e137334bbbea510d77f1fa384e8b1b5779`](https://github.com/xiaokai-lyk/FTC20827-2026Decode/tree/118c28e137334bbbea510d77f1fa384e8b1b5779/TeamCode/src/main/java/org/firstinspires/ftc/teamcode)。只比较结构，不复制 route coordinates 或 mechanism sequence：

| Beginner example | 20827 case | Migration lesson |
|---|---|---|
| one `SafePedroAuto` | [`TopAutoBase` / `BottomAutoBase`](https://github.com/xiaokai-lyk/FTC20827-2026Decode/tree/118c28e137334bbbea510d77f1fa384e8b1b5779/TeamCode/src/main/java/org/firstinspires/ftc/teamcode/autos) | move route flow into a reusable base only after one route is understood |
| Pose constants in one file | [`TopAutoRed` / `TopAutoBlue` constructor parameters](https://github.com/xiaokai-lyk/FTC20827-2026Decode/tree/118c28e137334bbbea510d77f1fa384e8b1b5779/TeamCode/src/main/java/org/firstinspires/ftc/teamcode/autos) | thin alliance classes supply coordinates without duplicating state flow |
| `Constants.createFollower` | [same centralized factory pattern](https://github.com/xiaokai-lyk/FTC20827-2026Decode/blob/118c28e137334bbbea510d77f1fa384e8b1b5779/TeamCode/src/main/java/org/firstinspires/ftc/teamcode/pedroPathing/Constants.java) | keep drivetrain/localizer construction out of match logic |
| enum state machine | [integer `pathState` in the case study](https://github.com/xiaokai-lyk/FTC20827-2026Decode/tree/118c28e137334bbbea510d77f1fa384e8b1b5779/TeamCode/src/main/java/org/firstinspires/ftc/teamcode/autos) | retain named enum states for newcomer code; integer states are not required |
| direct Servo gateway | [`XKCommandOpmode` + FTCLib scheduler](https://github.com/xiaokai-lyk/FTC20827-2026Decode/blob/118c28e137334bbbea510d77f1fa384e8b1b5779/TeamCode/src/main/java/org/firstinspires/ftc/teamcode/utils/XKCommandOpmode.java) | command framework is an optional mechanism-coordination upgrade |
| prebuilt paths | [`Supplier<PathChain>` from current pose](https://github.com/xiaokai-lyk/FTC20827-2026Decode/tree/118c28e137334bbbea510d77f1fa384e8b1b5779/TeamCode/src/main/java/org/firstinspires/ftc/teamcode/autos) | dynamic return paths are advanced and require explicit current-pose reasoning |

20827 也不是 16093 或新机器人的参数来源。不得复制其中的 hardware names、servo positions、poses、offsets、directions、mass、velocity、PIDF、power 或 timeout。想增加并行机构、复杂路径或传感器分支时，先让四阶段最小 Auto 在当前机器人通过，再单独设计和评审。

## 相关规则与来源

以下三条都是 `APPROVED` 的 `shared` 规则，由 overall software lead 批准，并在 2025-2026 对 20827 与 16093 的解析中 active：

- `shared.pedro-tune-current-robot`：只使用当前机器人测得/调出的值；
- `shared.pedro-localization-before-follower`：定位先通过，再评价 follower；
- `shared.pedro-explicit-coordinate-conversion`：明确声明并转换外部坐标。

### 官方来源

官方与版本来源：

- [Pedro Installation](https://pedropathing.com/docs/pathing/installation)
- [Constants](https://pedropathing.com/docs/pathing/constants)
- [Tuning order](https://pedropathing.com/docs/pathing/tuning)
- [Localization choices and test](https://pedropathing.com/docs/pathing/tuning/localization)
- [Pinpoint setup](https://pedropathing.com/docs/pathing/tuning/localization/pinpoint)
- [Coordinates and PoseConverter](https://pedropathing.com/docs/pathing/reference/coordinates)
- [Path constraints](https://pedropathing.com/docs/pathing/reference/constraints)
- [Detecting path completion](https://pedropathing.com/docs/pathing/reference/pathcomplete)
- [Example Auto](https://pedropathing.com/docs/pathing/examples/auto)
- [Pedro Quickstart at `d3aea9c`](https://github.com/Pedro-Pathing/Quickstart/tree/d3aea9ca3c5b4c09eded8580229b86996480ee89)
- [Pedro Pathing 2.1.2 source at `96df977`](https://github.com/Pedro-Pathing/PedroPathing/tree/96df977d30329eef57c226cf1e6854026f4dfe4f)
- [FIRST FTC SDK v11.2 at `4ed7c466`](https://github.com/FIRST-Tech-Challenge/FtcRobotController/tree/4ed7c4666aec265a6fd9e674ca40462e9dfe4bf8)

仓库内的 canonical Java、compile fixture 与测试决定“本项目当前验证了什么”；官方来源决定 Pedro API/流程；两者都不能替代实车证据。
