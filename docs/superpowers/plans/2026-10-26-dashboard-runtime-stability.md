# Dashboard 稳定启动实施计划

> **For agentic workers:** 由 root 调度，resume_integration 是唯一实现/Gradle/服务 owner，resume_review 独立审查；使用 subagent-driven-development 或 executing-plans 跟踪。本 planner 不实施产品代码，不自行 commit。

**Goal:** Agent 推荐入口从完整私有 distribution 副本运行 Dashboard，避免后续 installDist 损坏运行中资源和冷类加载。

**Architecture:** 一个标准库 Python helper负责复制核验、子进程与目录生命周期；prepare/doctor输出该已审阅外部 helper 的绝对 argv，知识库、审计和固定二进制仍来自目标项目。

**Tech Stack:** Python 3.10+、现有 Gradle distribution/POSIX launcher、unittest、隔离真实 JVM Dashboard。

## Global Constraints

- 实施依据：`docs/superpowers/specs/2026-10-26-dashboard-runtime-stability-design.md`；强审通过并由 root 开工后执行。
- 不改 kernel JSON v2、配置版本、Dashboard API、原 CLI 行为或目标 pin；不改真实规则/审批/机器人。
- 新保护入口只承诺 POSIX，Windows明确 unsupported但不能让既有 doctor有效安装新增必需失败；旧CLI行为保留。
- 只管理本次创建的子进程和私有目录；不扫描/停止现有用户服务，不自动构建/升级/后台化。
- 源 distribution 必须构建完成。前后hash核验是漂移检测，不是源码来源或抗同权限ABA证明。
- 本 feat验收与本轮授权commit/push后停止，不再开下一轮优化。

## Task 1：可用的完整副本启动入口

**Files:** 新增 `.agents/skills/ftckb-integrate/scripts/dashboard_runtime.py`、`tests/integration/test_dashboard_runtime.py`。

**接口:** 独立 `main(argv=None)->int`；参数 `--distribution`、`--knowledge` 必填，`--port`/`--no-browser`可选。模块导入不得启动、写文件或检查服务。为后续推荐链提供小型纯 argv 构造函数，使用本模块真实绝对路径，不从target猜helper。

- [ ] 先写失败测试：有效完整分发被复制成独立字节，内部链接/特殊文件/缺launcher或main JAR拒绝；512文件/256MiB限制有界；源改变和copy失败不spawn、不残留。
- [ ] 实现快照前/后源清单与目标摘要比较，唯一0700目录和全量distribution复制；拒绝hardlink/symlink“优化”。保持正常launcher的相对APP_HOME路径。
- [ ] 实现真实canonical knowledge、原cwd/环境、stdout/stderr继承；自身诊断只stderr，不保存URL/token，不宣称已连接。错误退出按spec，不吞掉子进程正常exit。
- [ ] 写并实现生命周期测试：fake launcher保持运行时目录仍在；正常exit/启动失败回收自己的目录；SIGINT/SIGTERM只转发本次PID，有限等待后只处理本次子进程，退出130/143；不确认子进程结束则不清理。
- [ ] 新会话/信号选择避免终端Ctrl-C重复转发；原Gradle POSIX launcher会exec JVM，可通过该已验证关系管理直接PID，不添加端口扫描或通用进程树杀手。
- [ ] 用隔离进程测试SIGKILL遗留边界；测试finally显式回收自己子进程与目录，不写自动GC。路径带空格和非ASCII用argv列表通过。

Run: `PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s tests/integration -p 'test_dashboard_runtime.py' -v`。

验收：helper真正启动并管理副本；任何生产源目录/不属于自己的目录字节不变。此步骤不需要Gradle。

## Task 2：实际推荐argv和旧固定版本兼容

**Files:** `.agents/skills/ftckb-integrate/scripts/installation_workflow.py`（`prepare_steps`）、`project_doctor.py`（Dashboard nextSteps）；扩展 `tests/integration/test_staged_installation.py`、`test_integration.py`。

**接口:** 推荐命令为 `[sys.executable, 实际reviewed helper绝对路径, '--distribution', 目标固定源码distribution, '--knowledge', 目标实际knowledge, '--no-browser']`；cwd与审计env保持实际工作流含义。不新增project.py子命令或新机器契约。

