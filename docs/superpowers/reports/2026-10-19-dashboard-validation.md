# Dashboard 验收记录

日期：2026-10-19。最终安装包的本地 HTTP / Chromium 验收通过；真实知识库保持 48 条（42 approved、6 candidate），未修改真实规则、未 commit/push。本记录对应本轮源码和安装产物，不代表真机、Robot Controller 或 Driver Station 验证。

## 验证策略与软件收据

按用户要求复用改动前基线，仅运行受影响测试和关键流程。以下收据存在交集，**不得相加为最终全量测试数**。后续窄测试会覆盖 Gradle XML 目录，旧 XML 也不能当作最终全量通过。

| 范围 | 本轮通过收据 | 证据 |
| --- | --- | --- |
| 改动前基线 | 482 tests，0 failure/error/skipped | `/private/tmp/ftckb-dashboard-baseline-rerun.log`；改动前记录，非最终源码全量结果 |
| 审计存储 | `FileAuditStoreTest` 16 | `modules/audit/build/test-results/test/TEST-org.ftckb.audit.FileAuditStoreTest.xml` |
| 知识库事务 | `KnowledgeWorkspaceTest` 15 | `modules/knowledge/build/test-results/test/TEST-org.ftckb.knowledge.KnowledgeWorkspaceTest.xml`；`/private/tmp/ftckb-dashboard-knowledge-final-targeted.log` |
| Agent 写入保护 | 负责人受影响类收据 71 | `/private/tmp/ftckb-dashboard-C-targeted.log`；包含最终新增 observer 回归的单方法窄收据，不能重复计数 |
| HTTP / 启动 | 16（2 launcher、14 API） | `/private/tmp/ftckb-dashboard-server-tests-3.log` |
| Session / chat / serve | 负责人受影响类收据 53 | `/private/tmp/ftckb-dashboard-E-targeted-4.log`；最终 `RequestAuditTest` 5 和 observer 单方法 1 再次窄验收见 `/private/tmp/ftckb-dashboard-E-final-narrow.log` |
| Kernel 审计与兼容 | 新增 14、受影响既有 41 | `/private/tmp/ftckb-dashboard-kernel-targeted-retry.log`；最终 XML 保留新增 14 |
| Kernel 输出矩阵 | 原 CLI stdout/exit 32 案例字节比较通过；最终受影响子集 10 案例通过 | `/private/tmp/ftckb-kernel-audit-baseline/comparison.json`、`final-comparison.json`、`final-audit-summary.json` |
| 最终资源打包 | installDist 成功，源码与 JAR 内 app.js 摘要相同 | `/private/tmp/ftckb-dashboard-resource-install.log`；SHA-256 `42e44c5dc785fc02918861c8fe1d2d0a1129b8e89faa7519cc2662dd5ea349c5` |

构建使用 Android Studio JBR 21，离线缓存 Gradle 9.4.0。基线之后没有重复执行整个基线命令。最终资源修正只重新打包，并窄验收受影响界面。

## 独立 HTTP 与真实浏览器验收

使用已安装 `ftckb dashboard --knowledge <临时副本> --no-browser`，注入临时 `FTCKB_AUDIT_DIR`，通过 bundled Node Playwright / Chromium 151 访问真实本地服务。所有写入位于 `/private/tmp/ftckb-dashboard-qa-h4VuhE/knowledge`。主脚本 `/private/tmp/ftckb-dashboard-qa.cjs` 的 16 个流程组通过；补充窄脚本 `/private/tmp/ftckb-dashboard-qa-narrow.cjs` 的模板、拒绝与翻页通过。

- 安全：缺 bearer 为 401；外部 Origin、实际错误 Host 为 403；错误方法为 405。反复 summary/rules/audit 读取没有新增审计。
- 规则事务：新增、批准、已批准实质编辑转 candidate 并清 approval；坏 YAML preview 为 422 且原字节不变；外部修改后的 apply 为 409 且外部字节不丢失；外部非法 YAML 立即标记无效，移除后恢复；声明角色后的候选拒绝成功。
- 确定性检测：明确 generic / 20827 / 2025-2026；实际 resolver 返回 26 active；指定 patch 实际命中 hard 1、soft 19、dev 19，分别拦截、保留待复核、保留降级提醒。数量是测试副本的该次上下文结果，不是对产品全部调用的合规统计。
- 独立 CLI 进程：validate、resolve、hard、soft、dev、缺参数分别返回 0/0/1/0/0/64，stdout 可解析为原 kernel JSON；相应记录出现在同一临时知识库的 dashboard。
- 审计：API cursor 翻页无重复；hard 的 standardizer 为 blocked；resolve 的 diff 为 not_applicable；UI 下一页和上一页实际切换记录。
- UI：搜索、状态筛选、规则详情；`<img src=x onerror=alert(1)>` 仅显示为文本，无图片插入或 alert；启动 fragment 随即移除；未选完整上下文不能裁决；真实硬拦截显示；键盘预览/保存；编辑草稿后旧预览失效；过期保存保留草稿并展示最新源文件作比较。
- 实时/布局：浏览器实际断网后保留旧数据并显示过期，恢复网络后连接状态恢复；390px 下 document scrollWidth=innerWidth=390；浏览器 pageerror 为 0。

