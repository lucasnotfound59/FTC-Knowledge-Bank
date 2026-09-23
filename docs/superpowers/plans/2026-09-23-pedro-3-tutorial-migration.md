# Pedro Pathing 3 Tutorial Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. The user explicitly selected `deepseek-delegate` for this execution, so that executor choice overrides the generic handoff option.

**Goal:** Make every current Pedro teaching path and its compiled safety example use Pedro 3.0.0 APIs and official evidence.

**Architecture:** Pin one official v3 release/Quickstart baseline, migrate the canonical SafePedroAuto and fixture together, then rewrite active guides and their acceptance assertions. Preserve historical version attribution and current-robot tuning/physical-test boundaries.

**Tech Stack:** Java FTC OpMode/Pedro 3.0.0, Gradle fixture, Kotlin acceptance tests, YAML v4 rules, Markdown.

## Global Constraints

- Start after V0.8.0; release as repository V0.9.0. Do not bump kernel/YAML/integration versions solely for tutorial edits.
- Use official Pedro 3 installation, Constants, path/follow/auto and v3.0.0 release docs, plus pinned Quickstart commit `b4312385b7d0cc5e8dd263ec3927c9ef0cb48f36`.
- Keep the single canonical SafePedroAuto path; no duplicate example to bypass compile verification.
- Never copy official example hardware names, offsets, gains, power, poses or velocities as validated team parameters.
- Compile/build is not deployment, Driver Station or real-robot validation.

---

### Task 1: Pin the v3 fixture and migrate the canonical Java example

**Files:** Modify `fixtures/pedro-compile/gradle.properties`, `fixtures/pedro-compile/build.gradle`, `fixtures/pedro-compile/src/main/java/org/firstinspires/ftc/teamcode/pedroPathing/Constants.java`, `knowledge/examples/pedro/SafePedroAuto.java`, `apps/knowledge-cli/src/test/kotlin/org/ftckb/cli/PedroTutorialAcceptanceTest.kt`.

**Interfaces:** The fixture compiles `knowledge/examples/pedro/SafePedroAuto.java` directly against `com.pedropathing:revhub:3.0.0` and `com.pedropathing:tuning:1.0.0`. Java follower setup uses the official v3 `Constants.create(hardwareMap)` pattern; path/follow calls come from the pinned v3 docs/API.

- [ ] Pin the official Quickstart/release and inspect its actual FTC/Gradle versions and Java signatures. Record exact sources in the guide; do not infer v3 APIs from the old example.
- [ ] Replace 2.1.2-only test assertions with v3 API/dependency assertions, preserving safety assertions for default lock, non-blocking loop, true state-transition timer and post-write servo telemetry.
- [ ] Run focused acceptance tests and `./gradlew verifyPedroRelease` to expose incompatible v2 code, then migrate fixture and canonical Java example. If the fixture's FTC SDK baseline is incompatible, align it with the pinned official Quickstart rather than stubbing APIs.
- [ ] Run fixture compilation and tests. If external dependency or Android SDK resolution fails, state the exact environmental failure and do not mark compile verified.

### Task 2: Guides, rules and traceability

**Files:** Modify `knowledge/guides/tools/pedro-pathing.md`, `knowledge/guides/practices/rookiebot-tutorial.md`, `knowledge/shared/practices/rookiebot-tutorial.yaml`, `docs/handbook/pedro-verification.md`, `README.md`, relevant acceptance tests.

**Interfaces:** Current instructions teach Dairy Maven + revhub/tuning dependencies, tuned `Constants.create`, v3 Pose/Path/follow APIs, safety checklist and stage-by-stage real-robot verification. Historical 2.1.2 provenance remains clearly historical.

- [ ] Rewrite active install/API/tutorial snippets from first-party v3 sources. Mark current robot's hardware/configuration numbers as placeholders requiring measurement; link official v3 reference pages next to the claims they support.
- [ ] Make `shared.rookiebot-pedro-complete-builder` version-independent while specifically explaining the v3 `Constants.create` complete follower; add first-party v3 evidence and preserve the old RookieBot commit only as v2 historical source.
- [ ] Update acceptance tests so they reject active 2.1.2 dependencies/API while allowing historical-source notes, and verify README anchors and one canonical example.
- [ ] Run `ftckb validate knowledge --json`, resolve for supported teams/profiles, focused tests, `./gradlew test`, `./gradlew verifyPedroRelease`, Python integration tests, `scripts/smoke.sh` and `git diff --check`. Publish exact pass/fail results and explicitly leave real-robot validation open.
