# FTC Android Studio and Library Setup Guides Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add seven evidence-backed setup rules and beginner Chinese guides for Android Studio/FTC SDK, FTCLib, and FTC Dashboard.

**Architecture:** Three schema v2 files hold shared candidate policy; three Chinese Markdown guides provide version-pinned procedures and troubleshooting. An acceptance test loads rules and ensures each guide cites its governing IDs. The setup guidance is Android/Control Hub only.

**Tech Stack:** YAML schema v2, Markdown, Java FTC examples, Kotlin/JUnit acceptance tests, Gradle Wrapper.

## Global Constraints

- Execute the Evidence Schema v2 plan first.
- Every new rule is `authority: shared`, `status: candidate`, with no `approval`.
- Use first-party FIRST, FTCLib, Android Developers, and FTC Dashboard sources only.
- Verify live first-party pages at implementation time and record `accessedAt: 2026-08-13`.
- Pin the documented snapshot: FIRST SDK v11.2, FTCLib core 2.1.1/vision 2.1.0 as listed by its installation page, and FTC Dashboard 0.6.0.
- If a live official source contradicts those versions, stop that affected claim, record the mismatch in the guide, and do not guess a replacement.
- Distinguish Gradle JDK, Java source compatibility, Android SDK levels, FTC SDK, RC app, and DS app.
- Name every edited Gradle file exactly.
- Do not instruct beginners to use rolling branches, dynamic versions, `-SNAPSHOT`, or automatic AGP upgrades.
- FTCLib vision steps are conditional and must not be presented as core requirements.
- Each guide must include scope, concepts, prerequisites, configuration table, setup, minimal Java example, validation, troubleshooting, safety/misuse, related IDs, and official sources.
- Use Chinese prose and introduce English technical terms on first use.
- Do not approve the rules.

## File Structure

- `knowledge/shared/setup/android-studio-ftc-sdk.yaml`: three reproducibility/toolchain rules.
- `knowledge/shared/setup/ftclib.yaml`: two dependency rules.
- `knowledge/shared/setup/ftc-dashboard.yaml`: stable dependency and verification rules.
- `knowledge/guides/setup/android-studio-ftc-sdk.md`: environment-to-Control-Hub tutorial.
- `knowledge/guides/setup/ftclib.md`: core-first and optional-vision tutorial.
- `knowledge/guides/setup/ftc-dashboard.md`: install/use/debug tutorial.
- `apps/knowledge-cli/src/test/kotlin/org/ftckb/cli/KnowledgeGuideAcceptanceTest.kt`: content-to-rule linkage.

---

### Task 1: Add seven setup candidate rules

**Files:**
- Create: `knowledge/shared/setup/android-studio-ftc-sdk.yaml`
- Create: `knowledge/shared/setup/ftclib.yaml`
- Create: `knowledge/shared/setup/ftc-dashboard.yaml`
- Create: `apps/knowledge-cli/src/test/kotlin/org/ftckb/cli/KnowledgeGuideAcceptanceTest.kt`

**Interfaces:** Seven globally unique rule IDs loaded recursively but never active while candidate.

- [ ] **Step 1: Write the failing rule-count/linkage test**

Create `KnowledgeGuideAcceptanceTest.kt`:

```kotlin
package org.ftckb.cli

import java.nio.file.Files
import java.nio.file.Path
import org.ftckb.domain.RuleStatus
import org.ftckb.knowledge.FileKnowledgeRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KnowledgeGuideAcceptanceTest {
    private val root=Path.of("..","..","knowledge").normalize()

    @Test
    fun `setup rules are valid candidates and guides cite them`() {
        val expected=mapOf(
            "guides/setup/android-studio-ftc-sdk.md" to setOf(
                "shared.ftc-sdk-pin-release",
                "shared.ftc-sdk-preserve-build-tooling",
                "shared.ftc-sdk-separate-toolchain-versions"
            ),
            "guides/setup/ftclib.md" to setOf(
                "shared.ftclib-check-current-prerequisites",
                "shared.ftclib-pin-module-versions"
            ),
            "guides/setup/ftc-dashboard.md" to setOf(
                "shared.dashboard-pin-stable-dependency",
                "shared.dependency-verify-sync-build-run"
            )
        )
        val loaded=FileKnowledgeRepository.load(root)
        assertTrue(loaded.violations.isEmpty(),loaded.violations.joinToString())
        val byId=loaded.rules.associateBy { it.id }

        expected.forEach { (guidePath,ids) ->
            val guide=Files.readString(root.resolve(guidePath))
            ids.forEach { id ->
                assertEquals(RuleStatus.CANDIDATE,byId.getValue(id).status,id)
                assertTrue(id in guide,"$guidePath must cite $id")
            }
        }
    }
}
```

