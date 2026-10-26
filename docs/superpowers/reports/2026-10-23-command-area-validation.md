# Command 架构专区与新手导航：候选阶段集成验收

日期：2026-10-23。基线HEAD `5b652e8111769bfe259234d8f99e14f62aa549db`。本批仅本地更新，未commit/push，不触碰BioBuzz、不更改已接入项目pin、不批准真实候选。该报告记录A/B/C/D共同产物的本次候选阶段快照，不覆盖planner进度报告。

## 产物及ownership

- A owner新增 `knowledge/shared/command-framework.yaml` 四条shared候选、`knowledge/guides/architecture/command-based.md`，修订旧FTCLib指南，新增知识/standardizer测试。
- B owner仅在生成runtimeSkill添加显式profile下的固定指南发现入口；候选/被赛季排除不算active，自有框架不强制FTCLib运行依赖，不自动切profile/pin。原managedFiles生成/校验保持，未覆写机器人项目Skill。
- 真实检索通过，未修改KnowledgeRetriever实现或预算；新增回归验证Command-Based、FTCLib、类 FTCLib、自有实现四query命中 `guides/architecture/command-based.md`，返回仍不超过2 sections。
- C owner完成新手四路径指南/README入口/接入与Dashboard回链，42项本地链接及锚点已核验；当前快照计数同步。
- D从真实CLI stdout生成3个受影响fixture：validate-ok、resolve-ok、resolve-test；独立合成冲突/error/check fixtures未改。AGENTS/kernel-contract当前快照更新，CLI验收仅同步真实fixture数量及四候选status排除断言，任意序列化输入48未改。

## 实际规则状态与矩阵

真实validate：schemaVersion2、exit0、ok=true、52规则、0 violations；实际Dashboard catalog为42 approved、10 candidate。新增四条均candidate且无approval：

- shared.command-runtime-boundary
- shared.command-scheduler-lifecycle
- shared.command-default-resumption
- shared.command-scheduler-reentrancy

candidate YAML SHA256为 `7a6f6fee1b913e0f2c6de92a7954d2039a995379ef9b6a3acd99e3e01c4127e1`。该快照未激活新规则；真实激活仍须本轮人类明确批准人和overall_software_lead声明，不从长期目标或旧YAML借身份。

主知识库矩阵20次：team={20827,20287} × season={2025-2026,2026-2027} × profile={generic,command-based,ftclib-command,simple-opmode,rookiebot}。全部schema2、exit0、ok=true、conflicts=[]、stderr为空；profiles规范化及active排序正确；四候选均在excludedRules且含status原因，没有进入active。新YAML输入在整轮矩阵前后hash不变。

两队计数相同：

| season | generic | command-based | ftclib-command | simple-opmode | rookiebot |
| --- | ---: | ---: | ---: | ---: | ---: |
| 2025-2026 | 26 | 29 | 30 | 26 | 38 |
| 2026-2027 | 17 | 17 | 17 | 17 | 29 |

20组active IDs均等于旧基线。原planner的14份实际收据直接复用作对照；其余6组对照由同CLI加载/tmp内从已提交5b652e8抽出的原始YAML字节得到，单独历史知识分区审计，不把这6次称为主知识库新输入覆盖。没有人工编辑成功JSON。

精确argv、每份stdout/stderr、旧对照与输入hash、dashboard身份/状态计数：`/private/tmp/ftckb-command-area-validation-20261023/`，汇总 `matrix-summary.json`、`validate.json`、`dashboard.json`。主Dashboard60284/session42681实时目录已只GET刷新为52；正常Playwright页面已连接正确canonical knowledge、52、token从地址栏移除，证据page.json。未停止服务或写规则/复核记录。

## 相关测试与构建

首轮Kotlin组合28 tests全部通过，0fail/0skip：

- CommandBasedKnowledgeTest：3
- CommandFrameworkReviewTest：4
- KnowledgeRetrieverTest：8
- KernelJsonAcceptanceTest：13

```bash
./gradlew :modules:knowledge:test --tests org.ftckb.knowledge.CommandBasedKnowledgeTest \
  :modules:standardizer:test --tests org.ftckb.standardizer.CommandFrameworkReviewTest \
  :modules:agent-runtime:test --tests org.ftckb.agent.KnowledgeRetrieverTest \
  :apps:knowledge-cli:test --tests org.ftckb.cli.KernelJsonAcceptanceTest \
  :apps:knowledge-cli:installDist --console=plain
```

log：`/private/tmp/ftckb-command-area-final-kotlin-20261023.log`；精确JUnit计数 `tests.json`。A单元中合成批准只在隔离对象，不写回真实YAML，不冒充本批审批；新增soft无hard，删除/改名/无关新增等实际边界已由测试核对。没有扩大真实test模式豁免。

Python模板安装/重跑保留/升级/人工修改保护及旧wrapper相关5 tests全部通过（4.283s）：

