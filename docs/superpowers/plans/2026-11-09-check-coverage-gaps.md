# Check coverage gaps — implementation plan

Date: 2026-11-09. Branch: `claude/fix-check-coverage-gaps` (worktree `.worktrees/fix-check-coverage-gaps`, based on `origin/main` 7d379ec).

## Background

A real negative test against FTC project 20827 (team 20827, season 2026-2027, profile `command-based`) found:

1. `shared.dashboard-pin-stable-dependency` uses `appliesTo: "**/*.gradle*"`. java.nio glob requires at least one `/` for `**/`, so a floating Dashboard version in the repository-root `build.dependencies.gradle` was **not** blocked, while the same line in `TeamCode/build.gradle` was.
2. The Dashboard displayed the rule text "task-appropriate maximum result age" as `ta[REDACTED] maximum result age`: the audit/review secret regex treated `sk-appropriate` inside the word "task-appropriate" as an `sk-` API key.

Out of scope (decided separately by the maintainer): season scope of `shared.ftc-sdk-*` rules; OpModes outside `teamcode/tests` (documented limitation).

The working tree already contains the changes for Tasks 1 and 2 (uncommitted). The tasks below verify, adjust and commit them.

## Global Constraints

- Work only inside `/Users/xinlu/Documents/ChatGPT/FTC Knowledge bank/.worktrees/fix-check-coverage-gaps`. Never touch the main checkout `/Users/xinlu/Documents/ChatGPT/FTC Knowledge bank` or other worktrees. Never push. Never use bare `git stash`.
- Do not modify any file under `knowledge/`.
- Commit only the files listed in the task. Commit messages end with the line `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Kotlin pitfall: block comments nest, so never write the character pair slash-star inside a comment (for example inside KDoc text).
- Use `set -o pipefail` (or check `$?` of gradle directly) — piping gradle into `tail`/`grep` hides its exit code.
- JDK 21 / Gradle wrapper: `./gradlew ... --console=plain`.

## Task 1: Limit the redaction fix to the audit codecs

The model-provider `SecretRedactor` must keep detecting `sk-` tokens glued directly after other text (existing test `redacts multiple standalone and exact secrets without overlapping replacements` requires it), so the word-boundary change there is reverted. The Dashboard false positive comes from the audit codecs, which keep the fix.

Steps:

1. In `modules/model-provider/src/main/kotlin/org/ftckb/model/SecretRedactor.kt`, restore the original line exactly:
   `    private val skToken=Regex("(?i)sk-[A-Za-z0-9][A-Za-z0-9._-]{10,}")`
   After this step `git diff -- modules/model-provider` must be empty.
2. In `modules/agent-runtime/src/test/kotlin/org/ftckb/agent/SecretRedactorTest.kt`, delete the whole test function `keeps words that merely contain the sk prefix` (it was added in the working tree). After this step `git diff -- modules/agent-runtime` must be empty.
3. Keep, unchanged, the working-tree edits in:
   - `modules/audit/src/main/kotlin/org/ftckb/audit/AuditCodec.kt` and `modules/audit/src/main/kotlin/org/ftckb/audit/ReviewCodec.kt`: the `secret` regex alternative is `(?<![A-Za-z0-9_-])(?:sk-(?:proj-)?|sk-ant-)[A-Za-z0-9_-]{8,}` (lookbehind added).
   - `modules/audit/src/test/kotlin/org/ftckb/audit/FileAuditStoreTest.kt`: test `rule text containing sk inside a word is persisted intact`.
   - `modules/audit/src/test/kotlin/org/ftckb/audit/FileReviewStoreTest.kt`: test `reasons quoting words that contain sk are not treated as secrets`.
4. Verify (both must pass, report counts):
   `./gradlew :modules:agent-runtime:test --tests 'org.ftckb.agent.SecretRedactorTest' --tests 'org.ftckb.agent.CredentialRedactorTest' --console=plain`
   `./gradlew :modules:audit:test --console=plain`
5. Commit exactly these four files: `AuditCodec.kt`, `ReviewCodec.kt`, `FileAuditStoreTest.kt`, `FileReviewStoreTest.kt`. Subject: `fix(audit): stop redacting sk- inside ordinary words`. Body: one or two lines explaining the "task-appropriate" false positive.

## Task 2: Commit the globstar fix for rule path globs

Already in the working tree:

- New `modules/standardizer/src/main/kotlin/org/ftckb/standardizer/RuleGlobs.kt` (`RuleGlobs.compile`, `RuleGlobs.zeroDirectoryVariants`): a standalone `**/` segment may also match zero directories; at most 4 such segments are expanded; segments inside braces or after other characters are left alone; invalid globs return null.
- `modules/standardizer/src/main/kotlin/org/ftckb/standardizer/Standardizer.kt`: `evaluate` builds matchers through `RuleGlobs.compile` with a per-call cache; unused `FileSystems` import removed.
- `modules/standardizer/src/test/kotlin/org/ftckb/standardizer/StandardizerTest.kt`: tests `double star globs also match root level files` and `zero directory variants cover every standalone double star segment`.
- `docs/standardizer-check.md`: one paragraph describing the globstar semantics.

Steps:

1. Verify: `./gradlew :modules:standardizer:test --console=plain` passes. If a test fails, fix the cause in the files above only (do not weaken the two new tests' expectations) and report what you changed.
2. Commit exactly these four files. Subject: `fix(standardizer): let ** match zero directories in rule globs`. Body: one or two lines naming the root-level `build.dependencies.gradle` miss.

## Task 3: End-to-end verification (no code changes, no commit)

1. `./gradlew :apps:knowledge-cli:installDist --console=plain` and `./gradlew :apps:knowledge-cli:test --console=plain`; report pass/fail counts (from `apps/knowledge-cli/build/test-results/test/*.xml`) and the names of any failing tests.
2. `apps/knowledge-cli/build/install/ftckb/bin/ftckb validate knowledge --json` must report `"ok":true`.
3. Use a scratch audit directory so the maintainer's audit log is untouched: `export FTCKB_AUDIT_DIR=/private/tmp/claude-501/-Users-xinlu-Documents-GitHub-FTC20827-BioBuzz/404509bc-c542-43b8-94ea-a3d929a463e1/scratchpad/audit-verify`.
4. Negative patch (read-only FTC repo):
   `apps/knowledge-cli/build/install/ftckb/bin/ftckb check /Users/xinlu/Documents/GitHub/FTC20827-BioBuzz --knowledge "$PWD/knowledge" --team 20827 --season 2026-2027 --profile command-based --diff /private/tmp/claude-501/-Users-xinlu-Documents-GitHub-FTC20827-BioBuzz/404509bc-c542-43b8-94ea-a3d929a463e1/scratchpad/ftckb-run/hard-violations.patch --json`
   Expected (corrected after Task 3): exit code 1 and four violations — `global.test-utility-layout` ×2, `official.keep-customizations-in-teamcode`, **plus** `shared.dashboard-pin-stable-dependency` on `build.dependencies.gradle`. (`global.solverslib-production-imports` exists only in the main checkout's uncommitted knowledge, not on `origin/main`.)
5. Prototype patch, same command with `--diff .../scratchpad/ftckb-run/turretless-aim.patch`. Expected: exit code 0, zero violations, 15 soft entries with this branch's knowledge (the earlier 18 came from the main checkout's uncommitted knowledge).
6. Write all commands, exit codes and violation lists to the report file. Do not commit anything.
