# ftckb 知识内核机器契约（Kernel JSON Contract）

面向外部 Agent 与脚本的稳定、版本化、确定性接口只有 `validate`、`resolve`、`check`。这些命令不需要 API key，不调用模型或联网核验证据。chat / serve / eval 不是机器契约。

## 1. 获取与版本

```bash
git clone https://github.com/lucasnotfound59/FTC-Knowledge-Bank.git
cd FTC-Knowledge-Bank
./gradlew :apps:knowledge-cli:installDist
# JDK 21+；产物 apps/knowledge-cli/build/install/ftckb/bin/ftckb
```

消费方固定审阅过的完整 commit，不跟踪 main。仓库 V0.4.0、CLI 2.0.0、YAML v4、kernel JSON v2、项目接入协议 v2 是独立版本轴。YAML 解码兼容 v1-v3，不代表 kernel v1 消费方兼容 v2。旧机器模式见 [v1 schema](kernel-contract.v1.schema.json)；当前 [v2 schema](kernel-contract.schema.json)。

知识总数 46（40 已批准 + 6 候选）；validate 包含候选计数，resolve 的 activeRules 不包含候选。

## 2. 命令与显式 profile

```bash
ftckb validate knowledge --json
ftckb resolve knowledge --team 20827 --season 2025-2026 --generic-profile --json
ftckb check <repo-root> --knowledge knowledge --team 20827 --season 2025-2026 --profile command-based --json
```

上面展示两种 profile，实际同一项目的 resolve/check 必须使用相同选择。

- resolve/check 必填数字 `--team`、`YYYY-YYYY` 格式的 `--season`。
- profile 必须显式选择：`--generic-profile` 表示空集，或可重复 `--profile NAME`。不能混用；重复 generic、缺 profile 值是 usage。
- 支持 rookiebot、simple-opmode、command-based、ftclib-command；rookiebot 隐含 simple-opmode，ftclib-command 隐含 command-based。simple-opmode 与 command-based 互斥。未知名称或不兼容组合是 invalid-context；缺少选择是 context-required。不从依赖猜架构。
- validate 只接受知识根目录和可选 --json，不接受 profile。
- check 可选 `--diff FILE`；省略 --knowledge 时使用当前目录的 knowledge。
- --json 存在时机器路径的成功与失败均为单行 JSON，无日志噪音；--help 是独立的人类帮助入口。

| 退出码 | 含义 |
| --- | --- |
| 0 | validate 成功、resolve 无冲突、check 无硬违规 |
| 1 | check 硬违规，violations 非空 |
| 2 | 加载、校验、上下文或冲突失败 |
| 64 | 未知命令、缺失/错误参数等 usage |

## 3. authority 与 policyLevel

`authority` 是来源身份：official / team / shared；`policyLevel` 是适用策略：global / local / shared。有效优先级固定为 **OFFICIAL > GLOBAL > LOCAL > SHARED**。

official 来源必须用 policyLevel=global，但有效层级是 official，永远高于共享来源的 global；team 来源必须是 local，且限定队号。shared 来源可以是 global、local 或 shared；local 必须限定 teams 或 profiles。文件目录和 id 前缀不参与裁决。

只有 approved 且队号、赛季、profile 全部匹配才进入同主题比较。teams/seasons 空集表示该维度不限制；profiles 空集表示无架构要求，非空时要求当前 normalized profiles 包含全部指定值，不是任选其一。同主题最高有效层级并列即冲突，即使 authority 不同也不静默选胜者。其他主题仍可输出 activeRules。

8 条原队伍规则迁移到 global 时只去掉队号限制，保留 2025-2026；2 条 candidate 不转正。当前两队相同赛季/profile 的 active IDs 相同，其他赛季必须重新裁决。

## 4. JSON v2 字段

所有输出含 schemaVersion=2、ok；已识别命令含 command，未知命令 usage 可无 command。

validate 成功：

```json
{"schemaVersion":2,"command":"validate","ok":true,"ruleCount":46,"violations":[]}
```

resolve（无冲突或有冲突）都有 team、season、normalized profiles、activeRules、excludedRules、overriddenRules、conflicts，不使用 error 字段。

| 字段 | 元素及含义 |
| --- | --- |
| activeRules | id/topic/title/instruction/rationale/status/authority/policyLevel/applicability/evidence/checks/reviewTriggers |
| applicability | teams、seasons、profiles 字符串数组 |
| excludedRules | ruleId、reasons；原因可为 profile/season/status/team，同时保留所有不匹配原因 |
| overriddenRules | ruleId、topic、winnerIds、effectiveLevel；适用但层级较低，effectiveLevel 是最高层级，winnerIds 可是冲突规则 |
| conflicts | topic、effectiveLevel、ruleIds、authorities；最高有效层级并列，该主题没有 active 胜者 |

冲突元素：

```json
{"topic":"same-topic","effectiveLevel":"global","ruleIds":["global.a","global.b"],"authorities":{"global.a":"shared","global.b":"shared"}}
```

resolve 冲突时 ok=false、退出 2；仍保留其他主题 activeRules 和排除/覆盖解释。不要把它当成功。文本模式遇到冲突抑制 active 行。

规则正文可以是中文或英文。evidence 用 type=git 或 web；git 含 repository/commit/file 及可选 symbol/line，web 含 url/title/publisher/accessedAt/section 及可选 version/product/sku。checks 的 kind 在 resolve JSON 使用下划线（path_forbidden 等），YAML 与 check 违规的 check 字段使用连字符（path-forbidden 等）。reviewTriggers 是审阅元数据，不是新的硬检查类型。

check 完成时含 team、season、profiles、ok、violations、soft：

```json
{"schemaVersion":2,"command":"check","team":"20827","season":"2025-2026","profiles":[],"ok":true,"violations":[],"soft":[]}
```

violations 必有 ruleId/check/pattern/detail，可有 path/line；soft 为 ruleId/note。soft 非空不导致硬失败，更不是硬件验证证明。

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
- check violations 按 ruleId/path/line，soft 按 ruleId。证据和 checks 保留规则声明顺序。
- 同知识内容、team、season、normalized profiles 和相同 diff（对 check）产生逐字节相同 stdout；输出不注入运行时间戳，证据日期来自数据。
- 默认 check 合并 HEAD→index 与 HEAD→工作区，包括非忽略 untracked；路径检查包含删除、只删行、空文件及重命名前后路径，regex 只看新增行。
- --diff 替代默认集合；空补丁合法，无法解析的非空补丁失败。见 [规范器](standardizer-check.md)。

## 7. 变更、工件与验证边界

删除/改名/改类型等破坏性变更必须提升 schemaVersion；兼容新增字段可忽略，未知错误不能视为成功。CLI 2.0.0 是 kernel v2 破坏性升级对应的 CLI 版本，不等于仓库 V0.4.0。

[fixtures/kernel](../fixtures/kernel/) 的 10 份 JSON 从实际构建 CLI stdout 生成：validate-ok / resolve-ok / resolve-conflict / error-usage / error-invalid-knowledge / check-pass / check-hard / check-error-usage / check-error-load / check-error-conflict。KernelJsonAcceptanceTest 对每份执行当前 v2 JSON Schema 校验；错误与 check 样例使用隔离合成输入，resolve-ok 和 validate-ok 使用仓库知识。

编译、CLI、JSON Schema 验证不等于 Robot Controller、Driver Station、IDE 交互、部署或真机验证。两条 Limelight regex-required 当前作用于所有 Java 新增行，有误报限制；不得插入无意义代码绕过。冲突须由授权维护者修订规则/范围并重新校验，不由模型猜测。
