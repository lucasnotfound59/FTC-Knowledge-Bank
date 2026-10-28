# 本地知识库管理台

第一次使用可先读[新手指南](getting-started.md)。Dashboard 供人需要时查看规则、已有审计或人工复核。AI 接入和编码直接走 CLI，不依赖启动、连接、截图或浏览器确认，也不自动操作管理台。

`ftckb dashboard` 管理启动时指定的知识库，提供规则编辑、候选审批、裁决解释、调用审计及逐项复核。它不加载模型配置、不要求模型 API key；`ftckb serve` 是另一个带 Ask/Edit 的聊天服务，仍需要配置模型。

## 启动与访问

先检查并复用实际知识库和审计目录一致的已有实例。需要新实例时，在仓库根目录显式完成构建，再从完整 distribution 的私有副本启动（Python 3.10+，POSIX macOS/Linux）：

```bash
./gradlew :apps:knowledge-cli:installDist
python3 .agents/skills/ftckb-integrate/scripts/dashboard_runtime.py \
  --distribution apps/knowledge-cli/build/install/ftckb --knowledge knowledge
```

默认分配空闲端口并尝试打开浏览器。只打印链接、自行打开：

```bash
python3 .agents/skills/ftckb-integrate/scripts/dashboard_runtime.py \
  --distribution apps/knowledge-cli/build/install/ftckb \
  --knowledge knowledge --port 8080 --no-browser
```

在人明确要求打开管理台的接入项目中，用已审阅 checkout 的 `dashboard_runtime.py` helper，显式传目标旧 pin 的完整 distribution 与 canonical knowledge，并设置与 CLI 相同的 `FTCKB_AUDIT_DIR`。旧 pin 不必包含 helper，不升级 pin；默认 doctor/prepare 的 `nextSteps` 只引导 CLI 验收与编码。缺 helper 或平台不支持时不声称受保护。Windows 不支持新保护入口，原 CLI 仍保持兼容。

监听地址固定为 `127.0.0.1`。终端输出 `dashboard=ok` 及含 `#token=` 的本次启动链接；页面读取 token 后从地址栏移除，只保存在当前页面内存中。数据 API 都要求 token，写操作还校验同源请求。若刷新浏览器后提示缺少凭据，重新打开终端输出的完整链接；页面内的刷新按钮会保留凭据。停止终端中的服务可用 `Ctrl+C`。

已有知识库必须可读取。外部修改导致 YAML 无效时，页面显示当前错误，不能把旧快照当作最新有效结果。页面可见时约每两秒检查更新；失联时保留最后数据并标记过期。

## 页面打不开，但 API 仍正常

API 健康与页面可用要分别确认。曾实际出现 `/api/dashboard/identity` 和规则接口返回 200，但 `/`、`/app.js`、`/styles.css` 返回 500 的情况；此时不能仅凭身份核验成功宣称 Dashboard 已连接。先查看页面与静态资源响应、浏览器错误及服务终端日志，再确认知识库和审计身份。

隔离的真实 Gradle `installDist` 实验已复现：原地更新 distribution 后，旧服务的静态资源返回 500，预热 identity/rules 仍返回 200，首次 resolve 因冷类加载 `NoClassDefFoundError` 失败。这证明了可复现的运行文件替换机制，不能追认历史 60284 故障的唯一根因；仅缓存静态资源不足以覆盖冷类加载。

需要恢复时，先确认实际 knowledge 和 audit，保留已有用户实例；完成构建后通过上述入口显式启动替代实例。恢复后重新确认页面及静态资源可访问、浏览器实际已连接、知识库路径和规则目录正确，并核验实际审计目录；有私有连接文件时更新为已验证的新连接。服务文件副本和 API 返回 200 都不能代替这些确认。含 token 的完整链接继续只私下保存。

## 稳定副本与生命周期

