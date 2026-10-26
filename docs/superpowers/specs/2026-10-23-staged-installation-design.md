# 分阶段首次接入：prepare／accept 草案

日期：2026-10-23。状态：只读调查后的设计草案，交 root／独立 reviewer 审核；本文件未实现任何新 flag。用户已授权持续改进，现有安装器默认兼容是本批硬要求。

## 实际问题

`integrate.py:194-208` 在完成 submodule、pin、托管文件后，立即调用 `verify(root)`；`verify.py:18-24` 核对接入、构建 CLI，随后直接运行 validate／resolve／check。现场 `--help` 也确认当前没有准备或分阶段验收入口。

新安装的实际 knowledge 只有安装到目标 submodule 后才存在；当前流程不给操作者先连接该 canonical path 的 Dashboard 再运行内核的窗口。先连接上游 checkout 的 Dashboard 不能替代它。`project_doctor.py:264-265` 有意不执行 Dashboard 连接／kernel 检查；`dashboard_connection.py:289-298` 也明确 API 身份通过不等于浏览器页面已连接，只返回后续调用建议。这些边界应保留。

## 方案与推荐

| 方案 | 行为 | 取舍 |
| --- | --- | --- |
| A：显式 prepare／accept，使用确定性上下文摘要交接（推荐） | 安装并准备 CLI 后停止；输出实际路径、未验收状态与摘要；连接实际 Dashboard 后，accept 重核摘要及服务身份，再执行内核 | 不新增状态仓库、daemon 或 token 持久化；丢失输出时重新 prepare；可检查观察到的输入漂移 |
| B：写 Git metadata 的安装 UUID 收据，再 accept | 类似 work receipt 的独立安装收据 | 可列出历史 prepare，但增加存储、清理、恢复与安全测试；本次不需要历史列表，暂不采用 |

选择 A 作为待审建议。现有 `integrate.py`、`verify.py` 无新 flag 的默认行为、返回及退出码保留；新手文档在实现和验收后推荐显式分阶段流程。旧单步命令仍可用于兼容自动化，但不能描述成首次安装中已经满足 Dashboard-before-rule。

## 公开入口提案

- `integrate.py --prepare-only`：沿用现有明确 team／season／ref／profile 与 dry-run 契约，执行所选 pin 的接入、托管文件和 CLI 准备；不调用 validate／resolve／check，不创建 work receipt，不启动／扫描／关闭 Dashboard。
- `verify.py --accept --prepared-context-sha256 HASH --connection-file PRIVATE_JSON --browser-confirmed`：只接受先前实际输出的摘要；复核安装、CLI 产物、实际 Dashboard 与同一审计环境，然后验收。accept 不构建、不 fetch、不重写配置／pin／托管文件。
- `--browser-confirmed` 是操作者已实际观察页面连接的明确声明，不是机器证明或身份认证。API 身份检查独立记录，服务 binary pin 仍为 unknown。尚未实际观察页面连接时，Agent 不得设置此参数。
- 新参数必须先实现并加入实际 help／测试，再更新用户文档；不能把本草案的命令当成当前可用入口。当前已审阅外部 checkout 的绝对入口可以处理旧目标，不向旧 pin 的不存在命令发送新 flag。

## prepare 的边界与输出

沿用现有 preflight、明确版本、配置原选择、managedFiles hash、Git 副作用和不 commit/push 的边界。prepare 允许显式构建独立 CLI；构建不是内核裁决，也不要求 Android SDK。构建失败保留已安装文件，明确阶段及恢复动作；不回滚用户文件。

准备完毕后检查实际 pin／gitlink／clean source／托管内容，再观察匹配 pin 的构建 stamp、当前平台 launcher、主 JAR 和 distribution lib 的实际内容摘要。摘要只证明本次观察到的产物身份，不把旧 stamp 或 hash 等同完整编译来源证明。缺主 JAR 等部分构建状态不得因旧 stamp 跳过而标 prepared；需要重新构建或给出明确修复提示。

输出独立 `installationWorkflowSchemaVersion:1`、command=prepare、ok、stage、context、preparedContextSha256、kernelStatus=not_performed、acceptanceStatus=not_performed、nextSteps。准备成功 stage=prepared／exit0，**不输出 installationOk=true**；不把准备成功写成验收失败，installationOk 可完全省略。参数错误 exit64；准备／构建错误 exit2，保留实际阶段。

nextSteps 为输入已齐全的命令返回实际绝对 cwd／argv，并输出显式 `FTCKB_AUDIT_DIR`，包括实际 knowledge 的 Dashboard 启动建议。连接核验与 accept 在 prepare 时尚无私有连接文件路径，应返回所需输入说明及实际准备摘要，不能将缺参数的 argv 或占位路径标成可执行命令；操作者提供真实私有文件路径并确认页面连接后才组成完整命令。没有已知端口就不生成 URL，不生成 token 或不存在的连接文件。prepare 不把建议启动当服务成功。

## 确定性 preparedContextSha256

以 version=1 的 canonical JSON（UTF-8、sorted keys、紧凑分隔、无 timestamp）计算 SHA-256，至少包含：canonical projectRoot／实际 Git directory；配置原字节 hash；pin 的 repository／commit／实际 sourcePath／index gitlink；已验证 managed 内容 hash；canonical knowledgePath 与全部实际小写 `.yaml/.yml` 输入的相对路径→内容摘要；构建 stamp 和实际执行 distribution 的 launcher／lib 摘要；canonical 显式 auditRoot／partition。team／season／原 profiles 可显示于 context，但其身份仍受配置原字节 hash 约束。

