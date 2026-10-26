# 在 FTC Android Studio 工程中安装 FTCLib

> 来源复核：2026-10-23。本文使用 FTCLib v2.1.1 固定 commit `98e921bf9eac506c2bbe80a0cddf51ae33f1cbb6` 的 readme.md 所列 `core:2.1.1` 与可选 `vision:2.1.0`。FIRST v11.2 对照保留自 2026-08-13 的历史核验，不代表当前 SDK 兼容性或项目维护状态已确认。本次旧 docs.ftclib.org 安装／命令页不可可靠核验，改用固定第一方源码；不以跳转后的页面作为证据。

## 适用范围与目标

FTCLib 是面向 FTC 的 Java 辅助库，包含 command-based 框架、控制器、硬件封装、驱动底盘与可选视觉模块。本文采用 **core-first（先核心、后视觉）**：先证明 core 能同步、编译和部署，再决定是否承担 vision 的 ABI 与本地库配置。

**如果你只参考 FTCLib 的 Command-Based 思路、运行时是队伍自有实现，不需要按本文安装依赖。** 选择 `command-based` 并阅读[Command-Based／类 FTCLib／自有实现指南](../architecture/command-based.md)。明确使用 FTCLib command API 时选 `ftclib-command`；仅使用其他工具类也不能据此自动推断架构。

本文假设你已经完成[Android Studio 与 FTC SDK 配置](android-studio-ftc-sdk.md)，并能成功运行：

```bash
./gradlew :TeamCode:assembleDebug
```

## 安装前对照

不要把 FTCLib 页面里的每一段历史设置直接覆盖到 FIRST 工程。逐项比较：

| 固定 FTCLib readme 的历史要求 | v11.2 tag 历史核验状态 | 操作 |
|---|---|---|
| `mavenCentral()` | 根 `build.gradle` 已有 | 不重复添加 |
| `minSdkVersion 24` | `build.common.gradle` 已是 24 | 不改 |
| DEX 超限时考虑 `multiDexEnabled true` | v11.2 tag 未设置 | 先装 core 并构建；仅在方法数/DEX 错误或当前组合确实需要时添加到 TeamCode 的 `android.defaultConfig` |
| `JavaVersion.VERSION_1_8` | `build.common.gradle` 已是 Java 8 | 不改；更不能把 Gradle JDK 改成 8 |
| `core:2.1.1` | 固定 readme 列出的 core 版本 | 精确加入 `TeamCode/build.gradle` |
| `vision:2.1.0` | 固定 readme 列出的可选版本 | 只在确实使用 FTCLib vision 时考虑；额外前置条件仍需单独核验 |

版本选择原则：只安装需要的模块并固定精确版本。明确使用 `ftclib-command` 时按实际 resolve 结果应用架构规则；不要把安装 core 等同于必须改用 CommandOpMode。旧 `shared.ftclib-command-candidate` 实际为 approved，但仅适用于 2025-2026／ftclib-command，不覆盖自有运行时或全部未来赛季。

## 安装 core

### 1. 只编辑 TeamCode/build.gradle

找到原来的：

```groovy
dependencies {
    implementation project(':FtcRobotController')
}
```

改为：

```groovy
dependencies {
    implementation project(':FtcRobotController')
    implementation 'org.ftclib.ftclib:core:2.1.1'
}
```

不要为了 core 重复添加 `mavenCentral()`，也不要编辑 FIRST 明确要求尽量保持不动的 `build.common.gradle`。

### 2. Sync、查看依赖、编译

先在 Android Studio 执行 Gradle Sync，再运行：

```bash
./gradlew :TeamCode:dependencies --configuration debugRuntimeClasspath
./gradlew :TeamCode:assembleDebug
```

第一条用于确认 `org.ftclib.ftclib:core:2.1.1` 被解析到；第二条证明它能进入 TeamCode debug 构建。不要只看到 Sync 的绿色勾就结束验证。

### 3. 添加最小 core OpMode

以下示例专用于 FTCLib command 运行时。创建 `TeamCode/src/main/java/org/firstinspires/ftc/teamcode/tests/FtclibSmokeTest.java`：

```java
package org.firstinspires.ftc.teamcode.tests;

import com.arcrobotics.ftclib.command.CommandOpMode;
import com.qualcomm.robotcore.eventloop.opmode.TeleOp;

@TeleOp(name="FTCLib Smoke Test",group="Setup")
public class FtclibSmokeTest extends CommandOpMode {
    @Override
    public void initialize() {
        telemetry.addLine("FTCLib core loaded");
        telemetry.update();
    }
}
```

固定版本 `CommandOpMode.runOpMode` 调用 `initialize()`、等待 START、运行调度循环并在正常退出后 reset。`CommandScheduler.reset` 只清 singleton 引用，不等同于 cancel 或调用 end；该 runOpMode 也没有 finally 异常清理保证。这个无硬件动作的例子只验证类加载和 OpMode 注册，不代表 command、subsystem、STOP 清理或硬件逻辑已经设计正确，后续集成见[生命周期与清理指南](../architecture/command-based.md)。

### 4. 部署与验收

1. 保持机器人机构无动力或安全架空；
2. 安装最新 debug RC App 到 Control Hub；
3. 在 DS 找到 `FTCLib Smoke Test`；
4. INIT 后看到 `FTCLib core loaded`；
5. STOP，确认 RC 没有崩溃或 `NoClassDefFoundError`。

只有“解析依赖、编译、安装、INIT 运行”全部通过，才算 core 安装完成。

## 可选 vision：当前兼容性状态为 blocked

