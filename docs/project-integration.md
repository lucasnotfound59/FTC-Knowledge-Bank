# 把知识库接入 FTC 项目

第一次接触本项目，可先读[新手指南](getting-started.md)，按「首次接入」或「已接入后编码」选择步骤。本文保留安装、版本与验收的完整细节。

安装入口：[`ftckb-integrate` Skill](../.agents/skills/ftckb-integrate/SKILL.md)。这是项目级接入，不是全局插件安装，也不会把知识库 Kotlin 模块加入机器人的 Android Gradle 工程。

## 队员怎么用，需要 API key 吗？

给正在使用的编码 Agent 仓库链接，并明确目标项目和队号，例如：

```text
把 https://github.com/lucasnotfound59/FTC-Knowledge-Bank
接入当前 FTC 项目，队号 16093，赛季 2025-2026，显式使用 generic profile。
请读取上游 .agents/skills/ftckb-integrate/SKILL.md，并锁定已审阅的版本。
```

接入后的 `validate / resolve / check` 在本机确定性执行，**不需要 API key、不调用大模型、没有 token 费用**。队员正在用的编码 Agent 如何登录或计费，仍由那个 Agent 自己决定。只有另行运行本仓库自带的聊天/网页 Agent，才需要配置模型供应商的 key；本接入配置不保存 key。

能运行 shell、读文件的 Agent 可按此契约调用 CLI，但是否自动发现 `.agents/skills` 取决于产品。不能保证任意 Agent 仅看链接就自动安装；不支持自动发现时，让它显式读取目标 Skill。此机制不是不可绕过的编辑器拦截器。

## 前提与版本

- 目标是已有至少一个 Git 提交的 FTC Gradle 项目，包含 `TeamCode` 与 `settings.gradle` 或 `settings.gradle.kts`。
- Git、Python 3.10+、运行用的 JDK 21+。Gradle 工具链会请求 JDK 21；首次构建需要相应缓存或网络。不是任意 JDK 都能运行已编译 CLI。
- `jsonschema>=4.18,<5` 安装在运行脚本的同一 Python 环境。推荐仓库外虚拟环境，避免污染机器人项目；例如：

```bash
python3 -m venv /path/to/ftckb-venv
/path/to/ftckb-venv/bin/python -m pip install -r /path/to/FTC-Knowledge-Bank/.agents/skills/ftckb-integrate/scripts/requirements.txt
```

下文 `python3` 替换为该虚拟环境 Python。Windows 使用环境内 `python.exe`，或 `py -3`。不要向共享配置提交个人 `JAVA_HOME`、`sdk.dir` 或 SDK 绝对路径。这个独立 CLI 构建不需要 Android SDK；机器人编译仍需要队员自己的 Android SDK。

首次安装须指定包含本功能的 tag 或完整 40 位 commit SHA。仓库 V0.9.0、CLI 2.1.0、YAML v4、kernel JSON v2、项目接入协议 v2 是独立版本轴；当前知识总数为 52（46 已批准 + 6 候选）。README 的版本不代表同名 tag 已发布。正式 tag 发布前，请使用包含本 Skill 和脚本的已提交版本；审阅所选 checkout 后用 `git rev-parse HEAD` 获取完整 SHA，不要把旧版本 SHA 或 `main` 传给安装器。

## 安装与 dry-run

在已下载并审阅的上游版本中运行：

```bash
python3 .agents/skills/ftckb-integrate/scripts/integrate.py \
  --project "/path/to/FTC project" --team 16093 --season 2025-2026 \
  --ref FULL_COMMIT_SHA --generic-profile --prepare-only --dry-run
```

`FULL_COMMIT_SHA` 必须替换为实际完整 SHA，也可传实际存在的 tag。默认来源是官方 GitHub 仓库；测试镜像可显式 `--repository URL`。不接受 branch 名，不把 README 示例队号作为默认值；缺少 team/season 时脚本报错，Agent 应询问。

