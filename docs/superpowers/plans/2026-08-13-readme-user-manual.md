# README Quick Start and User Manual Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rewrite `README.md` so a first-time FTC team member can run the current Knowledge Core in five minutes and a maintainer can create, validate, approve, resolve, and troubleshoot schema v1 rules without reading source code.

**Architecture:** Keep one README with progressive disclosure: current capability status and quick start first, operational manual second, and conceptual architecture/roadmap last. All commands, formats, roles, precedence, and expected output must be derived from the checked-in Kotlin implementation and canonical YAML rather than future plans.

**Tech Stack:** Markdown, Mermaid, Kotlin/JVM 2.4.10, JDK 21, Gradle Wrapper 9.4.0, YAML schema version 1.

## Global Constraints

- Modify `README.md` only; do not change runtime code, rules, tests, or `todolist.md`.
- Do not claim that repository import, automatic candidate extraction, an Agent runtime, Ask/Edit/Run, an approval UI, Android Studio integration, deployment, Pedro Pathing content, or Limelight content is implemented.
- Current verified snapshot: 59 tests, four checked-in rules, one active official rule, and three inactive repository-derived candidates.
- Use JDK 21 and only the checked-in Gradle Wrapper commands.
- Approval authority must match code: official/shared require `overall_software_lead`; team rules require `team_software_lead` with the same single team number.
- Precedence must match code for the same topic and applicable context: `OFFICIAL > TEAM > SHARED`.
- CLI exit codes must match code: `0` success, `2` load/validation/conflict failure, `64` usage failure.
- Deployment and other hardware-impacting actions remain future work and require explicit human confirmation.

---

### Task 1: Replace the stale project status and add the five-minute quick start

**Files:**
- Modify: `README.md`

**Interfaces:**
- Consumes: `apps/knowledge-cli/src/main/kotlin/org/ftckb/cli/Main.kt`, checked-in `knowledge/` files, and the current JDK/Gradle build.
- Produces: README sections `项目状态` and `5 分钟快速开始`, placed before the architecture explanation.

- [ ] **Step 1: Record the current executable baseline**

Run from the repository root:

```bash
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew --no-daemon clean test
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew --no-daemon :apps:knowledge-cli:run --args='validate knowledge' --quiet
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew --no-daemon :apps:knowledge-cli:run --args='resolve knowledge --team 20827 --season 2025-2026' --quiet
```

Expected:

```text
BUILD SUCCESSFUL
validation=ok rules=4
active official.keep-customizations-in-teamcode
```

- [ ] **Step 2: Replace the stale top-level status statement**

Replace the sentence that labels the entire project as only an early design with a compact status section containing these three categories:

```markdown
## 项目状态

当前仓库已经完成 **Knowledge Core Foundation**，但完整 FTC 编程 Agent 和 IDE 客户端尚未实现。

| 状态 | 能力 |
| --- | --- |
| 已实现 | schema v1 规则模型、严格本地 YAML 加载、证据与审批校验、规则优先级和冲突解析、`validate` / `resolve` CLI、20827 与 16093 初始档案、自动化测试 |
| 部分完成 | `knowledge/` 当前包含 4 条规则：1 条已批准官方规则和 3 条来自参考仓库的候选规则；队伍知识内容仍需扩充 |
| 尚未实现 | 自动导入和分析 FTC 仓库、候选规范提取、审批 UI/历史、Ask/Edit/Run Agent、Android Studio 插件、Control Hub 部署、Pedro Pathing 与 Limelight 新人内容 |
```

Add one sentence immediately below the table stating that the currently verified JDK 21 suite has 59 passing tests and that this count is a snapshot that may grow.

- [ ] **Step 3: Add a copyable five-minute quick start**

Add a `## 5 分钟快速开始` section before `为什么需要这个项目`. It must include:

1. Prerequisites:
   - Git;
   - JDK 21;
   - no system Gradle installation required.
2. A version check:

```bash
java -version
```

3. A macOS/Android Studio fallback:

```bash
export JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home'
```

4. Commands from the repository root:

```bash
./gradlew :apps:knowledge-cli:run --args="validate knowledge" --quiet
./gradlew :apps:knowledge-cli:run --args="resolve knowledge --team 20827 --season 2025-2026" --quiet
```

5. Exact expected output:

```text
validation=ok rules=4
active official.keep-customizations-in-teamcode
```

Explain directly after the output that the three other rules are `candidate`, so their absence is correct and proves candidates do not automatically become active.

