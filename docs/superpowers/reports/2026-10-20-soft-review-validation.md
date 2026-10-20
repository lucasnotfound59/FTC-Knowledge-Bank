# 检查提醒复核闭环验收记录

日期：2026-10-20。本轮实现 Dashboard 逐项复核、追加历史、检查输入版本关联；不修改 kernel JSON v2、退出码、规则判定或真实知识规则。功能实现阶段未自动 commit/push；随后用户明确授权提交与推送。保留任务开始前已有未跟踪内容，提交前核验见末节。

## 最终软件验收

在独立审查修复后执行以下受影响组合，`BUILD SUCCESSFUL`。这是本轮最终代码的实际收据，不能与中间测试数量相加。

```sh
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew --offline \
  :modules:audit:test :modules:standardizer:test :modules:session-shell:test \
  :apps:knowledge-cli:test :apps:knowledge-cli:installDist
```

| 模块 | tests | failure/error/skipped |
| --- | ---: | --- |
| audit | 33 | 0/0/0 |
| standardizer | 14 | 0/0/0 |
| session-shell | 17 | 0/0/0 |
| knowledge-cli | 261 | 0/0/0 |
| 合计 | 325 | 0/0/0 |

日志：`/private/tmp/ftckb-soft-review-final-combined2.log`；XML 位于各模块 `build/test-results/test/`。包括复核跨进程 CAS、幂等、持久化失败与日志损坏、原 kernel 输出兼容、输入摘要、直接读取跨 100 条的旧事项时 unknown→stale、源摘要变化后只读、HTTP 未确认保存后同 UUID 重试，以及 `-Xmx64m` 下未修改 24 MiB binary 的真实子进程回归。`node --check` 与 `git diff --check` 通过。

RED 已实际出现：首次审计测试在 FileReviewStore 尚未实现时 compileTestKotlin 缺类失败（`/private/tmp/ftckb-soft-review-audit-red.log`）。中间 HTTP fixture 曾缺必需证据字段，低堆测试曾因测试 worker 不暴露 URLClassLoader 而未取得 classpath；均修正后在上述最终组合重新执行，没有放宽产品校验器。

安装目录 `lib/knowledge-cli.jar` 的 `dashboard/app.js` 与源码字节相同，SHA-256 为 `a67fd58bae30506bc9090d89b157e01c4a14a1462abf49442916c2dee9be2737`。

## 独立审查与覆盖边界

独立审查指出并已修复：全仓冻结导致未变化大 binary 堆溢出；未确认持久化后 API/UI 原请求重试被过早阻断；源审计内容改变后旧声明仍被投影为可用。新增实质回归覆盖这些路径。独立审查确认上述三项 P2 已关闭，最终组合、低堆探针与安装资源均已核对；没有剩余发布阻断发现。

工作区版本采集有累计内存预算，最大 16 MiB，并随 JVM 堆和剩余空间进一步减小。预算不足时保留原检查行为、缺少可比较版本并显示 unknown，不能据此判断后续是否陈旧。本共享仓库在本轮代表性完整工作区采集中实际产生了非空 worktree 版本（HEAD `9bc3b950018d2e17d6f67f5e6fe76674163f84cc`）；收据为 `/private/tmp/ftckb-soft-review-observation.json` 与同名 observation-audit 临时目录。这个观测不是对所有 FTC 仓库都能生成版本的承诺。

复核是操作者针对指定历史检查快照的声明；复核人身份不认证，链接不抓取。旧记录可标版本未知，新检查默认待复核，外部编辑须重新执行检查才可观察。没有真机、Robot Controller、Driver Station 或真实模型/provider 验证。

## 浏览器与服务

独立验收使用本机已有 bundled Playwright/Chromium，未安装依赖。临时验收只使用 `/private/tmp/ftckb-soft-review-live-8cc8p37n/knowledge` 和独立 audit 根，未向真实分区注入测试样例。实际浏览器通过：

