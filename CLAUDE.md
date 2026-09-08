# CLAUDE.md — 本仓库对 AI 编码 Agent 的接入说明（Claude Code 入口）

将本仓库接入目标 FTC 项目时，先读取 [`.agents/skills/ftckb-integrate/SKILL.md`](.agents/skills/ftckb-integrate/SKILL.md)，使用项目级配置和固定版本 submodule；不要把知识库加入 Android 运行依赖。

完整接入说明见同目录 `AGENTS.md`（本文件是它的 Claude Code 兼容入口）。

核心约定：

- 不要直接读 `knowledge/*.yaml` 做规则裁决；调用 `ftckb resolve knowledge --team N --season YYYY-YYYY --json` 拿确定性结果（OFFICIAL > TEAM > SHARED 与冲突检测由该 CLI 执行）。
- 机器契约：`ftckb validate/resolve/check --json`，完整定义见 `docs/kernel-contract.md`。修改前 resolve，交付前 check；硬违规不能宣称通过，soft 如实报告。
- 修改知识后必须 `ftckb validate knowledge --json` 通过；契约破坏性变更必须提升 schemaVersion。
- 构建：`./gradlew :apps:knowledge-cli:installDist`（JDK 21）。
