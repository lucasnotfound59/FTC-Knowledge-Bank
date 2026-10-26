# Dashboard 稳定启动设计

状态：root 已接受完整 distribution 快照入口方向；实现前由 resume_review 独立强审。仅完成本 feat 和本轮已授权交付后停止。

## 问题与实证

当前 Dashboard 每次静态请求从运行 JAR 读资源。完全隔离的 Gradle 9.4.0 `installDist` 实验确认：覆盖保持同 inode，替换 JAR 合法且 CRC 通过，但旧服务的三个静态资源变为 500；预热 identity/rules 仍为 200，首次 resolve 因 `DashboardServer$Trace` 的 `NoClassDefFoundError` 断连。JDK 21/25 同 JVM 探针另捕获 ZIP offset/EOF 异常。

证据在 `/private/tmp/ftckb-dashboard-runtime-20261026/` 的 `reproduction-evidence.json`、`probe-evidence.json`、`gradle-install/evidence.json`。这是已复现机制，不追认历史 60284 故障的唯一原因。只缓存静态资源不能解决已证明的冷类加载故障。

## 选择与边界

新增 Python 标准库启动入口，在启动 JVM 前把完整 distribution 复制至本次独占目录，从副本运行并监督本次子进程。它避免原 distribution 后续替换、删除导致本进程类路径损坏。直接 `ftckb dashboard` 保持兼容，但不获得透明保护。暂不做 JVM re-exec、Gradle 发布重构、共享缓存、自动升级或服务管理平台。

保证针对正常 Gradle FTCKB distribution、已完成构建的源和经此入口启动的服务；不证明源构建来源、任意自定义 launcher/外部 JVM agent 路径、同时构建中的全局原子一致性，或人为删除运行副本后的可用性。JVM 可以延迟加载其它依赖，因此必须复制完整 distribution，不能仅复制主 JAR 或三个资源。

## 入口契约

新增 `.agents/skills/ftckb-integrate/scripts/dashboard_runtime.py`，命令：

```text
python3 /reviewed/checkout/.agents/skills/ftckb-integrate/scripts/dashboard_runtime.py --distribution DIR --knowledge DIR [--port N] [--no-browser]
```

- `--distribution`、`--knowledge` 必填；参数不接受缩写，port 保持 0–65535。知识库使用实际 canonical path，不复制知识库或审计目录。
- 支持 Python 3.10+ 的 POSIX（macOS/Linux）。Windows 的新入口明确 unsupported；原 CLI、原 doctor 必需检查/退出含义不变，不加入令既有有效安装失败的新必需检查，不声称 Windows 原生验证。
- 不构建、不 fetch、不改 pin、配置、托管文件、规则或已有实例；不扫描端口、不后台化、不自动复用或关闭别人的服务。
- 保留调用 cwd 与环境；`FTCKB_AUDIT_DIR` 不改指向快照。推荐流程传明确审计环境；实际库、审计身份和页面连接仍由已有流程核验。
- 子进程 stdout/stderr 原样继承。wrapper 不提前输出 `dashboard=ok`、不生成或保存 token；其自身诊断只走 stderr，包含可识别的本次快照目录，不打印环境或凭据。
- 正常结束透传 CLI 退出码；参数错误 64，快照/启动/平台错误 2。SIGINT/SIGTERM 转发给本次拥有的子进程，有限等待后只结束该子进程；wrapper 对信号返回 130/143。不得按进程名或端口批量终止。

## 快照和生命周期

1. 源目录完成构建后，检查完整 distribution 的普通文件清单，拒绝内部 symlink、特殊文件和缺失 launcher/main JAR；有界遍历（沿用 512 文件、256 MiB 量级）。
2. 创建唯一的私有临时目录（POSIX 0700），复制整个 distribution，流式计算文件摘要；比较复制前源清单、复制后源清单和副本摘要。可观察到漂移或复制失败时不启动 JVM，清理自己未启动的目录。此核验不宣称防止同权限 ABA 或暂停于中间状态的构建。
3. 从副本 `bin/ftckb` 启动 Dashboard，保持生成 launcher 的 `APP_HOME/lib` 相对关系；源目录此后移走不影响副本类路径。不得用 hardlink 或 symlink 代替独立字节副本。
4. 子进程存活时保留快照。正常退出、处理信号退出和启动失败后，仅清理本次创建且确认拥有的目录；子进程未确认退出时保留目录并报告。
5. SIGKILL/断电可能留下子进程和目录；不自动扫描、猜测或回收。文档给出通过本次路径/PID核验后人工清理的边界，不声称无残留。正常任务收尾仍保留用户需要的 Dashboard。

## Agent 推荐链及旧 pin

- `installation_workflow.prepare_steps` 和 `project_doctor.diagnose` 的 Dashboard nextSteps 都改用当前已审阅 wrapper **旁边实际存在的 helper 绝对路径**，传目标固定源码的实际 distribution/knowledge；不调用旧 target 中不存在的 helper。
- capability unknown/absent 仍不给猜测 argv；helper 不可用或平台不受保护时给明确说明，不把不受保护启动冒充推荐保护路径。doctor 的既有 overall 诊断与必需项不因此无故失败。
- prepare 仍只返回建议，绝不自动启动；其 context 摘要继续绑定原 distribution，不改 acceptance 协议。helper 只复制，因此不使准备输入自行漂移。
- 旧 pin 已有 Dashboard 时，可由新外部 helper 保护其**旧版本二进制**；无 Dashboard 时仍需明确升级决定。外部 helper 不替换目标模板、规则、JAR 或 source.commit。
- README、仓库 AGENTS、接入 Skill、运行时 Skill 模板和 `assets/AGENTS.block.md` 统一教用户先复用匹配实例，否则执行返回的受保护 argv。已有机器人项目的托管文件不直接改写；模板只随未来显式安装/升级生成。
- 更新 `docs/dashboard.md` 和接入文档中实际启动说明；token、浏览器确认、audit identity 和 resolve/check 分阶段证据含义保持原样。

## 验收

- Python 单元/集成测试覆盖完整副本、无链接、源漂移拒绝、复制/启动失败清理、子进程退出与信号、自己目录之外不清理，以及旧 pin/外部 wrapper/unsupported 推荐语义。
- 真实隔离演示：通过 helper 启动副本，先 GET 静态/identity/rules；随后移走原 source distribution，再首次 POST resolve 和 check（临时知识库、明确上下文、空或小 patch），所有响应正确且审计仍写原临时 audit 目录。确认副本资源摘要不变，实际页面已连接。
- 独立验证 SIGTERM/SIGINT 的子进程结束和目录清理；SIGKILL 仅在隔离测试中验证不误清活跃副本，最后由测试显式回收自己 PID/目录。
- 测试真实 nextSteps argv可执行，外部新 wrapper + 旧无 helper pin 不找错路径；原直接 CLI、doctor必需项、prepare/accept无提前内核、Windows旧调用保持既有边界。
- 不重跑无关全仓库套件，不改真实规则/审批/机器人。由唯一 Gradle/服务 owner 排程实际构建与演示，独立 reviewer 按最后版本验收。

用户已明确授权本轮完成后 commit/push。由 integration 在最终验收后用显式 allowlist 提交原本轮授权改动与本 feat，排除 `/tmp` 演示、token、截图和无关文件；随后 root 停止连续优化，不再开新 feat。
