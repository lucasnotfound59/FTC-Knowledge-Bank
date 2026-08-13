# FTC Tool Knowledge Design

Date: 2026-08-13

Status: Approved for implementation planning

Initial scope: Android Studio FTC SDK setup, FTCLib, FTC Dashboard, Pedro Pathing, goBILDA motors and servos, and Limelight 3A

## 1. Objective

Add an evidence-backed tool knowledge layer that serves two consumers:

1. the future FTC Agent, which needs concise machine-enforceable rules; and
2. team members, especially newcomers, who need explanations, setup steps, parameter meanings, examples, validation, and troubleshooting.

The first release covers the Android Studio and Control Hub setup path, FTCLib, FTC Dashboard, Pedro Pathing, goBILDA motors and servos, and Limelight 3A. It establishes a reusable structure and writing standard before other FTC tools are added.

## 2. Confirmed Decisions

- Use a dual-layer structure: YAML for enforceable rules and Markdown for human-facing guides.
- Derive technical claims from first-party documentation only. Blogs, forum posts, videos, search-result snippets, and team code may help identify questions but are not authoritative evidence for this release.
- Store vendor- and project-derived rules as `shared` candidates, not `official` rules. A source can be authoritative about its own product without becoming a FIRST policy authority.
- Do not promote any new rule automatically. Every new rule starts as `candidate` without approval metadata.
- Add first-class web evidence instead of inventing Git commits for product pages or web documentation.
- Keep current schema v1 documents readable while introducing schema v2 for typed evidence.
- Write machine-facing rule fields in English to match the current rule set. Write guides in Chinese, introducing specialized terms bilingually on first use.
- Use Java for minimum FTC SDK code examples in this release.
- Attach product model, SKU, software version, or document version whenever the official source provides one. Never present an access date as a product version.
- Target the current Android Studio and Control Hub toolchain only. Do not mix Systemcore, WPILib, GradleRIO, or Linux deployment instructions into these guides.
- Distinguish the Android Studio runtime JDK, Gradle JDK, Java source compatibility, Android compile SDK, FTC SDK, Robot Controller app, and Driver Station app versions. Similar names do not make these values interchangeable.
- Pin installation examples to a verified release snapshot. A guide may explain how to check for a newer release, but it must not silently replace the documented version with a rolling `master` branch.

## 3. Repository Layout

```text
knowledge/
├── official/
│   └── rules.yaml
├── shared/
│   ├── rules.yaml
│   ├── setup/
│   │   ├── android-studio-ftc-sdk.yaml
│   │   ├── ftclib.yaml
│   │   └── ftc-dashboard.yaml
│   └── tools/
│       ├── pedro-pathing.yaml
│       ├── gobilda-motors-servos.yaml
│       └── limelight.yaml
├── teams/
│   ├── 20827/
│   └── 16093/
└── guides/
    ├── setup/
    │   ├── android-studio-ftc-sdk.md
    │   ├── ftclib.md
    │   └── ftc-dashboard.md
    └── tools/
        ├── pedro-pathing.md
        ├── gobilda-motors-servos.md
        └── limelight-3a.md
```

The existing recursive YAML loader already permits the nested `shared/setup` and `shared/tools` directories. Markdown guides remain outside CLI rule loading and cannot silently introduce enforceable behavior.

## 4. Evidence Model

### 4.1 Compatibility strategy

Schema v1 remains supported unchanged. Schema v2 introduces a required `type` discriminator for each evidence item. A knowledge root may contain v1 and v2 documents together, allowing existing rules to remain valid during migration.

New setup and tool rule documents use `schemaVersion: 2`. Existing v1 rule files do not need to be rewritten as part of this work.

### 4.2 Git evidence

Git evidence preserves the current repository-pinned model:

```yaml
evidence:
  - type: git
    repository: Pedro-Pathing/PedroPathing
    commit: 0123456789abcdef
    file: path/to/File.java
    symbol: ExampleSymbol
```

Required fields:

- `type: git`;
- non-empty `repository`;
- 7-64 character hexadecimal `commit`;
- safe repository-relative `file`;
- at least one of `symbol` or positive `line`.

### 4.3 Web evidence

Web evidence supports official documentation and manufacturer specifications:

```yaml
evidence:
  - type: web
    url: https://www.gobilda.com/example-product/
    title: Official product title
    publisher: goBILDA
    accessedAt: 2026-08-13
    section: Specs
    product: 5203 Series Yellow Jacket Planetary Gear Motor
    sku: 5203-2402-0019
```

