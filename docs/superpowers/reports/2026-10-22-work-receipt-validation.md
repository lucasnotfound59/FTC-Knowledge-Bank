# Work receipt validation — 2026-10-22

基线 `c2fce467cbc53ecf9d22aeff23ab318b76c75005`（doctor已提交推送）。本轮新feature仅本地更新，未 commit/push；不改真实knowledge、项目配置/pin、kernel JSON v2或audit结构。

## 实现与命令

`project.py work resolve --project ROOT` 返回独立 `workReceiptSchemaVersion:1` envelope、UUID收据和完整 `nextSteps.cwd/argv`；`work check --project ROOT --receipt UUID` 显式关联。原 validate/resolve/check/doctor/verify 默认路径保持兼容。

收据绑定canonical root/gitDir、配置原字节、明确上下文/协议/profile、pin、实际knowledgePath和全部小写yaml/yml输入（含ignored规则）以及workMode。mode省略仍为normal，不从收据默选test/dev；dev check须diff。普通机器人代码修改/提交、重复check不使收据过期，不设任意TTL。调用前后重验，supplied diff也核对字节；失败时保留已执行的实际kernel事实。

存储仅使用Git实际metadata的固定子目录与UUID文件，POSIX目录0700/文件0600，独占临时文件+fsync+不覆盖的原子发布，拒绝symlink/非普通文件/坏摘要等。自定义gitDir在工作区内且不在.git子树时明确拒绝，不修改ignore或迁移数据；linked worktree使用其独立外部metadata。新入口不自动构建/联网，缺产物/build receipt返回setup-required。

## 针对性回归

最终执行以下相关组合，共38 tests，38通过、0失败、0跳过，29.821s。日志：`/private/tmp/ftckb-work-final-targeted.log`。不重复全仓库Kotlin/Android/IDE回归。

```bash
PYTHONDONTWRITEBYTECODE=1 \
PYTHONPATH=tests/integration:.agents/skills/ftckb-integrate/scripts \
python3 -m unittest \
  test_integration.WorkReceiptTest \
  test_integration.IntegrationContractTest \
  test_integration.IntegrationTest.test_wrapper_returns_kernel_json_without_plan_noise \
  test_integration.IntegrationTest.test_project_adapter_forwards_explicit_work_modes_without_touching_config \
  test_integration.IntegrationTest.test_kernel_omits_work_mode_by_default_and_requires_the_echoed_mode \
  test_integration.IntegrationTest.test_project_adapter_refuses_dev_without_diff_and_validate_with_work_mode \
  test_integration.IntegrationTest.test_install_repeat_preserves_user_files_index_head_and_branch \
  test_integration.IntegrationTest.test_explicit_upgrade_changes_pin_and_templates \
  test_integration.IntegrationTest.test_existing_v1_pin_is_retained_without_implicit_upgrade \
  test_integration.DoctorTest.test_templates_have_doctor_dashboard_resolve_order -v
```

涵盖：创建/重复check/两进程独立UUID、正常代码修改、配置格式/team/season/profile变化、错误协议/pin/path、ignored YAML增改删、知识symlink/FIFO、mode/dev diff、v1、坏/缺失/重复键/超大/篡改/不支持收据、metadata/file symlink、FIFO读取不阻塞、发布/fsync失败清理、不覆盖UUID、root迁移、调用中context/diff漂移、实际hard1/soft、旧默认输出/exit以及不自动build。测试准备复用原有fake build/kernel设施，不将其当真实内核验收。

## 独立真实CLI隔离演示

根目录：`/private/tmp/ftckb-work-receipt-real-demo-20261022/`。`setup.json`、`offline-build.log`记录临时固定submodule在pin c2fce46的真实离线installDist（19s，25 tasks，exit0），成功后建立build receipt；JAR hash有记录。没有fakekernel，没有联网下载，没有修改主仓库真实knowledge/audit。

`evidence.json`、`README.md`保存精确argv/cwd、返回JSON和文件事实：

1. 真实work resolve退出0，创建UUID。
2. 仅新增fixture的机器人工具代码，原UUID真实work check退出0/matched，0hard/18soft。
3. 仅把fixture队号20827改为16093，原UUIDcheck退出2/context-changed，changedFields为configSha256和team；kernel=null，隔离audit字节完全不变。
4. 重新resolve退出0且新UUID不同。真实audit共2次resolve+1次check，拒绝路径未执行kernel；这不是猜配历史audit。
5. 收据位于真实.git/ftckb/receipts，0700/0600，不进入workspace diff，check不改写收据。work调用前后真实JAR/stamp hash和mtime一致，没有触发build。

18条真实soft原note与演示适用性另见同目录`soft-applicability.json`。review修复后以定向回归确认；按root已验收的范围，无相关真实CLI输入变化，不重复构建或真实演示。

## 真实知识库检查与范围

开始前实际 `ftckb resolve knowledge --team 20827 --season 2025-2026 --generic-profile --json` 成功，收据`/private/tmp/ftckb-work-20261022-resolve.json`。最终normal完整任务补丁check使用相同平台维护QA上下文（不是用户机器人配置），包括新helper、project.py路由、两模板、接入Skill、README、project-integration、tests、本次spec/plan/report共11文件。

不暂存、不提交本轮文件；原有无关untracked保留。patch排除历史plans/specs、.DS_Store、dsh-client-ui-tokyo-night-dsh、media，不将排除项声称为通过。最终实际check：schemaVersion2，exit0，ok=true，0hard、18soft，与下方逐项说明完全一致；`git diff --check`通过。完整清单和SHA为`/private/tmp/ftckb-work-20261022-task-files.json`，实际kernel JSON为`/private/tmp/ftckb-work-20261022-check-final.json`；git diff --check也执行。

## Soft逐项Agent复核

以下是本任务范围的Agent复核说明，没有声称真人复核，也没有向Dashboard写独立review声明。工作收据是Python接入层变化，无新增FTC依赖/机器人硬件代码；实际check的18项soft逐项列出：

| ruleId | 本轮判断 |
| --- | --- |
| `global.constants-centralized` | 已核对：收据读取上限集中为 LIMIT，安全Git限时复用既有 TIMEOUT；无机器人调参。 |
| `global.documentation-intent` | 已核对：helper/context/metadata存储、模式和失效边界均有说明，生成流程为中文。 |
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

## 独立审查与边界

独立review发现并修复两项P2：flags在子命令前时envelope必须使用解析后的实际command；特殊separate-git-dir若位于可见工作区必须拒绝。独立reviewer亲自运行上述三项精确回归，3/3通过，已关闭两项P2，无剩余P1/P2；并核对38项相关测试与真实演示证据。

摘要不是签名/身份/审批/语义或真机保证；同一用户可以改写并重算，前后快照也不是抵御同权限ABA的事务。未改变audit配对规则，不证明外部Agent全部行为。Windows原生运行、机器人部署和硬件未验证。
