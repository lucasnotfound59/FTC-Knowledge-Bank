# Explicit Test and Dev Work Modes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. The user explicitly selected `deepseek-delegate` for this execution, so that executor choice overrides the generic handoff option.

**Goal:** Make explicit test-code exemptions and per-task dev hard-to-soft downgrades deterministic, without changing default production behavior.

**Architecture:** Parse one ephemeral `WorkMode` option in resolve/check and the project adapter. Resolve excludes exactly two architecture-mandate rules in test mode; check transforms hard findings into location-rich soft notices in dev mode, requiring an explicit scoped patch.

**Tech Stack:** Kotlin domain/CLI/standardizer, Python project adapter, JUnit/Python tests, JSON Schema draft-07, Markdown Skill/docs.

## Global Constraints

- Start after V0.7.0 and release this feature as repository V0.8.0, CLI 2.1.0.
- Keep YAML v4, kernel JSON schemaVersion 2 and project integration protocol v2.
- Default mode must retain existing JSON bytes, exits, profile resolution and five hard checks.
- Only explicit user designation can select test/dev; no filename or dependency inference.
- `dev` requires `--diff FILE`, never silently downgrades a full dirty checkout; loading/validation/resolver conflicts remain errors.
- No target FTC test-code plan prerequisite; test layout/JUnit hard rule remains enforced.

---

### Task 1: Work-mode domain and test resolver

**Files:** Create `modules/domain/src/main/kotlin/org/ftckb/domain/WorkMode.kt`; modify `modules/domain/src/main/kotlin/org/ftckb/domain/RuleResolver.kt`; add domain tests.

**Interfaces:** `enum class WorkMode { NORMAL, TEST, DEV }`; extend `RuleContext` with `workMode:WorkMode=WorkMode.NORMAL`. In TEST, excluded IDs are exactly `global.command-responsibilities` and `shared.ftclib-command-candidate`, with reason `work-mode-test`; DEV resolves unchanged.

- [ ] Add tests that resolve the same team/season/ftclib-command profile in NORMAL and TEST; assert only those two rule IDs move from active to excluded, with the exact reason, while `global.command-requirements-cleanup` and `global.test-utility-layout` remain active.
- [ ] Run focused tests, observe failure, then implement the enum and resolver filter before topic precedence. Run focused tests to green.

### Task 2: CLI and dev soft conversion

**Files:** Modify `apps/knowledge-cli/src/main/kotlin/org/ftckb/cli/Main.kt`, `CheckCommand.kt`, `KernelJson.kt`, `modules/standardizer/src/main/kotlin/org/ftckb/standardizer/Standardizer.kt`; add CLI/standardizer tests.

**Interfaces:** `--work-mode normal|test|dev` on resolve/check; omitted NORMAL. DEV check must also have `--diff FILE` or return JSON usage error/64. Explicit non-normal JSON adds `workMode`; NORMAL JSON does not change. `soft` retains `{ruleId,note}` with deterministic check/path/line/detail text for converted findings.

- [ ] Write failing CLI tests for all three modes, invalid/duplicate options, missing dev patch, normal byte compatibility, test excluded IDs, dev hard-to-soft conversion and deterministic ordering. Use a patch that simultaneously hits two separate hard checks; assert no violations and exit 0 in DEV, but exit 1 in NORMAL.
- [ ] Implement argument validation and propagation. Evaluate the scoped diff normally, then transform each hard finding into a soft notice only for DEV; retain pre-existing soft notices. Preserve error exits 2/64.
- [ ] Run focused tests and schema validation. Verify text output prints every converted soft notice rather than silently saying only `check=pass`.

### Task 3: Project adapter and user-facing workflow

**Files:** Modify `.agents/skills/ftckb-integrate/scripts/project.py`, `.agents/skills/ftckb-integrate/assets/project-skill/SKILL.md`, `docs/kernel-contract.md`, `docs/kernel-contract.schema.json`, `docs/cli-agent.md`, `README.md`, `AGENTS.md`; test project adapter.

**Interfaces:** `project.py resolve|check --work-mode test|dev` forwards the option while leaving v1/v2 pins and default calls unchanged. The Agent chooses modes only from explicit user wording and reports which patch was checked.

- [ ] Test that project.py forwards TEST/DEV and refuses DEV without `--diff`; assert default calls omit the new flag and preserve old output.
- [ ] Update runtime Skill: test code follows user test purpose, not mandatory CommandBase or pre-plan; dev code uses scoped patch, reports every downgraded hard item and honors user choice to ignore; no implication of production compliance or robot testing.
- [ ] Document examples and JSON additive `workMode` field. Update CLI 2.1.0 and repository V0.8.0 references.
- [ ] Run Gradle tests, project Python tests, `ftckb validate knowledge --json`, JSON Schema fixtures, `scripts/smoke.sh` and `git diff --check`.
