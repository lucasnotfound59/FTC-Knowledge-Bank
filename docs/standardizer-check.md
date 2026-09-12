# 规范器：`ftckb check` 设计文档

## 0. 定位

本仓库核心目的：**规范和统一代码库，让所有 Agent 写出同一标准的代码**。
知识库不只是“给 Agent 看规则的资料库”，而是规范器：

- `resolve` = 告知：把生效规则（OFFICIAL > GLOBAL > LOCAL > SHARED 确定性裁决）交给任何 Agent；
- **`check` = 执法（本设计新增）**：对 Agent/队员的代码改动做**确定性机器检查**，违规即报，
  通过才算符合标准。

闭环：任何 Agent 写码前 `resolve` 拿规则 → 写码 → 提交前 `check` 验证 → 报告附 check 结果。
规则作者在写规则的同时定义“怎么验证它”——检查引擎数据驱动，不硬编码规则内容。

## 1. 命令与契约

```bash
# 默认合并 HEAD→index 与 HEAD→工作区（含非忽略 untracked）的变化
ftckb check <repo-root> --team 20827 --season 2025-2026 --generic-profile [--json]
# 或检查一个补丁/任意 diff（外部 Agent 常用：提交前自检）
ftckb check <repo-root> --team 20827 --season 2025-2026 --generic-profile --diff <file.patch> [--json]
```

JSON 契约（沿用 kernel 风格，退出码扩展）：

```json
{
  "schemaVersion": 2,
  "command": "check",
  "team": "20827", "season": "2025-2026", "profiles": [],
  "ok": false,
  "violations": [
    {"ruleId": "shared.limelight-check-result-validity", "check": "regex-required",
     "path": "TeamCode/src/main/java/example/Vision.java", "line": 42,
     "pattern": "isValid()", "detail": "added line uses getLatestResult without a validity check"}
  ],
  "soft": [
    {"ruleId": "shared.dependency-verify-sync-build-run",
     "note": "改动依赖后请执行 Sync/Build/Run 验证（机器无法验证行为）"}
  ]
}
```

- 退出码：`0` 无硬违规；`1` 存在硬违规（violations 非空）；`2` 知识/仓库加载、校验、上下文或规则冲突失败；`64` 参数错误。
- 确定性：violations 按 ruleId+path+line 排序；soft 按 ruleId 排序；同输入同输出。
- 判定范围：路径 checks 检查所有触及路径（含删除、只删行、空文件、重命名前后路径）；regex checks 只检查新增行（不报全库历史债）。`--diff` 使用同样的路径/新增行语义；非空坏补丁报错，不静默通过。
- 默认同时检查 HEAD→index 和 HEAD→工作区并去重，避免暂存违规仅在工作区撤销后漏检；仅存在于 index 的行号可能与当前文件不同，应结合 staged diff 阅读。
- `check` 使用与 `resolve` 完全相同的生效规则集（含冲突检测），保证“告知什么就执法什么”。

## 2. 检查类型库（v1，数据驱动）

规则 YAML 新增可选 `checks:` 数组（schema v3/v4；不写 checks 的规则视为仅告知/软提示）：

| kind | 语义 | 判定 |
| --- | --- | --- |
| `path-forbidden` | 禁止改动命中路径 | diff 触及 `pattern` 匹配的路径 → violation |
| `path-required` | 改动必须包含命中路径 | diff 未触及任何匹配路径 → violation（本身没有“改 X 才触发”的条件） |
| `regex-required` | 新增行必须含模式 | 匹配 `appliesTo` 路径的已加行中没有任何一行命中 `pattern` → violation |
| `regex-forbidden` | 新增行不得含模式 | 已加行命中 `pattern` → violation（行号=首次命中） |

字段：`kind`、`pattern`（glob 或正则，见下）、`appliesTo`（可选 glob，限定路径）、`note`（人类可读说明）。
`path-*` 的 pattern 是 glob；`regex-*` 的 pattern 是 Java 正则，`appliesTo` 是路径 glob。

`path-required` 本身没有“改 X 才触发”的条件字段；它要求本次变化集合包含匹配路径。`regex-required` 在匹配文件的新增行集合中寻找模式，并非 Java 控制流分析。resolve JSON 的 kind 使用下划线形式，check 输出违规种类使用连字符形式。

YAML 示例：

