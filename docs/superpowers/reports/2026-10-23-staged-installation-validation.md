# 分阶段安装验收（2026-10-23）

新增显式 `integrate.py --prepare-only` 与 `verify.py --accept --prepared-context-sha256 HASH --connection-file PRIVATE --browser-confirmed`。prepare 复用原安装检查并显式构建，不运行 validate/resolve/check；dry-run 不写文件且没有可用摘要。prepared 不含 installationOk=true。accept 不构建或修改 pin/配置，先核准备输入与实际目标 Dashboard，再逐条执行 validate、resolve、check。无新参数的程序 API 与旧 CLI 路径保持兼容。

准备摘要绑定实际根目录/gitdir、配置原字节、明确队伍/赛季/profile、固定源身份/gitlink、托管内容、完整 YAML 输入（含 ignored）、launcher/lib/stamp 实际内容与审计身份，不绑定普通机器人源码变化。每个内核命令前后均重核；观察到漂移停止后续阶段并保留实际执行结果。摘要不防御同权限并发 ABA，不是构建来源或审批凭证。

新 accept 的 diagnostics 只输出固定有界分类，隐藏原始 stderr。两类已知 audit storage 失败使 acceptance incomplete，即使实际 kernel exit0 仍保留；checked input version unavailable 单独 version-unknown；其他诊断 unknown；无诊断 not_reported，均不证明审计完整。Dashboard health 不能替代内核审计写入证明。attempted 与 executed 分开：启动未完成不声称已执行；已完成但响应不可信只保留 exitCode、output=null 与固定校验状态，不回显原始输出。

中途独立审查三项已修：阶段参数缩写绕过（新阶段禁缩写、缩写/漏accept均64且无旧安装/内核调用）；旧 pin 的 Dashboard 能力 unknown 时不提供可执行 argv；进程启动/响应失败保留准确执行事实。自查补齐 capture 的 ConnectionProblem 到新工作流 JSON 错误归一。旧 default kernel tuple/JSON/退出码不变。

当前相关测试：staged 20 项（含 dry-run、build/retry、漂移、ignored YAML、实际观察产物、audit env、普通源码、每阶段前后门禁、诊断脱敏、硬违规安装有效、私有连接前置及失败执行事实）；Dashboard connection 24 项；旧 integration 97 项（96 passed + 1 opt-in skipped）。精确类执行避免导入基类时重复计数。证据 `/private/tmp/ftckb-staged-installation-final-python-20261023.log` 与 `/private/tmp/ftckb-staged-installation-legacy-tests-20261023.log`。unit 输出 `/private/tmp/ftckb-staged-installation-20261023/*-unit-fixture.json` 只用于核对文档结构，连接被 mock、内核为隔离fixture，不冒充真实端到端通过。

客户端连接复用有界私有文件、严格 loopback URL、禁止代理/重定向与 3 秒 socket I/O 超时；不是整个命令总时限。浏览器确认是操作者声明；identity 匹配不证明服务 binary pin。未知 connection-file 不输出假路径或不完整可执行 argv。旧 pin 能力不足需显式评估升级，不自动改 pin。

本轮保持所有变更本地未提交；未改 BioBuzz、实际机器人、真实知识源 pin，不声称 RC/DS 或真机通过。以上 unit 结果不替代真实演示。

## 独立真实隔离演示

新建临时 FTC Git 项目，固定已发布在仓库历史中的完整 `5b652e8111769bfe259234d8f99e14f62aa549db`，使用当前八个 wrapper 脚本的冻结快照；逐文件 SHA 与当前最终源一致。真实 prepare 构建 13.18 秒，未产生 kernel/audit 调用；冻结入口后幂等重跑摘要相同。目标固定知识为 48 条，与当前主库 52 条明确区分。

目标 Dashboard 55231 的 canonical knowledge 与隔离 audit 经过身份 API 和正常浏览器页面核验；页面已连接、48 条实载、仅 GET、无浏览器错误，token 从页面 URL 正常移除。之后正确 accept 退出 0；同摘要改用主库服务退出 2、零 kernel 且目标 audit 字节不变；目标普通 diff 故意触及 build.common.gradle 时退出 1，但 installationOk=true，摘要保持相同，真实 hard 保留、18 soft 全量返回。目标 HEAD/pin 未变，没有机器人编译、部署或硬件测试。

