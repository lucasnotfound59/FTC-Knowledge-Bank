# AGENTS.md — FTC Knowledge Bank 对 AI 编码 Agent 的接入说明

给 Agent 此仓库链接并要求接入目标 FTC 项目；Agent 应读取 [`.agents/skills/ftckb-integrate/SKILL.md`](.agents/skills/ftckb-integrate/SKILL.md)。安装器生成项目配置与运行时 Skill，固定完整 commit，不自动 commit/push；队号和赛季不明确时询问。

面向 Codex / Claude Code / Qoder / DSH 等任何能执行 shell、读文件的 Agent。
本文件是入口；完整契约见 `docs/kernel-contract.md`。

版本轴：仓库 V0.9.0、CLI 2.1.0、YAML v4、kernel JSON v2、项目接入协议 v2。
`authority` 表示来源，`policyLevel` 表示 global/local/shared 策略；official 来源始终最高。
resolve/check 不需要 API key。必须显式选择 generic 或命名 profile，不从依赖猜测：
`--profile command-based`、`--profile ftclib-command`、`--profile rookiebot`、`--profile simple-opmode`。
rookiebot 隐含 simple-opmode，ftclib-command 隐含 command-based；两种架构不能混用。
同一项目 resolve/check 使用同一选择。candidate、赛季/team/profile 不匹配进入 excludedRules；
同主题低层级进入 overriddenRules；最高有效层级并列则 conflict，不交给模型猜胜者。
编译、静态检查与 JSON Schema 验证不等于 Robot Controller、Driver Station 或真机通过。

## 一句话

本仓库 = FTC 知识库 + 确定性“策略裁决器 + 规范器”CLI（`ftckb`）+ 本地聊天/网页 Agent。
你（Agent）必须先启动或复用实际知识库的 Dashboard 并确认连接，再开始规则或代码工作。
不要自己读规则文本做裁决 —— 写码前调用 `ftckb resolve --json` 拿确定性规则，
提交前调用 `ftckb check` 对自己的 diff 执法，让所有 Agent 写出同一标准的代码。

## 快速接入

### 1. 构建（新克隆的机器；本机已有产物可跳过）

```bash
./gradlew :apps:knowledge-cli:installDist
# 产物在 apps/knowledge-cli/build/install/ftckb/bin/ftckb
# JDK 21 工具链缺省时由 Foojay resolver 自动下载；沙箱禁写 ~/.gradle 时：
# GRADLE_USER_HOME=/tmp/xxx ./gradlew :apps:knowledge-cli:installDist
# macOS 沙箱出现 Kotlin daemon 的 FileSystemException 告警可忽略（自动降级无 daemon 编译）
# 一键自检：scripts/smoke.sh
```

（`build/` 不入库，新环境必须重新构建；本机已有且对应当前源码的产物可复用，源码更新后重新构建。）

### 2. 必须启动或复用 Dashboard，并确认可用

1. 先检查已有终端/管理台，确认知识库路径与审计目录一致；有可用实例就复用，不重复启动或关闭用户已有实例。
2. 在 Knowledge Bank 仓库根目录执行。已接入项目须用配置中的实际知识库路径替换 `knowledge`；产物缺失或源码更新时先构建：

   ```bash
   if [ ! -x apps/knowledge-cli/build/install/ftckb/bin/ftckb ]; then
     ./gradlew :apps:knowledge-cli:installDist
   fi
   FTCKB_KNOWLEDGE_ROOT="$(python3 -c 'from pathlib import Path; print(Path("knowledge").resolve(strict=True))')"
   ```

   已有构建过期时也须重新执行构建命令。旧固定 commit 不支持 dashboard 时，报告限制并按既有流程明确升级，不能暗中改 pin 或声称旧副本已有新审计。
