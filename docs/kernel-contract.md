# ftckb 知识内核机器契约（Kernel JSON Contract）

> 面向对象：对接本知识库的外部 Agent（Codex / Claude Code / DSH 会话 / 任意脚本）。
> 本文档描述 `validate` 与 `resolve` 两个命令的**稳定、版本化、确定性**机器接口。
> `chat` / `eval` 是本地交互模式，不属于本契约。

## 1. 获取与运行

代码位于 git 分支 `codex/cli-agent`（工作树路径 `./.worktrees/cli-agent`，与主检出 `main` 分支共享同一仓库）。

```bash
# 正常环境（能跑 gradle 时）：
cd .worktrees/cli-agent
./gradlew :apps:knowledge-cli:installDist   # 产物在 apps/knowledge-cli/build/install/ftckb/bin/ftckb

# 无 gradle 的受限环境（如本机 DSH sandbox）：直接使用已构建的启动器：
./.worktrees/cli-agent/apps/knowledge-cli/build/install/ftckb/bin/ftckb --help
```

知识根目录：`.worktrees/cli-agent/knowledge`（27 条规则：20 条已批准 + 7 条候选，候选规则不会进入 resolve 结果）。

## 2. 命令与退出码

| 命令 | 形式 | 说明 |
| --- | --- | --- |
| validate | `ftckb validate <knowledge-root> [--json]` | 加载并校验全部规则；成功输出规则总数 |
| resolve | `ftckb resolve <knowledge-root> --team N --season YYYY-YYYY [--json]` | 按队伍+赛季裁决出全部**生效规则**；存在规则冲突时退出码为 2 |

选项约束：

- `--team`：仅数字（如 `20827`）。
- `--season`：严格 `YYYY-YYYY`（如 `2025-2026`）。
- `--json` 可放在命令行的任意位置；出现 `--json` 时**所有输出（包括错误）都是单行 JSON**。
- `resolve` 的 `--team` / `--season` 为必填，可重复出现时视为错误；`validate` 不接受任何额外参数。

退出码（稳定，契约的一部分）：

| 退出码 | 含义 |
| --- | --- |
| 0 | 成功（resolve 成功即无冲突） |
| 2 | 知识加载失败、规则校验失败（violations），或 resolve 存在冲突 |
| 64 | 用法错误（未知命令、缺/错参数） |

## 3. JSON 契约（schemaVersion = 1）

所有 JSON 输出都是**单行**（无换行、无日志噪音）写往 stdout。顶层必有：

- `schemaVersion`：整数，当前恒为 1；破坏性变更必须提升版本号。
- `ok`：布尔。
- `command`：`"validate"` 或 `"resolve"`（未知命令的用法错误没有此字段）。

### 3.1 validate 成功

```json
{"schemaVersion":1,"command":"validate","ok":true,"ruleCount":27,"violations":[]}
```

`ruleCount` 为加载到的规则总数（含候选）。退出码 0。

### 3.2 resolve 成功

```json
{
  "schemaVersion": 1,
  "command": "resolve",
  "team": "20827",
  "season": "2025-2026",
  "ok": true,
  "activeRules": [
    {
      "id": "official.keep-customizations-in-teamcode",
      "topic": "build-customization-location",
      "title": "Keep build customizations in TeamCode",
      "instruction": "Put legacy FTC SDK build customizations in TeamCode/build.gradle instead of build.common.gradle.",
      "rationale": "The official SDK reserves build.common.gradle for changes delivered with SDK updates.",
      "status": "approved",
      "authority": "official",
      "applicability": { "teams": [], "seasons": [] },
      "evidence": [
        {
          "type": "git",
          "repository": "FIRST-Tech-Challenge/FtcRobotController",
          "commit": "26cd1fdd2a3c4b26173d9ff33a3279c27d1c7ad1",
          "file": "build.common.gradle",
          "symbol": "build.common.gradle"
        }
      ]
    }
  ],
  "conflicts": []
}
```

`activeRules` 中每条规则的字段：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | string | 全局唯一规则 id（`official.*` / `shared.*` / `team-<编号>.*`） |
| topic | string | 规则主题 slug；同一 topic 的多条规则构成冲突 |
| title / instruction / rationale | string | 规则正文（英文） |
| status | string | `approved` 或 `candidate`；resolve 结果只含 `approved` |
| authority | string | `official` / `shared` / `team` |
| applicability.teams / applicability.seasons | string[] | 空数组 = 对所有队伍/赛季生效 |
| evidence | array | 每条证据带 `type` 字段，见下 |

证据两种形态（按 `type` 区分）：