- 正常本地导航已连接，正确临时知识根与 48 规则；保存独立复核时原 `events.jsonl` 全字节不变。
- 重新打开页面后记录保留；改判追加历史；双页面同 revision 的第二次提交收到 409 并保留草稿，读取最新历史。
- 离线提交保留草稿，恢复后重试保存；关联修改后的补丁重检使旧项 stale、新项尚无声明。
- 390px 下没有 document 横向溢出；Tab 聚焦、键盘填写姓名/理由、Enter 预览与保存成功。
- 仅重启临时服务后原事项五条历史保留；专门旧日志 fixture 移除 codeVersion 后显示 unknown，记录历史声明后仍为 unknown，并出现在 recorded。
- 仅在临时复核日志追加故意损坏行后，可读历史保留、health=false、canReview=false、保存按钮禁用。
- 新真实知识库 Dashboard 页面实际已连接、正确真实路径、48 规则，浏览器仅发出 GET。

核心七项收据为 `/private/tmp/ftckb-soft-review-ui/receipt.json`，移动端保存为 `mobile-state.json`，重启/旧记录/坏日志/真实页四项为 `phase-two-receipt.json`，详细说明为同目录 `SUMMARY.md`。原审计字节不变核验发生在专门旧日志 fixture 改动之前；`restart-state.json` 记录了故意修改的时点与前后摘要，不能将后续 fixture 变化说成复核写入改写原检测。

| 截图 | 路径 |
| --- | --- |
| 保存与历史 | `/private/tmp/ftckb-soft-review-ui/01-saved-desktop.png`、`02-revised-history.png` |
| 冲突草稿保留 | `/private/tmp/ftckb-soft-review-ui/03-conflict-draft-retained.png` |
| 旧项陈旧 | `/private/tmp/ftckb-soft-review-ui/04-old-review-stale.png` |
| 390px 与键盘表单 | `/private/tmp/ftckb-soft-review-ui/05-mobile-keyboard.png`、`10-mobile-form-viewport.png` |
| 重启/旧记录/损坏 | `/private/tmp/ftckb-soft-review-ui/06-restart-history.png`、`07-legacy-unknown.png`、`08-corruption-readonly-history.png` |
| 新真实库已连接 | `/private/tmp/ftckb-soft-review-ui/09-current-user-dashboard.png` |

真实库首页和移动端截图已人工视觉检查。macOS headless 环境中没有验证原生 select 的纯键盘选项选择，选项以语义 selectOption 设置后，其余 Tab/文本/Enter 流程实测通过；长列表旧项的鼠标定位遇到轮询重绘，正常按钮 Enter 打开通过，不宣称该鼠标步骤通过。危险 URL/输入与 XSS 渲染边界依赖本轮 HTTP 测试、独立界面 smoke 和 textContent 自审，没有在本轮真实浏览器逐项重演；hard/dev 类型、超多命中分页、源摘要变化与 fsync 失败等边界以最终自动化收据为准。

浏览器早期 harness 对同文档 fragment、异步详情加载和原生控件的假设已修正；最终成功收据与早期失败分开，不把工具问题当作产品通过。临时服务现为 PID 88061、origin `http://127.0.0.1:64122`、会话 73972，仍保留故意损坏的负向测试 fixture。

已为用户启动新构建的真实知识库 Dashboard：origin `http://127.0.0.1:63734`，PID 87163，exec 会话 3006，知识根为仓库的 `knowledge`，审计根为默认 `~/.ftckb/audit`。认证 summary 只读确认 valid=true、48 规则、audit health 正常。启动 token 不写入报告。原 PID 77204、端口 58496 的旧构建实例仍保留；新旧实例不是同一构建。最终真实浏览器已确认页面连接，见截图 09；只有只读 GET，没有写入规则或复核样例。

## 最终规则诊断

沿用明确的平台 QA 上下文 20827 / 2025-2026 / generic / normal，仅检查本次平台任务 diff，不代表实际机器人上下文。真实知识库 `validate` 返回 `ok:true`、48 规则；`resolve` 返回 26 active、0 conflicts；完整任务 diff（含新增文件，排除任务开始前用户未跟踪内容）`check` 返回退出 0、`ok:true`、0 hard、18 soft。

