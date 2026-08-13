# Pedro Pathing 配置、定位与调参教程

> 核验日期：2026-08-13。Pedro Pathing 网页未显示统一的软件版本号；依赖版本必须以队伍工程中实际固定的版本为准。本文只使用当前 `Constants`/`FollowerBuilder` API，不与旧版 `FConstants`/`LConstants` 写法混用。

## 它解决什么问题

Pedro Pathing 是 FTC 的 autonomous path follower（自动路径跟随器）。它用定位反馈、Bézier 曲线、PIDF、向心力修正和制动逻辑，让全向底盘沿路径运动。它不是“输入几个点就自动准确”的黑盒：定位、质量、电机方向、实测速度、控制参数和完成条件都与当前机器人有关。

使用前需要 Android Studio、全向底盘和一种 localizer（定位器），例如里程轮、Pinpoint、OTOS 或驱动编码器。先完成[FTC SDK 配置](../setup/android-studio-ftc-sdk.md)；需要 FTC Dashboard 时参考[Dashboard 教程](../setup/ftc-dashboard.md)。

## 坐标系先说清楚

Pedro 使用右手坐标系，和 FTC SDK standard coordinate system 不同。按官方当前场地图：

- 场地图向右是 `+x`，向上是 `+y`；
- 朝右的 heading（航向）是 `0 rad`；
- 朝上是 `π/2 rad`，朝左是 `π rad`；
- 逆时针为正旋转；
- PathBuilder 的 heading 使用弧度，角度输入要先 `Math.toRadians(degrees)`；
- 官方 Example Auto 把 12 ft × 12 ft 场地写成 x/y `[0,144]` in，原点是图中左下角。

在任何 pose（位姿）例子前都要记录：场地版本、原点、轴方向、长度单位、角度单位和正旋方向。视觉系统输出不能靠手工交换 x/y 或猜符号。

当前官方转换方式是先声明来源坐标系，再转为 Pedro：

```java
Pose ftcStandard=PoseConverter.pose2DToPose(
    ftcPose2d,
    InvertedFTCCoordinates.INSTANCE
);
Pose pedroPose=ftcStandard.getAsCoordinateSystem(
    PedroCoordinates.INSTANCE
);
```

`InvertedFTCCoordinates` 是官方针对当前 DECODE 示例使用的来源约定；换赛季或换视觉系统必须重新确认来源坐标系。不要把这段中的赛季选择当作永久默认值。这对应 `shared.pedro-explicit-coordinate-conversion`。

## Constants 参数分成四类

| 分类 | 例子 | 值的性质 |
|---|---|---|
| Follower constants | 质量、预测制动或 PIDF 参数、向心力缩放 | 当前机器人实测/调参结果 |
| Drivetrain constants | 电机名、方向、最大功率、x/y velocity | 硬件配置与实测值 |
| Localizer constants | 硬件名、pod offset、编码器方向 | 当前机器人测量/配置 |
| Path constraints | t-value、速度、平移/航向容差、timeout | 队伍策略与调参值 |

重要单位和边界：

| 字段 | 官方含义/单位 |
|---|---|
| `mass` | 机器人质量，kg |
| `maxPower` | 电机最大功率，范围 `[0,1]` |
| odometry pod offsets | 各 localizer 页面给出的相对旋转中心偏移；three-wheel 页面使用 in |
| t-value constraint | 无量纲，范围 `[0,1]` |
| velocity constraint | in/s |
| timeout constraint | ms；机器人到达路径末端后允许纠正的时间 |
| translational constraint | 最大平移误差；官方 Constraints 此页未明确单位 |
| heading constraint | 最大航向误差；官方 Constraints 此页未明确单位 |

官方示例里的数值只是演示，不是你的默认值。`shared.pedro-tune-current-robot` 要求质量、offset、速度和控制参数都来自当前机器人；`shared.pedro-localization-before-follower` 要求先证明定位正确再评价路径。

## 安装与版本固定

最稳妥的新人路线是从官方 Quickstart 开始，并把实际 commit/依赖版本写入队伍版本记录。手动安装页使用占位版本：

```groovy
implementation 'com.pedropathing:ftc:x.y.z'
implementation 'com.pedropathing:telemetry:1.0.0'
implementation 'com.bylazar:fullpanels:1.0.12'
```

`x.y.z` 不是可复制值。应从官方 Quickstart 的锁定依赖中读取精确版本，再在同一提交中记录。当前手动安装页还要求 compile SDK 34；这会改变 FIRST v11.2 的构建配置，必须单独做兼容性分支和完整回归，不能在比赛工程里边调路径边升级工具链。

## 正确的调试顺序

1. **固定依赖与工程版本**：记录 Pedro Quickstart commit 或精确 artifact 版本；
2. **底盘配置**：核对四个 hardware map 名称和每个电机方向；低功率确认所有轮子合力方向；
3. **机器人质量**：称量完整比赛状态机器人，以 kg 写入 `.mass(...)`；
4. **选择并配置 localizer**：写硬件名、offset 和 encoder direction；
5. **运行 Localization Test**：前推应增加 x，左移应增加 y，逆时针转动应增加 heading；
6. **运行 forward/lateral velocity tuners**：得到当前机器人 x/y velocity；
7. **调 heading**：先确认符号和单位，再评价跟随误差；
8. **二选一**：按官方当前流程选择 predictive braking，或完整 PIDF/zero-power/centripetal 路线，不要混抄两套中间值；
9. **设置 path constraints**：让完成与 timeout 在 telemetry 中可观察；
10. **低功率短路径实车测试**：从直线、低速、空场开始，再增加曲线和速度。

每一步都保留输入、测量条件和结果。定位轴错时调 PID 只会把错误隐藏得更危险。

## 当前 API 的最小路径例子