Required fields:

- `type: web`;
- absolute `https` URL without credentials or fragment-only targets;
- non-empty page `title` and `publisher`;
- ISO `YYYY-MM-DD` `accessedAt` date;
- non-empty `section` that identifies the relevant heading, table, or named part of the page.

Optional fields:

- `version` for a documented software, firmware, or document version;
- `product` for a named hardware product;
- `sku` for a manufacturer part number.

Validation checks syntax and completeness, not whether a publisher is genuinely official. Human review remains responsible for source authenticity and claim-to-source matching. Runtime tests must not fetch the network.

### 4.4 Evidence semantics

- Evidence supports a claim; it does not grant policy approval.
- Mutable web pages must record `accessedAt` and any visible version or SKU.
- A rule that combines several distinct technical claims must cite each necessary source or be split into narrower rules.
- A guide may cite the same sources as its related rules, but it must not contain stronger claims than those sources support.
- Search snippets are discovery aids only and are never stored as evidence.

## 5. Guide Writing Standard

Each guide uses the same section order so a newcomer can predict where to find information:

1. **Scope and supported versions**: exact hardware, library, FTC SDK, and season assumptions.
2. **What the tool does**: short conceptual explanation and when to use it.
3. **Prerequisites**: hardware, wiring, dependencies, calibration, and software requirements.
4. **Parameter dictionary**: parameter, meaning, unit, valid or documented range, configuration location, source, and whether the value is measured, specified, or tuned.
5. **Setup procedure**: numbered steps with a reason and observable success condition for each step.
6. **Minimum Java example**: the smallest useful FTC SDK integration, with placeholders clearly distinguished from measured values.
7. **Validation checklist**: tests that establish configuration correctness before match use.
8. **Troubleshooting table**: observable symptom, likely causes, and ordered checks.
9. **Safety and misuse warnings**: electrical, mechanical, coordinate, stale-data, or calibration boundaries supported by official sources.
10. **Related rule IDs**: the YAML rules that govern Agent behavior.
11. **Official sources**: direct first-party links, visible version or SKU, and last verification date.

Writing requirements:

- Define specialized terms in Chinese and English on first use.
- Always write units next to numeric values.
- Distinguish manufacturer specifications, library defaults, example values, and robot-specific tuned values.
- Never copy PID, feedforward, odometry offset, camera pose, servo endpoint, or other robot-specific calibration values as universal defaults.
- Label placeholders such as `YOUR_MOTOR_NAME` explicitly.
- State coordinate origin, axis directions, angle unit, and rotation sign before giving pose examples.
- Prefer a minimal supported API path before advanced alternatives.
- Describe validation through observable outcomes rather than claims such as "it should work."
- Keep historical instructions only when they are clearly marked with their applicable version.
- Show the exact file path and Gradle block for every build edit. Do not use the ambiguous instruction "edit build.gradle" without naming the project or module file.
- Separate required installation steps from optional modules, advanced development workflows, and historical compatibility workarounds.
- After each environment or dependency change, state a local verification command and an observable Android Studio or robot-side result.

## 6. Initial Candidate Rules

All nineteen rules below will be `authority: shared`, `status: candidate`, and initially applicable across teams. Season or version limits are added when the source makes them necessary.

### 6.1 Android Studio and FTC SDK

1. `shared.ftc-sdk-pin-release`: Build a team project from an identified FIRST `FtcRobotController` release or tag and record its FTC SDK version; do not describe a rolling branch as a reproducible release.
2. `shared.ftc-sdk-preserve-build-tooling`: Use the Gradle Wrapper, Android Gradle Plugin, and Android build configuration supplied by the selected FTC SDK release unless a newer FIRST release explicitly changes them; do not accept an IDE upgrade suggestion without checking FTC SDK compatibility.
3. `shared.ftc-sdk-separate-toolchain-versions`: Record the Gradle JDK, Java source compatibility, Android compile/target/minimum SDK levels, and FTC SDK version as distinct values and verify each in its actual configuration location.

The Android Studio guide will target the latest verified official release snapshot at implementation time. As of the design review on 2026-08-13, that snapshot is FIRST SDK v11.2, whose release requires Android Studio Narwhal 3 Feature Drop or later. The guide will verify the release tag before publication rather than assuming the current `master` contents are identical.

The guide will cover:

- installing Android Studio and selecting a compatible embedded or configured Gradle JDK;
- explaining why the JDK used to run Gradle is different from the Java 8 source compatibility currently retained by the FTC project;
- using SDK Manager to install the Android SDK and NDK components required by the pinned project rather than guessing newer API levels;
- cloning or downloading the official release, extracting it, opening the project root, and trusting the project;
- declining unsourced Android Gradle Plugin upgrade prompts;
- completing the first network-connected Gradle Sync and build;
- locating `TeamCode`, the official samples, `build.common.gradle`, `build.dependencies.gradle`, and the module build files;
- producing a minimal OpMode, building the Robot Controller app, deploying to a Control Hub, and verifying the OpMode on the Driver Station;
- diagnosing Gradle JDK, missing Android SDK component, dependency download, sync, device connection, and SDK/RC/DS version mismatch failures.

### 6.2 FTCLib

4. `shared.ftclib-check-current-prerequisites`: Compare FTCLib's documented prerequisites with the pinned FTC SDK before editing build files; do not repeat already-satisfied settings or overwrite FIRST-managed build configuration blindly.
5. `shared.ftclib-pin-module-versions`: Declare only the required FTCLib modules with their exact documented versions, and treat vision-specific native-library or ABI steps as conditional on using the vision module.

The FTCLib guide will record that the official installation page reviewed on 2026-08-13 lists `org.ftclib.ftclib:core:2.1.1` and `org.ftclib.ftclib:vision:2.1.0`. It will not assume those versions are still current at implementation time. It will compare the pinned FTC SDK against the page's `mavenCentral`, minimum SDK, multidex, Java compatibility, ABI, and EasyOpenCV instructions, then apply only changes that remain necessary.

The guide will show exact file paths, a core-only installation first, an optional vision branch, Gradle Sync and compile verification, a minimum Java import/use example, and troubleshooting for dependency resolution, duplicate FTC artifacts, native libraries, ABI mismatch, and method/class-not-found failures.

### 6.3 FTC Dashboard

6. `shared.dashboard-pin-stable-dependency`: Configure the official FTC Dashboard Maven repository and an exact stable dependency version; do not use a `-SNAPSHOT` build in a team project unless the experimental source and reason are explicitly documented.
7. `shared.dependency-verify-sync-build-run`: After adding or changing an FTC library, require successful Gradle Sync, local build, Robot Controller deployment, and a minimal runtime check before presenting the integration as working.

The FTC Dashboard guide will target the latest stable release verified at implementation time. The official installation page and release list reviewed on 2026-08-13 identify `com.acmerobotics.dashboard:dashboard:0.6.0` and `https://maven.brott.dev/`. The guide will name the exact build file used by the documented installation path, distinguish the OpenRC/non-standard SDK exclusion from the normal FTC SDK path, and keep advanced local `-SNAPSHOT` development out of the beginner flow.

The guide will cover dependency installation, Gradle verification, Control Hub Wi-Fi access at the documented dashboard address, a minimum `@Config`/telemetry example, safe use of live configuration values, and troubleshooting for repository resolution, stale app deployment, wrong robot network or IP, missing annotated fields, and dashboard page connection failures.

### 6.4 Pedro Pathing

8. `shared.pedro-tune-current-robot`: Treat follower, drivetrain, and localizer values as robot-specific measurements or tuning outputs; do not copy another robot's values as validated configuration.
9. `shared.pedro-localization-before-follower`: Configure and validate the selected localizer before relying on follower tuning or autonomous path accuracy.
10. `shared.pedro-explicit-coordinate-conversion`: State the source and destination coordinate systems and convert explicitly when exchanging poses between Pedro Pathing and FTC-standard or vision coordinate systems.

The guide will additionally explain installation prerequisites, the four constants categories, the official tuning sequence, localizer choices, path building, path completion, and basic troubleshooting. These explanatory topics do not all need separate enforceable rules.

### 6.5 goBILDA motors and servos

11. `shared.gobilda-identify-exact-sku`: Identify the exact product and SKU before recording or applying gear ratio, speed, torque, current, encoder, voltage, PWM, or travel specifications.
12. `shared.gobilda-use-output-shaft-encoder-resolution`: When converting encoder counts to mechanism motion, use the documented output-shaft resolution for the exact motor SKU and include any external transmission ratio in the calculation.
13. `shared.gobilda-separate-stall-and-operating-values`: Label no-load and stall specifications distinctly and do not present stall values as continuous operating ratings.
14. `shared.gobilda-servo-mode-and-pwm-range`: Record servo mode, documented PWM range, voltage, direction, and travel for the exact SKU before defining software endpoints or interpreting a position command.

