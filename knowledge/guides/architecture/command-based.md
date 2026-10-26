# Command-Based：FTCLib、类 FTCLib 与自有实现指南

> 核验日期：2026-10-23。本文适用于已经选择命令架构的项目；接口示例用于映射现有代码，不是一套可直接复制的新运行库。四条 `shared.command-*` 专区规则已由本轮真实批准人 lucas／overall_software_lead 批准；当前项目的实际生效结论仍取自 resolve。

## 先选对 profile

| 已决定采用的组织方式 | 显式选择 | 是否必须安装 FTCLib |
| --- | --- | --- |
| 自有 Command／Subsystem／scheduler，包括类 FTCLib 的轻量实现 | `--profile command-based` | 否 |
| 使用 FTCLib command 运行时及其 API | `--profile ftclib-command` | 是；内核自动包含 command-based |
| 直接编写普通 OpMode | `--profile simple-opmode` | 否 |
| 按既有 RookieBot 教程组织 | `--profile rookiebot` | 否；包含 simple-opmode |
| 尚未采用命名架构约束 | `--generic-profile` | 不据此判断依赖 |

profile 由使用者明确选择，不从依赖、类名或目录猜测。command-based 与 simple-opmode 两种架构不能混用；generic 表示显式选择通用规则。resolve/check 使用同一真实队号、赛季和 profile。

调用前先按[新手入口](../../../docs/getting-started.md)确认已启动或复用实际知识库的 Dashboard，页面已连接，knowledge 路径和 audit 目录与本次 CLI 一致；发出启动命令不算连接成功。已接入项目优先使用项目运行时 Skill／工作回执给出的入口与固定知识库。以下裸 CLI 是平台 QA 示例，机器人项目替换为自身上下文和知识库路径：

```bash
apps/knowledge-cli/build/install/ftckb/bin/ftckb resolve knowledge --team 20827 --season 2026-2027 --profile command-based --json
apps/knowledge-cli/build/install/ftckb/bin/ftckb check /absolute/path/to/robot --knowledge knowledge --team 20827 --season 2026-2027 --profile command-based --json
```

以 `activeRules`、`excludedRules` 和 `conflicts` 为准；读到教程、candidate、同名 ID 或其他赛季批准不等于本次生效。旧 `shared.ftclib-command-candidate` 虽然名字有 candidate，实际已批准，但限定 2025-2026／ftclib-command。`test` 只在用户明确指定测试任务时使用，不强制测试 OpMode 改为某个基类；现有硬安全及布局约束继续适用。新指南只随未来明确选定的新知识库 commit 进入项目，旧 pin 不会自动升级。

## 把职责映射到已有接口

| 概念 | 负责 | 不应隐含承担 |
| --- | --- | --- |
| Subsystem | 封装机构硬件、状态、限位和安全输出 | 私自决定全局按钮行为或抢占别的机构 |
| Command | 一次动作的 initialize／execute／isFinished／end，以及全部 requirements | 绕开 scheduler 与其他命令同时写同一机构 |
| scheduler | 资源互斥、周期执行、冲突政策、default 恢复、生命周期状态 | 依靠类名猜资源或保证硬件天然安全 |
| OpMode／装配层 | 建立对象、绑定输入、选择调度时机、停止与诊断 | 同时运行两套拥有同一机构的 scheduler |

这些职责不强制包名、singleton、DSL 或 `CommandOpMode` 基类。FTCLib 用户按固定版本 API 集成；自有实现复用队伍已有接口，不需要为“像 FTCLib”添加库依赖。现有更高优先级规则仍以 resolve 为准。

## 一次完整周期怎么走

下面选择一种**自有运行时的示意政策**：INIT 只装配；START 后调度；默认驾驶 D 占用 drive；动作 A 同样占 drive、允许抢占 D；default 在全部资源空闲时恢复。项目可以有不同周期边界，但必须写清楚并验证。

| 时刻 | 可观察事件 | 资源与输出 |
| --- | --- | --- |
| INIT | 创建机构 → 创建命令 → 注册 D → 绑定实时输入 | 尚未 execute；机构保持明确的安全输出 |
| START／周期 1 | 启动运行时 → D.initialize → D.execute | drive 属于 D；按本周期输入输出 |
| 周期 2 | 操纵杆从 0.2 变 0.7 → D.execute | 使用 0.7，不能还用构造时的 0.2 |
| 周期 3 | 请求 A → D.end(true) → A.initialize → A.execute | drive 唯一属于 A |
| 周期 4 | A.isFinished=true → A.end(false) → 释放 drive | 先完成 A 的收尾，再恢复 D |
| 周期 4 末或周期 5 | 全部 requirements 空闲 → D.initialize → 下一 execute | 恢复时点由本项目明确约定，读取当前输入 |
| STOP／中断／异常 | 禁止新调度 → 尝试取消所有活动命令 → 逐项机构安全输出 → 清待办并使旧实例失效 | 后续 tick 和 callback 均不得恢复动作 |

