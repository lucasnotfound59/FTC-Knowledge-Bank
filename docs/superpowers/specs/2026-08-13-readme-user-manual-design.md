# README Quick Start and User Manual Design

## Goal

Turn the repository README into an accurate entry point for new team members, software leads, and contributors. A reader should be able to understand the current project state, validate the checked-in knowledge, resolve active rules for a team and season, create a candidate rule, add evidence, approve an eligible rule, and diagnose common failures without reading the Kotlin source.

## Scope

This change edits `README.md` only. It documents the currently implemented Knowledge Core Foundation and does not implement repository analysis, candidate extraction, an Agent runtime, Ask/Edit/Run workflows, an Android Studio plugin, robot deployment, or Pedro Pathing/Limelight knowledge content.

The README must replace the stale “early design stage” description with a precise capability matrix:

- implemented: rule domain model, strict local YAML loading, evidence and approval validation, authority resolution, CLI validation/resolution, initial 20827/16093 profiles, and 59 passing tests;
- partially populated: the knowledge directory has one active official rule and three repository-derived candidate rules;
- planned: repository import and analysis, candidate extraction, approval UI/history, Agent modes, IDE clients, deployment adapters, and beginner content for Pedro Pathing and Limelight.

## Information Architecture

The README will use one continuous path with two depths:

1. Project status and capability boundary.
2. Five-minute quick start.
3. Complete user manual.
4. Team governance workflow.
5. Developer verification.
6. Troubleshooting.
7. Architecture, references, and roadmap context.

The quick start appears before the architecture discussion so first-time users can obtain a successful result before learning internals. Existing architecture and platform-evolution material remains available after the operational instructions.

## Quick Start

The quick start will require JDK 21, use the checked-in Gradle Wrapper, and show commands from the repository root:

```bash
./gradlew :apps:knowledge-cli:run --args="validate knowledge"
./gradlew :apps:knowledge-cli:run --args="resolve knowledge --team 20827 --season 2025-2026"
```

It will show the exact expected output:

```text
validation=ok rules=4
active official.keep-customizations-in-teamcode
```

It will explain that the other three records are candidates and therefore correctly absent from the active result. macOS users with Android Studio will receive an example `JAVA_HOME` command for its bundled JDK 21, while the generic instruction remains to point `JAVA_HOME` at any installed JDK 21.

## Complete Manual

The complete manual will document:

- the `knowledge/official`, `knowledge/shared`, `knowledge/teams/<team>`, and schema-example paths;
- every schema v1 field, including required/optional status and exact format constraints;
- canonical formats for rule IDs, topic slugs, team numbers, seasons, Git commits, and repository-relative evidence paths;
- how to copy the `.yaml.example`, create a candidate rule, attach evidence, and validate it;
- how approval metadata differs for official/shared rules and team rules;
- rule lifecycle statuses and the `official > team > shared` precedence for the same topic and applicable context;
- same-authority conflicts, candidate inactivity, deterministic CLI output, and exit codes `0`, `2`, and `64`;
- a team workflow separating candidate authorship from authorized approval.

Examples will use repository-relative paths and non-secret placeholder identities. The manual will not suggest that changing YAML is an approval UI or historical audit system; those remain future capabilities.

## Developer and Troubleshooting Sections

Developer instructions will describe the three Gradle modules and run the full suite with `./gradlew clean test`. They will state the currently verified total of 59 tests as a dated/current snapshot rather than a permanent invariant.

Troubleshooting will cover:

- Gradle cannot find a JDK 21 toolchain;
- malformed YAML or schema field/type failures;
- invalid evidence path or commit;
- unauthorized or missing approval;
- a candidate not appearing in resolved output;
- same-authority same-topic conflicts;
- CLI usage errors and the meaning of exit codes.

## Accuracy and Safety Rules

- Do not claim that the Agent, repository importer, approval UI, IDE plugin, or deployment tools exist.
- Do not present Pedro Pathing or Limelight content as populated.
- Do not include secrets, API keys, robot credentials, or commands that deploy to hardware.
- Keep approval roles consistent with code: overall software lead for official/shared, matching team software lead for team rules.
- Keep precedence consistent with code: official, then team, then shared.
- Explain that deployment and other hardware-impacting actions require explicit human confirmation in future stages.

## Acceptance Criteria

- A new user can copy the quick-start commands and understand both expected outputs.
- A team member can construct a valid candidate YAML record using only the README and example file.
- A software lead can identify which approval metadata is authorized for each authority.
- The documented field formats, status behavior, precedence, CLI syntax, and exit codes match the current implementation.
- The README clearly distinguishes current, partial, and planned capabilities.
- All existing tests continue to pass and `git diff --check` reports no errors.
