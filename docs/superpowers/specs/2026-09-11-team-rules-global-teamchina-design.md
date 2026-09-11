# 两队规则全局化与 TeamChina 写作标准设计

日期：2026-09-11。状态：用户已复核通过；实施计划见 `docs/superpowers/plans/2026-09-11-global-teamchina-rules.md`。

## 1. 目标与边界

本次把 `knowledge/teams/20827/` 与 `knowledge/teams/16093/` 中的规则整理为对所有队伍可见的 global 规则，并从 TeamChina 仓库提炼可复用的 FTC Java 写作标准。

TeamChina 证据固定到提交 `9be3eb7776f35d71e60cec4cb47be6bd7892acee`，不跟踪滚动的 `master`。只提炼代码组织与安全表达方式，不复制设备名称、方向、功率、舵机位置或比赛策略。SDK 自带 README、示例和仓库中偶然出现的不一致格式不作为团队标准。

本次实现 global 规则必需的 v4 格式和 profile 匹配，并只迁移与架构隔离直接相关的 12 条 RookieBot 规则及 1 条 FTCLib 规则，使它们不再对所有项目生效；不改这些规则的内容或状态，不改 Limelight reviewTriggers、安装进度或网络超时，也不升级 Pedro Pathing。官方和安全规则不因 TeamChina 写法而降级。

## 2. 已确认的冲突决定

| 主题 | 原队伍规则 | TeamChina 观察 | 最终决定 |
| --- | --- | --- | --- |
| 硬件访问 | 通过 `Hardwares` 容器集中访问，子系统不直接使用 `HardwareMap` | 新式 `*Subsystem` 直接接收 `HardwareMap`，旧式类仍接收 `Hardwares` | 保留现有 `Hardwares` 标准；这是用户明确指定的例外 |
| 成员命名 | 电机字段必须使用 `m` 前缀 | 主要使用 `leftDrive`、`shooterLeft` 等语义化 lowerCamelCase | 合并 TeamChina 写法，取消强制 `m` 前缀 |
| 电机初始化 | 所有电机在 `init()` 中使用 `BRAKE` | 射轮使用 `FLOAT`，送料等机构使用 `BRAKE`；有些配置位于构造函数 | 合并 TeamChina 写法：必须明确方向、模式与零功率行为，但按机构选择，不强制全部 `BRAKE` 或固定放在 `init()` |
| 注释与遥测格式 | 中文 Javadoc、固定分节、`---` 遥测分隔 | 两套代码并不统一，部分保留、部分省略 | 合并为语义要求：关键类、单位、边界和设计原因应清楚，不强制固定模板与分隔符 |

“TeamChina 优先”只用于写作标准冲突。上述硬件容器例外以用户本次明确决定为准；官方 API、比赛规则和安全约束仍具有更高权威。

## 3. 采用的实现方案

采用“最小完整 global 闭环”，而不是只移动 YAML：

1. 在 YAML codec 中接通现有 domain 模型的 `policyLevel`、`applicability.profiles` 和 `reviewTriggers` 字段，并保留旧 schema 的确定性映射。
2. Resolver 规范化 profile 后再判断适用范围，并使用 `RulePolicy.level(rule)` 裁决同 topic 规则，顺序固定为 `OFFICIAL > GLOBAL > LOCAL > SHARED`。
3. 新增通用 `command-based` profile；`ftclib-command` 归一化时同时具有 `command-based`，而 `simple-opmode` 与任意 command profile 互斥。TeamChina 规则不冒充 FTCLib 或 SolversLib 的通用官方规范。
4. 冲突输出表达有效层级，不能继续把冲突等同于单一 `authority`。
5. CLI、JSON contract、fixtures 和直接消费 Resolver 的入口同步更新；不支持新契约时失败闭合。
6. 新增 `knowledge/global/`，其中规则使用 shared 来源和 global 策略层级。目录名称本身不决定优先级，YAML 字段才是机器事实。

旧 v1-v3 规则继续按来源映射：official→global、team→local、shared→shared。新写 global 规则使用 YAML schemaVersion 4，并显式填写 `policyLevel`、teams、seasons、profiles；核心 JSON 契约升级到 schemaVersion 2。v4 是此前架构设计已预留的版本，不能把新语义塞入旧 v3。

## 4. 两队规则迁移

两队当前共有 8 条规则：20827 的 7 条与 16093 的 1 条。迁移遵循以下原则：

