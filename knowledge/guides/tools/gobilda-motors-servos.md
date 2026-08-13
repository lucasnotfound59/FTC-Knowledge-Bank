# goBILDA 电机与舵机：从精确 SKU 到 FTC 代码

> 核验日期：2026-08-13。本文的数字只适用于电机 SKU `5203-2402-0019` 和舵机 SKU `2000-0025-0502`，不能外推到整个 Yellow Jacket 或 2000 Series 产品族。

## 为什么必须先看标签

外观相似的 goBILDA 电机可以有不同减速比、转速、输出轴和 encoder resolution（编码器分辨率）；同系列舵机也可能有不同转数、力矩或模式。代码前先拍摄标签并把完整 SKU 写进硬件表。这是 `shared.gobilda-identify-exact-sku` 的核心要求。

制造商规格、库的默认值、队伍实测值和最终调参值要分栏保存：产品页告诉你特定条件下的名义参数，不证明你机器人上的摩擦、负载、电压降和机械端点。

## 示例电机：5203-2402-0019

产品全名：**5203 Series Yellow Jacket Planetary Gear Motor (19.2:1 Ratio, 24 mm Length 8 mm REX Shaft, 312 RPM, 3.3–5 V Encoder)**。

| 项目 | goBILDA 规格 |
|---|---:|
| 额定电压 | 12 VDC |
| 减速比 | 19.2:1 |
| 12 VDC 空载转速 | 312 RPM |
| 12 VDC 空载电流 | 0.25 A |
| 12 VDC 堵转电流 | 9.2 A |
| 12 VDC 堵转转矩 | 24.3 kg·cm（338 oz-in） |
| 编码器类型 | relative quadrature（相对式正交） |
| 编码器传感器 | magnetic Hall effect（磁性霍尔） |
| 编码器电压 | 3.3–5 VDC |
| 输出轴分辨率 | 537.7 PPR at output shaft |

“空载”是输出轴几乎没有外部负载的条件；“堵转”是轴不转的极端条件。9.2 A 和 24.3 kg·cm 不是连续工作额定值，不能拿它们设计持续保持或常态运行。这对应 `shared.gobilda-separate-stall-and-operating-values`。

## 编码器 ticks 如何变成机构运动

对这个精确 SKU，制造商给的是**减速箱输出轴**每转 537.7 pulses。先换算输出轴，再乘外部传动比：

```text
motor_output_revolutions=encoder_ticks/537.7
mechanism_revolutions=motor_output_revolutions*(driver_teeth/driven_teeth)
mechanism_degrees=mechanism_revolutions*360
```

其中 `driver_teeth` 是装在电机输出侧的主动轮齿数，`driven_teeth` 是机构侧从动轮齿数。多级齿轮、链轮或皮带要把每一级比值相乘；丝杆还要乘每转导程，轮子线位移还要乘实际周长。

仅用于检查的例子：

```text
1075.4 ticks / 537.7 PPR = 2 motor-output revolutions
mechanism_revolutions=2*(driver_teeth/driven_teeth)
```

这里故意不代入齿数，因为那是你的机器人参数。537.7 也不能用于 5203 的其他减速比 SKU。这对应 `shared.gobilda-use-output-shaft-encoder-resolution`。

## 最小电机编码器测试

先拆下链条/皮带或让输出轴能安全空转，在 Robot Configuration 中把电机命名为你自己的名称。下列 `YOUR_MOTOR_NAME` 是占位符：

```java
DcMotorEx motor=hardwareMap.get(DcMotorEx.class,"YOUR_MOTOR_NAME");
motor.setMode(DcMotor.RunMode.STOP_AND_RESET_ENCODER);
motor.setMode(DcMotor.RunMode.RUN_USING_ENCODER);

double outputRevolutions=motor.getCurrentPosition()/537.7;
telemetry.addData("Encoder ticks",motor.getCurrentPosition());
telemetry.addData("Output revolutions",outputRevolutions);
telemetry.update();
```

一个安全的验收方法是：断电后在输出轴做标记，重置 encoder，手动缓慢转动输出轴恰好 1 圈，记录绝对 ticks 是否接近 537.7，并同时记录符号。实际读数是整数，齿隙和手动对准会带来误差；队伍应先写下允许误差，而不是修改常数去迎合一次测量。

### 电机验证清单

1. 标签完整 SKU 是否是 `5203-2402-0019`；
2. 12 V 电机线和 3.3–5 V encoder 线是否接在正确端口；
3. 小功率正转时输出轴方向和 ticks 符号是否符合代码约定；
4. 一圈标记测试是否接近 537.7 ticks；
5. 外部每一级传动比是否进入换算；
6. 空载测试与装上机构后的电流、速度和温升是否分别记录；
7. 是否存在卡滞、长时间接近堵转或电池压降。

## 示例舵机：2000-0025-0502

产品全名：**2000 Series 5-Turn, Dual Mode Servo (25-2, Torque)**。

