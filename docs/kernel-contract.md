# ftckb 知识内核机器契约（Kernel JSON Contract）

面向外部 Agent 与脚本的稳定、版本化、确定性接口只有 `validate`、`resolve`、`check`。这些命令不需要 API key，不调用模型或联网核验证据。chat / serve / eval 不是机器契约。

## 1. 获取与版本

```bash
git clone https://github.com/lucasnotfound59/FTC-Knowledge-Bank.git
cd FTC-Knowledge-Bank
./gradlew :apps:knowledge-cli:installDist
# JDK 21+；产物 apps/knowledge-cli/build/install/ftckb/bin/ftckb
```

消费方固定审阅过的完整 commit，不跟踪 main。仓库 V0.9.0、CLI 2.1.0、YAML v4、kernel JSON v2、项目接入协议 v2 是独立版本轴。YAML 解码兼容 v1-v3，不代表 kernel v1 消费方兼容 v2。旧机器模式见 [v1 schema](kernel-contract.v1.schema.json)；当前 [v2 schema](kernel-contract.schema.json)。`workMode` 是逐次调用的 ephemeral 参数，不属于项目配置或长期 profile。

知识总数 48（42 已批准 + 6 候选）；validate 包含候选计数，resolve 的 activeRules 不包含候选。

## 2. 命令与显式 profile

```bash
ftckb validate knowledge --json
ftckb resolve knowledge --team 20827 --season 2025-2026 --generic-profile --json
ftckb check <repo-root> --knowledge knowledge --team 20827 --season 2025-2026 --profile command-based --json
ftckb resolve knowledge --team 20827 --season 2025-2026 --profile ftclib-command --work-mode test --json
ftckb check <repo-root> --knowledge knowledge --team 20827 --season 2025-2026 --profile command-based --diff change.patch --work-mode dev --json
```

上面展示两种 profile，实际同一项目的 resolve/check 必须使用相同选择。

- resolve/check 必填数字 `--team`、`YYYY-YYYY` 格式的 `--season`。
- profile 必须显式选择：`--generic-profile` 表示空集，或可重复 `--profile NAME`。不能混用；重复 generic、缺 profile 值是 usage。
- 支持 rookiebot、simple-opmode、command-based、ftclib-command；rookiebot 隐含 simple-opmode，ftclib-command 隐含 command-based。simple-opmode 与 command-based 互斥。未知名称或不兼容组合是 invalid-context；缺少选择是 context-required。不从依赖猜架构。
- validate 只接受知识根目录和可选 --json，不接受 profile 或 --work-mode。
- `--work-mode normal|test|dev` 是 resolve/check 的逐任务临时参数，省略等同 `normal`；它不写入项目配置、不改变 team/season/profile，也不能从文件名、路径或依赖推断。只有用户明确指定当前任务是测试代码或 dev 代码时才选择 `test`/`dev`。`normal`（含显式传入）的输出、排序和退出码与旧版本逐字节相同。
- `test` 只把强制正式命令架构的 `global.command-responsibilities` 与 `shared.ftclib-command-candidate` 放入 excludedRules，原因 `work-mode-test`（其他不匹配原因仍一并保留）；命令实时输入、安全清理、`global.test-utility-layout` 的 tests/utils 位置与目标 TeamCode 不使用 JUnit 等其余规则和硬检查全部继续生效。
- `test` 的 CLI 不强制 `--diff`；但默认 check 会扫描整个脏工作区。若其中混有非测试改动，Agent 必须以 `--diff FILE` 限定本次测试代码，并对其余改动另跑 normal 检查；两次范围与排除项均需报告，不能用 test 模式放松正式代码。
- `check --work-mode dev` 必须同时提供 `--diff FILE`，否则 usage/64；只评估该补丁，每个 hard 命中连 ruleId/check/path/line/detail 一起转为 soft，`violations` 为空且没有加载/校验/冲突/调用错误时退出 0，原有 soft 保留。dev 不把无效知识、同层级冲突、错误 profile 或坏补丁伪装成通过，也不代表符合正式规则或真机验证。
- check 可选 `--diff FILE`；省略 --knowledge 时使用当前目录的 knowledge。
- --json 存在时机器路径的成功与失败均为单行 JSON，无日志噪音；--help 是独立的人类帮助入口。

