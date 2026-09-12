# 两队规则全局化与 TeamChina 写作标准迁移台账

决定日期：2026-09-11。实施记录时间：2026-09-12T05:43:33Z。
本台账记录用户已复核的全局范围决定，不把旧队伍审批追溯改写为全局审批。
依据：[已批准设计](../superpowers/specs/2026-09-11-team-rules-global-teamchina-design.md)。

## 范围、状态与审批

8 条旧队伍规则迁移为 8 条 global 规则，另新增 3 条命令式标准；总数从 43 到 46，approved 从 37 到 40，candidate 保持 6。
所有 11 条规则使用 YAML v4、authority=shared、policyLevel=global、teams=[]、seasons=["2025-2026"]。
generic 指 applicability.profiles=[]，并非名为 generic 的 profile；3 条命令标准使用 profiles=[command-based]。
候选的目录、ID 和策略层级改变不构成转正，2 条迁移候选没有 approval，也不进入 activeRules。
旧 team YAML 删除，不保留可再次加载的副本；历史可通过本台账与 Git 恢复。

9 条当前 approved global 规则统一使用本次新记录：
approver="lucasnotfound59"，role=overall_software_lead，approvedAt=2026-09-12T05:43:33Z，无 team 字段。
这记录的是 2026-09-11 的 global-scope 用户授权在本次实施中的新 UTC 审批元数据，
不是 2026-08-27 曾经批准了全球范围，也不是作者、机器人或库厂商的背书。
同一维护者的 overall_software_lead 身份沿用仓库已有审批记录，不创造新的身份。

| 旧 ID | 新 ID | 状态 | Profile |
| --- | --- | --- | --- |
| `team-20827.hardware-layer-candidate` | `global.hardware-access-candidate` | candidate（无审批） | generic |
| `team-20827.hardware-container` | `global.hardware-container` | approved（新全局审批） | generic |
| `team-20827.motor-init-safety` | `global.motor-configuration` | approved（新全局审批） | generic |
| `team-20827.constants-centralized` | `global.constants-centralized` | approved（新全局审批） | generic |
| `team-20827.chinese-javadoc` | `global.documentation-intent` | approved（新全局审批） | generic |
| `team-20827.telemetry-multiple` | `global.telemetry-organization` | approved（新全局审批） | generic |
| `team-20827.naming-conventions` | `global.naming-conventions` | approved（新全局审批） | generic |
| `team-16093.fsm-candidate` | `global.mechanism-state-machine-candidate` | candidate（无审批） | generic |
| 新增 TeamChina 标准 | `global.command-responsibilities` | approved（新全局审批） | command-based |
| 新增 TeamChina 标准 | `global.command-live-input` | approved（新全局审批） | command-based |
| 新增 TeamChina 标准 | `global.command-requirements-cleanup` | approved（新全局审批） | command-based |

### 旧审批完整保留

下表逐条保留原 20827 的 6 次审批。原 authority=team、teams=["20827"]、seasons=["2025-2026"]；
表中的 team_software_lead 是旧角色，不是新 YAML 的全局审批角色。

| 旧规则 ID | approver | role | team | 原 approvedAt |
| --- | --- | --- | --- | --- |
| `team-20827.hardware-container` | lucasnotfound59 | team_software_lead | 20827 | 2026-08-27T11:09:36.390654Z |
| `team-20827.motor-init-safety` | lucasnotfound59 | team_software_lead | 20827 | 2026-08-27T11:09:36.722832Z |
| `team-20827.constants-centralized` | lucasnotfound59 | team_software_lead | 20827 | 2026-08-27T11:09:37.064018Z |
| `team-20827.chinese-javadoc` | lucasnotfound59 | team_software_lead | 20827 | 2026-08-27T11:09:37.403584Z |
| `team-20827.telemetry-multiple` | lucasnotfound59 | team_software_lead | 20827 | 2026-08-27T11:09:37.746027Z |
| `team-20827.naming-conventions` | lucasnotfound59 | team_software_lead | 20827 | 2026-08-27T11:09:38.091448Z |