- [ ] **Step 2: Run and verify missing files/IDs**

```bash
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew :apps:knowledge-cli:test --tests 'org.ftckb.cli.KnowledgeGuideAcceptanceTest'
```

Expected: FAIL because setup rule and guide files do not exist.

- [ ] **Step 3: Create exact candidate YAML**

Create `android-studio-ftc-sdk.yaml` with schema version 2 and three rules. Use these exact semantic fields:

```yaml
schemaVersion: 2
rules:
  - id: shared.ftc-sdk-pin-release
    topic: ftc-sdk-version-source
    title: Pin the official FTC SDK release
    instruction: Build team projects from an identified FIRST FtcRobotController release or tag and record its FTC SDK version.
    rationale: A rolling branch can change and cannot reproduce a known robot build.
    status: candidate
    authority: shared
    applicability: {teams: [], seasons: [2025-2026]}
    evidence:
      - type: web
        url: https://github.com/FIRST-Tech-Challenge/FtcRobotController/releases/tag/v11.2
        title: FtcRobotController v11.2
        publisher: FIRST Tech Challenge
        accessedAt: 2026-08-13
        section: v11.2 release
        version: v11.2
  - id: shared.ftc-sdk-preserve-build-tooling
    topic: ftc-sdk-build-tooling
    title: Preserve release-supplied build tooling
    instruction: Use the Gradle Wrapper, Android Gradle Plugin, and Android build configuration supplied by the selected FTC SDK release unless FIRST documents a compatible change.
    rationale: Independent upgrades can make the FTC workspace incompatible with Android Studio or the robot controller runtime.
    status: candidate
    authority: shared
    applicability: {teams: [], seasons: [2025-2026]}
    evidence:
      - type: web
        url: https://ftc-docs.firstinspires.org/en/latest/programming_resources/tutorial_specific/android_studio/downloading_as_project_folder/Downloading-the-Android-Studio-Project-Folder.html
        title: Downloading the Android Studio Project Folder
        publisher: FIRST Tech Challenge
        accessedAt: 2026-08-13
        section: Importing the Project into Android Studio
  - id: shared.ftc-sdk-separate-toolchain-versions
    topic: ftc-toolchain-version-record
    title: Record FTC toolchain versions separately
    instruction: Record the Gradle JDK, Java source compatibility, Android SDK levels, and FTC SDK version as distinct values from their actual configuration locations.
    rationale: These similarly named versions control different parts of the build and are not interchangeable.
    status: candidate
    authority: shared
    applicability: {teams: [], seasons: [2025-2026]}
    evidence:
      - type: web
        url: https://developer.android.com/build/releases/agp-8-13-0-release-notes
        title: Android Gradle Plugin 8.13.0
        publisher: Android Developers
        accessedAt: 2026-08-13
        section: Compatibility
        version: "8.13"
```

Create `ftclib.yaml` with the IDs, instructions, and official URL:

```yaml
schemaVersion: 2
rules:
  - id: shared.ftclib-check-current-prerequisites
    topic: ftclib-prerequisite-editing
    title: Compare FTCLib prerequisites before editing
    instruction: Compare FTCLib prerequisites with the pinned FTC SDK and apply only settings that are not already satisfied.
    rationale: Blindly copying historical setup steps can overwrite FIRST-managed configuration or duplicate current defaults.
    status: candidate
    authority: shared
    applicability: {teams: [], seasons: []}
    evidence:
      - type: web
        url: https://docs.ftclib.org/ftclib/installation
        title: FTCLib Installation
        publisher: FTCLib
        accessedAt: 2026-08-13
        section: Installation
  - id: shared.ftclib-pin-module-versions
    topic: ftclib-module-selection
    title: Pin only required FTCLib modules
    instruction: Declare only required FTCLib modules with exact documented versions and apply vision native-library or ABI steps only when using the vision module.
    rationale: Core and vision have separate versions and prerequisites.
    status: candidate
    authority: shared
    applicability: {teams: [], seasons: []}
    evidence:
      - type: web
        url: https://docs.ftclib.org/ftclib/installation
        title: FTCLib Installation
        publisher: FTCLib
        accessedAt: 2026-08-13
        section: Installation
```

