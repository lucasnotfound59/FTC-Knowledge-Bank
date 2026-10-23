# 显式 Dashboard 连接核验

日期：2026-10-23。本轮只做已存在连接的只读核验，不自动 commit/push；保留未提交的 work receipt 全部成果。

## 目标与取舍

doctor 已给出配置实际 knowledge 的完整启动 argv，再做启动别名收益有限。
新增 `project.py dashboard verify --project PATH --connection-file PRIVATE_JSON`，证明显式选中的服务是否报告了期望 knowledge 与实际 audit 存储身份。
只核验该连接，不扫描端口、不启动/关闭/升级服务、不管理 daemon、不保存 token。
仍需按 AGENTS 打开浏览器确认页面实际连接；服务二进制 pin/来源保持未证明，不把 authenticated identity 当身份认证系统或真机保证。

## 输入与本地期望

连接文件唯一内容格式是 `{"url":"http://127.0.0.1:PORT/#token=TOKEN"}`；token 不进入 argv、stdout、stderr、仓库或新增状态文件。
读取上限 8 KiB；拒绝重复键、额外字段、非普通文件、symlink 与不安全路径。POSIX 要求当前用户所有且无 group/world 权限；无法核验所需隐私边界的平台明确 unknown。
URL 仅允许字面 `http://127.0.0.1:1..65535/`、唯一 `#token=`、合法有限长度 base64url token；禁止 userinfo、query、其他路径/host、DNS 与重定向。
本地先安全验证 Git 根、配置/协议、source metadata/gitlink/pin/clean、managed 内容和实际 knowledge；使用 doctor 安全 Git runner，不复用含 unsafe Git 的旧整链。
复用纯校验能力，不把 work receipt 存储限制、JDK/CLI 构建或 Dashboard 静态字符串探测当连接核验的必要前提；不构建、不执行 kernel。
expected audit root 来自本次 FTCKB_AUDIT_DIR 非空白值，否则当前用户 home 下 `.ftckb/audit`；不展开 `~`，相对环境值以未来 kernel 的 cwd=projectRoot 解析。
canonical 遵循 FileAuditStore：absolute normalize、最长存在父目录 realpath，再附不存在尾段；expected partition 为 canonical audit root/sha256(canonical knowledgePath UTF-8)。
Python home 只是本工具选定的显式期望，不证明未加 env 的 kernel JVM user.home；独立状态 `kernel.defaultAudit=not_checked`。默认不匹配就提示显式设置 FTCKB_AUDIT_DIR，下一步 env 同时适用于后续 resolve/check。

## 服务只读身份 API

新增受现有 token/Host/Origin 保护的 `GET /api/dashboard/identity`，仍属于 Dashboard API v1，拒绝 query/非 GET，不创建审计事件或执行规则操作。
在原 `{apiVersion:1,ok:true}` 下增加 `identitySchemaVersion:1`、`knowledge`、`audit`；不改 kernel JSON v2、已有 summary 或既有 API 字段。
knowledge 从同一次 KnowledgeWorkspace snapshot 返回 `{root,version,fileDigests,valid,ruleCount}`；fileDigests 是相对路径→文件 SHA-256，不返回正文。
其范围与现有 snapshot 相同：全部小写 `.yaml`/`.yml`，含 guides 与 Git ignored 文件，拒绝任何 symlink；version 仍是现有 framed source-bytes SHA256，不另造规则裁决。
本地扫描同范围得到路径→SHA-256 映射，与服务映射精确相等；不在 Python 重写 JVM version framing/排序。服务 version 与映射必须来自同一次 capture。
audit 从实际绑定 FileAuditStore.partitionPath 返回 `{kind:"file",root,partition,health}`；包括注入的 FileAuditStore，按真实 root+partition 比较期望分区hash，不拿 options.knowledge 或环境猜 store 归属。
非文件/自定义 store 或缺可靠来源时返回 `{kind:"unknown",root:null,partition:null,health:...}`；不能假定为默认 audit 目录。
API 不返回 token、服务二进制 pin 或无法证明的 build 来源；只返回自身实际存储身份。

## 核验流程与输出契约

新 namespace 延迟分流到独立 helper；固定 GET、关闭代理继承、拒绝 redirect、3 秒 socket I/O 超时（不是整个命令总时限）、响应最多 1 MiB，凭据只放 Authorization header。
严格核对 apiVersion/identitySchemaVersion、类型、摘要格式和路径字段；不读取服务返回路径中的文件，不把响应正文/异常原文输出。
本地上下文与知识映射在 GET 前后复核；观察到变化则拒绝匹配，不宣称能抵御同权限中途改回的 ABA。
必需项：本地身份、authenticated identity、knowledge root/完整文件摘要/valid、实际 audit root/partition/health；缺失、未知或不一致均不能 ok。
API 版本/endpoint 不支持为 unknown+unsupported-api；401 为 failed+unauthorized；超时/拒绝连接为 unknown+unreachable；路径/内容不符为 failed+knowledge-mismatch/audit-mismatch。
输出独立 `{dashboardConnectionSchemaVersion:1,command:"dashboard-verify",ok,context,server,checks,nextSteps,error}`；check 使用稳定 code/status/message，status 为 passed/failed/blocked/unknown/not_checked。
context 仅含安全已验证 projectRoot/sourceCommit/knowledgePath/expectedAuditRoot/expectedAuditPartition；server 仅含本地解析的无 fragment origin、合法摘要 version 与整数 ruleCount，不回显服务自由字符串、错误body、输入 URL 或 token。
exit0=所有必需核验 passed；exit1=任何 mismatch、未知/不支持/不可达/凭据或本地校验失败；exit64=命令参数错误。错误仍为单 JSON。
浏览器连接为 not_checked、服务二进制 pin 为 unknown，均明确非必需；exit0 只表示本次 authenticated API 的库/审计身份匹配，不表示 AGENTS 浏览器步骤完成。
成功下一步包含浏览器确认说明、适用于后续 resolve/check 的 workflow-env，以及实际当前 wrapper 绝对入口的 work resolve cwd/argv/env；env 显式固定已核验 FTCKB_AUDIT_DIR，返回收据的 check argv 也必须应用该 env，不凭空生成 UUID。
失败给可操作的凭据重开、连接选择、分区修正或版本限制说明；不自动重启、kill、扫描或改 pin，不建议执行尚不存在的旧 pin 新入口。

## 实施责任、兼容与验收

单 owner 改 Python helper/最小 project.py 分流、Kotlin identity route、必要 store 来源、模板/docs 与针对性 tests；planner 只写本轮 spec/plan，reviewer 只读审查，root 阅读证据验收。
既有 doctor/work/kernel 输出、退出码和状态保持；前轮 work 11 文件基线在 /tmp，报告本轮 delta 与累计未提交差异，不回退或混报成果。
测试：正确连接、wrong knowledge/内容、相对及自定义 audit、注入 store、旧 API、401/redirect/proxy/timeout/oversize、私有文件/URL 边界、GET 无写入与前后变化。
真实隔离 demo 使用新编译 CLI 的临时知识库实例，仅明确创建 demo 服务；已有用户实例保持运行。先匹配，再用不同 expected audit/knowledge 展示拒绝；仍实际打开页面确认。
只跑相关 Python/CLI tests 和必要 CLI 编译，不全仓库/Android 回归、不下载 IDE。无 compile/deploy/真机或服务二进制来源声明。
