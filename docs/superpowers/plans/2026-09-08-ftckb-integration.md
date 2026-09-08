# FTC 项目级接入 Implementation Plan

> **For agentic workers:** Use subagent-driven-development for the bounded kernel task and an independent review; implement the tightly coupled installer/runtime files together. Track the checkboxes below. Work stays on the user's existing branch. This plan covers implementation and verification; commit/push follow the user's subsequent explicit authorization, and release tags remain separate.

**Goal:** 提供可复用、固定版本、可验收的 FTC 知识库项目级接入。

**Architecture:** 独立 Kotlin CLI 负责裁决与执法；Python 包装负责 Git 安装、配置、执行与验收；Skill 负责 Agent 行为。配置只保存可移植项目数据。

**Tech Stack:** Kotlin/JVM 21、JGit、JUnit 5、Python 3.10+、unittest、JSON Schema draft-07/jsonschema。

## Global Constraints

- 直接在用户当前分支修改，不创建分支，不自动 commit/push，不安装 hooks。
- 固定完整 commit SHA；tag 解析后也记录 SHA。不追踪 main，不使用 submodule update --remote。
- `--dry-run` 不修改目标文件、index、Git 配置，不执行构建。
- 队号/赛季不从 README 或当前日期猜测；缺少参数给出可操作错误。
- 保留用户已有未提交改动；冲突时失败，不强制覆盖。
- 修改前 resolve，修改后 check；schemaVersion 必须为 1，硬违规阻止交付，soft 如实报告。
- 不把编译或软件检查称为真机验证。

### Task 1: 完整 check 契约与 diff 覆盖

**Files:**
- Modify: `modules/standardizer/src/main/kotlin/org/ftckb/standardizer/Standardizer.kt`
- Modify: `apps/knowledge-cli/src/main/kotlin/org/ftckb/cli/CheckCommand.kt`（仅如错误路径需要）
- Modify: `docs/kernel-contract.schema.json`
- Test: `modules/standardizer/src/test/kotlin/org/ftckb/standardizer/StandardizerTest.kt`
- Test: `apps/knowledge-cli/src/test/kotlin/org/ftckb/cli/CheckAcceptanceTest.kt`
- Create: `fixtures/kernel/check-*.json`（来自实际 CLI 输出，覆盖 pass/hard/usage/load/conflict）

**Interfaces:** 保持 `DiffChange(path, addedLines)`、`parsePatch(String)`、`worktreeChanges(Path)`、CLI JSON 字段和 exit code 兼容。路径 checks 看所有触及路径；regex 看新增行。HEAD→index 和 HEAD→worktree 都不能漏掉待提交违规；同一文件同一行去重并稳定排序。

- [x] 写回归测试：暂存违规、未暂存违规、untracked 新文件、删除、仅删行、重命名两端、空文件、路径含空格/中文、非法补丁报错。关键断言：

```kotlin
assertTrue(Standardizer.worktreeChanges(repo).any { it.path=="build.common.gradle" })
assertEquals(1,runCheck(repo,knowledge,listOf("--json")).first)
```

- [x] 跑 focused tests 验证修复（实现报告未保留修改前 RED 日志），修复采集/解析，不能用读取失败→空列表吞错。
- [x] 扩展 schema，加入 check 结构和 conflict error code；验证所有 fixtures。
- [x] `JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew :modules:standardizer:test :apps:knowledge-cli:test --tests '*CheckAcceptanceTest' --console=plain`；模块测试必要时单独调用以免 --tests 过滤无匹配。记录结果，不提交。

### Task 2: 安装器、配置、运行器与验收

**Files:** `.agents/skills/ftckb-integrate/scripts/{integrate,project,verify}.py`、`assets/{AGENTS.block.md,project-skill/SKILL.md}`、`tests/integration/test_integration.py`。

**Interfaces:** `integrate.py --project PATH --team N --season YYYY-YYYY --ref TAG_OR_SHA [--repository URL] [--dry-run]`；`project.py {validate,resolve,check} --project PATH [--diff PATCH]` 透传 kernel JSON/exit；`verify.py --project PATH` 汇总 installationOk、projectCheck 和退出码。

- [x] 用临时 Git 源仓库与目标项目写 unittest，固定 tag、已有文件、index 快照。
- [x] 实现预检/显式参数/版本解析/托管文件计划/拒绝冲突，dry-run 只输出计划。
- [x] 实现 submodule 安装与显式升级、内容摘要保护和可重试构建；不改 Android Gradle 文件。
- [x] 实现 project.py 固定版本检查、三种命令参数与跨平台 launcher。
- [x] 实现 verify.py schema+exit code 联合校验，区分安装失败、规则冲突、目标硬违规。
- [x] `python3 -m unittest discover -s tests/integration -v` 必须通过；真实 CLI 在临时 FTC 项目完成 resolve→修改→check 验收。

```python
before=snapshot(project)
result=run_install(project,dry_run=True)
self.assertEqual(0,result.returncode)
self.assertEqual(before,snapshot(project))
```

### Task 3: Agent 入口、文档和最终验收

**Files:** `.agents/skills/ftckb-integrate/SKILL.md`、`README.md`、`AGENTS.md`、`CLAUDE.md`、`docs/{project-integration,kernel-contract,standardizer-check}.md`、`todolist.md`。

- [x] 编写真实安装入口与项目 resolve→修改→check 契约；自动发现能力按 Agent 平台如实描述。
- [x] 入口顶部链接 Skill；文档给出安装、dry-run、重跑、升级、fresh clone、CI 命令和平台前提。
- [x] 按 skill-creator 的 quick_validate.py 验证两份 Skill。
- [x] 执行 Python 测试、Kotlin 全部核心模块与 CLI 测试（插件全量尝试中止，未计通过）、真实 CLI 三命令、schema fixtures 对拍及本仓库 diff check。
- [x] 更新实际测试结果、todolist 和本计划，不把未执行平台或真机测试记为通过。
- [x] 独立只读 review，修复重要问题后复测；最后汇报本地完成范围和未发布状态。

## 交付记录

2026-09-08：已完成本地实现。核心/CLI Kotlin 422 项（421 通过、1 旧路径假设用例跳过）；Python 常规 22 项通过，真实固定源码构建与 CLI 测试 1 项单独通过；两份 Skill、10 份 JSON fixtures、本仓库 validate/resolve/check 均通过。最终审查发现的 dry-run index 隐式刷新已修复，新增回归有 RED→GREEN 证据，复审 PASS。

本地验收后，用户明确授权提交并推送本次实现；发布 tag 另行安排。仅提交本次接入功能，保留用户原分支与其他未跟踪文件。Android Studio 插件全量测试尝试中止，Windows 原生、部署与真机未验证。Limelight 规则适用范围治理仍需维护者确认，不包含在本次规则修改中。