- [ ] **Step 4: Check the quick start for stale or future claims**

Run:

```bash
rg -n "早期设计阶段|已经实现.*Agent|已经实现.*Android Studio|已经支持.*部署" README.md
git diff --check
```

Expected: no stale status or false implementation claim; `git diff --check` prints nothing.

- [ ] **Step 5: Commit the operational entry point**

```bash
git add README.md
git commit -m "docs: add Knowledge Core quick start"
```

---

### Task 2: Add the complete schema, governance, CLI, and troubleshooting manual

**Files:**
- Modify: `README.md`

**Interfaces:**
- Consumes: schema v1 fields decoded by `RuleYamlCodec`, constraints enforced by `RuleValidator`, approval policy, resolver behavior, and CLI error contracts.
- Produces: a complete operational manual that follows the quick start and precedes the conceptual architecture/roadmap material.

- [ ] **Step 1: Add a manual table of contents**

Add `## 完整使用手册` with a compact linked list for:

```markdown
- [知识库目录](#知识库目录)
- [规则字段说明](#规则字段说明)
- [创建候选规则](#创建候选规则)
- [添加审批](#添加审批)
- [校验和解析](#校验和解析)
- [团队协作流程](#团队协作流程)
- [CLI 退出码](#cli-退出码)
- [常见问题](#常见问题)
- [开发与验证](#开发与验证)
```

- [ ] **Step 2: Document the knowledge layout and every schema field**

Add `### 知识库目录` using this mapping:

| Path | Meaning |
| --- | --- |
| `knowledge/official/rules.yaml` | FIRST official constraints |
| `knowledge/shared/rules.yaml` | cross-team shared rules and candidates |
| `knowledge/teams/<team>/rules.yaml` | team-specific rules and candidates |
| `knowledge/schema/examples/rule-example.yaml.example` | copyable example excluded from recursive loading |

Add `### 规则字段说明` with a field table covering all decoder keys:

- root: `schemaVersion`, `rules`;
- rule: `id`, `topic`, `title`, `instruction`, `rationale`, `status`, `authority`, `applicability`, `evidence`, optional `approval`, `supersedes`, `positiveExample`, `negativeExample`;
- applicability: `teams`, `seasons`;
- evidence: `repository`, `commit`, `file`, optional `symbol`, optional `line`, with at least one of `symbol`/`line`;
- approval: `approver`, `role`, optional `team`, `approvedAt`.

State these exact constraints:

```text
id: ^[a-z0-9]+(?:[.-][a-z0-9]+)*$
topic: ^[a-z0-9]+(?:-[a-z0-9]+)*$
team: ^[0-9]+$
season: ^[0-9]{4}-[0-9]{4}$
commit: 7–64 hexadecimal characters
file: repository-relative, / separators only, no absolute path, backslash, empty segment, . or .. segment
line: positive integer
```

Explain that schema v1 rejects unknown fields, duplicate YAML keys, malformed collection/scalar types, and unsafe object tags instead of ignoring them.

- [ ] **Step 3: Add a complete candidate-rule workflow**

Add `### 创建候选规则` with these actions:

1. Copy `knowledge/schema/examples/rule-example.yaml.example` into the correct official/shared/team YAML document rather than renaming the example into the loaded tree.
2. Start repository-derived knowledge as `status: candidate` without `approval`.
3. Use this complete team candidate example:

```yaml
schemaVersion: 1
rules:
  - id: team-20827.example-candidate
    topic: example-topic
    title: Example team practice
    instruction: Describe one action the code should follow.
    rationale: Explain why team 20827 uses this practice.
    status: candidate
    authority: team
    applicability:
      teams: ["20827"]
      seasons: [2025-2026]
    evidence:
      - repository: owner/repository
        commit: abcdef1234567890
        file: TeamCode/src/main/java/example/Example.java
        symbol: Example
```

4. Run validation and explain that a candidate passes validation but never appears in resolved active output.

- [ ] **Step 4: Document approval and resolution semantics**

Add `### 添加审批` with two exact examples.

Official/shared approval:

```yaml
status: approved
authority: shared
approval:
  approver: overall-software-lead
  role: overall_software_lead
  approvedAt: 2026-08-13T00:00:00Z
```

Team approval:

```yaml
status: approved
authority: team
applicability:
  teams: ["20827"]
  seasons: [2025-2026]
approval:
  approver: lead-20827
  role: team_software_lead
  team: "20827"
  approvedAt: 2026-08-13T00:00:00Z
```