`dashboard_runtime.py` 使用 Python 标准库，核对构建完成的完整 distribution 文件清单和摘要，复制 `bin`、`lib` 等全部普通文件至本次独占的 0700 临时目录，从副本 launcher 启动；拒绝内部链接、特殊文件、缺失 launcher/main JAR 和可观察的复制漂移。它不构建、不联网、不改配置/pin，不复制 knowledge 或 audit，保留调用 cwd 和环境。仅复制主 JAR、静态资源或使用 hardlink/symlink 不能获得同样保护。

保护适用于经此入口启动的正常 FTCKB distribution，避免源产物后续替换或删除破坏运行文件；不证明构建来源、并发构建的全局原子一致性、同权限 ABA 或自定义 launcher/外部 JVM agent 路径。必须等源构建完成再启动；运行中不得删除副本。直接 `ftckb dashboard` 保持原行为，不会透明获得快照保护。

helper 原样继承子进程 stdout/stderr，自身只向 stderr 报告本次快照路径，不提前输出 `dashboard=ok` 或保存 token。子进程正常结束后清理本次私有目录；`Ctrl+C`/SIGINT、SIGTERM 只处理本次 child，确认退出后清理；未确认退出则保留目录并报告。SIGKILL、断电可能留下子进程与目录，没有自动扫描或回收。确需人工清理时，先用本次诊断路径/PID 核对所有权并确认子进程已退出，再只删除该次目录，不能按端口/进程名批量结束用户实例。正常任务收尾仍保留用户需要的 Dashboard。

## 核验一个已有连接

仅当人明确要求核验既有管理台时，使用以下独立只读入口，减少复用错误知识库或审计分区：

```bash
python3 /path/to/reviewed-FTC-Knowledge-Bank/.agents/skills/ftckb-integrate/scripts/project.py dashboard verify --project /path/to/FTC-project --connection-file /private/path/dashboard.json
```

先在当前用户私有位置创建文件（POSIX创建前设置`umask 077`，并确认文件为0600或更严格），用编辑器保存唯一字段 `{"url":"http://127.0.0.1:PORT/#token=TOKEN"}`。占位须替换为本次实际启动链接；不要把实际token放argv、仓库、报告或共享日志。文件上限8KiB，拒绝重复/额外字段、symlink、非普通类型、非当前用户及组/其他权限；无法核验所需隐私的平台明确unknown。

核验只GET显式指定的字面127.0.0.1 HTTP根地址，token仅放Authorization header；不走环境代理、不重定向、3秒socket I/O超时（不是整个命令总时限）、响应上限1MiB。不扫描端口、不启动/关闭实例、不自动构建/改pin，也不执行kernel或写审计记录。旧pin缺此脚本时用已审阅新checkout的绝对入口，目标pin不变。

受现有token/Host/Origin保护的`GET /api/dashboard/identity`返回Dashboard API v1内独立`identitySchemaVersion:1`。knowledge的root/version/valid/ruleCount/fileDigests来自同一次源文件快照，映射覆盖所有小写yaml/yml（含ignored输入），只有路径和摘要，没有正文；Python不重写JVM版本算法。audit root/partition/health来自实际绑定的FileAuditStore，含注入对象；其它自定义store为unknown，不能从env补默认目录。identity GET不变更rules/events/reviews，已有summary及kernel JSON v2不变。

独立报告`dashboardConnectionSchemaVersion:1`退出0仅表示本次authenticated API的实际库/存储匹配，退出1包括不匹配/不支持/不可达/失效凭据/隐私未知，退出64为参数错误。仍须打开私下完整链接确认页面已连接；API响应不证明页面状态、服务binary pin或构建来源。旧服务缺identity路由时明确unsupported-api，继续保留该服务，由用户显式选择支持的实例/升级或人工核验，不自动重启。

本次expected audit根由非空白FTCKB_AUDIT_DIR选定（相对值按未来kernel cwd即项目根解析，不展开~），否则本工具显式选择Python home下`.ftckb/audit`；不据此声称无env的JVM user.home已核验。匹配后nextSteps提供workflow-env和准确外部wrapper work resolve argv；同一显式FTCKB_AUDIT_DIR也必须用于随后收据返回的check argv，不凭空生成UUID。正常 AI 顺序是 doctor→work resolve→修改→同一 receipt 的 work check，无需此核验。

