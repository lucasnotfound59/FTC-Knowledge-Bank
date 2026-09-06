# RookieBot 新手代码约定：硬件、Auto、注释与舵机角度

记录日期：2026-09-06。来源是用户在 RookieBot 项目中逐项确认并已发布的实践，不是 FIRST 官方标准，也不是所有 FTC 项目必须采用的架构。

## 状态、来源与适用范围

- 已确认的项目实践：集中 Hardwares、公开分组字段、简洁 OpMode、逐步中文注释、0~360 度舵机变量。
- 本知识库的机器规则：[rookiebot-tutorial.yaml](../../shared/practices/rookiebot-tutorial.yaml)，12 条均为 `candidate`。项目采用不等于跨队正式审批；未填写或推断审批人角色，`resolve` 暂不将这些新条目列为 active。
- 参考实现：[RookieBot 5581415](https://github.com/OLeslieO/FTC2026-RookieBot/tree/558141588e2a0eb766e195ab71df3c188e942891)，FTC SDK 11.2.1、Pedro 2.1.2；这是来源项目版本，不改动本知识库已有编译 fixture 的版本。
- 结构参考：[FTC16093 Premier 888b0c7](https://github.com/lucasnotfound59/FTC16093-2026DECODE-Premier/tree/888b0c7894c8badfc6a7bdb4fa558db67446eaed/TeamCode/src/main/java/org/firstinspires/ftc/teamcode)。学习硬件分组和 Pedro 调用，不沿用 `XKCommandOpmode` 命名或复制参考车参数。
- 已标记的旧版：[flaw 对应 ee45dd0](https://github.com/OLeslieO/FTC2026-RookieBot/commit/ee45dd043aa542e50f7f27f60fae3af0d18e62e3)。它是此前已发布的有缺陷版本，不能继续推荐其无条件抛错的 Follower 工厂。标签只标记该提交，未重写历史。

本约定面向采用 RookieBot 风格的新手教程。当前 `shared.ftclib-command-candidate` 虽然名称带 candidate，状态已是 approved，要求适用项目使用 subsystem + CommandOpMode；20827 也有已批准的硬件分组规范。本文的简洁写法只作为 RookieBot 新手教程候选约定保存，不覆盖正式规则。实际项目先按队号和赛季调用 `ftckb resolve --json`；若需改变已批准架构，须另走审批流程。已有 [Pedro 安全教程](../tools/pedro-pathing.md) 是独立 API/安全教学资料；本文是应用项目的代码组织约定，不替换其唯一编译 fixture。

## 1. 所有硬件声明和初始化集中在 Hardwares

一个设备只维护一套名称、字段与初始化规则：

```java
public final class Hardwares {
    public final Motors motors;
    public final Servos servos;
    public final Sensors sensors;
    // 构造方法创建以上分组；具体实现见固定提交的 Hardwares.java。
}
```

`Motors` 内声明电机，`Servos` 内声明舵机，`Sensors` 内声明 Pinpoint、IMU、距离传感器等。所有设备查找、初始方向和硬件配置都放在对应分组或 Hardwares 的硬件构造方法中。Auto/TeleOp 只持有一个 Hardwares 引用，不再分散初始化设备。

Pedro 自身会查找和配置设备，所以构造入口也在 `Hardwares.createFollower()`，`Constants` 仅存质量、速度、PID 等调参数据。不要在 Auto 中再次初始化或重置同一个定位器。

## 2. 使用公开分组字段，新增设备只改清单

```java
Hardwares hardwares=new Hardwares(hardwareMap);
hardwares.motors.leftFront.setPower(power);
hardwares.servos.servo1.setPosition(Hardwares.servoAngleToPosition(angleDegrees));
```

上面的轮子 `setPower` 只用于没有交给 Follower 控制的手动底盘场景。Pedro 运行时由 Follower 统一输出底盘功率并更新 Pinpoint；不要同时手动给同一组轮子写功率，也不要重复调用 Pinpoint 的更新或重置，否则会干扰路径控制。公开硬件字段不代表可以让两套控制逻辑同时接管设备。

新增 `motor1` 时，在 `Hardwares.Motors` 中加字段，并在它的构造方法内加查找与初始化：

```java
public final DcMotorEx motor1;
// 以下两行放在 Motors 构造方法内；配置字符串按当前机器人填写。
motor1=hardwareMap.get(DcMotorEx.class,"motor1");
motor1.setDirection(DcMotorSimple.Direction.FORWARD);
```

以后直接使用 `hardwares.motors.motor1`。不要仅为基础教程额外套多层 getter、机制对象或命令封装。Java 字段名与 Robot Configuration 字符串可以不同，但要分别维护；重命名字段时同步修改声明、赋值、调用和注释。

RookieBot 的 `new Hardwares(hardwareMap,false)` 只初始化电机，此时 `servos/sensors` 为 null，不能访问。完整初始化则要求所有已声明设备都存在，包括 `servo2`；没有该设备时同时移除声明与初始化，不能把缺失配置悄悄当作成功。

## 3. 普通 OpMode 和少量步骤足够

- Auto 使用标准 `OpMode` 的 `init/start/loop/stop`。
- 纯底盘示例可使用 `LinearOpMode` 的 `waitForStart` 与 `opModeIsActive` 循环；不要混用两种生命周期。
- 用 `switch(step)` 表达“关舵机 → 到动作点 → 开舵机 → 等待 → 停车”。
- 命名用 `Hardwares`、`MecanumDrive`、`ServoPedroAutoExample` 等通用可读名称。
- 不沿用 `XKCommandOpmode` 等参考项目特有命名；复杂框架应由实际需求决定，不为教程增加层级。

## 4. 每一步注释都说明用途和改法

有意义的步骤应说明四件事：做什么、何时执行、参数是什么单位、想修改时改哪里。

| 新手想做什么 | 注释应指向哪里 |
| --- | --- |
| 加电机、舵机、传感器 | Hardwares 对应分组中的字段与初始化 |
| 改设备名称或转向 | 配置字符串或统一的 setDirection |
| 改舵机开合 | 带 ANGLE_DEGREES 的变量，单位度 |
| 改等待时间 | SERVO_WAIT_SECONDS，进入等待时才 reset |
| 改路径或朝向 | Pose、buildPaths 和对应 step；inch 与 rad 分开 |
| 改动作顺序 | switch 分支及下一步编号 |
| 改手柄与速度 | TeleOp 输入字段或所有轮子的统一功率系数 |

不要只注释“调用 setPosition”；应说明它只是发送目标，不会等待舵机转完。不要把 `NaN`、`null` 或 `REPLACE_` 写成可直接使用的默认参数。示范中的 `motor1`、`servo3` 等还需实际声明和初始化。

## 5. 舵机变量使用 0~360 度，统一换算

这是一项可读性约定，位置舵机的业务代码不直接维护难读的 0~1 数字：

```java
public static double servoAngleToPosition(double angleDegrees) {
    if(!Double.isFinite(angleDegrees)||angleDegrees<0.0||angleDegrees>360.0) {
        throw new IllegalArgumentException("舵机角度必须是 0~360 度内的有限数值");
    }
    return angleDegrees/360.0;
}
```

该方法放在 Hardwares。调用时：

```java
double angleDegrees=180; // 仅演示数学换算，实际目标按机构标定填写
hardwares.servos.servo1.setPosition(Hardwares.servoAngleToPosition(angleDegrees));
```

映射：0→0、90→0.25、180→0.5、270→0.75、360→1。360 是满行程，不是取模后回到 0；支持小数角度，拒绝越界、NaN 和无穷大。变量建议用 `CLOSED_ANGLE_DEGREES`、`OPEN_ANGLE_DEGREES`，不能把 180 直接传给 SDK `setPosition`。

**软件满行程映射不等于实际机械行程。** 180 度、270 度、多圈舵机及自定义 PWM 范围必须按型号和标定解释；该函数不会增加硬件转角。连续旋转 `CRServo` 接收速度/方向，不使用这套位置换算。遵循已有 [舵机型号与模式规则](../tools/gobilda-motors-servos.md)，此约定不替代机械限位或标定。

## 6. 占位模板和自己的可运行 Auto 区分清楚

模板保留 `@Disabled`、配置锁和未填写参数。注释必须明确：

> 只有示例文件使用 @Disabled。编写队伍自己要运行的 Auto 时不能添加或保留 @Disabled，否则该 OpMode 不会出现在 Driver Station 中。

移除注解之前，先填写硬件名称、方向、PID、定位参数、路线和机构参数，完成相应验证后开启配置锁。用户要求的是安全占位教程，不是填一套其他机器人的值直接部署。

## 7. Pedro 使用匹配版本的完整构造链

2.1.2 需要实际接入 drivetrain 和 localizer。下面只示意位于 Hardwares 内的核心调用：

```java
return new FollowerBuilder(Constants.followerConstants(),hardwareMap)
        .pathConstraints(PathConstraints.defaultConstraints.copy())
        .mecanumDrivetrain(driveConstants)
        .pinpointLocalizer(localizerConstants)
        .build();
```

空 builder 的 `build()` 和永远抛错的工厂都不是完整的可配置实现。保留未配置时的条件拒绝，但条件后面必须有完整构造代码。更换 localizer 时同时改设备声明、查找、配置与 builder 调用。先验证定位再调 follower，不复制来源机器人的名字、PID、方向、offset 或路径值。

## 8. 非阻塞 Auto 保持持续更新

`init` 里创建硬件和路径，`start` 发起首段，`loop` 每轮执行 `follower.update()`。只在进入步骤时 `followPath`，通过 `isBusy` 与计时器决定何时跳转。

进入舵机等待步骤时才 `timer.reset()`，不要每帧清零，也不要 `sleep` 或忙等。路径结束可能涉及超时条件，`isBusy=false` 不证明实物零误差到点。STOP/异常要取消跟随，异常后不能在保护范围外再读取故障 follower 的 telemetry。

## 9. 共享 SDK 配置与 Git 卫生

`sdk.dir` 是机器专属路径。`local.properties`、`.gradle/`、`**/build/`、`.idea/`、`*.iml` 和临时 worktree 不进入共享提交；保留必要的 wrapper 和公共构建配置。新电脑使用自己的 Android SDK 路径、JDK 和依赖准备，不能承诺任何环境无需配置就能 build。

见 [FTC SDK 配置教程](../setup/android-studio-ftc-sdk.md)。路径示范用占位符，不保存个人用户目录。

## 10. AS 红线先查具体 import

JUnit 测试使用 `org.junit.Assert`。不要再导入 FTC 内部 Assert 的同名断言；不要把项目 Constants 自动补全成 `com.sun.tools.javac.util.Constants`。

重命名后先核对字段、import 和首条错误，再检查同步与 Gradle 编译。生产源集和测试源集都要检查，不把重复点击自动导入当作修复。

## 11. 验证结论对应交付版本

RookieBot 的软件检查命令是：

```bash
./gradlew :TeamCode:testDebugUnitTest :TeamCode:assembleDebug
```

在本次来源项目会话中，提交 `5581415` 对应内容通过 5 个测试（包含角度边界、分数角和非法输入）及 Debug 构建。这个结果来自当时 RookieBot 工作区，不是本知识库的测试数量，也不是本次重新在机器人上运行的结果。

后续报告分别写清：检查了哪个版本、命令、编译/测试结果、是否部署、是否真实运行。AS 索引、构建、单元测试与真机验证不是同一种证据。硬件未测试就明确未测试，不使用旧测试数包装当前版本。

## 12. 版本标记与来源边界

`flaw` 指向 `ee45dd043aa542e50f7f27f60fae3af0d18e62e3`；集中 Hardwares 和教程注释版本为 `624334fff4e119bc06b880ece726ac3dac18dd3b`；角度换算版本为 `558141588e2a0eb766e195ab71df3c188e942891`。检索推荐使用固定的新提交，不继续复制标记缺陷的旧工厂。

此次“push”和“标记 flaw”是用户对具体版本的操作授权，不是以后任意变更都可自动推送、覆盖标签或重写历史的长期授权。

## 规则索引

- `shared.rookiebot-hardware-init`：集中声明和初始化所有硬件
- `shared.rookiebot-hardware-groups`：按 motors、servos、sensors 直接访问设备
- `shared.rookiebot-simple-opmode`：使用简单生命周期和通用命名
- `shared.rookiebot-step-comments`：逐步解释用途、单位和修改方法
- `shared.rookiebot-servo-degrees`：舵机业务变量用度数并统一换算
- `shared.rookiebot-template-activation`：区分安全占位模板和要运行的队伍 Auto
- `shared.rookiebot-pedro-complete-builder`：Pedro 构造必须接入底盘和定位器
- `shared.rookiebot-nonblocking-auto`：用非阻塞步骤组合舵机与路径
- `shared.rookiebot-sdk-path-hygiene`：共享项目不提交个人 SDK 路径与构建产物
- `shared.rookiebot-java-imports`：使用正确的项目类与测试断言导入
- `shared.rookiebot-verification-evidence`：分别报告编译、测试和真机验证
- `shared.rookiebot-source-provenance`：保留参考来源并排除已标记缺陷版本

## 与已有知识的关系

- 延续 `shared.pedro-tune-current-robot`、`shared.pedro-localization-before-follower` 和 `shared.pedro-explicit-coordinate-conversion`，不另改其审批状态。
- 角度显示不覆盖 `shared.gobilda-servo-mode-and-pwm-range` 的型号、模式与 PWM 限制。
- 不把 `team-20827.hardware-layer-candidate` 的队号直接改成 RookieBot；没有给新规则虚构队号。
- 新增 YAML 使用项目限定的 instruction 和独立 topic，避免将本教程风格误判为 FIRST 约束或自动替代其他团队规则。
