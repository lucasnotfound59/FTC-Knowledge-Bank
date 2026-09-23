# Vendor-Documented Build Dependencies Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. The user explicitly selected `deepseek-delegate` for this execution, so that executor choice overrides the generic handoff option.

**Goal:** Implement the already approved `2026-09-17-vendor-documented-build-dependencies-design.md` as repository V0.7.0.

**Architecture:** Keep the official hard rule for `build.common.gradle`. Add one path-triggered global soft rule for `build.dependencies.gradle`; let the Agent verify first-party vendor evidence rather than claiming that regex can authenticate URLs.

**Tech Stack:** YAML v4 knowledge, Kotlin CLI/standardizer, JUnit acceptance tests, JSON fixtures, Markdown.

## Global Constraints

- Preserve YAML v4, kernel JSON v2, CLI 2.0.0, integration v2.
- Use Pedro official installation page, v3.0.0 release and pinned Quickstart commit from the approved spec as evidence.
- Do not soften `build.common.gradle` or turn all root Gradle edits into silent passes.
- Do not auto-push or claim Gradle sync/build is robot validation.

---

### Task 1: Conditional soft rule

**Files:** Modify `knowledge/official/rules.yaml`; create `knowledge/global/vendor-documented-build-dependencies.yaml`; test in `apps/knowledge-cli/src/test/kotlin/org/ftckb/cli/`.

**Interfaces:** The existing `RuleCheckKind.PATH_FORBIDDEN` and `reviewTriggers` path-only behavior are sufficient; no kernel code change is expected.

- [ ] Write a failing acceptance test with a `build.dependencies.gradle` patch asserting `exit=0`, one soft entry `global.vendor-documented-build-dependencies`, and no `official.keep-customizations-in-teamcode` violation. In the same test, a `build.common.gradle` patch must assert `exit=1` and the official rule ID.
- [ ] Run the focused acceptance test and confirm it fails for the existing unconditional dependency-file prohibition.
- [ ] Remove only the `build.dependencies.gradle` path-forbidden check from the official rule. Add the approved global/shared cross-season rule with `checks: []` and `reviewTriggers: [{paths: ["build.dependencies.gradle"], addedLinePatterns: []}]`, official Pedro evidence, maintainer approval and explicit first-party evidence requirements in its instruction.
- [ ] Run the focused test and `ftckb validate knowledge --json`; confirm 48 rules and no knowledge violation. Test delete/rename and unrelated Java patches, so the soft trigger does not overreach.

### Task 2: Contract snapshots and release text

**Files:** Modify `README.md`, `AGENTS.md`, relevant `docs/kernel-contract.md`, `docs/standardizer-check.md`, `docs/handbook/`, `fixtures/kernel/` and acceptance tests that lock counts/outputs.

**Interfaces:** No new JSON keys or schemaVersion. The new active rule appears in normal resolve; check adds one conditional soft only when the dependency file is touched.

- [ ] Update acceptance tests for 48 total/42 approved/6 candidate, profile counts 26/29/38/30 in the order generic/command-based/rookiebot/ftclib-command, while keeping five hard-check rules.
- [ ] Regenerate real kernel fixtures from built CLI output; assert byte-for-byte schema validity and deterministic ordering.
- [ ] Mark repository V0.7.0 and explain that vendor-documented dependency edits trigger soft evidence review; do not label the first-party Pedro instruction as FIRST policy.
- [ ] Run `./gradlew test`, integration Python tests, `scripts/smoke.sh` and `git diff --check`; record environment-limited checks accurately.
