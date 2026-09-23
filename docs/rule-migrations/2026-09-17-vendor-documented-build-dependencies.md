# Vendor-documented build dependency rule migration record

Date: 2026-09-17

Rule: `global.vendor-documented-build-dependencies`

Approval: `lucasnotfound59` / `overall_software_lead` at `2026-09-17T13:07:17Z`
Decision evidence: [`46108502eb6c963b06b255da0f8a2dd152615128`](../../docs/superpowers/specs/2026-09-17-vendor-documented-build-dependencies-design.md), `docs/superpowers/specs/2026-09-17-vendor-documented-build-dependencies-design.md:1`

## Maintainer decision and scope

This is the 2026-09-17 maintainer decision recorded in the cited design commit. It is not a FIRST decision, not a withdrawal of FIRST's protection of SDK build files, and not a statement that Pedro Pathing defines FTC Knowledge Bank priority. FIRST's `official.keep-customizations-in-teamcode` rule keeps its ID, topic, official authority, approved status, applicability, FIRST Git evidence, approval record, and its `build.common.gradle` prohibition unchanged. Only the unconditional prohibition of `build.dependencies.gradle` was removed, because the original FIRST evidence cited for that rule covers `build.common.gradle`.

The maintainer decision adds one shared/global approved rule whose `teams`, `seasons`, and `profiles` are all empty, so it applies across teams, seasons, and project profiles. It has no `checks`; its only enforcement artifact is a path-only `reviewTriggers` entry for `build.dependencies.gradle` with empty `addedLinePatterns`. Every touch of that root file — addition, modification, deletion, or rename — produces exactly one conditional soft reminder per rule ID.

## First-party evidence requirement

The rule instruction requires the Agent to record and check, before delivery:

1. a first-party official URL or a pinned commit in the dependency vendor's official repository;
2. product/dependency name and exact version;
3. the repository or dependency the official material requires adding or changing;
4. the item-by-item correspondence between the actual diff and the official steps;
5. the actual Gradle sync/build result, stated separately from deployment, Robot Controller, Driver Station, or physical-robot validation.

Rolling web pages may explain an installation flow, but an exact version must also be tied to a versioned release or a pinned official Quickstart commit. Blogs, forums, other teams' code, search summaries, and model answers cannot satisfy the requirement alone.

The evidence attached to the rule is the Pedro Pathing first-party chain identified in the approved design:

- `https://pedropathing.com/docs/pathing/installation`, publisher `Pedro Pathing`, section `Manual Installation`, accessed `2026-09-17`;
- `https://github.com/Pedro-Pathing/PedroPathing/releases/tag/v3.0.0`, publisher `Pedro Pathing`, version `v3.0.0`;
- `Pedro-Pathing/Quickstart` commit `b4312385b7d0cc5e8dd263ec3927c9ef0cb48f36`, file `build.dependencies.gradle`, using `com.pedropathing:revhub:3.0.0` and `com.pedropathing:tuning:1.0.0`.

## Deterministic enforcement boundary

The knowledge engine never fetches or authenticates a URL, never judges whether a domain is first-party, and never verifies that a dependency version matches the evidence. The rule is deliberately a conditional soft, so that unknown third-party or stale evidence does not become a false hard failure. Passing `ftckb check` proves only that the file was touched and that the reminder was reported; it does not prove the official evidence is correct, that the diff matches the official steps, or that the project builds, deploys, or runs on a robot.

## Relationship to the existing hard rule

`build.common.gradle` remains a `path-forbidden` hard check under `official.keep-customizations-in-teamcode`; a diff that touches it still exits `1`. A `build.dependencies.gradle` diff exits `0` when there is no other hard violation, but it always reports the vendor-documented soft entry, and the Agent must report the evidence review outcome to the user.

The repository version is raised to V0.7.0. CLI 2.0.0, YAML v4, kernel JSON v2, and project integration protocol v2 are unchanged.