本地前后快照能发现观察到的漂移，但不是抵御同权限改回原值（ABA）的事务，也不是身份认证、审批、语义或真机安全保证。

## 规则与候选审批

1. 在「规则管理」中搜索 ID、标题或正文，按状态、来源、策略层级、适用范围及检查类型筛选。详情分别展示 `authority`、`policyLevel`、`applicability`，以及证据、批准记录、检查和源文件；文件夹与 ID 前缀不能代替这些字段。
2. 新增规则先建立 candidate。编辑器接受一条规则的完整 YAML 文档，包含 `schemaVersion: 4` 和 `rules` 序列；普通编辑不允许修改 ID，也不允许直接把状态改成 approved。新候选的文件路径由服务端生成。
3. 点击「预览差异与校验」，检查完整知识库的错误、修改前后内容，以及可选上下文下的裁决影响。只有可应用的预览才能保存。预览十分钟后失效；任一规则文件发生外部修改后需要重新预览。
4. 保存只替换目标规则所在文件，保留其他规则内容；路径越界、symlink、旧版本预览和校验失败都会阻止保存。修改已批准规则的实质内容或归属会清除旧 approval，并转回 candidate，必须重新批准。
5. 对候选选择「批准候选」或「拒绝候选」，明确填写批准人 ID、角色和适用队伍，再预览并应用。操作不会自动 commit 或 push。

角色检查沿用 ApprovalPolicy：official/shared 来源要求 `overall_software_lead`；team 来源要求 `team_software_lead`，且声明队伍必须等于该规则唯一适用队伍。该检查验证提交的角色声明，不验证操作者身份。候选不参与生效裁决。

## 查看裁决与手动检查补丁

在「归属与裁决」中填写真实 team、season，并显式选择 generic 或命名 profile。generic 表示空 profile 集合；没有完整上下文时，只看规则目录，不推测哪些规则生效。

结果由现有 RuleResolver 生成，展示 active、excluded 及原因、overridden 及 winner IDs，以及同主题最高有效层级的冲突。优先级为 `OFFICIAL > GLOBAL > LOCAL > SHARED`；official 由来源取得最高级，冲突不会交给前端或模型猜胜者。

页面的补丁检测检查手动粘贴的 unified diff，不读取任意本地项目路径。需要检查工作区暂存、未暂存和未忽略的新文件时，继续使用 CLI：

```bash
ftckb check <repo-root> --knowledge <knowledge-root> --team <真实队号> --season <YYYY-YYYY> --generic-profile --json
```

使用项目真实 profile；不要把示例的 generic 当成自动推断。默认工作模式为 normal。只有用户明确要求当前为测试或 dev 任务时，才选择 test/dev；它们不保存为项目默认。test 继续执行安全和目录等硬检查；dev 对明确给出的补丁保留每一条降级提醒。soft 表示需要复核，dev 通过不等于符合正式规则。

## 逐项复核检查提醒

「复核」页面从实际执行过规范器的审计阶段列出 soft 与 dev 降级提醒。默认「待复核」，也可选「全部」「已记录结论」；每页数量仅代表当前筛选返回范围，不是全部事项统计。审计详情的对应命中可直接跳转。普通 soft 若没有位置，显示「规则级提醒；原检测未提供精确位置」，不会从文字猜代码行。即使原检查因 hard 退出 1，其中可校验的 soft 仍可逐项复核；hard 不会被复核解除。

选择一项后，填写「已核对」（`verified`）、「需修改」（`needs_changes`）或「本次不适用」（`not_applicable`），填写复核人及具体理由，预览完整声明，再保存。复核人最多 120 字符，理由最多 4000 字符；证据链接可空，最多 8 条，每条最多 2048 字符。姓名是操作者身份声明，不是实名或角色认证。未提供链接会如实标记；链接只允许完整 http(s) URL，禁止账号密码和敏感凭据参数，不抓取内容，点击后以独立页面打开。服务端会遮盖姓名和理由中疑似凭据，界面展示最终保存内容。

