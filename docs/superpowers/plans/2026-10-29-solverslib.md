# SolversLib Implementation Plan

**Goal:** 在用户已确认的 command-based 范围强制正式代码使用 SolversLib，并提供有固定依据的使用规则。

**Architecture:** 复用现有 profile、topic 覆盖、checks、reviewTriggers 和逐任务 WorkMode；不新增 profile、schema 或联网检查。保留当前 checkout 与无关未跟踪内容，不自动 commit/push。

**Tech Stack:** YAML v4、Kotlin domain/CLI、JUnit、kernel JSON v2。

## Task 1: 政策、使用规则与工作模式

- [x] 新建 `knowledge/global/solverslib-production.yaml`：两个 global 规则，分别无条件 soft、FTCLib 命令引用与依赖 hard；覆盖现有两个架构 topic。
- [x] 新建 `knowledge/shared/solverslib.yaml`：四个 command-based 条件式 soft，按设计覆盖依赖/Pedro、生命周期、requirements/default、组合/非阻塞。
- [x] 修改 `modules/domain/src/main/kotlin/org/ftckb/domain/WorkMode.kt`，将两个新 mandate IDs 加入 test 排除表。
- [x] 新增 `SolversLibRuleAcceptanceTest.kt`，直接加载真实规则，覆盖 profile/赛季/队伍、topic 覆盖、test 安全保留、Java/Kotlin/Gradle 正反例和 soft 触发。
- [x] 运行 `./gradlew :modules:domain:test :apps:knowledge-cli:test --tests '*WorkModeTest' --tests '*SolversLibRuleAcceptanceTest' --offline`。

## Task 2: 文档、快照与验收

- [x] 新建 `knowledge/guides/setup/solverslib.md`，写清用户政策与厂商事实、固定版本证据、test/dev、兼容 profile 及检查边界。
- [x] 同步 README、AGENTS、当前用户文档、安装 Skill 和受影响验收测试；历史报告不改写。
- [x] 用重建后的 CLI 生成现有真实 kernel fixture 对应输出，不改示例输入与 schema。
- [x] 运行受影响 domain/knowledge/standardizer 及 CLI 验收；以实际通过数量记录本次结果。
- [x] 运行 `ftckb validate knowledge --json`，用显式 normal 的 `--diff` 对本次变更做 check 并逐项审阅 soft。
- [x] 独立代码审查并处理重要问题；Dashboard 保留运行，交付有效链接及核验边界。
