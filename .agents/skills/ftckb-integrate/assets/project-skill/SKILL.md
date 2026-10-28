---
name: ftc-knowledge-bank
description: 在已接入 FTC Knowledge Bank 的项目中编写、修改或检查 FTC 代码时，使用项目配置调用确定性的规则裁决和 diff 检查。
---

# FTC 项目规则工作流

从 Git 根目录读取 `.ftckb/project.yaml`（JSON 语法的 YAML 1.2）。它提供 team、season、固定 source.commit 和协议版本；v2 还提供明确的 profiles。不可从 README 示例、日期或其他队伍猜测。

v2 的 `schemaVersion`、`kernelSchemaVersion`、`integrationVersion` 都为 2，`profiles` 必须是无重复的字符串数组。支持 `rookiebot`、`simple-opmode`、`command-based`、`ftclib-command`；`profiles:[]` 表示用户明确选择 generic，不等同于未配置。`rookiebot` 隐含 `simple-opmode`，`ftclib-command` 隐含 `command-based`，隐含后 simple/command 两类互斥。配置保留原选项，由脚本规范化并传给内核，不由 Agent 自行裁决。

## 命令架构指南发现

只有当前用户/配置已明确选择 `command-based` 或 `ftclib-command`，且任务涉及 Command、Subsystem、scheduler 或生命周期职责时，先读取固定知识库副本中的 `tools/FTC-Knowledge-Bank/knowledge/guides/architecture/command-based.md`，再结合本次 resolve 的实际 `activeRules` 工作。自有或 FTCLib 启发的轻量实现使用用户明确选定的 `command-based`，不因此要求安装 FTCLib；明确采用 FTCLib command 运行时才选择 `ftclib-command`。不从类名、现有依赖或指南内容自动更改 profile。

指南是教学与证据入口，不能把 candidate 或因赛季/profile排除的规则说成已生效；状态和适用范围以当前固定知识、真实上下文的 resolve 为准。旧 pin 若没有该指南，明确报告缺失并遵循显式版本升级流程，不读取浮动 main 冒充当前输入、不自动改 pin。新模板只随未来明确安装/升级生成，不直接覆写已接入机器人项目的 Skill 或绕过 managedFiles hash。

## 首次接入与验收边界

首次接入使用已审阅安装器 `--prepare-only`，再按返回 `nextSteps.cwd/argv/env` 做 CLI `--accept --prepared-context-sha256 HASH`。`stage:prepared` 与准备摘要不表示安装验收、代码或机器人通过。默认不查询或启动 Dashboard，不需要连接文件或浏览器声明。

accept 不构建，每个内核阶段前后复核配置/pin/managed/knowledge/CLI/audit准备输入；漂移或审计存储失败停止后续阶段，保留实际 JSON/exit 与脱敏诊断。普通 TeamCode 修改不会单独使摘要过期；硬违规可表示安装有效但当前 diff 未通过。人明确要求附加页面核验时，旧 `--connection-file` 与 `--browser-confirmed` 必须成对提供，失败不能伪装通过。

## 开始修改前

记录 `git status --short` 和已有 staged/unstaged/untracked 修改，区分用户已有改动与本次变更。直接使用当前分支；不要求新建分支，也不因文件已有改动而一律拒绝协作，但不得覆盖/撤销用户修改。

日常顺序：**doctor → work resolve → 修改 → work check --receipt ID**。

先用只读诊断检查实际接入状态（Python 3.10+）：

```bash
python3 tools/FTC-Knowledge-Bank/.agents/skills/ftckb-integrate/scripts/project.py doctor --project .
```

doctor 返回独立 `doctorSchemaVersion:1` JSON；0 表示必需接入检查通过，1 表示存在问题或证据不足，64 表示调用错误。它不构建、不联网、不启动服务、不运行规则内核、不修改配置/pin，也不执行目标脚本；`nextSteps` 中的 `cwd`/`argv` 只是建议。逐项处理 failed/blocked/unknown，不能把接入检查当规则、代码或真机通过；静态 receipt 也不能证明构建来源或可运行。

若 submodule 尚未初始化，或旧 pin 没有 doctor，不能调用不存在的脚本。先取得独立、已审阅的 Knowledge Bank checkout，再运行其绝对入口：

```bash
python3 /path/to/reviewed-FTC-Knowledge-Bank/.agents/skills/ftckb-integrate/scripts/project.py doctor --project /path/to/FTC-project
```

该外部入口只诊断，不改变目标 pin；经元数据核验后才按建议初始化。缺配置时先明确真实 team/season/profile 并走接入流程；不要用示例默认值。后续 resolve/check 仍使用项目固定源码，升级是独立的显式选择。

