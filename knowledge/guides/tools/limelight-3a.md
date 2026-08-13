# Limelight 3A：接线、pipeline、结果与定位

> 核验日期：2026-08-13。Limelight 网页未标明统一文档版本；本文按核验日官方 Quick-Start、FTC Programming Guide 与 FTC SDK 11.2 API 编写。

## 先认识硬件边界

Limelight 3A 是通过 USB-C 接入 Control Hub 的智能相机。官方硬件规格包括：

| 项目 | 官方规格 |
|---|---:|
| USB 供电 | 4.1–5.75 V |
| 最大功耗 | 4 W |
| sensor capability | OV5647，640×480 @ 90 FPS |
| 内置照明 | 无 built-in LED illumination |
| 连接 | 仅 USB-C，无 RJ45 |
| 视场角 | H 54.5°，V 42° |

90 FPS 是 sensor capability，不代表每种 pipeline、网络轮询或 Robot Controller 都能得到 90 Hz 的新结果。官方同时列出 AprilTag、color、neural 和 SnapScript 等不同性能路径；neural pipeline 在 3A 上是 CPU inference。

## 安装与 Web 配置

1. 用至少 2 枚合适的 M3/M4 螺钉固定相机，避免只靠容易位移的临时粘接；
2. 先用 USB-C 数据线连接电脑，等待绿灯活动，官方给出的启动等待约 15–20 s；
3. 打开 Limelight Hardware Manager，或浏览器访问 `http://limelight.local:5801`；
4. 在 Settings 设置队号并点击 Restart Vision Client；
5. 创建并保存所需 pipeline，记录 index、类型、分辨率、曝光、tag family/field map；
6. 断电后把 Limelight 3A 接到 Control Hub **蓝色 USB 3.0 端口**；
7. 在 DS → Configure Robot 中 Scan，应看到 `Ethernet Device`；
8. 把设备名改为 `limelight`，保存并激活 Robot Configuration。

相机没有内置照明；颜色/反光目标表现随现场光线变化，必须在接近赛场照明的环境采集 snapshot 并验证。

### 更新 LimelightOS 前必须备份

官方 Quick-Start 明确警告：flashing/upgrading LimelightOS 会擦除 pipelines 和 scripts。先导出并在队伍仓库/备份盘验证文件可读，再开始更新。这对应 `shared.limelight-back-up-before-os-update`。不能把“网页当前能打开”当作备份。

## Pipeline 是异步切换

`pipelineSwitch(index)` 是 fire-and-forget（发出即返回）：代码不会等待相机切换完成。因此 pipeline 0 的请求发出后，下一次 result 仍可能属于旧 pipeline。任何依赖 pipeline 类型的读取都要比较 `result.getPipelineIndex()`；这对应 `shared.limelight-synchronize-pipeline-dependent-reads`。

## 最小、可拒绝坏数据的 OpMode

下面的 `YOUR_TASK_MAXIMUM_AGE_MS` 是任务专属占位符。100 Hz 只是官方示例的 RC poll request rate，不保证 100 Hz 新 frame。官方 freshness 示例使用 100 ms 作为演示，也不是所有闭环控制的通用阈值。

```java
package org.firstinspires.ftc.teamcode;

import com.qualcomm.hardware.limelightvision.LLResult;
import com.qualcomm.hardware.limelightvision.Limelight3A;
import com.qualcomm.robotcore.eventloop.opmode.OpMode;
import com.qualcomm.robotcore.eventloop.opmode.TeleOp;

@TeleOp(name="Limelight Smoke Test",group="Setup")
public class LimelightSmokeTest extends OpMode {
    private static final int EXPECTED_PIPELINE=0;
    private static final long MAXIMUM_AGE_MS=YOUR_TASK_MAXIMUM_AGE_MS;
    private Limelight3A limelight;

    @Override
    public void init() {
        limelight=hardwareMap.get(Limelight3A.class,"limelight");
        limelight.setPollRateHz(100);
        limelight.pipelineSwitch(EXPECTED_PIPELINE);
        limelight.start();
    }

    @Override
    public void loop() {
        LLResult result=limelight.getLatestResult();
        if(result==null || !result.isValid()) {
            telemetry.addLine("No valid Limelight target");
            telemetry.update();
            return;
        }

        long ageMs=result.getStaleness();
        if(ageMs>MAXIMUM_AGE_MS ||
            result.getPipelineIndex()!=EXPECTED_PIPELINE) {
            telemetry.addData("Rejected result age (ms)",ageMs);
            telemetry.addData("Pipeline",result.getPipelineIndex());
            telemetry.update();
            return;
        }

        telemetry.addData("tx (deg)",result.getTx());
        telemetry.addData("ty (deg)",result.getTy());
        telemetry.addData("ta (%)",result.getTa());
        telemetry.addData("age (ms)",ageMs);
        telemetry.update();
    }

    @Override
    public void stop() {
        limelight.stop();
    }
}
```

