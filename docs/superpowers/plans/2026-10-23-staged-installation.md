# 分阶段首次接入实施计划草案

> **For agentic workers:** 这是交 root／强审的计划草案；root 接受后分配一个实现 owner，另一位 reviewer 只读验收。使用 subagent-driven-development 或 executing-plans 跟踪任务，本 planner 不实施 installer 代码。

**Goal:** 首次接入在内核运行前提供连接目标实际 Dashboard 的明确窗口，同时保持现有默认安装与验收兼容。

**Architecture:** 新增显式 prepare-only 和 accept 两个可选阶段；以确定性上下文 hash 交接，不新增状态存储。复用现有 pin／managed／knowledge／Dashboard 验证；accept 不构建，连接与原始内核输出分开报告。

**Tech Stack:** Python integration wrapper、现有 Dashboard identity API、kernel JSON v2、unittest／临时 Git fixture。

## Global Constraints

- 本计划只在 root 接受设计并确定实现 owner 后执行；当前没有这些新 flag。
- 无新参数的 integrate／verify 调用与 programmatic `integrate(args)`／`verify(root)` 保持兼容，原配置和 kernel 协议均不升级。
- prepare 可以安装与构建，不能跑 validate／resolve／check；accept 可以连接已知 loopback API及跑内核，不能构建／fetch／改 pin或托管文件。
- 未连接目标 canonical knowledge 的 Dashboard，不进入内核。浏览器实际连接需操作者核验；flag 只记录声明，不冒充机器证明。
- 不保存 token、不猜端口、不扫描／启动／关闭服务，不修改真实机器人仓库，不 commit/push。

## 文件边界

实现 owner 可写：`.agents/skills/ftckb-integrate/scripts/integrate.py`、`verify.py`、新增 `installation_workflow.py`；最小复用调整限 `dashboard_connection.py` 及必要的 `project.py` 可选参数；测试新增 `tests/integration/test_staged_installation.py` 并按需扩展现有 integration fixtures。不改现有规则 YAML／ApprovalPolicy／profiles。

文档 owner 在功能及 help 落地后更新 `.agents/skills/ftckb-integrate/SKILL.md`、运行时 Skill 模板、`docs/project-integration.md`／`docs/getting-started.md`。如前批 owner 仍在编辑，root 先移交明确 ownership；不并发覆盖。

### Task 1：准备阶段与上下文交接

- [ ] 在现有临时 FTC Git fixture 中添加显式阶段测试；用 call log／mock 证明 prepare 不调用 kernel，不创建 work receipt，不启动 Dashboard。原 Args 对象没有新属性时仍走旧默认分支。
- [ ] 在 `integrate.py` 增加实际 `--prepare-only` parser 和末尾分支；沿用同一安装 preflight／write_files，禁止复制另一套更宽松安装逻辑。无 flag 时仍调用原 verify。
- [ ] 新 helper 负责准备后的只读 capture、必要 CLI 构建及产物观察；复用 pin／managed 安全校验。部分构建缺 main JAR 时不能仅凭旧 stamp 返回 prepared；保持默认 build_cli 的旧调用兼容。
- [ ] 实现 canonical preparation context hash。字段按 spec；流式读取固定 distribution／知识输入，限制遍历范围，拒绝 symlink。timestamp 不进 digest，不写配置或新安装收据。
- [ ] 输出独立 workflow schema、stage、未执行内核／验收状态、实际 cwd／argv／env。只给参数已齐全的命令可执行 argv；尚未知 connection-file 时给输入要求和真实摘要，不伪造路径或输出不完整可执行命令。成功不得包含 installationOk=true。dry-run 不生成真实 digest、不写目标或构建。

验收：prepare 首次和重复执行均只发生授权文件／gitlink副作用；目标 HEAD／分支／用户 staged与unstaged内容保留；改模板、错误pin、重复markers和symlink仍拒绝。

### Task 2：连接后的显式验收

