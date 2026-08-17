# AGENTS.md — FTC Knowledge Bank 对 AI 编码 Agent 的接入说明

面向 Codex / Claude Code / Qoder / DSH 等任何能执行 shell、读文件的 Agent。
本文件是入口；完整契约见 `docs/kernel-contract.md`。

## 一句话

本仓库 = FTC 知识库 + 确定性“策略裁决器”CLI（`ftckb`）+ 本地聊天/网页 Agent。
你（Agent）不要自己读规则文本做裁决 —— 调用 `ftckb resolve --json` 拿确定性结果。

## 快速接入

### 1. 构建（新克隆的机器；本机已有产物可跳过）

```bash
./gradlew :apps:knowledge-cli:installDist
# 产物在 apps/knowledge-cli/build/install/ftckb/bin/ftckb
# JDK 21 工具链缺省时由 Foojay resolver 自动下载；沙箱禁写 ~/.gradle 时：
# GRADLE_USER_HOME=/tmp/xxx ./gradlew :apps:knowledge-cli:installDist
```

本仓库 `.worktrees/cli-agent/apps/knowledge-cli/build/install/ftckb/bin/ftckb`
有本机已构建的启动器（`build/` 不入库，新环境必须重新构建）。

### 2. 契约调用（稳定、版本化、确定性）

```bash
ftckb validate knowledge --json
ftckb resolve knowledge --team 20827 --season 2025-2026 --json
```

- `validate`：加载并校验全部规则 → `{schemaVersion,command,ok,ruleCount,violations}`。
- `resolve`：按队伍+赛季裁决出生效规则 → `{activeRules:[…],conflicts:[…]}`，
  每条规则含 id/topic/title/instruction/rationale/status/authority/applicability/evidence。
- 退出码：`0` 成功；`2` 加载/校验失败或存在冲突；`64` 参数错误。
- 带 `--json` 时**所有失败路径也是 JSON**（`error.code`: `usage` | `load-error` | `invalid-knowledge`）。
- 确定性：activeRules 按 id 排序、conflicts 按 topic 排序——同输入同输出，可以缓存。
- 完整字段表、示例与变更策略见 `docs/kernel-contract.md`；摘要见 README「用法二」。

## 关键规则（不要做）

- 不要把 `knowledge/*.yaml` 当普通文本解释裁决：规则优先级 OFFICIAL > TEAM > SHARED 与
  冲突检测由 `ftckb resolve` 的确定性代码执行，绕过它会得到错误结论。
- 修改任何规则/知识文件后，必须 `ftckb validate knowledge --json` 通过（`ok:true`）才算数。
- `chat` / `eval` / `serve` 是给人用的本地交互模式，不属于机器契约；对接只用 validate/resolve。
- 契约破坏性变更必须提升 `schemaVersion`；消费方看到 `schemaVersion!=1` 应停止并报错。
- 文档中的规则数/测试数快照（当前 27 条规则、404 项测试）随改动同步更新。
- 已知现状：`knowledge/teams/` 下的队伍规则目前全部是 `candidate`，所以 `resolve` 的结果暂时与 `--team` 无关（activeRules 相同），对接方不要误判为 bug。
- 机器可消费工件：`docs/kernel-contract.schema.json`（JSON Schema）与 `fixtures/kernel/*.json`（真实输出示例）可直接用来对拍你的解析器。

## 目录速览

- `knowledge/` — 规则（`official/` `shared/` `teams/<编号>/`）与教程（`guides/`）
- `modules/` — Kotlin 多模块：domain / knowledge / model-provider(-openai-compatible) / repository-analysis / tooling-git / agent-runtime
- `apps/knowledge-cli/` — `ftckb` CLI（applicationName=`ftckb`）
- `fixtures/` — 测试 fixture（含 `agent/eval` 5 场景质量评估）
- `docs/` — `cli-agent.md`（用户文档）、`kernel-contract.md`（机器契约）
