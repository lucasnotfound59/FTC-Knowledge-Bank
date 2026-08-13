# 在 Android Studio 中配置 FTC SDK

> 核验快照：2026-08-13。本文面向 Android Robot Controller（RC）与 Control Hub，示例基线为 FIRST `FtcRobotController` v11.2。它不适用于 Blocks、OnBot Java 或未来的 Systemcore。

## 适用范围与目标

完成本文后，你应该能在 Android Studio 中打开固定版本的 FTC 工程、完成 Gradle Sync、编译 `TeamCode`、把 RC App 安装到 Control Hub，并在 Driver Station（DS）上看到一个最小 OpMode。

这里有五种容易混淆的“版本”：

- **FTC SDK/RC App 版本**决定 FTC API 与 RC/DS 协议；
- **Android Studio 版本**是 IDE 版本；
- **Gradle Wrapper**运行构建，版本写在 `gradle/wrapper/gradle-wrapper.properties`；
- **Android Gradle Plugin（AGP）**把 Gradle 与 Android 构建系统连接起来，版本写在根目录 `build.gradle`；
- **Gradle JDK**负责运行 Gradle，和 `sourceCompatibility` 所规定的 TeamCode Java 语法级别不是同一件事。

## 先记录基线，不要先升级

本仓库在 2026-08-13 对官方 v11.2 tag（commit `4ed7c4666aec265a6fd9e674ca40462e9dfe4bf8`）进行了逐文件核验：

| 名称 | v11.2 tag 中的值 | 在哪里查看 | 用途 |
|---|---:|---|---|
| FTC 依赖 | `11.2.0` | `build.dependencies.gradle` | RC、Hardware、Vision 等 SDK artifact |
| Android Studio 下限 | Narwhal 3 Feature Drop | v11.2 release notes | 打开与同步工程 |
| Gradle JDK | 使用与项目 AGP 兼容的 Android Studio Embedded JDK | Settings → Build Tools → Gradle | 运行 Gradle |
| Java source/target | Java 8 | `build.common.gradle` | 编译 TeamCode Java；不等于 Gradle JDK 8 |
| compile/min/target SDK | 30 / 24 / 28 | `build.common.gradle` | Android 编译与设备兼容 |
| NDK | `21.3.6528147` | `build.common.gradle` | 本地库工具链 |

### v11.2 上游不一致

同一个 v11.2 tag 的 README “Breaking Changes” 声称 Gradle 已升级到 9.1、AGP 已升级到 8.13.2；但该 tag 的实际文件在核验日分别是 Gradle 8.9 和 AGP 8.7.0。Android 官方兼容表也将 AGP 8.13 的最低 Gradle 列为 8.13，而不是 9.1。

因此本文不把 README 中的两个数字当作手工修改指令。正确做法是：下载完整 v11.2 release/tag，保留其中的 wrapper 和 build 文件原样，记录上述矛盾；如果 FIRST 后续修正 tag 或发布新版本，重新核验全部文件后再迁移。不要自行拼出“Gradle 9.1 + AGP 8.13.2”的组合。

## 前置条件

- 一台可以安装 Android Studio 的 Windows、macOS 或 Linux 电脑；
- Control Hub、Driver Station 和数据线；
- 首次 Gradle Sync 时可访问 Google Maven、Maven Central 和 Gradle 分发站；
- 已备份现有 TeamCode 与机器人配置；
- RC 与 DS 最终使用兼容版本，更新前先记录二者 About 页面版本。

## 配置步骤

### 1. 安装 Android Studio

安装 **Narwhal 3 Feature Drop 或更高版本**。启动后先不要接受“Upgrade Android Gradle Plugin”或“Downgrade AGP”之类的项目迁移建议。IDE 更新与工程构建工具更新是两件事。

### 2. 固定下载 v11.2

推荐直接从 v11.2 release 页面下载 Source code archive。使用 Git 时执行：

```bash
git clone --branch v11.2 --depth 1 https://github.com/FIRST-Tech-Challenge/FtcRobotController.git
cd FtcRobotController
git rev-parse HEAD
```

最后一条在本快照应输出以 `4ed7c466` 开头的 SHA。不要把 `master` 当作可复现赛季基线。

### 3. 打开正确目录

在 Android Studio 选择 **Open**，打开包含 `settings.gradle`、`gradlew`、`TeamCode/` 和 `FtcRobotController/` 的工程根目录，而不是只打开 `TeamCode/`。确认来源可信后允许 Gradle 脚本执行。

### 4. 选择 Gradle JDK

进入 Settings/Preferences → Build, Execution, Deployment → Build Tools → Gradle，把 **Gradle JDK** 设为 Android Studio 的 Embedded JDK。终端中用下面命令确认 Gradle 实际使用的 JVM：

```bash
./gradlew --version
```

不要因为 `sourceCompatibility JavaVersion.VERSION_1_8` 就把 Gradle JDK 改成 Java 8。前者限制 TeamCode 字节码/语法兼容，后者负责启动现代 AGP。

### 5. 安装 tag 要求的 Android 组件

在 Tools → SDK Manager 中按 `build.common.gradle` 安装缺失的 Android SDK Platform 30 与 NDK `21.3.6528147`。只安装实际报缺失的组件；不要为了“更新”而改动 `compileSdkVersion`、`targetSdkVersion` 或 `ndkVersion`。

### 6. 完成在线 Gradle Sync

点击 **Sync Project with Gradle Files**。若 IDE提出 AGP/Gradle 升降级，取消并保留 release 自带文件。第一次同步需要联网下载依赖；成功标准是 Build 窗口没有 unresolved dependency 或 SDK/NDK 错误。

