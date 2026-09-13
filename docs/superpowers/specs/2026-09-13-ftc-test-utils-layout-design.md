# FTC Test 与 Utils 目录硬约束 — 设计说明

日期：2026-09-13

批准时间：2026-09-13T09:12:38Z
目标版本：仓库 V0.6.0；CLI 2.0.0、YAML v4、kernel JSON v2、项目接入协议 v2 保持不变。

## 1. 已批准决定

目标 FTC 项目的机器人侧测试、诊断、校准 OpMode 统一放在：

```text
TeamCode/src/main/java/org/firstinspires/ftc/teamcode/tests/
```

复用工具类统一放在：

```text
TeamCode/src/main/java/org/firstinspires/ftc/teamcode/utils/
```

`tests/` 与 `utils/` 必须位于 `teamcode/` 包内，与 `subsystems/`、`commands/` 平级；不能直接建立在
`src/main/java/` 或 `org/firstinspires/ftc/` 下。目标 FTC 项目不采用 JUnit、`TeamCode/src/test/`
或 `TeamCode/src/androidTest/` 作为队伍测试方案。

“不采用 JUnit”只约束接入知识库的 FTC TeamCode 项目，不删除或禁止 FTC Knowledge Bank 自身用于验证
Kotlin/CLI 的 JUnit 测试。

## 2. 冲突与明确覆盖

这项决定是对既有来源写法的明确覆盖：

- 当前本地 TeamChina checkout 把 `ConfigTeleOpTest.java` 与 `FGC2026TeleOpTest.java` 放在
  `teamcode/teleops/`。用户已确认新规则优先，不继续沿用该目录写法；
- `shared.rookiebot-java-imports` 当前推荐 `org.junit.Assert`，
  `shared.rookiebot-verification-evidence` 当前要求 `:TeamCode:testDebugUnitTest`。二者若不迁移，
  会与新 global hard 规则同时生效并向 Agent 提供相反指令，因此必须删除 JUnit 专属内容；
- TeamChina 的其他写作标准、RookieBot 的非 JUnit 内容、原有来源记录与验证边界不受影响。

迁移台账必须写明这是 2026-09-13 的新维护者决定，不把它追溯描述成 TeamChina、RookieBot、FIRST
或 FTC SDK 原本已经采用的要求。

## 3. 新知识规则

新增独立文件 `knowledge/global/test-utility-layout.yaml`，其中包含一条规则：

```text
id: global.test-utility-layout
topic: test-utility-layout
status: approved
authority: shared
policyLevel: global
teams: []
seasons: []
profiles: []
```

规则为跨队伍、跨赛季、跨 profile 的项目组织规范。`seasons=[]` 是有意选择：目录职责不依赖比赛任务、
SDK API 或机器人机构参数。审批使用用户本次确认的 `overall_software_lead` 授权；证据固定到本设计文档的
完整 Git commit，以保留规范来源，不伪造外部仓库已经遵循该布局。

规则 instruction 必须同时表达：

1. 机器人侧 test/debug/diagnostic/calibration OpMode 进入 `teamcode/tests/`；
2. 可复用、无机构所有权的 helper/conversion/adapter 类进入 `teamcode/utils/`；
3. subsystem 与 command 仍进入各自目录，不能为了绕过目录规则伪装成 utils；
4. 不创建或使用 JUnit、`src/test`、`src/androidTest`；
5. Java `package` 声明必须与实际目录一致。

## 4. Hard 检测设计

使用 YAML v4 已有的 `path-forbidden` 与 `regex-forbidden`，不新增 check kind，不提升 schemaVersion。
确定性 hard checks 至少覆盖：

- `TeamCode/src/test/**`；
- `TeamCode/src/androidTest/**`；
- `TeamCode/src/main/java/{tests,utils}/**`；
- `TeamCode/src/main/java/org/firstinspires/ftc/{tests,utils}/**`；
- TeamCode Java 新增行中的 `org.junit`／`junit.*` import；
- TeamCode Gradle 新增行中的 JUnit test dependency。