3. 没有可复用实例时，在持久终端/后台会话启动并保留会话 ID；不要让单次命令超时杀死服务：

   ```bash
   python3 .agents/skills/ftckb-integrate/scripts/dashboard_runtime.py \
     --distribution apps/knowledge-cli/build/install/ftckb \
     --knowledge "$FTCKB_KNOWLEDGE_ROOT" --no-browser
   ```

   默认空闲端口，仅监听 `127.0.0.1`，无需模型 API key；可追加 `--port 8080`。后续工作使用另一会话，任务收尾不自动停止 Dashboard。

   推荐入口要求 Python 3.10+ 和 POSIX（macOS/Linux），从构建完成的完整 distribution 创建私有副本后启动；后续 `installDist` 更新原产物不影响该副本。知识库和审计目录仍使用实际路径。直接 `ftckb dashboard` 保持兼容，但没有此保护；Windows 新保护入口不支持。接入项目优先执行已审阅新版 doctor/prepare 返回的实际 `nextSteps.cwd/argv/env`，外部 helper 可保护目标旧 pin 的 Dashboard 二进制，不改 pin；能力未知、缺 helper 或平台不支持时不猜启动命令。生命周期与限制见[管理台指南](docs/dashboard.md#稳定副本与生命周期)。
4. 必须确认 `dashboard=ok`、完整 `url=` 可访问、页面已连接且知识库路径与规则列表正确，再进入 validate/resolve/check 和编码流程。将有效链接交给用户；`#token=` 不写入仓库或公开日志，浏览器刷新缺凭据时重开原链接。失败时报告实际错误及未完成的核验，不能将发出启动命令视为成功。

**这是 Agent 工作流程要求，不是 kernel 执行条件。** 本版本 CLI 自行写入共享审计日志，Dashboard 用于读取和展示；Dashboard 未运行不等于 CLI 没有审计。其他版本、未接入进程或不同知识库分区的记录不能据此声称已覆盖。

### 3. 契约调用（稳定、版本化、确定性）

```bash
ftckb validate knowledge --json
ftckb resolve knowledge --team 20827 --season 2025-2026 --generic-profile --json
ftckb check <repo-root> --knowledge knowledge --team 20827 --season 2025-2026 --generic-profile --json
```

- `validate`：加载并校验全部规则 → `{schemaVersion,command,ok,ruleCount,violations}`。
- `resolve`：按队伍+赛季裁决出生效规则 → `{activeRules:[…],conflicts:[…]}`，
  每条规则含 id/topic/title/instruction/rationale/status/authority/policyLevel/applicability/evidence/checks/reviewTriggers；顶层还有 profiles、excludedRules、overriddenRules。
- 退出码：validate/resolve 为 `0` 成功；`2` 加载/校验失败或存在冲突；`64` 参数错误。
  `check` 为 `0` 通过；`1` 存在硬违规；`2` 加载失败/冲突；`64` 参数错误。
- 带 `--json` 时**所有失败路径也是 JSON**（`error.code`: `usage` | `load-error` | `invalid-knowledge` | `conflict` | `context-required` | `invalid-context`）。
- **规范器**：`check` 合并 HEAD→index 与 HEAD→工作区（含非忽略 untracked）的变化，路径规则检查所有触及路径（含删除/重命名）。`regex-required` 和 review trigger 逐条新增行检查；`regex-forbidden` 先逐条检查，再可匹配连续新增行块。无 checks 且无 `reviewTriggers` 是无条件 soft；无 checks 且有 triggers 是**条件式 soft**，只有路径与新增行匹配才进入 `soft`；有 checks 才是硬检查。当前 **5 条生效规则带硬检查**。`global.test-utility-layout` 跨赛季硬拦截确定的错误路径/JUnit 新增：机器人侧 OpMode 只能放在 `TeamCode/src/main/java/org/firstinspires/ftc/teamcode/tests/`，可复用工具只能放在 `TeamCode/src/main/java/org/firstinspires/ftc/teamcode/utils/`；完整 Agent instruction 仍负责不能由路径判定的语义分类。目标 TeamCode 不使用 JUnit、`src/test` 或 `src/androidTest`，但 Knowledge Bank 自身的 Kotlin/CLI JUnit 测试不受此规则约束。硬违规退出码 1。`official.keep-customizations-in-teamcode` 只对 `build.common.gradle` 保持 hard 保护；`global.vendor-documented-build-dependencies` 让 `build.dependencies.gradle` 的任何触及都进入跨赛季条件式 soft，要求第一方厂商文档/固定 commit、精确依赖版本和 diff 逐项对应；规则引擎不联网、不鉴别域名、不验证证据真伪，soft 不代表机器已证明合规，Agent 必须实际核对来源并报告结论。Limelight validity/freshness 的 `reviewTriggers` 命中仍保持退出码 0（没有其他硬违规时），只请求人工/模型复核，不能证明违规或真机安全；**Agent 必须向用户报告**每项 soft。详见 `docs/standardizer-check.md`。
- 确定性：activeRules 按 id 排序、conflicts 按 topic 排序——同输入同输出，可以缓存。
- 完整字段表、示例与变更策略见 `docs/kernel-contract.md`；摘要见 README「用法二」。

### 4. 规则管理与调用审计

`dashboard` 不需要模型 API key，使用终端输出的本次启动链接访问，仅监听 `127.0.0.1`。它提供规则目录、单规则 YAML 编辑、候选批准/拒绝、确定性裁决、调用审计及逐项复核；操作说明见 [`docs/dashboard.md`](docs/dashboard.md)。普通编辑不能更名规则 ID 或直接晋升状态；修改已批准规则的实质内容或归属会移除 approval 并转回 candidate。批准与拒绝要求明确批准人、角色和适用队伍，复用 ApprovalPolicy；角色声明不等于身份认证。所有写入先预览、校验完整知识库，再核对版本保存，不自动 commit/push。

本版本的 `validate`/`resolve`/`check`、chat/serve 用户请求与模型子调用，以及 dashboard 手动检测/规则事务记录到本地审计。默认目录是 `~/.ftckb/audit`；所有相关进程可用 `FTCKB_AUDIT_DIR` 指定相同目录。按知识库规范路径分区，无法归属知识库的记录单列为 unscoped。审计失败使用独立诊断通道，不能改写 kernel JSON v2 的 stdout 或退出码。

独立复核声明追加到同知识分区的 `reviews.jsonl`（`reviews.lock` 协调写入），不修改 `events.jsonl` 或检测统计。每项只可记录已核对/需修改/本次不适用，理由与复核人必填；身份只是声明，证据链接仅允许安全 http(s) URL，不抓取。保留全部 revision 与 dev 原类型，不解除 hard；来源不可校验时禁写。版本状态区分历史快照、需重新复核、版本未知，缺版本的历史声明不算当前代码结论。代码修改后重新运行检查，新检查默认待复核；只关联实际检查快照，不监控外部编辑、不从日志路径读取用户文件。Dashboard 手动 supplied_patch 可显式关联前次检查重检，但历史不保存补丁正文，必须重新提供补丁并核对上下文。

审计只证明实际记录的阶段：resolve 不代表 diff 已检查；soft 本身不代表人工复核完成；仅独立复核声明能说明指定历史快照有人声明复核，原检测仍保留；中断记录不能算通过。内置 chat/serve 的新编辑在写入前检查本次预览 diff，normal/test 中仍生效的硬违规阻止写入，soft 必须向用户报告；显式 dev 保留每条降级项。chat 的 `/work-mode normal|test|dev` 仅作用于下一条提交的消息，之后重置为 normal；serve 的 ask/submit 可显式传本次 `workMode`，不保存到设置。undo/discard 是独立恢复事务，不冒充新编辑通过检查。旧固定 commit 副本、审计启用前的调用、未接入进程和绕过 CLI 的操作不在覆盖内，不能推断外部 Agent 的全部对话或任意工具调用，也不能擅自将分开的 resolve/check 记录配对。

## 关键规则（不要做）

- 不要把 `knowledge/*.yaml` 当普通文本解释裁决：规则优先级 OFFICIAL > GLOBAL > LOCAL > SHARED 与
  冲突检测由 `ftckb resolve` 的确定性代码执行，绕过它会得到错误结论。
- 修改任何规则/知识文件后，必须 `ftckb validate knowledge --json` 通过（`ok:true`）才算数。
- `chat` / `eval` / `serve` / `dashboard` / `extract` / `candidates` / `approve` 是给人用的交互模式；机器契约只用 validate/resolve/check。Dashboard HTTP API 与审计事件使用独立版本，不能当作 kernel JSON v2。
- 契约破坏性变更必须提升 `schemaVersion`；消费方看到 `schemaVersion!=2` 应停止并报错。
- `resolve`/`check` 的 `--work-mode normal|test|dev` 是逐任务临时参数（省略即 normal，默认 JSON/退出码字节兼容，不写入项目配置）：只有用户明确说明当前是测试代码或 dev 代码才选 `test`/`dev`，不按文件名、路径或依赖推断。`test` 仅把 `global.command-responsibilities` 与 `shared.ftclib-command-candidate` 放入 excludedRules（原因 `work-mode-test`），命令安全与 `global.test-utility-layout`/JUnit 硬检查继续拦截；用户指定的测试代码优先实现测试目的，不需要先提交编码 plan。`dev` 的 `check` 必须同时给 `--diff FILE`，把每个 hard 命中转成带 ruleId/check/path/line/detail 的 soft 并退 0，原有 soft 保留，Agent 必须逐项报告；无效知识、同层级冲突、错误 profile、坏补丁仍是错误，不伪装成通过。显式 test/dev JSON 增加 `workMode` 字段。
- 文档中的规则数/测试数快照随改动同步更新：当前 52 条规则（46 已批准 + 6 候选）；测试数字必须随实际验收更新，历史测试不视为当前通过；平台范围见 README。
- 当前相同赛季/profile 下 20827 与 16093 active IDs 相同；8 条原队伍规则已移到 global，保留 2025-2026 赛季，2 条 candidate 不转正。仍必须传真实队号。
- 机器可消费工件：`docs/kernel-contract.schema.json`（JSON Schema）与 `fixtures/kernel/*.json`（真实输出示例：validate-ok / resolve-ok / resolve-conflict / resolve-test / check-pass / check-hard / check-dev / error-usage / error-invalid-knowledge）可直接用来对拍你的解析器。

## 目录速览

- `knowledge/` — 规则（`official/` `shared/` `teams/<编号>/`）与教程（`guides/`）
- `modules/` — Kotlin 多模块：domain / knowledge / model-provider(-openai-compatible) / repository-analysis / tooling-git / agent-runtime / standardizer / session-shell / audit
- `apps/knowledge-cli/` — `ftckb` CLI（applicationName=`ftckb`）
- `fixtures/` — 测试 fixture（含 `agent/eval` 5 场景质量评估）
- `docs/` — `cli-agent.md`（用户文档）、`dashboard.md`（管理台与审计）、`kernel-contract.md`（机器契约）
