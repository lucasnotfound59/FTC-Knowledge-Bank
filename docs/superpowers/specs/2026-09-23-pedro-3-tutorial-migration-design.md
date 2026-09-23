# Pedro Pathing 3 教程迁移 — 设计说明

日期：2026-09-23。用户批准：2026-09-23（“ok”，针对本设计的口头概要）。

## 目标

所有**现行教学材料**以 Pedro Pathing 3.0.0 为基线，不再把 Pedro 2.1.2 的 `FollowerBuilder`、`com.pedropathing:ftc` 或旧路径 API 作为可照抄的当前教程。旧赛季来源、历史证据和迁移说明仍保留真实版本，不篡改为 v3。仅编译通过不能宣称真机、Control Hub 或 Driver Station 已验证。

## 官方基线

- 官方安装页：<https://pedropathing.com/docs/pathing/installation>，手动安装在 `build.dependencies.gradle` 中加入 Dairy release Maven、`com.pedropathing:revhub:x.y.z` 与 `com.pedropathing:tuning:1.0.0`。
- 固定发布：<https://github.com/Pedro-Pathing/PedroPathing/releases/tag/v3.0.0>，Pedro v3.0.0。
- 官方 Quickstart 固定 commit：`Pedro-Pathing/Quickstart@b4312385b7d0cc5e8dd263ec3927c9ef0cb48f36`；用其核对精确 `revhub:3.0.0` 坐标与可编译的 FTC 工程配置。
- 官方 API 文档：<https://pedropathing.com/docs/pathing/tuning/constants> 和 <https://pedropathing.com/docs/pathing/guide/setting-up-auto>；`Constants.create(hardwareMap)` 必须实际连接 localizer、drivetrain 与 follower 算法。路径创建、跟随、状态转换和停止行为还应逐项对照对应的官方 v3 guide/reference 页。

Pedro 3 是 API 重写，不能进行文字级版本替换。机器人专属电机名、Pinpoint 偏移、方向、控制器增益、速度、路径坐标必须由当前机器人重新测量／调参；官方示例数值不能作为本队参数。现有安全示例的默认锁、非阻塞循环、超时和可观察状态等安全意图保留，具体调用按 v3 改写。

## 实施范围

先完成已批准的 `2026-09-17-vendor-documented-build-dependencies-design.md`，让官方文档支持的 `build.dependencies.gradle` 变更触发需核对证据的 soft 提醒，而不是无条件 hard 拦截；`build.common.gradle` 仍 hard。此独立功能记为仓库 V0.7.0。

随后迁移 `knowledge/guides/tools/pedro-pathing.md`、`knowledge/guides/practices/rookiebot-tutorial.md`、`knowledge/examples/pedro/SafePedroAuto.java`、`fixtures/pedro-compile/`、`docs/handbook/pedro-verification.md` 与 README 的现行 Pedro 入口及其验收测试。`shared.rookiebot-pedro-complete-builder` 的 instruction 改成版本无关的“实际接入底盘、定位器及算法”，并加入第一方 v3 evidence；不伪称旧 RookieBot 的 2.1.2 提交使用了 v3。其他现行页面若包含可照抄的 v2 安装/API 指令一并迁移；历史 specs、plans、旧来源版本说明可保留。

仓库版本从 V0.8.0 增至 V0.9.0；无必要时不改变 CLI、YAML、kernel JSON 或项目接入协议版本。此迁移不升级用户机器人项目，也不执行部署／真机测试。

## 验收

现行教程没有把 v2 依赖或 API 表述为当前操作步骤；v3 安装、Constants、Pose/Path、跟随、调参及安全示例均有官方来源；Pedro fixture 编译**唯一规范示例**，且测试不再硬锁 2.1.2；`ftckb validate knowledge --json` 通过，相关规则 resolve 无冲突，整套 Kotlin/Python 验收与 `verifyPedroRelease` 按环境运行。若 Gradle 依赖或 Android SDK 环境不可用，记录实际失败点，不声称编译成功。