每次保存追加一个 revision，改判保留全部历史；没有批量通过。原检查存在 `events.jsonl`，独立声明存在相同知识分区的 `reviews.jsonl`，使用 `reviews.lock` 协调追加与并发。保存复核不改变原审计、不新增 diff 检测统计。仅独立复核声明能说明指定历史快照有人声明复核，不能证明规则已经批准、机器合规、当前工作区已验证或 RC/DS/真机安全。

网络中断或存储失败会保留当前页面内的草稿；同一提交重试保留 UUID 与内容。修改草稿会生成新提交。两个页面同时修改同一 revision 时，后提交收到冲突，保留草稿并读取最新历史，核对后重新预览保存。草稿只在当前页面内存中，关闭或重新加载页面会丢失未保存内容；已持久化的历史可在刷新或重启服务后读取。来源缺失、未完成、失败、摘要不符或日志健康异常时禁止写入，仍展示可读历史，不将不完整来源算作完成复核。

版本状态独立显示为「历史快照」（`snapshot`）、「需重新复核」（`stale`）或「版本未知」（`unknown`）。版本摘要来自实际检查输入，日志不保存补丁或代码正文。后续可比较检查输入或知识版本变化时，旧声明标为需重新复核；新检测默认待复核，不继承旧结论。旧记录缺版本时，可对完整、可校验的历史提醒记录声明，但始终显示「历史复核，版本未知」，不计为当前代码结论；填写已核对/本次不适用后可进入已记录结论，不会永久占据默认待办。不同格式的知识版本、未完整建立的索引或无法比较的来源保持未知。工作区输入摘要采用有界内存采集；仓库较大或预算不足时保留原检查行为，但不生成可比较的代码版本，并显示版本未知，后续不能据此判定陈旧。

**代码修改后重新运行检查，新检查需重新复核。** 管理台不会监控外部修改，也不会根据日志中的路径读取仓库。只有管理台原先手动提供的 `supplied_patch` 检查可选择「重新检查此补丁」建立前后关联；历史不保存正文，须重新粘贴本次补丁、核对上下文后提交。CLI 工作区、CLI 指定补丁和 chat/serve 编辑预览按各自实际来源关联，不互相冒充；未重跑检查时无法观察外部编辑。

复核接口属于 Dashboard API v1：`GET /api/dashboard/reviews`（`filter=pending|all|recorded` 与分页 cursor）、`GET /api/dashboard/reviews/detail?itemId=…` 和 `POST /api/dashboard/reviews/record`。它们不修改 kernel JSON v2；读取与轮询不创建事项文件或检测记录。unscoped 分区只读。损坏或不支持的日志记录会提示历史不完整，不能据此推断未显示事项已完成。

## 审计存储与共享

默认位置：

```text
~/.ftckb/audit/<知识库规范路径的 SHA-256>/events.jsonl
~/.ftckb/audit/unscoped/events.jsonl
```

分区标识取自知识库路径，不是当前规则内容版本。调用记录另存本次知识库版本摘要；历史详情使用调用时的规则与命中快照，不拿当前规则覆盖旧结果。无法确定知识库的参数错误等记录放在 unscoped，界面单独显示。

CLI 的 `rules-sha256:` 摘要标识当次实际加载、解码后的规则集合；dashboard 与 chat/serve 使用知识库源文件路径和内容的摘要。两种格式不能直接比较是否相等：例如只修改 YAML 注释会改变源文件摘要，但不会改变规则语义摘要。CLI 摘要不通过事后重读文件来推测当次执行版本。

所有入口使用 `FTCKB_AUDIT_DIR` 更换审计根目录。例如：

```bash
FTCKB_AUDIT_DIR=/absolute/path/to/ftckb-audit python3 .agents/skills/ftckb-integrate/scripts/dashboard_runtime.py \
  --distribution apps/knowledge-cli/build/install/ftckb --knowledge /absolute/path/to/knowledge
FTCKB_AUDIT_DIR=/absolute/path/to/ftckb-audit ftckb validate /absolute/path/to/knowledge --json
```

