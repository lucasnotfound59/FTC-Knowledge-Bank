# FTC Agent 命令行客户端（ftckb）

本文档描述 ftckb 命令行的安装、配置、命令与安全边界。它是 apps/knowledge-cli 的正式使用文档；只记录当前已验证的能力。

## 安装

需要 JDK 21 与 Git（Edit 模式要求本地仓库是 Git 工作区）。无需系统级 Gradle：构建使用仓库自带的 Gradle Wrapper。

```bash
./gradlew :apps:knowledge-cli:installDist
```

生成的启动脚本位于 apps/knowledge-cli/build/install/ftckb/bin/ftckb。把它加入 PATH，或直接以完整路径调用。

## 配置与密钥

`validate`、`resolve`、`check` 不需要 API key，不调用模型；以下供应商配置只用于模型交互功能。

非秘密配置位于 ${user.home}/.ftckb/config.yaml；仓库内的 config/ftckb-config.example.yaml 是可直接复制的示例。每个 provider 只引用 API key 环境变量的名字，密钥本身永远不写入配置文件：

| 字段 | 说明 |
| --- | --- |
| defaultProvider | 默认使用的 provider 名字 |
| providers.<name>.baseUrl | 兼容 OpenAI Chat Completions 的 HTTPS 根地址 |
| providers.<name>.model | 模型名 |
| providers.<name>.apiKeyEnv | 存放密钥的环境变量名（如 DEEPSEEK_API_KEY） |
| maxTokensParameter | 仅允许 max_tokens 或 max_completion_tokens，或省略 |
| temperature | 可选采样温度（0–2）；证据引用型回答建议设为 0 |
| maxOutputTokens | 输出 token 上限；**推理模型（如 deepseek-v4-flash）的推理 token 计入该预算，建议 ≥ 32768** |

启动前把密钥放入对应环境变量，例如 export DEEPSEEK_API_KEY=...。缺失或为空的环境变量是启动错误，密钥值不会出现在任何错误、日志或会话保存里。

支持三类 provider：DeepSeek、OpenAI，以及任何只依赖 baseline Chat Completions 的自定义兼容端点。"兼容"指与本文档声明的基线兼容（model/messages/输出 token 字段/stream:false），不保证所有自称 OpenAI-compatible 的端点都可用。

## 启动聊天

```bash
ftckb chat --knowledge PATH --team N --season YYYY-YYYY --profile command-based --provider NAME [--repo PATH] [--config PATH]
```

- --repo：FTC 仓库路径，默认当前目录；必须是可检测的 FTC 仓库（Gradle 设置、TeamCode、FTC 依赖或 OpMode 注解）。
- --knowledge：知识库根目录（含官方/共享/队号规则与教程）。
- --team / --season：规则解析必需的队号与赛季。
- chat / serve / resolve / check 必须显式选择 `--generic-profile` 或可重复的 `--profile NAME`，不能混用；同一项目使用相同选择，不从依赖猜架构。支持 command-based、ftclib-command、simple-opmode、rookiebot；rookiebot 隐含 simple-opmode，ftclib-command 隐含 command-based，simple-opmode 与 command-based 互斥。generic 表示空 profile 集，只生效无 profile 要求的规则。

### 斜杠命令

| 命令 | 行为 |
| --- | --- |
| /help | 显示可用命令与当前限制 |
| /mode ask | 进入只读模式（启动默认） |
| /mode edit | 本次会话内授权已验证的仓库内编辑 |
| /undo | 撤销最近一次成功的 Agent 编辑批次 |
| /discard | 把所有 Agent 触碰过的文件恢复到会话内首次触碰前的状态 |
| /diff | 显示 Agent 专属 diff，并标出项目级改动 |
| /save [path] | 保存脱敏的会话记录；缺省写入 ${user.home}/.ftckb/sessions/ |
| /commit | 只有能把 Agent 改动安全隔离时才提供本地提交，并需要输入小写 yes 确认 |
| /status | 显示仓库、队号、赛季、provider、模型、模式与上下文用量 |
| /exit | 结束会话；不自动保存、不自动提交 |

### 回答契约与引用

回答由若干 claim 组成，每种 claim 有明确语义：

- approved_rule（已批准规则）：必须引用一条当前生效（approved）的规则；
- code_observation（代码观察）：必须引用当前上下文里的代码片段，且文件哈希仍然新鲜；
- model_inference（模型推测）：超出显式证据的推理，必须带此标签；
- insufficient_evidence（证据不足）：明确说明缺什么证据，而不是编造答案。