复用现有安全 capture／safe path／只读 Git 规则，拒绝 symlink 和无法确定的输入；对分布文件做有界流式哈希，不跟随额外路径。摘要不包含 token、连接 URL、个人 SDK 路径或时间；不写 `.ftckb/project.yaml`、不增加配置协议字段、不复用 work receipt schema。

操作者把实际返回摘要传给 accept；新摘要不是身份认证。同权限操作者可以重新 prepare，工具只报告观察到的输入变更，不宣称抵御中途改回的 ABA 或恶意同权限篡改。普通 TeamCode 工作区变更不使准备摘要失效，accept 的 check 仍检查当时真实 diff。

## accept 顺序与状态

1. 检查参数及浏览器确认声明，安全读取现有项目；不构建。缺准备所需 artifact／stamp 返回 setup_required；无准备摘要或格式错误为 usage。
2. 重算准备上下文，与显式摘要精确比较；config 原字节、pin／gitlink、managed、知识输入、构建产物或 auditRoot 变化返回 preparation_changed，不执行任何内核，不静默接受新版本。
3. 复用现有私有 connection-file 读取和 authenticated GET；要求实际 knowledgePath、完整 YAML 摘要、valid 与 audit root／partition／health 相同。上游服务、错误分区、旧 API、401、重定向、不安全文件或不可达均阻止内核。连接服务 binary pin 依旧不能由 API 证明。
4. 用刚核验的显式 auditRoot 环境执行 validate→resolve→check。每次命令只使用当前已核对 pin、真实 team／season／profile 和 normal；不生成新 test／dev 豁免。**每一条内核命令执行前和执行后都重新 capture 并核对准备上下文**，不是全部命令结束后才比较；命令前观察到漂移则不执行该命令，命令后观察到漂移则停止后续命令，保留已执行命令的真实 JSON、退出码及诊断，不把部分成功当整体验收。
5. 完成验收后保留既有含义：check exit1 可表示 installationOk=true 且 projectCheck.ok=false，完整返回所有 soft／violations；加载／冲突／上下文错误 exit2 且 installationOk=false。安装有效不代表代码合规或真机通过，robotValidation=not-performed。

新阶段输出使用独立 wrapper schema；kernel JSON v2 原样嵌入，不改 kernel 字段、退出码或配置三项 v2 版本。accept 显式允许且只允许 loopback 身份 GET；不扩展成 daemon/auth 平台。

accept 必须逐阶段保留并暴露有界、脱敏的 stderr diagnostics，包括退出 0 的命令；当前 `project.kernel` 的成功路径丢弃 stderr，不能直接沿用其二元返回而遗失审计失败信息。原文先限长，再处理凭据、token／私有 URL 及控制字符；无法安全脱敏的原文不回显，只报告存在未展示诊断，并明确截断或省略。此能力只用于显式新 accept 路径，保持 kernel JSON／退出码及旧默认调用行为。

Dashboard health 只反映服务进程观察到的存储状态，不证明独立 validate／resolve／check 进程已成功写审计；CLI 审计写入失败也可能仍返回 kernel exit0。逐阶段审计状态必须能表示已观察到失败的不完整、以及未获写入证明的未知；无 stderr 也不能单独证明全部审计成功。Dashboard API 匹配和 kernel0 可以分别报告，不能据此声称全流程审计覆盖。

## 恢复与版本边界

- dry-run+prepare-only 只给计划，保持目标 files／index／Git config 原字节不变；不构建、不存阶段、不输出真实准备摘要。
- prepare 成功但未连接：保留文件与 staged gitlink，正常连接实际服务后继续；不自动重新安装或运行内核。
- 构建失败或输出丢失：以相同明确参数重跑 prepare，沿用现有 idempotency 和 managed 保护；没有用户改动时不跟踪 tag／main、不改变分支、不覆盖托管文件。构建失败不伪造 stamp。
- 已准备项目发生 pin／配置／managed／知识／artifact 漂移：accept 拒绝；先审阅变更，再显式 prepare 生成新摘要，不替换用户内容或凭空“恢复”。
- 接入 v1／旧 pin 的保留与显式升级政策不变；缺 Dashboard API 或必要 capability 时明确 unknown／unsupported，不改 pin、不跳过身份核验。所选 pin 的模板仍来自所选源码，不使用外部新模板替换旧模板。
- 不增加 repository release tag、不改项目协议／kernel schema。新 wrapper feature 是否可用由实际已审阅入口及 help 证明，不从 README 版本猜测。

## 验收

保留原 integration／verify 回归；新增零 kernel-before-accept 的调用轨迹、prepare 幂等／构建失败重试／dry-run 不变性、每项上下文漂移、错误上游 Dashboard／审计分区、accept 不构建／不改配置、浏览器声明边界、硬违规与安装成功的分离、实际 fake kernel 和一次真实隔离新手流程。特别验证 validate 后漂移不再调用 resolve／check、resolve 后漂移不再调用 check；验证 Dashboard health 正常但 kernel exit0 携审计失败诊断时，原 JSON／exit 保持且审计显示不完整。诊断脱敏、截断及旧默认兼容必须覆盖。测试只用临时机器人 fixture，不改 BioBuzz、不动现有用户服务。