| 退出码 | 含义 |
| --- | --- |
| 0 | validate 成功、resolve 无冲突、check 无硬违规（dev 下 hard 已按上述规则降级为 soft） |
| 1 | check 硬违规，violations 非空 |
| 2 | 加载、校验、上下文或冲突失败 |
| 64 | 未知命令、缺失/错误参数等 usage |

## 3. authority 与 policyLevel

`authority` 是来源身份：official / team / shared；`policyLevel` 是适用策略：global / local / shared。有效优先级固定为 **OFFICIAL > GLOBAL > LOCAL > SHARED**。

official 来源必须用 policyLevel=global，但有效层级是 official，永远高于共享来源的 global；team 来源必须是 local，且限定队号。shared 来源可以是 global、local 或 shared；local 必须限定 teams 或 profiles。文件目录和 id 前缀不参与裁决。

只有 approved 且队号、赛季、profile 全部匹配才进入同主题比较。teams/seasons 空集表示该维度不限制；profiles 空集表示无架构要求，非空时要求当前 normalized profiles 包含全部指定值，不是任选其一。同主题最高有效层级并列即冲突，即使 authority 不同也不静默选胜者。其他主题仍可输出 activeRules。

8 条原队伍规则迁移到 global 时只去掉队号限制，保留 2025-2026；2 条 candidate 不转正。当前两队相同赛季/profile 的 active IDs 相同，其他赛季必须重新裁决。

## 4. JSON v2 字段

所有输出含 schemaVersion=2、ok；已识别命令含 command，未知命令 usage 可无 command。显式 `--work-mode test|dev` 时 resolve/check 顶层增加加法字段 `workMode`（值 `test`/`dev`）；默认或显式 `normal` 不新增字段，因此既有消费方的逐字节对拍不受影响。

validate 成功：

```json
{"schemaVersion":2,"command":"validate","ok":true,"ruleCount":48,"violations":[]}
```

resolve（无冲突或有冲突）都有 team、season、normalized profiles、activeRules、excludedRules、overriddenRules、conflicts，不使用 error 字段。

| 字段 | 元素及含义 |
| --- | --- |
| activeRules | id/topic/title/instruction/rationale/status/authority/policyLevel/applicability/evidence/checks/reviewTriggers |
| applicability | teams、seasons、profiles 字符串数组 |
| excludedRules | ruleId、reasons；原因可为 profile/season/status/team，以及 test 工作模式的 work-mode-test，同时保留所有不匹配原因 |
| overriddenRules | ruleId、topic、winnerIds、effectiveLevel；适用但层级较低，effectiveLevel 是最高层级，winnerIds 可是冲突规则 |
| conflicts | topic、effectiveLevel、ruleIds、authorities；最高有效层级并列，该主题没有 active 胜者 |

冲突元素：

```json
{"topic":"same-topic","effectiveLevel":"global","ruleIds":["global.a","global.b"],"authorities":{"global.a":"shared","global.b":"shared"}}
```

resolve 冲突时 ok=false、退出 2；仍保留其他主题 activeRules 和排除/覆盖解释。不要把它当成功。文本模式遇到冲突抑制 active 行。

规则正文可以是中文或英文。evidence 用 type=git 或 web；git 含 repository/commit/file 及可选 symbol/line，web 含 url/title/publisher/accessedAt/section 及可选 version/product/sku。checks 的 kind 在 resolve JSON 使用下划线（path_forbidden 等），YAML 与 check 违规的 check 字段使用连字符（path-forbidden 等）。

检查执行规则是确定的：无 checks、无 `reviewTriggers` 的生效规则始终输出 soft；无 checks、有 `reviewTriggers` 的规则是**条件式 soft**，仅当同一 trigger 的路径和新增行模式匹配时输出 soft；有 checks 的规则保持硬检查。空 `addedLinePatterns` 使 trigger 仅按路径触发；多个 trigger 之间为 OR；单个 trigger 内，路径约束和新增行模式约束必须同时满足。当前 **5 条生效规则带硬检查**。跨赛季 `global.test-utility-layout` 对确定的错误 TeamCode 路径/JUnit 新增返回 violations 与退出码 1：机器人侧 OpMode 的规范路径是 `TeamCode/src/main/java/org/firstinspires/ftc/teamcode/tests/`，复用工具的规范路径是 `TeamCode/src/main/java/org/firstinspires/ftc/teamcode/utils/`。它不替代完整 Agent instruction 对文件语义及 package 的判断，也不禁止 Knowledge Bank 自身用于验证 Kotlin/CLI 的 JUnit 测试。Limelight validity/freshness 的 trigger 命中不产生 violations，若没有其他硬违规则退出码 0；soft 只表示需人工/模型复核，不是机器证明的违规或真机验证，**Agent 必须向用户报告**它。

