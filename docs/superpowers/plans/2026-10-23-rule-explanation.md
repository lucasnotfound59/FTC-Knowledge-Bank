# Rule Explanation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. 本轮由 root 指定的单一实现 owner 执行，之后独立复核；不要递归委派。

**Goal:** 修正现有 Dashboard 的排除原因中文和最高层级冲突集合文案，使新手读懂已存在的确定性裁决。

**Architecture:** 只修改 app.js 中的 reasonLabel 和 resolutionPanel 展示。直接呈现同一次 RuleResolver 结果；保留已有接口、排序、规则链接和错误流程，不另算优先级或适用性。

**Tech Stack:** 原生 JavaScript、现有 Kotlin/JUnit 测试、现有 Dashboard/CLI、临时合成知识库、真实浏览器。

## Global Constraints

- 产品 owner：`dashboard_reason_labels`；独立 reviewer：`rule_explanation_plan`；所有 Gradle/安装构建由 root 协调的构建 owner 串行执行。
- 产品只修改 `apps/knowledge-cli/src/main/resources/dashboard/app.js`；无 commit/push 授权。
- kernel JSON v2、Dashboard API v1、YAML v4、确定性引擎、审批和真实规则不变。
- `status` 同时覆盖 candidate、rejected、deprecated，不能仅凭这一原因码声称它一定是 candidate。
- 多原因完整保留；未知原因码原样回退；不猜 profile，不跨快照拼接规则状态。
- 保留用户已有主 Dashboard；新资源真实展示验收使用独立临时实例，不能声称已有进程已升级。
- 不新建前端框架、依赖、产品模块或仅镜像文案实现的单元测试。

---

### Task 1: 修正展示并完成实际引擎到浏览器验收

**Files:**

- Modify: `apps/knowledge-cli/src/main/resources/dashboard/app.js`，`reasonLabel` 与 `resolutionPanel`。
- Existing tests: `modules/domain/src/test/kotlin/org/ftckb/domain/RuleResolverTest.kt`。
- Existing tests: `apps/knowledge-cli/src/test/kotlin/org/ftckb/cli/DashboardServerTest.kt`、`DashboardCommandTest.kt`。
- Temporary acceptance material only: `/private/tmp/ftckb-rule-explanation-*/knowledge/display.yaml`、`audit/`、结果 JSON、截图。

**Interfaces:**

- Consumes: `resolution.excludedRules[].reasons`、`overriddenRules[].{ruleId,topic,winnerIds,effectiveLevel}`、`conflicts[].{topic,effectiveLevel,ruleIds,authorities}`；均来自本次裁决结果。
- Produces: 已有 DOM 中的准确中文说明；不导出新函数或数据格式。

- [ ] **Step 1: 记录修改前证据。** 在正确知识库的已核验 Dashboard 工作流下，读取 `RuleResolver.kt` 和当前 app.js；确认真实原因码为 status/team/season/profile，现有映射遗漏它们，且 overridden 文案恒称「胜出规则」。保留调查记录，不改源规则来制造生产冲突。

- [ ] **Step 2: 最小修改 reasonLabel。** 将当前 `names` 对象替换为以下内容，保留其后的 `return names[reason] || reason;`：

```javascript
const names = {
  status:'状态不是 approved（已批准），不参与本次生效',
  team:'当前队号不在规则适用范围内',
  season:'当前赛季不在规则适用范围内',
  profile:'当前明确选择的 profiles 未满足规则要求',
  candidate:'候选规则不参与生效',
  'status-candidate':'候选规则不参与生效',
  'not-approved':'未批准',
  'team-mismatch':'队伍不匹配',
  'season-mismatch':'赛季不匹配',
  'profile-mismatch':'profile 不匹配',
  'work-mode-test':'test 模式明确排除'
};
```

- [ ] **Step 3: 最小修改覆盖与冲突说明。** 在 `kind === 'conflict'` 分支的已有 topic/level 行之后添加：

```javascript
row.append(el('p','','这些规则在最高有效层级并列，该主题未产生生效规则。'));
```

将现有 overridden 条目行替换为：

```javascript
if (kind === 'overridden') append(row,el('p','','主题：' + item.topic + ' · 最高有效层级：' + item.effectiveLevel),el('p','mono','同主题最高有效层级规则：' + list(item.winnerIds).join(', ')));
```

不对 winnerIds 计数推断冲突，不查当前目录的 approval，不修改 active/conflicts 数组。此中性标签对唯一胜者和冲突集合都正确。

- [ ] **Step 4: 静态检查与已有相关测试。** 先执行：

```bash
node --check apps/knowledge-cli/src/main/resources/dashboard/app.js
git diff --check
```

通过后让构建 owner 串行运行以下命令，复用本轮已确认的 JDK/Gradle 环境，不自行新开并行 Gradle：

```bash
./gradlew :modules:domain:test --tests org.ftckb.domain.RuleResolverTest :apps:knowledge-cli:test --tests org.ftckb.cli.DashboardServerTest --tests org.ftckb.cli.DashboardCommandTest :apps:knowledge-cli:installDist --no-daemon
```

预期相关测试全部通过、打包包含新 app.js。已有主 Dashboard 不能当新资源已加载的证据。

- [ ] **Step 5: 创建明确的展示 fixture。** 以下 Python 只在 `/private/tmp` 新建合成知识库，输出临时根；其中审批均为合成测试数据，不是真实批准：