- 去除 team 限制，但第一轮保留 `2025-2026` 赛季范围，避免把尚未跨季复核的经验描述成永久有效。
- 保留每条规则的来源 URL、固定 commit、文件和 symbol；迁移后可以追溯来自哪个队伍。
- 6 条 approved 的旧 team-lead 审批完整写入迁移说明；YAML 中改为本次 global 范围的新审批记录。仓库已将同一维护者 `lucasnotfound59` 记录为 overall software lead，因此本次用户明确授权可作为新的 global 审批决定；不得复用 2026-08-27 的时间冒充当时已经批准了全局范围。
- 2 条 candidate 继续为 candidate，不因移动目录或提高 policyLevel 自动转正。
- 语义重叠的 20827 `hardware-layer-candidate`（topic=`hardware-access`）与 approved `hardware-container`（topic=`hardware-container`）仍保留原 topic 和状态：candidate 作为历史候选，approved 规则承载实际标准；不为了去重而伪造 topic 或审批历史。
- 原复合命名规则拆分为通用 Java 命名与赛季自动类命名时，必须保留原证据和状态；不借拆分扩大适用范围。

迁移后 team 目录不保留第二份可加载规则，避免重复 ID。若需要说明历史位置，使用 Markdown 迁移说明而不是重复 YAML。

## 5. TeamChina 规则修订与新增

新增规则应保持窄范围，并将观察写成可以复核的指令：

- 新增：命令式项目中，OpMode 负责生命周期与输入绑定，Subsystem 负责机构能力，Command 负责协调动作。
- 新增：连续变化的手柄值通过 `DoubleSupplier`／`BooleanSupplier` 等实时读取，避免在命令创建时把输入快照写死。
- 新增：控制 `SubsystemBase` 的 `CommandBase` 显式声明 requirements；持续输出命令在结束或中断时停止机构。
- 修订原 naming 规则：字段与方法使用表达机构语义的 lowerCamelCase；类使用 PascalCase，不要求匈牙利式 `m` 前缀。
- 修订原 motor-init 规则：电机配置必须明确且可解释；`BRAKE`、`FLOAT`、方向和运行模式取决于机构，不从示例机器人复制数值。
- 修订原 constants 规则：参数集中并使用能表达用途或单位的名称；允许 enum、`static final` 或合适的配置对象，不强制单一常量容器形式。
- 修订原 documentation 规则：注释优先说明机构意图、单位、范围和不明显的安全理由；不强制每个类使用相同分节模板。
- 修订原 telemetry 规则：使用 Dashboard 时组合 Driver Station 与 Dashboard 遥测，并组织关键数据，但不强制安装 Dashboard 或使用固定分隔符。

前三条只适用于 `command-based` profile；修订规则适用于本赛季所有队伍。它们都使用本次新的 overall-lead 审批记录。没有可靠静态分析的规则只输出 soft 指导，不添加为了“能过检查”而设计的宽泛 regex。

## 6. 审批、版本和可解释性

- `authority` 表示来源与审批责任，`policyLevel` 表示冲突时的策略层级，两者保持独立。
- global 提权属于范围变化。迁移记录保留原 team-lead 审批，当前 YAML 记录本次新的 overall-lead 审批；两次审批的语义和日期不能混写。
- 本次用户决定记录在迁移文档和 Git 历史中；审批者 ID 与角色只使用仓库已有身份记录，不生成新的姓名、职位或签名。
- 同 topic 同最高有效层级有多个 approved 规则时，Resolver 返回冲突并以退出码 2 失败，不能依赖文件顺序或 TeamChina URL 偷偷选中。
- TeamChina 优先结果另写入冲突记录，至少包含旧规则 ID、固定源码位置、冲突内容和最终决定。
- 这是 feature 版本，README 版本更新为 `V0.4.0`；测试数和规则数只能在实际测试后更新。
- 两队规则迁移不改变总数，新增 3 条命令式规则；若没有其他规则同时变化，预期从 43 条变为 46 条。最终 approved／candidate 快照以验证结果为准。

## 7. 验证与交付

至少验证：

1. domain 单元测试覆盖四级优先级、同级冲突、candidate 排除和旧规则兼容。
2. codec 测试覆盖新字段、未知字段、缺失字段及 v1-v3 兼容读取。
3. profile 测试覆盖 `command-based`、FTCLib 归一化、simple-opmode 互斥及 generic 项目排除命令规则。
4. CLI fixtures 覆盖 global 覆盖 local、official 覆盖 global，以及冲突 JSON。
5. `ftckb validate knowledge --json` 成功；两个队号在同一赛季 resolve 到相同的 global 写作规则，candidate 不进入 activeRules。
6. 全部相关 Gradle/Python 测试、`git diff --check` 与工作区 `ftckb check`。

软件检查通过不描述成机器人、Driver Station 或真机验证通过。提交只包含本功能相关文件，不包含已有 `.DS_Store`、网站目录或其他未跟踪文件；未经新的明确授权不 push。