The guide will use the 5203 Series 19.2:1 Yellow Jacket motor, SKU `5203-2402-0019`, and the 2000 Series 5-turn dual-mode torque servo, SKU `2000-0025-0502`, as worked examples. It will make clear that their values do not apply to every goBILDA motor or servo.

### 6.6 Limelight 3A

15. `shared.limelight-check-result-validity`: Check that the latest result exists and is valid before using target or pose fields.
16. `shared.limelight-enforce-freshness-policy`: Define and enforce a task-appropriate maximum result age before using Limelight data in closed-loop control or localization.
17. `shared.limelight-synchronize-pipeline-dependent-reads`: Treat pipeline switching as asynchronous and verify the reported pipeline before consuming data when an operation depends on the new pipeline.
18. `shared.limelight-configure-camera-pose`: Configure the camera pose relative to the robot before using field localization, and provide current robot orientation before consuming MegaTag 2 results.
19. `shared.limelight-back-up-before-os-update`: Back up pipelines and scripts before flashing or upgrading LimelightOS.

The guide will additionally cover mounting, USB connection to the Control Hub USB 3.0 port, robot configuration, web-interface setup, initialization and lifecycle, pipeline selection, basic target fields, AprilTag pose choices, snapshots, and diagnostic telemetry.

## 7. Source Set

Implementation research begins from these first-party pages and follows only their first-party links where more detail is needed.

### Android Studio and FTC SDK

- `https://github.com/FIRST-Tech-Challenge/FtcRobotController/releases`
- the README and Gradle files at the selected official release tag, including `build.gradle`, `build.common.gradle`, `build.dependencies.gradle`, `TeamCode/build.gradle`, and `gradle/wrapper/gradle-wrapper.properties`;
- `https://ftc-docs.firstinspires.org/en/latest/programming_resources/tutorial_specific/android_studio/downloading_as_project_folder/Downloading-the-Android-Studio-Project-Folder.html`
- current FIRST Android Studio installation, OpMode, project-management, Robot Controller update, and hardware-configuration pages linked from FTC Docs;
- the Android Developers compatibility page for the exact Android Gradle Plugin used by the selected FTC SDK release.

If an evergreen FTC Docs page conflicts with the selected release's README or pinned Gradle files, the guide treats the release artifact as authoritative for that release and documents the discrepancy. It does not silently combine instructions from different FTC SDK generations.

### FTCLib

- `https://docs.ftclib.org/ftclib/installation`
- the official FTCLib repository, releases, and module build files linked by the documentation;
- official FTCLib API pages used by the minimum Java example.

The implementation must verify whether the installation page's versions and native-library instructions remain current. If the official page and latest release disagree, the guide pins a coherent documented combination or records the unresolved mismatch instead of guessing.

### FTC Dashboard

- `https://acmerobotics.github.io/ftc-dashboard/gettingstarted.html`
- `https://github.com/acmerobotics/ftc-dashboard/releases`
- official FTC Dashboard configuration, telemetry, and Javadoc pages used by the minimum example.

The guide uses a stable release. The official local-development `-SNAPSHOT` path may be mentioned as an advanced appendix but is not part of the team setup procedure.

### Pedro Pathing

- `https://pedropathing.com/docs/pathing`
- `https://pedropathing.com/docs/pathing/constants`
- `https://pedropathing.com/docs/pathing/tuning`
- `https://pedropathing.com/docs/pathing/tuning/localization`
- `https://pedropathing.com/docs/pathing/reference/coordinates`
- the official `Pedro-Pathing/PedroPathing` repository for commit-pinned code evidence when appropriate.

### goBILDA

- `https://www.gobilda.com/5203-series-yellow-jacket-planetary-gear-motor-19-2-1-ratio-24mm-length-8mm-rex-shaft-312-rpm-3-3-5v-encoder/`
- the linked official spec sheet for SKU `5203-2402-0019`;
- `https://www.gobilda.com/2000-series-5-turn-dual-mode-servo-25-2-torque/`
- official manuals linked from goBILDA product pages when a rule depends on configuration procedure rather than a product specification.

### Limelight

- `https://docs.limelightvision.io/docs/docs-limelight/getting-started/limelight-3a`
- `https://docs.limelightvision.io/docs/docs-limelight/apis/ftc-programming`
- official pages linked from those documents for pipeline setup, calibration, AprilTags, and software updates;
- official FTC samples and Javadoc linked by the programming guide for code-level evidence.

