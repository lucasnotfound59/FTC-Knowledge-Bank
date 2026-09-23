# Pedro Pathing 3 新生 Auto 教程

采用 RookieBot 新手项目结构时，另见 [Hardwares 与简洁 Auto 约定](../practices/rookiebot-tutorial.md)。本页讲 Pedro 3 API、当前机器人调参和分阶段安全验证；唯一完整 Java 示例是 [SafePedroAuto.java](../../examples/pedro/SafePedroAuto.java)。

> 资料核对：2026-09-23。Gradle 编译只验证依赖与 Java API；Robot Controller、Driver Station、定位、机构和真机路径必须另外验证。目前没有已完成的 Pedro 实车四阶段记录。

## 适用范围与证据标签

先完成 [FTC SDK / Android Studio 配置](../setup/android-studio-ftc-sdk.md)。FTC Dashboard 是可选观察工具，见 [FTC Dashboard 教程](../setup/ftc-dashboard.md)。

| 标签 | 含义 | 如何使用 |
| --- | --- | --- |
| Pedro requirement | Pedro 3 官方文档或固定版本 API | 遵守接口与流程，不能照抄机器人参数 |
| beginner safety convention | 知识库为首次实车测试加的阶段锁 | 保留门控，并在当前机器人记录验证 |
| 20827-inspired pattern | 队伍旧代码的架构观察 | 只学组织方式，不把旧 Pedro API 当作 v3 |
| robot-specific value | 名称、方向、offset、pose、servo 位置、速度、Foresight 参数 | 由当前机器人实测、复核、记录 |

本文的验证标签分开写：内容/官方来源已核对、Java 编译通过、硬件阶段未验证。不要把前两者写成真机验证。

## 版本矩阵与两条安装路线

