# 第一次使用 FTC Knowledge Bank

先选你现在要做的事。只读教程不需要先安装 CLI；准备让 Agent 按规则工作时，再完成 Dashboard 和项目接入。

| 现在的目标 | 从哪里开始 | 完成时应该看到什么 |
| --- | --- | --- |
| 看 FTC 教程或了解规则 | [浏览知识库](#浏览知识库) | 找到教程；知道规则是否适用于自己的项目仍需裁决 |
| 把知识库接入机器人项目 | [第一次接入](#第一次接入) | 项目有固定版本、真实队号/赛季和明确 profile，安装验收完成 |
| 已接入，开始一次编码 | [每次编码](#每次编码) | 本次 resolve 收据对应的 check 完成，soft 已逐项说明 |
| 命令失败或不知道下一步 | [遇到问题](#遇到问题) | 找到出错阶段，按诊断建议处理后重试 |

## 浏览知识库

你可以直接阅读文件，不必配置模型 API key：

- 第一次配置 FTC 工程：[Android Studio 与 FTC SDK](../knowledge/guides/setup/android-studio-ftc-sdk.md)。
- 选择命令架构：[Command-Based：FTCLib、类 FTCLib 与自有实现](../knowledge/guides/architecture/command-based.md)。自有 Command／Subsystem／scheduler 选择 `command-based`；使用 FTCLib command 运行时及其 API 选择 `ftclib-command`。专区四条规则已批准；仅对适用的 command-based（含 ftclib-command 继承）项目产生条件式 soft，实际以 resolve/check 为准。
- 了解 FTCLib 安装：[FTCLib 教程](../knowledge/guides/setup/ftclib.md)。注意教程中的核验日期；安装库与选择项目架构是两件事，适用规则以本项目的 resolve 结果为准。
- 看入门机器人示例：[RookieBot 教程](../knowledge/guides/practices/rookiebot-tutorial.md)。
- 看自动路径与调参：[Pedro Pathing 教程](../knowledge/guides/tools/pedro-pathing.md)。

规则目录里有 approved（已批准）和 candidate（候选）。看见一条规则，不代表它已经对你的项目生效；裁决还会考虑队号、赛季和 profile。不要仅凭文件夹、标题或 ID 推断适用范围。

想在网页中搜索规则时，使用本地 Dashboard。它与机器人的 FTC Dashboard 不同，也与本仓库需要模型 key 的 `serve` 聊天网页不同。按 [README 的启动步骤](../README.md#必做先启动并确认-dashboard) 复用或启动服务；打开终端输出的完整链接，确认页面已连接、知识库路径正确、规则已加载。保留终端运行，后续命令用另一个终端。详细操作见 [Dashboard 指南](dashboard.md)。

## 第一次接入

接入是在现有 FTC Git 项目里增加配置、Agent 指引和固定版本的知识库副本；不会把知识库的 Kotlin 模块加进机器人 Android 工程，也不会自动 commit 或 push。

### 1. 准备四项信息

| 信息 | 应填写的内容 |
| --- | --- |
| 项目路径 | 包含 `TeamCode`、Gradle settings 和至少一个 Git 提交的项目根目录 |
| team | 真实队号，不照抄教程里的示例队号 |
| season | 本次使用的赛季，格式为相邻两年，如 `YYYY-YYYY` |
| profile | 队伍明确采用的架构，见下表；不从依赖自动猜测 |

| profile | 何时明确选择 |
| --- | --- |
| `generic` | 显式不选择架构专属 profile，仍会裁决其他适用规则 |
| `command-based` | 明确采用自有或类 FTCLib 的 Command／Subsystem／scheduler 架构 |
| `ftclib-command` | 实际使用 FTCLib command 运行时及其 API；同时包含 command-based |
| `simple-opmode` | 明确采用 simple-opmode 架构 |
| `rookiebot` | 明确采用 RookieBot 对应架构；同时包含 simple-opmode |

`simple-opmode` 与 `command-based` 两种架构不能混用，generic 与命名 profile 也不能同时选择。仅安装 FTCLib 的 PID 或硬件工具，不足以推断项目使用 `ftclib-command`。如果队伍还没决定架构，先让负责软件的队员确认，不让 Agent 替你猜。

**固定版本（pin）**是项目记录的知识库完整 commit：所有队员使用同一份规则和工具，避免上游变化悄悄改变项目要求。更新本仓库不会自动更新已接入项目；升级必须另行选择已审阅版本。

### 2. 让编码 Agent 接入

将以下指令中的占位替换后，交给正在使用的编码 Agent：

```text
将 https://github.com/lucasnotfound59/FTC-Knowledge-Bank 接入我的 FTC 项目。
项目绝对路径：<实际路径>
真实队号：<队号>；赛季：<YYYY-YYYY>；profile：<明确选择的 profile>
读取上游 .agents/skills/ftckb-integrate/SKILL.md，选择并固定已审阅的 tag 或完整 commit。
先 dry-run，再用 --prepare-only 准备；连接目标实际知识库的 Dashboard 并观察页面后，用 --accept 验收。
不要自动 commit/push。
开始规则或代码工作前，复用或启动实际知识库的 Dashboard，确认页面已连接。
```

需要 Git、Python 3.10+、JDK 21+ 和同一 Python 环境里的 `jsonschema`。依赖准备、手动安装命令及 dry-run 的具体行为见 [项目接入指南](project-integration.md#前提与版本)。接入命令和规则检查在本地执行，不调用大模型；编码 Agent 自己的登录和费用仍由其供应商决定。

若手动操作，在**已下载并审阅的知识库根目录**运行安装器。下列内容是模板，先替换路径、队号、赛季和 commit，再把最后一项换成你选择的 `--generic-profile` 或 `--profile NAME`：

```bash
python3 .agents/skills/ftckb-integrate/scripts/integrate.py \
  --project "/实际/FTC项目路径" --team 实际队号 --season YYYY-YYYY \
  --ref 完整40位COMMIT --profile 选定的PROFILE --prepare-only --dry-run
```

确认预览指向正确项目和版本后，去掉 `--dry-run`，保留 `--prepare-only` 执行相同命令。`generic` 对应 `--generic-profile`，不要写成 `--profile generic`。准备会安装文件并构建独立 CLI，但不执行规则裁决或代码检查。dry-run 只有计划，没有可用于验收的准备摘要。

### 3. 连接目标 Dashboard，再验收

准备成功应看到 `stage="prepared"`、`ok=true`，保存实际返回的 `preparedContextSha256`。这只是**准备完成，还未验收**。安装应生成项目配置、固定 submodule 和 Agent 指引；检查 `git status` 是否符合预览。`.gitmodules` 和 submodule 指针可能被暂存，但没有自动提交。

按返回的 `nextSteps` 操作：

1. 使用 `workflow-env.env` 中实际的 `FTCKB_AUDIT_DIR`，后续 Dashboard、连接核验、accept 和编码检查保持一致。
2. 复用与 `context.knowledgePath` 和审计目录一致的 Dashboard；没有实例且 `dashboard` 项已给出启动命令时，按返回的实际 `cwd/argv/env` 启动；若能力为 unknown、没有 argv，先核对固定源码支持或显式升级，不猜启动命令。上游 checkout 的同内容服务不能代替目标项目实际副本。
3. 打开服务给出的完整链接，亲自确认页面已连接、知识库路径正确、规则已加载。准备命令不会替你打开、确认或关闭服务。
4. 在仓库外的私有位置，用编辑器创建仅含 `url` 的 JSON，保存本次完整链接。POSIX 上先设置 `umask 077`，文件权限为 `0600` 或更严格；不要把链接贴到命令参数、仓库或报告。具体要求见[连接文件说明](dashboard.md#核验一个已有连接)。
5. 用准备结果 `accept.entrypoint` 指向的已审阅 `verify.py` 执行验收。以下是模板，四处占位均换成真实值；审计目录使用准备输出的原值：

```bash
FTCKB_AUDIT_DIR="/准备输出的实际审计目录" python3 /实际/已审阅知识库/.agents/skills/ftckb-integrate/scripts/verify.py \
  --project "/实际/FTC项目路径" --accept \
  --prepared-context-sha256 实际返回的64位摘要 \
  --connection-file "/实际/私有/dashboard.json" --browser-confirmed
```

`--browser-confirmed` 记录操作者已经观察页面的声明，只有实际看见连接后才能填写。accept 会重核准备输入、Dashboard 的实际知识与审计身份，然后执行 validate → resolve → check；它不会构建、重新安装或改 pin。API 核验与浏览器观察分别完成，API 不能证明服务二进制来自指定 commit。

读验收结果时，`installationOk=true` 表示接入有效；`projectCheck.ok=false`、退出 1 表示当前 diff 有硬违规，仍需修正。soft 必须逐项核对并报告。准备输出丢失、构建失败或准备输入变化时，先审阅并修复原因，再以同样的明确参数重跑 prepare；不要猜摘要或手改配置来通过验收。

新分阶段参数只交给实际 `--help` 已支持它们的已审阅工具入口；目标旧 pin 不会自动获得新脚本。旧单步安装/verify 仍兼容原自动化，但不会提供首次安装中连接目标 Dashboard 的窗口，新手优先用上述分阶段流程。完整输出与恢复见[分阶段接入](project-integration.md#分阶段准备与验收)。其他队员 fresh clone、旧版本升级或安装中断，按[恢复指南](project-integration.md#fresh-clone升级和失败恢复)处理。

## 每次编码

以下命令在**目标 FTC 项目的 Git 根目录**执行，适用于已经接入、且固定版本包含这些入口的项目。Python 使用安装依赖时的同一环境。未接入时先完成上面的安装；旧 pin 缺少脚本时，见 [旧版本入口](#旧版本没有-doctor-或-work)。

### 1. 用 doctor 确认接入状态

```bash
python3 tools/FTC-Knowledge-Bank/.agents/skills/ftckb-integrate/scripts/project.py doctor --project .
```

doctor 是只读诊断，检查配置、固定版本、源码能力和已有构建状态。按输出的 `checks` 与 `nextSteps` 先处理失败或证据不足的项；它不会替你构建、联网或启动 Dashboard。

### 2. 确认 Dashboard 已连接

复用与项目配置中**实际知识库路径和审计目录一致**的实例。没有实例时，按 [Dashboard 启动指南](dashboard.md#启动与访问) 在对应知识库副本启动；缺失或过期的 CLI 要先显式构建。不能用另一个 checkout 的网页代替项目固定副本的规则。

打开本次完整链接，确认页面已连接、知识库路径正确且规则加载成功。`dashboard=ok` 只说明服务启动，仍需确认页面。已有连接还可做 [只读身份核验](dashboard.md#核验一个已有连接)。完整链接含会话 token，私下保存，不写进仓库；刷新后缺凭据时重开原链接。

### 3. 取得本次规则和工作收据

```bash
python3 tools/FTC-Knowledge-Bank/.agents/skills/ftckb-integrate/scripts/project.py work resolve --project .
```

成功后，Agent 根据 `kernel.output.activeRules` 工作，并保存返回的收据 ID 和 `nextSteps`。**resolve 决定本次使用哪些规则，还没有检查任何代码改动。** 收据把这次裁决的项目、配置、固定版本和实际规则联系起来，保存在 Git metadata，不进入代码 diff。

### 4. 修改代码，再用同一收据检查

完成改动后，运行 resolve 返回的 `nextSteps.cwd/argv` 中的准确命令；通常形如：

```bash
python3 tools/FTC-Knowledge-Bank/.agents/skills/ftckb-integrate/scripts/project.py work check --project . --receipt RETURNED_UUID
```

把 `RETURNED_UUID` 换成**本次成功 resolve 返回的 ID**，不要猜 ID 或选“最近的一条”。若连接核验返回了明确的环境变量，resolve 和 check 都使用同一环境。配置、固定版本或实际规则变化后，重新 resolve，再检查当前改动。

默认检查当前暂存、未暂存和未忽略的新文件；开始前先看 `git status`，区分已有改动和本次改动。收据关联成功后，还要读实际 check 结果：

| 结果 | 下一步 |
| --- | --- |
| `ok=true`，没有 hard | 硬检查通过；继续逐项处理 soft |
| 存在 hard，退出 1 | 修正命中项，重新 check |
| 收据/接入错误、规则冲突或加载失败 | 按错误修复；不能当作代码通过 |
| 存在 soft，即使退出 0 | 逐项说明适用性、核对证据并报告结论；需要修改就再 check |

给队友或用户交付时，说清本次改了什么、check 结果、每项 soft 的结论，以及实际做过的构建/测试。check 是静态规则检查；机器人项目仍需正常编译，RC/DS 与真机运行另行验证。

日常省略 `--work-mode` 即为 normal。只有用户明确指定本次是 test 或 dev 时才用对应模式；不要为绕过错误自行切换。特殊模式、独立复核声明和收据失效条件见 [完整编码流程](project-integration.md#每次编码与验收)。

## 遇到问题

先记录失败的命令、所在目录和错误原文，再按阶段处理。不要先重装或删除项目。

| 现象 | 先检查什么 |
| --- | --- |
| `project.py` 不存在 | 是否在目标 Git 根目录；是否尚未接入、submodule 未初始化或 pin 太旧 |
| `No module named jsonschema` | 是否使用了安装 requirements 的同一 Python 环境，见[依赖准备](project-integration.md#前提与版本) |
| doctor 有 failed/blocked/unknown | 阅读对应项和 `nextSteps`；doctor 不会自动修复或执行建议 |
| CLI 缺失、构建状态不匹配或 Java 错误 | 检查 JDK 21+、缓存/网络及固定副本，按[构建恢复](project-integration.md#fresh-clone升级和失败恢复)显式准备 |
| Dashboard 页面失联或没有凭据 | 确认原终端仍运行，重开原完整链接；勿用旧缓存页面判定已连接 |
| Dashboard 显示另一个知识库 | 回到项目配置的实际副本；核对知识库与审计目录，再 resolve |
| 收据不匹配或失效 | 修好上下文后重新 work resolve，使用新返回的 check 命令 |
| check 退出 0，但有 soft | 这是待复核提醒，逐项核对并报告；不等于已经完成复核 |
| build 通过，但机器人行为不对 | 保存 RC/DS 日志与现场条件，单独诊断硬件和运行逻辑 |

### 旧版本没有 doctor 或 work

不要先运行不存在的 `tools/.../project.py`，也不要自动把项目升级到 main。使用**独立、已审阅且具备该入口的知识库 checkout**，从绝对脚本路径诊断目标：

```bash
python3 /实际/已审阅知识库/.agents/skills/ftckb-integrate/scripts/project.py doctor --project /实际/FTC项目
```

它不改变目标 pin。目标已有有效接入但缺 work 入口时，同样可用已审阅新 checkout 的绝对入口运行 work，后续沿返回的 `nextSteps` 使用同一入口；实际裁决仍来自目标项目固定源码。目标尚未接入时先安装；旧固定源码不支持所需能力时，明确决定是否升级，见[项目接入与升级](project-integration.md)。