### 7. 先在命令行构建 TeamCode

macOS/Linux：

```bash
./gradlew :TeamCode:assembleDebug
```

Windows PowerShell：

```powershell
.\gradlew.bat :TeamCode:assembleDebug
```

构建成功只证明本地代码与 Android 工具链能产出 APK，不证明 USB、RC/DS、硬件配置或运行时已经正确。

### 8. 添加最小 OpMode

创建 `TeamCode/src/main/java/org/firstinspires/ftc/teamcode/SetupSmokeTest.java`：

```java
package org.firstinspires.ftc.teamcode;

import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode;
import com.qualcomm.robotcore.eventloop.opmode.TeleOp;

@TeleOp(name="Setup Smoke Test",group="Setup")
public class SetupSmokeTest extends LinearOpMode {
    @Override
    public void runOpMode() {
        telemetry.addLine("FTC SDK build and deployment succeeded");
        telemetry.update();
        waitForStart();
        while(opModeIsActive()) {
            telemetry.addData("Runtime (s)",getRuntime());
            telemetry.update();
            idle();
        }
    }
}
```

### 9. 部署到 Control Hub

给机器人断开动力机构或架空轮子，只让 Control Hub 通电。用可靠的数据线连接电脑，在 Android Studio 设备列表选择 Control Hub，然后 Run `FtcRobotController`。允许 USB 调试并等待安装完成。

如果使用 ADB，可先只读检查：

```bash
adb devices
```

### 10. 做端到端验收

1. 确认 RC App 与 DS App 已连接且版本兼容；
2. 在 DS 的 TeleOp 列表找到 `Setup Smoke Test`；
3. INIT 后看到构建成功文字；
4. START 后看到 Runtime 持续变化；
5. STOP 后程序正常退出。

这五步全部通过，才说明“下载 → Sync → 编译 → 安装 → OpMode 注册 → 运行”闭环成立。

## 常见问题

| 现象 | 按顺序检查 |
|---|---|
| `Unsupported class file major version` / JDK 错误 | `./gradlew --version` 的 JVM → Gradle JDK 是否为 Embedded JDK → 是否误把 Java source level 当成 Gradle JDK |
| 缺少 Android SDK Platform 或 NDK | `build.common.gradle` 中的精确值 → SDK Manager 已安装组件 → `local.properties` 的 SDK 路径 |
| 依赖无法解析 | 网络/DNS → 是否能访问 Google Maven/Maven Central → Gradle Offline mode 是否关闭 → 代理设置 |
| Sync 长时间不结束 | Build 窗口具体下载项 → 网络 → 停止并重试一次 → 用 `./gradlew :TeamCode:assembleDebug --stacktrace` 获取真实错误 |
| IDE 要升级/降级 AGP | 取消提示 → 对照 release tag 的根 `build.gradle` 和 wrapper → 不手改兼容组合 |
| Android Studio 看不到 Control Hub | 机器人供电 → 数据线是否支持数据 → USB 调试授权 → `adb devices` 状态是否为 `device` |
| APK 安装失败 | 设备空间 → 旧 App 签名冲突 → RC App 版本 → Android Studio Run 输出；卸载会丢失本地 RC 数据，先备份再决定 |
| RC 与 DS 连接但版本警告 | 两边 About 版本 → 更新为同一发布系列 → 重启 RC/DS 后重新配对 |
| OpMode 不出现 | 文件 package 是否是 `org.firstinspires.ftc.teamcode` → 类是否 `public` → `@TeleOp` 是否存在且未 `@Disabled` → 最新 APK 是否真的安装 |

## 安全与误用边界

- 首次安装与每次依赖变化都在动力机构脱开、机器人固定时验证；
- 不在比赛前临时升级 Android Studio、AGP、Gradle、SDK 或库；
- 不删除签名、配置或 RC App 数据来“试试看”，除非已备份并理解后果；
- RC/DS 更新属于控制系统变更，必须留出回滚和场地测试时间；
- 本教程不会替你验证具体赛季的竞赛合法版本，赛前还要查当季 Competition Manual 与官方公告。

## 相关规则

- `shared.ftc-sdk-pin-release`
- `shared.ftc-sdk-preserve-build-tooling`
- `shared.ftc-sdk-separate-toolchain-versions`

这些规则当前都是 `candidate`，需要授权负责人审批后才会成为 Agent 的 active 规则。

## 官方来源

- [FIRST FtcRobotController v11.2 release](https://github.com/FIRST-Tech-Challenge/FtcRobotController/releases/tag/v11.2)
- [v11.2 README](https://github.com/FIRST-Tech-Challenge/FtcRobotController/blob/v11.2/README.md)
- [v11.2 build.gradle](https://github.com/FIRST-Tech-Challenge/FtcRobotController/blob/v11.2/build.gradle)
- [v11.2 build.common.gradle](https://github.com/FIRST-Tech-Challenge/FtcRobotController/blob/v11.2/build.common.gradle)
- [v11.2 build.dependencies.gradle](https://github.com/FIRST-Tech-Challenge/FtcRobotController/blob/v11.2/build.dependencies.gradle)
- [v11.2 Gradle Wrapper](https://github.com/FIRST-Tech-Challenge/FtcRobotController/blob/v11.2/gradle/wrapper/gradle-wrapper.properties)
- [Android Developers: AGP 8.13 compatibility](https://developer.android.com/build/releases/agp-8-13-0-release-notes)
