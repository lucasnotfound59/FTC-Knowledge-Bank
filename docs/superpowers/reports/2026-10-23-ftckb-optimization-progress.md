# FTCKB 持续优化进度

记录日期：2026-10-23。本次管理汇总已冻结，交 doctor 生成最终 52 文件补丁与检查；本 planner 不再修改。后续 UI 最终汇总及主实例更新以 root／reviewer 后续收据为准，执行者不并发覆盖本文件。

## 本批目标

优先补齐 FTCLib 启发、自有 Command 运行时的知识入口；改善新手教程；用实际 CLI、模块测试和来源核对验收。持续优化不意味着为了消耗额度新增无价值功能。

## 已完成的调查（激活前基线）

- 确认主仓库基线 `5b652e8111769bfe259234d8f99e14f62aa549db`；保留用户既有 untracked 文件。
- 环境负责人确认实际主知识库 Dashboard 页面已连接、canonical path 与 48 条规则一致，主 audit 分区可用；validate schema2／ok／48／0 violations。
- planner 实跑两队、两赛季、三主要 profile 与两个简单架构对照，共 14 次 resolve：均退出 0、schema2、无冲突、无 stderr 审计诊断。
- 确认 2025-2026 generic／command-based／ftclib-command 为 26／29／30；2026-2027 都为 17，没有命令规则。不能把过季规则或 ID 中的 candidate 字样当作当前生效判断。
- 核对 FTCLib v2.1.1 完整 SHA `98e921bf9eac506c2bbe80a0cddf51ae33f1cbb6`；固定 vendor 源码可以作为有限技术证据，不是 FIRST official。
- 发现旧 docs.ftclib.org 两个命令页面实时跳转异常，旧安装教程有 profile、测试路径及权威表述需修正；不推断整个项目维护状态。
- 输出 [设计](../specs/2026-10-23-ftclib-inspired-rules-design.md) 与 [实施计划](../plans/2026-10-23-ftclib-inspired-rules.md)。
- 独立强审发现并实测 FTCLib v2.1.1 多资源 default 可抢占其他资源、reset 不调用 cancel/end、cancelAll 多命令可能 CME；planner 已修正自己的 spec／plan，与规则作者的条件式说明对齐。全资源门控仅作为自有 scheduler 政策，vendor 走固定语义核对及应用层适配。

## 本批交付状态

第一批已通过 Astra 最终独立审查，结论 `pass-no-remaining-P1-P2`。审查核对了集成者原始证据及最终文件摘要，本 planner 未将读取证据称为重新执行测试。验收快照覆盖 27 个文件，patch SHA-256 为 `b788b1c9cfdd057163ebc4b82e55fc416548d1b084b9c0bd94487e0b3193ca97`；28 项 Kotlin 测试、5 项 Python 模板测试通过，20 个矩阵上下文无冲突，normal check 为 0 hard／18 soft，18 项均已逐项说明。该 SHA 标识当时验收快照，不代表本进度文件随后更新后的整个工作区。

独立审查收据：`/private/tmp/ftckb-command-area-independent-review-20261023.json`；原始验收目录：`/private/tmp/ftckb-command-area-validation-20261023/`。该候选验收快照时，四条实际候选与审批卡片一致、尚未批准；这段历史不代表下述真实批准后的当前状态。

### 用户授权后的真实批准里程碑

四条 Command 规则已按用户本轮明确授权，由真实 CLI 批准；actor 为 `lucas`，role 为 `overall_software_lead`，四次 approve 均退出 0。范围为所有队伍、所有赛季、command-based（由 ftclib-command 继承）；没有扩大旧三条已批准规则的赛季，也没有复用旧审批。当前知识共 52 条，46 approved／6 candidate。

批准收据为 `/private/tmp/ftckb-command-approval-20261023/approval.json`；激活后矩阵为同目录 `matrix-summary.json`。20 个上下文均无冲突，command-based／ftclib-command 各新增四条 active IDs，generic／simple-opmode／rookiebot 不变；2025-2026 命令两 profile 为 33／34，2026-2027 均为 21。收据记录 stderr 为空；本报告不将无诊断单独解释为完整审计覆盖证明。批准后 tests／fixtures 已更新并完成独立核验，详见 [真实激活验收](2026-10-23-command-rule-activation.md)。

### 冻结时的最终验证与未完成边界

最终 Kotlin 为 123 个不同用例全部通过：批准相关 59，加检索／裁决／候选新旧兼容／Dashboard 补集 64；两组合类不重叠，已独立核对，不能再加上历史候选阶段测试数。最终 Python 为 141 项，其中 140 passed、1 项默认 opt-in skipped（旧 integration 97，staged 20，Dashboard connection 24）。UI 自身 38 项测试是功能证据，不额外累加到 123。综合范围与原始路径见 [分阶段及集成验证报告](2026-10-23-staged-installation-validation.md)。

候选审阅导出已获得独立最终 PASS：真实 6 候选只读导出，批准后的 Command ID 正确返回 not-candidate，默认兼容与来源边界已核验；见 [候选导出复核](/private/tmp/ftckb-candidate-review-export-20261023/reviewer-result.json) 和 [候选导出验收报告](/private/tmp/ftckb-candidate-review-export-20261023/acceptance-report.md)。

