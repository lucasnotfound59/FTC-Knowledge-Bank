# Dashboard connection verification Implementation Plan

> **For agentic workers:** 唯一 owner 实现，独立 reviewer 验收；不新增 agent，不自动 commit/push，不改前轮 receipt 的既定行为。

**Goal:** 对显式已有 Dashboard 连接核验真实 knowledge/audit 身份，减少复用错库、错分区。

**Architecture:** 独立 Python dashboard verify helper读取私有文件，只 GET 现有服务；新 Kotlin identity route从一次 knowledge snapshot 与实际 auditStore 提供身份。旧命令和 kernel 不变。

**Tech Stack:** Python 3.10+ 标准库 HTTP/JSON、现有安全 Git 与纯配置校验；Kotlin Dashboard HTTP API v1、JUnit、unittest。

## Global Constraints

- 设计 `docs/superpowers/specs/2026-10-23-dashboard-connection-design.md`；Python dashboardConnectionSchemaVersion=1；identity API identitySchemaVersion=1，外层 apiVersion=1。
- `project.py dashboard verify --project PATH --connection-file PRIVATE_JSON`；exit0=必需项匹配，1=失败/未知，64=调用错误；新错误单 JSON。
- 仅显式 loopback GET，无 redirect/proxy/扫描/spawn/kill/upgrade；不保存输出 token，不构建或运行 kernel。
- 旧 doctor/work/kernel/API 兼容，前轮 work 未提交11文件完整保留；本轮新增 delta可追溯，不 commit/push，不改真实 knowledge/pin。
- 现有 Dashboard 60284/session74128由 owner 已核验；复用现有前置，不重复启动或关闭。新编译隔离服务仅用于本轮 demo。

## 文件与 Owner

- Create `.agents/skills/ftckb-integrate/scripts/dashboard_connection.py`：独立 parser/契约、私有输入、期望身份、严格 HTTP 与匹配报告。
- Modify `.agents/skills/ftckb-integrate/scripts/project.py`：首 token dashboard 的延迟分流，旧分支不重写。
- Modify `apps/knowledge-cli/src/main/kotlin/org/ftckb/cli/DashboardServer.kt`：只读 identity GET，单 snapshot 与实际 store 身份；必要小 helper 留在现有 CLI 层。
- Modify 相关现有 Dashboard Kotlin test；`tests/integration/test_integration.py` 或单独 `test_dashboard_connection.py` 只覆盖本轮功能与受影响兼容路径。
- Modify 生成 Skill/AGENTS、接入 Skill、README、docs/project-integration.md、docs/dashboard.md：核验入口与仍需浏览器确认的步骤。
- Owner 实现/测试/演示并记录；reviewer只读；root阅读证据验收。开始状态以 `/private/tmp/ftckb-dashboard-entry-baseline-20261022` 为准。

## Task 1：服务身份 GET 与针对性 CLI 测试

- [ ] 先加 identity 路由测试：无凭据401、foreign Host/Origin 拒绝、非GET405、query拒绝；GET不能改变 rules/events/reviews。
- [ ] 从一次 workspace.snapshot 返回 root/version/fileDigests/valid/ruleCount；fileDigests 不含正文，version保持现有 source framing，不推断规则优先级。
- [ ] 从实际 FileAuditStore.partitionPath 取 audit root/partition 与 health；注入 FileAuditStore 也取其真实路径，自定义非file store返回unknown，不从环境补值。
- [ ] API response沿用外层apiVersion1，新增identitySchemaVersion1；现summary/其他API/kernelJSON不变，route只读不新建日志事件。
- [ ] 测试所有yaml/yml（含guides/ignored）在同snapshot映射内，Unicode/目录路径与知识内容更新一致；symlink保持原workspace拒绝语义。
- [ ] 测试默认/自定义及注入audit路径、非file unknown；确认错误只返回现有安全文案，不泄漏token。

## Task 2：私有输入、项目期望与只读核验