如果只是使用 command-based、PID、MotorEx 或 drivebase，不要安装 vision。固定版本 readme 列出下面的可选 artifact；这不是当前环境兼容性结论：

```groovy
dependencies {
    implementation project(':FtcRobotController')
    implementation 'org.ftclib.ftclib:core:2.1.1'
    implementation 'org.ftclib.ftclib:vision:2.1.0'
}
```

2026-08-13 的历史网页核验曾记录 vision 的 ABI 调整及 `libOpenCvAndroid453.so` 步骤。本次固定 readme 没有提供这些额外步骤，旧安装网页也未能可靠复核，因此这些历史操作不构成当前可执行证据。

本次没有证明这套历史步骤与 FIRST v11.2、Control Hub 及队伍的其他 native dependencies 兼容，因此本文**不把它写成可直接执行的 v11.2 修改方案**。不要在主比赛工程中照抄 ABI 或 `.so` 操作。确需 vision 时，应在隔离分支中从官方页面开始，明确记录要修改的 Gradle 文件/块、设备架构和 `.so` 校验值，完成 `assembleDebug`、APK 安装与官方 vision 示例实机验证；在这些证据齐全前保持 blocked。若页面链接、文件名或版本发生变化，停止核验，绝不从非官方来源寻找同名 `.so`。

## 什么时候考虑 multidex

固定版本 readme 将 `multiDexEnabled true` 作为依赖引起 DEX 超限时的处理，历史 v11.2 对照没有默认启用。不要把它误当作“Gradle Sync 必须项”。如果构建明确出现 DEX 方法数或 multidex 相关错误，再在 `TeamCode/build.gradle` 的 `android` 块中加入：

```groovy
android {
    defaultConfig {
        multiDexEnabled true
    }
}
```

修改后保存错误原文并重新构建，以证明该改动确实解决了对应问题。若没有相关错误，保持最小变更。

## 常见问题

| 现象 | 按顺序检查 |
|---|---|
| `Could not find org.ftclib...` | 是否拼成 `org.ftclib.ftclib` → `mavenCentral()` 是否存在 → 是否离线/网络受限 → 精确版本是否对应固定第一方来源 |
| duplicate classes 或 FTC artifact 冲突 | `:TeamCode:dependencies` 中是谁带入重复 FTC SDK → 是否同时装了不兼容库 → 不要用随意 `exclude` 隐藏冲突 |
| `NoClassDefFoundError` | 是否部署了最新 APK → 依赖是否在 `debugRuntimeClasspath` → RC 日志中的完整类名 → 是否只 Sync 没重新安装 |
| core 与 vision 版本混淆 | 该固定 readme 的 core 为 `2.1.1`、vision 为 `2.1.0`；不要强行写成相同数字 |
| `UnsatisfiedLinkError` / 找不到本地库 | 是否真的使用 vision → `.so` 文件名/来源 → `FIRST` 目录 → ABI 与 APK 架构 → 重启 RC 后看完整日志 |
| ABI mismatch / 安装失败 | `abiFilters` → 设备架构 → 是否按旧教程错误移除了需要的 ABI → 回退配置并重新核对当前官方页 |
| 加 vision 后体积或构建异常 | 先移除 vision，确认 core 基线恢复 → 单独检查 EasyOpenCV/OpenCV 依赖和 native library 步骤 |

## 安全与误用边界

- 新库先在无机构运动的 smoke test 中验证；
- 不用动态版本、`latest.release` 或 `-SNAPSHOT`；
- 不为了消除错误随意排除 FTC SDK、改 ABI 或复制未知 `.so`；
- 每次只改变一个变量，保存依赖报告和 RC 日志；
- FTCLib 封装不会自动保证电机方向、限位、PID 参数或路径安全。

## 相关规则

- `shared.ftclib-command-candidate`

该规则 status 为 approved，适用 teams=[]、seasons=[2025-2026]、profiles=[ftclib-command]。当前项目是否 active 必须调用 `ftckb resolve --json`；`test` 工作模式明确排除此架构条目。新专区四条 `shared.command-*` 已获本轮真实批准，范围为全队伍／全赛季／command-based，仅条件式 soft，详见[命令指南](../architecture/command-based.md)；旧规则范围没有随之扩大。

## 第一方来源与核验边界

- [FTCLib v2.1.1 readme／Installation](https://github.com/FTCLib/FTCLib/blob/98e921bf9eac506c2bbe80a0cddf51ae33f1cbb6/readme.md)：本次已读，支持依赖坐标与版本、历史 minSdk/Java 要求；其中旧 build.common.gradle 修改不能覆盖当前 FIRST／知识库约束。
- [FTCLib v2.1.1 CommandOpMode](https://github.com/FTCLib/FTCLib/blob/98e921bf9eac506c2bbe80a0cddf51ae33f1cbb6/core/src/main/java/com/arcrobotics/ftclib/command/CommandOpMode.java)：本次已读 `runOpMode`。
- [FTCLib v2.1.1 CommandScheduler](https://github.com/FTCLib/FTCLib/blob/98e921bf9eac506c2bbe80a0cddf51ae33f1cbb6/core/src/main/java/com/arcrobotics/ftclib/command/CommandScheduler.java)：本次已读 `reset` 等；库维护方来源不是 FIRST official。
- [FIRST v11.2 TeamCode build.gradle](https://github.com/FIRST-Tech-Challenge/FtcRobotController/blob/v11.2/TeamCode/build.gradle)
- [FIRST v11.2 build.common.gradle](https://github.com/FIRST-Tech-Challenge/FtcRobotController/blob/v11.2/build.common.gradle)
