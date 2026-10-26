# Dashboard 稳定启动验收

记录日期：2026-10-26。范围：此前已授权的 52 文件交付，加本次完整 distribution 稳定启动功能。既有四条 Command 规则的批准未重新执行；本次没有改规则、审批、目标机器人或固定版本。

## 行为与兼容边界

新增标准库 `dashboard_runtime.py`。它核验并独立复制完整 distribution，在 0700 私有目录中运行 POSIX launcher；文件数、总字节数和目录项均有界，复制时每文件写入不得超过前清单大小。源分发后续被替换、删除或移走，不会改变此副本的类路径。复制失败、可观察漂移、链接或特殊文件不启动 JVM。

正常退出、SIGINT/SIGTERM 只管理本次子进程，确认退出后清理自己创建且身份仍一致的目录；子进程退出未确认时保留目录并报告。SIGKILL 可留下活跃子进程和目录，测试显式回收自己资源，产品没有自动扫描或 GC。

prepare 与 doctor 的真实下一步均指向已审阅入口旁实际存在的 helper，目标固定源码仍提供二进制和 knowledge。cwd 是项目根目录，相对 audit 从项目根目录归一为显式绝对环境。旧 target 无 helper 仍可由外部新 helper 保护；未知能力、helper 缺失或 Windows 不输出受保护 argv。原 CLI、doctor 必需检查与退出含义、prepare/accept 协议、kernel JSON v2 和 Dashboard API 未改变。

## 验证

- 最终 Python 组合：156 项，155 passed、1 项原有 opt-in skipped；97 integration、20 staged、24 connection、15 runtime，未重复计算 connection 模块导入的 fixture 类。完整日志为本机 `final-python-tests.log`。
- Kotlin `CliDocumentationAcceptanceTest`：10 项通过，无失败或跳过；只运行该受影响类，未运行全仓库测试。
- 生命周期回归包含复制期信号、Popen 返回窗口信号、SIGINT/SIGTERM、SIGKILL、未确认子进程退出时保留副本、不清理外部或被替换目录、首个/后续文件增长在 write 前拒绝。
- 独立强审发现复制阶段可能先超过总预算的 P2，已修为每文件受前清单大小限制；独立小预算探针确认首次超限 write 前拒绝、无残留。

## 真实隔离闭环

在临时最小 FTC Git fixture 中，真实最新外部安装器以 `--prepare-only` 安装固定 `5b652e8111769bfe259234d8f99e14f62aa549db`，fresh CLI 准备约 20.11 秒。该历史 target 不包含新 helper，实际知识库为 **48 条**；主库仍为 **52 条**，不可混报。

直接执行实际输出的 `nextSteps.cwd/argv/env`，没有手写替代启动命令。正常浏览器已连接目标知识库，规则列表 48/48、无页面错误；暖访问只涉及静态资源、identity、rules 与页面读取。随后移走原 source distribution，首次 POST resolve 与 check 均返回 200、实际结果有效；三个静态资源摘要不变，副本主 JAR 摘要不变，审计仍写原项目指定的 `relative audit` 绝对目录，实际有 17 条事件。空补丁 check 无硬违规，18 项 soft 按下表评估。

该实例 SIGTERM 后 wrapper exit 143，副本已清理；为后续信号复测，测试结束后恢复了临时源 distribution；另一次真实 JVM SIGINT 后 exit 130，副本已清理。隔离服务均已停止。主 56382 和历史 staged 55231 未被实验起停。临时 fixture 的 commit 仅用于安装前提，不属于本仓库发布。

## Soft 逐项说明

以下是 KB 平台维护及隔离空补丁范围的 Agent 复核，不是真人审批或 Dashboard 复核身份声明；不写入 reviews.jsonl。

| ruleId | 判断 |
| --- | --- |
| global.constants-centralized | 无机器人调参；helper 预算和等待常量集中定义。 |
| global.documentation-intent | 文档解释启动目的、实际返回命令、失败和生命周期边界。 |
| global.hardware-container | 不适用：无 HardwareMap 或机器人硬件引用。 |
| global.motor-configuration | 不适用：无电机初始化或控制。 |
| global.naming-conventions | 无机器人程序命名变更。 |
| global.telemetry-organization | 不适用：无机器人遥测。 |
| shared.dependency-verify-sync-build-run | 未新增 FTC 依赖；只验证 KB CLI，不宣称 Android Sync、部署或运行。 |
| shared.ftc-sdk-separate-toolchain-versions | KB JVM 与目标 Android/FTC 工具链分开说明。 |
| shared.gobilda-identify-exact-sku | 不适用：无 goBILDA 产品。 |
| shared.gobilda-separate-stall-and-operating-values | 不适用：无电机规格或负载计算。 |
| shared.gobilda-servo-mode-and-pwm-range | 不适用：无舵机/PWM 端点。 |
| shared.gobilda-use-output-shaft-encoder-resolution | 不适用：无编码器换算。 |
| shared.limelight-back-up-before-os-update | 不适用：无 LimelightOS 更新。 |
| shared.limelight-configure-camera-pose | 不适用：无相机姿态改动。 |
| shared.limelight-synchronize-pipeline-dependent-reads | 不适用：无 pipeline 切换或读取。 |
| shared.pedro-explicit-coordinate-conversion | 不适用：无坐标转换。 |
| shared.pedro-localization-before-follower | 不适用：无 localizer/follower。 |
| shared.pedro-tune-current-robot | 不适用：未复制或验证机器人调参。 |

发布前 cached diff 检查发现原新规则文件末尾多余空行；仅删除四个末尾换行字节。完整 YAML 文档解析结果（含所有字符串、approval、checks、reviewTriggers）一致，validate 仍为 52 条通过，resolve 结果不变；最终 cached diff 检查通过。此格式修正没有重新审批或修改规则语义。

## 可复核证据与限制

程序、日志、凭据、截图和 manifest 留在 `/private/tmp`，不提交。本机测试目录：`/private/tmp/ftckb-runtime-feature-20261026/`；真实演示：`/private/tmp/ftckb-runtime-real-20261026-pi90_kr8/`（`prepare.json`、`actual-next-step.json`、`results.json`、`browser-evidence.json`、`connected.png`、`sigint-evidence.json`）。包含 token 的私有连接与日志不放入仓库。

运行中 JAR 原地覆写机制已用隔离 Gradle `installDist` 复现；这不追认历史 60284 故障的唯一根因。新入口保证限于构建完成的正常 POSIX distribution，不证明构建来源、同权限 ABA、并发构建的全局原子性、自定义 launcher 或外部 JVM agent 路径。Windows 原生、机器人构建、Robot Controller/Driver Station/Control Hub 部署和真机均未验证。直接 CLI 不透明获得保护。

发布收尾由 root 验收最终独立审查后，integration 执行已授权的显式 allowlist commit/push；不创建 PR，随后停止连续优化，不启动下一功能。