需要在同一管理台看到 CLI 记录时，相关进程必须使用相同审计目录和同一知识库路径。复制知识库到另一路径会成为独立分区。记录跨进程安全追加，列表分页并增量读取；普通页面轮询不创建检测调用。进程中断留下未完成记录，日志尾部中断、损坏或读取失败会显示存储状态；写入失败通过独立诊断报告。审计错误不改变确定性 CLI 的 kernel JSON stdout 或退出码。

审计记录调用 ID、入口、阶段、上下文、时间、结果、规则/位置及可用 token 用量。模型子调用关联实际的 chat/serve 请求 ID。不保存 API key、访问 token、完整提示词、模型正文或原始 diff。

## 如何理解检测轨迹

| 记录 | 可以说明什么 |
| --- | --- |
| validate | 已执行知识库加载与规则校验；不表示检查过代码 diff |
| resolve | 已对指定上下文裁决规则；不表示执行过 Standardizer |
| check / 管理台补丁检测 | 各阶段标明加载、校验、裁决、diff 读取与 Standardizer 是否实际执行 |
| chat/serve 询问 | 记录实际规则与模型阶段；diff 检测不适用 |
| chat/serve 新编辑 | 对本次预览 diff 执行写入前检查；normal 下硬违规阻止写入，soft 随结果返回 |
| undo/discard | 独立恢复事务，展示分支、路径与冲突等实际结果；不冒充新编辑通过检查 |
| 未完成 | 没有结束事件；不能算通过 |

内置聊天服务在下次请求开始前重新加载和校验规则，并为该请求固定快照。编辑检查与写入之间会复查规则版本和文件基线；过期后需要重新生成。规则保存与内置编辑写入使用共同锁协调，但无法控制任意外部编辑器；边界检查发现变化会拒绝或回滚。

chat 可用 `/work-mode normal|test|dev` 明确选择下一条提交消息的工作模式；消息提交后立即重置为 normal，包括请求失败的情况。serve 的 ask/submit API 可在本次请求传 `workMode`，省略即 normal，不写入持久设置。dev 只对该次生成的预览 diff 降级，仍返回每个硬命中的规则、检查和位置；知识无效、冲突及过期预览仍会失败。只有用户明确指定本次为 test/dev 时才能使用，不能从文件名推断。

概览统计的是「已记录调用中的 diff 检测状态」。被拦截的 diff 是已检测的一部分；soft、dev 降级提醒、参数错误、知识无效、冲突和模型失败含义不同。仅独立复核声明能说明指定历史快照有人声明复核；原检测、退出码及 hard/soft/dev 类型仍保留。界面不会将两个没有关联 ID 的 resolve/check 自动配成一组。

## 覆盖与版本边界

- 审计启用前、旧固定 commit/旧构建、未接入进程和绕过 CLI 的操作不可观测。升级本仓库不会自动升级其他 FTC 项目中固定的副本；需按既有接入流程明确升级和重新构建。旧可执行文件可能不认识 `dashboard`。
- 外部编码 Agent 调用本版本 CLI 时，可以记录这些 CLI 调用；它的全部对话、任意工具执行和私自写文件并不因此受管理台监督。写入前门禁覆盖这里集成的 chat/serve 编辑流程。
- 当前聚合审计入口是新版 validate/resolve/check、chat/serve 用户请求及模型子调用，以及 dashboard 手动检测和规则事务；不要据此宣称所有其他命令或 Android Studio 插件操作均有同等审计覆盖。
- Dashboard HTTP API、审计事件与 kernel JSON 是不同契约。管理台新增接口不修改 kernel JSON v2 或项目接入协议 v2，也不代表已经创建 tag、Release 或更新公开文档站。
- 构建、静态检查、规则校验和浏览器操作均不能替代 Robot Controller、Driver Station 或真机验证。测试结果按实际执行范围报告，不将历史全量测试当作本次新增功能已被验证的证据。

更多：[`AGENTS.md`](../AGENTS.md)、[机器契约](kernel-contract.md)、[diff 检查](standardizer-check.md)、[聊天 Agent](cli-agent.md)。
