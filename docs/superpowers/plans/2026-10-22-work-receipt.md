# Work receipt Implementation Plan

> **For agentic workers:** 唯一 implementer 顺序实现，独立 reviewer 按证据验收；不新增子 agent，不自动 commit/push。

**Goal:** 显式关联一次 resolve 与后续 check，阻止旧规则上下文被误用。

**Architecture:** 新 `project.py work` 命名空间延迟分流到小型 receipt helper。复用配置/内核验证；收据存实际 Git metadata，使用准确 context fingerprint，旧命令默认完全兼容。

**Tech Stack:** Python 3.10+ 标准库、现有 project/integration_contract/doctor Git runner、unittest；无新服务或依赖。

## Global Constraints

- 设计：`docs/superpowers/specs/2026-10-22-work-receipt-design.md`；新 envelope/file `workReceiptSchemaVersion:1`；不改 kernel JSON v2。
- 命令：`work resolve --project PATH`；`work check --project PATH --receipt UUID`；normal 可省略，test/dev 必须显式且与 receipt 匹配。
- exit0=实际成功且关联通过，exit1=真实 check 硬违规，exit2=收据/上下文/接入失败，exit64=新调用参数错误；全部新错误单 JSON。
- 不联网、不自动 build、改知识/pin/config、推断 audit 配对或承诺身份/语义/真机；不自动 commit/push。
- doctor push 确认前只读 repo、草稿放 /tmp；后续保留原 untracked，Dashboard 复用 root 已验证实例。

## 文件与 Owner

- Create `.agents/skills/ftckb-integrate/scripts/work_receipt.py`：独立解析/契约、context 捕获、receipt 原子存储与新流程编排。
- Modify `.agents/skills/ftckb-integrate/scripts/project.py`：仅增加首 token 为 work 的延迟分流；既有默认 parser/流程不重写。
- Modify `tests/integration/test_integration.py`：独立 WorkReceiptTest 类和 fixture helpers，复用现有临时 Git/假内核基础设施。
- Modify `assets/project-skill/SKILL.md`、`assets/AGENTS.block.md`、接入 `SKILL.md`、README、docs/project-integration.md：新推荐操作与边界。
- Implementer 执行/记录检查与演示；reviewer 只读审查；root 阅读证据验收，不代写方案或代码。

## Task 1：新入口、上下文与原子收据

- [ ] 先测试新入口报告字段与 exit：当前 work 不存在应失败，之后要求 `workReceiptSchemaVersion==1`、command 为 work-resolve/work-check。
- [ ] 新 helper 定义固定 envelope 与安全 error factory；自有 parser 负责 resolve/check 必填参数、mode/dev-diff、非法 UUID 与 JSON64。原命令分支不依赖 helper。
- [ ] 以已验证 root/config/pin/managed/schema 捕获 context；raw config SHA-256 保证包括格式变化；profiles v1 为 null、v2 保留原选并另存规范结果。
- [ ] Git 使用 doctor 安全 runner，求实际 gitDir；知识扫描与 FileKnowledgeRepository.kt:16-18 同范围（全部 .yaml/.yml），排序路径+各文件hash，拒绝 symlink/无法读取。
- [ ] 预检 CLI launcher/发行目录/现有 build receipt；未准备好时 exit2+setup-required，不进入 build_cli，不联网；错误文案不包含原URL/ref/stderr。
- [ ] kernel 调用前后重新捕获并核对 context；检查配置/pin/knowledge漂移，明确 changedFields，只输出字段名。
- [ ] resolve 实际返回成功后构建 `{version,id,createdAt,context,resolve.outputDigest,integrityDigest}`，把完整真实 kernel output 嵌入 envelope。
- [ ] storage 固定于实际 gitDir/ftckb/receipts；ID UUID4，不接受任意 path，拒绝 symlink/非普通类型，读上限64KiB，重复键/版本/字段/摘要严格校验。
- [ ] POSIX目录0700/文件0600；同目录独占临时文件、flush/fsync、原子发布且不覆盖既有ID，失败清理；使用现有安全文件方法或目录句柄避免跟随符号链接。
- [ ] `work check` 显式读取ID，先比较context/mode，匹配后调用真实check并重新确认上下文；默认代码变更不使收据失效，收据不消费不改写。
- [ ] supplied diff 调用前后hash一致才返回关联匹配；kernel payload与exit保留在envelope，context漂移最终exit2，真实hard仍exit1，soft原样保留。
- [ ] nextSteps给可复制cwd+argv；stale只提示新的work resolve，成功resolve输出准确work check与ID；dev额外说明需本次diff，不凭空填路径。