Create `ftc-dashboard.yaml`:

```yaml
schemaVersion: 2
rules:
  - id: shared.dashboard-pin-stable-dependency
    topic: dashboard-dependency-version
    title: Pin a stable FTC Dashboard dependency
    instruction: Configure the official FTC Dashboard Maven repository and an exact stable dependency version for team projects.
    rationale: Dynamic and snapshot artifacts make competition builds non-reproducible.
    status: candidate
    authority: shared
    applicability: {teams: [], seasons: []}
    evidence:
      - type: web
        url: https://acmerobotics.github.io/ftc-dashboard/gettingstarted.html
        title: FTC Dashboard Getting Started
        publisher: FTC Dashboard
        accessedAt: 2026-08-13
        section: Basic Installation
        version: "0.6.0"
  - id: shared.dependency-verify-sync-build-run
    topic: ftc-dependency-verification
    title: Verify dependencies through runtime
    instruction: Require successful Gradle Sync, local build, Robot Controller deployment, and a minimal runtime check after adding or changing an FTC library.
    rationale: Dependency resolution alone does not prove that the deployed robot app contains and can use the library.
    status: candidate
    authority: shared
    applicability: {teams: [], seasons: []}
    evidence:
      - type: web
        url: https://acmerobotics.github.io/ftc-dashboard/gettingstarted.html
        title: FTC Dashboard Getting Started
        publisher: FTC Dashboard
        accessedAt: 2026-08-13
        section: Basic Usage
        version: "0.6.0"
```

- [ ] **Step 4: Add temporary guide stubs only to turn the failure into missing-ID assertions**

Create each guide with its title and a `相关规则` section listing its IDs. Do not commit the stubs separately; Tasks 2-4 replace them before the task-level commit.

- [ ] **Step 5: Validate rules**

```bash
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew :apps:knowledge-cli:run --args="validate knowledge" --quiet
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew :apps:knowledge-cli:run --args="resolve knowledge --team 20827 --season 2025-2026" --quiet
```

Expected after adding seven candidates:

```text
validation=ok rules=11
active official.keep-customizations-in-teamcode
```

Do not commit until the three guide tasks replace stubs.

---

### Task 2: Write Android Studio and FTC SDK guide

**Files:**
- Replace: `knowledge/guides/setup/android-studio-ftc-sdk.md`

**Interfaces:** Cites the three `shared.ftc-sdk-*` rules and uses v11.2 as a dated snapshot.

- [ ] **Step 1: Re-verify the pinned release**

Open the official v11.2 release/tag README and tag-pinned `build.gradle`, `build.common.gradle`, `build.dependencies.gradle`, `TeamCode/build.gradle`, and wrapper properties. Record exact Android Studio floor, AGP, Gradle, compile/min/target SDK, NDK, Java source compatibility, and FTC artifact version. Do not read those values from rolling `master`.

Expected consistency table shape:

```markdown
| 名称 | v11.2 核验值 | 在哪里查看 | 用途 |
|---|---:|---|---|
| FTC SDK | v11.2 | Git release/tag | RC/DS API 与赛季基线 |
| Android Studio | Narwhal 3 Feature Drop 或更高 | release notes | IDE |
| Gradle JDK | 与该 AGP 兼容的 JDK；优先 AS Embedded JDK | Settings > Build Tools > Gradle | 运行 Gradle |
| Java sourceCompatibility | 以 tag 中 build.common.gradle 为准 | build.common.gradle | 编译 TeamCode Java |
| compile/min/target SDK | 以 tag 中 build.common.gradle 为准 | build.common.gradle | Android 构建与设备兼容 |
```

- [ ] **Step 2: Write the complete guide in the standard section order**

The setup procedure must contain these observable gates:

1. install Narwhal 3 Feature Drop or later;
2. download/clone tag v11.2, not `master`;
3. open the extracted project root and trust it;
4. select the compatible embedded Gradle JDK;
5. use SDK Manager to install only tag-required Android SDK/NDK components;
6. reject automatic AGP upgrade prompts;
7. complete online Gradle Sync;
8. run `./gradlew :TeamCode:assembleDebug`;
9. create `TeamCode/src/main/java/org/firstinspires/ftc/teamcode/SetupSmokeTest.java`;
10. deploy to Control Hub and confirm `Setup Smoke Test` appears on Driver Station.

Use this minimum OpMode:

```java
package org.firstinspires.ftc.teamcode;

import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode;
import com.qualcomm.robotcore.eventloop.opmode.TeleOp;

@TeleOp(name="Setup Smoke Test",group="Setup")
public class SetupSmokeTest extends LinearOpMode {
    @Override
    public void runOpMode() {
        telemetry.addLine("FTC SDK build and deployment succeeded");
        telemetry.update();
        waitForStart();
        while (opModeIsActive()) {
            telemetry.addData("Runtime (s)",getRuntime());
            telemetry.update();
            idle();
        }
    }
}
```

Explain that a successful local build does not prove USB deployment, RC/DS compatibility, hardware configuration, or runtime.

- [ ] **Step 3: Add exact troubleshooting rows**

Include symptoms for unsupported class-file/JDK errors, missing SDK platform/NDK, dependency resolution, endless Sync, unwanted AGP upgrade, no Control Hub device, install failure, RC/DS mismatch, and OpMode absent. Each row must order checks from version record to build to device to annotation/package.

- [ ] **Step 4: Run linkage test**

```bash
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew :apps:knowledge-cli:test --tests 'org.ftckb.cli.KnowledgeGuideAcceptanceTest'
```

Expected: Android guide assertions pass; other stubs still satisfy only linkage.

---

### Task 3: Write FTCLib guide

**Files:**
- Replace: `knowledge/guides/setup/ftclib.md`

**Interfaces:** Cites two FTCLib rule IDs and uses core-first installation.

- [ ] **Step 1: Build the prerequisite comparison table**

Compare the v11.2 tag with FTCLib's installation page:

```markdown
| FTCLib 文档要求 | v11.2 当前状态 | 操作 |
|---|---|---|
| mavenCentral() | 已在官方依赖配置中时 | 不重复添加 |
| minSdkVersion 24 | 以 tag 为准 | 相同则不改 |
| multiDexEnabled | 先核对 tag | 仅在仍缺失且官方要求时添加 |
| JavaVersion.VERSION_1_8 | 以 tag 为准 | 不把 Gradle JDK 误改成 8 |
| core 2.1.1 | FTCLib 页面列出的版本 | 加到 TeamCode/build.gradle |
| vision 2.1.0 | 可选 | 仅使用视觉模块时添加并处理其官方前置条件 |
```

Explicitly label FTCLib's page as the source of listed versions, not proof of compatibility with every future SDK.

- [ ] **Step 2: Write core installation and exact Gradle edit**

Use `TeamCode/build.gradle`:

```groovy
dependencies {
    implementation project(':FtcRobotController')
    implementation 'org.ftclib.ftclib:core:2.1.1'
}
```

Do not instruct changes to `build.common.gradle` when v11.2 already satisfies a setting. Explain how to inspect Gradle dependency output:

```bash
./gradlew :TeamCode:dependencies --configuration debugRuntimeClasspath
./gradlew :TeamCode:assembleDebug
```

- [ ] **Step 3: Add a minimal core smoke test and optional vision branch**

Use:

```java
package org.firstinspires.ftc.teamcode;

import com.arcrobotics.ftclib.command.CommandOpMode;
import com.qualcomm.robotcore.eventloop.opmode.TeleOp;

@TeleOp(name="FTCLib Smoke Test",group="Setup")
public class FtclibSmokeTest extends CommandOpMode {
    @Override
    public void initialize() {
        telemetry.addLine("FTCLib core loaded");
        telemetry.update();
    }
}
```

Before publishing, compile this exact example against the pinned dependency; if the official API has changed, replace it with a first-party FTCLib example and cite its page/commit. Put vision in a separate optional section with `vision:2.1.0`; do not copy ABI/native-library steps unless live FTCLib docs still require them for the pinned pair.

- [ ] **Step 4: Add troubleshooting and verify**