如 INIT 确需周期执行（例如定位或传感器预热），单独列出允许行为和机构输出约束，不能直接把整个动作调度提前。被取消的命令下一次重新调度属于新的执行；“end 最多一次”针对同一次执行。

FTCLib v2.1.1 的实际时序不同：`run()` 先调用注册 subsystem 的 periodic，再轮询按钮，执行已调度命令并结束完成项，处理排队 schedule/cancel，最后尝试调度 default。因此 default 在该轮末 initialize，通常在下一轮 execute。不要把上表当成 vendor 的逐行实现。

## 连续输入：传读取行为，不传一次性快照

Java 示意片段，`drive` 和命令 API 必须换成项目现有接口；不假定任何硬件配置名：

```java
DoubleSupplier forward=()->-gamepad1.left_stick_y;
// 交给现有命令；每次 execute 都调用 forward.getAsDouble()
void execute() {
    drive.setForward(forward.getAsDouble());
}
```

反例是构造时 `double forward=-gamepad1.left_stick_y;`，随后整个 command 重用这个值。supplier 也可能闭包捕获旧局部变量，出现 `DoubleSupplier` 不能证明实时性。观察至少两个周期的输入和实际输出；自动动作的固定目标则可以有意保存，不与实时操控混为一谈。

## default 与多资源：先核对运行时

自有 scheduler 推荐先检查 default 的**全部** requirements 都空闲，再恢复它。例：D 同时需要 drive+arm，drive 空闲但 arm 被 B 占用时，D 不应仅因为 drive 空闲就抢走 arm。一直等待 D 也可能暴露设计耦合，应考虑把独立操控拆成单机构 default。

FTCLib v2.1.1 的 `setDefaultCommand` 检查 default 包含注册 subsystem 且初始 `isFinished()` 为 false；`run` 的 default 扫描只判断该注册 subsystem 是否空闲。随后 `schedule(default)` 会处理其完整 requirements，可能中断其他资源上的 interruptible command。因此，“库自动保证 default 的全部资源空闲才恢复”是错误描述。FTCLib 用户应优先令 default 只占其注册资源；多资源 default 需在应用层协调并核验抢占策略，不默认改 vendor 源码。普通命令的冲突策略也应明确：允许中断、拒绝新命令或排队，不能靠碰巧的遍历顺序决定。

## 自有 scheduler：重入和初始化失败

只在实现或修改自有 scheduler 时检查以下契约；库使用者不需要因此重新实现 FTCLib。

1. 写明所有 callback 内 schedule/cancel 是立即还是延后生效；覆盖 initialize、execute、isFinished、end，不能只保护 execute 的循环。
2. 遍历活动集合时用待办队列、快照或等价机制；待办处理期间再次提交操作也要有确定顺序，避免无限重放。
3. initialize 可能部分触及机构后抛错。活动登记和资源占用应回滚，并尝试其安全收尾；是否对失败初始化调用 end 必须明确定义，不能再按“正常活动命令”结束第二次。
4. end 内 cancel 自己、schedule 其他命令，以及 STOP 中的排队请求，不能造成同一次执行重复 end 或使已停止实例复活。

FTCLib 的 `m_inRunLoop`／`m_toSchedule`／`m_toCancel` 只说明它对部分周期内请求有延迟处理，不能证明所有 callback 的重入和异常安全。

## 清理：每项都尝试，保留原始故障

先停止接收调度，再对活动命令快照逐项取消；随后对所有已拥有机构逐项调用项目定义的安全输出，最后清除排队请求、注册绑定和运行状态。每项分别捕获并记录故障，第一项抛错也继续尝试独立项；用 suppressed 异常或等价诊断保留原始 initialize/execute 故障。安全输出需按机构评估：重力负载可能需要保持、制动或机械支撑，不能一律把所有电机归零当作安全。

仅写 `finally` 不足以证明这一点：`finally { first.stop(); second.stop(); }` 中 first 抛错会跳过 second。按项目异常政策包装独立清理步骤；日志也不应覆盖原故障。这里规定的是共享工程策略，不是 FTCLib 已提供的保证。