staged 安装真实独立全链已 PASS：fresh CLI build 为 13.18 秒，prepare 零 kernel；目标 Dashboard `55231` 的 actual knowledge 页面实际连接并载入 48 条（固定历史 pin，与主库 52 条区分）；正确 accept 退出 0，误用主库服务退出 2 且零 kernel，故意 hard diff 时退出 1 但安装有效。具体命令、浏览器和审计记录见 [staged 验证报告](2026-10-23-staged-installation-validation.md) 与 [真实隔离结果](/private/tmp/ftckb-staged-real-independent-20261023-mo7z12gq/real-demo-results.json)。

UI labels 已完成源码独立审查、38 项相关测试及 9 条规则的真实浏览器验证，见 [UI 验证报告](/private/tmp/ftckb-rule-explanation-sfxw7iel/verification-report.md) 与 [浏览器证据](/private/tmp/ftckb-rule-explanation-sfxw7iel/verification-evidence.json)。UI 最终汇总报告仍等待 Astra；此时不宣称整轮总 PASS。主 `54327` 服务仍在提供旧 UI 资源，虽然源码和当前发行产物已有新标签；root 准备在 UI 最终 PASS 后更新自建恢复实例，不能把新 UI 说成已经在主服务上线。该差别见 [主实例资源核对](/private/tmp/ftckb-main-dashboard-final-resource-check-20261023.json)。

| 工作包 | 当前状态 | 完成条件 |
| --- | --- | --- |
| 方案／计划 | 已实施并通过第一批独立验收 | 保留设计与已验收快照边界 |
| Command 专区指南及四条规则 | 已真实批准、生效，20 矩阵及批准后独立验证通过 | 保留原候选历史与固定来源边界 |
| 新手导航 | 已落地，分阶段流程及真实隔离闭环已验证 | 不将历史目标的 48 条与主库 52 条混报 |
| 外部 Agent 发现与内置检索 | 已落地，相关测试与最终审查通过 | 已接入旧 pin 不自动获得新内容 |
| 正式激活 | 四次 approve、52=46 approved+6 candidate、20 矩阵及相关独立终验已通过 | 不等同机器人部署或真机验证 |
| staged 安装 | 代码、最终 Python 及真实独立全链 PASS | 保持 prepare／accept、诊断及审计证明边界 |
| 候选审阅导出 | 独立最终 PASS | 导出不自动批准，旧默认兼容 |
| UI 原因码／labels | 源码独立审查、38 tests、9 规则真实浏览器验证完成 | 等待 Astra 最终汇总；主 54327 仍旧资源 |

## 后续优先项

1. **完成收口依赖。** 批准后验证、候选导出和 staged 独立全链已完成；当前待 Astra UI 最终汇总与 doctor 最终 52 文件补丁／显式 diff 检查。此管理文件冻结后不再变化，供最终 manifest 与 SHA 固定输入。
2. **保留可复现的新手闭环。** 真实隔离 prepare／实际目标 Dashboard／accept 和 hard 拒绝演示已完成；保留实际 pin、命令、浏览器及审计证据，不把独立 CLI 构建称为机器人编译／部署。
3. **保持候选审阅边界。** 完整导出已独立 PASS；继续区分候选详情、真实批准与实际生效，不把候选输出冒充 kernel v2 或身份认证。
4. **完成 UI 汇总与主实例更新。** 9 条规则的真实页面验证已完成，但仍需 Astra 最终汇总；通过后由 root 更新自建恢复实例并另行核对实际服务资源。主 54327 当前旧资源，不能提前声称上线；doctor 也仍不能猜内核结果。
5. **来源健康可见性。** 旧文档域名跳转异常已是实证；`RuleValidator.validateWebEvidence` 只做格式校验，kernel 按契约不联网。先提供离线可审阅的来源清单和带日期的人工核验结果，使 evidence 声明与访问结果分开；自动联网检查若有用户价值，再单独设计并保持 kernel 离线。

## 下一批：首次安装分阶段 prepare／accept

前期只读调查确认：当时 `integrate.py:206-208` 写完目标后立刻 verify，`verify.py:21-24` 构建并直接运行内核，缺少连接新 actual knowledge Dashboard 的窗口；当时 help 尚无 prepare／accept 参数。这是功能实施前的问题记录，不代表代码落地后的当前入口。

建议保留现默认兼容，新增显式 prepare-only→连接实际 Dashboard／浏览器确认→accept。prepare 停在构建完成与实际上下文摘要；accept 重核摘要／pin／managed／知识／artifact／审计身份后运行内核，不构建或改配置。不保存 token，不新增 daemon／状态仓库；准备状态用确定性摘要交接，丢失输出重跑 prepare。

只读调查形成的 [设计](../specs/2026-10-23-staged-installation-design.md) 与 [计划](../plans/2026-10-23-staged-installation.md) 已由 root 接受并交 doctor 实现；逐阶段诊断和漂移即停的强审反馈已落实。最终 Python 与真实独立全链均已通过，上述 [验证报告](2026-10-23-staged-installation-validation.md) 记录实现、固定输入、真实页面、拒绝路径和适用边界。

## 真实边界

本次平台 QA 不代表队伍机器人批准。BioBuzz 只作只读需求案例；其未提交代码没有成为公开证据或被本仓库修改。新规则没有虚构 approval；本报告不宣称 Driver Station、Control Hub 部署或真机通过。所有 `/private/tmp` JSON 只属于本机本次收据，不提交。
