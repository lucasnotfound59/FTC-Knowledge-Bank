# 显式测试／dev 工作模式 — 设计说明

日期：2026-09-23。用户批准：2026-09-23（“ok”，针对本设计的口头概要）。

## 目标与边界

用户明确说当前任务是测试代码时，Agent 应优先实现其测试目的，不为了符合正式架构而强迫使用 `CommandBase`，且不要求先提交编码 plan。用户明确说当前部分是 dev 代码（新架构或新 system 的尝试）时，所有机器 hard 命中改为逐项 soft 提醒，用户可选择忽略。两种例外都只对当前任务生效，不写入项目长期配置，也不根据路径、文件名或依赖自动推断。默认正式模式完全保留现有执法。

## 选定方案

CLI 的 `resolve` 和 `check` 增加 `--work-mode normal|test|dev`，省略等同于 `normal`。项目接入脚本原样传递显式参数；运行时 Skill 只有在用户明确声明时才选 `test` 或 `dev`，并在交付中写明模式及其适用文件。命令仍使用同一 team、season、profile。不能从 `--profile command-based` 推断代码一定是正式命令代码。

`test` 模式在裁决时仅豁免强迫测试代码采用正式命令架构的规则 `global.command-responsibilities` 与 `shared.ftclib-command-candidate`，将其放入 `excludedRules`，原因记为 `work-mode-test`。其余规则（包括实际使用 Command 时的实时输入、安全清理、`global.test-utility-layout` 的 `tests/` 与 `utils/` 位置及目标 TeamCode 不使用 JUnit）继续生效。Agent 可以因测试目的明确说明为何没有采用其他非机器化架构建议，但不能把“测试代码”当作全局硬检查免除。目标 FTC 项目的测试代码不要求事先提交 plan；这不取消对本知识库功能开发本身的设计、检查与验证。

`dev` 模式不改变规则优先级、状态或 resolver 结果。`check` 先按现有逻辑计算 hard 命中，再把每个命中连同 ruleId、check、path、line、detail 放入 `soft`，`violations` 为空；若规则集有效且没有其他调用错误，退出码为 0。原本的 soft 也保留。为避免把用户其他未提交改动一并放松，`check --work-mode dev` 必须同时提供本次改动的 `--diff FILE`；不提供时以 usage/64 拒绝。运行时 Agent 需说明该 diff 覆盖哪些文件、哪些项目改动被排除，并逐项报告降级提醒；用户说忽略后可继续，不得把提醒描述为“符合正式规则”。

无效知识、同层级规则冲突、错误 profile、无法解析 diff 等仍分别按现有 2/64 退出：它们表示规则裁决无法可靠完成，不是用户代码触犯 hard rule。`dev` 不把这些基础错误伪装成通过。

## 契约与兼容性

默认 `normal` 的 CLI 输出、排序和退出码保持字节级兼容。显式 `test`／`dev` 的 JSON 响应添加 `workMode`，取值为 `test` 或 `dev`；现有 kernel JSON v2 允许增加字段，因此不升 schemaVersion。`soft` 继续使用 `{ruleId,note}`；dev 转换项在 note 中携带稳定格式的原检查与位置，不增 YAML 字段。CLI 增加向后兼容参数，版本从 2.0.0 增至 2.1.0；在已批准但尚未落地的 V0.7.0 依赖规则改动之后，仓库版本按既定“每个 feat 提升一个 V0.x”约定增至 V0.8.0。接入协议和配置版本保持 v2。

## 验收

默认 normal 的 validate/resolve/check fixtures 与现有行为不变；test 只排除上述两个架构规则，其余 hard（特别是测试目录/JUnit）仍拦截；dev 在仅包含本次 diff 时把所有 hard 命中逐项移入 soft、退出 0，同时缺少 `--diff` 返回 64；project.py 正确透传；运行时 Skill 的模式选择与软提醒报告清晰。新增单元、CLI、JSON Schema 和接入脚本测试；软件验证不等于部署或真机验证。不自动 commit/push 目标 FTC 项目。