- [ ] 将 `dashboard_connection.py` 当前逻辑提取为返回 `(exitCode,payload)` 的内部 callable，保留原 main 输出与退出码；或用同等小的已验证复用接口，禁止复制绕过私有文件／URL安全校验。
- [ ] `verify.py` 新 `--accept` 模式必须同时接受显式准备摘要、私有连接文件与浏览器确认声明；默认 `verify(root)` 保持原行为。只返回状态，不伪造输入文件或URL。
- [ ] 验收先复核完整准备 context，再核验已知 Dashboard 的真实知识与审计身份；失败时 kernel call log必须为空。参数／能力／prepared-context漂移使用可区分错误。
- [ ] 在同一显式 FTCKB_AUDIT_DIR 下调用既有 kernel adapter 的 validate／resolve／check；必要的 env 可选参数缺省时保持旧行为。每一条内核命令前后分别 capture／核对 context：前置漂移不执行该命令，后置漂移立即停止后续命令，并保留实际已执行的 JSON／exit／diagnostics；不能三阶段全部跑完才比较。
- [ ] 为显式 accept 保留每阶段有界、脱敏 stderr diagnostics，不能因 `project.kernel` 成功返回而丢弃 stderr。记录存在／截断／省略，隐藏 token、私有 URL、控制字符及无法安全脱敏的原文。逐阶段区分审计失败所致不完整与未获证明的未知；Dashboard health 或 kernel exit0 不能证明独立内核进程已成功写审计。kernel JSON／exit 及旧默认调用保持原样。
- [ ] accept 不调用 build_cli。缺 artifact、stamp不符或准备摘要失效给可执行 prepare 恢复提示；不能重写配置、模板、pin或新建 work receipt。
- [ ] 输出完整 soft／hard 与安装状态。硬违规仍可 installationOk=true／exit1；加载／冲突失败不能算验收通过；浏览器声明及 binary pin未知保持明确。

验收：正确连接成功；上游同内容但不同 canonical path仍失败；wrong audit、旧API、401、私有文件／URL边界沿用现有失败含义。无明示阶段的旧verify输出保持兼容。

### Task 3：防漂移与恢复矩阵

- [ ] 每项独立改变配置原字节、team／season／profile、sourcecommit／indexgitlink／URL、managed内容、ignored YAML、知识路径、launcher／lib／stamp及auditRoot，确认 accept在内核前拒绝。
- [ ] 验证普通 TeamCode diff不使 prepare摘要过期，accept检查当前diff；不要把它错误绑定到整个机器人工作树。
- [ ] 验证构建失败后文件保留且可用同参数prepare重试；丢输出后重跑prepare可重新取得摘要；更改pin须仍显式ref，不能跟踪tag／main。
- [ ] 验证dry-run+prepare-only byte-for-byte不改目标files／index／Git config；验证非阶段安装、v1保留／显式v2升级、profile保留、managed保护的旧用例继续通过。
- [ ] 验证未连接、错误connection或缺浏览器确认声明时没有validate／resolve／check；确认没有token进入stdout／stderr／nextSteps／配置。
- [ ] 在 validate 返回后改动准备上下文，断言 resolve／check 没有被调用；在 resolve 返回后改动，断言 check 没有被调用；每例保留已执行阶段事实，不伪装成三阶段完成。
- [ ] 使用 Dashboard health 正常且 kernel exit0 的 fixture 注入审计写失败 stderr，断言阶段诊断被安全保留、审计为不完整、原 kernel JSON／exit 不变。覆盖无诊断仍不证明完整审计、超长诊断截断、token／私有 URL 脱敏及旧默认返回兼容。

测试入口：`python3 -m unittest discover -s tests/integration -p 'test_staged_installation.py'`；随后运行现有 `python3 -m unittest discover -s tests/integration -p 'test_integration.py'` 和 `python3 -m unittest discover -s tests/integration -p 'test_dashboard_connection.py'`。work receipt测试目前也在前一个文件中，不另造不存在的文件名。文档不能宣称尚未创建的测试已通过。

### Task 4：真实隔离演示与文档

- [ ] 在临时最小FTC仓库执行实际dry-run→prepare；查看实际call log／审计，确认没有提前内核记录。保留完整SHA和实际team／season／profile，不拿示例队号当真实机器人。
- [ ] 使用明确新建的演示Dashboard连接prepare输出的actualknowledge；打开页面确认连接，再执行authenticated identity核验与accept。现有用户服务保持运行。
- [ ] 以另一个上游knowledge实例展示reject；以简单硬违规diff展示installationOk=true与projectCheck=false／exit1；记录每个soft，不声称机器人部署／真机通过。
- [ ] 功能／help与相关测试通过后，文档改用实际返回argv。解释旧单步兼容模式、prepared非accepted、私有连接文件、明确audit env以及恢复，不继续教授违反前置顺序的首次安装流程。
- [ ] 由独立强审检查阶段顺序、防漂移、默认兼容与token边界；root按最后一版相关suite和真实演示验收，不因候选审批等待停止本批工具工作。