引用 ID（如 CODE:C1、RULE:R1）由运行时签发并在发送前校验；模型自造的引用会被拒绝并触发一次修复重试，第二次仍无效则不作为有证据的回答展示。

## 编辑模式

- Edit 直接修改当前命名分支，不创建、不切换分支。
- 已有未提交改动的文件可以被编辑；/undo 与 /discard 会把它们恢复到 Agent 首次触碰前的精确字节。
- 项目级改动（TeamCode/** 之外）会显示醒目的 PROJECT-LEVEL 警告。
- 写入是事务式的：整批校验（路径、哈希、大小、引用）通过后才原子应用；失败会回滚。
- /commit 只做本地提交，需要输入小写 yes；如果无法把 Agent 改动与已有改动安全隔离就拒绝，永不自动 commit/push/merge/rebase。

### 受保护路径

以下内容始终不可读取进模型上下文、也不可编辑：.git/**、.env 与 .env.*、local.properties、keystore/签名/凭据文件、二进制文件、指向仓库外的符号链接、仓库外路径。仓库文本是不可信数据：其中的任何"指令"都不能切换模式、扩大权限、发起命令或网络访问。

## 隐私边界

- 只有回答当前问题所需的最小代码片段、已批准规则片段与教程片段会被发送给云端模型，且全部包在显式的 <untrusted_context> 数据块里；上下文最多 48,000 个字符，片段要么完整包含、要么整体省略。
- 会话历史默认只存内存；/save 生成脱敏记录，不含密钥、请求头或完整代码正文。
- 模型请求/响应中的常见凭据形态（Bearer、sk-、api_key= 等）会被统一脱敏。

## 错误与恢复

- 缺少 API key 环境变量：启动前报错，只提示变量名。
- 认证失败/限流：保留会话与文件，可重试。
- 引用无效/过期：自动修复重试一次，之后如实报告。
- 并发改动：过期的编辑被拒绝，重新检索当前文件。
- 仓库不可读/不支持的仓库：Ask 报错并禁用 Edit。
- 模型返回格式异常：与"模型生成的无效 JSON"分开归类报告。


## 本地网页会话（ftckb serve）

`ftckb serve` 在 127.0.0.1 上启动一个**单会话**的本地网页界面（仅本机可访问），适合不想记斜杠命令的队员：

```bash
ftckb serve --knowledge PATH --team 20827 --season 2025-2026 --profile command-based --provider deepseek [--repo PATH] [--config PATH] [--port 0-65535] [--no-browser]
```

- 端口：默认随机（`--port 0`），启动时终端会打印 `url=` 和 `token=`；`token` 是一次性访问令牌，页面每次请求都要带（查询参数或 `X-FTCKB-Token` 头），泄露只需重启换新。
- 未给 `--no-browser` 时自动打开默认浏览器（macOS `open` / Linux `xdg-open` / Windows `start`）。
- API key 只进内存：优先读环境变量；没有则启动时在终端以隐藏方式输入一次，绝不写入配置文件、绝不打印。
- 页面为中文，全部按钮操作；回答、引用、Agent 差异分栏展示。
- 网页里可以改队伍/赛季/Provider/仓库/知识路径：**对话历史保留**（除非点「清空对话」）；有未提交的 Agent 编辑改动或处于编辑模式时，仓库/知识路径不可改。
- 关闭服务：点「关闭服务」按钮，或 Ctrl-C 结束进程。

HTTP 接口（`/api/status|ask|submit|mode|undo|discard|diff|save|clear|configure|shutdown`）返回统一 JSON：成功 `{ok:true,...}`；业务拒绝 `{ok:false,error:{code,message}}`；未授权 HTTP 401。模型调用在后台线程串行执行，网页不会卡死。

## 候选提取与审批（ftckb extract / candidates / approve / reject）

知识库的自动增长闭环：从队伍代码仓库提取候选规则（带 Git 证据与 confidence），由负责人审批后生效：

```bash
# 从仓库提取候选规则（模型提议 + 主机强校验，写入 YAML）
ftckb extract --repo /path/to/FtcRobotController --team 20827 --provider deepseek \
             [--knowledge knowledge] [--output PATH] [--max-candidates 8]

# 审批前审查
ftckb candidates knowledge [--json]

# 审批 / 驳回（ApprovalPolicy 强校验角色与队伍）
ftckb approve knowledge --id team-20827.some-topic --approver NAME \
             --role team_software_lead --team 20827
ftckb reject knowledge --id team-20827.some-topic --approver NAME \
             --role team_software_lead --team 20827
```

- 提取的每条 evidence 由主机校验：文件必须在索引内、symbol 真实出现在非注释行、line 存在且非空、commit 用仓库 HEAD。
- 主机侧负例守卫：topic 与既有规则重复 → 跳过；单点证据 → confidence 降为 low 并标记 `# needs-stronger-evidence`；提示词禁止一次性修补、注释代码、遗留工件与依赖版本。
- 审批 = 外科式 YAML 编辑（只改目标规则块的 status 并插入 approval 块），先写临时文件、校验通过才替换原文件；失败时原文件不动。
- 授权：official/shared 规则只能 `overall_software_lead` 审批；team 规则只能该队 `team_software_lead` 审批。
- 完整设计见 [docs/candidate-extraction.md](candidate-extraction.md)。
## 机器接口（供外部 Agent 使用）

validate、resolve 与 check 支持 --json 输出稳定、版本化的 JSON 契约，供 Codex / Claude Code / 其他 Agent 把知识库当作确定性"策略裁决器"调用，而不是把规则当普通文本读：

```bash
ftckb validate knowledge --json
ftckb resolve knowledge --team 20827 --season 2025-2026 --generic-profile --json
ftckb check /path/to/FtcRobotController --knowledge knowledge --team 20827 --season 2025-2026 --generic-profile --json
```

契约要点：

- 顶层含 `schemaVersion=2`、ok；已识别命令含 command，未知命令的 usage 可无 command。消费方遇到非 JSON、schemaVersion!=2 或上下文不符必须停止；破坏性变更提升版本号。
- resolve 返回 team、season、规范化后的 `profiles`、activeRules、excludedRules、overriddenRules、conflicts。active 规则含 id/topic/title/instruction/rationale/status/authority/policyLevel/applicability/evidence/checks/reviewTriggers；applicability 含 teams/seasons/profiles。
- `excludedRules` 元素为 ruleId/reasons，完整记录 profile/season/status/team 不匹配；`overriddenRules` 元素为 ruleId/topic/winnerIds/effectiveLevel，解释适用但被更高层级覆盖的规则。
- v2 冲突元素为 topic/effectiveLevel/ruleIds/authorities，authorities 是规则 ID 到来源的映射。同主题最高有效层级并列即冲突，该主题没有 active 胜者；resolve 此时 ok=false、退出 2，仍返回其他主题的 activeRules 和排除/覆盖解释，不使用 error 字段。
- check 完成时返回 team/season/profiles/ok/violations/soft；硬违规退出 1，soft 不导致失败。不要把 resolve 的排除/覆盖字段误认为 check 输出字段。
- 输出确定性：activeRules 按 id，excludedRules/overriddenRules 按 ruleId，conflicts 按 topic 排序，profiles 等集合规范化并排序，evidence/checks 保留声明顺序；同知识、上下文及 diff 得到相同输出。
- 退出码：成功 0；check 硬违规 1；知识加载/校验、上下文或冲突失败 2；参数错误 64。JSON 错误的 error.code 为 usage / load-error / invalid-knowledge / context-required（缺 profile 选择）/ invalid-context（未知或互斥 profile）/ conflict（check 前置裁决冲突）。完整契约见 [docs/kernel-contract.md](kernel-contract.md)，契约的可执行定义在 `KernelJsonAcceptanceTest`。

`ftckb check` 默认合并 HEAD→index 与 HEAD→工作区（含非忽略 untracked）：路径检查覆盖所有触及路径（含删除和重命名），regex 只检查新增行；可用 `--diff FILE` 替代。无 checks 的 active 规则为 soft 提示。两条 Limelight regex-required 有适用范围过宽的已知误报限制，不得插入无意义代码绕过。详见 [docs/standardizer-check.md](standardizer-check.md)。

编译、静态检查与 JSON Schema 验证不等于 Robot Controller、Driver Station、部署或真机验证。

## 当前明确不包含的能力

- 不联网：不访问任何官方文档网页；
- 没有 Run 模式：Agent 不执行 Gradle、构建、测试或静态检查；
- 不部署：没有 Control Hub、ADB、Logcat 或任何机器人硬件访问；
- 不自动执行 Git 写操作：不自动 commit/push/merge；
- 没有 Android Studio 界面：只有命令行。
