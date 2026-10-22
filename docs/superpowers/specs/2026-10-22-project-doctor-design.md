# Project doctor 与统一 Agent 工作流

日期：2026-10-22。范围已经确定：优先让 Agent 容易正确接入和使用；本轮不自动 commit/push。

## 目标与选择

新增 `project.py doctor --project .`，在改代码前说明实际接入状态、可独立发现的问题和准确下一步。
选择独立只读 doctor 模块，复用纯校验函数；不复用会构建、执行内核或提前退出的 verify/resolve 链。
相比增强 verify，这保留现有调用语义；相比自动修复，这让诊断安全、可审阅且适用于旧安装。
统一生成 Skill、AGENTS 托管段和用户文档为 `doctor → Dashboard 已连接 → resolve → 修改 → check`。

## 调用与独立报告契约

- 入口：`python3 <已审阅知识库>/.../scripts/project.py doctor --project <Git根目录>`；总是输出单个 JSON。
- 顶层：`doctorSchemaVersion:1`、`command:"doctor"`、`ok`、`projectRoot`、`context`、`checks`、`nextSteps`；参数错误另含 `error:{code:"usage",message:...}`。
- `context` 仅含验证过的 team、season、原选 `profiles`、`normalizedProfiles`、协议版本、完整 commit pin、实际 knowledgePath；无法验证的字段为 null，不猜测、不回显原始配置。
- 每个 check 含稳定 `code`、`status`、安全 `message`，可含 `blockedBy:[code]`；数组按检查顺序稳定输出。
- `status` 为 `passed|failed|blocked|unknown|not_checked`；failed 表示发现确定问题，blocked 表示前置失败，unknown 表示现有证据不足，not_checked 表示本工具明确不执行的阶段。
- 必需检查全部 passed 才 `ok:true`；必需检查 failed/blocked/unknown 均 `ok:false`。规则裁决、diff 检查、Dashboard 连接和硬件验证始终 not_checked，不影响接入诊断 ok。
- 退出码：0=上述接入诊断通过；1=诊断发现问题或无法确认必需项；64=doctor 参数错误。所有路径遵循 doctor 自有契约，不冒充 kernel JSON v2。
- `nextSteps` 条目为稳定 `code`、说明、`cwd` 和 `argv`；需要人工选择或无法安全确定参数时只给说明，不拼猜测命令。只建议，绝不执行。

## 检查顺序与前置

1. 解析 doctor 参数、确认目录存在且是有 HEAD 的 Git 根目录；Git 不可用/超时给明确失败，后续依赖检查 blocked。
2. 安全读取 `.ftckb/project.yaml`；缺文件单独报告 missing，语法/重复键错误报告 malformed，不误导为“都改 JSON 语法”。
3. 配置可解析为对象后，分别验证协议版本、team、season、profiles、固定 source.path/commit、repository/ref 存在性、managedFiles receipts；汇总相互独立的错误。
4. 使用可信字段分别检查 `.gitmodules` 唯一条目和 URL 一致性、index gitlink、submodule 初始化及独立根、HEAD pin、干净状态、knowledge 目录和源码协议 manifest；前置不足的检查 blocked，其他检查继续。
5. 独立检查 Skill 内容摘要、AGENTS 托管标记与摘要；安全路径禁止 symlink 穿越。配置 receipt 损坏不妨碍报告文件存在性。
6. 检查当前 Python>=3.10、Git、jsonschema 可用性及 Java/JDK 前提；只运行固定、限时的工具版本查询，不运行项目提供的脚本。
7. 静态检查 CLI launcher、必需发行目录/库与已有 build receipt；receipt 最多证明与其记录匹配，不证明所有产物真实来源或完整可运行。缺产物给 build 建议，不自动 build，也不执行 launcher。
8. 静态确认固定源码是否支持 Dashboard；可靠正证据才提供该源码 cwd 与 `dashboard --knowledge <实际路径> --no-browser` argv。已知旧版明确提示显式升级；证据不足为 unknown，不声称已支持。

## 安全、兼容与建议策略

doctor 禁止网络、构建、启动 Dashboard、运行 kernel、改配置/pin、执行目标项目脚本；不安装依赖，不产生 audit 调用。
专用 Git runner 固定只读子命令，关闭 core.fsmonitor 和 optional locks，禁交互/外部 helper，设置超时；不调用 hooks、diff/textconv 或任意 alias。
报告使用固定文案，不回显原始 stderr、repository URL/ref、token 或任意配置文本；合法 team/season/profile/pin/path 可输出，路径和 argv 始终作为数据传递。
无配置先说明需明确 team/season/profile；不得建议用户运行尚不存在的 submodule 脚本。未初始化可建议固定路径 `git submodule update --init -- tools/FTC-Knowledge-Bank`，但先核验元数据。
旧 pin 不含 doctor 时，文档指向外部已审阅 checkout 的只读 doctor；这不改变目标 pin，后续 resolve/check 仍用原固定源码。升级是独立显式选择。
不更改 kernel JSON v2、接入协议 v1/v2、已有 resolve/check/verify 输出、work-mode 或实际 knowledge；不让 doctor 代替任何规则/代码/真机结论。

## 文件责任与验收

唯一 implementer 负责 scripts/project.py 的 doctor 路由、新只读 helper、tests/integration/test_integration.py、两份生成模板、接入 Skill、README 和 docs/project-integration.md。
独立 reviewer 只读复核契约、安全边界、旧调用兼容与演示证据；root 负责验收和向用户交付，不代写实现。
针对性测试：有效 v2 generic/命名与 v1；缺失/坏 JSON/重复键；四个独立配置错误同时报告；根目录/dirty/pin/gitlink/submodule/managed file/CLI 缺失；超时与敏感输出脱敏；doctor 参数错误 JSON。
只读测试用全目录含 `.git` 快照、禁止网络/build/kernel 的哨兵及 fsmonitor 哨兵证明；已有 Python suite 回归确认 resolve/check/install/verify 不变。
隔离 demo：临时 FTC Git fixture 先运行 doctor 展示多错误和下一步，再修复 fixture 后重跑；记录退出码与前后快照，保留命令和 JSON，不触及用户项目，不把 fake kernel 当真实验收。

## 风险与后续

静态诊断无法证明构建产物可运行或 Dashboard 已连接；报告与文案明确这些边界。恶意/损坏配置不能变成命令、日志或路径穿越。
旧安装需外部入口；文档给出先取已审阅 checkout 再只读诊断的具体步骤，不暗中升级。
后续想法仅保留：resolve→check 的显式工作收据；配置驱动的 Dashboard 入口。本轮均不实施。
