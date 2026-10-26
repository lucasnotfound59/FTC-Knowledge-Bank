# Candidate Review Export Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use subagent-driven-development or executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking. 本轮由 root 已指定的 ftckb_command_rules 实现，本设计作者独立复核；不要另启重复 owner。

**Goal:** 为 candidates 新增可选完整只读审阅导出，便于 Agent 交付正文、适用范围及来源快照供负责人审批。

**Architecture:** 仅 `--details` 进入新路径，用一次 KnowledgeWorkspace.snapshot 获取全库校验、候选与来源摘要。复用 DashboardJson.rule 的完整结构；JSON 与人类卡片共享同一数据对象，旧 candidates 路径保持原样。

**Tech Stack:** Kotlin/JVM 21、现有 Jackson、KnowledgeWorkspace、JUnit Jupiter；不新增依赖。

## Global Constraints

- 设计依据：`docs/superpowers/specs/2026-10-23-candidate-review-export-design.md`。
- `candidates <root> --details [--id RULE_ID] [--json]`；新 JSON 为 `format=ftckb-candidate-review`、`formatVersion=1`，不是 kernel JSON v2。
- 未传 --details 的默认输出、错误路径、重复 --json 行为逐字节不变；帮助可扩充。
- 真实知识仍为 52 条、42 approved、10 candidate；不修改任何候选/审批状态，不 commit/push。
- 不改 KernelJson、DashboardJson、ApprovalPolicy、KnowledgeWorkspace 或 installer；不新增网络/模型调用、身份或审批服务。
- 所有构建/Gradle 测试先与 doctor 协调，串行运行；docs 共享文件写前由 root 协调。
- 先复用并确认实际 Dashboard，再运行必要 kernel；平台 QA 的 resolve/check 固定 20827 / 2025-2026 / generic / normal。
- 不触碰 BioBuzz、预存 untracked 文件、临时审批卡或连接凭据。

## Owner 文件表

| 操作 | 路径 | 职责 |
| --- | --- | --- |
| Create | `apps/knowledge-cli/src/main/kotlin/org/ftckb/cli/CandidateReviewExport.kt` | 详细模式参数、快照投影、渲染、输出上限、错误 |
| Modify | `apps/knowledge-cli/src/main/kotlin/org/ftckb/cli/ApprovalCommand.kt` | 仅详细分派及 candidates 帮助 |
| Modify | `apps/knowledge-cli/src/main/kotlin/org/ftckb/cli/Main.kt` | 顶层 candidates 帮助 |
| Create | `apps/knowledge-cli/src/test/kotlin/org/ftckb/cli/CandidateReviewExportTest.kt` | 新功能及旧入口兼容验收 |
| Modify, root 协调 | `docs/cli-agent.md` | 候选命令使用、独立导出格式边界 |
| Modify, root 协调 | `docs/candidate-extraction.md` | 审阅到现有审批的工作流与完整字段说明 |

## Task 1: 实现 opt-in 完整导出与关键行为验收

**Consumes:** `KnowledgeWorkspace(root:Path).snapshot():KnowledgeSnapshot`、`DashboardJson.rule(rule:KnowledgeRule):Map<String,Any?>`。

**Produces:** `internal fun runCandidateReviewExport(args:List<String>,out:PrintStream):Int`。参数包含知识根目录及 --details，返回 0/2/64。内部统一先构建完整输出字符串再 print，含换行的 UTF-8 上限为 `1024*1024`。

- [ ] **先保存兼容基线并加测试。** 新测试使用 @TempDir 自造 YAML，不依赖真实候选状态。记录原 candidates text/json 的完整字符串、未知选项与缺根错误、重复 --json 输出；新增 --details 时这些断言仍通过。详细模式还未实现时，新完整导出断言应失败。

```kotlin
val output=ByteArrayOutputStream()
val code=runCli(listOf("candidates",knowledge.toString(),"--details","--json"),PrintStream(output))
val json=DashboardJson.mapper.readTree(output.toString(Charsets.UTF_8))
assertEquals(0,code)
assertEquals("ftckb-candidate-review",json["format"].asText())
assertEquals(1,json["formatVersion"].asInt())
assertFalse(json.has("schemaVersion"))
assertTrue(json["readOnly"].asBoolean())
assertFalse(json["contextEvaluated"].asBoolean())
assertEquals("candidate",json["candidates"][0]["rule"]["status"].asText())
assertTrue(json["candidates"][0]["rule"]["approval"].isNull)
```

