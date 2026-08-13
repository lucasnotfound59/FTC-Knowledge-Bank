# FTC Knowledge Bank CLI Agent Design

Date: 2026-08-14

Status: Approved design; implementation has not started

Initial users: FTC teams 20827 and 16093

This specification supersedes the CLI entry point, provider baseline and Edit/Git workflow in `2026-08-13-ftc-agent-mvp-design.md`. The older document remains the long-term product direction, but its dedicated-branch requirement does not apply to this CLI release.

## 1. Objective

Build the first usable FTC Knowledge Bank Agent as a Kotlin command-line application. It will open an existing local FTC repository, maintain a continuous conversation, answer questions using local code and approved knowledge, and modify code in an explicitly enabled Edit mode.

The first release must prove that the Agent can use the checked-in knowledge correctly before an Android Studio client, build execution, robot deployment, or live web retrieval is added.

The core workflow is:

```text
Open local FTC repository
→ identify project, team, season, code and dependencies
→ resolve approved knowledge
→ chat continuously
→ retrieve only relevant local context
→ answer with verified citations
→ optionally enter Edit mode
→ validate and apply model-proposed changes on the current branch
→ show the Agent-specific diff and support undo
```

## 2. Confirmed Product Decisions

- The first client is a command-line continuous chat, not an Android Studio plugin.
- The implementation remains a Kotlin/JVM modular monolith.
- The model layer uses an internal provider interface and an OpenAI-compatible Chat Completions adapter.
- OpenAI, DeepSeek and custom compatible services are configured with a provider name, `baseUrl`, `model` and API-key environment variable.
- The compatibility baseline is ordinary text Chat Completions. Native function calling, Responses API tools and provider-hosted web search are not required.
- Only the minimum code fragments needed for the current question are sent to the configured cloud model.
- The first release does not access live web pages. It uses the local target repository, approved YAML rules and checked-in Markdown guides.
- Live official-document retrieval is a required future component and is recorded in `todolist.md` before Agent implementation begins.
- Answers distinguish approved rules, observations from current code, model inference and insufficient evidence.
- Conversation history exists only in memory unless the user explicitly enters `/save`.
- The default mode is read-only Ask.
- Edit is enabled for the current session with `/mode edit` and directly modifies the user's current Git branch.
- Edit does not create or switch branches and does not require per-patch confirmation.
- Files that already contain uncommitted user work may still be edited. Undo must restore their exact pre-Agent content.
- The Agent never automatically commits, pushes, merges, runs Gradle, accesses a robot or deploys code.
- `TeamCode/**` is the ordinary edit scope. Required changes to Gradle or other project configuration are permitted but must be highlighted as project-level changes.

## 3. Scope

### 3.1 In scope

- Open a local FTC SDK repository selected by path.
- Require or accept an explicit team number and season for deterministic rule resolution.
- Detect common FTC project markers, FTC SDK structure, source roots, dependencies and relevant configuration files.
- Build an in-memory local index of supported text files.
- Resolve approved official, team and shared rules through the existing knowledge module.
- Search checked-in Markdown guides as explanatory material without treating them as enforceable rules.
- Use a two-stage, host-controlled retrieval process that does not require model tool calling.
- Maintain a continuous multi-turn terminal conversation.
- Manage model context using recent turns plus a rolling summary.
- Validate code, rule and guide citations before displaying them as evidence.
- Enter and leave a session-scoped Edit mode.
- Create, replace, delete and move repository-local text files through validated structured operations.
- Modify the current Git branch without creating or switching branches.
- Show per-batch Agent changes, support `/undo`, and restore the session baseline with `/discard`.
- Save a redacted conversation record only when the user requests `/save`.
- Work with a deterministic fake provider for automated tests.

### 3.2 Out of scope

- Native model function calling or provider-specific hosted tools.
- Embedding generation, a vector database or a hosted retrieval service.
- Automatic live access to FIRST, Pedro Pathing, FTCLib, FTC Dashboard, Limelight, goBILDA or other websites.
- Candidate-rule extraction or automatic rule approval.
- Arbitrary shell execution.
- Gradle build, test, formatting or static-analysis execution from the Agent.
- Automatic Git commit, push, pull, merge, rebase or history rewriting.
- Android Studio UI integration.
- Control Hub access, ADB, Logcat or physical robot deployment.
- Public accounts, remote conversation storage or multi-tenant hosting.