原 team-20827.hardware-layer-candidate 与 team-16093.fsm-candidate 没有 approval；迁移后仍然没有。
其原 topic 分别为 hardware-access 与 mechanism-state-management，保持不变。
approved hardware-container 保留独立 topic；不为去重而伪造 topic 或合并候选审批。

## 四项已确认冲突及最终决定

| 主题 | 旧规则 | TeamChina 复核观察 | 最终指令及理由 |
| --- | --- | --- | --- |
| 硬件访问 | team-20827.hardware-container：子系统仅接收 Hardwares | 同一固定提交包含旧式容器及较新直接接收 HardwareMap 的组织方式 | **明确例外：保留 Hardwares 容器**，按 Sensors、Motors、Servos 分组，子系统不直接从 HardwareMap 获取设备。用户本次明确决定优先于来源写法。 |
| 成员命名 | team-20827.naming-conventions：电机字段强制 m 前缀 | Hardwares 使用表达机构含义的命名 | global.naming-conventions 采用语义 lowerCamelCase，不强制 m 前缀；类用 PascalCase。本赛季自动类继续用 &lt;Side&gt;&lt;Color&gt;[Mini] 与 Base，不外推未来赛季。 |
| 电机初始化 | team-20827.motor-init-safety：init() 中统一 BRAKE | Shooter.init 使用按机构选择的零功率行为，来源各机构配置位置不必相同 | global.motor-configuration 要求初始化阶段明确方向、运行模式和 ZeroPowerBehavior，按机构选择 BRAKE 或 FLOAT，不要求名为 init 的方法或统一 BRAKE。 |
| 注释与遥测格式 | team-20827.chinese-javadoc 固定 Javadoc/分节，team-20827.telemetry-multiple 固定分隔符 | Intake 与 TeleOpSolo 的组织方式不构成统一格式模板 | global.documentation-intent 解释用途、单位、范围、安全理由；global.telemetry-organization 在已使用 Dashboard 时组合 Driver Station 与 Dashboard 并按机构/用途组织数据，不强制固定模板、分隔符或安装 Dashboard。 |

TeamChina 优先仅用于这里已复核的写作标准冲突，不降低官方 API、比赛规则或安全约束。
硬件访问例外是明确批准的项目规范，不能描述成 TeamChina 所有类均遵循它。
保留旧 symbol 只是为了追溯：例如旧 m 前缀 symbol 不表示当前仍强制该前缀。

## 非冲突与未推广内容

- FSM 与常量集中不是对立选择：FSM 表达多步骤状态，常量集中表达参数，两者可并存。
  global.mechanism-state-machine-candidate 仍是待批准候选，不要求全项目采用 FSM，也不与命令架构自动捆绑。
- global.constants-centralized 保留集中维护参数的原则，接受 enum、static final 或配置对象；
  20827 与 TeamChina 的具体存放方式不同，不构成必须二选一的冲突。
- 只提炼职责、实时输入与资源清理：Command 控制 SubsystemBase 时声明 requirements，
  连续手柄输入在执行时通过 Supplier 读取，持续输出在结束或中断时恢复安全状态。
  不从 TeamChina 的框架使用推断 generic 项目必须安装 FTCLib 或 SolversLib。
- 未导入来源机器人的设备名称、实际方向选择、功率数值、舵机位置、路径或比赛策略。
  这些依赖接线、机械结构与标定，不能作为跨队伍已验证参数；证据 symbol 不是配置建议。
- 不添加宽泛 regex；这 11 条没有 checks，仅提供 soft 指导，代码审查与真机验证仍是独立门禁。

## 既有架构规则仅收窄范围

knowledge/shared/practices/rookiebot-tutorial.yaml 的 12 条 shared.rookiebot-* 规则：
仅升级为 v4、policyLevel=local、profiles=[rookiebot]，保留 authority=shared、teams=[]、seasons=[]，
以及所有 ID、instruction、rationale、status、examples、证据与审批。
原 approver="lucasnotfound59"、role=overall_software_lead、approvedAt=2026-09-06T16:03:37Z 全部不变。

