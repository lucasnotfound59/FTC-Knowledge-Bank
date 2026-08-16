# Control Hub / Expansion Hub 指示灯（Status LED）速查与诊断

> 核验日期：2026-08-15。内容来自 REV Robotics 官方文档 *Status LED Blink Codes*（Control Hub REV-31-1595、Expansion Hub REV-31-1153、Driver Hub REV-31-1956）。LED 灯含义随固件/App 版本变化，下表以 REV 文档当前版本为准；Control Hub 需运行最新 Control Hub 操作系统与 Robot Controller App 6.0+。

## 为什么先看灯

REV 官方把 Status LED 比作汽车的"check engine light"：它不会告诉你具体哪个零件坏了，但它能帮你**缩小范围**。机器人"不动了"可能是没电、通信断了、还在启动、或只是在配网——这些在灯上表现为完全不同的颜色/闪烁，对应的处理也完全不同。所以排查硬件问题**第一步永远是读灯**，再决定下一步，而不是上来就重启或换件（对应候选规则 `official.hub-led-read-before-repair`）。

## Control Hub 指示灯（Robot Controller App 6.0+）

| 灯状态 | 出现时机 | 含义 / 该做什么 |
|---|---|---|
| 常亮蓝 | 开机 | 有电、电池 >7V，正在等待初始化通信。正常，稍等 |
| 常亮蓝 | 任意时刻 | 正在等待与 Driver Station 通信；有电、电池 >7V |
| 常亮绿 | 任意时刻 | 有电，且与 Android 平台有活动通信。**这是正常工作态** |
| 蓝闪 | 任意时刻 | keep-alive 超时 = 通信断了；**通信恢复后自动清除**。查连接而不是查电/查电机（`official.hub-led-blue-keepalive-timeout`） |
| 橙闪 | 任意时刻 | **电池电压 <7V**：12V 电池要充电，或 Hub 只靠 USB 供电。电压回升到 >7V 后清除；**该状态不会被 keep-alive 超时覆盖**（`official.hub-led-orange-low-battery`） |
| 品红闪 | Wi-Fi 重置时 | 按键后已切换到 5GHz 频段 |
| 黄闪 | Wi-Fi 重置时 | 按键后已切换到 2.4GHz 频段 |

> 若 Control Hub 运行 Robot Controller App 5.5 或更低，其灯码与 Expansion Hub（固件 1.7.0+）相同。

## Expansion Hub 指示灯（固件 1.7.0+）

| 灯状态 | 出现时机 | 含义 / 该做什么 |
|---|---|---|
| 常亮蓝 | 开机 | 有电、电池 >7V，等待初始化通信 |
| 常亮蓝 | 任意时刻 | 等待与 Robot Controller 通信；有电、电池 >7V |
| 常亮绿 + 每约 5 秒 N 次蓝闪 | 任意时刻 | 有电且与 Android 平台有活动通信；**蓝闪次数 = 该 Hub 的地址**（出厂默认 2）（`official.expansion-hub-led-blue-blinks-address`） |
| 蓝闪 | 任意时刻 | keep-alive 超时 = 通信断了；恢复后清除 |
| 橙闪 | 任意时刻 | 电池电压 <7V（充电或 USB-only）；>7V 后清除；不被 keep-alive 覆盖 |

> 配置 Expansion Hub 时（例如改地址），LED 会切换为**紫-青（purple-cyan）交替闪烁**，表示该 Hub 正处于寻址/配置模式，**这不是故障**（来自 FIRST FtcRobotController README）。

## Driver Hub 指示灯

| 灯 | 状态 | 含义 |
|---|---|---|
| LED A | 白闪 | 操作系统正在启动 |
| LED B | 常亮绿 | 设备已开机 |
| 电池状态 LED | 红闪 | 充电中 |
| 电池状态 LED | 常亮红 | 已充满 |

## 快速诊断流程

1. **灯完全不亮** → 查供电/接线（XT30 是否松动、电池是否接上），或尝试固件更新后仍不亮再联系 REV Support。
2. **橙闪** → 充电/换电池，先把电压抬回 7V 以上，再判断别的故障。
3. **蓝闪** → 通信断：查 Control Hub ↔ Driver Station、Control Hub ↔ Expansion Hub 的连接与配对，不要先怀疑电机。
4. **常亮蓝迟迟不变绿** → 还在等待通信，查 Driver Station 是否已连接、是否在 2.4/5GHz 正确频段。
5. **常亮绿 + 蓝闪次数不对** → Expansion Hub 地址不是你以为的那个，去核对地址配置。
6. **紫-青闪烁** → 正在配置模式，别打断。

> 以上每一条都把"症状"和"处理"分开，目的是**避免把低电量/通信断误判成硬件损坏**。灯是缩小范围的第一步，不是最终结论；REV 官方也强调要继续按 troubleshooting 流程隔离问题，必要时带上日志联系 REV Support。