## Task 2：针对性测试与生成流程

- [ ] 添加下表测试；通过真实临时Git而非仅模拟fingerprint证明存储不影响工作区diff：

| 场景 | 核验 |
| --- | --- |
| 正常resolve→改机器人代码→check、重复check | 同ID明确关联且不因合理代码编辑过期 |
| 两次resolve并发/顺序 | 不同ID、无latest、不能误选 |
| team/season/profile/protocol/pin/path/config格式变化 | stale/接入拒绝，check哨兵未执行 |
| ignored YAML、新增/删除/修改规则、symlink | 实际知识digest变化或安全拒绝 |
| test/dev模式、generic与命名、v1 | 显式mode准确，v1不补profiles，dev仍需diff |
| 缺失/非法ID/坏JSON/重复键/版本/摘要/超大 | 安全单JSON，无任意文件读取 |
| 存储目录/文件symlink、写入中断、发布失败 | 不跟随、不覆盖，无可接受半文件 |
| resolve/check期间配置/规则漂移 | 不签发或exit2；保留真实已执行kernel事实 |
| 假kernel exit1、soft、错误/冲突 | 正确退出码与原payload嵌入，非收据成功冒充代码通过 |
| 原resolve/check默认调用 | stdout与exit与改动前一致，不创建任何receipt |
| 不准备CLI | setup-required，build/network哨兵不触发 |

- [ ] 模板和文档改成 doctor→Dashboard已连接→work resolve→修改→work check --receipt ID；解释无收据旧resolve/check仍可用，但不具有关联证明。
- [ ] 明确无时间TTL、配置字节变更需重新resolve、receipt在Gitmetadata不入库、check不消费；同用户可伪造非身份认证；未修改audit。
- [ ] 运行受影响类（root cwd）：`PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=tests/integration:.agents/skills/ftckb-integrate/scripts python3 -m unittest test_integration.WorkReceiptTest test_integration.IntegrationContractTest -v`。
- [ ] 另跑既有wrapper/模式/安装托管模板相关用例；记录准确命令和实际数字。新变化后只重复受影响测试，不跑全Kotlin/Android回归。

## Task 3：真实隔离演示、独立审查与交付

- [ ] 用临时FTC Git项目与已固定、已构建真实CLI完成demo；新wrapper可由当前已审阅checkout执行，不改目标pin。禁止为了演示调用未授权网络build。
- [ ] 依次记录真实work resolve输出/ID→只改机器人代码→真实work check→仅改fixture配置→同ID拒绝且kernel未执行→重新resolve新ID。
- [ ] 记录receipt路径/权限、工作区前后状态和实际kernel结果/soft；fixture配置用于演示，不能误说实际用户项目配置。
- [ ] reviewer核对最终diff、针对性测试、原默认字节/exit兼容、存储安全与真实演示；只收敛阻断项，不扩展新功能。
- [ ] implementer修复必修后重跑受影响用例和demo关键路径；root阅读证据验收。无大版本全回归，无IDE下载。
- [ ] 交付具体入口、上下文失效条件、实际测试数字/演示路径、未覆盖的真实硬件/身份/完整语义边界；本轮新feat保持本地，待用户明确再commitpush。