```json
{"type":"git","repository":"FIRST-Tech-Challenge/FtcRobotController","commit":"26cd1fdd…","file":"build.common.gradle","symbol":"build.common.gradle","line":12}
{"type":"web","url":"https://acmerobotics.github.io/ftc-dashboard/gettingstarted.html","title":"FTC Dashboard Getting Started","publisher":"FTC Dashboard","accessedAt":"2026-08-13","section":"Basic Installation","version":"0.6.0"}
```

- git：`symbol`、`line` 仅在有值时出现。
- web：`version`、`product`、`sku` 仅在有值时出现。

### 3.3 resolve 存在冲突

冲突时 `ok:false`、`conflicts` 非空、退出码 2（仍输出完整 activeRules）：

```json
{"schemaVersion":1,"command":"resolve","team":"20827","season":"2025-2026","ok":false,"activeRules":[…],"conflicts":[{"topic":"same-topic","authority":"official","ruleIds":["official.first","official.second"]}]}
```

`conflicts` 元素字段：`topic`（string）、`authority`（string，裁决层级）、`ruleIds`（string[]）。

### 3.4 所有失败路径（统一错误形状）

只要命令行里出现 `--json`，**任何失败**都输出单行 JSON：

```json
{"schemaVersion":1,"command":"resolve","ok":false,"error":{"code":"usage","message":"missing --season"}}
```

`error.code` 取值：

| code | 退出码 | 含义 |
| --- | --- | --- |
| usage | 64 | 用法错误；`message` 为具体原因（missing --team / unknown command / invalid value for --season …） |
| load-error | 2 | 知识目录加载失败；`message` 形如 `error loading knowledge: …` |
| invalid-knowledge | 2 | 规则校验失败；额外带 `violations` 数组 |

`invalid-knowledge` 示例：

```json
{"schemaVersion":1,"command":"validate","ok":false,"violations":[{"ruleId":"shared.invalid-commit","field":"evidence[0].commit","message":"commit must be a Git SHA"}],"error":{"code":"invalid-knowledge","message":"1 rule violation(s)"}}
```

外部 Agent 的稳健解析策略：按退出码分支，stdout 能解析成 JSON 且含 `schemaVersion` 即按契约处理；否则按纯文本错误行处理（兼容旧版本）。

## 4. 确定性保证

- `activeRules` 按 `id` 字典序排序；`conflicts` 按 `topic` 排序，`ruleIds` 排序；`applicability.teams/seasons` 排序。
- 相同输入（knowledge-root 内容 + team + season）必定产生逐字节相同的 stdout。
- 输出不含运行时间戳（`evidence.accessedAt` 来自规则数据本身）。
- 规则裁决优先级固定：OFFICIAL > TEAM > SHARED；同主题同层级冲突上报而不是静默覆盖。

## 5. 文本模式（给人看）

不带 `--json` 时输出人类可读文本：

```
$ ftckb validate knowledge
validation=ok rules=27
$ ftckb resolve knowledge --team 20827 --season 2025-2026
active official.keep-customizations-in-teamcode
active shared.dashboard-pin-stable-dependency
…
$ ftckb resolve knowledge --team 20827 --season 2025-2026   # 有冲突时
conflict topic=build-customization-location rules=official.a,shared.b   # 退出码 2
```

## 6. 变更策略

- 只增不改：新增字段、新增 error.code 是向后兼容的；删除/改名/改类型必须提升 `schemaVersion`。
- 消费方应以 `schemaVersion==1` 判断兼容性，未知字段一律忽略。
- 相关测试：`apps/knowledge-cli/src/test/kotlin/org/ftckb/cli/KernelJsonAcceptanceTest.kt`（含契约、确定性、冲突、错误形状共 7 个用例），这是契约的可执行定义。

## 7. 边界与已知限制

- 候选规则（status=candidate）只出现在 `validate` 的 `ruleCount` 里，不会进入 `resolve`。
- **当前 `knowledge/teams/` 下的队伍规则全部是 candidate**，因此现阶段不同 `--team` 得到的 `activeRules` 完全相同（只有顶层 `team` 字段不同）；队伍规则获批后 `--team` 才会真正改变结果。这不是 bug，对接方无需核对。
- 程序输出的错误消息为英文；规则正文（title/instruction/rationale）为英文，界面文案（web 会话等）另做中文化。
- `chat` / `eval` / `serve` 不在本契约内：它们基于同一内核构建，但不是给机器消费的接口。
- 规则冲突目前只能检测（退出码 2），不能自动裁决；由上层 Agent 决定如何处理。

## 8. 当前快照

- 知识规则：27 条（20 approved + 7 candidate；candidate 含 4 条 Control Hub LED 官方候选）。
- 契约测试与全套离线测试随 `build/kotlinc-verify/verify-all.sh` 运行，当前全绿。
- 上次更新：2026-08-16，分支 `codex/cli-agent`。
