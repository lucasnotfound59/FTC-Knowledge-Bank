# FTC Knowledge Bank Agent MVP Design

Date: 2026-08-13

Status: Proposed for implementation

Initial users: internal FTC teams 20827 and 16093

## 1. Objective

Build an internal FTC programming assistant that can import an existing FTC repository, identify evidence-backed candidate coding rules, apply approved team rules while explaining or editing code, and verify edits with the repository's Gradle build.

The MVP must complete this workflow:

```text
Import repository
→ detect FTC project and team context
→ extract candidate rules with evidence
→ approve rules
→ Ask / Edit / Run
→ show rule citations and diff
→ verify with Gradle
```

The assistant supports team members but does not decide the robot's mechanical design, hardware design, or complete software architecture. Deployment to a Control Hub is not part of the MVP.

## 2. Confirmed Product Decisions

- The first release is internal to the organization.
- Teams 20827 and 16093 share a common rule set and may define approved team-number-specific overrides.
- FIRST rules and official SDK constraints cannot be overridden by team rules.
- Shared rules are approved by the overall software lead; team-specific rules are approved by that team's software lead.
- Repository-derived patterns are candidates only. They do not affect Agent behavior until approved.
- Agent code edits always occur on a dedicated Git branch in the target robot repository.
- The Agent exposes Ask, Edit, and Run permission modes.
- Any future deployment to physical robot hardware requires an explicit, non-bypassable human confirmation.
- The Agent Core is independent of the IDE and robot control platform. Android Studio is the first client.

## 3. Scope

### 3.1 In scope

- Open a local FTC Android Studio repository.
- Detect the FTC SDK version, Gradle structure, Java source roots, dependencies, likely season, and configured team profile.
- Index Java, Gradle, Markdown, YAML, and selected configuration files.
- Compare patterns across the 20827 and 16093 reference repositories.
- Generate candidate rules with source evidence, confidence, and conflict warnings.
- Review, approve, reject, supersede, and deprecate rules.
- Load official constraints, shared rules, team overrides, and season data using deterministic precedence.
- Answer questions about the current repository with file and rule citations.
- Propose code edits, show a diff, and apply approved changes on a dedicated branch.
- Run an allowlisted Gradle verification task and interpret the result.
- Record approvals, Agent actions, tool executions, and outcomes in an audit log.
- Provide the workflow through an Android Studio tool window.

### 3.2 Out of scope

- Designing a complete robot from mechanical or hardware requirements.
- Autonomous deployment to a Control Hub or Systemcore.
- Automatic merging to a protected branch.
- Installing arbitrary dependencies.
- Supporting arbitrary non-FTC repositories.
- Public accounts, hosted multi-team tenancy, billing, or a knowledge marketplace.
- A standalone IDE, VS Code client, or production Systemcore adapter.
- Treating code frequency as proof of quality.

## 4. Technical Direction

### 4.1 Approaches considered

1. **Kotlin modular monolith inside the Android Studio plugin.** This gives direct IntelliJ and Gradle integration, simple packaging, and a natural fit with Java FTC projects. The risk is accidental coupling to IntelliJ APIs.
2. **TypeScript Agent daemon with a thin Kotlin plugin.** This offers a strong AI tooling ecosystem and an easier future VS Code client, but adds process lifecycle, runtime packaging, RPC, and cross-platform installation work before the first useful workflow exists.
3. **Standalone native core with multiple clients from day one.** This gives the strongest isolation but creates the highest implementation cost and slows validation of the knowledge and approval model.

### 4.2 Selected approach

Use a **Kotlin/JVM modular monolith** for the MVP. Keep all domain, knowledge, Agent, Git, and Gradle logic in modules that do not import IntelliJ APIs. The Android Studio plugin depends on these modules through an `AgentService` interface.

This preserves an IDE-independent core without introducing a local daemon prematurely. A later transport module can expose the same service through JSON-RPC for VS Code or a standalone IDE.

The initial model integration uses a provider interface. The first concrete provider will use the OpenAI Responses API, with the API key stored through the IDE's secure credential facility rather than in the repository. Tests use a deterministic fake provider and do not require network access.

## 5. Repository Structure