```sh
ftckb validate knowledge --json
ftckb resolve knowledge --team 20827 --season 2025-2026 --generic-profile --json
ftckb check . --knowledge knowledge --team 20827 --season 2025-2026 --generic-profile \
  --diff /private/tmp/ftckb-soft-review-final.patch --json
```

结果保存在 `/private/tmp/ftckb-soft-review-final-{validate,resolve,check}.json`。以下逐项记录 CLI 实际返回的原文和本任务适用性；没有用退出 0 或无关规则推断目标机器人安全。

| ruleId | 原提醒 | 本任务复核 |
| --- | --- | --- |
| `global.constants-centralized` | 调参与机构参数集中存放并使用能表达用途或单位的名称；可以使用 enum、static final 或配置对象，不在逻辑代码中散落魔数。 | 平台适用性复核：请求/记录上限与采集预算在各自边界集中声明；本轮未引入机器人调参或机构参数，不据此声称机器人参数合规。 |
| `global.documentation-intent` | 为子系统、工具类和不明显的机构逻辑编写中文说明，重点解释用途、单位、有效范围和安全理由；不强制固定 Javadoc 或分节模板。 | 平台适用性复核：新增存储、版本采集、投影均有用途与边界说明，用户文档和管理台文案为中文；本轮没有机器人子系统或机构逻辑。 |
| `global.hardware-container` | 所有硬件引用集中在一个 Hardwares 容器类中，并按 Sensors、Motors、Servos 等职责分组；子系统接收该容器，不直接从 HardwareMap 获取设备。 | 本次不适用：未修改 HardwareMap、机器人硬件容器或机构初始化。 |
| `global.motor-configuration` | 每个电机必须在机构初始化阶段明确配置方向、运行模式和 ZeroPowerBehavior；BRAKE 或 FLOAT 应按机构需要选择，不得无依据统一套用。 | 本次不适用：没有电机方向、模式、ZeroPowerBehavior 或硬件初始化改动。 |
| `global.naming-conventions` | 类使用 PascalCase，字段、方法和硬件配置名使用表达机构语义的 lowerCamelCase；不强制电机字段使用 m 前缀。本赛季自动类继续使用 <Side><Color>[Mini] 与 Base 抽象类的组合命名，不据此推断未来赛季路线。 | 平台适用性复核：新增 Kotlin 类使用 PascalCase，字段与方法使用 lowerCamelCase 并表达审计/复核用途；没有赛季自动路线类或硬件配置名改动。 |
| `global.telemetry-organization` | 项目使用 FtcDashboard 时，通过 MultipleTelemetry 同时提供 Driver Station 与 Dashboard 遥测，并按机构或用途组织关键数据；不强制固定分隔符。 | 本次不适用：本地规则管理台没有 FtcDashboard/Driver Station 遥测改动。 |
| `shared.dependency-verify-sync-build-run` | Require successful Gradle Sync, local build, Robot Controller deployment, and a minimal runtime check after adding or changing an FTC library. | 本次不适用 FTC 部署部分：复用已有 Jackson/JGit，无新增或更换 FTC 库；Gradle 仅补测试子进程 classpath，平台构建/测试通过，没有 RC 部署。 |
| `shared.ftc-sdk-separate-toolchain-versions` | Record the Gradle JDK, Java source compatibility, Android SDK levels, and FTC SDK version as distinct values from their actual configuration locations. | 平台构建复核：使用 Android Studio JBR 21 与 Gradle 9.4.0；没有修改目标项目 Java/Android SDK/FTC SDK 配置，不把这些版本混作一项。 |
| `shared.gobilda-identify-exact-sku` | Identify the exact goBILDA product and SKU before applying gear ratio, speed, torque, current, encoder, voltage, PWM, or travel specifications. | 本次不适用：没有使用或修改 goBILDA 产品规格、SKU 或硬件计算。 |
| `shared.gobilda-separate-stall-and-operating-values` | Label no-load and stall specifications distinctly and do not present stall values as continuous operating ratings. | 本次不适用：没有电机空载/堵转或连续工作额定值改动。 |
| `shared.gobilda-servo-mode-and-pwm-range` | Record exact servo SKU, mode, documented PWM range, voltage, direction, and travel before defining software endpoints or interpreting commands. | 本次不适用：没有舵机 SKU、模式、PWM、供电或软件端点改动。 |
| `shared.gobilda-use-output-shaft-encoder-resolution` | Use the documented output-shaft encoder resolution for the exact motor SKU and include every external transmission ratio when converting ticks to mechanism motion. | 本次不适用：没有编码器分辨率、传动比或位置换算改动。 |
| `shared.limelight-back-up-before-os-update` | Back up Limelight pipelines and scripts before flashing or upgrading LimelightOS. | 本次不适用：没有相机刷机、LimelightOS、pipeline 或脚本更新。 |
| `shared.limelight-configure-camera-pose` | Configure camera pose relative to the robot before field localization and provide current robot orientation before consuming MegaTag 2 results. | 本次不适用：没有视觉定位、相机位姿或 MegaTag 2 改动。 |
| `shared.limelight-synchronize-pipeline-dependent-reads` | Treat pipeline switching as asynchronous and verify the reported pipeline before consuming data that depends on the new pipeline. | 本次不适用：没有相机 pipeline 切换或结果读取改动。 |
| `shared.pedro-explicit-coordinate-conversion` | State source and destination coordinate systems and convert explicitly when exchanging poses between Pedro Pathing and FTC-standard or vision coordinates. | 本次不适用：没有 Pedro、FTC 或视觉坐标转换改动。 |
| `shared.pedro-localization-before-follower` | Configure and validate the selected localizer before relying on follower tuning or autonomous path accuracy. | 本次不适用：没有 localizer、follower 或自动路径配置改动。 |
| `shared.pedro-tune-current-robot` | Treat follower, drivetrain, and localizer values as measurements or tuning outputs for the current robot; do not copy another robot's values as validated configuration. | 本次不适用：没有机器人质量、底盘、定位、控制器或路径完成参数改动。 |


