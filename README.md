# FTC Knowledge Bank

**版本：V1.5.0**

面向 FTC 队伍与编码 Agent 的工程知识库：把有来源、经审批的规范接入开发流程，在写码前取得规则，完成后检查改动。

**第一次使用：[本地新手指南](docs/getting-started.md)** · [完整文档站](https://ftckb.lucasxl.com)

新手指南按「浏览教程 / 首次接入 / 已接入后编码 / 遇到错误」分流，解释 profile、固定版本与每一步的完成条件。

已接入目标项目先从其 Git 根目录运行只读诊断：

```bash
python3 tools/FTC-Knowledge-Bank/.agents/skills/ftckb-integrate/scripts/project.py doctor --project .
```

未初始化或旧 pin 没有 doctor 时，使用独立已审阅 checkout 的绝对入口，`--project` 指向目标；不会改变目标 pin，后续裁决仍用固定源码。doctor 的独立 JSON/退出码及恢复示例见[项目接入](docs/project-integration.md#只读接入诊断)。它不构建、联网、启动服务或执行内核；接入检查不代表规则、代码、构建来源或真机通过。

## 人需要时再打开 Dashboard

AI 接入与编码通过 CLI 完成，不自动启动、连接、截图或浏览器核验 Dashboard。人需要查看规则、已有调用审计或进行人工复核时，再显式打开 Dashboard；按以下步骤操作：

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
   python3 .agents/skills/ftckb-integrate/scripts/dashboard_runtime.py \
     --distribution apps/knowledge-cli/build/install/ftckb \
     --knowledge "$FTCKB_KNOWLEDGE_ROOT" --no-browser
   ```

   无需模型 API key，仅监听 `127.0.0.1`，默认空闲端口；可加 `--port 8080`。去掉 `--no-browser` 可自动尝试打开浏览器。

   推荐入口要求 Python 3.10+ 和 POSIX（macOS/Linux），从构建完成的完整 distribution 创建私有副本后启动；后续 `installDist` 更新原产物不影响该副本。知识库和审计目录仍使用实际路径。直接 `ftckb dashboard` 保持兼容，但没有此保护；Windows 新保护入口不支持。人要求在接入项目打开管理台时，使用已审阅 checkout 的 `dashboard_runtime.py` helper，显式传目标固定 distribution、实际 knowledge 和同一审计环境；旧 pin 不必含 helper，不改 pin。缺 helper 或平台不支持时不声称受保护。默认 doctor/prepare 的 `nextSteps` 只引导 CLI 工作。生命周期与限制见[管理台指南](docs/dashboard.md#稳定副本与生命周期)。
4. **核验并保持运行。** 看到 `dashboard=ok` 后打开完整 `url=` 链接，确认页面已连接、知识库路径正确且规则已加载，才可宣称人请求的页面核验完成；CLI 规则/代码流程独立执行。`#token=` 是会话凭据，不写入仓库或公开日志；页面移除片段后，浏览器刷新如缺凭据就重新打开原链接。失败须如实报告，不能声称已启动。后续工作用另一会话，保留管理台进程，任务收尾不自动关闭。

管理台提供规则搜索与编辑、候选批准/拒绝、指定队伍/赛季/profile 的裁决解释，以及本版本记录的调用审计与 soft/dev 逐项复核。规则修改先预览差异并校验完整知识库，再保存；修改已批准规则的实质内容或归属会转回候选并清除旧批准记录。审批校验的是声明的角色与队伍，不是身份认证。

审计默认保存到 `~/.ftckb/audit`，用 `FTCKB_AUDIT_DIR` 可更换目录；需要共享记录的 CLI 和管理台进程应使用相同审计目录和知识库规范路径。**CLI 独立写入审计日志，Dashboard 读取这些共享日志；人可稍后用同一知识库和审计目录查看已有记录。AI 不依赖管理台运行。** 旧固定 commit 副本不会自动获得审计或编辑前检查，未接入的外部 Agent 行为也不可观测。复核声明独立追加到同分区 `reviews.jsonl`，可选已核对/需修改/本次不适用，必须填写理由与复核人；身份为声明，证据链接不会抓取。声明只说明指定历史快照有人声明复核，原检测仍保留，不代表当前代码或真机通过。缺版本时保留未知，后续可比较检查变化后须重新复核；外部代码修改后必须重新运行检查，历史补丁正文不保存。使用流程与覆盖边界见[本地管理台指南](docs/dashboard.md)。

## 快速接入

把下面的指令交给你使用的编码 Agent，替换目标项目、队号和赛季：

```text
将 https://github.com/lucasnotfound59/FTC-Knowledge-Bank 接入我的 FTC 项目。
目标项目：<项目路径>；队号：<实际队号>；赛季：<YYYY-YYYY>；profile：generic（或明确选择 command-based / ftclib-command / rookiebot / simple-opmode）。
请读取 .agents/skills/ftckb-integrate/SKILL.md，固定已审阅的版本，先预览再安装。
AI 使用确定性 CLI 完成接入和编码检查，不自动操作 Dashboard；人需要查看规则或审计时再打开。
```

接入后的日常流程：**doctor → work resolve → 修改 → work check --receipt ID**。`project.py work resolve --project .` 返回显式 UUID 和完整下一步 `work check --receipt ID`，配置或实际规则变化会拒绝旧收据。收据在 Git metadata，不进入工作区 diff；旧 resolve/check 仍兼容但无关联证明。这些确定性命令在本地运行，不需要额外模型 API key；编码 Agent 自身的登录与费用由其供应商决定。

可选用 `project.py dashboard verify --project . --connection-file PRIVATE_JSON` 只读核验一个显式已有连接的实际库/审计身份；不代替页面确认、不证明服务binary pin、不管理实例。私有凭据与后续显式audit env见[连接核验](docs/dashboard.md#核验一个已有连接)。

需要 Git、Python 3.10+、JDK 21+ 和 jsonschema。安装步骤、依赖配置与版本升级见[接入指南](https://ftckb.lucasxl.com/getting-started/integrate/)。

## 文档导航

命令架构入口：[Command-Based：FTCLib、类 FTCLib 与自有实现](knowledge/guides/architecture/command-based.md)。自有框架选 `command-based`，实际使用 FTCLib command 运行时及其 API 选 `ftclib-command`；专区四条新规则已批准，适用于 command-based（含 ftclib-command 继承），触发时只产生条件式 soft；项目实际生效结果以 resolve 为准。

| 栏目 | 内容 |
| --- | --- |
| [项目介绍](https://ftckb.lucasxl.com/introduction/) | 目标、适用人群与能力边界 |
| [本地新手指南](docs/getting-started.md) · [在线快速开始](https://ftckb.lucasxl.com/getting-started/) | 按目的选择步骤、第一次接入与编码闭环 |
| [核心概念](https://ftckb.lucasxl.com/concepts/rules/) | 规则、证据、队伍与赛季、审批与裁决 |
| [使用指南](https://ftckb.lucasxl.com/guides/cli/) | CLI、聊天编辑、本地网页与 Android Studio |
| [Agent 接入](https://ftckb.lucasxl.com/agent/workflow/) | 编码流程、固定版本、验收与升级 |
| [FTC 教程](https://ftckb.lucasxl.com/tutorials/sdk/) | SDK、Pedro、Dashboard 与故障排查 |
| [维护与参考](https://ftckb.lucasxl.com/reference/contributing/) | 添加知识、审批、字段、命令与 JSON 契约 |

## 项目状态

已实现规则裁决、diff 检查、项目级接入、候选提取与审批、Ask/Edit、本地聊天网页、独立知识库管理台及 Android Studio 插件。Run 模式、官方文档联网检索和 Control Hub 自动部署尚未实现。静态检查不替代真机验证。

当前规则总数为 52（46 已批准 + 6 候选）。裁决器、CLI、运行时和固定版本接入均传递显式 profile；20827／16093 的 8 条规则已迁移到 global，保留 2025-2026 赛季与 2 条 candidate，并增加 3 条 command-based 规则。`global.test-utility-layout` 与 `global.vendor-documented-build-dependencies` 是独立的跨赛季 global 规则。相同赛季和 profile 下两队当前 active IDs 相同；这不是忽略队号的理由。

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
