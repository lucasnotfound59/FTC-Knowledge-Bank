# Test and utils layout policy migration record

Date: 2026-09-13

Rule: `global.test-utility-layout`

Approval: `lucasnotfound59` / `overall_software_lead` at `2026-09-13T09:12:38Z`
Decision evidence: [`6aa385d75484b22b3f73c3b493a695e753ccc76e`](../../docs/superpowers/specs/2026-09-13-ftc-test-utils-layout-design.md), `docs/superpowers/specs/2026-09-13-ftc-test-utils-layout-design.md:8`

## Maintainer decision and scope

This is the 2026-09-13 maintainer decision recorded in the cited design commit. It is not a retrospective claim that TeamChina, RookieBot, FIRST, or the FTC SDK already required this layout.

For every target TeamCode project, robot-side test, diagnostic, and calibration OpModes belong in `TeamCode/src/main/java/org/firstinspires/ftc/teamcode/tests/`. Reusable helpers, conversions, and adapters without mechanism ownership belong in `TeamCode/src/main/java/org/firstinspires/ftc/teamcode/utils/`. Both directories are peers of `subsystems/` and `commands/` inside `teamcode/`; Java package declarations must match their directories. Subsystems and commands must retain their own ownership rather than being relabeled as utils.

The policy is intentionally cross-team, cross-season, and cross-profile: `teams`, `seasons`, and `profiles` are all empty. Directory responsibility does not depend on a particular game, SDK API, robot mechanism, or command architecture.

## Conflicts and replacement record

The local TeamChina checkout had `ConfigTeleOpTest.java` and `FGC2026TeleOpTest.java` under `teamcode/teleops/`. That historical checkout placement conflicts with this new policy; the new maintainer decision controls future rule guidance and does not assert that the prior TeamChina layout was already compliant.

Task 1 replaced the contradictory JUnit-specific content in these existing RookieBot rules while preserving their IDs, topics, local policy level, and RookieBot applicability:

- `shared.rookiebot-java-imports`: removed JUnit assertion import guidance and now covers correct project-class and FTC API imports.
- `shared.rookiebot-verification-evidence`: removed `:TeamCode:testDebugUnitTest`, `TeamCode/src/test`, and unit-test reporting guidance; it now requires `:TeamCode:assembleDebug` plus separately reported build, deployed tests OpMode, and robot verification evidence.

The RookieBot tutorial was changed in the same Task 1 migration to point to the canonical `teamcode/tests/` and `teamcode/utils/` locations and to remove target-TeamCode JUnit/source-set instruction. This policy does not remove the FTC Knowledge Bank's own Kotlin/JUnit test infrastructure.

## Deterministic enforcement boundary

The rule uses the existing YAML v4 `path-forbidden` and `regex-forbidden` checks only. It blocks known wrong `src/test`, `src/androidTest`, misplaced `tests`/`utils`, singular aliases, new TeamCode Java JUnit imports, and new TeamCode Gradle JUnit dependencies. It deliberately does not infer the semantic role of every arbitrary Java class from filenames or control flow; the full active-rule instruction remains the classification requirement.

Passing `ftckb check` proves only these deterministic diff checks. It does not prove Android Studio indexing, Robot Controller or Driver Station behavior, deployment, tests OpMode execution, or physical robot operation.