发现并解决的集成问题：早期安装包未含最新候选模板，仍带 codec 不接受的 `reviewTriggers: []`。重新打包后，模板省略该字段，明确提示必须补充真实证据；空 evidence 的 preview 返回 200、`canApply:false` 和 evidence 字段错误，保存按钮禁用。没有放松校验器。

机器可读结果：`/private/tmp/ftckb-dashboard-qa-h4VuhE/report.json`、`narrow-report.json`。这些路径和下列截图是本机临时验收工件，未提交到仓库。

| 截图 | 路径 |
| --- | --- |
| 真实 48 条规则首页 | `/private/tmp/ftckb-dashboard-qa-h4VuhE/actual-library-overview.png` |
| 临时副本首页 | `/private/tmp/ftckb-dashboard-qa-h4VuhE/desktop-overview.png` |
| 实际硬拦截 | `/private/tmp/ftckb-dashboard-qa-h4VuhE/desktop-blocked-check.png` |
| 过期草稿与源文件对照 | `/private/tmp/ftckb-dashboard-qa-h4VuhE/desktop-stale-draft.png` |
| 调用阶段详情 | `/private/tmp/ftckb-dashboard-qa-h4VuhE/desktop-audit.png` |
| 断连提示 | `/private/tmp/ftckb-dashboard-qa-h4VuhE/desktop-disconnected.png` |
| 390px 规则页 | `/private/tmp/ftckb-dashboard-qa-h4VuhE/mobile-rules.png` |
| 最终候选模板校验 | `/private/tmp/ftckb-dashboard-qa-h4VuhE/desktop-template-validation.png` |

上述首页、过期编辑器、审计、手机布局及最终模板截图已人工视觉检查，文字/控件可辨，没有页面水平裁切。

## 用户启动实例与覆盖边界

验收后已为用户启动真实根 `/Users/xinlu/Documents/ChatGPT/FTC Knowledge bank/knowledge`，origin 为 `http://127.0.0.1:60074`，默认审计位置为 `~/.ftckb/audit`，运行会话 77417。只做读取：summary 显示 valid=true、48 rules、当前已记录调用 0、audit.health.ok=true 且 warnings=[]、token 已从地址 fragment 移除。临时测试记录没有混入真实分区。启动 token 单独交付，不写入报告。

```sh
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
  env -u FTCKB_AUDIT_DIR apps/knowledge-cli/build/install/ftckb/bin/ftckb dashboard \
  --knowledge '/Users/xinlu/Documents/ChatGPT/FTC Knowledge bank/knowledge' --no-browser
```

此浏览器验收没有调用真实模型/provider，没有通过浏览器执行 provider-backed chat/serve、模型子调用详情、undo/discard，也没有在浏览器重测 symlink、超大 body、并发写入或恢复竞态；这些范围分别依赖受影响自动化测试收据。断连测试是浏览器传输中断，未声称旧 token 能在服务重启后使用。直接调用 agent-runtime 而未接入生产 gate 的旧调用方、旧版/外部未接入 Agent 和绕过 CLI 的行为不在自动检测覆盖范围内。soft 不等于人工复核完成，resolve 不等于检查了 diff。

## 发布前检查（2026-10-19）

本轮发布只运行轻量校验，不重复完整测试套件。`validate knowledge --json` 返回 `ok:true`、48 条规则；`git diff --cached --check` 通过。独立 reviewer 对最终 55 个任务文件的范围、最终增量与敏感内容进行只读复核，未发现发布阻塞。

本仓库是 Knowledge Bank 平台，没有目标机器人队号/profile。以下命令明确沿用 QA 上下文，**只诊断本 PR 平台代码 patch，不代表目标机器人合规或现场验证**；未使用 test/dev 放松检查：

```sh
ftckb check . --knowledge knowledge --team 20827 --season 2025-2026 --generic-profile --diff /private/tmp/ftckb-dashboard-pr.patch --json
```

实际结果：normal 模式退出 0、`ok:true`、0 hard、18 soft。上述 patch 是暂存的代码与文档；本小节仅补充这次检查说明。所有 soft 原文逐项保留如下。它们是上下文中无条件返回的人工复核提示，不能因为退出 0 被当作机器证明已满足。