dry-run 会查询远程或在临时目录获取选定版本，以检查模板与冲突，但**不写目标文件、index、Git 配置，也不构建**。计划说明创建/更新哪些文件和 submodule。去掉 `--dry-run`、保留 `--prepare-only` 执行同一命令，先准备文件和独立 CLI：

```text
目标项目/
├── .gitmodules
├── AGENTS.md                                  # 只插入/替换托管段
├── .agents/skills/ftc-knowledge-bank/SKILL.md
├── .ftckb/project.yaml
└── tools/FTC-Knowledge-Bank/                   # 固定 commit 的 submodule
```

`git submodule add` 会暂存 `.gitmodules` 和 gitlink；这是明确的 Git 副作用，不是 commit。安装器不自动暂存其余文件，不 commit/push，不创建/切换目标分支，不安装 hooks。已有 `.gitmodules` 用户改动会导致预检停止，避免意外暂存。

## 分阶段准备与验收

首次接入推荐：**dry-run → prepare → CLI accept**。使用实际 `--help` 已支持以下参数的已审阅 checkout；新入口可以从外部绝对路径调用，不要求目标旧 pin 已包含新脚本，也不暗改目标 pin。模板和内核仍来自选定固定源码，能力不足时明确升级，而不是向旧脚本发送新参数。

`integrate.py --prepare-only` 完成接入文件、固定 submodule 和独立 CLI 构建后停止，不运行 validate/resolve/check，不创建 work receipt，也不启动 Dashboard。与 `--dry-run` 合用时仅返回计划，不构建，不生成真实准备摘要。

准备成功输出独立 `installationWorkflowSchemaVersion:1`，`command="prepare"`、`stage="prepared"`、`ok=true`，同时 `kernelStatus` 和 `acceptanceStatus` 都是 `not_performed`；这不表示 installationOk，也不表示代码检查通过。保存实际 `preparedContextSha256`，按 `nextSteps` 接续：

- `workflow-env.env`：本次实际 `FTCKB_AUDIT_DIR`，accept 和后续 work 调用使用同一显式值。人稍后可用同一知识库和审计目录查看已有记录。
- `accept.cwd/argv/env`：已审阅工具返回的完整 CLI 验收命令，直接使用真实摘要；不需要 Dashboard、连接文件或浏览器声明。

以下是须替换占位的模板：

```bash
FTCKB_AUDIT_DIR="/actual/audit/root" python3 /path/to/reviewed-FTC-Knowledge-Bank/.agents/skills/ftckb-integrate/scripts/verify.py \
  --project "/path/to/FTC project" --accept \
  --prepared-context-sha256 RETURNED_64_CHARACTER_SHA256
```

accept 不构建、不 fetch、不修改配置/pin/托管文件，重核准备摘要后以 normal 执行 validate → resolve → check。每阶段前后重核准备输入，观察到漂移即失败并保留实际已执行结果。默认 `dashboardVerification=not_requested`、`browserConfirmation=not_declared`、`serviceBinaryPin=unknown`，不宣称页面或服务版本核验通过。