```yaml
  - id: shared.limelight-check-result-validity
    # ...既有字段...
    checks:
      - kind: regex-required
        appliesTo: "**/*.java"
        pattern: "\\.isValid\\(\\)|getLatestResult\\(\\).*\\.isValid\\(\\)"
        note: "新增的 Limelight 结果读取必须有有效性检查"
  - id: official.keep-customizations-in-teamcode
    checks:
      - kind: path-forbidden
        pattern: "build.common.gradle"
        note: "SDK 保留 build.common.gradle，定制放 TeamCode/build.gradle"
      - kind: path-forbidden
        pattern: "build.dependencies.gradle"
```

规则校验器同步扩展：`checks` 字段的 kind 必须合法、pattern 必须可编译、note 非空；
非法 checks 让 `validate` 失败（规则作者写错会立刻被发现）。

## 3. 规则的首批硬检查覆盖（v1）

硬检查（可机判，写进 checks）：

| 规则 | 检查 |
| --- | --- |
| official.keep-customizations-in-teamcode | path-forbidden build.common.gradle / build.dependencies.gradle |
| shared.ftc-sdk-pin-release | regex-forbidden 依赖行含 `+`/`SNAPSHOT`（RobotCore/Hardware/Inspection） |
| shared.ftc-sdk-preserve-build-tooling | path-forbidden gradle/wrapper/*、gradlew、gradlew.bat |
| shared.dashboard-pin-stable-dependency | regex-forbidden dashboard 依赖行含 `+`/`SNAPSHOT` |
| shared.limelight-check-result-validity | regex-required 新增结果读取必须带有效性检查 |
| shared.limelight-enforce-freshness-policy | regex-required 新增结果使用必须带 freshness 检查 |

软提示（行为/结构类，机器无法验证，check 输出 soft）：

dependency-verify-sync-build-run、ftc-sdk-separate-toolchain-versions、
gobilda 四条（SKU/档位/伺服/PID）、limelight-back-up-before-os-update、limelight-configure-camera-pose、
limelight-synchronize-pipeline-dependent-reads、pedro 三条（坐标转换/定位先行/实机调参）。

诚实声明：机器只对“能从 diff 文本确定性判定”的事项执法；行为类规则一律走 soft + 人工确认，不假装全能。

已知误报范围：当前两条 Limelight regex-required 的 `appliesTo` 均为 `**/*.java`，并未检测新增行是否真的使用 Limelight；普通 Java 新增行也可能被拦住。本次接入更新没有擅自改动这些已审批规则。维护者应确认后缩小触发条件或转为 soft，Agent 不得硬塞无意义调用来过检查。

## 4. 分阶段计划（M1–M4 已交付，M5 即文档合并）

> 状态：M1 schema v3 + checks 模型 ✅；M2 检查引擎 + `ftckb check` + 离线测试 ✅；
> M3 硬检查规则落地（当前六条生效规则含 checks）+ 正反例冒烟 ✅；M4 standardizer 模块（CLI 与 AS 插件共用）+
> 插件 Edit 后自动检查 + `scripts/check-gate.sh` CI 门禁 ✅；已合并 main。

- M1 schema v3 + 领域模型：`Checks` 模型、RuleYamlCodec/RuleValidator 扩展、resolve --json 增量输出
  `checks`（契约只增不改）；全部既有测试绿。
- M2 检查引擎 + `ftckb check`：diff 解析（jgit 默认 + `--diff` 补丁）、四类检查、JSON 契约、退出码、
  确定性排序；离线单测覆盖四类检查与边界（空 diff、坏补丁、冲突规则）。
- M3 覆盖落地：为硬检查规则写 checks + 负例/正例 fixture；`validate` 全绿；
  真实仓库上 `check` 冒烟（对一份违规 diff 与一份合规 diff）。
- M4 接入面：CI 示例（PR gate 脚本）、AS 插件 Edit 后自动 check 并在 diff viewer 标注、
  `eval` 增加 check 场景、README/AGENTS.md 更新“规范器”定位。
- M5 文档合并：`docs/standardizer-check.md`（本文件）+ kernel-contract.md 契约章节 + 推送合并。

## 5. 边界

- kernel JSON v2 返回 normalized `profiles`；resolve/check 必须选择同一 profile（generic 是显式空集）。两者不需要 API key。
- YAML v4 的 `reviewTriggers` 是审阅触发元数据，不是新增硬检查类型，也不能替代 checks 或人工审阅。
- 来源 authority 不等于 policyLevel；official 来源最高，其余按 global/local/shared 裁决。candidate、队号/赛季/profile 不匹配不执法。

- check 不执行任何代码/构建/测试；只做静态文本判定。
- 只对 diff 判定；全库历史债不在 v1 范围（后续可加 `--full` 扫描）。
- 规则冲突时 check 与 resolve 行为一致：先报冲突（退出码 2），不猜测裁决。