```bash
PYTHONDONTWRITEBYTECODE=1 \
PYTHONPATH=tests/integration:.agents/skills/ftckb-integrate/scripts \
python3 -m unittest \
  test_integration.IntegrationTest.test_install_repeat_preserves_user_files_index_head_and_branch \
  test_integration.IntegrationTest.test_explicit_upgrade_changes_pin_and_templates \
  test_integration.IntegrationTest.test_edited_managed_files_are_not_overwritten \
  test_integration.IntegrationTest.test_wrapper_returns_kernel_json_without_plan_noise \
  test_integration.DoctorTest.test_templates_have_doctor_dashboard_resolve_order -v
```

log：`/private/tmp/ftckb-command-area-template-tests-20261023.log`。生产Kotlin未变，只串行相关Gradle与必要installDist，不跑全仓库/IDE/Android/机器人构建。

独立review补充发现4类正式验收仍使用旧知识总数，已仅同步真实52/42+10；FTCLib guide的来源标题按vendor第一方语义核对，未改回含混official。补充4类38/38通过，0失败/跳过：CliDocumentationAcceptanceTest、KnowledgeGuideAcceptanceTest、TeamChinaRuleMigrationAcceptanceTest、VendorDependencyRuleAcceptanceTest。log `/private/tmp/ftckb-command-area-extra-acceptance-20261023.log`、精确各类计数extra-tests.json。与首轮28项没有重复测试类，第一批合计66项不同Kotlin测试及原5项Python均通过。仅相关类，没有全仓回归。

## 本批规范检查范围

明确allowlist包括本批三指南/新手页、四候选、模板、导航、当前计数、三个真实fixture、相关测试和本批spec/plan/progress/本报告。reviewer另核6份现行公开文档仍漏旧计数，已同步52/42+10（不改历史报告），resolution的数量明确仅指2025-2026。实际normal check为schema2、exit0、ok=true、0hard/18soft；git diff --check通过。

原先DS_Store、历史无关docs、dsh-client-ui-tokyo-night-dsh、media，以及下一批staged-installation草案排除，不宣称其通过。完整patch与清单在 `/private/tmp/ftckb-command-area-validation-20261023/task.patch`、`task-files.json`。

实际normal check使用平台维护20827/2025-2026/generic，不冒充用户机器人上下文；JSON为check.json。soft下表是本次范围的Agent复核说明，不宣称真人复核，不写Dashboard review声明。

| ruleId | 本批判断 |
| --- | --- |
| global.constants-centralized | 本批无机器人调参；指南数值仅用于讲解，不是已验证机器人配置。 |
| global.documentation-intent | 中文指南说明职责、实时输入、default恢复、cleanup及候选/赛季边界。 |
| global.hardware-container | 本次不适用：没有修改实际硬件引用或HardwareMap。 |
| global.motor-configuration | 本次不适用：没有电机初始化/运行代码变更。 |
| global.naming-conventions | 本次不适用机器人程序命名；伪代码接口明确映射项目现有实现，不强加包名/基类。 |
| global.telemetry-organization | 本次不适用：没有修改机器人遥测。 |
| shared.dependency-verify-sync-build-run | 未添加FTC依赖，自有框架不要求安装FTCLib；未声称Sync/部署/真机通过。 |
| shared.ftc-sdk-separate-toolchain-versions | 仅KB相关JVM测试/CLI，没有把KB JDK当机器人FTC/Android SDK验证。 |
| shared.gobilda-identify-exact-sku | 本次不适用：无goBILDA产品/规格变更。 |
| shared.gobilda-separate-stall-and-operating-values | 本次不适用：无电机负载或堵转规格。 |
| shared.gobilda-servo-mode-and-pwm-range | 本次不适用：无舵机/PWM端点代码。 |
| shared.gobilda-use-output-shaft-encoder-resolution | 本次不适用：无编码器/传动换算。 |
| shared.limelight-back-up-before-os-update | 本次不适用：没有升级LimelightOS。 |
| shared.limelight-configure-camera-pose | 本次不适用：无相机姿态/定位代码。 |
| shared.limelight-synchronize-pipeline-dependent-reads | 本次不适用：无pipeline切换/读取。 |
| shared.pedro-explicit-coordinate-conversion | 本次不适用：无坐标变换。 |
| shared.pedro-localization-before-follower | 本次不适用：无localizer/follower变更。 |
| shared.pedro-tune-current-robot | 本次不适用：未复制或验证机器人调参值。 |

## 独立审查与未完成边界

Astra已审A的真实四candidate、两指南及测试源码；default/reset/cancelAll与Dashboard前置P2已由A修复，候选正文hash固定。规则触发只是请求soft复核，未命中不证明安全，不把关键词当控制流证明。B/D的20组矩阵、3份真实fixtures、28项Kotlin已被独立reviewer复核通过；没有真实审批前不能宣称命令专区新规则已启用。

候选审批卡片只在 `/private/tmp/ftckb-command-rule-approval-cards-20261023.md`，正文/全赛季/command-based范围与最终YAML一致，由root向用户收集真实声明。不自动commit/push，不写memory。没有RC/DS部署、真机或FTCLib对当前FTC SDK兼容性声明；只核验所引用固定第一方源码，不把vendor当FIRST official。