AI 不自动启动、探测、连接、截图或浏览器确认 Dashboard。人需要查看规则、已有审计或人工复核时再打开；CLI 独立写审计。人类启动入口为已审阅 checkout 的 `dashboard_runtime.py` helper，显式传旧 pin 的完整 distribution、实际 knowledge 与同一审计环境，不改 pin。仅 Python 3.10+ POSIX 支持保护入口，细节见 `docs/dashboard.md`。默认 doctor/prepare 的 nextSteps 只引导 CLI；缺可选管理台不要求升级。

只有人明确请求才运行独立 `project.py dashboard verify --project . --connection-file PRIVATE_JSON`。私有 JSON 仅含真实完整链接的 `url`，不把 token 放 argv/仓库；API 匹配不代替浏览器观察、不证明服务 binary pin，unknown 不当作通过。正常 AI 工作无需这一步。

使用同一 Python 环境的 `jsonschema>=4.18,<5`，运行：

```bash
python3 tools/FTC-Knowledge-Bank/.agents/skills/ftckb-integrate/scripts/project.py work resolve --project .
```

work 入口检查配置、submodule URL/HEAD/gitlink、固定源码能力声明、托管文件、JSON schema 和退出码；要求已有匹配的 CLI 发行产物和 build receipt，不自动构建或联网。缺少时先显式准备，再做 CLI 验收/resolve。旧无收据入口保留原有构建行为。v2 resolve/check 自动添加重复 `--profile` flags 或 `--generic-profile`，并核对内核 schemaVersion 与配置 kernelSchemaVersion、返回 profiles 与配置规范化结果一致。使用 JDK 21+ 运行，首次构建可能需要联网；不需要模型 API key，也不使用机器人的 Android SDK。Windows 可用 `py -3` 替换 `python3`。

work 输出是独立 `workReceiptSchemaVersion:1` envelope，实际内核原文在 `kernel.output`，其 schemaVersion 仍为配置版本。只依据 `kernel.output.activeRules` 裁决结果工作；`conflicts` 非空、schemaVersion 不支持、脚本报错或退出非 0 时先解决，不继续假装规则已经载入。按规则的实际适用范围编写代码，不强迫所有项目迁移到某个队伍的风格。

## 显式关联一次工作

固定源码已有 doctor 但尚无 work 入口时，用已审阅新 checkout 的绝对入口 `python3 /path/to/reviewed-FTC-Knowledge-Bank/.agents/skills/ftckb-integrate/scripts/project.py work resolve --project /path/to/FTC-project`；保留目标 pin，后续沿返回的 nextSteps 使用同一外部入口，不自动升级。

成功 work resolve 返回 UUID 收据和完整 `nextSteps.cwd/argv`，直接使用返回的命令；下方 `RETURNED_UUID` 仅占位，不能照抄或猜测。check 必须显式传该 ID，不自动选择“最近一次”。配置原字节（含格式）、实际 knowledgePath、固定 commit、team/season/profile 或实际 `.yaml/.yml` 输入变化时重新 resolve；包括 Git 忽略的规则。普通机器人代码变更、提交与重复 check 不使收据过期，不设任意时间 TTL。

收据保存在实际 Git 元数据的 `ftckb/receipts/`，不进入工作区 diff；若自定义 gitDir 在工作区内且不位于 `.git` 子树，明确拒绝，不改 ignore 或迁移数据，不消费/改写。mode 省略仍为 normal；test/dev 必须明确传且与 resolve 匹配，不能从收据静默继承。dev check 仍须本次 `--diff FILE`。CLI 的内核输出保留在 envelope，exit1 仍是实际硬违规，exit2 是收据/上下文/接入等错误，exit64 是新入口参数错误；上下文拒绝前若已执行内核，会保留该实际结果，不冒充关联通过。

work 收到内核 stderr 时，在独立 envelope 返回安全分类的 `diagnostics`/`auditStatus`：审计存储失败为 incomplete，输入版本或其它诊断为 unknown，不暴露原始敏感内容；无诊断不添加这些字段，也不证明审计完整。用户摘要必须报告审计不完整/未知及诊断项。work 保留真实 kernel JSON/退出码和既有收据创建/关联语义；收据绑定实际执行不代表独立审计完整。CLI accept 对已报告的审计存储失败仍停止后续验收阶段。

收据只是本地关联证据，不是签名、身份、审批、语义或真机保证；同一用户可改写并重算摘要。前后快照不保证抵御同权限的中途更改再恢复（ABA）。它不猜配或改写 audit events/reviews。旧 `project.py resolve/check` 默认输出/退出码不变，仍可用于兼容，但不能声称带有工作收据。

## 工作模式：normal / test / dev

`project.py work resolve|check`、旧兼容入口与底层 `ftckb` 支持临时 `--work-mode normal|test|dev`，省略等同 `normal`。模式只对当前任务生效，不写入 `.ftckb/project.yaml`，也不改变固定版本、team、season 或 profiles。**只有用户明确说明当前任务是测试代码或 dev 代码时才选择 test/dev；不得根据文件名、路径、分支、依赖或代码外观推断，也不得自行把默认模式改成 test/dev。** 交付时必须写明所用模式、覆盖的文件范围和被排除的项目改动。

