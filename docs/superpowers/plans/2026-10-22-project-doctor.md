# Project doctor Implementation Plan

> **For agentic workers:** 按已授权的 manager 分工由唯一 implementer 执行，独立 reviewer 验收；不再拆分子 agent，不自动 commit/push。

**Goal:** 提供只读、结构化项目自检和准确下一步，并统一生成的 Agent 工作流。

**Architecture:** 新增 doctor 专属 helper，project.py 仅做最小路由；纯校验复用，Git 和错误输出使用专用安全路径。诊断只读配置、Git 元数据和固定源码/产物，所有修复仅作为结构化建议。

**Tech Stack:** Python 3.10+ 标准库、现有 integration_contract、Git、unittest；不增加第三方依赖。

## Global Constraints

- 遵守设计 `docs/superpowers/specs/2026-10-22-project-doctor-design.md`；doctorSchemaVersion=1，与 kernel JSON v2 独立。
- 入口 `project.py doctor --project .`；exit 0/1/64；所有 doctor 错误输出单个 JSON。
- 不网络/build/kernel/Dashboard 启动，不改配置/pin，不执行目标脚本；Git 关闭 fsmonitor/optional locks、禁 helper、限时。
- 不改 resolve/check/verify 既有契约、接入协议、work-mode、knowledge；无自动 commit/push，保留用户原有 untracked。
- Dashboard 前置已由 root 确认，本轮复用实际服务；不重启、不记录凭据链接。

## 文件与 Owner

- Implementer：`.agents/skills/ftckb-integrate/scripts/project.py` 最小 doctor 分流；新 `scripts/project_doctor.py` 只读报告逻辑。
- Implementer：`tests/integration/test_integration.py` 添加独立 Doctor 测试类，复用离线 Git fixture/snapshot。
- Implementer：`assets/project-skill/SKILL.md`、`assets/AGENTS.block.md`、接入 `SKILL.md`、`README.md`、`docs/project-integration.md` 统一操作顺序与旧安装入口。
- Reviewer：只读审查所有 diff；implementer 执行并记录实际库 resolve/check；root 阅读证据验收与确认交付范围。

## Task 1：一次完成只读诊断、模板与针对性验证

**接口：** `project_doctor.main(argv:list[str])->int` 输出报告；`diagnose(project:Path)->dict` 收集诊断；原命令分支不依赖新 helper。

- [ ] 先添加 Doctor 测试类与最小 JSON 契约断言，确认当前 doctor 不存在导致失败：

```python
result=command(sys.executable,str(ROOT/BUNDLE/"scripts/project.py"),"doctor","--project",str(self.target))
payload=json.loads(result.stdout)
self.assertEqual(1,payload["doctorSchemaVersion"])
self.assertEqual("doctor",payload["command"])
self.assertTrue(all(check["status"] in {"passed","failed","blocked","unknown","not_checked"} for check in payload["checks"]))
```

- [ ] 新增 helper：固定报告字段/检查顺序；每项稳定 code/status/message/blockedBy；顶层 ok 仅聚合必需检查。禁止使用原始异常文本拼 message。
- [ ] 实现 doctor 专属 argparse 错误 JSON；`project.py` 只在第一个 command 为 doctor 时延迟 import helper，其余 main 代码保持原样。`--help` 可保留帮助输出，错误参数必须 JSON+64。
- [ ] 实现安全 Git runner：argv 列表、固定 Git 子命令、`-c core.fsmonitor=false --no-optional-locks`、超时、禁交互/外部 helpers；拒绝从 stderr 直接构造报告。
- [ ] 配置解析与字段校验分离；分别调用纯 versions/normalize_profiles/identity 等能力时捕获并转为固定 code，team/season 分项验证；不调用 load_config 整链吞掉后续问题。
- [ ] 检查 Git root/HEAD、配置、submodule 元数据/index/HEAD/dirty、knowledge/source manifest、managed receipts；缺前置时 blocked，其余独立项继续。
- [ ] 检查运行环境和 CLI 产物存在/receipt；产物未知或不匹配保守报告，禁止执行 launcher、gradlew、verify 或 kernel。
- [ ] 用可靠静态证据判定 Dashboard 支持状态；只在确认路径/能力时输出 cwd+argv。缺 build 建议 build，旧版建议显式升级，unknown 不猜。
- [ ] nextSteps 使用经过验证的值，修复参数不全时只给说明；不输出 URL/ref/stderr。所有输出可直接 JSON 解析，禁止 shell 拼接建议。
- [ ] 添加下表用例；预期以稳定 code/status 断言，不固定全部文案：