- [ ] **添加隔离参数解析。** 在旧 runCandidates 开头、原有加载逻辑之前增加下列唯一分派；其余旧逻辑不动。详细解析要求首位非空根目录且不以 `--` 开头，随后严格遍历 --details/--json/--id，重复/未知/缺值均 usage/64。ID 使用 `^[a-z0-9]+(?:[.-][a-z0-9]+)*$`。错误 JSON 模式在解析前通过 `args.contains("--json")` 确定。

```kotlin
if ("--details" in args) return runCandidateReviewExport(args,out)
```

- [ ] **新增同快照投影。** 调用 snapshot 一次；无效知识立即失败。按 candidate 状态与可选 id 筛选、id 排序。`--id` 先在所有规则中查找再验证状态，使 not-found 与 not-candidate 区分。成功对象采用以下字段与投影，不再次读取来源文件：

```kotlin
val cards=selected.sortedBy { it.id }.map { rule ->
    val source=snapshot.sources[rule.id]
    linkedMapOf<String,Any?>(
        "rule" to DashboardJson.rule(rule),
        "source" to source?.let { linkedMapOf("path" to it.path,"fileDigest" to it.fileDigest) }
    )
}
val result=linkedMapOf<String,Any?>(
    "format" to "ftckb-candidate-review","formatVersion" to 1,
    "command" to "candidates","ok" to true,"readOnly" to true,"contextEvaluated" to false,
    "knowledge" to linkedMapOf("root" to snapshot.root.toString(),"version" to snapshot.version),
    "candidates" to cards
)
```

- [ ] **实现两种渲染与一致错误。** JSON 使用单行 mapper.writeValueAsString(result)。人类模式输出设计中原文中文提示、knowledge root/version/数量，然后逐条输出同一 cards 元素的 Jackson 缩进 JSON。不得只给摘要或隐藏空数组、null、checks/evidence/examples。错误对象固定 format/formatVersion/command/ok=false/error，不含 candidates；invalid-knowledge 可附排序后的原 violations/loadErrors。异常仅输出固定失败说明，不打印堆栈或 YAML 正文。

- [ ] **在统一输出边界限制体积。** 输出前构造含换行字符串并检查 UTF-8 字节数。超过 1 MiB 返回 output-too-large/2，消息明确用 --id 缩小范围；尚未输出任何成功字节。错误对象本身由固定文案组成，不回显大型用户输入。不得截断正文。

```kotlin
val rendered=body+"\n"
if (rendered.toByteArray(Charsets.UTF_8).size>1024*1024) {
    return failure("output-too-large","Candidate review exceeds 1 MiB; select one rule with --id",2)
}
out.print(rendered)
return 0
```

这里的 `failure(code:String,message:String,exit:Int):Int` 作为新文件内部统一错误函数，依据已保存 jsonMode 输出上述错误包或单条文本；自身不走成功体积递归。

- [ ] **完成以下行为测试，分别命名并断言具体结果。** fixture 包括一条完整 v4 shared 候选、带全部 Web/Git 可选 evidence 字段与正反例/触发器/全范围，一条有限 team/season/ftclib-command 候选，一条 v1 候选，以及一条已批准规则。所有 fixture 仅在临时知识库内。

| 用例 | 关键断言 |
| --- | --- |
| 完整语义导出 | rule 字段集合与设计一致；中文长正文、rationale、supersedes、正反例、两类 evidence 可选字段、checks、triggers 无丢失；不隐去 null/空数组 |
| 范围与状态 | 全范围仍为 []；有限 team/season 原值；声明 ftclib-command 不擅自增加 command-based；candidate/approval=null 明确 |
| 来源与版本 | 与同一实际 KnowledgeWorkspace.snapshot 的 version/source path/fileDigest 相等；不输出 ruleYaml；v1 或 flow mapping source=null 时仍有完整 rule |
| 选择 | --id 只返回所选；缺失 id 为 not-found/2；approved id 为 not-candidate/2；没有 candidate 的合法库成功 candidates=[] |
| 无效知识 | 坏 YAML、重复 ID、candidate 带 approval 均 invalid-knowledge/2；根不存在/symlink 路径为 load-error/2；JSON 可完整解析且不带 candidates |
| 解析 | 空根、空/缺 id、重复 details/json/id、未知 flag、额外位置参数、混入 --help 为 usage/64；--json 不同位置均保持完整错误 JSON |
| 输出上限 | 用中文大正文触发 >1 MiB；只返回 error、不含半张卡；--id 选小候选成功；单条过大仍失败；text/json 两种都验证 |
| 确定性与只读 | 相同文件连续两次 stdout 完全相等；源文件/目录清单与字节前后相同；注入临时 auditDirectory 不产生 events/reviews；无真实 approve/reject |
| 人类卡片 | 有中文未批准/未裁决提示，source=null 解释、范围语义、knowledge version；每条完整卡片与 JSON mode 对应，不含生成的审批命令或身份 |
| 兼容 | 无 --details 的旧 text/json/错误逐字节相同，包括重复 --json；现有 approve/reject 测试保持通过 |

