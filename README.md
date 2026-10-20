# FTC Knowledge Bank

**版本：V0.9.0**

面向 FTC 队伍与编码 Agent 的工程知识库：把有来源、经审批的规范接入开发流程，在写码前取得规则，完成后检查改动。

**[完整文档 → ftckb.lucasxl.com](https://ftckb.lucasxl.com)**

## 必做：先启动并确认 Dashboard

开始规则维护、裁决或按知识库编写代码前，**必须启动或复用当前知识库的 Dashboard，并确认页面已连接**。按以下顺序操作：

1. **先复用已有服务。** 确认已有管理台使用本次实际知识库和相同审计目录，可用就复用，不重复启动。
2. **准备 CLI 和路径。** 在 Knowledge Bank 仓库根目录执行；产物缺失时构建，源码更新后也需重新运行构建命令：

   ```bash
   if [ ! -x apps/knowledge-cli/build/install/ftckb/bin/ftckb ]; then
     ./gradlew :apps:knowledge-cli:installDist
   fi
   FTCKB_KNOWLEDGE_ROOT="$(python3 -c 'from pathlib import Path; print(Path("knowledge").resolve(strict=True))')"
   ```

   接入其他项目时，将 `knowledge` 替换为项目配置中的实际知识库路径；Dashboard、resolve 和 check 必须使用同一份知识库。
3. **没有已有实例时启动。** 在持久终端会话中运行：

   ```bash
   apps/knowledge-cli/build/install/ftckb/bin/ftckb dashboard --knowledge "$FTCKB_KNOWLEDGE_ROOT" --no-browser
   ```

   无需模型 API key，仅监听 `127.0.0.1`，默认空闲端口；可加 `--port 8080`。去掉 `--no-browser` 可自动尝试打开浏览器。
4. **核验并保持运行。** 看到 `dashboard=ok` 后打开完整 `url=` 链接，确认页面已连接、知识库路径正确且规则已加载，再开始规则/代码流程。`#token=` 是会话凭据，不写入仓库或公开日志；页面移除片段后，浏览器刷新如缺凭据就重新打开原链接。失败须如实报告，不能声称已启动。后续工作用另一会话，保留管理台进程，任务收尾不自动关闭。

管理台提供规则搜索与编辑、候选批准/拒绝、指定队伍/赛季/profile 的裁决解释，以及本版本记录的调用审计与 soft/dev 逐项复核。规则修改先预览差异并校验完整知识库，再保存；修改已批准规则的实质内容或归属会转回候选并清除旧批准记录。审批校验的是声明的角色与队伍，不是身份认证。

审计默认保存到 `~/.ftckb/audit`，用 `FTCKB_AUDIT_DIR` 可更换目录；需要共享记录的 CLI 和管理台进程应使用相同审计目录和知识库规范路径。**CLI 独立写入审计日志，Dashboard 读取这些共享日志；启动 Dashboard 是本项目要求的工作步骤，不是 CLI 记录审计的技术前提。** 旧固定 commit 副本不会自动获得审计或编辑前检查，未接入的外部 Agent 行为也不可观测。复核声明独立追加到同分区 `reviews.jsonl`，可选已核对/需修改/本次不适用，必须填写理由与复核人；身份为声明，证据链接不会抓取。声明只说明指定历史快照有人声明复核，原检测仍保留，不代表当前代码或真机通过。缺版本时保留未知，后续可比较检查变化后须重新复核；外部代码修改后必须重新运行检查，历史补丁正文不保存。使用流程与覆盖边界见[本地管理台指南](docs/dashboard.md)。

## 快速接入

把下面的指令交给你使用的编码 Agent，替换目标项目、队号和赛季：

```text
将 https://github.com/lucasnotfound59/FTC-Knowledge-Bank 接入我的 FTC 项目。
目标项目：<项目路径>；队号：<实际队号>；赛季：<YYYY-YYYY>；profile：generic（或明确选择 command-based / ftclib-command / rookiebot / simple-opmode）。
请读取 .agents/skills/ftckb-integrate/SKILL.md，固定已审阅的版本，先预览再安装。
开始规则或代码工作前，按 README 和 AGENTS.md 启动或复用实际知识库的 Dashboard，确认可用并保持运行。
```

接入后的日常流程：**启动或复用 Dashboard 并确认连接 → `resolve` → 按规则写代码 → `check`**。这些确定性命令在本地运行，不需要额外模型 API key；编码 Agent 自身的登录与费用由其供应商决定。

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

已实现规则裁决、diff 检查、项目级接入、候选提取与审批、Ask/Edit、本地聊天网页、独立知识库管理台及 Android Studio 插件。Run 模式、官方文档联网检索和 Control Hub 自动部署尚未实现。静态检查不替代真机验证。

当前规则总数为 48（42 已批准 + 6 候选）。裁决器、CLI、运行时和固定版本接入均传递显式 profile；20827／16093 的 8 条规则已迁移到 global，保留 2025-2026 赛季与 2 条 candidate，并增加 3 条 command-based 规则。`global.test-utility-layout` 与 `global.vendor-documented-build-dependencies` 是独立的跨赛季 global 规则。相同赛季和 profile 下两队当前 active IDs 相同；这不是忽略队号的理由。

版本轴分别是：仓库 V0.9.0、CLI 2.1.0、YAML v4、kernel JSON v2、项目接入协议 v2。来源 `authority` 与策略 `policyLevel` 分离，有效优先级为 `OFFICIAL > GLOBAL > LOCAL > SHARED`。候选不生效；同主题最高有效层级的多规则冲突必须先由维护者解决。

规范器当前有 **5 条生效规则带硬检查**：官方 build-file 保护、FTC SDK release 钉扎、FTC build-tool 保留、Dashboard 稳定版本钉扎，以及 `global.test-utility-layout`。后者跨赛季阻止可确定的错误 TeamCode 路径和 JUnit 新增：机器人侧 OpMode 只能在 `TeamCode/src/main/java/org/firstinspires/ftc/teamcode/tests/`，可复用工具只能在 `TeamCode/src/main/java/org/firstinspires/ftc/teamcode/utils/`。它不取代完整 Agent 指令对语义分类的要求；目标 TeamCode 不使用 JUnit、`src/test` 或 `src/androidTest`，但 Knowledge Bank 自身用于验证 Kotlin/CLI 的 JUnit 测试不受禁止。硬违规退出码为 1。官方 `official.keep-customizations-in-teamcode` 只对 `build.common.gradle` 保持 hard 保护；`global.vendor-documented-build-dependencies` 对 `build.dependencies.gradle` 是跨赛季的条件式 soft：任何触及该文件的 diff 都要求 Agent 用第一方厂商文档或固定 commit、精确依赖版本和 diff 逐项对应关系证明合理性，但规则引擎不联网鉴别来源，也不验证证据真伪，soft 不代表机器已证明合规。Limelight 的 validity/freshness 规则保持 approved，但改为由 `reviewTriggers` 驱动的**条件式 soft**：新增行匹配相机类型或结果读取时才进入 `soft`，不匹配不产生 Limelight soft；两种情况都保持退出码 0（只要没有其他硬违规）。soft 只要求人工/模型复核，不是机器已证明违规，也不是真机验证；**Agent 必须向用户报告**命中的 soft 项。

```bash
ftckb validate knowledge --json
ftckb resolve knowledge --team 20827 --season 2025-2026 --generic-profile --json
ftckb check <repo-root> --knowledge knowledge --team 20827 --season 2025-2026 --profile command-based --json
ftckb resolve knowledge --team 20827 --season 2025-2026 --profile ftclib-command --work-mode test --json
ftckb check <repo-root> --knowledge knowledge --team 20827 --season 2025-2026 --profile command-based --diff change.patch --work-mode dev --json
```

写码前与检查时必须使用同一 profile；前三条分别演示 validate、generic 与 command-based，后两条是显式 test/dev 工作模式示例，不是同一项目的连续步骤。`rookiebot` 隐含 `simple-opmode`，`ftclib-command` 隐含 `command-based`；generic 是显式空选择，不从依赖猜架构。

工作模式是逐任务的临时参数，不写入项目长期配置。`resolve`/`check` 支持 `--work-mode normal|test|dev`，省略等同 `normal`，默认输出、排序与退出码保持字节级兼容；只有用户明确说明当前是测试代码或 dev 代码时才选择 `test`/`dev`，不根据文件名、路径或依赖推断。`test` 只把 `global.command-responsibilities` 与 `shared.ftclib-command-candidate` 放入 `excludedRules`（原因 `work-mode-test`），命令安全、测试目录/JUnit 等其余硬检查继续生效；用户指定的测试代码优先实现测试目的，不需要先提交编码 plan。若脏工作区混有非测试改动，test 检查必须用 `--diff FILE` 限定测试补丁，其余改动另以 normal 检查并报告范围。`dev` 的 `check` 必须提供本次改动的 `--diff FILE`，把每个 hard 命中转成带 ruleId、check、path、line、detail 的 soft 提醒并保持退出码 0，原有 soft 保留；Agent 必须逐项报告这些降级项，用户可明确选择忽略。显式 test/dev 的 JSON 增加 `workMode` 字段（kernel schemaVersion 仍为 2），无效知识、同层级冲突、错误 profile 和坏补丁仍是 2/64 错误。dev 不是“符合正式规则”，也不是真机验证。

Pedro Pathing 3 教程：[参数字典](knowledge/guides/tools/pedro-pathing.md#safepedroauto-参数字典)、[四阶段实车清单](knowledge/guides/tools/pedro-pathing.md#四阶段实车测试清单)、[SafePedroAuto.java](knowledge/examples/pedro/SafePedroAuto.java)。示例默认锁定；官方依赖固定为 `com.pedropathing:revhub:3.0.0` / `com.pedropathing:tuning:1.0.0`，使用本机 `ANDROID_HOME` 或 `ANDROID_SDK_ROOT` 后可运行 `./gradlew verifyPedroRelease`。fixture 中的硬件名和调参值仅供编译、未经本队机器人验证；软件编译通过不代表部署或实机验证通过，当前没有已完成的 Pedro 实机验证记录。

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