特别注意 FTCLib v2.1.1：`CommandOpMode.runOpMode` 正常退出后调用 reset，没有 finally；`CommandScheduler.reset` 仅令 singleton 引用为 null，不调用活动命令的 end。`cancelAll` 也不是全量清理证明：该版本遍历活动 keySet 时调用会修改该集合的 cancel，多个命令下存在中断清理的风险。应在项目生命周期适配层核对实际取消行为，并独立逐项执行机构安全输出，不把一次 cancelAll/reset 调用当作 STOP 验收完成；不要为此直接修改 vendor 源码。

## 归一化之后再应用输出限制

下面仅展示已计算好的轮输出如何限幅，不假定底盘结构或硬件名：

```java
double denominator=Math.max(1.0,maxAbs(rawOutputs));
for (int i=0;i<rawOutputs.length;i++) {
    outputs[i]=(rawOutputs[i]/denominator)*outputLimit;
}
```

`outputLimit` 应先校验为有限的 `[0,1]` 值，rawOutputs 同样要处理非有限值。例：raw=[2,1]、limit=0.5，先归一化得 [1,0.5] 再限幅为 [0.5,0.25]。若先乘限幅得到 [1,0.5]，再按其最大绝对值归一化，结果仍 [1,0.5]，输出上限被抵消。算法检查不等于轮向、电机映射或实车速度已验证。

## 最小行为验收表

可先用项目已有的纯逻辑 harness 记录事件；机器人侧调试 OpMode 放在 `TeamCode/src/main/java/org/firstinspires/ftc/teamcode/tests/` 并使用匹配 package，不新增 JUnit、src/test 或 src/androidTest。逐步做无动力检查、安全架空测试及受控场地测试，保留输入、事件和输出证据。

| 情景 | 应观察到的证据 | 失败信号 |
| --- | --- | --- |
| 连续两周期输入改变 | 下一 execute 读取新值 | 一直重复构造时输入 |
| D 被 A 抢占后 A 完成 | D.end(true) → A.initialize → A.end(false) → D 恢复 | 同时写资源、D 不恢复或恢复时点不明 |
| 自有多资源 D，其中一项仍忙 | D 等待全部资源空闲 | 抢占非预期的另一资源 |
| vendor 多资源 default | 固定版本策略及应用协调记录 | 假定全资源门控、未解释抢占 |
| initialize 抛错 | 无残余拥有者，触及机构尝试安全清理 | 资源泄漏、下一动作永久被拒绝 |
| callback 内 schedule/cancel | 事件顺序稳定、同次执行 end 最多一次 | 并发修改异常、重复 end |
| 第一项 cleanup 抛错 | 第二项及其后仍执行，原始故障保留 | 后续机构未清理、异常被覆盖 |
| STOP 后旧引用再 tick／提交请求 | 不再 initialize/execute/输出 | default 或待办在 STOP 后复活 |

通过逻辑检查、编译或静态检查只能证明对应阶段；Robot Controller、Driver Station、部署和真机验证分别记录，不能替代。

## 证据、规则状态与软复核

第一方是库维护方，不等于 FIRST official。2026-10-23 读取以下完整 commit（v2.1.1）源码：

- [CommandScheduler.java](https://github.com/FTCLib/FTCLib/blob/98e921bf9eac506c2bbe80a0cddf51ae33f1cbb6/core/src/main/java/com/arcrobotics/ftclib/command/CommandScheduler.java)：`run`、`schedule`、`setDefaultCommand`、`initCommand`、`cancelAll`、`reset`，支持本文对固定实现的有限描述。
- [CommandOpMode.java](https://github.com/FTCLib/FTCLib/blob/98e921bf9eac506c2bbe80a0cddf51ae33f1cbb6/core/src/main/java/com/arcrobotics/ftclib/command/CommandOpMode.java)：`runOpMode`，支持正常生命周期描述，不支持异常全量 cleanup 保证。

本轮已批准的四条规则是 `shared.command-runtime-boundary`、`shared.command-scheduler-lifecycle`、`shared.command-default-resumption`、`shared.command-scheduler-reentrancy`；批准范围为所有队伍／所有赛季／command-based（包含 ftclib-command 继承），只有条件式 soft，没有新硬检查。每个项目仍须用真实上下文调用 resolve/check。触发限定 TeamCode Java 的相关新增行，部分 scheduler 命名路径按触及触发；无新增行的普通 Java 删除／纯重命名可能不触发，改名后的 scheduler 路径可能触发。关键词和路径有漏报、误报，soft 是复核请求，未命中也不能证明安全。

旧 approved 规则没有因本文改状态或扩展赛季；更强的自有运行时政策经本轮真实审批独立加入，不冒充 vendor 保证。FTCLib 安装步骤见[安装指南](../setup/ftclib.md)；当前 SDK 兼容性和项目维护状态需另行核验。