Cover failed artifact resolution, duplicate classes/FTC artifacts, missing native library, ABI mismatch, `NoClassDefFoundError`, and core/vision version confusion.

```bash
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew :apps:knowledge-cli:test --tests 'org.ftckb.cli.KnowledgeGuideAcceptanceTest'
```

Expected: setup linkage test passes.

---

### Task 4: Write FTC Dashboard guide and close setup acceptance

**Files:**
- Replace: `knowledge/guides/setup/ftc-dashboard.md`
- Modify: `README.md`

**Interfaces:** Cites Dashboard and shared dependency-verification rules.

- [ ] **Step 1: Write exact stable dependency configuration**

Use stable 0.6.0. Prefer team-owned configuration in `TeamCode/build.gradle`:

```groovy
repositories {
    maven { url='https://maven.brott.dev/' }
}

dependencies {
    implementation project(':FtcRobotController')
    implementation 'com.acmerobotics.dashboard:dashboard:0.6.0'
}
```

Explain that the Dashboard page names `build.dependencies.gradle`, while the stock FTC project marks `TeamCode/build.gradle` as the team customization point. Cite both first-party sources and do not edit `build.common.gradle`. Keep the OpenRC/non-standard SDK exclusion in an advanced note only.

- [ ] **Step 2: Add the minimum dashboard smoke test**

```java
package org.firstinspires.ftc.teamcode;

import com.acmerobotics.dashboard.FtcDashboard;
import com.acmerobotics.dashboard.config.Config;
import com.acmerobotics.dashboard.telemetry.MultipleTelemetry;
import com.qualcomm.robotcore.eventloop.opmode.OpMode;
import com.qualcomm.robotcore.eventloop.opmode.TeleOp;

@Config
@TeleOp(name="Dashboard Smoke Test",group="Setup")
public class DashboardSmokeTest extends OpMode {
    public static double TEST_VALUE=0.25;

    @Override
    public void init() {
        telemetry=new MultipleTelemetry(
            telemetry,
            FtcDashboard.getInstance().getTelemetry()
        );
    }

    @Override
    public void loop() {
        telemetry.addData("TEST_VALUE",TEST_VALUE);
        telemetry.update();
    }
}
```

Require compile, deploy, Control Hub Wi-Fi, open `http://192.168.43.1:8080/dash`, run OpMode, change `TEST_VALUE`, and observe matching telemetry. Warn that live values are mutable and should start with safe limits; do not tune a moving mechanism without physical safeguards.

- [ ] **Step 3: Add troubleshooting and README navigation**

Cover Maven resolution, Sync succeeded but old APK deployed, wrong RC network/IP, page unavailable, `@Config` field not visible, and telemetry missing. Add README links under a new setup-guides subsection.

Add this test to `KnowledgeGuideAcceptanceTest.kt` so current and future relative guide links fail closed:

```kotlin
@Test
fun `relative links in knowledge guides resolve`() {
    val linkPattern=Regex("""\[[^]]+]\(([^)]+)\)""")
    Files.walk(root.resolve("guides")).use { paths ->
        paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".md") }.forEach { guide ->
            linkPattern.findAll(Files.readString(guide)).forEach { match ->
                val link=match.groupValues[1]
                if (!link.startsWith("http://") && !link.startsWith("https://") &&
                    !link.startsWith("#") && !link.startsWith("mailto:")) {
                    val target=guide.parent.resolve(link.substringBefore('#')).normalize()
                    assertTrue(Files.exists(target),"$guide has missing link $link")
                }
            }
        }
    }
}
```

- [ ] **Step 4: Run full acceptance**

```bash
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew clean test --quiet
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew :apps:knowledge-cli:run --args="validate knowledge" --quiet
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew :apps:knowledge-cli:run --args="resolve knowledge --team 20827 --season 2025-2026" --quiet
git diff --check
```

Expected:

```text
validation=ok rules=11
active official.keep-customizations-in-teamcode
```

Expected: all tests pass; candidates are absent from active output.

- [ ] **Step 5: Commit setup rules and guides**

```bash
git add knowledge/shared/setup knowledge/guides/setup apps/knowledge-cli/src/test/kotlin/org/ftckb/cli/KnowledgeGuideAcceptanceTest.kt README.md
git commit -m "docs: add FTC setup knowledge guides"
```