knowledge/shared/rules.yaml 的 shared.ftclib-command-candidate 虽然 ID 含 candidate，但原 status 已是 approved，仍然 approved。
仅升级为 v4、policyLevel=shared、profiles=[ftclib-command]，保留 seasons=["2025-2026"] 与原文字、证据。
原 approver="lucasnotfound59"、role=overall_software_lead、approvedAt=2026-09-06T03:06:57.152813Z 不变。
v1 Git evidence 添加显式 type=git 只是 v4 表示要求，不更改仓库、commit、文件或定位。

这些不是新审批，也不是 global 提权；只是让已写明的架构适用范围被机器执行。
generic 排除全部 12 条 RookieBot 规则及 FTCLib 规则；rookiebot 归一化包含 simple-opmode；
ftclib-command 归一化包含 command-based，得到 FTCLib 规则与 3 条通用命令标准，但不得到 RookieBot 规则。

## 固定来源与精确定位

以下均固定完整 commit，不使用滚动 master。旧证据的 repository、commit、file、symbol 与 line 全部保留；
TeamChina 新证据只记录已指定的文件与 symbol，不臆造行号。

### 原队伍证据

- `team-20827.hardware-layer-candidate` → [xiaokai-lyk/FTC20827-2026Decode / TeamCode/src/main/java/org/firstinspires/ftc/teamcode/Hardwares.java](https://github.com/xiaokai-lyk/FTC20827-2026Decode/blob/118c28e137334bbbea510d77f1fa384e8b1b5779/TeamCode/src/main/java/org/firstinspires/ftc/teamcode/Hardwares.java)，commit=`118c28e137334bbbea510d77f1fa384e8b1b5779`，symbol=`Hardwares`。
- `team-20827.hardware-container` → [xiaokai-lyk/FTC20827-2026Decode / TeamCode/src/main/java/org/firstinspires/ftc/teamcode/Hardwares.java](https://github.com/xiaokai-lyk/FTC20827-2026Decode/blob/118c28e137334bbbea510d77f1fa384e8b1b5779/TeamCode/src/main/java/org/firstinspires/ftc/teamcode/Hardwares.java#L17)，commit=`118c28e137334bbbea510d77f1fa384e8b1b5779`，symbol=`Hardwares`，line=17。
- `team-20827.motor-init-safety` → [xiaokai-lyk/FTC20827-2026Decode / TeamCode/src/main/java/org/firstinspires/ftc/teamcode/subsystems/Intake.java](https://github.com/xiaokai-lyk/FTC20827-2026Decode/blob/118c28e137334bbbea510d77f1fa384e8b1b5779/TeamCode/src/main/java/org/firstinspires/ftc/teamcode/subsystems/Intake.java#L40)，commit=`118c28e137334bbbea510d77f1fa384e8b1b5779`，symbol=`setZeroPowerBehavior`，line=40。
- `team-20827.constants-centralized` → [xiaokai-lyk/FTC20827-2026Decode / TeamCode/src/main/java/org/firstinspires/ftc/teamcode/pedroPathing/Constants.java](https://github.com/xiaokai-lyk/FTC20827-2026Decode/blob/118c28e137334bbbea510d77f1fa384e8b1b5779/TeamCode/src/main/java/org/firstinspires/ftc/teamcode/pedroPathing/Constants.java#L17)，commit=`118c28e137334bbbea510d77f1fa384e8b1b5779`，symbol=`Constants`，line=17。
- `team-20827.chinese-javadoc` → [xiaokai-lyk/FTC20827-2026Decode / TeamCode/src/main/java/org/firstinspires/ftc/teamcode/subsystems/Intake.java](https://github.com/xiaokai-lyk/FTC20827-2026Decode/blob/118c28e137334bbbea510d77f1fa384e8b1b5779/TeamCode/src/main/java/org/firstinspires/ftc/teamcode/subsystems/Intake.java#L18)，commit=`118c28e137334bbbea510d77f1fa384e8b1b5779`，symbol=`Intake`，line=18。
- `team-20827.telemetry-multiple` → [xiaokai-lyk/FTC20827-2026Decode / TeamCode/src/main/java/org/firstinspires/ftc/teamcode/teleops/TeleOpBase.java](https://github.com/xiaokai-lyk/FTC20827-2026Decode/blob/118c28e137334bbbea510d77f1fa384e8b1b5779/TeamCode/src/main/java/org/firstinspires/ftc/teamcode/teleops/TeleOpBase.java#L55)，commit=`118c28e137334bbbea510d77f1fa384e8b1b5779`，symbol=`MultipleTelemetry`，line=55。
- `team-20827.naming-conventions` → [xiaokai-lyk/FTC20827-2026Decode / TeamCode/src/main/java/org/firstinspires/ftc/teamcode/Hardwares.java](https://github.com/xiaokai-lyk/FTC20827-2026Decode/blob/118c28e137334bbbea510d77f1fa384e8b1b5779/TeamCode/src/main/java/org/firstinspires/ftc/teamcode/Hardwares.java#L48)，commit=`118c28e137334bbbea510d77f1fa384e8b1b5779`，symbol=`mLeftFront`，line=48。
- `team-20827.naming-conventions` → [xiaokai-lyk/FTC20827-2026Decode / TeamCode/src/main/java/org/firstinspires/ftc/teamcode/autos/TopAutoBase.java](https://github.com/xiaokai-lyk/FTC20827-2026Decode/blob/118c28e137334bbbea510d77f1fa384e8b1b5779/TeamCode/src/main/java/org/firstinspires/ftc/teamcode/autos/TopAutoBase.java#L25)，commit=`118c28e137334bbbea510d77f1fa384e8b1b5779`，symbol=`TopAutoBase`，line=25。
- `team-16093.fsm-candidate` → [tqdmye/FTC2026-16093National / TeamCode/src/main/java/org/firstinspires/ftc/teamcode/Subsystems/shooter/ShooterFSM.java](https://github.com/tqdmye/FTC2026-16093National/blob/3e6de8944081ef347fbb76b2f97c89b89b10b669/TeamCode/src/main/java/org/firstinspires/ftc/teamcode/Subsystems/shooter/ShooterFSM.java)，commit=`3e6de8944081ef347fbb76b2f97c89b89b10b669`，symbol=`ShooterFSM`。

### TeamChina 新证据

- `global.hardware-container` → [Hardwares.java:Hardwares](https://github.com/OLeslieO/FGC2026-TeamChina/blob/9be3eb7776f35d71e60cec4cb47be6bd7892acee/TeamCode/src/main/java/org/firstinspires/ftc/teamcode/Hardwares.java)，repository=`OLeslieO/FGC2026-TeamChina`，commit=`9be3eb7776f35d71e60cec4cb47be6bd7892acee`，symbol=`Hardwares`。
- `global.motor-configuration` → [subsystems/Shooter.java:init](https://github.com/OLeslieO/FGC2026-TeamChina/blob/9be3eb7776f35d71e60cec4cb47be6bd7892acee/TeamCode/src/main/java/org/firstinspires/ftc/teamcode/subsystems/Shooter.java)，repository=`OLeslieO/FGC2026-TeamChina`，commit=`9be3eb7776f35d71e60cec4cb47be6bd7892acee`，symbol=`init`。
- `global.constants-centralized` → [subsystems/Constants.java:Constants](https://github.com/OLeslieO/FGC2026-TeamChina/blob/9be3eb7776f35d71e60cec4cb47be6bd7892acee/TeamCode/src/main/java/org/firstinspires/ftc/teamcode/subsystems/Constants.java)，repository=`OLeslieO/FGC2026-TeamChina`，commit=`9be3eb7776f35d71e60cec4cb47be6bd7892acee`，symbol=`Constants`。
- `global.documentation-intent` → [subsystems/Intake.java:Intake](https://github.com/OLeslieO/FGC2026-TeamChina/blob/9be3eb7776f35d71e60cec4cb47be6bd7892acee/TeamCode/src/main/java/org/firstinspires/ftc/teamcode/subsystems/Intake.java)，repository=`OLeslieO/FGC2026-TeamChina`，commit=`9be3eb7776f35d71e60cec4cb47be6bd7892acee`，symbol=`Intake`。
- `global.telemetry-organization` → [opmodes/TeleOpSolo.java:TeleOpSolo](https://github.com/OLeslieO/FGC2026-TeamChina/blob/9be3eb7776f35d71e60cec4cb47be6bd7892acee/TeamCode/src/main/java/org/firstinspires/ftc/teamcode/opmodes/TeleOpSolo.java)，repository=`OLeslieO/FGC2026-TeamChina`，commit=`9be3eb7776f35d71e60cec4cb47be6bd7892acee`，symbol=`TeleOpSolo`。
- `global.naming-conventions` → [Hardwares.java:Hardwares](https://github.com/OLeslieO/FGC2026-TeamChina/blob/9be3eb7776f35d71e60cec4cb47be6bd7892acee/TeamCode/src/main/java/org/firstinspires/ftc/teamcode/Hardwares.java)，repository=`OLeslieO/FGC2026-TeamChina`，commit=`9be3eb7776f35d71e60cec4cb47be6bd7892acee`，symbol=`Hardwares`。
- `global.command-responsibilities` → [opmodes/TeleOpSolo.java:TeleOpSolo](https://github.com/OLeslieO/FGC2026-TeamChina/blob/9be3eb7776f35d71e60cec4cb47be6bd7892acee/TeamCode/src/main/java/org/firstinspires/ftc/teamcode/opmodes/TeleOpSolo.java)，repository=`OLeslieO/FGC2026-TeamChina`，commit=`9be3eb7776f35d71e60cec4cb47be6bd7892acee`，symbol=`TeleOpSolo`。
- `global.command-responsibilities` → [subsystems/Intake.java:Intake](https://github.com/OLeslieO/FGC2026-TeamChina/blob/9be3eb7776f35d71e60cec4cb47be6bd7892acee/TeamCode/src/main/java/org/firstinspires/ftc/teamcode/subsystems/Intake.java)，repository=`OLeslieO/FGC2026-TeamChina`，commit=`9be3eb7776f35d71e60cec4cb47be6bd7892acee`，symbol=`Intake`。
- `global.command-live-input` → [commands/DriveCommand.java:DriveCommand](https://github.com/OLeslieO/FGC2026-TeamChina/blob/9be3eb7776f35d71e60cec4cb47be6bd7892acee/TeamCode/src/main/java/org/firstinspires/ftc/teamcode/commands/DriveCommand.java)，repository=`OLeslieO/FGC2026-TeamChina`，commit=`9be3eb7776f35d71e60cec4cb47be6bd7892acee`，symbol=`DriveCommand`。
- `global.command-requirements-cleanup` → [commands/DriveCommand.java:DriveCommand](https://github.com/OLeslieO/FGC2026-TeamChina/blob/9be3eb7776f35d71e60cec4cb47be6bd7892acee/TeamCode/src/main/java/org/firstinspires/ftc/teamcode/commands/DriveCommand.java)，repository=`OLeslieO/FGC2026-TeamChina`，commit=`9be3eb7776f35d71e60cec4cb47be6bd7892acee`，symbol=`DriveCommand`。
- `global.command-requirements-cleanup` → [commands/DriveCommand.java:end](https://github.com/OLeslieO/FGC2026-TeamChina/blob/9be3eb7776f35d71e60cec4cb47be6bd7892acee/TeamCode/src/main/java/org/firstinspires/ftc/teamcode/commands/DriveCommand.java)，repository=`OLeslieO/FGC2026-TeamChina`，commit=`9be3eb7776f35d71e60cec4cb47be6bd7892acee`，symbol=`end`。

## 验证边界

验收通过 FileKnowledgeRepository 与 RuleResolver 验证真实知识目录；CLI validate/resolve 验证 JSON 计数、profile 与无冲突。
这不等于 Robot Controller、Driver Station、IDE 交互、来源机器人或真机安全验证，也不扩大赛季审批。