| 场景 | 必须证明 |
| --- | --- |
| v2 generic、rookiebot、v1 | 原 profiles/normalizedProfiles 正确，v1 不补 generic |
| 无配置、坏 JSON、重复键 | 错误分类准确，依赖 blocked，非 traceback |
| team/season/profile/commit 四错 | 同一报告列出四项，不只报第一个 |
| 非根/无 HEAD、submodule 未初始化、dirty、pin/gitlink 错 | 确定失败与 blocked 区分 |
| Skill/AGENTS 缺失或 hash 错，symlink | 不覆盖、不穿越，独立错误保留 |
| CLI 缺 launcher/库/receipt | 不执行产物，不把 stamp 当来源证明 |
| 旧版/未知 Dashboard 能力 | 不输出无依据的启动命令 |
| Git 超时、敏感 URL/ref/stderr | 固定安全报告，不泄漏原文本 |
| 无 --project/未知 doctor 参数 | 单 JSON，exit64 |
| Git fsmonitor、build/kernel/network 哨兵 | 全部不触发；目标含 .git 快照不变 |

- [ ] 更新两份模板、接入 Skill、README、integration docs：先 doctor，再复用/启动与实际 knowledge/audit 一致的 Dashboard，确认页面连接后 resolve，修改后 check；明确 doctor 无规则/代码通过含义。
- [ ] 文档示例区分已安装/未初始化/旧 pin 无 doctor：旧安装使用外部已审阅 checkout 的绝对入口，`--project` 指向目标；不要求缺失脚本、不自动改 pin。人工选择保留真实 team/season/profile。
- [ ] 测试模板生成内容和顺序；新模板仅随显式安装/升级生成，不直接覆盖旧项目托管文件；现有 receipts/重跑保护继续通过。
- [ ] 执行：`PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s tests/integration -p 'test_integration.py' -v`。
  预期：全部非重型用例通过，真实 Gradle 重型用例维持显式 opt-in skip；报告实际数字，不复制历史数字。

## Task 2：隔离演示、独立复核与交付

- [ ] 在临时目录复用测试 fixture 建 FTC Git 项目，只在 fixture 内人为制造配置四错；保存 doctor JSON、exit code 和 `.git`/工作区前后快照，证明无写入。
- [ ] 修复 fixture 配置后重跑；若 fixture 缺 CLI 真实产物或能力证据，诚实保留 failed/unknown，不为展示全绿伪造来源或运行环境。
- [ ] 通过测试中完整 fixture 展示 `context`、`checks`、`nextSteps.cwd/argv`，并注明 fixture 证明的是 doctor 行为，不是真实规则/真机通过。
- [ ] 独立 reviewer 审查最终 diff 与 suite/demo 证据，重点核对只读 runner、无凭据回显、所有前置状态、旧命令/旧 pin 兼容、模板流程一致。
- [ ] reviewer 必修意见由同一 implementer 修复；变更后重跑相关 doctor 测试及最后完整 Python integration suite。
- [ ] implementer 用已确定正式 QA 上下文（20827/2025-2026/generic/normal）执行并记录实际库 check、逐项报告 soft；root 阅读证据验收，不把该上下文当用户机器人项目配置。
- [ ] 交付实际测试数字、临时 demo 位置、改动文件、doctor 边界和未验证项；无 commit/push，无 knowledge 修改。

## 本轮保留但不实施

resolve→check 显式工作收据；配置驱动 Dashboard 入口。它们不进入当前接口或验收条件。