- [ ] 写测试捕获prepare与doctor实际nextSteps：两者都引用当前外部入口旁的helper、实际target dist和knowledge；不再推荐直接mutable launcher。
- [ ] 覆盖旧pin包含Dashboard但不含helper：外部新wrapper输出可执行新helper argv，而二进制仍来自旧target；config/pin/gitlink/managed内容不变。
- [ ] capability未知/无Dashboard时维持无argv语义；新helper缺失或平台不支持给明确说明，不默默退回不受保护argv；doctor旧required checks/overall退出不因可选保护能力失败。
- [ ] 保持prepare只生成建议、不启动、无提前kernel；snapshot不回写原dist，因此prepared-context/accept协议无需变更。
- [ ] 校准doctor cwd/显式audit环境，使相对audit路径不因执行helper而指向副本。不要把Python home推断伪装成JVM默认audit实证；后续仍做identity核验。
- [ ] 调整原Windows nextSteps测试只对新保护边界作真实断言；保留原Windows CLI launcher测试和doctor既有诊断语义，不添加未验证Windows执行分支。

Run: `PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s tests/integration -p 'test_staged_installation.py' -v`，再运行 `test_integration.py` 与 `test_dashboard_connection.py` 对应的同型命令。

验收：执行实际输出argv而非测试重新手写命令；旧target无helper仍走正确reviewed入口，已有单步兼容入口和accept顺序不回归。

## Task 3：正式说明和Agent托管入口一致

**Files:** `README.md`、`AGENTS.md`、`docs/dashboard.md`、`docs/project-integration.md`、`.agents/skills/ftckb-integrate/SKILL.md`、`assets/project-skill/SKILL.md`、`assets/AGENTS.block.md`。其它安装文档仅修正实际仍教直接启动的相邻段落，不扩写新功能。

- [ ] 仓库启动示例使用新helper和实际distribution/knowledge；先复用匹配实例的要求保持，手工启动仍由用户/Agent明确执行。
- [ ] 接入/运行时模板教执行返回的受保护nextSteps；旧pin用外部新wrapper，不调用不存在target helper，不暗升pin。只改模板，不向现有机器人项目写托管文件。
- [ ] 解释保护只属于新入口，源已完成构建、完整副本、POSIX、正常清理与SIGKILL残留边界；直接CLI仍兼容；删除副本不是运行中安全操作。
- [ ] 将故障说明更新为隔离已复现机制，同时保留历史60284唯一原因未知；API 200不等于页面/冷API健康。
- [ ] 保留token私密、真实页面connected、knowledge/audit一致、独立resolve/check与soft报告要求。同步受影响文档断言，不能只写固定字符串通过测试。

验收：从README、prepare、doctor和托管Skill任一正常推荐路径都能到保护入口；未知能力/不支持平台不会伪称受保护。

## Task 4：一次真实隔离验收、强审与发布收尾

**Files:** 正式验收报告可新增 `docs/superpowers/reports/2026-10-26-dashboard-runtime-stability-validation.md`；程序/日志/凭据/截图留 `/private/tmp`，不入提交。

- [ ] owner确认实际主Dashboard仍connected、源码/产物匹配；如需Gradle串行执行本模块构建，不并行改真实运行distribution来实验。
- [ ] 在/tmp复制知识库和distribution，以独立audit目录执行**实际nextSteps helper argv**；正常浏览器确认已连接，核对knowledge/audit identity与三静态摘要。
- [ ] 暖访问仅静态/identity/rules；移走原source distribution后，首次调用真实 `/api/dashboard/resolve` 和 `/api/dashboard/check`（明确上下文、隔离小patch）。必须200且结果有效，审计仍在指定原audit，副本静态字节不变。用此直接验收延迟类加载，不能替换成仅缓存资源单测。
- [ ] 对本次隔离进程核验正常退出/SIGTERM/SIGINT回收，SIGKILL残留由测试明确清理；现有主服务和55231等无关实例不受影响。
- [ ] 最终修改后统一运行受影响Python套件、必要文档测试和 `git diff --check`；不把先前版本通过当本次通过，不重跑无关全仓库套件。
- [ ] resume_review审查最后diff和真实证据；root按强审结论和实际报告验收，任何不足先收敛修复，不开新feat。
- [ ] 用户已明确授权完成后push：integration按显式allowlist包含原本轮授权改动及本feat/正式测试报告，检查暂存diff，排除/tmp、凭据、截图、无关untracked；按仓库约束完成commit/push并核对远端。
- [ ] root报告实际提交/验证和未验证平台，随后停止连续优化。不要把此稳定启动证明扩大为机器人或所有CLI运行保证。
