# 在 FTC Android Studio 工程中安装 FTCLib

> 核验快照：2026-08-13。本文使用 FTCLib 官方安装页当前列出的 `core:2.1.1` 与可选 `vision:2.1.0`，并以 FIRST v11.2 工程为对照。版本来自 FTCLib 页面，不等于对未来 FTC SDK 的永久兼容保证。

## 适用范围与目标

FTCLib 是面向 FTC 的 Java 辅助库，包含 command-based 框架、控制器、硬件封装、驱动底盘与可选视觉模块。本文采用 **core-first（先核心、后视觉）**：先证明 core 能同步、编译和部署，再决定是否承担 vision 的 ABI 与本地库配置。

本文假设你已经完成[Android Studio 与 FTC SDK 配置](android-studio-ftc-sdk.md)，并能成功运行：

```bash
./gradlew :TeamCode:assembleDebug
```

## 安装前对照

不要把 FTCLib 页面里的每一段历史设置直接覆盖到 FIRST 工程。逐项比较：

| FTCLib 文档要求 | v11.2 tag 当前状态 | 操作 |
|---|---|---|
| `mavenCentral()` | 根 `build.gradle` 与 `build.dependencies.gradle` 已有 | 不重复添加 |
| `minSdkVersion 24` | `build.common.gradle` 已是 24 | 不改 |
| `multiDexEnabled true` | v11.2 tag 未设置 | 先装 core 并构建；仅在方法数/DEX 错误或当前组合确实需要时添加到 TeamCode 的 `android.defaultConfig` |
| `JavaVersion.VERSION_1_8` | `build.common.gradle` 已是 Java 8 | 不改；更不能把 Gradle JDK 改成 8 |
| `core:2.1.1` | FTCLib 页面列出的 core 版本 | 精确加入 `TeamCode/build.gradle` |
| `vision:2.1.0` | FTCLib 页面列出的可选版本 | 只在确实使用 FTCLib vision 时加入，并完成其官方前置条件 |

这体现两条原则：`shared.ftclib-check-current-prerequisites` 要求先比较；`shared.ftclib-pin-module-versions` 要求只安装需要的模块并固定精确版本。

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

创建 `TeamCode/src/main/java/org/firstinspires/ftc/teamcode/FtclibSmokeTest.java`：

```java
package org.firstinspires.ftc.teamcode;

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

FTCLib 官方 CommandOpMode 文档说明：子类只需实现 `initialize()`，调度循环和停止后的 scheduler reset 由 `CommandOpMode` 处理。这个例子只验证类加载和 OpMode 注册，不代表 command、subsystem 或硬件逻辑已经设计正确。

### 4. 部署与验收

1. 保持机器人机构无动力或安全架空；
2. 安装最新 debug RC App 到 Control Hub；
3. 在 DS 找到 `FTCLib Smoke Test`；
4. INIT 后看到 `FTCLib core loaded`；
5. STOP，确认 RC 没有崩溃或 `NoClassDefFoundError`。

只有“解析依赖、编译、安装、INIT 运行”全部通过，才算 core 安装完成。

## 可选：安装 vision

如果只是使用 command-based、PID、MotorEx 或 drivebase，不要安装 vision。需要 FTCLib vision 时，将依赖改为：

```groovy
dependencies {
    implementation project(':FtcRobotController')
    implementation 'org.ftclib.ftclib:core:2.1.1'
    implementation 'org.ftclib.ftclib:vision:2.1.0'
}
```

截至核验日，FTCLib 官方安装页仍明确要求 vision 使用者：

1. 将 FTC 工程中的 `arm64-v8a` ABI 移除，只保留 `armeabi-v7a`；
2. 下载其链接的 `libOpenCvAndroid453.so`；
3. 通过 USB/MTP 把该文件复制到 Robot Controller 存储的 `FIRST` 文件夹。

这些步骤会改变可安装架构并向设备写入本地库，不是 core 的通用要求。执行前要核对 FTCLib 页面、所用 Control Hub/RC 架构和文件来源，备份原配置；若页面链接、文件名或版本发生变化，停止并重新核验，不要从非官方网盘寻找同名 `.so`。完成后重新运行依赖报告、`assembleDebug`、部署和一个官方 vision 示例。

## 什么时候考虑 multidex

FTCLib 页面仍列出 `multiDexEnabled true`，但 v11.2 没有默认启用。不要把它误当作“Gradle Sync 必须项”。如果构建明确出现 DEX 方法数或 multidex 相关错误，再在 `TeamCode/build.gradle` 的 `android` 块中加入：

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
| `Could not find org.ftclib...` | 是否拼成 `org.ftclib.ftclib` → `mavenCentral()` 是否存在 → 是否离线/网络受限 → 精确版本是否为页面当前值 |
| duplicate classes 或 FTC artifact 冲突 | `:TeamCode:dependencies` 中是谁带入重复 FTC SDK → 是否同时装了不兼容库 → 不要用随意 `exclude` 隐藏冲突 |
| `NoClassDefFoundError` | 是否部署了最新 APK → 依赖是否在 `debugRuntimeClasspath` → RC 日志中的完整类名 → 是否只 Sync 没重新安装 |
| core 与 vision 版本混淆 | core 固定 `2.1.1`、vision 固定 `2.1.0`；它们在官方页就是不同版本，不要强行写成相同数字 |
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

- `shared.ftclib-check-current-prerequisites`
- `shared.ftclib-pin-module-versions`

这些规则当前都是 `candidate`，尚未自动生效。

## 官方来源

- [FTCLib Installation](https://docs.ftclib.org/ftclib/installation)
- [FTCLib Robot and CommandOpMode](https://docs.ftclib.org/ftclib/command-base/command-system/robot-and-commandopmode)
- [FTCLib Releases](https://github.com/FTCLib/FTCLib/releases)
- [FIRST v11.2 TeamCode build.gradle](https://github.com/FIRST-Tech-Challenge/FtcRobotController/blob/v11.2/TeamCode/build.gradle)
- [FIRST v11.2 build.common.gradle](https://github.com/FIRST-Tech-Challenge/FtcRobotController/blob/v11.2/build.common.gradle)