| ruleId | soft 原文 | 本 PR 适用范围复核 |
| --- | --- | --- |
| `global.constants-centralized` | 调参与机构参数集中存放并使用能表达用途或单位的名称；可以使用 enum、static final 或配置对象，不在逻辑代码中散落魔数。 | 包含通用代码建议；最终平台增量已独立审查，不据此声称目标机器人语义合规。 |
| `global.documentation-intent` | 为子系统、工具类和不明显的机构逻辑编写中文说明，重点解释用途、单位、有效范围和安全理由；不强制固定 Javadoc 或分节模板。 | 包含通用代码建议；最终平台增量已独立审查，不据此声称目标机器人语义合规。 |
| `global.hardware-container` | 所有硬件引用集中在一个 Hardwares 容器类中，并按 Sensors、Motors、Servos 等职责分组；子系统接收该容器，不直接从 HardwareMap 获取设备。 | 本 PR 未修改机器人硬件、机构、视觉、定位或路径代码；不构成相应设备/现场验证。 |
| `global.motor-configuration` | 每个电机必须在机构初始化阶段明确配置方向、运行模式和 ZeroPowerBehavior；BRAKE 或 FLOAT 应按机构需要选择，不得无依据统一套用。 | 本 PR 未修改机器人硬件、机构、视觉、定位或路径代码；不构成相应设备/现场验证。 |
| `global.naming-conventions` | 类使用 PascalCase，字段、方法和硬件配置名使用表达机构语义的 lowerCamelCase；不强制电机字段使用 m 前缀。本赛季自动类继续使用 <Side><Color>[Mini] 与 Base 抽象类的组合命名，不据此推断未来赛季路线。 | 包含通用代码建议；最终平台增量已独立审查，不据此声称目标机器人语义合规。 |
| `global.telemetry-organization` | 项目使用 FtcDashboard 时，通过 MultipleTelemetry 同时提供 Driver Station 与 Dashboard 遥测，并按机构或用途组织关键数据；不强制固定分隔符。 | 本 PR 未修改机器人硬件、机构、视觉、定位或路径代码；不构成相应设备/现场验证。 |
| `shared.dependency-verify-sync-build-run` | Require successful Gradle Sync, local build, Robot Controller deployment, and a minimal runtime check after adding or changing an FTC library. | 新增的是平台本地 audit 模块依赖，没有新增或更换 FTC 库；平台安装构建已验证，无 Robot Controller 部署。 |
| `shared.ftc-sdk-separate-toolchain-versions` | Record the Gradle JDK, Java source compatibility, Android SDK levels, and FTC SDK version as distinct values from their actual configuration locations. | 本轮构建使用 JBR 21/Gradle 9.4.0；没有修改目标 FTC 项目的 Java/Android/SDK 配置。 |
| `shared.gobilda-identify-exact-sku` | Identify the exact goBILDA product and SKU before applying gear ratio, speed, torque, current, encoder, voltage, PWM, or travel specifications. | 本 PR 未修改机器人硬件、机构、视觉、定位或路径代码；不构成相应设备/现场验证。 |
| `shared.gobilda-separate-stall-and-operating-values` | Label no-load and stall specifications distinctly and do not present stall values as continuous operating ratings. | 本 PR 未修改机器人硬件、机构、视觉、定位或路径代码；不构成相应设备/现场验证。 |
| `shared.gobilda-servo-mode-and-pwm-range` | Record exact servo SKU, mode, documented PWM range, voltage, direction, and travel before defining software endpoints or interpreting commands. | 本 PR 未修改机器人硬件、机构、视觉、定位或路径代码；不构成相应设备/现场验证。 |
| `shared.gobilda-use-output-shaft-encoder-resolution` | Use the documented output-shaft encoder resolution for the exact motor SKU and include every external transmission ratio when converting ticks to mechanism motion. | 本 PR 未修改机器人硬件、机构、视觉、定位或路径代码；不构成相应设备/现场验证。 |
| `shared.limelight-back-up-before-os-update` | Back up Limelight pipelines and scripts before flashing or upgrading LimelightOS. | 本 PR 未修改机器人硬件、机构、视觉、定位或路径代码；不构成相应设备/现场验证。 |
| `shared.limelight-configure-camera-pose` | Configure camera pose relative to the robot before field localization and provide current robot orientation before consuming MegaTag 2 results. | 本 PR 未修改机器人硬件、机构、视觉、定位或路径代码；不构成相应设备/现场验证。 |
| `shared.limelight-synchronize-pipeline-dependent-reads` | Treat pipeline switching as asynchronous and verify the reported pipeline before consuming data that depends on the new pipeline. | 本 PR 未修改机器人硬件、机构、视觉、定位或路径代码；不构成相应设备/现场验证。 |
| `shared.pedro-explicit-coordinate-conversion` | State source and destination coordinate systems and convert explicitly when exchanging poses between Pedro Pathing and FTC-standard or vision coordinates. | 本 PR 未修改机器人硬件、机构、视觉、定位或路径代码；不构成相应设备/现场验证。 |
| `shared.pedro-localization-before-follower` | Configure and validate the selected localizer before relying on follower tuning or autonomous path accuracy. | 本 PR 未修改机器人硬件、机构、视觉、定位或路径代码；不构成相应设备/现场验证。 |
| `shared.pedro-tune-current-robot` | Treat follower, drivetrain, and localizer values as measurements or tuning outputs for the current robot; do not copy another robot's values as validated configuration. | 本 PR 未修改机器人硬件、机构、视觉、定位或路径代码；不构成相应设备/现场验证。 |