## 8. Data Flow

```text
First-party documentation
→ extract narrowly scoped claims with version/SKU context
→ write web or Git evidence
→ create shared candidate YAML rules
→ validate schema and policy
→ write Chinese guide sections that reference rule IDs
→ verify examples and internal links
→ software-lead review
→ later approval through the existing approval policy
```

Candidate extraction is manual and source-driven for this release. No crawler, automatic promotion, or model-generated approval is introduced.

## 9. Error Handling

- If an official page lacks a version, record the access date and explicitly state that no version was shown.
- If two official pages disagree, record both, narrow the affected product/version scope, and leave the claim out of an enforceable rule until resolved.
- If a parameter's unit or coordinate convention is ambiguous, do not infer it; mark the guide section as blocked from publication until a first-party source resolves it.
- If a code sample and prose documentation disagree, prefer neither automatically. Pin the code sample to a commit, cite the prose page separately, and document the discrepancy for review.
- If a product family contains different SKUs, never generalize one SKU's numeric specifications to the family.
- If a rule cannot be expressed as a direct, testable instruction, retain it as explanatory guide content rather than weakening the rule set.
- If a web source disappears, validation still succeeds locally, but the next human source review must mark the evidence unavailable and replace or retire the affected claim.

## 10. Testing and Verification

### 10.1 Schema and codec tests

- decode valid v1 Git evidence unchanged;
- decode valid v2 Git and web evidence;
- load mixed v1 and v2 documents from one knowledge root;
- reject missing or unknown evidence types;
- reject unknown fields for each evidence type;
- reject non-HTTPS, relative, credential-bearing, or malformed web URLs;
- reject invalid access dates and blank title, publisher, or section fields;
- preserve duplicate rule-ID detection across schema versions;
- preserve immutability of evidence collections.

### 10.2 Content tests

- all nineteen new rules validate as candidates;
- candidate rules do not appear in active resolution output;
- current approved official behavior remains unchanged for teams 20827 and 16093;
- every guide references existing rule IDs;
- every numeric specification includes a unit and a direct first-party source;
- minimum Java examples contain no real credentials and no unlabeled team-specific calibration values;
- Markdown internal links resolve locally.
- setup guides name every edited Gradle file and distinguish project-root files from the `TeamCode` module;
- Android Studio guidance keeps Gradle JDK, Java source compatibility, Android SDK levels, and FTC SDK version distinct;
- dependency guides contain a sync, build, deploy, and minimum runtime verification sequence;
- no beginner setup path uses a rolling branch, dynamic dependency version, or `-SNAPSHOT` artifact.

No automated test performs live web requests. Source availability and claim accuracy are checked during the documented research pass.

### 10.3 Acceptance commands

Using JDK 21:

```bash
./gradlew :apps:knowledge-cli:run --args="validate knowledge" --quiet
./gradlew :apps:knowledge-cli:run --args="resolve knowledge --team 20827 --season 2025-2026" --quiet
./gradlew test --quiet
```

Acceptance requires successful validation, no new active rule before approval, unchanged resolution of the existing approved official rule, and a passing full test suite.

## 11. Out of Scope

- approving the nineteen candidate rules;
- changing team-specific robot constants;
- choosing motors, servos, localizers, or cameras for a particular robot design;
- automatically scraping or mirroring vendor websites;
- Road Runner, Pinpoint, or other tool guides except where they are necessary context for one of the six selected topics;
- Systemcore, WPILib, GradleRIO, Linux deployment, or a migration guide from the legacy Android control system;
- FTCLib source builds, custom FTCLib forks, or third-party native-library combinations not documented by FTCLib;
- FTC Dashboard source development or custom `-SNAPSHOT` publication beyond a clearly separated advanced note;
- Android Studio UI, Agent Ask/Edit/Run behavior, or Control Hub deployment automation;
- translating every official document or reproducing long copyrighted passages.

## 12. Completion Criteria

The implementation is complete when:

1. schema v2 typed evidence is implemented without breaking v1;
2. the candidate rule files contain the nineteen specified candidate rules with first-party evidence;
3. the six Chinese guides follow the common writing standard and link to their related rules;
4. all technical claims are scoped by product/version where needed and use direct first-party sources;
5. validation, resolution, and the full automated test suite pass; and
6. no candidate has been presented as approved or active.
