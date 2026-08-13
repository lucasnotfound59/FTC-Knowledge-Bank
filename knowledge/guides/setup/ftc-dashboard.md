# 在 FTC 工程中安装和验证 FTC Dashboard

> 核验快照：2026-08-13。本文固定使用 FTC Dashboard 官方 Getting Started 当前列出的稳定依赖 `0.6.0`，面向 Android RC App 与 Control Hub。

## 适用范围与目标

FTC Dashboard 提供浏览器遥测、实时配置变量和图表。它适合调试与调参，但不是 Driver Station 的替代品，也不会自动给电机参数加安全限制。

完成本文后，你应该能：

- 在 `TeamCode/build.gradle` 中加入固定版本依赖；
- 构建并把含 Dashboard 的 RC App 部署到 Control Hub；
- 连接 RC Wi-Fi，打开 Dashboard 页面；
- 运行 smoke test，在网页中修改安全的静态变量并看到遥测。

前置条件是[Android Studio 与 FTC SDK 配置](android-studio-ftc-sdk.md)已经端到端通过。

## 两个 Gradle 文件为什么看起来冲突

Dashboard 官方页面要求编辑根目录的 `build.dependencies.gradle`。FIRST v11.2 的 `build.common.gradle` 同时说明：队伍如需自定义构建，应优先放在 `TeamCode/build.gradle`，以降低未来合并 SDK 更新时的冲突。

本知识库把第三方 team dependency 放在 `TeamCode/build.gradle`：作用域更小，也不会修改 FIRST 集中维护的 SDK 依赖清单。如果你的队伍已有统一依赖管理方式，可以继续使用，但必须记录文件位置并避免同一仓库/依赖被声明两次。

## 安装稳定依赖

打开 `TeamCode/build.gradle`。保留已有内容，在文件中加入 Dashboard Maven 仓库与精确版本：

```groovy
repositories {
    maven { url='https://maven.brott.dev/' }
}

dependencies {
    implementation project(':FtcRobotController')
    implementation 'com.acmerobotics.dashboard:dashboard:0.6.0'
}
```

如果 `dependencies` 已经存在，只向原块添加一行，不要创建第二个重复块。不要使用 `0.+`、`latest.release` 或 `SNAPSHOT`。

Dashboard 页面还给 OpenRC 或非标准 SDK 依赖提供了排除 `org.firstinspires.ftc` 的高级写法。标准 FIRST v11.2 工程不要默认套用它；只有依赖报告确认 Dashboard 带入的 FTC artifacts 与项目发生重复时，才结合完整 dependency tree 判断。

## Sync 与本地构建

在 Android Studio 执行 Gradle Sync，然后运行：

```bash
./gradlew :TeamCode:dependencies --configuration debugRuntimeClasspath
./gradlew :TeamCode:assembleDebug
```

在依赖输出中确认存在 `com.acmerobotics.dashboard:dashboard:0.6.0`。这一步对应 `shared.dashboard-pin-stable-dependency`，但仍只证明本地依赖和编译成功。

## 最小 Dashboard smoke test

创建 `TeamCode/src/main/java/org/firstinspires/ftc/teamcode/DashboardSmokeTest.java`：

```java
package org.firstinspires.ftc.teamcode;

import com.acmerobotics.dashboard.FtcDashboard;
import com.acmerobotics.dashboard.config.Config;
import com.acmerobotics.dashboard.telemetry.MultipleTelemetry;
import com.qualcomm.robotcore.eventloop.opmode.OpMode;
import com.qualcomm.robotcore.eventloop.opmode.TeleOp;

@Config
@TeleOp(name="Dashboard Smoke Test",group="Setup")
public class DashboardSmokeTest extends OpMode {
    public static double TEST_VALUE=0.25;

    @Override
    public void init() {
        telemetry=new MultipleTelemetry(
            telemetry,
            FtcDashboard.getInstance().getTelemetry()
        );
    }

    @Override
    public void loop() {
        telemetry.addData("TEST_VALUE",TEST_VALUE);
        telemetry.update();
    }
}
```

`@Config` 让 public static 字段出现在配置页面；`MultipleTelemetry` 同时把数据发往 DS 和 Dashboard。示例变量没有连接任何执行器，适合作为第一项验证。

## 端到端验收

按 `shared.dependency-verify-sync-build-run` 完成四层验证：

