# SolversLib 规则验收 — 2026-10-29

用户确认范围：仅已选择 command-based 的项目。旧 ftclib-command 归一化也包含该 profile；generic/simple-opmode/rookiebot 不扩大范围。未 commit/push、未修改任何机器人项目的固定 pin。

## 结果

- 完整知识库 validate：exit 0、ok=true、58 条（52 approved + 6 candidate）。
- 受影响 CLI 七类验收共 72 项通过；domain WorkModeTest 7 项通过，共 79 项，无失败/跳过。最后的指南/文档修正后，两类 20 项另行复验通过。这不是全仓回归。
- 新增 SolversLibRuleAcceptanceTest 8 项覆盖 profile/队号/跨赛季、覆盖旧主题、显式 test 安全保留、Java/Kotlin/Gradle 正反例、条件式 soft、CLI normal/test/dev。
- 真实 CLI 的 resolve matrix 无同层冲突；2025-2026 generic=26、command-based=38、rookiebot=38、ftclib-command=38；显式 test 的 ftclib-command=36。test 会恢复原共享运行时选择，不能只用 active 集合相减推断。
- 基线 WorkModeAcceptanceTest 有一项旧 30/28 数量断言失败，已随真实当前规则修正，未忽略失败。
- 对仅本次修改的显式补丁运行 normal/command-based check：exit 0、ok=true、无 hard violations；以下逐项列出全部 soft。
- 独立代码审查完成，旧 FTCLib 安装页与正式运行时政策的矛盾已修正，无剩余重要问题。
- CLI 分区与 Dashboard 实际 audit root 均为 /Users/xinlu/.ftckb/audit；知识根为当前 checkout/knowledge。Dashboard 已使用新建构建的稳定副本，页面连接单独核验；无 token 入库。

## Soft 逐项复核

维护 QA 上下文使用已有验收矩阵的 20827/2025-2026/command-based/normal，不宣称这是某个机器人项目的实际接入上下文。此次变更范围为知识库政策、Kotlin/CLI 验收、文档、模板与 JSON 示例，无目标 TeamCode/FTC 依赖或硬件运行变更。以下结论是本次交付复核，不冒充已提交 Dashboard 的独立 reviews.jsonl 声明。

- `global.command-live-input`：本次不适用：未修改对应目标机器人命令、机构、硬件参数、遥测、相机或路径控制；新增文本政策与验收数据不构成该机器人行为的实现或硬件验证。
- `global.command-requirements-cleanup`：本次不适用：未修改对应目标机器人命令、机构、硬件参数、遥测、相机或路径控制；新增文本政策与验收数据不构成该机器人行为的实现或硬件验证。
- `global.command-responsibilities`：本次不适用：未修改对应目标机器人命令、机构、硬件参数、遥测、相机或路径控制；新增文本政策与验收数据不构成该机器人行为的实现或硬件验证。
- `global.constants-centralized`：本次不适用：未修改对应目标机器人命令、机构、硬件参数、遥测、相机或路径控制；新增文本政策与验收数据不构成该机器人行为的实现或硬件验证。
- `global.documentation-intent`：已核对：新增规则与指南使用中文说明意图、单位、版本和安全边界；本次没有新增目标机器人机构实现。
- `global.hardware-container`：本次不适用：未修改对应目标机器人命令、机构、硬件参数、遥测、相机或路径控制；新增文本政策与验收数据不构成该机器人行为的实现或硬件验证。
- `global.motor-configuration`：本次不适用：未修改对应目标机器人命令、机构、硬件参数、遥测、相机或路径控制；新增文本政策与验收数据不构成该机器人行为的实现或硬件验证。
- `global.naming-conventions`：本次不适用：未修改对应目标机器人命令、机构、硬件参数、遥测、相机或路径控制；新增文本政策与验收数据不构成该机器人行为的实现或硬件验证。
- `global.solverslib-production-runtime`：本次不适用机器人运行时接入：修改的是 Knowledge Bank。已验证该政策在 command-based 正式上下文生效、test 排除；未在本仓库添加机器人 SolversLib 依赖，也未声称真实机器人已使用。
- `global.telemetry-organization`：本次不适用：未修改对应目标机器人命令、机构、硬件参数、遥测、相机或路径控制；新增文本政策与验收数据不构成该机器人行为的实现或硬件验证。
- `shared.dependency-verify-sync-build-run`：本次不适用目标机器人依赖验收：未安装或升级任何 FTC 库；实际完成的是 Knowledge Bank 构建及受影响验收，未声称 RC 部署或真机通过。
- `shared.ftc-sdk-separate-toolchain-versions`：本次不适用目标 FTC 工具链：未改变 Gradle JDK、Java compatibility、Android SDK 或 FTC SDK 版本。
- `shared.gobilda-identify-exact-sku`：本次不适用：未修改对应目标机器人命令、机构、硬件参数、遥测、相机或路径控制；新增文本政策与验收数据不构成该机器人行为的实现或硬件验证。
- `shared.gobilda-separate-stall-and-operating-values`：本次不适用：未修改对应目标机器人命令、机构、硬件参数、遥测、相机或路径控制；新增文本政策与验收数据不构成该机器人行为的实现或硬件验证。
- `shared.gobilda-servo-mode-and-pwm-range`：本次不适用：未修改对应目标机器人命令、机构、硬件参数、遥测、相机或路径控制；新增文本政策与验收数据不构成该机器人行为的实现或硬件验证。
- `shared.gobilda-use-output-shaft-encoder-resolution`：本次不适用：未修改对应目标机器人命令、机构、硬件参数、遥测、相机或路径控制；新增文本政策与验收数据不构成该机器人行为的实现或硬件验证。
- `shared.limelight-back-up-before-os-update`：本次不适用：未修改对应目标机器人命令、机构、硬件参数、遥测、相机或路径控制；新增文本政策与验收数据不构成该机器人行为的实现或硬件验证。
- `shared.limelight-configure-camera-pose`：本次不适用：未修改对应目标机器人命令、机构、硬件参数、遥测、相机或路径控制；新增文本政策与验收数据不构成该机器人行为的实现或硬件验证。
- `shared.limelight-synchronize-pipeline-dependent-reads`：本次不适用：未修改对应目标机器人命令、机构、硬件参数、遥测、相机或路径控制；新增文本政策与验收数据不构成该机器人行为的实现或硬件验证。
- `shared.pedro-explicit-coordinate-conversion`：本次不适用：未修改对应目标机器人命令、机构、硬件参数、遥测、相机或路径控制；新增文本政策与验收数据不构成该机器人行为的实现或硬件验证。
- `shared.pedro-localization-before-follower`：本次不适用：未修改对应目标机器人命令、机构、硬件参数、遥测、相机或路径控制；新增文本政策与验收数据不构成该机器人行为的实现或硬件验证。
- `shared.pedro-tune-current-robot`：本次不适用：未修改对应目标机器人命令、机构、硬件参数、遥测、相机或路径控制；新增文本政策与验收数据不构成该机器人行为的实现或硬件验证。

## 边界

真实使用 SolversLib 属于必须完成的 Agent 语义复核；hard 只识别新增 FTCLib 命令引用及常见 core 依赖文本，不能证明缺失依赖、存量代码或自有 scheduler 合规。四条使用规范为条件式 soft，不是机器证明安全。源码证据固定到官方 0.3.6 tag commit，不等于发布 JAR、目标机器人编译、RC/DS 或真机验证。没有做这些外部验证。