任一命中进入 `violations`，`ftckb check` 返回 1，Agent 必须修复后才能交付。正确的
`teamcode/tests/**` 与 `teamcode/utils/**` 不应被这些 checks 拦截。

现有检查器不能从任意类名和控制流可靠判断一个类在语义上是否属于测试或工具。因此本功能不增加一个
会误判所有 Java 文件的新正则，也不声称仅凭 `check` 能证明每个类均分类正确。Agent 仍须遵守 active rule
的完整 instruction；机器 hard checks 负责阻止可确定的错误路径和 JUnit 用法。以后若需要完全语义化分类，
应单独设计 AST/索引检查，而不是扩大本次 glob。

## 5. RookieBot 迁移

保留 `shared.rookiebot-java-imports` 的 ID、topic、local policy 与 RookieBot profile，只删除 JUnit 断言
导入内容，收窄为“项目类与 FTC 类的正确 import”规则；更新标题、instruction、examples、证据和审批时间，
不得继续引用 `src/test` 文件作为当前推荐范例。

保留 `shared.rookiebot-verification-evidence` 的 ID、topic、local policy 与 RookieBot profile，改为要求：

- 运行 `:TeamCode:assembleDebug`；
- 报告实际执行的静态检查、构建、机器人侧 tests OpMode、部署和真机结果；
- 未实际部署／运行的 tests OpMode 不得写成已通过；
- 不再要求或展示 `:TeamCode:testDebugUnitTest`。

这两条仍为 approved，但以本次用户决定作为新审批，不把修改后的内容归因于 2026-09-06 的旧审批。
`knowledge/guides/practices/rookiebot-tutorial.md` 同步移除 JUnit 和 `src/test` 教程，改为新目录与验证边界。

## 6. 版本与计数

Limelight 条件式 soft 是独立的 V0.5.0 feature；本目录 hard 规则是下一项 feature，因此最终当前版本为
V0.6.0。CLI 命令、YAML 字段集合、kernel JSON 输出形状和接入协议均不变。

新增一条 approved 规则后：

- 规则总数：47；
- approved：41；
- candidate：6；
- 2025-2026 两队 active 数量：generic 25、command-based 28、rookiebot 37、ftclib-command 29；
- 完成 Limelight soft 迁移后，带 hard checks 的 active 规则数由 4 增为 5。

文档和真实 fixtures 必须由构建后的 CLI 输出同步，不手工保留旧计数。

## 7. 验收

至少证明：

1. 新规则为 approved/global/cross-season/cross-profile，审批和固定 Git 证据存在；
2. `teamcode/tests/DriveTest.java` 与 `teamcode/utils/AngleUtils.java` 不触发该规则；
3. `src/main/java/tests/DriveTest.java`、`org/firstinspires/ftc/utils/AngleUtils.java`、
   `src/test/...` 与 `src/androidTest/...` 均产生该规则的 hard violation 和退出码 1；
4. TeamCode Java 新增 JUnit import、Gradle 新增 JUnit dependency 均被阻止；
5. RookieBot active rules 与教程不再推荐 JUnit 或 `testDebugUnitTest`；
6. `validate` 返回 47 条、无违规，四种 profile 数量符合本设计且无冲突；
7. Limelight 无关／相关 diff 的 V0.5.0 soft 行为保持不变；
8. Kotlin、Python、smoke、fixtures schema 与 `git diff --check` 全部通过。

构建和静态检查不等于 Android Studio UI、Robot Controller、Driver Station、部署或真机验证。

## 8. 非目标

- 不移动用户现有 FTC 项目的文件；知识库只为 Agent 输出规则并检查后续 diff；
- 不删除 Knowledge Bank 自身的 JUnit 测试基础设施；
- 不新增 YAML/JSON 字段、check kind、模型 API 或网络调用；
- 不按类名猜测所有测试／工具语义，不把无关 Java 文件拦截为违规；
- 不自动 tag 或 push。