- `tx`：目标水平偏角，degree；
- `ty`：目标垂直偏角，degree；
- `ta`：目标占图像面积，0–100%；
- `getStaleness()`：结果年龄，ms。

FTC SDK 10.3 起在尚未收到数据时也可能返回一个 invalid `LLResult`，所以 `isValid()` 是核心门槛；保留 null 检查可兼容保守调用。`shared.limelight-check-result-validity` 与 `shared.limelight-enforce-freshness-policy` 分别禁止使用 invalid 和过期结果。

### 如何决定 freshness threshold

不要直接复制 100 ms。先测量该 pipeline 在目标硬件、分辨率、光照、运动速度和 RC 负载下的 staleness 分布，再按任务风险决定：

- 只做驾驶员提示可容忍较宽阈值；
- 闭环对准要结合机器人最大角速度/平移速度估计旧数据带来的位置误差；
- field localization 融合要同时检查 pose jump、tag 数量/质量和当前 odometry；
- 超过阈值时必须明确 fallback，例如停止修正、退回 odometry 或要求重新捕获，而不是继续使用最后值。

## Snapshot 验证 pipeline

官方 API 支持：

```java
limelight.captureSnapshot("setup_known_target");
```

在 Web UI Input tab 选择该 snapshot 作为 image source，可离线复查阈值和识别。snapshot 要带场景、距离、光照、pipeline index 和日期；它只能复现图像处理，不复现机器人运动延迟。

## MegaTag 1/2 field localization

在读取 field pose 前必须：

1. 在 AprilTag pipeline Advanced 中启用 **Full 3D**；
2. 在 Web UI 精确配置相机相对 robot footprint center 的位置与朝向（camera extrinsics）；
3. 核对当前赛季 field map、tag family 与 tag 实际安装；
4. 采用官方 FTC field coordinate system：场地地面中心为 `(0,0,0)`；
5. 静止在多个已知点验证，不先接入 path follower。

MegaTag 2 会融合外部 IMU。每次读取 MT2 前先给 Limelight 当前 robot yaw。为了避免隐式角度单位，FTC IMU 端显式请求 degree：

```java
double robotYawDegrees=imu.getRobotYawPitchRollAngles()
    .getYaw(AngleUnit.DEGREES);
limelight.updateRobotOrientation(robotYawDegrees);

LLResult result=limelight.getLatestResult();
if(result!=null && result.isValid() &&
    result.getStaleness()<=YOUR_LOCALIZATION_MAXIMUM_AGE_MS) {
    Pose3D pose=result.getBotpose_MT2();
    if(pose!=null) {
        telemetry.addData("x",pose.getPosition().x);
        telemetry.addData("y",pose.getPosition().y);
    }
}
```

需要 imports：

```java
import org.firstinspires.ftc.robotcore.external.navigation.AngleUnit;
import org.firstinspires.ftc.robotcore.external.navigation.Pose3D;
```

本文使用 degree 是为了与 Limelight FTC 示例的 robot yaw 约定保持一致，并避免 IMU 默认单位不清。对你固定的 FTC SDK 版本仍应打开 `Limelight3A.updateRobotOrientation` Javadoc/官方 sample 核验签名。

相机 pose 配错几 cm 或几度会系统性扭曲 field pose；MT2 输入 yaw 符号/零点错也会造成跳变。这对应 `shared.limelight-configure-camera-pose`。

### 与 Pedro Pathing 联用

Limelight `botPose` 使用 FTC standard field coordinates；Pedro 使用另一坐标约定。不要把 x/y/heading 直接塞给 follower。先按[Pedro 坐标教程](pedro-pathing.md)使用官方 PoseConverter，显式处理长度单位，再经过 validity、freshness、jump gate 和队伍定义的 fusion policy。