State that a team rule must target exactly the approving lead's team, while official/shared rules use the overall software lead role. State that current approval is version-controlled metadata, not yet an approval UI or immutable audit history.

Add `### 校验和解析` explaining:

- only `approved` rules can be active;
- `candidate`, `deprecated`, and `rejected` are inactive;
- applicability filters by team and season;
- precedence for the same canonical topic is `OFFICIAL > TEAM > SHARED`;
- two applicable rules at the same highest authority and topic produce a conflict and no active winner for that topic.

- [ ] **Step 5: Document CLI contracts and team workflow**

Add `### 团队协作流程`:

```text
队员识别可复用经验
→ 写入 candidate 并附 repository/commit/file/symbol-or-line 证据
→ 运行 validate
→ 软件负责人审查 instruction、rationale、适用范围和证据
→ 授权负责人添加 approval 并改为 approved
→ 再次 validate 和 resolve
→ 通过 Git review 合并
```

Clarify that ordinary contributors may propose candidates but must not self-assign an authorization role they do not hold.

Add `### CLI 退出码`:

| Exit | Meaning |
| --- | --- |
| `0` | validation/resolution succeeded |
| `2` | knowledge loading, schema validation, rule validation, or resolution conflict failed |
| `64` | command syntax, missing/duplicate/unknown option, or invalid team/season argument |

Show generic syntax:

```text
knowledge-cli validate <knowledge-root>
knowledge-cli resolve <knowledge-root> --team <digits> --season <YYYY-YYYY>
```

- [ ] **Step 6: Add actionable troubleshooting and developer verification**

Add `### 常见问题` as a table with symptom, cause, and action for:

- JDK 21 toolchain not found;
- `error loading knowledge`;
- `invalid rule id` / canonical topic errors;
- invalid commit or evidence path;
- `approved rule requires approval`;
- unauthorized approval;
- candidate missing from active output;
- same-level conflict output;
- CLI exit `64`.

Add `### 开发与验证` with module responsibilities:

| Module | Responsibility |
| --- | --- |
| `modules/domain` | rule values, validation, approval policy, resolution |
| `modules/knowledge` | strict YAML decoding and filesystem repository loading |
| `apps/knowledge-cli` | user-facing validate/resolve commands and exit codes |

Show:

```bash
./gradlew clean test
```

State: “2026-08-13 的当前快照为 59 项测试全部通过；测试数量会随功能增长，以本地最新结果为准。”

- [ ] **Step 7: Remove duplicate operational sections and preserve conceptual context**

Merge or remove the old `Knowledge Core（Foundation）` usage block so commands, path tables, and approval explanations have one authoritative location. Preserve and position after the manual:

- why the project exists;
- core principles;
- architecture Mermaid diagram;
- Agent permission modes as planned behavior;
- MVP and platform adapter roadmap;
- reference repositories and Control Hub/Systemcore discussion.

Ensure planned sections use future tense and do not imply implementation.

- [ ] **Step 8: Verify documentation against implementation**

Run:

```bash
rg -n 'validation=ok rules=4|active official.keep-customizations-in-teamcode|overall_software_lead|team_software_lead|OFFICIAL > TEAM > SHARED|`64`|59 项测试' README.md
rg -n '早期设计阶段|Pedro Pathing.*已完成|Limelight.*已完成|已经实现.*Ask|已经实现.*Edit|已经实现.*Run|自动部署已实现' README.md
git diff --check
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew --no-daemon clean test --console=plain
```

Expected:

- required current-state strings are present;
- false/stale claim search prints nothing;
- `git diff --check` prints nothing;
- Gradle prints `BUILD SUCCESSFUL` with 59 tests passing.

- [ ] **Step 9: Commit the complete manual**

```bash
git add README.md
git commit -m "docs: add complete Knowledge Core manual"
```

---

## Completion Gate

Before pushing `main`, run:

```bash
./gradlew :apps:knowledge-cli:run --args="validate knowledge" --quiet
./gradlew :apps:knowledge-cli:run --args="resolve knowledge --team 20827 --season 2025-2026" --quiet
git diff --check
git status --short
```

Required result:

- quick-start commands in the README match successful real commands;
- output is `validation=ok rules=4` and `active official.keep-customizations-in-teamcode`;
- README contains one operational source of truth rather than a duplicated old usage block;
- current, partial, and planned capabilities are visibly separated;
- a candidate author and an authorized approver can follow the complete workflow from the README;
- no runtime or knowledge files changed;
- the worktree is clean after the documentation commits.