## 4. Approaches Considered

### 4.1 Host-controlled two-stage retrieval

The model first produces a small retrieval intent. Kotlin validates that intent, searches local code and knowledge, constructs a bounded context pack, and asks the model for the final answer or edit plan.

Advantages:

- needs only baseline text Chat Completions;
- behaves consistently across OpenAI, DeepSeek and simpler compatible services;
- keeps file access and permissions under deterministic local control;
- makes retrieval and citation behavior independently testable.

Costs:

- usually requires two model calls per user turn;
- host retrieval can miss context if the first intent is weak;
- requires a retry and fallback strategy for malformed structured text.

### 4.2 Native model tool-calling loop

The model would call tools such as `searchCode`, `readFile` and `resolveRules` repeatedly.

This is more flexible for long investigations, but OpenAI-compatible providers differ in tool schema, reasoning-message and continuation behavior. It is deferred until the baseline provider matrix is proven.

### 4.3 Embedding and vector retrieval

The repository and knowledge would be embedded and searched semantically.

This introduces a second model type, indexing lifecycle, incremental updates, storage and additional provider compatibility problems. FTC repositories are small enough for local lexical and structural retrieval in the first release.

### 4.4 Selected direction

Use host-controlled two-stage retrieval. The model supplies language understanding and synthesis; Kotlin owns repository access, knowledge resolution, validation, citation checking and all writes.

## 5. Module Architecture

The implementation extends the existing Gradle multi-project repository:

```text
apps/
└── knowledge-cli/                    # Terminal commands and REPL presentation

modules/
├── domain/                          # Existing rule and approval domain
├── knowledge/                       # Existing YAML loading and rule resolution
├── repository-analysis/             # FTC detection, indexing and local retrieval
├── agent-runtime/                   # Conversation, context, Ask/Edit orchestration
├── model-provider/                  # Provider-neutral request and response models
├── model-provider-openai-compatible/# Chat Completions HTTP adapter
└── tooling-git/                     # Status, Agent diff and optional user commit support
```

The CLI imports these modules but contains no rule, retrieval, provider or edit policy. No new module outside `apps/knowledge-cli` imports terminal UI classes.

### 5.1 `repository-analysis`

Responsibilities:

- verify that the selected root is a plausible FTC repository;
- identify `TeamCode`, Gradle files, Java/Kotlin source roots and common FTC dependencies;
- build and incrementally refresh an in-memory index;
- search by exact symbol, path, dependency, import and lexical term;
- return line-numbered fragments with file hashes;
- exclude unsafe, generated, binary, secret and ignored content.

### 5.2 `agent-runtime`

Responsibilities:

- maintain conversation and mode state;
- construct and validate retrieval intents;
- resolve active rules for the selected team and season;
- assemble bounded context packs;
- call the provider through a provider-neutral interface;
- validate final citations and edit operations;
- maintain recent turns, rolling summary, Agent edit batches and undo state;
- expose typed events to the CLI and a future IDE client.

### 5.3 `model-provider`

The provider-neutral module defines plain internal types for:

- system, user and assistant messages;
- model request settings;
- text output;
- token-usage metadata when supplied;
- provider, authentication, rate-limit and protocol errors.

Domain and runtime code must not depend on OpenAI SDK response classes.

### 5.4 `model-provider-openai-compatible`

Responsibilities:

- send baseline Chat Completions HTTP requests;
- authenticate through a named environment variable;
- handle timeouts and response-size limits;
- normalize ordinary text output and usage metadata;
- classify HTTP and response-shape failures;
- optionally request JSON-object output only when the provider profile declares support.

### 5.5 `tooling-git`

Responsibilities:

- report the current repository and branch state without blocking existing changes;
- compute Agent-specific before/after diffs independent of pre-existing Git changes;
- report the overall Git diff separately when useful;
- support an explicit `/commit` workflow only when it can avoid silently including pre-session changes.

It does not create or switch branches.

## 6. CLI Contract

The installed form is:

```bash
ftckb chat \
  --repo /path/to/FtcRobotController \
  --knowledge /path/to/FTC-Knowledge-Bank/knowledge \
  --team 20827 \
  --season 2025-2026 \
  --provider deepseek
```