兼容旧显式人工验收：只有人实际观察页面后，才成对追加 `--connection-file PRIVATE_JSON --browser-confirmed`，进行真实服务知识/审计身份核验；只给一项退出 64，核验失败退出 2。`--browser-confirmed` 是操作者声明，不是工具证明或身份认证，API 也不证明服务 binary pin。私有文件仅含真实完整链接的 `url`，POSIX 当前用户所有、0600 或更严格、无 symlink；不把 token 放 argv、仓库或报告。正常 AI 不创建、索取或读取它，人工入口见 [Dashboard 指南](dashboard.md#核验一个已有连接)。

摘要绑定配置原字节、pin/gitlink、托管内容、实际知识输入、CLI 产物以及审计目录。普通 TeamCode diff 不使其失效，accept 检查验收当时的真实 diff。它不包含 token，不是身份签名；不保证抵御同权限中途改回的变化。

| 输出或错误 | 意义与恢复 |
| --- | --- |
| `stage="accepted"`、`installationOk=true`、退出 0 | 接入有效，本次 diff 硬检查通过；仍须逐项复核 soft |
| `installationOk=true`、`projectCheck.ok=false`、退出 1 | 接入有效，当前代码有硬违规；修正后重新检查 |
| `error.code="usage"`、退出 64 | 缺少真实摘要，或显式人工核验参数未成对提供 |
| `setup_required` 或 `build_failed`、退出 2 | 准备/构建不完整；修好 JDK、缓存、网络或产物后显式重跑 prepare |
| `preparation_changed`、退出 2 | 观察到准备输入变化；先审阅，再 prepare 获取新摘要 |
| `connection_failed`、退出 2 | 仅显式人工路径：修复选定 Dashboard、连接文件或同一 audit env，不能把失败说成通过 |
| `integration_error` 或 `audit_incomplete`、退出 2 | 输入/内核阶段或审计未完整核验，保留已执行结果，不能宣布完整验收 |

输出丢失时可用相同明确参数重跑 prepare；它不追踪 main/tag、不自动覆盖人工修改。accept 的实际内核结果在 `checks.<command>.kernel`，汇总代码结果在 `projectCheck`，原 kernel JSON v2 保留。

无 `--prepare-only` 的旧安装器和无 `--accept` 的 `verify.py --project PATH` 继续原合并安装/构建/验收行为，供已有自动化兼容；新接入优先使用可复核准备摘要的分阶段入口。

## 配置与重复执行

`.ftckb/project.yaml` 使用 **JSON 语法（合法 YAML 1.2）**，由脚本生成；普通缩进 YAML 不受本包装器支持。

```json
{
  "schemaVersion": 2,
  "kernelSchemaVersion": 2,
  "integrationVersion": 2,
  "team": "16093",
  "season": "2025-2026",
  "profiles": [],
  "source": {
    "repository": "https://github.com/lucasnotfound59/FTC-Knowledge-Bank.git",
    "ref": "RELEASE_TAG_OR_FULL_SHA",
    "commit": "FULL_40_CHARACTER_COMMIT_SHA",
    "path": "tools/FTC-Knowledge-Bank"
  },
  "managedFiles": {
    ".agents/skills/ftc-knowledge-bank/SKILL.md": "GENERATED_SHA256",
    "AGENTS.md#ftckb": "GENERATED_SHA256"
  }
}
```

以上仅字段示意，占位字符串不能直接使用。`managedFiles` 摘要用于防止无意覆盖，不是对恶意篡改的签名。不要手改摘要来强行通过预检。

新安装必须选择 `--generic-profile` 或可重复的 `--profile NAME`（例如 `--profile command-based`）。`profiles:[]` 是显式 generic；缺失/null 不合法。rookiebot 隐含 simple-opmode，ftclib-command 隐含 command-based；simple-opmode 与 command-based 不兼容，generic 与命名 profile 互斥。不从代码或依赖猜测 profile。

重跑时可省略 team/season/ref，缺省重用配置中的 SHA，不重新解析 tag。相同输入不会产生新 diff。AGENTS 托管段之外的原文及队伍补充保留；项目补充写在段外，不改生成的 Skill。若生成文件/段落有人工修改、路径冲突、symlink 或 submodule 脏改动，脚本停止而非覆盖。

## 只读接入诊断

日常顺序：**doctor → work resolve → 修改 → work check --receipt ID**。已安装且固定源码包含 doctor 时，从目标 Git 根目录运行：

```bash
python3 tools/FTC-Knowledge-Bank/.agents/skills/ftckb-integrate/scripts/project.py doctor --project .
```

尚未初始化 submodule 或旧 pin 没有 doctor 时，先取得独立、已审阅的 Knowledge Bank checkout，再用它的绝对脚本入口：

```bash
python3 /path/to/reviewed-FTC-Knowledge-Bank/.agents/skills/ftckb-integrate/scripts/project.py doctor --project /path/to/FTC-project
```

替换为实际绝对路径。此入口不改变目标 pin，后续 resolve/check 仍使用项目固定源码。doctor 核验 `.gitmodules` 和 gitlink 后才建议初始化；缺配置时先明确真实 team/season/profile 并执行安装，不调用目标尚不存在的脚本。缺必要 CLI 能力时显式决定升级；缺可选 Dashboard 不要求升级，不自动跟随 main。

doctor 只读配置、Git 元数据、源码能力与静态发行产物，不联网、不构建、不运行 kernel、不启动 Dashboard、不写 audit、不改配置/pin。它使用单独的 `doctorSchemaVersion:1`（不是 kernel JSON v2），输出 `context/checks/nextSteps`；每项为 `passed/failed/blocked/unknown/not_checked`。必需项均 passed 才退出 0；问题或必需证据不足退出 1；参数错误退出 64，仍返回 JSON。无法验证的 context 为 null；v1 不自动补 generic。`nextSteps` 的 cwd/argv 是数据和建议，不会执行。静态 receipt 只核对记录，不证明所有产物来源或可运行；Dashboard 连接、裁决、diff 与硬件始终 not_checked，不代表通过。

先处理诊断问题，按需要显式准备和 CLI 验收，再运行 work resolve、修改代码和同一收据的 work check。AI 不探测、启动、连接、截图或浏览器确认 Dashboard。人需要查看规则或已有审计时再打开；默认 doctor/prepare 不提供管理台建议，不因未知管理台能力建议升级。

新模板仅在显式安装/升级时生成；不要为同步新流程覆盖旧项目的托管文件或改摘要。人类入口可使用已审阅 checkout 的 `dashboard_runtime.py` helper，显式传旧 pin 的完整 distribution/实际 knowledge 和同一审计目录，不改 pin。Python 3.10+ POSIX 的保护范围、Windows 限制、SIGKILL 与清理边界见 [Dashboard 指南](dashboard.md#稳定副本与生命周期)。

## 机器核验一个已有 Dashboard

仅当人明确要求时，可选的机器核验入口是 `project.py dashboard verify --project . --connection-file PRIVATE_JSON`；它只对显式已知现有服务做 authenticated GET，核对实际知识输入与审计存储身份，不扫描/启动/关闭实例。私有文件只含本次完整链接的 `url`，POSIX须当前用户所有、无组/其他权限且路径无symlink；token不进argv或仓库。旧pin无入口时用已审阅新checkout的绝对project.py入口，保留目标pin。exit0仍不代表浏览器页面已连接或服务binary pin已证明；旧API/未知store不能全绿。成功返回的workflow-env适用于后续resolve和收据返回的check argv，必须保留同一显式FTCKB_AUDIT_DIR。详见 `docs/dashboard.md`「核验一个已有连接」。

```bash
python3 /path/to/reviewed-FTC-Knowledge-Bank/.agents/skills/ftckb-integrate/scripts/project.py dashboard verify --project /path/to/FTC-project --connection-file /private/path/dashboard.json
```

`PRIVATE_JSON` 唯一字段格式是 `{"url":"http://127.0.0.1:PORT/#token=TOKEN"}`，PORT/TOKEN必须替换为当前启动私下提供的实际值；不要照抄占位或将实际值写入共享命令历史。仅精确127.0.0.1 HTTP根路径，拒绝proxy/redirect；文件8KiB、响应1MiB、3秒socket I/O超时（不是整个命令总时限）。无法验证隐私边界的平台返回unknown。

输出是独立 `dashboardConnectionSchemaVersion:1`，退出0为本次必需API核验匹配，退出1为失败/未知，退出64为参数错误。`browser.connection=not_checked`、`server.binaryPin=unknown`、`kernel.defaultAudit=not_checked`，不冒充这些阶段通过。expected audit来自本次非空白FTCKB_AUDIT_DIR（相对值按项目根解析，不展开~），否则Python home下显式选定`.ftckb/audit`；这不证明未配置的kernel JVM user.home。成功nextSteps的env应同时应用于resolve和后续check，不能只在verify时设置。

## 每次编码与验收

完成 doctor 和必要的 CLI 准备/验收后，从目标根目录运行：

```bash
python3 tools/FTC-Knowledge-Bank/.agents/skills/ftckb-integrate/scripts/project.py work resolve --project .
# Agent 根据 activeRules 修改代码
python3 tools/FTC-Knowledge-Bank/.agents/skills/ftckb-integrate/scripts/project.py work check --project . --receipt RETURNED_UUID
```

`RETURNED_UUID` 是占位，使用成功 resolve 返回的 `nextSteps.cwd/argv` 中的准确 ID；不手填多份 context，也不选最近一次。新 work 入口不构建、不联网，要求已有 CLI 发行和匹配 build receipt；缺失时先显式准备，再运行 CLI 验收/resolve。内核输出位于 `kernel.output`，仍按原 schemaVersion 校验；旧 resolve/check 默认输出和退出码保留，也保留原构建行为，但不具有关联证明。

`resolve|check` 还可用 `--work-mode normal|test|dev` 透传逐任务工作模式，例如用户明确指定测试代码时 `project.py resolve --project . --work-mode test`，或用户明确指定 dev 代码时 `project.py check --project . --diff /tmp/change.patch --work-mode dev`。该参数不写入 `.ftckb/project.yaml`，不改变固定版本、team、season、profiles 或托管文件；省略时保持原命令与输出。`--work-mode dev` 缺少 `--diff` 时包装器拒绝执行。模式不能按文件名、路径或依赖推断，只有用户明确指定才使用；包装器会核对内核响应确实回报了请求的 `workMode`，避免旧内核静默忽略。

### 工作收据边界

固定源码已有 doctor 但尚无 work 入口时，用已审阅新 checkout 的绝对入口 `python3 /path/to/reviewed-FTC-Knowledge-Bank/.agents/skills/ftckb-integrate/scripts/project.py work resolve --project /path/to/FTC-project`；保留目标 pin，后续沿返回的 nextSteps 使用同一外部入口，不自动升级。

work 的独立 envelope/file 为 `workReceiptSchemaVersion:1`；退出0为真实成功且关联验证通过，退出1为真实 check 硬违规，退出2为收据/上下文/接入/运行错误或冲突，退出64为新参数错误。`kernel:{exitCode,output}` 保留真实内核结果，不往 kernel JSON v2 加字段。

收据位于实际 Git directory 的 `ftckb/receipts/<UUID>.json`；若自定义 gitDir 在工作区内且不位于 `.git` 子树，明确拒绝，不修改 ignore 或迁移数据。允许普通 linked worktree 的外部 metadata；收据不进入项目工作区、不自动 commit、不改 audit，不自动选择 latest。它绑定 root/gitDir、配置原字节、team/season、原/规范 profiles、协议、pin、实际 knowledgePath、全部实际小写 `.yaml/.yml` 的路径/内容摘要和本次 mode，含 Git ignored 规则。任何这些输入变化都必须重新 resolve；v1 profiles 仍为 null，不变成 generic。normal 不静默继承 test/dev；dev check 必须提供本次 diff。普通机器人代码修改/提交和重复 check 允许，不设时间 TTL，不消费收据；用户可自行删除旧文件。

work 收到内核 stderr 时，在独立 envelope 返回安全分类的 `diagnostics`/`auditStatus`：审计存储失败为 incomplete，输入版本或其它诊断为 unknown，不暴露原始敏感内容；无诊断不添加这些字段，也不证明审计完整。用户摘要必须报告审计不完整/未知及诊断项。work 保留真实 kernel JSON/退出码和既有收据创建/关联语义；收据绑定实际执行不代表独立审计完整。CLI accept 对已报告的审计存储失败仍停止后续验收阶段。

调用前后重验上下文，传入diff时也核对其字节；中途变化即返回关联失败，并保留已执行的实际内核事实。收据是本地摘要，不是签名/身份/审批/不可抵赖审计；同用户可重算伪造，前后快照也不是抵御同权限 ABA 的事务。缺失、损坏、symlink 或不匹配时明确重新 resolve，不猜配分开的 audit 调用。compile/deploy/真机仍分别验证。

旧兼容入口仍可显式调用（不创建或使用工作收据）：

```bash
python3 tools/FTC-Knowledge-Bank/.agents/skills/ftckb-integrate/scripts/project.py resolve --project .
python3 tools/FTC-Knowledge-Bank/.agents/skills/ftckb-integrate/scripts/project.py check --project .
```

旧兼容完整验收（可能构建并立即运行内核；首次接入优先使用上面的分阶段入口）：

```bash
python3 tools/FTC-Knowledge-Bank/.agents/skills/ftckb-integrate/scripts/verify.py --project .
```

| 输出/退出码 | 意义 |
| --- | --- |
| `installationOk=true`、`projectCheck.ok=true`，0 | 接入有效且本次 diff 硬检查通过；仍有 soft 待评估 |
| `installationOk=true`、`projectCheck.ok=false`，1 | 接入有效，但目标已有或新增硬违规，不能交付为通过 |
| `installationOk=false`，2 | 配置/版本/构建/schema/知识加载或规则冲突等错误 |

`project.py` 的成功调用透传 kernel JSON 和退出码（check 0/1/2/64）；包装器自身失败是独立 `integrationOk=false` 输出，不冒充 kernel 成功。验收不推断违规是谁引入的；Agent 应记录开始时的工作区基线并如实区分。

## 规则会不会太严格？

硬规则与软建议应区分：接入参数、版本一致性、避免覆盖用户内容是工程保护；只有适用范围明确、可可靠判定的代码规范才适合硬检查。命名、架构、调参经验等当前多数是 soft。

当前 **5 条生效规则带硬检查**。跨赛季 `global.test-utility-layout` 硬拦截确定的错误 TeamCode 路径和 JUnit 新增（退出码 1）：机器人侧 OpMode 在 `TeamCode/src/main/java/org/firstinspires/ftc/teamcode/tests/`，可复用工具在 `TeamCode/src/main/java/org/firstinspires/ftc/teamcode/utils/`。完整 Agent instruction 仍负责无法由路径可靠判定的语义分类；此规则不禁止 Knowledge Bank 自身的 Kotlin/CLI JUnit 测试。`official.keep-customizations-in-teamcode` 只对 `build.common.gradle` 保持 hard 保护；`global.vendor-documented-build-dependencies` 对 `build.dependencies.gradle` 的任何触及（含删除/重命名）输出跨赛季条件式 soft，要求 Agent 提供第一方厂商文档或固定 commit、精确依赖版本和 diff 逐项对应。规则引擎不联网鉴别来源、不验证证据真伪，soft 不代表机器已证明合规，也不是静默放行。两条 approved Limelight validity/freshness 规则没有 hard checks，而是 `reviewTriggers` 驱动的**条件式 soft**：相机类型或结果读取的新增行命中触发条件才进入 `soft`；无关 Java 不产生 Limelight soft。触发 soft 时 check 仍是退出码 0（没有其他硬违规时），只要求人工/模型复核，不是机器已证明违规或硬件安全；**Agent 必须向用户报告**该 soft，且不得插入无意义的 `isValid()`/时间戳代码。check 仍是静态文本检查，不是完整 Java 语义分析，也不能证明硬件安全。

## fresh clone、升级和失败恢复

其他队员克隆已经提交了接入配置的项目后：

```bash
git submodule update --init -- tools/FTC-Knowledge-Bank
python3 tools/FTC-Knowledge-Bank/.agents/skills/ftckb-integrate/scripts/verify.py --project .
```

旧 v1 项目保持原固定 SHA，不自动迁移。v1→v2 升级必须显式选择 profile；已有有效 v2 配置升级时可以保留原选择。升级时显式选择新版本，先 dry-run，再运行安装器的 `--ref NEW_TAG_OR_SHA`。它更新 gitlink、配置和未被人工修改的模板，重新构建并验收。没有 `--ref` 不升级；不要 `git pull` submodule 或 `git submodule update --remote`。

构建失败：托管文件和固定 submodule 保留，修正 JDK、缓存权限或网络后重跑安装或 verify。版本/路径冲突：先查看 `git status`、`.gitmodules`、配置与 submodule HEAD，不直接 reset/删除目录。安装中途被强制终止可能留下 Git 注册或部分托管文件；脚本宁可拒绝未知残留，也不猜测并删除。审阅具体残留后人工恢复；不要执行全仓库 `git clean -fdx` 或 `git reset --hard`。

## 可选 CI 门禁（不自动安装）

Skill 依靠 Agent 遵循；要阻止不合规 PR 合并，需维护者另行配置 CI 和分支保护。PR checkout 已经是提交状态，默认工作区 check 会看到空 diff，所以 CI 必须检查 PR 的明确差异范围：

```bash
# BASE_SHA/HEAD_SHA 必须由可信 CI 提供；checkout/fetch 应包含相关历史。
git submodule update --init -- tools/FTC-Knowledge-Bank
git diff --no-ext-diff --binary "$BASE_SHA" "$HEAD_SHA" -- > /tmp/ftckb-pr.patch
python3 tools/FTC-Knowledge-Bank/.agents/skills/ftckb-integrate/scripts/project.py \
  check --project . --diff /tmp/ftckb-pr.patch
```

维护者应保护 `.ftckb/project.yaml`、`.gitmodules`、Skill 和知识库版本升级的审阅权限；否则 PR 自改门禁同样可绕过。此示例不部署机器人，不把 CI/编译通过描述为真机通过。

## 开发验收

```bash
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s tests/integration -v
# 显式启用较重的固定源码构建 + 真实 CLI 验收：
FTCKB_REAL_INTEGRATION=1 PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s tests/integration -k test_real_cli_from_pinned_source -v
# 核心/CLI 全套（不拉取 Android Studio 插件测试平台）：
./gradlew :modules:domain:test :modules:knowledge:test :modules:model-provider:test \
  :modules:model-provider-openai-compatible:test :modules:repository-analysis:test \
  :modules:tooling-git:test :modules:agent-runtime:test :modules:standardizer:test \
  :modules:session-shell:test :apps:knowledge-cli:test :apps:knowledge-cli:installDist
```

普通 Python 用例使用真实 Git、隔离构建/CLI 夹具检验安装行为。真实 CLI 与发布验证结果单独记录，不把夹具成功或 Windows launcher 参数测试称为 Windows 实机通过。

历史基线（不是 V0.5.0 当前验收），2026-09-08 验收：Python 常规 22 项通过（含已安装项目 dry-run 不刷新 index 回归）；单独开启的真实源码安装测试 1 项通过（临时 Git 源仓库固定 SHA→真实 Gradle installDist→validate/resolve→故意违规的 check exit 1），在最终 Git dry-run 修复后再次通过。Kotlin 核心与 CLI 422 项，421 通过、1 项原有 installDist 预期路径假设检查跳过；无失败。10 份 kernel JSON fixtures 均通过 jsonschema；两份 Skill 通过结构校验；本知识库 validate/resolve/check 通过。`./gradlew test` 全仓库尝试长时间无输出后中止，因此 Android Studio 插件全量测试未完成；Windows 原生环境、部署和真机运行未验证。