## 分层验收

1. **Discovery**：DS Scan 稳定出现 Ethernet Device，hardware name 是 `limelight`；
2. **Web**：`limelight.local:5801` 可开，队号、pipeline 与输入画面正确；
3. **Pipeline**：请求 index 后 telemetry 最终报告同一 index；切换期间数据被拒绝；
4. **Validity**：移走目标后 `isValid` 行为符合 pipeline 设计，不保留旧控制输出；
5. **Freshness**：静止与运动时记录 staleness 分布，超阈值走 fallback；
6. **Angles**：将已知目标左右/上下移动，核对 tx/ty 符号和 degree；
7. **Snapshots**：保存已知好/坏场景并能在 Web UI 重放；
8. **Localization**：多个静止已知点检查坐标、heading、camera pose；
9. **Motion latency**：低速移动/转动，比较 vision 与 odometry 的时间差；
10. **Closed loop**：只在前九项有记录后，以低功率和人员急停进行。

## 常见问题

| 现象 | 优先检查 |
|---|---|
| Scan 没有 Ethernet Device | Control Hub 蓝色 USB 3.0 端口 → 数据线 → Limelight 供电/绿灯 → RC/DS 版本 → 重新 Scan |
| Web UI 打不开 | 先直连电脑 → 等 15–20 s → Hardware Manager → `limelight.local:5801` → 主机 mDNS/防火墙 |
| result invalid | pipeline 是否有目标 → exposure/光照 → tag family/阈值 → Web UI 原始画面 → 不要继续用 tx/pose |
| switch 后仍是旧 pipeline | 这是异步行为 → 读取 `getPipelineIndex()` → 匹配前拒绝 pipeline-dependent data |
| staleness 持续很大 | pipeline 帧率/CPU → USB 连接 → RC poll rate 与负载 → 分辨率/算法 → 采用 fallback |
| tx/ty 符号与预期相反 | 相机安装朝向 → 目标移动测试 → 不要在多层代码同时反号 |
| field pose 镜像/旋转 | FTC field coordinate → field map/alliance → camera extrinsics → yaw zero/sign/unit |
| field pose 跳变 | tag 可见数/遮挡 → 运动模糊/曝光 → camera mount 松动 → freshness/jump gate → odometry 对照 |
| 看不到 tag | pipeline 类型和 tag family → Full 3D → 场地图 → 距离/视场/光照 |
| neural pipeline 很慢 | 3A 仅 CPU inference → 模型/分辨率 → 是否可用简单 90 FPS color pipeline 完成任务 |
| 更新后 pipeline 消失 | 官方更新过程会擦除 → 从已验证备份恢复；没有备份时不要凭记忆重建并直接比赛 |

## 安全与误用边界

- invalid、wrong-pipeline 或 stale data 必须被拒绝，不能只显示 warning 后继续驱动；
- `setPollRateHz(100)` 不等于 100 Hz 新感知，也不等于 10 ms end-to-end latency；
- camera pose、field map、yaw 和坐标转换都属于定位校准，不是通用默认值；
- 视觉闭环首次测试限功率、清场并保留人工急停；
- Limelight 没有内置光源，不要假定实验室 pipeline 在赛场光照下相同；
- 更新 OS 前导出 pipelines/scripts 并验证备份。

## 相关规则

- `shared.limelight-check-result-validity`
- `shared.limelight-enforce-freshness-policy`
- `shared.limelight-synchronize-pipeline-dependent-reads`
- `shared.limelight-configure-camera-pose`
- `shared.limelight-back-up-before-os-update`

这些规则当前都是 `candidate`。

## 官方来源

- [Limelight 3A Quick-Start](https://docs.limelightvision.io/docs/docs-limelight/getting-started/limelight-3a)
- [FTC Java & Blockly Programming Guide](https://docs.limelightvision.io/docs/docs-limelight/apis/ftc-programming)
- [FTC Hardware 11.0 Limelight3A Javadoc](https://javadoc.io/static/org.firstinspires.ftc/Hardware/11.0.0/com/qualcomm/hardware/limelightvision/Limelight3A.html)
- [FIRST FtcRobotController v11.2 README](https://github.com/FIRST-Tech-Challenge/FtcRobotController/blob/v11.2/README.md)