`--repo` defaults to the current directory. An installed distribution may locate its bundled knowledge directory automatically; development execution accepts an explicit `--knowledge`. Team and season must be known before team- or season-specific rules can be presented as active.

Ask can inspect a supported FTC directory that is not under Git. Edit requires the selected repository to be a Git worktree with a named current branch; a detached `HEAD` cannot satisfy the approved current-branch workflow.

The REPL supports:

| Command | Behavior |
| --- | --- |
| `/help` | Show available commands and current restrictions. |
| `/mode ask` | Enter read-only mode. |
| `/mode edit` | Authorize validated repository-local edits for this session. |
| `/undo` | Reverse the last successfully applied Agent edit batch. |
| `/discard` | Restore every Agent-touched file to its first-touch state for this session. |
| `/diff` | Show Agent changes and identify project-level changes. |
| `/save [path]` | Save a redacted conversation record; use the default sessions directory when no path is supplied. |
| `/commit` | Offer a local commit only when Agent changes can be isolated safely. |
| `/status` | Show repository, team, season, provider, model, mode and context use. |
| `/exit` | End the session without automatically saving or committing. |

Ordinary terminal text is treated as a new user message.

## 7. Provider Configuration and Compatibility

Non-secret settings live at `${user.home}/.ftckb/config.yaml` by default. `--config` may select another file. A representative configuration is:

```yaml
defaultProvider: deepseek
providers:
  deepseek:
    baseUrl: https://api.deepseek.com
    model: deepseek-chat
    apiKeyEnv: DEEPSEEK_API_KEY
    timeoutSeconds: 90
    maxOutputTokens: 4096
    maxTokensParameter: max_tokens
    jsonMode: false
  openai:
    baseUrl: https://api.openai.com/v1
    model: gpt-5.6-terra
    apiKeyEnv: OPENAI_API_KEY
    timeoutSeconds: 90
    maxOutputTokens: 4096
    maxTokensParameter: max_completion_tokens
    jsonMode: true
```

The adapter appends `/chat/completions` to a base URL whose path ends immediately before that endpoint. Provider names are user-defined profile names rather than hard-coded brands.

`maxTokensParameter` is an allowlisted compatibility choice: `max_tokens`, `max_completion_tokens`, or omitted. It does not permit arbitrary request fields.

The API key is read only from the configured environment variable. Configuration, diagnostics, saved sessions and audit text never include its value. A missing or empty environment variable is a startup error for model-backed chat.

The baseline request uses only broadly compatible fields:

- `model`;
- `messages` with system, user and assistant roles;
- the configured output-token field when supported by the selected profile;
- `stream: false` in the first release.

Reasoning controls, provider-hosted tools and arbitrary provider-specific request fields are excluded from the first release. `response_format` is sent only for a profile with `jsonMode: true`; otherwise the runtime requests fenced JSON in ordinary text and validates it locally.

"OpenAI-compatible" means compatible with this documented baseline, not guaranteed compatibility with every endpoint that uses the label. Provider contract tests make the supported subset explicit.

## 8. Repository Detection and Indexing

The repository analyzer looks for FTC markers including Gradle settings, `TeamCode`, Android application modules, FTC SDK dependencies and OpMode annotations. It reports detected evidence rather than assuming that a directory name alone proves support.

The first index supports selected text formats such as:

- `.java` and `.kt`;
- `.gradle` and `.gradle.kts`;
- `.xml`;
- `.yaml` and `.yml`;
- `.properties` excluding protected local or secret files;
- `.md` when repository documentation is relevant.

The index skips:

- `.git`, `.gradle`, build outputs, generated sources and IDE caches;
- binaries and files above configured size limits;
- protected credential and local-machine files;
- paths excluded by repository ignore rules or explicit Agent exclusions;
- symbolic links that resolve outside the repository.

The index records path, file type, size, hash, searchable terms, imports, class names, method names and line offsets. It is refreshed only for files changed by the IDE, user or Agent.

## 9. Two-Stage Ask Flow

### 9.1 Retrieval planning

For each user turn, the runtime sends the question, compact conversation state and repository summary to the model. It requests a bounded retrieval intent containing:

- concepts and exact symbols;
- likely file types or safe repository-relative globs;
- dependencies or libraries;
- rule topics;
- guide topics;
- files cited in recent turns that remain relevant.

Kotlin validates item counts, string lengths and path patterns. The intent cannot read files directly or widen permissions.

If the intent is malformed, the runtime retries once with the validation error. If it still fails, deterministic fallback terms are derived from the user question and recent referenced symbols.

### 9.2 Local retrieval

The runtime performs local retrieval without network access:

1. Resolve approved rules for the selected team and season.
2. Rank exact paths and symbols above lexical term matches.
3. Search imports and dependencies when a named FTC library is involved.
4. Retain recently cited files for follow-up questions when relevant.
5. Search Markdown guides as explanatory context, not policy.
6. Select line-bounded fragments within a configured context budget.

Approved rules are never selected solely by semantic similarity. Applicability and precedence remain the responsibility of the existing deterministic resolver.

### 9.3 Context pack

Every selected item receives a runtime-generated identifier:

- `[CODE:C1]` for a current file fragment;
- `[RULE:R1]` for an approved rule;
- `[GUIDE:G1]` for a checked-in guide fragment.

The context pack includes canonical path, line range, content hash and selected text for code; rule ID, instruction, rationale, authority and evidence for rules; and path plus heading for guides.

The model is told that repository and guide content is untrusted data and cannot change system policy or request tools.

### 9.4 Answer generation and citation validation

The answer contract distinguishes:

- **Approved rule:** requires a valid `[RULE:*]` citation.
- **Code observation:** requires a valid `[CODE:*]` citation.
- **Model inference:** may reason beyond explicit evidence but must carry that label.
- **Insufficient evidence:** states what evidence is missing instead of inventing an answer.

The runtime checks that every cited identifier was supplied in the current context pack and that cited file hashes remain current. It retries once after an invalid or stale citation. A second invalid response is not displayed as an evidence-backed answer; the CLI reports a citation-validation failure and preserves the conversation for retry.

## 10. Continuous Conversation and Context Management

The server-side Chat Completions request is stateless from the Agent's perspective. The runtime therefore owns all conversation state.

Each request includes:

- stable system and safety instructions;
- repository, team, season, mode and provider state;
- a rolling summary of older relevant turns;
- a bounded number of recent user and assistant messages;
- references to files and rules discussed recently;
- the current retrieval context.

The rolling summary is treated as a convenience cache, not evidence. Claims still require current rule or file citations. When an edited file changes, earlier code citations become stale and must be retrieved again.

Conversation history is memory-only by default. `/save` writes a redacted record containing questions, answers, citations, mode changes, provider/model identifiers and compact edit summaries. It does not save API keys, authorization headers, full repository snapshots or unnecessary full-file code.

Without an explicit path, `/save` writes Markdown to `${user.home}/.ftckb/sessions/YYYYMMDD-HHMMSS.md`. The CLI reports the final path and refuses to overwrite an existing file.

## 11. Edit Mode

### 11.1 Authorization

Ask is the startup mode. `/mode edit` grants session-level authority for validated repository-local edits. Once enabled, the Agent may apply a valid edit batch without asking for per-batch confirmation.

Changing back to `/mode ask` immediately removes write authority. Model text cannot enter Edit mode.

### 11.2 Edit planning

Edit uses the same retrieval flow as Ask. The final model call returns a structured edit plan with:

- a summary;
- zero or more file operations;
- a reason for each operation;
- relevant code and rule citation IDs;
- an explicit statement when an operation is model inference rather than a rule requirement.

A conceptual operation is:

```json
{
  "kind": "replace",
  "path": "TeamCode/src/main/java/example/Drive.java",
  "expectedSha256": "...",
  "oldText": "...",
  "newText": "...",
  "reason": "...",
  "citations": ["CODE:C1", "RULE:R1"]
}
```

Supported operation kinds are create, exact replace, delete and repository-local move.

Create requires `expectedAbsent: true`. Replace and delete require the current source hash. Move requires the source hash and an absent destination. These preconditions are part of the schema rather than optional prompt conventions.

### 11.3 Validation

Before writing, Kotlin verifies the complete batch against an in-memory virtual result:

- mode is still Edit;
- every path remains within the repository after normalization and symlink resolution;
- the file is a supported text file;
- no protected path or likely credential file is touched;
- existing content hash matches the operation precondition;
- exact replacement text exists uniquely at the expected state;
- destination paths do not overwrite unexpected files;
- operation, file-count and byte limits are respected;
- citations exist and do not claim an inactive rule;
- the resulting text is decodable and within size limits.

If JSON or the operation schema is invalid, the runtime gives the validation error to the model once. A second invalid plan is rejected with no writes.

### 11.4 Application and rollback

The runtime computes every resulting file before the first write. It records each touched file's first-touch state, including content that was already uncommitted before the Agent session.

Writes use temporary sibling files and atomic replacement when the platform supports it. If any write fails, previously written files in that batch are restored from their recorded pre-batch state.

After success, the runtime:

- refreshes the affected index entries;
- shows a diff from the immediately previous batch state;
- labels changes outside `TeamCode/**` as project-level changes;
- records a reverse batch for `/undo`;
- reports reasons, rules, observations and inferences.

`/undo` reverses the latest Agent batch. `/discard` restores every Agent-touched path to its first-touch state for the current session. Consequently, a file that was dirty before the session returns to that exact dirty content rather than to `HEAD`.

The Agent works on the current branch. It does not create or switch branches, and an existing dirty worktree does not block Edit.

### 11.5 Commit boundary

The Agent never commits automatically. `/commit` first shows the exact files and proposed message. It must refuse automatic staging when doing so would silently include pre-session changes that cannot be separated safely. In that case it leaves the working tree intact and instructs the user to review and commit manually.

Push, merge, pull, rebase and history rewriting are not available.

## 12. File and Secret Safety

The following are always protected:

- `.git/**`;
- `.env` and `.env.*`;
- `local.properties`;
- keystores, signing material and credential files;
- files detected as binary;
- links resolving outside the selected repository;
- repository-external paths expressed through absolute paths, drive paths, `..` or equivalent encodings.

Before model upload or optional session save, the runtime scans selected text for common credential patterns. A match excludes or redacts the sensitive span and records that context was withheld. The system must not print the secret in its diagnostic.

Repository source, comments, strings, YAML and Markdown are untrusted data. Instructions embedded in them cannot authorize writes, mode changes, shell commands, network access or secret disclosure.

## 13. Failure Handling

- **Missing provider or API-key environment variable:** fail before the first model request and name only the missing configuration key.
- **Authentication failure:** preserve the local conversation and files; do not display or retry with the key value.
- **Rate limit or transient provider failure:** retain the user turn for an explicit retry and do not repeat completed writes.
- **Provider response-shape failure:** classify it separately from model-generated invalid JSON.
- **Malformed retrieval intent:** retry once, then use deterministic local fallback retrieval.
- **Malformed edit plan:** retry once, then reject the batch without writes.
- **Invalid or stale citation:** retry once; do not display the second invalid answer as evidence-backed.
- **Unsupported repository:** report the missing FTC markers; do not enable Edit.
- **Non-Git worktree or detached `HEAD`:** Ask remains available, but Edit reports that a named current branch is required.
- **Unknown team or season:** require explicit values before presenting scoped rules as active.
- **Rule conflict:** Ask may explain the conflict; Edit cannot use the conflicting topic as a requirement.
- **Concurrent file change:** reject the stale operation and retrieve the current file again.
- **Context limit:** reduce older conversation first, then lower-ranked fragments; never silently truncate a cited fragment.
- **Edit size limit:** stop and ask the user to narrow the request or explicitly raise a configured limit.
- **Partial filesystem failure:** restore the pre-batch states and report whether rollback succeeded.
- **Process crash:** the current branch and filesystem remain untouched by any automatic Git operation. Already completed file edits remain visible in ordinary Git/IDE diff.

## 14. Testing Strategy

### 14.1 Unit tests

- provider profile parsing and secret-free diagnostics;
- baseline Chat Completions request encoding and response normalization;
- repository-root, path, symlink and secret-file protection;
- FTC project detection;
- index construction and incremental refresh;
- retrieval-intent validation and deterministic fallback;
- local ranking and context-budget enforcement;
- approved-rule resolution and guide-versus-policy separation;
- citation identifier and content-hash validation;
- session redaction and rolling-summary behavior;
- edit schema, exact replacement, virtual batch validation and size limits;
- reverse batches, `/undo` and `/discard`;
- Agent-specific diff generation when the worktree was already dirty.