## 用户授权提交前复核（2026-10-20）

用户明确要求仅提交并推送本次功能，排除临时演示。拟提交范围为本次 31 个功能/测试/设计/计划/验收/用户文档文件，当前分支 `codex/dashboard-rule-management-audit`，推送目标为同名 `origin` 上游。暂存前 index 为空，没有混入他人已暂存内容。真实 `knowledge` 没有 diff；临时演示、浏览器脚本/截图/收据、启动凭据及原有未跟踪内容不进入提交。

重新认证读取现有真实 Dashboard 的 summary/rules 均 HTTP 200，正确知识根、valid=true、48 条规则，未重启或关闭任何用户服务。

按收尾流程尝试 `./gradlew --offline test`，在 Android Studio 插件的依赖解析阶段即无法启动测试：缓存缺少 `ideaIU-2025.2.6.1-aarch64.dmg`。这是未执行的 IDE 范围，不能算作全仓测试通过或产品断言失败；本次没有下载大型 IDE 或修改该模块。

对本次受影响范围强制重新执行，全部 34 个 Gradle task 实际执行，构建成功：

```sh
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew --offline --rerun-tasks \
  :modules:audit:test :modules:standardizer:test :modules:session-shell:test \
  :apps:knowledge-cli:test :apps:knowledge-cli:installDist
```

本次实际 XML 仍为 audit 33、standardizer 14、session-shell 17、CLI 261，共 325 tests，0 failure/error/skipped。日志为 `/private/tmp/ftckb-soft-review-publish-affected-tests.log`；全仓环境限制日志为 `/private/tmp/ftckb-soft-review-publish-full-test.log`。产品代码相对已验浏览器版本没有后改，未无理由重复浏览器整套。实际暂存 diff 按相同 20827 / 2025-2026 / generic / normal 平台上下文再次执行 validate/resolve/check，沿用上表逐项 soft 适用性，不据此声称 RC/DS 或真机通过。

本次提交前实际结果：validate `ok:true`/48；resolve 26 active/0 conflicts；精确暂存补丁 check 退出 0、`ok:true`、0 hard/18 soft、schemaVersion 2。18 项原文与上表完全一致。收据为 `/private/tmp/ftckb-soft-review-publish-{validate,resolve,check}.json`，不包含演示或凭据。
