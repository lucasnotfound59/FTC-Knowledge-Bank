# Dashboard connection verification — 2026-10-23

本轮实现显式既有Dashboard连接核验，未commit/push。HEAD仍为已推送doctor的c2fce467cbc53ecf9d22aeff23ab318b76c75005。前轮work receipt的未提交成果叠加保留，不把前轮工作算成本轮新增。

## 范围与前置

前轮基线完整副本/逐文件hash/mode/patch/status：`/private/tmp/ftckb-dashboard-entry-baseline-20261022`。本轮delta为14文件，累计未提交为19文件；清单/双patch SHA见`/private/tmp/ftckb-dashboard-20261023-task-files.json`。前轮work helper、正式integration测试、设计、计划和报告5文件逐字节未改；另6个入口/文档文件在原成果上增加本轮说明。所有原无关untracked保留，不reset/stash/stage。

开始时复用用户60284/session74128，authenticated summary确认actualknowledge48/defaultaudit；正式平台维护QA resolve退出0，收据在baseline目录/resolve.json。本轮更新JAR后旧进程仍为旧构建，不假定热升级，不停止或覆盖用户服务。

## 实现

`project.py dashboard verify --project ROOT --connection-file PRIVATE_JSON` 只读取当前用户私有文件并GET显式已知服务。不扫描端口、不spawn/kill、不build/执行kernel/改pin，不保存token。唯一输入字段url包含本次完整fragment-token链接；8KiB上限、POSIX ownership/私有mode/type/symlink检查，其他无法验证隐私的平台unknown。仅字面127.0.0.1 HTTP根地址，拒绝query/userinfo/redirect/proxy。

独立Python报告dashboardConnectionSchemaVersion1，exit0为必需API身份匹配，exit1为失败/未知，exit64为参数错误。browser.connection和kernel.defaultAudit仍not_checked，server.binaryPin仍unknown，不当成浏览器步骤或binary来源通过。nextSteps使用实际当前外部wrapper绝对路径，workflow-env显式固定已核验FTCKB_AUDIT_DIR；同一env也必须用于后续收据返回的check argv，不凭空造UUID或改变旧work契约。

新受token/Host/Origin保护的GET `/api/dashboard/identity` 属于Dashboard API v1、identitySchemaVersion1。knowledge root/version/fileDigests/valid/ruleCount来自同一次KnowledgeWorkspace snapshot；不在Python复写JVM版本framing，不解码YAML做裁决。audit root/partition来自实际FileAuditStore.partitionPath及parent，覆盖构造注入；其它store为unknown而不猜env。既有summary/kernel/doctor/work协议不变。

## 实际验证

- Kotlin先红：DashboardServerTest17项中新增3项失败（route未存在），API落盘后17/17通过。
- 最终 `./gradlew :apps:knowledge-cli:test --tests org.ftckb.cli.DashboardServerTest :apps:knowledge-cli:installDist --console=plain` 成功；17 tests、0 failures/errors/skips。log `/private/tmp/ftckb-dashboard-kotlin-final.log`，XML/main JAR SHA及source SHA为`/private/tmp/ftckb-dashboard-build-evidence-20261023.json`。只跑相关CLI类，不全仓库/Android/IDE；Gradle串行。
- 最终Python相关组合57/57通过，0失败/跳过，47.864s；log `/private/tmp/ftckb-dashboard-python-final.log`。精确命令：

```bash
PYTHONDONTWRITEBYTECODE=1 \
PYTHONPATH=tests/integration:.agents/skills/ftckb-integrate/scripts \
python3 -m unittest \
  test_dashboard_connection.DashboardConnectionTest \
  test_integration.WorkReceiptTest \
  test_integration.IntegrationContractTest \
  test_integration.IntegrationTest.test_wrapper_returns_kernel_json_without_plan_noise \
  test_integration.IntegrationTest.test_project_adapter_forwards_explicit_work_modes_without_touching_config \
  test_integration.DoctorTest.test_templates_have_doctor_dashboard_resolve_order -v
```

覆盖匹配、错库/输入/无效状态、实际audit root/partition/health/unknown、相对env/缺失后缀/默认home/不展开~、private owner/mode/symlink/FIFO/重复键/大小/URL边界、HTTP401/旧API/字段类型/坏JSON/超大/截断/redirect/proxy/I/O timeout、本地漂移、ignored Unicode YAML、无kernel/build/metadata写入与安全Git。测试HTTP服务/fake安装设施仅用于边界，不冒充真实kernel或服务binary证明。

## 独立真实演示

`/private/tmp/ftckb-dashboard-connection-real-demo-20261023/evidence.json` 与同目录README.md保存精确argv、结果、身份与快照。实际使用本轮新installDist，仅显式启动本轮demo服务，五场景：