完整命令、输入摘要、浏览器证据与实际审计前后字节：`/private/tmp/ftckb-staged-real-independent-20261023-mo7z12gq/real-demo-results.json`、metadata.json、reviewed-wrapper/snapshot.json、dashboard-browser-evidence.json。独立审查报告 real-demo-review.md 逐项说明18soft，并实读6组started/finished（validate/resolve/check各两次，五个exit0、一个exit1），全部归属目标知识分区；这是本次实际记录的证据，不表示未来调用审计一定成功。私有连接文件/截图/运行产物均只留在 /tmp，不纳入仓库补丁。

## 整轮集成与规范检查范围

本轮从 HEAD `5b652e8111769bfe259234d8f99e14f62aa549db` 开始，累计包含 Command 指南/真实批准、新手文档、候选完整详情导出、Dashboard 原因标签与分阶段安装；没有 commit/push。此前 doctor/work receipt/连接核验已在 HEAD 之前提交，不冒充本轮新增。52 文件明确清单与逐文件 SHA、完整补丁位于 `/private/tmp/ftckb-final-integration-20261023/` 的 task-files.json、task.patch；不含原有 DS_Store、历史无关文档、dsh/media 或临时凭据/截图/构建/演示文件。

最终 Kotlin 去重 123/123：审批输入相关 59，检索/裁决/候选新旧兼容/Dashboard 补集 64；不重复累加审批前31或旧候选阶段66。最终 Python 为 141 项：旧 integration 97（96通过+1默认 opt-in 跳过）、staged20、Dashboard连接24；后两类最终44/44通过。普通沙箱最初拒绝本地HTTP fixture绑定，按正常权限重跑精确44类后通过；不是把受限运行当产品成功。未运行全仓库/IDE/Android或机器人构建。

候选导出独立复核：`/private/tmp/ftckb-candidate-review-export-20261023/reviewer-result.json`；真实6候选导出只读，批准后的Command ID返回not-candidate。Dashboard标签真实九规则浏览器收据：`/private/tmp/ftckb-rule-explanation-sfxw7iel/verification-evidence.json`。两feature各自normal check均0hard/18soft；整轮最终显式 `--diff task.patch` 的实际JSON、命令、退出码与补丁SHA另记 final目录的 check.json、check-receipt.json，不混入未授权本地目录。

以下是平台维护20827/2025-2026/generic/normal上下文的逐项Agent范围复核说明，不是对真实机器人上下文的裁决，也不冒充真人复核或写入Dashboard复核身份声明。最终soft ID集合必须与该表完全相同，否则收尾检查会失败。

| ruleId | 本轮判断 |
| --- | --- |
| global.constants-centralized | 未修改机器人调参；wrapper预算/接口常量集中定义，指南数值不冒充机器人配置。 |
| global.documentation-intent | 新增中文指南、安装阶段/失败恢复与状态说明；明确prepared、accepted、候选/批准及真机边界。 |
| global.hardware-container | 不适用：未修改机器人硬件引用、HardwareMap或硬件容器。 |
| global.motor-configuration | 不适用：未修改机器人电机初始化或运行代码。 |
| global.naming-conventions | 无机器人程序命名变更；Command指南要求复用现有接口而非强加vendor包名。 |
| global.telemetry-organization | 不适用：未修改机器人遥测。 |
| shared.dependency-verify-sync-build-run | 未新增FTC依赖；只验证KB相关构建/测试和隔离CLI，不宣称Android Sync、部署或真机通过。 |
| shared.ftc-sdk-separate-toolchain-versions | KB JVM工具链与目标FTC/Android版本明确分开，未将JDK测试视为Robot Controller兼容证据。 |
| shared.gobilda-identify-exact-sku | 不适用：无goBILDA产品或型号变更。 |
| shared.gobilda-separate-stall-and-operating-values | 不适用：无电机负载或堵转规格变更。 |
| shared.gobilda-servo-mode-and-pwm-range | 不适用：无舵机/PWM端点代码。 |
| shared.gobilda-use-output-shaft-encoder-resolution | 不适用：无编码器或传动换算。 |
| shared.limelight-back-up-before-os-update | 不适用：未升级LimelightOS。 |
| shared.limelight-configure-camera-pose | 不适用：无相机姿态/定位变更。 |
| shared.limelight-synchronize-pipeline-dependent-reads | 不适用：无pipeline切换/读取变更。 |
| shared.pedro-explicit-coordinate-conversion | 不适用：无坐标转换。 |
| shared.pedro-localization-before-follower | 不适用：无localizer/follower变更。 |
| shared.pedro-tune-current-robot | 不适用：未复制或验证机器人调参值。 |
