# 检查提醒的复核闭环设计

日期：2026-10-20。适用基线：`9bc3b95`。用户已确认的范围是 Dashboard 待复核列表、逐项复核记录及检查版本关联；本文件细化该范围。实施不自动 commit/push，不更改真实规则。

## 目标与边界

操作者能从已记录的 diff 检查找到 soft 提醒，逐项记录「已核对」「需修改」「本次不适用」，填写理由、证据链接与复核人，并查看完整的后续记录。复核只表示指定人在指定检查快照上作出的声明。原始 hard/soft、检查阶段、CLI stdout、退出码与 kernel JSON v2 均保持原义；不把复核声明算作规则批准、机器合规或真机安全。

当前证据：`AuditHit` 已包含 kind/ruleId/check/path/line/detail；普通 soft 当前是规则级提醒，没有精确代码行。`AuditRecord` 仅支持 STARTED → SNAPSHOT/STAGE → FINISHED，终结后不会接收新阶段。Dashboard 已有鉴权、同源写保护、审计分页和只用 textContent 的界面。当前日志没有被检查代码的版本摘要。

第一版不引入账号、权限角色体系、通知调度、自动修复、批量全选通过、外部 URL 抓取、仓库任意路径读取、完整代码存储或跨运行自动批准。

## 方案选择

| 方式 | 收益 | 成本与问题 |
| --- | --- | --- |
| 独立追加 `reviews.jsonl`，投影到审计详情与复核列表（采用） | 原始检测不变；沿用文件锁/分页/健康诊断模式；无需新依赖；历史可追溯 | 增加一个专门存储与读取投影；必须处理源记录缺失 |
| 在 `events.jsonl` 加入 REVIEW 事件 | 单文件存储 | 打破当前终结生命周期和旧解码器枚举；复核容易混入检测统计，迁移成本更高 |
| 引入 SQLite 维护事项与复核 | 查询和事务方便 | 新运行依赖与迁移机制超过首版需要 |

采用第一种。检查版本作为 audit schema 1 的可选附加字段，与复核文件自己的 schemaVersion 1 分开；旧记录缺字段仍可读取。Dashboard API 保留 apiVersion 1，新增路由/字段；没有既有字段语义变更。

## 事项身份与原始证据

- 仅实际执行过 Standardizer 的阶段中的 `soft` 与 `dev_downgrade` 形成复核事项；hard 不得通过复核解除。
- 标识为 SHA-256 的 canonical JSON：`[invocationId, standardizerStageIndex, kind, ruleId, check, path, line, detail]`。阶段下完全相同的命中合并一项；不同阶段、调用、种类与位置保持独立。stageIndex 使用持久化 record.stages 的原始零基索引，不能先过滤再编号。
- 事项保存/返回原始快照的 sourceDigest，覆盖元数据、知识版本、相关规则快照、对应阶段、检查版本；服务器依据当前源记录计算，客户端不能创建不存在的命中或修改它的 kind。
- 普通 soft 展示「规则级提醒；原检测未提供精确位置」，不能从说明文字猜路径。dev 降级项保留原 check/path/line/detail，并始终标「dev 降级提醒」。
- 每次调用都有自己的事项。新检查默认待复核，绝不自动继承前一次「已核对/本次不适用」。重复 GET/轮询不生成事项文件或审计事件。
- 未结束调用、FAILED 调用、源日志健康异常、来源缺失或 sourceDigest 改变时只读，保留历史并显示原因；不能把无法确认完整性的记录纳入「已完成复核」统计。允许 COMPLETED 且 exitCode=1 的运行复核其中的 soft，但原硬拦截保持可见。

## 检查版本与陈旧状态

新增可选 `AuditCodeVersion`：`schemaVersion=1`、`kind`（worktree/supplied_patch/edit_preview）、`subjectId`、`inputDigest`、可选 `head`、可选 `predecessorInvocationId`。所有摘要为小写 SHA-256；对象由实际检查入口产生，浏览器不能提交任意摘要或本地路径。

