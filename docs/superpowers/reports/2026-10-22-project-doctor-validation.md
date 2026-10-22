# Project doctor validation — 2026-10-22

本轮仅新增只读项目诊断与统一 Agent 工作流；不改 knowledge、kernel JSON v2、接入协议、旧 resolve/check/verify 行为或项目 pin。没有 commit/push。

## 验证命令与证据

- `PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s tests/integration -p test_integration.py -v`：70 tests：69通过、1项opt-in跳过、0失败（38.764s；`/private/tmp/ftckb-doctor-integration-final.log`）；真实 Gradle 重型用例保持 opt-in，不下载 IDE、不运行全仓库 Kotlin 测试。
- DoctorTest 25 项在最终 suite 通过；覆盖独立配置错误、v1/v2/profile、缺失/坏 JSON/重复键、根目录、pin/gitlink/dirty、托管摘要/symlink、工具超时/环境、JSON 参数错误、正向退出0与静态能力边界。
- `ftckb validate knowledge --json`：exit0，schemaVersion2，ok=true，48条。
- `ftckb resolve knowledge --team 20827 --season 2025-2026 --generic-profile --json`：exit0，schemaVersion2，ok=true，26 active，0 conflicts。
- `ftckb check . --knowledge knowledge --team 20827 --season 2025-2026 --generic-profile --diff /private/tmp/ftckb-doctor-20261022-task.patch --json`：normal，任务补丁（包括新helper/本报告）exit0，ok=true，0 hard、18 soft。此QA上下文用于本知识库维护，不冒充用户机器人配置。
- `git diff --check`：通过。

真实 CLI 证据：`/private/tmp/ftckb-doctor-20261022-{validate,resolve,check}-final.json`。初次沙箱内 validate/resolve 的 kernel 成功但默认审计写入受限；已获准提权按同一默认审计目录重跑，无审计错误。沿用已连接 Dashboard，不重启、不记录 token。doctor 自身不调用内核或写 audit。

任务补丁明确包含两模板、接入Skill、project.py、新project_doctor.py、README、project-integration、集成测试、本次2026-10-22设计/实施计划和本报告；保留并排除原有无关 untracked（.DS_Store、其他历史 specs/plans、dsh-client-ui-tokyo-night-dsh、media）。报告只证明此任务补丁检查，不把排除项声称为通过。

## 只读隔离演示

`/private/tmp/ftckb-doctor-demo-20261022/evidence.json` 保存实际 argv/cwd、退出码与逐阶段摘要，旁边保留两份 JSON 和每阶段前后全目录 SHA256 快照（含 `.git`）。

1. 临时 FTC Git fixture 配置同时有 team/season/profiles/commit 四错：doctor exit1，四项独立失败均出现。
2. 只修复 fixture 配置后重跑：上述四项通过；真实发行库缺失仍报 `cli.distribution` failed，exit1，不为演示伪造全绿。
3. 两次运行各自的 before/after snapshotEqual=true。fsmonitor、构建和 kernel 哨兵回归也未触发；工具 runner 限定只读 Git 查询，禁网络协议、交互及环境注入、限时。

fixture 的安装准备复用现有 fake build/kernel 测试设施；doctor 本身没有执行这些脚本。单独正向静态 fixture 可返回0，但仍保留 provenance unknown、Dashboard连接/resolve/check/硬件 not_checked。以上不是真实构建、规则或真机通过证据。

## Soft 逐项复核

以下18项来自真实 normal check；是执行 Agent 对本次范围的复核说明，没有声称真人复核，不向 Dashboard 写独立复核收据，也不改变原 soft 类型。

| ruleId | 本轮判断 |
| --- | --- |
| `global.constants-centralized` | 已核对：doctor 的超时集中为 TIMEOUT，路径复用现有常量；无机器人调参。 |
| `global.documentation-intent` | 已核对：helper/安全 runner/静态证据均有用途与边界说明，生成流程为中文。 |
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

## 真实限制与交付状态

静态发行文件/receipt 无法证明所有产物来源或完整运行；证据不足保留 unknown。Dashboard 能力只在固定、干净且元数据/协议均核验的源码出现可靠正证据时提供启动建议，连接必须另行确认。旧 pin/未初始化项目使用外部已审阅 doctor 绝对入口，不改目标 pin。未验证 Windows 原生、Android 部署、Robot Controller/Driver Station 或硬件。

最终 Python suite：70 tests，69通过、1项重型opt-in跳过、0失败。最终任务补丁覆盖上述11个文件（含本次设计/计划/报告）；确定性 check 退出0，0 hard、18 soft。独立代码 reviewer 已复验并关闭两项 P2，无剩余 P1/P2。修正包括：遵循 JAVA_HOME 的工具选择、拒绝目标内工具/根失败时阻止执行探测；主 knowledge-cli.jar 普通文件检查、Windows 缺产物时正确建议路径，以及 verify 前先连接 Dashboard 的指引。默认环境下独立复验内外两种入口，109文件含.git/submodule hash不变、无pycache；另一次独立命令体验收据为 `/private/tmp/ftckb-doctor-independent-20261022/evidence.json`，未执行建议命令。
