# Command-Based 正式代码使用 SolversLib

用户于 2026-10-29 要求正式代码使用 SolversLib，并补充相关使用规则；明确选择「只限制已选择 command-based 的项目」。本次按仓库维护者的明确范围决定新增已批准政策，approval 沿用维护者声明标识 lucasnotfound59 / overall_software_lead，不表示身份认证。没有自动 commit/push 或目标机器人项目升级。

## 范围与裁决

- 新增六条跨赛季、跨队伍、仅 `command-based` profile 适用的规则；`ftclib-command` 现有归一化也包含 command-based，因此正式代码遵守同一 SolversLib 政策。generic、simple-opmode、rookiebot 不受新限制。
- `global.solverslib-production-runtime` 使用 topic `command-runtime-boundary`，在 normal/dev 覆盖原共享运行时自选规则；作为无条件 soft 要求 Agent 确认固定依赖、真实运行时和唯一 scheduler，不能靠 import 证明使用。
- `global.solverslib-production-imports` 使用 topic `command-framework`，覆盖旧 FTCLib 选择规则；以新增 TeamCode Java/Kotlin 的 FTCLib 命令包引用和 Gradle FTCLib core 依赖为硬检查，不强制每个文件 import SolversLib。
- 两条正式架构限制加入显式 test 豁免表。test 恢复原共享运行时规则；已使用 SolversLib 的依赖、安全及组合规则仍保留。dev 继续逐项保留硬检查降级提醒。
- 四条条件式 soft：固定依赖与 Pedro 版本、生命周期与清理、requirements/default、非阻塞命令与组合。Agent 必须逐项报告，不能把 soft 或源码审阅称为真机验证。

## 依据与边界

安装依据为 https://docs.seattlesolvers.com/installation（2026-10-29 访问）。源码依据固定到官方 `FTC-23511/SolversLib` 的 `0.3.6` tag 所指 `93ff730965655b3a55f6648ce5e0f1c0b820206b`；不从 FTCLib 继承假定实际行为，不自动升级任何项目版本。

已核对 `CommandOpMode.runOpMode` 中 initialize 位于 try 之前、STOP during INIT 不进入 preRun、finally 的 end/reset；`CommandScheduler.reset` 仅清 singleton、cancel/end 回调异常不保证全量清理；WaitCommand 参数为毫秒；ParallelCommandGroup 拒绝重叠 requirements。异常隔离和安全输出是本库工程政策，不冒充 vendor 保证。

## 验收

验证完整知识库；以 20827、16093、2025-2026 和跨赛季上下文验证所有 profile、normal/test/dev，无同层冲突；验证 FTCLib 命令引用硬拦截、SolversLib 正例及无 import 的普通工具不误报；test 仅豁免架构限制，安全与 tests/utils/JUnit 硬检查保留。同步当前规则计数、文档与真实 kernel fixtures，保留历史报告。