- CLI 工作区：扩展 Standardizer 的采集方法，一次取得 HEAD→index 与 HEAD→worktree 的原始补丁，使用这些相同字节解析为本次 evaluate 的 changes。摘要包含版本标记、规范仓库标识、采集前后 HEAD、两段完整补丁；删除、重命名、binary/mode 变化不能只按 addedLines 散列。JGit 的 binary diff 占位文本不足以识别内容，必须附同次采集中旧/新 blob 的内容摘要（含 binary/untracked）；每个捕获版本都包含文件模式与缺失标记。若采集前后 HEAD 不同或无法建立稳定标识，版本缺失并标未知，不影响原检查输出/退出码。
- CLI `--diff`：先读取一次完整 patch；摘要来自实际解析的同一字符串。repository 参数只作经规范化的来源标识；不可把 supplied patch 声称为已读取工作区。无法规范化时只保留独立 patch 身份，不跨次关联。
- chat/serve：摘要来自实际 `ValidatedEditBatch` 中路径及 before/after 内容的 canonical 表示；不保存这些正文。其 kind 与工作区检查分开，避免把预览等同已写入代码。
- Dashboard 手动 patch：摘要来自本次提交文本。默认每次是独立来源。提供可选 `predecessorInvocationId`，仅在用户从已有手动检查选择「重新检查此补丁」时传入；服务端校验同知识分区、同上下文、前次为 Dashboard supplied_patch，才继承前次 subjectId 建立关联；无 predecessor 的首项以 invocationId 派生 subjectId。只允许引用已完成且早于新调用的记录，因此不能形成环；允许从历史项重检形成分叉，分叉仍共享 lineage，新到旧次序以审计追加顺序为准，不依赖浏览器时钟。不据路径相似自动合并不同项目。
- `subjectId` 是规范来源标识的摘要，Dashboard 不从历史日志中的路径执行 Git、读取本地项目或重新计算代码。相同 subjectId、kind、完整上下文（team、season、规范化 profiles、workMode）的后续完整检查若 inputDigest 不同，旧复核标「检查输入已变化，需要重新复核」；同摘要仍只代表该次快照。如果有界扫描尚未覆盖用于比较的后续记录，先标 unknown，不能根据不完整索引声称 snapshot。知识版本只能同格式比较；不同格式不判相等，显示未知。
- 版本状态与复核结论分开：`snapshot`（仅关联该次快照）、`stale`（存在可比较的后续不同输入/知识版本）、`unknown`（旧记录、损坏/缺失或无法比较）。任何状态都不称「当前工作区已验证」。未重新检查时无法观察外部编辑；页面明确说明「代码修改后重新运行检查，新检查需重新复核」。这项边界避免新增长驻文件监控与任意文件访问。
- 旧记录可展示已有证据；缺代码版本时可记录对历史提醒的复核，但必须显示 unknown 且不算作当前代码结论。源记录不完整/不可校验时禁止写入。

## 独立复核记录与事务

路径为同一 `FTCKB_AUDIT_DIR/<knowledge-partition>/reviews.jsonl`，不写入知识目录。只有 knowledge 分区可写；unscoped 只读。

每条记录：`schemaVersion`、`reviewId`（客户端 UUID 幂等键）、`itemId`、`invocationId`、`sourceDigest`、`revision`（该事项从 1 递增）、`decision`（verified/needs_changes/not_applicable）、`reason`、`evidenceUrls`、`reviewer`、服务器 UTC `createdAt`。记录不可编辑/删除；纠正结论必须追加新 revision。UI 展示最新声明和展开历史。

写入算法：同进程按规范日志路径互斥 → 获取专用 review 文件锁 → 增量读取最新数据 → 幂等检查 → 比较 expectedRevision → 再校验源记录/摘要 → 追加完整 JSON 行并 force → 返回持久化记录。跨进程共享同一个锁；两个并发更新同一 revision 只能一个成功。相同 reviewId 与完全相同业务内容重试返回原结果（即使 expectedRevision 已落后）；同 ID 不同内容或旧 revision 为 409，绝不能默默覆盖。

需要独立锁文件（`reviews.lock`）协调重放与追加，避免持有某文件独占锁后又递归申请同文件锁。sourceDigest 校验在写锁内完成；只承诺本地协作进程一致性，用户直接篡改日志不属于防篡改保障。获取失败或 fsync 失败返回 503，不能返回「已保存」。写失败保持原检查语义和复核草稿。

存储读取遵循现有 bounded JSONL 方法：单行上限 64 KiB，增量索引，不做每次全量扫描；损坏行/不支持版本计入 health 警告并跳过，尾部未完整行不算成功；重新启动可重建索引。未知/重复/倒序 revision 不覆盖有效结论。发生损坏、截断、替换或读取失败时禁写、标为历史不完整；仍可展示可读记录，不能静默清零待办或标完成。完整重试匹配到已持久化记录才可认定成功；对上次 force 失败但完整行可见的记录，返回幂等成功前仍必须成功 force，读取可见不代表已持久化。源审计缺失时历史保留为 orphaned，不重建或伪造源检查。

## 输入与本地安全

