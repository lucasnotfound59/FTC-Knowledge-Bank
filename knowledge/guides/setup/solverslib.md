# SolversLib：正式 Command-Based 代码与版本核对

核验日期：2026-10-29。本库维护者要求：显式选择 `command-based` 的正式代码使用 SolversLib；`ftclib-command` 因现有归一化包含 command-based，也受此限制。generic、simple-opmode、rookiebot 不受该架构要求。这是本库工程政策，不是 FIRST 或厂商要求所有 FTC 项目采用此架构。

以实际固定知识库、真实队号/赛季和 `resolve --json` 的 active/excluded/overridden/conflicts 为准。旧 pin 不会自动获得这些规则，不擅自升级接入版本或改项目 profile。

## 依赖与来源

先核对目标项目已有的固定版本，再按[官方安装说明](https://docs.seattlesolvers.com/installation)处理。2026-10-29 页面示例使用 `org.solverslib:core:0.3.6`，仓库是 `https://repo.dairy.foundation/releases`，源码包名为 `com.seattlesolvers.solverslib`。不同时安装 FTCLib 与 SolversLib，不以简单改 import 代替 API、运行时和生命周期迁移。

只有实际需要时引入 `pedroPathing` / `photon` 扩展。官方当前版本对应为：0.3.6+ 配 Pedro 3.x，0.3.3–0.3.5 配 Pedro 2.x，0.3.2 配 Pedro 1.0.9，0.3.1 配 Pedro 1.0.8；扩展不代替 Pedro 自身依赖。不要为套用示例而自动升级项目，精确 API 和依赖必须重新核对。

本文源码证据为官方 `0.3.6` tag 的完整 commit `93ff730965655b3a55f6648ce5e0f1c0b820206b`。tag 源码不等于已核验发布 JAR 内容；目标项目依赖解析、Gradle sync/build 需单独记录。若改根 `build.dependencies.gradle`，还须执行现有第一方依赖证据复核规则。

## 调度与安全

- 保持唯一 scheduler 生命周期 owner，在主循环调度；覆盖 `CommandOpMode.run` 时保留 `super.run()` 或经核对的等价集成，避免重复调度和额外调度线程。
- `CommandOpMode.runOpMode` 的 `initialize()` 在 try 之前；其后才由 finally 调用 `end()` 与 `reset()`，STOP during INIT 不进入 `preRun()`。不能宣称初始化异常也受该 finally 保护。
- `CommandScheduler.reset()` 只清 singleton；空 `end()` 不取消活动命令。`cancelAll()` 使用命令快照，但任一 `end(true)` 抛错仍可能阻止后续取消。项目适配层须逐项尝试安全输出并保留原故障，不用一次 reset/cancelAll 证明停机安全。
- 控制机构的命令声明全部 requirements，包括 lambda 命令；持续输出在正常结束和中断时清理。安全值依据机构决定，不一律断电或归零。
- default 命令包含注册子系统、持续输入在 execute 中读取；固定版本不是全资源空闲后才恢复，多资源 default 可能抢占其他可中断命令，须显式协调。
- 回调保持非阻塞，`WaitCommand(long)` 为毫秒。不要把该单位推定到所有 timeout API。条件等待需超时与失败处置；组合子命令不独立调度、同一实例不重复归属，并行分支不共享 requirements。

以上部分约束强于厂商保证，属于本库工程政策；源码审阅、编译和静态检查都不表示 RC、DS 或真机通过。

## 正式、测试和 dev

`normal` 下两个 global 规则覆盖旧的运行时自选和 FTCLib Command 主题。正式架构要求始终输出 soft，Agent 必须核对真正使用 SolversLib；另一个 hard 规则拦截新增 TeamCode Java/Kotlin 的 FTCLib 命令包引用及常见 Gradle FTCLib core 依赖声明。它不检查所有存量文件，也不能证明动态依赖、版本目录或自有 scheduler 合规；工具类不需要为了通过检查而增加 import。

仅用户明确指定本次测试任务时选择 `--work-mode test`。test 排除两个新架构限制与原有两个架构 mandate，原共享运行时选择规则重新参与裁决；已使用 SolversLib 的依赖、安全和组合规则仍保留，tests/utils/JUnit 等其他硬检查继续生效。混有正式改动时分别提供本次 `--diff FILE`，不要用测试豁免放松正式代码。

显式 `dev` 保留架构政策，仅把 hard 命中逐项转成 soft，必须报告原规则、位置和原因；不是正式合规结论。

六条规则分别为：`global.solverslib-production-runtime`、`global.solverslib-production-imports`、`shared.solverslib-dependencies`、`shared.solverslib-lifecycle`、`shared.solverslib-requirements`、`shared.solverslib-composition`。四条共享规则采用条件式 soft：相关 TeamCode Java/Kotlin 新增行或构建依赖路径触及时提示。每项 soft 必须报告；无提示不能证明代码安全。

## 固定源码入口

- [CommandOpMode](https://github.com/FTC-23511/SolversLib/blob/93ff730965655b3a55f6648ce5e0f1c0b820206b/core/src/main/java/com/seattlesolvers/solverslib/command/CommandOpMode.java)
- [CommandScheduler](https://github.com/FTC-23511/SolversLib/blob/93ff730965655b3a55f6648ce5e0f1c0b820206b/core/src/main/java/com/seattlesolvers/solverslib/command/CommandScheduler.java)
- [WaitCommand](https://github.com/FTC-23511/SolversLib/blob/93ff730965655b3a55f6648ce5e0f1c0b820206b/core/src/main/java/com/seattlesolvers/solverslib/command/WaitCommand.java)
- [ParallelCommandGroup](https://github.com/FTC-23511/SolversLib/blob/93ff730965655b3a55f6648ce5e0f1c0b820206b/core/src/main/java/com/seattlesolvers/solverslib/command/ParallelCommandGroup.java)

职责教学与历史实现比较见 [Command-Based 指南](../architecture/command-based.md)。