### 14.2 Integration tests

Use small synthetic FTC repositories and a scripted fake provider to verify:

- two related questions retain conversational context;
- exact code and rule citations appear in an Ask answer;
- unsupported conclusions are labelled model inference;
- insufficient evidence is reported honestly;
- Edit modifies the current branch and does not create a branch;
- a pre-existing dirty file may be edited and `/discard` restores its exact starting bytes;
- project-level changes produce a visible warning;
- invalid paths, outside symlinks, `.env` and `local.properties` are rejected;
- invalid JSON, stale hashes and invalid citations do not leave partial writes;
- provider failure after a successful batch does not reapply that batch;
- `/save` produces a useful redacted record without full-file snapshots.

### 14.3 Provider contract tests

Checked-in, secret-free response fixtures represent the supported OpenAI and DeepSeek Chat Completions subset. They test ordinary content, missing content, error bodies, usage fields and optional JSON-object mode.

Live provider tests are manual smoke tests guarded by environment variables. Normal CI does not require internet access or consume API credits.

### 14.4 Agent quality evaluation

Maintain a fixed internal evaluation set covering questions such as:

- why an OpMode fails during initialization;
- where Pedro localization is configured;
- whether Limelight results are checked safely;
- how a TeleOp hardware initialization can be reorganized;
- a follow-up request to modify the class discussed in the previous turn.

Each result is scored for correctness, retrieval relevance, citation validity, rule-versus-inference separation, task completion and unrelated-file changes. Model or prompt changes must be compared on the same evaluation set.

## 15. MVP Acceptance Criteria

The CLI Agent MVP is accepted when it can:

1. start a continuous chat for a supported FTC repository with explicit team, season and provider context;
2. work through OpenAI, DeepSeek and a configurable baseline-compatible endpoint;
3. retrieve local code, checked-in guides and only applicable approved rules without embeddings or live web access;
4. answer a code question with verified file, line and rule citations;
5. keep a relevant follow-up conversation within a bounded context;
6. label unsupported conclusions as model inference or insufficient evidence;
7. enter Edit only through an explicit user command;
8. modify validated files on the user's current branch without creating a branch or requiring per-batch confirmation;
9. edit a pre-existing dirty file while preserving its exact session baseline for `/undo` and `/discard`;
10. warn on project-level changes and reject repository-external, secret and protected paths;
11. show an Agent-specific diff and never automatically commit, push, merge, run Gradle or deploy;
12. save a redacted conversation only through `/save`;
13. pass all unit, integration and provider contract tests;
14. complete a manual end-to-end smoke test against at least one real OpenAI-compatible provider;
15. keep the future official-document networking component recorded in `todolist.md` without implementing it in this release.

## 16. Delivery Sequence

Implementation should proceed as vertical increments:

1. provider-neutral model types, provider configuration and fake provider;
2. OpenAI-compatible baseline adapter and offline contract fixtures;
3. FTC repository detection, safe index and local retrieval;
4. continuous Ask chat with two-stage retrieval and verified citations;
5. memory-only history, rolling summary and redacted `/save`;
6. validated edit protocol and current-branch file application;
7. Agent-specific diff, `/undo`, `/discard` and guarded `/commit`;
8. full integration suite and fixed FTC quality evaluation;
9. manual OpenAI/DeepSeek-compatible smoke test and documentation.

Run mode, Android Studio integration and live official-document retrieval begin only after the Ask/Edit CLI acceptance criteria pass.

## 17. Documentation Basis

- OpenAI currently exposes both Chat Completions and Responses endpoints, while recommending Responses for its advanced reasoning and tool workflows. This design intentionally selects the smaller Chat Completions compatibility baseline for cross-provider support: <https://developers.openai.com/api/docs/guides/latest-model>.
- DeepSeek documents use of an OpenAI client with a custom `base_url` and its Chat Completions endpoint: <https://api-docs.deepseek.com/zh-cn/>.
- Provider-specific tool calling and JSON behavior are treated as optional capabilities rather than assumptions: <https://api-docs.deepseek.com/guides/tool_calls> and <https://api-docs.deepseek.com/guides/json_mode/>.