- **test**：用户明确指定当前编写的是测试代码时，测试代码优先实现用户的测试目的，不因正式命令架构而被迫使用 `CommandBase`，也不需要先提交编码 plan。裁决仍通过 `resolve` 取得：`global.command-responsibilities` 与 `shared.ftclib-command-candidate` 进入 `excludedRules`，原因 `work-mode-test`；命令实时输入、安全清理、`global.test-utility-layout` 的 tests/utils 位置与目标 TeamCode 不使用 JUnit 等其余规则和硬检查全部继续生效。测试代码是逐任务的明确指定，不是全局豁免，不能当作绕过其他规则的理由。若工作区同时有非测试改动，`check --work-mode test` **必须**传仅覆盖本次测试代码的 `--diff FILE`；对其余改动另跑 normal 检查并披露两次检查各自的范围，不得用 test 模式放松整个脏工作区。
- **dev**：用户明确指定当前部分是 dev 代码（新架构或新 system 的尝试）时，`check` 必须同时提供本次改动的 `--diff FILE`，只评估该补丁；每个 hard 命中都会转成带 ruleId、check、path、line、detail 的 soft 提醒，没有加载/校验/冲突/调用错误时退出码为 0。原有的 soft 保留。必须逐项向用户报告降级提醒，并说明该 diff 覆盖哪些文件、排除了哪些项目改动；用户明确表示可以忽略后才能继续。dev 模式不是“符合正式规则”，也不是机器人验证，不得这样描述。

无效知识、同层级规则冲突、错误 profile、无法解析的补丁仍按退出码 2/64 失败，不因 test/dev 变成通过。

## 修改后交付前

```bash
python3 tools/FTC-Knowledge-Bank/.agents/skills/ftckb-integrate/scripts/project.py work check --project . --receipt RETURNED_UUID
```

工作模式经 `--work-mode` 转发，例如 `check --project . --diff change.patch --work-mode dev` 或 `resolve --project . --work-mode test`；默认调用不传该参数，与既有输出兼容。`--work-mode dev` 缺少 `--diff` 时脚本拒绝执行。

- 退出 0：硬检查通过；逐项评估并如实报告相关 soft 提醒。dev 模式下退出 0 仅表示没有未降级的 hard 命中，仍必须逐项报告被降级的提醒。
- 退出 1：硬违规，修复后重跑。已有违规属于用户基线时明确说明，不将其冒充本次引入，也不声称最终检查通过。
- 退出 2/64：加载、配置、规则冲突或调用错误，不等同于代码通过。

默认 check 覆盖 staged、unstaged 和非忽略 untracked 变更。路径规则看触及路径（含删除/重命名）。regex 只看新增行：`regex-required` 和 review trigger 逐条匹配，`regex-forbidden` 先逐条匹配，再可匹配连续新增行块。`--diff /path/to/change.patch` 只用于明确范围的检查，并披露排除的项目改动；交付仍运行默认 normal 全工作区 check 并如实报告其结果，不靠缩小 diff 隐藏违规。

`build.dependencies.gradle` 的任何触及（含删除/重命名）会命中 `global.vendor-documented-build-dependencies` 条件式 soft：必须打开依赖厂商的第一方官方文档或官方仓库固定 commit，核对 repository/dependency、精确版本，并逐项解释实际 diff；Gradle sync/build 结果与部署/真机验证分开报告。规则引擎不联网鉴别来源、不验证证据真伪，soft 不是自动放行；缺少第一方证据时撤回或修正改动并告知用户。

硬检查不等于完整语义验证。遇到看似误报的规则，展示 ruleId、适用代码与原因，请维护者调整规则；不要擅改知识库或插入无意义代码来过检查。

报告本次改动、resolve/check 结果、重要 soft/未验证项以及实际执行的构建/测试命令。编译、部署、真机运行分别说明；没有硬件测试就写未验证。

## 维护边界

不修改 submodule 中的规则来绕过检查；升级必须明确指定 tag/commit，并重跑安装和验收。不执行 `git submodule update --remote`，不自动 commit/push，不安装 Git hooks。
安装器的 `--profile NAME` 可重复且与 `--generic-profile` 互斥；新安装必须明确选择。v2 重跑/升级省略选择会保留原 profiles。既有 v1 pin 不自动升级，不补写空 profiles，也不传 v2 flags；显式 v1→v2 升级必须提供 profile/generic。不支持混合版本号、新装 v1 或自动 v2→v1 降级。版本不支持、选择缺失或冲突时停下来询问/解决，不用 README 示例代替用户选择。
此 Skill 依靠当前 Agent 遵循，不会自动拦截所有编辑器或所有成员提交。合并强制门禁需另行配置 CI。