```python
import json
import tempfile
from pathlib import Path

qa_root=Path(tempfile.mkdtemp(prefix='ftckb-rule-explanation-',dir='/private/tmp'))
qa_knowledge=qa_root/'knowledge'
qa_knowledge.mkdir()
(qa_root/'audit').mkdir()

def rule(rule_id,topic,status='approved',authority='shared',level='shared',teams=None,seasons=None,profiles=None):
    value={
        'id':rule_id,'topic':topic,'title':'Synthetic presentation fixture',
        'instruction':'Synthetic display verification only.','rationale':'Exercise real resolver output.',
        'status':status,'authority':authority,'policyLevel':level,
        'applicability':{'teams':teams or [],'seasons':seasons or [],'profiles':profiles or []},
        'evidence':[{'type':'git','repository':'fixture/only','commit':'abcdef1','file':'README.md','line':1}]
    }
    if status=='approved':
        value['approval']={'approver':'synthetic-fixture','role':'overall_software_lead','approvedAt':'2026-10-23T00:00:00Z'}
    return value

rules=[
    rule('shared.display-multi','display-multi','candidate',teams=['16093'],seasons=['2026-2027'],profiles=['command-based']),
    rule('shared.display-rejected','display-rejected','rejected'),
    rule('shared.display-deprecated','display-deprecated','deprecated'),
    rule('official.display-conflict-a','display-conflict',authority='official',level='global'),
    rule('official.display-conflict-b','display-conflict',authority='official',level='global'),
    rule('shared.display-conflict-lower','display-conflict'),
    rule('official.display-override','display-override',authority='official',level='global'),
    rule('shared.display-override-lower','display-override'),
    rule('shared.display-unrelated','display-unrelated')
]
(qa_knowledge/'display.yaml').write_text(json.dumps({'schemaVersion':4,'rules':rules},ensure_ascii=False,indent=2)+'\n')
print(qa_root)
```

JSON 是此处 YAML 输入的合法表达；不需要 YAML 写入依赖。先给该临时根启动独立 Dashboard、保留会话并确认连接/9条规则，再执行 validate/resolve，符合仓库工作流。把 Python 返回路径作为 `QA_ROOT` 实际值，shell 操作正确引用路径，不改 HOME/CODEX_HOME。

```bash
FTCKB_AUDIT_DIR="$QA_ROOT/audit" apps/knowledge-cli/build/install/ftckb/bin/ftckb dashboard --knowledge "$QA_ROOT/knowledge" --no-browser
```

在另一会话使用相同 audit 环境：

```bash
FTCKB_AUDIT_DIR="$QA_ROOT/audit" apps/knowledge-cli/build/install/ftckb/bin/ftckb validate "$QA_ROOT/knowledge" --json
FTCKB_AUDIT_DIR="$QA_ROOT/audit" apps/knowledge-cli/build/install/ftckb/bin/ftckb resolve "$QA_ROOT/knowledge" --team 20827 --season 2025-2026 --generic-profile --json
```

预期 validate 退出 0、9 条；resolve 退出 2、ok=false、2 active、3 excluded、2 overridden、1 conflict。`shared.display-multi` 的 reasons 必须同时含 profile/season/status/team。冲突 topic 是 display-conflict；其两条 official ID 同时出现在 conflict 与 lower 项的 winnerIds，均不在 active 中。不要把预期退出 2 当执行失败或改 fixture 消除冲突。

- [ ] **Step 6: 在真实浏览器验收新资源。** 打开本次临时实例私下完整链接，确认页面已连接、实际临时知识根和9条规则，token 不进入截图、repo或共享日志。进入「归属与裁决」，填写 20827、2025-2026、generic、normal 并提交；不要用截屏替代提交动作。检查：

| 场景 | 必须观察到 |
| --- | --- |
| multi candidate | 四条中文原因同时显示，没有裸 `profile · season · status · team` |
| rejected/deprecated | 使用通用非 approved 说明，不能称候选规则 |
| 唯一覆盖 | lower 条目显示最高有效层级及最高层级 official ID；active 仍有该 official ID |
| 冲突覆盖 | lower 条目列出两个最高层级 ID；无「胜出规则」措辞；冲突说明当前主题未产生生效规则 |
| 其他主题 | 两个 active 条目继续存在，不能把局部冲突误作所有主题都无结果 |
| 现有交互 | 被排除规则链接仍可打开详情，浏览器无脚本异常 |

比较修改后源码、安装包中的 `/dashboard/app.js` 与临时服务 GET `/app.js` 的内容摘要，确认所验页面资源对应本次改动。只报告实际观察；没有浏览器能力或临时服务失败时明确标为未完成。不要声称主 Dashboard 已升级。

- [ ] **Step 7: 独立复核并交付。** reviewer 检查最终 app.js diff、原因保真、未知码 fallback、无优先级重实现、构建和浏览器证据；真实库规则与审批状态由 root 按当前事实汇总，并行审批可能改变 approved/candidate 分布，不将调查时的 42/10 作为固定条件。仅交付本文件范围的源修改，不 stage 临时 fixture/截图/token/其他 owner 文件，不 commit/push。报告具体测试数量以实际结果为准，不复用历史数字。

计划自查：设计要求全部映射到步骤；没有新 schema/API、审批、规则写入、网络/模型服务、UI 框架或替代裁决逻辑。仅开发验收使用明确合成上下文；不改变真实项目选择。