- POST 继续使用 Bearer、精确 Host、同源 Origin、Sec-Fetch-Site 和 JSON Content-Type 校验；重复 JSON 键、尾随数据、未知字段拒绝。复核路由不套现有检查 Trace，成功记录本身就是独立历史，不增加 diffChecked/total。
- reviewId/invocationId 必须为 canonical UUID；itemId/sourceDigest 必须为 64 位小写 hex；expectedRevision 为非负整数。
- reviewer trim 后 1–120 字符、reason trim 后 1–4000 字符，两者必填；禁止 NUL 与除换行/Tab 外控制字符。身份只作操作者声明，不验证实名或角色。
- evidenceUrls 是 0–8 项数组，单 URL ≤2048 字符，合计受 64 KiB 请求/最终 UTF-8 JSON 记录字节上限约束。仅绝对 http/https，必须有 host，禁止 userinfo、控制字符和以 token/api_key/access_token/password/authorization 等为键的 query/fragment（键名先 URL decode，再忽略大小写比较）。允许无证据链接但页面明确标「未提供链接」，不虚构必需的外部证据。
- 理由/姓名沿用审计的敏感值遮盖策略，存储前遮盖疑似凭据并在返回中给出 sanitized=true；前端展示最终保存内容。URL 不抓取，不自动打开；用户点击后以 noopener/noreferrer 新页打开。
- 所有内容以 textContent 渲染，链接单独构造并复核协议。无 localStorage token，无把启动链接或凭据写入仓库、复核日志或截图报告。

## Dashboard 交互与接口

在现有导航新增「复核」，默认筛选待复核（无声明、needs_changes、stale、不可校验）；可切换全部与已记录结论。unknown 旧项填写 verified/not_applicable 后进入「已记录结论」，保留「历史复核，版本未知」标记，不再因未知版本永久占据待办，也不计为当前代码已核对。列表按源检查新到旧分页，展示来源/时间、规则、原提醒类型、复核状态和版本状态。统计只针对当前筛选返回的范围，不能把当前页数量冒充全部。

审计详情对应命中旁增加「记录复核」；右侧面板展示原检查、版本摘要、最近声明及历史，三选一结论、姓名、理由、可选链接，最后「保存复核」。保存前显示完整草稿摘要以便检查。保存中防重复；409 保留草稿并加载最新记录提示重试；离线/503 保留草稿并明确未保存。重检入口继续要求已有上下文，不猜 team/season/profile。

新增接口（均 apiVersion 1）：

| 路由 | 内容 |
| --- | --- |
| GET `/api/dashboard/reviews?cursor=&limit=50&filter=pending|all|recorded` | 有界事项页、nextCursor、revision、auditHealth、reviewHealth；允许空页带 nextCursor，客户端继续加载 |
| GET `/api/dashboard/reviews/detail?itemId=...` | 精确 source、版本状态、最新声明、历史、canReview/disabledReason、sourceDigest |
| POST `/api/dashboard/reviews/record` | reviewId/itemId/invocationId/sourceDigest/expectedRevision/decision/reason/evidenceUrls/reviewer；返回已持久化记录及幂等结果 |
| 原 POST `/api/dashboard/check` | 仅追加可选 predecessorInvocationId；既有请求/响应语义不变 |

分页 cursor 必须绑定审计页位置和单次 hit 位置，不能漏掉一条调用中超过一页的事项；不直接提供任意文件路径参数。sourceDigest 变化、事项不可写、CAS 冲突用 409；不存在用 404；输入错误 400/422；存储问题 503。后端投影负责状态计算，前端不自行裁决规则或推导源身份。

## 实施与验收

拆分为审计模型/复核存储、检查来源摘要、Dashboard API/投影、界面与集成验收四个工作包，接口先定，再并行处理互不重叠文件。实施计划见 `../plans/2026-10-20-soft-review-workflow.md`。

必须验证：原 stdout/退出码的兼容测试；同项两个 writer 的 CAS、同 ID 重试、重启与损坏日志；各 producer 的完整输入摘要（包含删除/改名/binary 等）与不存代码正文；soft 与 dev 的独立结论；旧/未完成/缺失源的限制；API 鉴权/路径拒绝/输入边界；浏览器实际填写、保存、改判、409 草稿、断连、分页、变化后重检、键盘与窄屏；最终构建打包后的资源检查。临时测试用知识副本与审计目录，不向真实用户日志注入样例。

本仓库是 Knowledge Bank 平台，不是目标 TeamCode 项目。验证可明确沿用仓库已有 QA 上下文 20827 / 2025-2026 / generic（normal），只作为平台改动的确定性规则诊断；不能宣称这是实际机器人上下文。所有返回 soft 必须逐项记录适用性，不从规则 YAML 自行推断生效顺序。真实知识库应仍 validate 为 48 规则；不预填最终测试数量或宣称现场通过。
