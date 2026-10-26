# 显式 resolve→check 工作收据

日期：2026-10-22。本轮仅本地更新，不自动 commit/push；doctor 提交完成前本文只存 /tmp。

## 用户体验与选择

完成 doctor、Dashboard 连接后，Agent 调用 `project.py work resolve --project .` 获取规则及新 UUID 收据。
输出包含完整下一步 `project.py work check --project . --receipt ID`；无需重复队号、赛季、profile 或 pin。
check 必须显式指定 ID，不自动选“最近一次”；配置、实际知识输入或模式变化时拒绝复用并提示重新 resolve。
正常编辑机器人代码、提交机器人代码或重复 check 不使收据失效；本轮不实现任务管理、账号、数据库或 Dashboard 新界面。
推荐独立 `work` 命名空间；原命令加 flags 虽略短，但会混用输出协议，独立入口能完整保留旧 resolve/check 默认输出与退出码。

## 新命令与独立输出契约

- `project.py work resolve --project PATH [--work-mode normal|test|dev]`：仅成功、无冲突且前后上下文相同时签发收据。
- `project.py work check --project PATH --receipt UUID [--diff FILE] [--work-mode normal|test|dev]`：校验收据和当前上下文后运行真实 check。
- mode 省略为 normal；check 必须与 resolve 相同，不能静默从收据继承 test/dev；dev check 继续要求 --diff。
- 顶层固定 `workReceiptSchemaVersion:1`、`command:"work-resolve"|"work-check"`、`ok`、`receipt`、`kernel`、`error`、`nextSteps`。
- `receipt` 为 null 或 `{id,status:"created"|"matched"|"rejected",createdAt,contextDigest,resolveOutputDigest}`；失败时仅回显已通过 UUID 格式校验的 ID。
- `kernel` 为 null 或 `{exitCode,output:<原 kernel JSON>}`；内核内容保持其 schemaVersion，禁止往 kernel JSON v2 增删字段。
- `error` 为 null 或 `{code,message,changedFields:[]}`；固定安全文案，不回显原 stderr、repository/ref、配置原文或 token。
- `nextSteps` 是 `{code,description,cwd,argv}` 数组；check argv 包含准确 ID，非 normal 时显式带 mode，dev 时说明还需本次 diff。
- exit0=实际 resolve/check 成功且关联校验成功；exit1=真实 check 硬违规；exit2=收据/上下文/接入/运行错误或 resolve 冲突；exit64=新入口参数错误。错误仍为上述单 JSON。
- `ok` 只表示本次命令与实际内核结果，无“已经读懂规则/语义合规/已人工复核/真机通过”含义。

## 收据与上下文

收据文件版本为 `workReceiptSchemaVersion:1`，含 `id`、`createdAt`、`context`、`resolve:{outputDigest}`、`integrityDigest`。
context 精确保存：canonical projectRoot、实际 gitDir、configSha256（配置原字节）、protocol 三版本、team、season、原 profiles 与 normalizedProfiles、sourceCommit、canonical knowledgePath、knowledgeDigest、workMode。
v1 profiles 用 null 表示“不支持”，不能补成 generic；v2 空数组是明确 generic。合法配置语义沿用现有纯校验，不自行裁决规则。
knowledgeDigest 对实际加载范围中按相对路径排序的 `.yaml`/`.yml` 文件，以路径与各文件 SHA-256 的规范 JSON 计算；包含被 Git 忽略的文件，不解码/裁决 YAML。
知识目录的 symlink、无法完整读取、文件类型或调用前后输入不一致均拒绝；不把未知当匹配。
contextDigest/integrityDigest 使用 UTF-8、固定排序键和紧凑 JSON；完整性摘要排除自身字段。
收据仅在已校验真实 resolve exit0 后写入，resolve outputDigest 绑定该次已验证完整 payload；check envelope 带回相同摘要，形成显式关联。

## 生命周期与存储

通过 Git 的实际 gitDir 存储 `ftckb/receipts/<UUID>.json`；收据不进入项目工作区，避免污染默认 check diff，也不写 audit events/reviews。
ID 由本工具生成 UUID4；调用者只能传严格 UUID，不接受任意收据路径；每次 resolve 创建独立文件，支持并行任务，不维护“latest”。
目录/文件拒绝 symlink 与非普通类型；目录在 POSIX 为 0700，收据为 0600，Windows 使用当前用户继承 ACL，不宣称 Unix 权限。
同目录独占临时文件写入、flush/fsync，再原子发布；失败清理临时文件，签发失败 exit2，不留下可被接受的半文件；不覆盖既有收据。
读取限制 64 KiB，拒绝重复键、未知版本、坏 ID/摘要/字段；不读取收据指定的外部路径。
不设任意时间 TTL；上下文一致时允许重复 check，变化即失效并提示重新 resolve。check 不消费或改写收据；用户可自行删除旧收据。
本地摘要只能发现损坏或未配套改写，不能防止同一用户伪造并重算；本功能不是签名、身份认证或不可抵赖审计。

## 调用边界与兼容

复用已验证配置、pin、托管文件、schema 和 kernel 调用；新入口要求已构建 CLI 与匹配现有 build receipt，缺少时给明确 setup-required，不自动构建或联网。
上下文在 kernel 调用前后重新读取并校验：resolve 期间漂移不签发；check 期间漂移返回 exit2，保留实际 kernel payload但不宣称关联通过。
若 --diff 生效，check 同时确认补丁调用前后字节一致；默认工作区代码本就允许变化，不额外发明代码通过收据。
调用外部固定 CLI 是用户显式请求的规则操作；Git 元数据查询复用 doctor 安全只读 runner，不执行项目 hooks/helper，不以未验证 receipt 字段构造命令。
原 resolve/check/validate/doctor/verify 默认分支与协议不改；不改 kernel、knowledge、项目 pin、配置或 managed receipts，不猜配分开的 audit 调用。
生成 Skill/AGENTS 和接入文档推荐新 work 流程，保留原命令为无关联的兼容入口；原入口不能声称拥有工作收据。

## 验收与责任

单 implementer 负责新 helper、最小 project.py 分流、针对性 integration tests、生成模板及接入文档；planner 只写 spec/plan，reviewer 只读独立审查，root 阅读证据验收。
测试覆盖正常关联与重复check、并发两ID、所有上下文字段变化、ignored YAML 变化、mode不匹配、坏/缺失/篡改/超大/symlink 收据、原子失败、调用中漂移、旧默认输出兼容和真实 check exit1/soft。
隔离演示先真实 resolve→改机器人代码→check 成功，再只改 fixture 配置让同一收据拒绝，展示重新resolve后新ID；记录实际内核结果，不改用户项目。
只运行受影响的 Python 用例与真实隔离演示；无需大版本全回归或安装 IDE。收据仅是关联证据，compile/deploy/真机另行说明。