| Item | 固定版本 | 作用 |
| --- | --- | --- |
| FIRST FTC SDK | [v11.2.1 / 26cd1fdd2a3c4b26173d9ff33a3279c27d1c7ad1](https://github.com/FIRST-Tech-Challenge/FtcRobotController/releases/tag/v11.2.1) | 本仓库 Android 编译 fixture 的 SDK 基线 |
| Pedro Pathing | [v3.0.0 / fa5a07c7761ed01f943ef37ea29b71f30138b5d1](https://github.com/Pedro-Pathing/PedroPathing/releases/tag/v3.0.0) | revhub 3.0.0、tuning 1.0.0 与示例 API |
| Pedro Quickstart | [b4312385b7d0cc5e8dd263ec3927c9ef0cb48f36](https://github.com/Pedro-Pathing/Quickstart/tree/b4312385b7d0cc5e8dd263ec3927c9ef0cb48f36) | 安装/工程结构的可复核快照 |

本仓库 fixture 使用 compile SDK 34、FTC SDK 11.2.1、Pedro revhub 3.0.0 和 tuning 1.0.0，只编译核心 SafePedroAuto 与其 Constants 适配器；不覆盖复制来的 tuner、Ivy、Dashboard/Panels 或真实硬件。版本组合的编译结果不是官方兼容保证。

### Route A — official Quickstart snapshot

1. clone 官方 Quickstart，checkout 上表固定 commit；不要悄悄追踪 main。
2. 核对根目录 build.dependencies.gradle 与 TeamCode 的 pedro 包。该固定 Quickstart 的 Constants.java 是返回 null 的占位实现，**不能直接当作可运行的 Follower 配置**。
3. 按[官方 Constants 教程](https://pedropathing.com/docs/pathing/tuning/constants)完成自己的 MecanumConfig、所选 LocalizerConfig、ForesightConfig，并让 Constants.create(HardwareMap) 真正构造三者；所有硬件名和数值必须来自当前机器人。
4. Gradle Sync/build 后，先架起机器人核对硬件配置和方向；获得队员复核后只运行 Localization Test，不直接运行路径。
5. 手推向前 x 增、向左 y 增、逆时针 heading 增，并记录已知距离/角度误差。定位失败就停在定位阶段。

### Route B — current FIRST v11.2.1 team project

[官方安装页](https://pedropathing.com/docs/pathing/installation)指定在项目根目录 build.dependencies.gradle 的 repositories 和 dependencies 中添加下列内容；本仓库的跨赛季依赖规则对此给出条件式 soft 提醒，Agent 必须把第一方来源、固定版本与实际 diff 逐项对应说明：

~~~groovy
repositories {
    maven { url 'https://repo.dairy.foundation/releases/' }
}
dependencies {
    implementation 'com.pedropathing:revhub:3.0.0'
    implementation 'com.pedropathing:tuning:1.0.0'
}
~~~

不要把旧的依赖、Maven 仓库或 API 示例混入 v3 工程。复制官方 pedro 包时要保留来源 commit，但先补完占位 Constants 和当前机器人调参。只有复制并使用 tuners/Panels 时才单独验收它们：它们不在核心编译 fixture 的范围内。当前团队工程还需独立核对 Android Gradle Plugin、compile SDK 34、SDK 版本与整项目构建；不要修改 FTC 保留的 build.common.gradle 来绕过不兼容。Gradle Sync、build、部署、Driver Station 列表和真机运行是不同关卡。

## 坐标系

Pedro 3 的 [Pose 创建](https://pedropathing.com/docs/pathing/guide/pose-creation)使用 com.pedropathing.math.Pose；PoseFactory.degrees() 可用角度创建，Pose 内 heading 以弧度表达。示例为避免隐式角度转换，直接使用 new Pose(xIn,yIn,headingRad)。先画出本赛季场地原点、+x、+y、零航向和联盟侧；位置单位与 LocalizerConfig 的 globalDistanceUnit 一致，示例采用 inch。静态起点、手推已知距离和逆时针转动都要核对。

外部视觉或 FTC 坐标结果不能靠交换 x/y 猜转换。先记录来源原点、轴、长度/角度单位与正转方向，再做显式转换，最后用已知位置验证；这也是 shared.pedro-explicit-coordinate-conversion 的要求。旧版坐标转换代码不是 Pedro 3 的现成 API，不要复制旧示例。

## Constants 的四类参数

[官方 Constants](https://pedropathing.com/docs/pathing/tuning/constants)要求把 Localizer、Drivetrain、Algorithm 都交给 Follower 构造器；固定 Quickstart 的 null 占位不是配置。fixture 的 [Constants.java](../../../fixtures/pedro-compile/src/main/java/org/firstinspires/ftc/teamcode/pedro/Constants.java)只用于编译，包含未经本队机器人验证的官方示例数值，禁止部署。

| 类别 | 当前机器人要填什么 | 验证 |
| --- | --- | --- |
| MecanumConfig | 四电机 hardware name、方向、机械布局 | 架空单轮与低速直线/横移逐项核对 |
| LocalizerConfig | 实际传感器、名称、pod 方向、offset、单位和分辨率 | 手推已知距离与角度，检查轴/符号/尺度/静止漂移 |
| ForesightConfig | 当前机器人的控制器、自然减速度、可达速度及约束 | 按官方 AutoTune 分步骤记录，不复制示例 gain |
| Path / end constraints | heading 插值、目标速度、完成阈值与 timeout | 观察 isBusy、终点速度/误差和重复性 |

完整构造形状如下；具体 config 必须先按实际硬件填好：

~~~java
public static Follower create(HardwareMap h) {
    return new Follower(
        new PinpointLocalizer(h, localizerConfig),
        new Mecanum(h, drivetrainConfig),
        new Foresight(foresightConfig)
    );
}
~~~

如果当前机器人不是 Pinpoint/Mecanum，替换成对应已配置的 localizer/drivetrain，而不是为了复制示例去改硬件。

## Pedro 3 的 localizer 选择

[官方 Localization 目录](https://pedropathing.com/docs/pathing/tuning/localization)列出 Pinpoint、OTOS、Three Wheel 等选项。只选当前机器人真实安装且能验证的一种；此处展开 Pinpoint，不表示它对所有队伍最佳。先通过 Localization Test，再开始 Foresight/path tuning，对应 shared.pedro-localization-before-follower。

## Pinpoint 完整新生流程

### 机械、接线与配置

1. forward pod 接 Pinpoint x port，lateral/strafe pod 接 y port；贴纸/接口朝上。官方 [Pinpoint 指南](https://pedropathing.com/docs/pathing/tuning/localization/pinpoint)要求插在 I2C port 0 以外的端口。
2. 在 RC Configuration 为 Pinpoint 设唯一 hardware name，核对 pod 型号与实际方向。先架空、检查接线和固定，再手推定位；不要给电机下路径命令。
3. 记录机器人旋转中心，测量 pod offset。不要复制 20827、16093 或官方例子数值。
4. 运行 Pinpoint AutoTuner；连到 Robot Controller 后访问 http://192.168.43.1:10158，依次完成型号/自定义分辨率、前进方向、横移方向和逆时针 180° offset 测试，把生成配置复核后放入 Constants.java。
5. 再运行 Localization Test：向前推动时 x 增加、向左推动时 y 增加；已知距离与角度误差和静止漂移均应达到队内预定阈值。任何失败都不得进入路径调参。

### Pinpoint 参数契约

Pedro 3 的 PinpointConfig 字段是 name、xPodOffset、yPodOffset、xPodDirection、yPodDirection；还可按硬件设置 podType、ticksPerUnit、encoderResolutionUnit、offsetUnits、globalDistanceUnit 和 resetMode。字段含义与旧版 API 不同。自定义 pod 的 ticksPerUnit 必须对应 encoderResolutionUnit，不能假定永远是 ticks/mm。优先用官方 Pinpoint AutoTuner 与已知距离实测，保留来源、测量和 reviewer；若使用默认 goBILDA pod 枚举，也要核对实物型号。

| 项目 | 填写依据 | 最小通过证据 |
| --- | --- | --- |
| name / podType | RC 配置与 pod 实物型号 | INIT 能读到设备；型号与记录一致 |
| xPodOffset / yPodOffset | 从旋转中心测量，AutoTuner 辅助识别 | 原地转动不造成系统性位置漂移 |
| xPodDirection / yPodDirection | 手推前进、左移确定符号 | 前推 x 增、左移 y 增 |
| ticksPerUnit / encoderResolutionUnit | 仅自定义 pod 按选定长度单位测量 | 多个已知距离的比例误差可重复且达阈值 |
| globalDistanceUnit / resetMode | 队伍坐标单位与启动流程 | 起点和重启后的 pose 与记录一致 |

## 官方调参顺序

Pedro 3 的 [Tuning](https://pedropathing.com/docs/pathing/tuning)以 AutoTune 为主。按 Drivetrain → Localization → Foresight 的顺序逐项记录输入、机器人配置、电池电压、输出与 reviewer。先确认电机名和方向；定位轴/尺度没有通过就不要调整 Foresight；随后按 [Foresight 页面](https://pedropathing.com/docs/pathing/tuning/foresight)和 [Path Constraints](https://pedropathing.com/docs/pathing/reference/pathconstraints)调控制、减速及完成条件。更换轮胎、质量、传动、pod 安装或整车后，受影响的参数必须重测。

## SafePedroAuto 参数字典

只编辑 [完整 SafePedroAuto.java](../../examples/pedro/SafePedroAuto.java) 两个 CONFIGURE HERE marker 之间的十二项；默认 CONFIGURATION_COMPLETE=false，TEST_STAGE=CONFIG_CHECK，不能因为编译通过就解锁。首先用当前机器人实现并复核 Constants.create(hardwareMap)。

| 参数 | 填什么 / 如何获得 | 单位或范围 / 验证 |
| --- | --- | --- |
| CONFIGURATION_COMPLETE | 十二项与 Constants、硬件均复核后才设 true | 默认 false；INIT 无 CONFIG 问题且机构不运动 |
| TEST_STAGE | CONFIG_CHECK → SERVO_ONLY → SHORT_DRIVE → FULL_AUTO | 每次晋级重新 build/deploy，记录 reviewer |
| SERVO_NAME | RC Configuration 中准确名称 | 非空，不能保留 YOUR_ sentinel；CONFIG_CHECK INIT 可读取 |
| SERVO_CLOSED_POSITION | 机构不顶死的闭合位置，架起后小步测 | [0,1]，与 open 不同；SERVO_ONLY 复核 |
| SERVO_OPEN_POSITION | 刚好可靠释放的位置，逐步实测 | [0,1]，无碰撞/拉线；SERVO_ONLY 复核 |
| START_POSE | 当前起点实测 x/y/heading | x/y inch、heading rad；telemetry 对齐 |
| SCORE_POSE | 安全释放位置与外廓空间 | 先手推路线，再短路径验证 |
| SHORT_TEST_POSE | 空场短直线终点 | 不与 start 相同，留足停止距离 |
| PARK_POSE | score 后的安全停车点 | 手推整线无障碍，末端在安全区 |
| RELEASE_WAIT_SECONDS | 多次测出的机构完成时间加有据裕量 | 本教程限定 0.05–5.0 s |
| SHORT_DRIVE_MAX_PATH_SPEED | 初次短路径的保守目标速度比例，先用 0.20 候选 | (0,0.30]；对当前可达速度的比例，不是电机功率上限 |
| FULL_AUTO_MAX_PATH_SPEED | 前三阶段通过后逐步提高的目标速度比例 | (0,1]；每次记录制动距离和末端误差 |

[Pedro 3 Path Constraints](https://pedropathing.com/docs/pathing/reference/pathconstraints)把 maxPathSpeed 定义为机器人当前最大可达路径速度的比例；它仅限制路径段的目标速度，**不是电机功率上限**，不等价于 Pedro 2.1.2 followPath(path,maxPower,...) 的 maxPower。控制器纠偏/制动仍可能请求更大电机功率。SHORT_DRIVE 只能在空场、低风险条件下由安全员持 STOP 验证；若队伍需要真正的电机功率硬限制，须另设计并实测底盘输出限幅，不能用本参数冒充。

### 代码如何限制能力

阶段 enum 声明 drive/servo 能力：

~~~java
CONFIG_CHECK(false,false),SERVO_ONLY(false,true),
SHORT_DRIVE(true,false),FULL_AUTO(true,true)
~~~

所有运动只经过 guard gateway，且 loop 非阻塞：

~~~java
if (safetyLocked||!TEST_STAGE.driveAllowed||follower==null||path==null||
    !inSpeedFractionRange(maxPathSpeed)) { /* safety stop */ }
Constants.foresightConfig.maxPathSpeed.set(maxPathSpeed);
follower.follow(path);

private void updateFollowerIfAllowed() {
    if (safetyLocked||!TEST_STAGE.driveAllowed||follower==null) return;
    follower.update();
}
private void updateShortDriveTest() {
    if (autoState==AutoState.DRIVE_TO_PARK&&!follower.isBusy()&&finishPathOutput("short drive"))
        transitionTo(AutoState.DONE);
}
~~~

Path 用 Pedro 3 的 Paths.line(start,end).linear(start,end) 创建。所有状态变化经过 transitionTo；仅当新状态不同才重置 stateTimer。舵机位置写成功后才更新 lastServoCommand，它是最后发出的命令，不是位置反馈。Pedro 3 路径结束时可能进入保持位姿模式；`isBusy()==false` 不等于电机零输出。示例在短线、得分段和最终停车段完成时都调用 `finishPathOutput`，由它执行 `follower.stop()` 与一次 `follower.update()` 发送停止输出，失败则进入 SAFETY_STOP；如果定位更新异常导致该停止序列无法完成，还会尝试直接调用 `follower.drivetrain.stop()`。OpMode 的 stop() 也先锁定并切换 STOPPED，再做同样的 best-effort 停止。软件停止请求不等于机器人已物理停稳，也不代替 Driver Station STOP 和安全员。

## 四阶段实车测试清单

每阶段记录 robot、commit、RC Configuration、参数、reviewer、日期、telemetry/video 和结果。晋级必须编辑 TEST_STAGE、重新 build/deploy；没有当前机器人的证据就保持“硬件阶段未验证”。

### 1. CONFIG_CHECK

机器人架起、驱动轮离地、机构卸载，安全员在场。十二项和 Constants 双人核对后才把 CONFIGURATION_COMPLETE 设 true。INIT 只做静态校验与资源构造，不跟随路径、不更新 follower、不写 Servo。通过条件：无 CONFIG 问题、资源可构造、静态起始 pose 与输入一致且机构不运动。随后另用官方 Localization Test 手推确认定位轴/尺度；此阶段本身不证明定位运动正确。

### 2. SERVO_ONLY

驱动轮离地或电机断能，机构清空。只允许 closed → 等待 → open，drive capability 为 false。记录 state elapsed (s)、last servo command、视频/声音；无顶死、干涉、拉线及意外驱动才通过。

### 3. SHORT_DRIVE

Localization Test 已通过；空场短线已手推；Servo 固定安全；留足停止距离，安全员持 STOP。只允许 start → SHORT_TEST_POSE。SHORT_DRIVE_MAX_PATH_SPEED=0.20 是目标路径速度比例，不是 20% 电机功率硬上限，也不保证制动距离。记录 pose、follower busy、电池电压、停止位置/误差；方向正确、安全停止且 STOP 可终止后才晋级。

### 4. FULL_AUTO

前三阶段在同一机器人配置上有证据，完整路线手推通过。只允许 closed → score path → release → wait → park path；记录每个 state、pose、机构视频、终点误差和 STOP 行为。多次可重复且满足预先阈值才能写“硬件四阶段已验证：robot/reviewer/date”。

## Telemetry 与排障

Driver Station 精确 label：configuration complete、test stage、auto state、safety locked、runtime failure、x (in)、y (in)、heading (rad)、follower busy、last servo command、state elapsed (s)；每个问题以 CONFIG: 开头。state elapsed (s) 只表示当前状态时长；last servo command 不反映舵机实际位置。

| 现象 | 优先检查 |
| --- | --- |
| INIT 锁住 | CONFIG 问题、名称、Constants/Pinpoint/Servo 构造；不要先解锁 |
| 前推 x 或左移 y 反号 | Pinpoint 接线、方向、单位与 pod 型号；不要先调 Foresight |
| 原地旋转画弧 | offset、旋转中心与机械松动 |
| 路径镜像 | 场地原点/联盟转换与视觉来源单位 |
| 振荡或过冲 | 先排定位噪声，再依官方 Foresight 调参；不要照抄 gain |
| 提前/永不完成 | isBusy、终点误差和 Foresight end constraints |
| Servo 顶死 | 立即 STOP，回到 SERVO_ONLY 小步重测位置 |

低误差 telemetry 不等于避障、机构安全或比赛可靠。任何异常都停止该阶段并保存证据。

## 20827-inspired advanced mapping

本节是旧队伍代码的**历史结构来源，非规范，也不是 Pedro 3 API 证据**。固定 [20827 commit 118c28e](https://github.com/xiaokai-lyk/FTC20827-2026Decode/tree/118c28e137334bbbea510d77f1fa384e8b1b5779/TeamCode/src/main/java/org/firstinspires/ftc/teamcode)：TopAutoBase、BottomAutoBase 把路线流程集中；TopAutoRed、TopAutoBlue 通过构造参数区分联盟；XKCommandOpmode 是可选命令框架。可借鉴“集中构造 Follower”的想法，在 Pedro 3 项目使用 Constants.create；旧提交本身不是该 v3 方法的实现。旧动态路径供应写法与具体 pose/机构序列不能直接搬进 Pedro 3。

## 历史来源：Pedro 2.1.2

仅供追溯：旧文档曾使用 [Pedro 2.1.2 source 96df977d30329eef57c226cf1e6854026f4dfe4f](https://github.com/Pedro-Pathing/PedroPathing/tree/96df977d30329eef57c226cf1e6854026f4dfe4f)、[Quickstart d3aea9ca3c5b4c09eded8580229b86996480ee89](https://github.com/Pedro-Pathing/Quickstart/tree/d3aea9ca3c5b4c09eded8580229b86996480ee89)、com.pedropathing:ftc:2.1.2 和 FollowerBuilder。它们不再是本教程的安装或编码步骤；不要在 Pedro 3 中使用。

## 相关规则与来源

按真实 team、season、profile 运行 ftckb resolve，而不是手工猜生效规则。相关 approved 规则包括 shared.pedro-tune-current-robot、shared.pedro-localization-before-follower、shared.pedro-explicit-coordinate-conversion；global.vendor-documented-build-dependencies 要求每次根依赖改动有官方证据和精确版本对应。

### 官方来源

- [Pedro 3 Installation](https://pedropathing.com/docs/pathing/installation)
- [Pedro 3 Constants](https://pedropathing.com/docs/pathing/tuning/constants)
- [Pedro 3 Pose creation](https://pedropathing.com/docs/pathing/guide/pose-creation)
- [Pedro 3 Path creation](https://pedropathing.com/docs/pathing/guide/path-creation)
- [Pedro 3 Path following](https://pedropathing.com/docs/pathing/guide/path-following)
- [Pedro 3 Follow states](https://pedropathing.com/docs/pathing/guide/follow-state)
- [Pedro 3 Pinpoint](https://pedropathing.com/docs/pathing/tuning/localization/pinpoint)
- [Pedro 3 Path constraints](https://pedropathing.com/docs/pathing/reference/pathconstraints)
- [Pedro Pathing v3.0.0 release](https://github.com/Pedro-Pathing/PedroPathing/releases/tag/v3.0.0)
- [Fixed Quickstart snapshot](https://github.com/Pedro-Pathing/Quickstart/tree/b4312385b7d0cc5e8dd263ec3927c9ef0cb48f36)

官方文档说明 Pedro API/流程，固定 fixture 说明本仓库编译范围；两者都不能替代当前机器人部署与实测记录。
