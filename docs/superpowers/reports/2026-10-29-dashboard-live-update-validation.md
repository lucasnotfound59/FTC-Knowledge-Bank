# Dashboard 实时更新修复验收

日期：2026-10-29。范围：Dashboard 前端状态同步、执行级前端回归和使用文档。未修改真实知识库规则、机器人项目配置或固定 pin；保留工作区原有未提交改动。

## 问题与修复

- 实际浏览器复现：打开编辑器后外部修改规则，首次预览及重复预览均返回 `stale_preview`，没有读取最新版本入口。修复后，轮询立即废弃旧预览；首次预览和保存的版本错误均提供保留草稿、对照最新 YAML 的恢复入口。
- 预览、版本恢复、列表和详情响应加入请求与版本保护，旧响应不能覆盖新状态。关闭重开编辑器或保存期间继续输入，不会让旧请求启用另一个编辑器的保存按钮或丢弃新草稿。
- 修改队号、赛季、profile、工作模式或补丁会标记旧结果过期；重新执行采用新输入，迟到的旧请求不能冒充当前结果。
- 规则参数仍通过「编辑 YAML / 适用范围」修改，经过预览和完整库校验后保存。裁决参数仅属于当次手动调用，不改项目默认设置。

## 本次验证

- `node --test tests/dashboard/app-state.test.cjs`：19/19 通过。执行真实页面脚本和绑定事件，控制 HTTP 响应时序；不冒充浏览器或文件写入测试。
- DashboardCommandTest 2、DashboardServerTest 17、DashboardReviewTest 10、KnowledgeWorkspaceTest 15：44/44 通过。
- `node --check`、`git diff --check` 通过；完成 `:apps:knowledge-cli:installDist`。最后新增的恢复响应版本保护经 19 项前端测试与独立审查通过，并重新构建。
- 真实浏览器在隔离知识库完成：外部修改后自动提示 → 读取最新 YAML 且草稿保留 → 修改标题、适用队伍与检查 pattern → 预览完整库通过 → 保存 → 列表及详情立即更新。另从文件读取确认三项修改实际持久化。
- 真实浏览器完成：裁决 20827 → 改为 16093 立即提示旧结果过期 → 重新执行显示 16093。未捕获浏览器错误。没有向真实知识库写入测试规则。
- 本次实际知识库 `validate` 返回 `ok:true`、58 条规则。自己的独立 diff 检查使用维护 QA 上下文 `20827 / 2025-2026 / command-based / normal`，没有硬违规；此上下文不冒充机器人项目的实际接入配置。

上述 58 条规则及下列 soft 来自验收时的完整工作区，包含此前未提交的 SolversLib 规则变更；这些规则变更不属于本次 Dashboard 修复提交。

未做完整仓库回归、Robot Controller、Driver Station 或真机验证。页面刷新会丢失仅存于内存的连接凭据，须重开本次完整启动链接；该既有连接约定未改变。

## 22 项 soft 的逐项复核

以下是本次交付的语义复核说明，不是写入 Dashboard `reviews.jsonl` 的独立历史声明，也不改变原始检测结果。

| ruleId | 本次结论与依据 |
| --- | --- |
| global.command-live-input | 本次不适用：未修改机器人 Command 或手柄读取。 |
| global.command-requirements-cleanup | 本次不适用：未修改 Subsystem、requirements 或机构输出。 |
| global.command-responsibilities | 本次不适用：修改知识库网页，不涉及机器人架构分工。 |
| global.constants-centralized | 本次不适用机构调参：未新增机器人常量或单位换算。 |
| global.documentation-intent | 已核对：中文界面和文档说明版本过期、草稿恢复与重新检测；没有机构逻辑变更。 |
| global.hardware-container | 本次不适用：没有硬件引用或 HardwareMap。 |
| global.motor-configuration | 本次不适用：没有电机初始化修改。 |
| global.naming-conventions | 已核对：新增 JS 状态和函数使用有语义的 lowerCamelCase；未修改自动 OpMode 命名。 |
| global.solverslib-production-runtime | 本次不适用机器人运行时接入：没有增加机器人命令框架或 scheduler，不声称已接入 SolversLib。 |
| global.telemetry-organization | 本次不适用：本地知识库 Dashboard 不属于机器人 FtcDashboard 遥测改动。 |
| shared.dependency-verify-sync-build-run | 本次不适用：未增加或变更 FTC 库依赖；只报告实际本机构建。 |
| shared.ftc-sdk-separate-toolchain-versions | 本次不适用：未修改 FTC SDK、Android SDK 或机器人工具链。 |
| shared.gobilda-identify-exact-sku | 本次不适用：未引用 goBILDA 型号参数。 |
| shared.gobilda-separate-stall-and-operating-values | 本次不适用：未使用堵转或运行参数。 |
| shared.gobilda-servo-mode-and-pwm-range | 本次不适用：未修改舵机模式、PWM 或端点。 |
| shared.gobilda-use-output-shaft-encoder-resolution | 本次不适用：未进行编码器或传动比换算。 |
| shared.limelight-back-up-before-os-update | 本次不适用：未更新 LimelightOS。 |
| shared.limelight-configure-camera-pose | 本次不适用：未修改相机位姿或定位。 |
| shared.limelight-synchronize-pipeline-dependent-reads | 本次不适用：未修改 Limelight pipeline。 |
| shared.pedro-explicit-coordinate-conversion | 本次不适用：未进行坐标系转换。 |
| shared.pedro-localization-before-follower | 本次不适用：未修改定位器或 follower。 |
| shared.pedro-tune-current-robot | 本次不适用：未提供或复制机器人实测调参值。 |