```text
FTC-Knowledge-Bank/
├── apps/
│   └── android-studio-plugin/       # IntelliJ UI and lifecycle only
├── modules/
│   ├── domain/                      # Rule, evidence, team, task, audit models
│   ├── knowledge/                   # File store, validation, precedence, retrieval
│   ├── repository-analysis/         # FTC detection and candidate extraction
│   ├── agent-runtime/               # Ask/Edit/Run orchestration and provider API
│   ├── tooling-git/                 # Branch, diff and repository operations
│   ├── tooling-gradle/              # Allowlisted builds and result parsing
│   └── platform-legacy-ftc/         # Current FTC SDK project conventions
├── knowledge/
│   ├── official/                    # Curated FIRST facts and immutable constraints
│   ├── shared/                      # Approved shared rules and guidance
│   └── teams/
│       ├── 20827/
│       └── 16093/
├── fixtures/                        # Small synthetic FTC repositories for tests
├── docs/
└── todolist.md
```

Build tooling will use a Gradle Kotlin multi-project build. The plugin module may use IntelliJ Platform APIs; all other modules must remain usable from tests or a future command-line/RPC adapter.

## 6. Knowledge Model

### 6.1 Storage format

Use version-controlled YAML for machine-enforced rules and Markdown for explanatory knowledge. YAML is chosen because approval metadata and applicability must be deterministic and reviewable in Git. Markdown pages may reference rule IDs but cannot silently introduce enforceable behavior.

An approved rule contains:

- stable rule ID;
- title and concise instruction;
- status: `candidate`, `approved`, `deprecated`, or `rejected`;
- authority: `official`, `shared`, or `team`;
- applicable team numbers and seasons;
- source evidence with repository, commit, file, and line or symbol;
- rationale;
- approver and approval timestamp when approved;
- superseded rule ID when applicable;
- positive and negative code examples where useful.

### 6.2 Precedence

Rules are resolved in this order:

```text
FIRST official constraint
> approved team-number override
> approved shared rule
> season configuration value
```

Season configuration is data rather than policy. It may specialize values such as hardware names or PID constants but cannot weaken an official or approved safety rule. A team override may replace a shared coding choice but cannot replace an official constraint.

If two applicable approved rules at the same authority conflict, the Policy Engine stops the affected Edit or Run action and asks a software lead to resolve the conflict. It must not guess.

### 6.3 Candidate extraction

Candidate extraction uses static repository evidence plus model-assisted summarization:

1. Detect project layout, dependencies, packages, inheritance, annotations, hardware access, commands, and test/tuning OpModes.
2. Count occurrences while retaining exact file and symbol evidence.
3. Compare both reference repositories.
4. Flag duplicated dependencies, commented-out files, copied vendor code, inconsistent naming, and stale APIs as possible quality problems.
5. Ask the model to express candidate rules from the structured evidence.
6. Validate that every candidate cites real evidence before presenting it for approval.

The system never promotes a candidate automatically, regardless of confidence.

## 7. Agent Runtime

### 7.1 Permission modes

| Mode | Allowed behavior |
| --- | --- |
| Ask | Read indexed files and knowledge; answer with citations; no writes or subprocesses |
| Edit | Everything in Ask; create a branch; propose and apply a reviewed patch |
| Run | Everything in Edit; run allowlisted Gradle verification and collect diagnostics |

Mode escalation requires an explicit user action in the plugin. A prompt cannot silently move itself from Ask to Edit or Run.

### 7.2 Agent loop

1. Build context from the active project, selected files, detected team, season, and approved rules.
2. Create a short plan containing only actions allowed by the current mode.
3. Retrieve the minimum necessary code and knowledge.
4. Invoke tools through typed interfaces rather than arbitrary shell access.
5. For edits, generate a patch and show the applicable rule IDs and evidence.
6. Apply the patch only after user confirmation.
7. For Run, execute verification and parse failures into structured diagnostics.
8. Repeat diagnosis and correction within a configured attempt limit.
9. Return the final diff, checks, unresolved risks, and audit record ID.

### 7.3 Tool boundaries

MVP tools are intentionally narrow:

- repository status and metadata;
- bounded file search and read;
- patch application inside the repository;
- branch creation and diff inspection;
- Gradle task discovery from a safe allowlist;
- Gradle build execution with timeout and output limits.

No arbitrary terminal tool is exposed to the model. No network fetch, dependency installation, merge, push, or robot deployment tool is available in the MVP Agent runtime.

## 8. Git Workflow

