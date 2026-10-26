# Dashboard 规则未生效原因展示修复

日期：2026-10-23。状态：root 已接受本轮小范围修复；本文件不代表实现或浏览器验收完成。

计划：[Rule Explanation Implementation Plan](../plans/2026-10-23-rule-explanation.md)。

## 目标与实际证据

让新手在现有「归属与裁决」页面读懂引擎给出的排除原因，并避免将最高层级的冲突集合看成已生效的胜者。现有 CLI JSON、Dashboard API 和裁决列表已提供必要数据，不需要新增 explain 命令或另一套裁决器。

本轮只读核验已有主 Dashboard 的 identity/rules：本仓库实际 `knowledge/`、共享审计目录、valid=true、52 条规则。实跑维护 QA 上下文 `20827 / 2025-2026 / generic / normal` 的 CLI resolve 得到 schemaVersion=2、退出 0、26 active、26 excluded、0 overridden、0 conflicts。候选与架构规则实际返回 `status`、`profile`。

源码证据：

- `modules/domain/src/main/kotlin/org/ftckb/domain/RuleResolver.kt:44`：排除原因是 `status`、`team`、`season`、`profile`，可同时存在；显式 test 另有 `work-mode-test`。
- `apps/knowledge-cli/src/main/resources/dashboard/app.js:349`：reasonLabel 只映射旧别名，当前四码会回退为裸字符串。因此 generic 中的新 command 候选显示 `profile · status`，没有说明。
- 同文件 `resolutionPanel` 把 `overriddenRules[].winnerIds` 一律称为「胜出规则」。`docs/kernel-contract.md:75` 明确该字段也可能是同主题最高层级冲突集合；冲突时没有 active 胜者。
- `docs/dashboard.md` 与 `docs/handbook/resolution.md` 已说明现有解释入口、显式上下文与冲突语义。本轮无需再增加导航或同步修改这些文档。

运行期限制：调查时主 Dashboard 的 `/app.js` GET 曾返回 500，identity/rules 先前为 200。原因未确认；不能据此宣称已验证运行中页面或旧进程已加载新资源。保留用户已有实例，新版本展示验收使用完成构建后的独立临时实例。

## 取舍

| 方案 | 判断 |
| --- | --- |
| 修正现有页面的原因码映射与覆盖文案 | 采用；直接消除已复现的解释缺口，产品改动集中于一个文件 |
| 新增单规则 explain CLI 或导出格式 | 不采用；kernel v2 已有完整、确定性解释，会重复入口与兼容面 |
| 新增规则详情的上下文查询组件 | 不采用；已有裁决页面可完成任务，本轮没有足够证据支持扩展 UI |

## 行为

当前原因码必须有以下准确中文含义；保留已有旧别名及未知码原样回退：

| 引擎原因码 | 展示文案 |
| --- | --- |
| `status` | 状态不是 approved（已批准），不参与本次生效 |
| `team` | 当前队号不在规则适用范围内 |
| `season` | 当前赛季不在规则适用范围内 |
| `profile` | 当前明确选择的 profiles 未满足规则要求 |
| `work-mode-test` | 沿用当前 test 模式明确排除说明 |

`status` 同时覆盖 candidate、rejected、deprecated，不能仅凭这一原因码声称它一定是 candidate。页面不读取当前目录状态拼接历史裁决的原因；规则链接仍可打开详情。多个原因全部保留，沿用引擎返回顺序，不改写为单一主因。

被覆盖条目的 `effectiveLevel` 标为「最高有效层级」，`winnerIds` 标为「同主题最高有效层级规则」。使用中性表述，无须让前端重新计算胜者或关联当前规则目录。冲突条目补充一句「这些规则在最高有效层级并列，该主题未产生生效规则。」其余主题的 active 列表和现有冲突提示继续显示。

这两处展示均使用传给 `resolutionPanel` 的同一次 resolution；不会从 ID、路径、依赖、旧审计或其他上下文推断适用性。

## 范围与所有权

产品 owner：`dashboard_reason_labels`。仅修改：

- `apps/knowledge-cli/src/main/resources/dashboard/app.js`

不新增模块、依赖、全局 API、CLI 参数、导出格式、后台 API、审批操作或真实规则。kernel JSON v2、Dashboard API v1、YAML v4、退出码、确定性引擎、profile 归一化与审计保持现有语义。安装器与 candidates 导出继续由其原 owner 负责。没有 commit/push 授权。

本轮不为几行文案新增镜像实现的单元测试或前端测试框架。用现有引擎/服务器测试确认边界，再用实际临时知识库产生真实结果，验证浏览器显示。

## 验收

1. `node --check` 检查修改后 app.js；由构建 owner 串行运行既有 `RuleResolverTest`、`DashboardServerTest`、`DashboardCommandTest`，构建当前资源。测试通过不代替浏览器检查。
2. 用计划中的 9 条合成规则建立 `/private/tmp` 独立知识库与审计目录。标注为展示 fixture；不写真实 `knowledge/`。在明确的 `20827 / 2025-2026 / generic / normal` 上下文执行真实 resolve：2 active、3 excluded、2 overridden、1 conflict；退出 2 是预期冲突。
3. 新建独立 Dashboard 实例并保留会话 ID；浏览器确认连接到临时根目录、9 条规则。在「归属与裁决」提交相同上下文，核对四原因同时显示中文、rejected/deprecated 未被称为 candidate、唯一覆盖与冲突覆盖均使用准确中性文案，冲突主题无 active 条目、无「胜出规则」措辞，其余两个 active 仍显示。
4. 修改后的源码、打包资源与临时服务返回的 app.js 内容一致；浏览器无脚本异常，规则链接仍可用。截图及原始 JSON 留在 `/private/tmp`，不含 token。已有主 Dashboard 不关闭、不重启、不宣称升级。
5. `git diff --check` 与最终文件 allowlist 复核通过；本修复不改变真实库的数量或审批状态。并行审批可能改变 approved/candidate 分布，以 root 与审批 owner 当前核验结果为准，不将调查时的 42/10 当作后续固定验收条件。报告把源码/测试/浏览器结果分别说明；本次不涉及 RC、DS 或真机验证。

设计自查：仅消除已观察的展示错误；不裁决规则、不猜 profile、不解除冲突、不激活候选、不扩大 UI 架构。