- [ ] **由 doctor 运行受影响组合。** 先在新增测试红灯后实现，再运行下面组合；只在变化或失败需要时重跑，不跑全仓库回归。

```bash
./gradlew :apps:knowledge-cli:test \
  --tests org.ftckb.cli.CandidateReviewExportTest \
  --tests org.ftckb.cli.ApprovalAcceptanceTest \
  --tests org.ftckb.cli.KernelJsonAcceptanceTest \
  :apps:knowledge-cli:installDist
```

预期所选测试全部通过、安装成功。旧 CliDocumentationAcceptanceTest 的 48 条断言已另交 doctor 修正；此功能不得偷偷修该 owner 文件或复用先前 PASS 当本次结果。

## Task 2: 帮助、文档与真实只读演示

**Consumes:** Task 1 的详细入口及测试过的独立格式。

**Produces:** 能被用户发现的命令说明与可复核的实际 stdout；没有审批或发布。

- [ ] **更新两处 help。** candidates 单命令与顶层命令展示 `[--details [--id RULE_ID]] [--json]`；说明默认简表、详细模式完整审阅和 --id 仅限 details。帮助的实际输出加到新增测试断言。

- [ ] **经 root 协调后更新两个候选文档段。** 写入设计中的四条命令，明确 formatVersion 与 kernel schemaVersion 不互换；导出不 resolve、不批准、不验证证据真伪；source=null 与 snapshot 路径拒绝；1 MiB 上限；真实审批须明确姓名、角色、最终正文与范围。原版 candidates JSON 错误仍为文本，不错误宣称全候选命令已具 kernel 契约。保留其他 owner 既有内容。

- [ ] **重新确认已复用 Dashboard 的知识路径与52/42/10快照。** 连接文件仅私有读取；不在输出、仓库或报告中打印 token。若其他人合法改规则，应报告真实变化，不硬写旧数量。

- [ ] **使用安装 CLI 执行三条只读演示并捕获实际结果。** root 选择私有临时输出路径；不要向仓库添加演示文件。

```bash
apps/knowledge-cli/build/install/ftckb/bin/ftckb candidates knowledge --json
apps/knowledge-cli/build/install/ftckb/bin/ftckb candidates knowledge --details --json
apps/knowledge-cli/build/install/ftckb/bin/ftckb candidates knowledge --details --id shared.command-scheduler-lifecycle
```

预期旧简表仍 10 候选且 shape 未变；新导出 10 张完整候选卡；单条显示最终正文、candidate、command-based、空 teams/seasons、conditional_soft、source/hash。前后确认真实 YAML 字节不变。新增候选若仍未审批则不声称已生效。

- [ ] **doctor 对本功能最终允许文件 diff 运行 normal check 并报告每项 soft。** 使用相同 QA 上下文先 resolve，check 用显式补丁仅含本 owner 文件；补丁在私有临时目录生成，包含新增文件，排除其他 owner 与既存 untracked。

```bash
apps/knowledge-cli/build/install/ftckb/bin/ftckb resolve knowledge --team 20827 --season 2025-2026 --generic-profile --work-mode normal --json
apps/knowledge-cli/build/install/ftckb/bin/ftckb check . --knowledge knowledge --team 20827 --season 2025-2026 --generic-profile --work-mode normal --diff /private/tmp/ftckb-candidate-review-export.patch --json
```

- [ ] **独立复核与移交。** reviewer 检查最终 diff、实际测试摘要、三条 stdout、只读对比与 soft 报告，重点检查无暗中审批、无源摘要二次读取、无默认格式扩大。root 接受后纳入本地交付；本计划不授权 commit/push、真实批准、RC/DS 或真机部署。

## 计划自查

接口、选项、错误码、字段与设计一致；每个产品文件有唯一 owner；默认兼容与 v1/source=null 已包含验证；输出界限没有被夸大为输入/内存界限；没有改审批权限或把导出当 resolve；未提前宣称测试通过。