- [ ] 新helper定义独立report与自有parser，未知参数/缺参数JSON64；project.py仅延迟分流，不影响旧命令缺helper时的兼容行为。
- [ ] 读取唯一url字段JSON，上限8KiB、重复键/额外字段拒绝；安全打开普通文件，拒绝symlink/非当前用户/组或其他用户权限；不能验证隐私边界的平台unknown。
- [ ] URL严格为http字面127.0.0.1、合法端口、根路径、唯一token fragment；拒绝DNS/userinfo/query/其他path与host，token仅内存/Authorization header。
- [ ] 本地身份使用纯config/managed校验与doctor安全Git：root、协议、metadata/gitlink/pin/clean、knowledge；不复用旧unsafe Git整链，不引入receipt存储/JDK/CLI构建限制。
- [ ] 按 kernel cwd=projectRoot 解释相对FTCKB_AUDIT_DIR；空白值选定Python home/.ftckb/audit显式期望，不展开~；实现与FileAuditStore一致的canonical及knowledge path分区hash，独立 `kernel.defaultAudit=not_checked`。
- [ ] 扫描本地实际知识范围，拒绝symlink，得到相对路径→SHA256映射；只读文件，不解码或裁决YAML，不重写JVM version算法。
- [ ] 固定GET identity，ProxyHandler禁代理、redirect handler直接拒绝，3秒socket I/O超时（不是整个命令总时限）/1MiB读上限；不把原响应/异常打印，认证失败/不支持/不可达分别固定code。
- [ ] 校验API版本/identity版本/所有类型/摘要格式；仅比较验证过的路径和映射，不从服务路径读取本地文件。audit unknown不能通过。
- [ ] GET后重新捕获本地上下文/映射；变化则失败，代码/文案不承诺抵御同权限ABA。
- [ ] 成功需本地身份、服务认证、knowledge path/files/valid、audit root/partition/health均passed；browser not_checked和binaryPin unknown不冒充通过，也不阻断本次API身份匹配。
- [ ] nextSteps用实际wrapper绝对入口，提供浏览器确认、workflow-env和work resolve cwd/argv/env；同一显式FTCKB_AUDIT_DIR也应用到之后receipt返回的check argv，不造UUID或更改旧work契约。失败仅建议，不自动执行修复。
- [ ] 追加表中测试并保存精确命令/实际数字：

| 场景 | 必须证明 |
| --- | --- |
| 正确连接、knowledge目录/内容不符或无效 | 准确passed/mismatch，非HTTP200即通过 |
| 相对env、默认root、custom store、missing后缀 | expected按project root，actual来自store；unknown不补默认 |
| 旧route/版本/字段缺失 | unsupported/unknown exit1，不能全绿 |
| 401、redirect、proxy、timeout、超大/坏响应 | 固定脱敏code，无外网/重定向请求 |
| 私有文件权限/symlink/重复键/oversize/URL边界 | 拒绝危险输入，无token出现在输出/异常/新增文件 |
| 前后本地配置/pin/知识变化 | 不把不稳定上下文判匹配 |
| 旧doctor/work/kernel相关调用 | 原契约/退出码保持，前轮receipt用例仍通过 |

## Task 3：文档、相关验证与真实隔离 demo

- [ ] 文档保留 doctor→Dashboard页面已连接→work resolve→修改→work check；verify是已有连接的可选机器核验，不代替浏览器步骤或启动器。
- [ ] 给600私有connection文件示例，避免argv/日志token；解释API identity仅库/存储身份，binary pin未证明，自定义store unknown、旧API显式升级/人工核验。
- [ ] 跑本轮Python类与相关兼容用例，相关Dashboard Kotlin tests；然后`:apps:knowledge-cli:installDist`确保新API使用对应产物。只相关模块，不全repo或IDE。
- [ ] 在临时目录建立隔离knowledge与实际audit目录，以新编译CLI显式启动demo服务，私有连接文件不进仓库；保留用户原60284实例。
- [ ] 真实demo依次展示匹配→错误audit期望→错误knowledge/内容→脱敏错误；验证GET不写events/reviews。实际浏览器打开demo页确认连接，并分开报告。
- [ ] owner记录本轮delta相对work baseline、测试/编译证据、demo路径与未验证项；仅清理本轮创建demo服务，不关闭用户服务，私有token不写报告。
- [ ] reviewer只读最终diff与证据，owner修必修后重跑受影响测试/关键demo；root阅读验收。前轮receipt和原untracked不丢失，本轮不commitpush。
