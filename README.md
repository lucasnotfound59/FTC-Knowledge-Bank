# FTC Knowledge Bank

**版本：V0.7.0**

面向 FTC 队伍与编码 Agent 的工程知识库：把有来源、经审批的规范接入开发流程，在写码前取得规则，完成后检查改动。

**[完整文档 → ftckb.lucasxl.com](https://ftckb.lucasxl.com)**

## 快速接入

把下面的指令交给你使用的编码 Agent，替换目标项目、队号和赛季：

```text
将 https://github.com/lucasnotfound59/FTC-Knowledge-Bank 接入我的 FTC 项目。
目标项目：<项目路径>；队号：<实际队号>；赛季：<YYYY-YYYY>；profile：generic（或明确选择 command-based / ftclib-command / rookiebot / simple-opmode）。
请读取 .agents/skills/ftckb-integrate/SKILL.md，固定已审阅的版本，先预览再安装。
```

接入后的日常流程：**`resolve` → 按规则写代码 → `check`**。这些确定性命令在本地运行，不需要额外模型 API key；编码 Agent 自身的登录与费用由其供应商决定。

需要 Git、Python 3.10+、JDK 21+ 和 jsonschema。安装步骤、依赖配置与版本升级见[接入指南](https://ftckb.lucasxl.com/getting-started/integrate/)。

## 文档导航

| 栏目 | 内容 |
| --- | --- |
| [项目介绍](https://ftckb.lucasxl.com/introduction/) | 目标、适用人群与能力边界 |
| [快速开始](https://ftckb.lucasxl.com/getting-started/) | 安装、第一次校验与项目接入 |
| [核心概念](https://ftckb.lucasxl.com/concepts/rules/) | 规则、证据、队伍与赛季、审批与裁决 |
| [使用指南](https://ftckb.lucasxl.com/guides/cli/) | CLI、聊天编辑、本地网页与 Android Studio |
| [Agent 接入](https://ftckb.lucasxl.com/agent/workflow/) | 编码流程、固定版本、验收与升级 |
| [FTC 教程](https://ftckb.lucasxl.com/tutorials/sdk/) | SDK、Pedro、Dashboard 与故障排查 |
| [维护与参考](https://ftckb.lucasxl.com/reference/contributing/) | 添加知识、审批、字段、命令与 JSON 契约 |

## 项目状态

已实现规则裁决、diff 检查、项目级接入、候选提取与审批、Ask/Edit、本地网页及 Android Studio 插件。Run 模式、官方文档联网检索和 Control Hub 自动部署尚未实现。静态检查不替代真机验证。

当前规则总数为 48（42 已批准 + 6 候选）。裁决器、CLI、运行时和固定版本接入均传递显式 profile；20827／16093 的 8 条规则已迁移到 global，保留 2025-2026 赛季与 2 条 candidate，并增加 3 条 command-based 规则。`global.test-utility-layout` 与 `global.vendor-documented-build-dependencies` 是独立的跨赛季 global 规则。相同赛季和 profile 下两队当前 active IDs 相同；这不是忽略队号的理由。

版本轴分别是：仓库 V0.7.0、CLI 2.0.0、YAML v4、kernel JSON v2、项目接入协议 v2。来源 `authority` 与策略 `policyLevel` 分离，有效优先级为 `OFFICIAL > GLOBAL > LOCAL > SHARED`。候选不生效；同主题最高有效层级的多规则冲突必须先由维护者解决。

规范器当前有 **5 条生效规则带硬检查**：官方 build-file 保护、FTC SDK release 钉扎、FTC build-tool 保留、Dashboard 稳定版本钉扎，以及 `global.test-utility-layout`。后者跨赛季阻止可确定的错误 TeamCode 路径和 JUnit 新增：机器人侧 OpMode 只能在 `TeamCode/src/main/java/org/firstinspires/ftc/teamcode/tests/`，可复用工具只能在 `TeamCode/src/main/java/org/firstinspires/ftc/teamcode/utils/`。它不取代完整 Agent 指令对语义分类的要求；目标 TeamCode 不使用 JUnit、`src/test` 或 `src/androidTest`，但 Knowledge Bank 自身用于验证 Kotlin/CLI 的 JUnit 测试不受禁止。硬违规退出码为 1。官方 `official.keep-customizations-in-teamcode` 只对 `build.common.gradle` 保持 hard 保护；`global.vendor-documented-build-dependencies` 对 `build.dependencies.gradle` 是跨赛季的条件式 soft：任何触及该文件的 diff 都要求 Agent 用第一方厂商文档或固定 commit、精确依赖版本和 diff 逐项对应关系证明合理性，但规则引擎不联网鉴别来源，也不验证证据真伪，soft 不代表机器已证明合规。Limelight 的 validity/freshness 规则保持 approved，但改为由 `reviewTriggers` 驱动的**条件式 soft**：新增行匹配相机类型或结果读取时才进入 `soft`，不匹配不产生 Limelight soft；两种情况都保持退出码 0（只要没有其他硬违规）。soft 只要求人工/模型复核，不是机器已证明违规，也不是真机验证；**Agent 必须向用户报告**命中的 soft 项。

```bash
ftckb validate knowledge --json
ftckb resolve knowledge --team 20827 --season 2025-2026 --generic-profile --json
ftckb check <repo-root> --knowledge knowledge --team 20827 --season 2025-2026 --profile command-based --json
```

写码前与检查时必须使用同一 profile；上面两条分别演示 generic 与 command-based，不是同一项目连续步骤。`rookiebot` 隐含 `simple-opmode`，`ftclib-command` 隐含 `command-based`；generic 是显式空选择，不从依赖猜架构。

Pedro 2.1.2 教程：[参数字典](knowledge/guides/tools/pedro-pathing.md#safepedroauto-参数字典)、[四阶段实车清单](knowledge/guides/tools/pedro-pathing.md#四阶段实车测试清单)、[SafePedroAuto.java](knowledge/examples/pedro/SafePedroAuto.java)。示例默认锁定；使用本机 `ANDROID_HOME` 或 `ANDROID_SDK_ROOT` 后可运行 `./gradlew verifyPedroRelease`。软件编译通过不代表部署或实机验证通过，当前没有已完成的 Pedro 实机验证记录。指南另记录 Pedro Pathing 3 的官方依赖安装证据（`com.pedropathing:revhub:3.0.0` / `com.pedropathing:tuning:1.0.0`），它与本仓库 v2.1.2 教程、fixture 的已验证范围分开；完整 Pedro 3 API 迁移尚未实现。

发布版本、已知限制与测试记录见[版本与验证范围](https://ftckb.lucasxl.com/reference/status/)。

## 版本规则

使用 `V主版本.次版本.补丁版本`：

| 更新类型 | 递增方式 | 示例 |
| --- | --- | --- |
| 新功能（feat） | 次版本加 1，补丁号归零 | V0.6.0 → V0.7.0 |
| 修复或小补丁（patch） | 补丁版本加 1 | V0.3.1 → V0.3.2 |
| 大更新 | 主版本加 1，次版本和补丁号归零 | V0.3.1 → V1.0.0 |

每个完整的 feat 对应一次次版本更新，内部实现步骤和未完成的计划不单独计为新功能发布。README 的版本标记与 Git tag／Release 发布分开管理，不因修改版本文字而自动创建 tag 或推送。

- Agent 入口：[AGENTS.md](AGENTS.md) · [接入 Skill](.agents/skills/ftckb-integrate/SKILL.md)
- 离线参考：[项目接入](docs/project-integration.md) · [机器契约](docs/kernel-contract.md) · [维护手册](docs/handbook/)
- 问题反馈：[GitHub Issues](https://github.com/lucasnotfound59/FTC-Knowledge-Bank/issues)