1. 正确fixture+audit：exit0，必需项passed，API返回48规则/12输入摘要。
2. 仅expected audit改变：exit1/audit-mismatch。
3. 不同固定真实项目的knowledge路径：exit1/knowledge-mismatch。
4. 同格式合法长度错token：真实401，exit1/unauthorized，输出无token。
5. 用户旧60284进程：exit1/unsupported-api，authenticated unknown，不全绿，旧服务未动。

项目含.git/knowledge、隔离audit、alternate fixture的内容及mode快照均不变；GET不写events/reviews/rules。正常Playwright页面另行确认已连接正确fixture知识库48条、token从地址栏移除、仅GET，浏览器前后audit快照一致；截图demo-page-connected.png已人工确认无token。仅停止本轮demo PID22203，保留用户60284。private-*凭据文件0600留在/tmp，不进入报告/patch/仓库。

## 本轮delta与累计规范检查

两个补丁分别是相对前轮work基线的 `/private/tmp/ftckb-dashboard-20261023-delta.patch` 和相对HEAD累计的 `/private/tmp/ftckb-dashboard-20261023-cumulative.patch`。实际normal check都使用平台维护20827/2025-2026/generic，不冒充用户机器人配置；JSON分别为`/private/tmp/ftckb-dashboard-20261023-check-delta.json`和`...-check-cumulative.json`。

两次实际normal check均为schemaVersion2、exit0、ok=true、0hard/18soft；soft原文完全一致并在下表逐项说明，git diff --check通过。

补丁排除原有无关DS_Store/历史plans与specs/dsh-client-ui-tokyo-night-dsh/media，不声称这些内容通过。编译产物、临时服务/私有连接/截图/测试运行收据均不入补丁。git diff --check另行核对。

## Soft逐项Agent复核

以下是任务范围内的Agent复核说明，没有声称真人复核，不向Dashboard写独立review声明。本轮没有FTC依赖或机器人硬件代码变化；18项常规soft逐项如下：

| ruleId | 本轮判断 |
| --- | --- |
| `global.constants-centralized` | 已核对：连接文件/响应上限与I/O timeout集中定义；安全Git复用既有限时runner，无机器人调参。 |
| `global.documentation-intent` | 已核对：helper/实际store身份/只读边界和非总deadline限制均有说明，生成流程为中文。 |
| `global.hardware-container` | 本次不适用：未修改硬件容器或 HardwareMap。 |
| `global.motor-configuration` | 本次不适用：没有电机初始化或运行代码。 |
| `global.naming-conventions` | 本次不适用机器人命名；Python helper 沿用接入脚本命名习惯。 |
| `global.telemetry-organization` | 本次不适用：未修改机器人遥测。 |
| `shared.dependency-verify-sync-build-run` | 本次不适用：未新增或更改 FTC 库，未声明部署/运行通过。 |
| `shared.ftc-sdk-separate-toolchain-versions` | 已核对范围：仅诊断本地 JDK 前提，不推断 FTC SDK、Android SDK 或 sourceCompatibility。 |
| `shared.gobilda-identify-exact-sku` | 本次不适用：无 goBILDA 产品或规格变更。 |
| `shared.gobilda-separate-stall-and-operating-values` | 本次不适用：无电机负载/堵转规格。 |
| `shared.gobilda-servo-mode-and-pwm-range` | 本次不适用：无舵机/PWM 代码。 |
| `shared.gobilda-use-output-shaft-encoder-resolution` | 本次不适用：无编码器/传动换算。 |
| `shared.limelight-back-up-before-os-update` | 本次不适用：未升级 LimelightOS。 |
| `shared.limelight-configure-camera-pose` | 本次不适用：无定位/相机姿态代码。 |
| `shared.limelight-synchronize-pipeline-dependent-reads` | 本次不适用：无 pipeline 切换/读取。 |
| `shared.pedro-explicit-coordinate-conversion` | 本次不适用：无坐标变换代码。 |
| `shared.pedro-localization-before-follower` | 本次不适用：无 localizer/follower 变更。 |
| `shared.pedro-tune-current-robot` | 本次不适用：无调参值或真机验证声明。 |

## 审查与限制

独立review发现深层JSON RecursionError可能破坏单JSON输出的P2，已在私有输入、HTTP JSON及本地capture前后边界归一成固定安全错误并加入回归。reviewer已用实际200001-byte深JSON与本地异常复验；旧wrapper默认错误契约未改。最终57项相关Python、17项Kotlin/API与独立真实证据已被reviewer核对，核心审查通过，无剩余P1/P2。

**3秒是socket I/O超时，不是整个命令总时限。** 持续滴入字节可能使整体核验超过3秒；本轮按确认scope精确说明此行为，不新增线程/deadline机制。响应仍受1MiB上限约束。前后快照不是抵御同权限ABA的事务；API身份不是签名/身份系统，不证明服务binary pin/来源、规则语义/代码合规或RC/DS/硬件。Python home只是本工具选定的期望，不证明无显式env的kernel JVM user.home。Windows原生隐私/执行、部署和真机未验证。