Before the first edit, the Agent verifies that the target repository is a Git repository and records the starting commit. It then creates a branch named with an approved prefix and a task slug. If unrelated uncommitted changes overlap the proposed files, Edit stops and asks the user to resolve or explicitly preserve them.

The Agent may apply patches and create local commits only after showing the diff. It cannot merge, delete branches, force-push, or rewrite history. The knowledge-bank development repository may be maintained directly on `main` by project-owner instruction; this does not weaken the independent-branch rule for robot repositories operated by the product.

## 9. Android Studio Client

The first client is an Android Studio tool window with four views:

1. **Chat:** Ask/Edit/Run conversation, active mode, context files, and citations.
2. **Rules:** candidate list, evidence viewer, approval controls, conflicts, and history.
3. **Changes:** proposed patch, rule justification, branch name, and confirmation.
4. **Verification:** Gradle output, structured errors, attempted fixes, and final status.

The plugin is a presentation and lifecycle layer. It calls `AgentService`, renders typed events, uses the IDE credential store, and never contains rule resolution or FTC-specific build logic.

## 10. Error Handling and Safety

- **Unsupported repository:** Explain the missing FTC markers and remain in read-only inspection mode.
- **Unknown team or season:** Require explicit selection before team or season rules are applied.
- **Rule conflict:** Block Edit/Run for the affected scope and identify both rules and approvers.
- **Dirty worktree conflict:** Preserve user changes and stop before branch or patch operations that would overlap.
- **Invalid model output:** Reject tool arguments or patches that fail schema, path, or size validation.
- **Build timeout:** Terminate the Gradle process, preserve logs, and report timeout separately from compilation failure.
- **Build failure:** Parse errors, cite source locations, and propose a bounded correction; do not claim success.
- **Credential failure:** Ask the user to configure credentials without logging or displaying the secret.
- **Provider/network failure:** Preserve the current task and allow retry without reapplying completed writes.
- **Attempt exhaustion:** Stop after the configured repair limit and report remaining diagnostics.
- **Physical deployment:** No tool exists in the MVP, making accidental deployment impossible.

Audit records must redact credentials and likely secrets. File reads, prompts, patches, tool calls, approvals, and outcomes are logged locally with timestamps and task IDs.

## 11. Verification Strategy

### 11.1 Unit tests

- rule schema validation;
- authority and applicability resolution;
- shared versus team override precedence;
- same-level conflict detection;
- candidate evidence validation;
- path and patch safety checks;
- Gradle output parsing;
- secret redaction.

### 11.2 Integration tests

- import synthetic FTC repositories for supported and unsupported layouts;
- extract expected candidates from small fixtures;
- verify that duplicated dependencies and stale/commented code are warnings, not standards;
- create an isolated temporary Git repository, branch, apply a patch, and inspect the diff;
- run a fake Gradle wrapper for success, compilation failure, and timeout cases;
- run Agent loops with a deterministic fake model provider.

### 11.3 Reference-repository evaluation

The 20827 and 16093 repositories are evaluation sources, not copied test fixtures. A local evaluation report records detected stacks, candidate rules, conflicts, and false positives without storing private additions or requiring the repositories in the automated test suite.

### 11.4 MVP acceptance criteria

The MVP is accepted when it can:

1. import a supported FTC repository and identify its SDK and major libraries;
2. generate candidate rules that all contain valid evidence;
3. keep candidates inactive until the correct approver approves them;
4. answer a repository question with code and rule citations;
5. create a dedicated branch, show and apply an approved edit;
6. run Gradle verification and accurately distinguish success, compilation failure, and timeout;
7. leave unrelated working-tree changes and protected branches untouched;
8. complete the workflow using the Android Studio tool window.

## 12. Platform Evolution

The current platform module understands the Android FTC SDK and Control Hub project shape. Later adapters may add dependency installation, ADB deployment, and Logcat, with an explicit deployment confirmation gate.

Systemcore support will be a separate adapter using WPILib, GradleRIO, Linux deployment, and WPILog. Because the Systemcore APIs and FTC competition workflow are still evolving, the MVP does not encode Alpha behavior as a permanent contract.

The long-term standalone IDE and automatic adaptation for arbitrary FTC teams remain roadmap items in `todolist.md`. They must reuse the same domain models and Agent service rather than fork the knowledge or policy logic.