下列大写值全是占位符，必须换成你在明确 Pedro 坐标系中测得的安全点位。`Constants.createFollower()` 也必须已经接入你的 drivetrain、localizer 和 path constraints。

```java
package org.firstinspires.ftc.teamcode;

import com.pedropathing.follower.Follower;
import com.pedropathing.geometry.BezierLine;
import com.pedropathing.geometry.Pose;
import com.pedropathing.paths.PathChain;
import com.qualcomm.robotcore.eventloop.opmode.Autonomous;
import com.qualcomm.robotcore.eventloop.opmode.OpMode;

@Autonomous(name="Pedro Smoke Test",group="Setup")
public class PedroSmokeTest extends OpMode {
    private static final double START_X=YOUR_START_X_INCHES;
    private static final double START_Y=YOUR_START_Y_INCHES;
    private static final double START_HEADING_RADIANS=YOUR_START_HEADING_RADIANS;
    private static final double END_X=YOUR_SAFE_END_X_INCHES;
    private static final double END_Y=YOUR_SAFE_END_Y_INCHES;
    private static final double END_HEADING_RADIANS=YOUR_END_HEADING_RADIANS;

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
        follower.setStartingPose(
            new Pose(START_X,START_Y,START_HEADING_RADIANS)
        );
    }

    @Override
    public void start() {
        follower.followPath(path);
    }

    @Override
    public void loop() {
        follower.update();
        telemetry.addData("x (in)",follower.getPose().getX());
        telemetry.addData("y (in)",follower.getPose().getY());
        telemetry.addData("heading (rad)",follower.getPose().getHeading());
        telemetry.addData("busy",follower.isBusy());
        telemetry.update();
    }
}
```

每个 loop 必须调用一次 `follower.update()`。官方推荐用 `!follower.isBusy()` 判断完成；它只有在路径末端约束满足或 timeout 条件达到后才变为完成。不要用固定 `sleep` 假装路径完成。

## 分层验收

### 1. 定位验收

- 机器人向场地图右方移动，x 是否按预期增加；
- 机器人向场地图上方/左 strafe 测试方向移动，y 是否按官方测试增加；
- 逆时针旋转，heading 是否增加且单位是 rad；
- 静止 10–30 s 的 pose 漂移是否低于队伍事先写下的验收阈值；
- 手推已知距离和旋转已知角度，输出误差是否可重复。

### 2. 起点与路径验收

- DS INIT 时的实际机器人位置是否与 `setStartingPose` 一致；
- 路径第一点是否等于实际起点，而不是上一次运行终点；
- 先用低 `maxPower` 跑短直线；
- telemetry 同时显示 pose、heading、`isBusy`、路径状态和超时；
- STOP 后所有驱动电机归零。

### 3. 完成条件验收

记录路径结束时的速度、平移误差、航向误差、`isBusy` 变化时间和是否由 timeout 放行。早结束和永不结束都不是“多跑几次看看”的问题，而是 constraints 或定位需要修正。

## 常见问题

| 现象 | 优先检查 |
|---|---|
| 推前却不是 x 增加 | localizer 类型 → encoder 名称/方向 → 坐标测试是否按官方场地图执行 |
| 左移 y 符号反了 | strafe encoder direction → pod 安装方向 → 是否混入 FTC/vision 坐标 |
| 路径镜像 | 起点 alliance 变换 → 原点/轴定义 → 是否手工交换符号而未用 PoseConverter |
| 原地振荡 | 定位噪声/符号 → 电机方向 → heading/translation 参数；不要先继续增大增益 |
| 过冲 | 实测速度/零功率减速度 → braking 路线 → 质量与轮胎状态 → constraints |
| 提前完成 | t-value、速度、平移、航向和 timeout telemetry → 哪个条件实际放行 |
| 永不完成 | `isBusy`、末端误差 → constraints 是否过严 → localizer 是否持续漂移 |
| 左右电机互相打架 | motor name 和 direction → 单轮低功率测试 → 机械阻力；立即 STOP |
| 换机器人后突然失准 | 是否复制旧质量、offset、速度、PIDF 或制动值 → 对当前机器人从 Localization Test 重做 |

## 安全与误用边界

- 调参区必须清场，机器人设置急停人员，首次测试限功率；
- 不把官方 example constants、其他队伍 constants 或旧赛季 pose 当作已验证值；
- 路径开始前确认机构收回、起点正确、localizer 已归零/设置 pose；
- 外部视觉 pose 必须先做 validity、freshness、坐标与单位转换，再考虑融合；
- 低误差 telemetry 不等于避障或机械安全。

## 相关规则

- `shared.pedro-tune-current-robot`
- `shared.pedro-localization-before-follower`
- `shared.pedro-explicit-coordinate-conversion`

这些规则当前都是已批准的 `shared` 规则，且在 2025-2026 赛季对 20827 与 16093 的解析中生效。

## 官方来源

- [Pedro Pathing Introduction](https://pedropathing.com/docs/pathing)
- [Installation](https://pedropathing.com/docs/pathing/installation)
- [Constants](https://pedropathing.com/docs/pathing/constants)
- [Setup](https://pedropathing.com/docs/pathing/tuning/setup)
- [Localization](https://pedropathing.com/docs/pathing/tuning/localization)
- [Tuning order](https://pedropathing.com/docs/pathing/tuning)
- [Coordinates and PoseConverter](https://pedropathing.com/docs/pathing/reference/coordinates)
- [Path Builder](https://pedropathing.com/docs/pathing/reference/path-builder)
- [Constraints](https://pedropathing.com/docs/pathing/reference/constraints)
- [Detecting Path Completion](https://pedropathing.com/docs/pathing/reference/pathcomplete)
- [Example Auto](https://pedropathing.com/docs/pathing/examples/auto)