check 完成时含 team、season、profiles、ok、violations、soft：

```json
{"schemaVersion":2,"command":"check","team":"20827","season":"2025-2026","profiles":[],"ok":true,"violations":[],"soft":[]}
```

violations 必有 ruleId/check/pattern/detail，可有 path/line；soft 为 ruleId/note。soft 非空不导致硬失败，更不是硬件验证证明。`check --work-mode dev` 的 soft note 使用稳定格式 `work-mode=dev; downgraded hard check=<kind> path=<path> line=<line> pattern=<pattern> detail=<detail>`（无 path/line 的检查省略对应片段），逐项保留原 hard 命中的位置与检查种类；消费方不得把该输出当作 violations 或正式规则通过。

## 5. 错误形状与消费者

```json
{"schemaVersion":2,"command":"resolve","ok":false,"error":{"code":"context-required","message":"Select a project profile or explicitly select generic"}}
```

| error.code | 退出码 | 含义 |
| --- | --- | --- |
| usage | 64 | 参数/命令错误 |
| load-error | 2 | 知识或 diff/仓库加载失败 |
| invalid-knowledge | 2 | 规则校验失败，附 violations（ruleId/field/message） |
| context-required | 2 | 未显式选择 profile |
| invalid-context | 2 | 未知或互斥 profile |
| conflict | 2 | check 所需规则冲突，尚未执行 diff 检查 |

消费方联合检查退出码、JSON Schema、command、team/season、normalized profiles、ok 与违规/冲突数组；非 JSON、schemaVersion!=2 或上下文不符即停止，绝不按通过处理。v1 固定项目保留旧 SHA，只有显式升级且选择 profile 才迁移到 v2。

## 6. 确定性与检查范围

- activeRules 按 id；excludedRules/overriddenRules 按 ruleId；conflicts 按 topic；嵌套 IDs、reasons、profiles、teams/seasons、authorities 键以及 trigger paths/patterns 排序。
- check violations 按 ruleId/path/line，soft 按 ruleId；dev 转换项先按 ruleId/path/line/check/pattern/detail 排序，与原有 soft 合并后再按 ruleId 稳定排序。证据和 checks 保留规则声明顺序。
- 同知识内容、team、season、normalized profiles、work mode 和相同 diff（对 check）产生逐字节相同 stdout；输出不注入运行时间戳，证据日期来自数据。
- 默认 check 合并 HEAD→index 与 HEAD→工作区，包括非忽略 untracked；路径 checks 覆盖删除、只删行、空文件及重命名前后路径。`regex-required` 和 review trigger 只逐条查看新增行；`regex-forbidden` 先逐条查看新增行，再可在连续新增行块中匹配跨行模式。
- --diff 替代默认集合；空补丁合法，无法解析的非空补丁失败。见 [规范器](standardizer-check.md)。

## 7. 变更、工件与验证边界

删除/改名/改类型等破坏性变更必须提升 schemaVersion；兼容新增字段可忽略，未知错误不能视为成功。CLI 2.0.0 是 kernel v2 破坏性升级对应的 CLI 版本；当前 CLI 2.1.0 只增加向后兼容的 `--work-mode` 与加法字段 `workMode`，不等于仓库 V0.9.0。

[fixtures/kernel](../fixtures/kernel/) 的 12 份 JSON 从实际构建 CLI stdout 生成：validate-ok / resolve-ok / resolve-conflict / resolve-test / error-usage / error-invalid-knowledge / check-pass / check-hard / check-dev / check-error-usage / check-error-load / check-error-conflict。KernelJsonAcceptanceTest 对每份执行当前 v2 JSON Schema 校验；错误与 check 样例使用隔离合成输入，resolve-ok、resolve-test 和 validate-ok 使用仓库知识。

编译、CLI、JSON Schema 验证不等于 Robot Controller、Driver Station、IDE 交互、部署或真机验证。Limelight 的 approved 条件式 soft 只按触发的新增行请求复核；无匹配不输出 Limelight soft。不得插入无意义代码改变检查结果。冲突须由授权维护者修订规则/范围并重新校验，不由模型猜测。