1. **Sync**：依赖解析成功；
2. **Build**：`:TeamCode:assembleDebug` 成功；
3. **Deploy**：重新 Run RC App 到 Control Hub，不能继续使用旧 APK；
4. **Runtime**：运行实际 Dashboard 页面和 OpMode。

Runtime 的精确步骤：

1. 固定机器人，保持机构无动力；
2. 用 DS 确认最新 RC App 正常启动；
3. 电脑连接 Control Hub 广播的 Wi-Fi；
4. 浏览器打开 `http://192.168.43.1:8080/dash`；若使用 Android 手机 RC，官方地址是 `http://192.168.49.1:8080/dash`；
5. 在 DS INIT/START `Dashboard Smoke Test`；
6. 在 Dashboard 的配置区把 `TEST_VALUE` 从 `0.25` 改成 `0.50`；
7. 确认网页遥测与 DS 遥测都显示 `0.50`；
8. STOP OpMode。

完成后把 `DashboardSmokeTest` 保留在 Setup 组或删除均可，但比赛代码中的可调字段必须另行设定安全范围与默认值。

## 在真实机器人上使用时

适合放入 Dashboard 的值包括：低风险显示阈值、PID 初始参数副本、视觉阈值和只读 telemetry。对于电机功率、舵机端点、升降高度和路径约束：

- 代码中仍要 `clip` 到机械安全范围；
- 默认值必须是机器人上电后的安全值；
- 调参时架空或拆除危险机构，旁边保留急停人员；
- 每次只改一个参数，记录原值、测试条件和结果；
- 比赛前把验证值写回受版本控制的代码，不能依赖浏览器临时状态。

## 常见问题

| 现象 | 按顺序检查 |
|---|---|
| Maven 仓库或 artifact 无法解析 | `https://maven.brott.dev/` 拼写 → 是否在 `TeamCode` repository scope → 网络/DNS → Gradle Offline mode → 版本 `0.6.0` |
| Sync 成功但运行时找不到 Dashboard 类 | `debugRuntimeClasspath` → `assembleDebug` → 是否重新安装最新 APK → RC 日志完整异常 |
| 页面打不开 | 电脑是否连接 RC/Control Hub Wi-Fi → Control Hub 用 `192.168.43.1`、手机 RC 用 `192.168.49.1` → 端口 `/8080/dash` → RC App 是否正在运行 |
| 浏览器连到了别的设备 | 核对 Wi-Fi SSID/队号 → 暂时关闭自动切回互联网 Wi-Fi → 重新打开正确 IP |
| `@Config` 字段不显示 | 类是否 `public` 且有 `@Config` → 字段是否 `public static` → 最新 APK 是否部署 → 刷新页面 |
| Dashboard 有页面但没有 telemetry | OpMode 是否 INIT/START → 是否取得 Dashboard telemetry → 是否调用 `telemetry.update()` → 查看 RC 日志 |
| DS 与 Dashboard 值不同 | 是否确实使用 `MultipleTelemetry` → 字段是否在运行中被其他代码重写 → 刷新频率与网络延迟 |
| 改值后机构突然运动 | 立即 STOP/断开机器人主电源 → 恢复安全默认值 → 在代码中添加硬限幅、状态门和软限位后再测试 |

## 安全与误用边界

- Dashboard 的实时编辑没有机械上下文，不能替代限位开关、软件限幅或急停；
- 不在机器人置于场上或人员靠近机构时远程改执行器参数；
- 不把 Dashboard 暴露到公共网络；只在受控的 RC 局域网使用；
- 不把“网页能打开”当作依赖安装完成，必须同时验证 build、deploy 和 OpMode；
- 不让比赛运行依赖浏览器保持连接。

## 相关规则

- `shared.dashboard-pin-stable-dependency`
- `shared.dependency-verify-sync-build-run`

这些规则当前都是 `candidate`，尚未自动生效。

## 官方来源

- [FTC Dashboard Getting Started](https://acmerobotics.github.io/ftc-dashboard/gettingstarted.html)
- [FTC Dashboard releases](https://github.com/acmerobotics/ftc-dashboard/releases)
- [FIRST v11.2 TeamCode build.gradle](https://github.com/FIRST-Tech-Challenge/FtcRobotController/blob/v11.2/TeamCode/build.gradle)
- [FIRST v11.2 build.common.gradle](https://github.com/FIRST-Tech-Challenge/FtcRobotController/blob/v11.2/build.common.gradle)