| 项目 | goBILDA 规格 |
|---|---:|
| 电压范围 | 4.8–7.4 VDC |
| 默认模式 | position feedback（位置反馈）；PWM 决定位置 |
| 可编程模式 | continuous rotation（连续旋转）；PWM 决定比例速度/方向 |
| 最大位置模式 PWM 范围 | 500–2500 µs |
| 最大连续模式 PWM 范围 | 900–2100 µs |
| PWM 频率 | 50 Hz（20 ms） |
| PWM 增大方向 | clockwise（顺时针） |
| 出厂 travel per µs | 0.90°/µs |
| 默认模式最大转动 | 5 turns（1800°） |
| 4.8/6.0/7.4 V 堵转转矩 | 17.2 / 21.6 / 25.2 kg·cm |
| 4.8/6.0/7.4 V 堵转电流 | 2.0 / 2.5 / 3.0 A |

双模式由 goBILDA 3102 Series programmer 切换，不是调用一次 `Servo.setPosition()` 就能改变。Robot Configuration 与 Java 类型必须匹配：位置模式按 `Servo` 使用；连续模式按 `CRServo` 使用。否则同一 PWM 命令会被误解成位置或速度。

这正是 `shared.gobilda-servo-mode-and-pwm-range` 要求记录 SKU、模式、PWM、电压、方向和 travel 的原因。

## 位置模式的安全 midpoint test

先**完全断开连杆**，或者机械团队已经测出不会撞限位的狭小安全范围。确认舵机仍是默认 position mode 后：

```java
Servo servo=hardwareMap.get(Servo.class,"YOUR_SERVO_NAME");
servo.setPosition(0.5);
```

FTC `Servo.setPosition(0..1)` 是归一化命令。它本身不证明 Hub/SDK 当前把 `0` 和 `1` 映射到制造商最大 500–2500 µs，也不保证机构拥有完整 1800°无碰撞行程。不要直接用 `0.0`/`1.0` 探机械端点。

安全标定流程：

1. 断开连杆，发送 `0.5`，确认无异常；
2. 每次只改变很小步长，例如队伍规定的 `0.02`；
3. 记录命令值、实际角度、方向、供电和是否出现 buzzing（持续嗡鸣）；
4. 一旦接近机械限制、温升或持续嗡鸣，立即退回并断电；
5. 把软件安全端点设在机械极限内侧，并在代码中 clip；
6. 接回连杆后以更小范围重新验证。

## 连续模式不是“无限位置模式”

continuous mode 下命令表示速度与方向，不再保持绝对位置。切换后要在 Robot Configuration 中配置为 continuous rotation servo，并使用 `CRServo.setPower(-1..1)`。先架空机构，从 `0` 和很小绝对功率开始，验证正方向和停止。切回 position mode 前也要用 programmer 明确切换并重新配置软件。

## 常见问题

| 现象 | 优先检查 |
|---|---|
| 电机一圈不是 537.7 ticks | 精确 SKU → 标记的是 gearbox output shaft 还是后级机构 → encoder 接线 → 是否使用绝对值/方向约定 |
| 机构距离换算差固定比例 | 是否漏掉外部链轮/齿轮/皮带/丝杆比 → 使用的是齿数还是直径 → 多级传动是否全乘 |
| encoder 符号相反 | 电机方向设置与 encoder 正方向约定 → 只改一处并重新做一圈测试 |
| 电机明显发热/掉压 | 机械卡滞 → 负载是否接近堵转 → 电源/连接器 → 功率与 duty cycle；立即停止长时间堵转 |
| 舵机 `0.5` 仍持续旋转 | 舵机被 programmer 设为 continuous mode → Robot Configuration 类型是否错误 |
| 舵机不保持位置 | 是否在 continuous mode → 供电压降 → 齿轮/反馈损坏 → 负载是否过大 |
| 舵机方向与预期相反 | 制造商“PWM 增大顺时针”观察基准 → 安装朝向 → 软件 direction；小步验证后只在一层反向 |
| 到端点嗡鸣 | 立即退回 → 连杆是否顶死 → 软件安全端点过宽 → 不要把堵转力矩当保持目标 |
| Servo 端点没覆盖 5 圈 | Hub 默认 PWM 映射可能没有覆盖制造商 maximum range → 不要盲目扩大 pulse；先查 SDK `PwmControl`、控制器能力和机械安全 |

## 安全与误用边界

- 电机/舵机首次测试拆下危险连杆、架空机构并准备断电；
- 任何 stall current/torque 都只标为堵转条件，绝不写成连续额定能力；
- 不用不明 BEC/Hub 电压供电，必须处于舵机 4.8–7.4 V 范围且满足控制系统规则；
- 不通过强行堵住输出来测试最大转矩；
- `setPosition`、encoder ticks 和产品页规格都不能替代机械限位与实测；
- 更换相似产品时重新读取 SKU，从规格表开始，不继承旧常数。

## 相关规则

- `shared.gobilda-identify-exact-sku`
- `shared.gobilda-use-output-shaft-encoder-resolution`
- `shared.gobilda-separate-stall-and-operating-values`
- `shared.gobilda-servo-mode-and-pwm-range`

这些规则当前都是 `candidate`。

## 官方来源

- [goBILDA 5203-2402-0019 product page](https://www.gobilda.com/5203-series-yellow-jacket-planetary-gear-motor-19-2-1-ratio-24mm-length-8mm-rex-shaft-312-rpm-3-3-5v-encoder/)
- [goBILDA 5203-2402-0019 spec sheet](https://www.gobilda.com/content/spec_sheets/5203-2402-0019_spec_sheet.pdf)
- [goBILDA 2000-0025-0502 product page](https://www.gobilda.com/2000-series-5-turn-dual-mode-servo-25-2-torque/)
