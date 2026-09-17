# 官方依赖文档驱动的 `build.dependencies.gradle` 修改 — 设计说明

日期：2026-09-17  
批准时间：2026-09-17T13:07:17Z  
目标版本：仓库 V0.7.0；CLI 2.0.0、YAML v4、kernel JSON v2、项目接入协议 v2 保持不变。

## 1. 问题与目标

当前 `official.keep-customizations-in-teamcode` 对根目录 `build.common.gradle` 与
`build.dependencies.gradle` 都执行 `path-forbidden`。这会阻止遵循第一方依赖文档的合法安装步骤。

Pedro Pathing 3 是当前已确认案例：

- Pedro Pathing 官方安装页在 “Manual Installation” 中明确要求打开
  `build.dependencies.gradle`，向 `repositories` 添加
  `https://repo.dairy.foundation/releases/`，并向 `dependencies` 添加 Pedro artifact；
- Pedro Pathing 官方 v3.0.0 Release 固定版本为 `v3.0.0`、release commit `fa5a07c`；
- Pedro Pathing 官方 Quickstart 的固定 commit
  `b4312385b7d0cc5e8dd263ec3927c9ef0cb48f36` 在 `build.dependencies.gradle` 中使用
  `com.pedropathing:revhub:3.0.0` 与 `com.pedropathing:tuning:1.0.0`。

目标不是完全解除根构建文件保护，而是允许**第一方官方文档明确要求**的依赖仓库或 artifact 修改，
同时让 Agent 报告证据并阻止把博客、队伍仓库或模型回答冒充官方依据。

这里的“官方文档”由用户明确为：依赖项目／厂商自己控制的官方网站或官方仓库即可，不限于 FIRST
官方材料。Pedro Pathing 官方网站和 `Pedro-Pathing` 官方 GitHub 组织因此属于合格来源；第三方教程不属于。

## 2. 规则拆分

### 2.1 保留的 hard 边界

保留规则 ID `official.keep-customizations-in-teamcode`，但把它收窄到现有 FIRST 证据实际支持的范围：

- `build.common.gradle` 继续使用 `path-forbidden`；
- 删除 `build.dependencies.gradle` 的无条件 `path-forbidden`；
- instruction、title 和 rationale 只陈述 `build.common.gradle` 与普通 TeamCode 构建定制边界；
- 不把 Pedro Pathing 的厂商文档写成 FIRST 官方要求；
- ID、topic、official authority、approved 状态、适用范围、FIRST 证据与原审批保持不变。

因此，触及 `build.common.gradle` 仍进入 `violations`，`ftckb check` 退出 1。

### 2.2 新增的条件式 soft 规则

新增独立 YAML v4 文件和 approved 规则：

```text
id: global.vendor-documented-build-dependencies
topic: root-dependency-evidence
status: approved
authority: shared
policyLevel: global
teams: []
seasons: []
profiles: []
checks: []
reviewTriggers:
  - paths: ["build.dependencies.gradle"]
    addedLinePatterns: []
```

空 applicability 使规则跨队伍、跨赛季、跨 profile 生效。空 `addedLinePatterns` 表示任何对
`build.dependencies.gradle` 的触及都会产生一条条件式 soft 复核，但不会单凭路径判定违规。

规则 instruction 必须要求 Agent 在交付前记录：

1. 第一方官方 URL 或官方仓库的固定 commit；
2. 产品／依赖名称和精确版本；
3. 官方材料要求添加或修改的 repository／dependency；
4. 实际 diff 与官方要求的逐项对应关系；
5. 构建或 Gradle sync 的实际结果，并明确它不等于部署或真机验证。

滚动网页用于说明安装流程时，应同时配合版本化 Release 或固定 Quickstart commit，避免把未来网页内容
倒推为旧版本证据。博客、论坛、其他队伍代码、搜索摘要和模型回答不能单独满足要求。

规则证据包含：

- `https://pedropathing.com/docs/pathing/installation`，publisher=`Pedro Pathing`，
  section=`Manual Installation`，accessedAt=`2026-09-17`；
- `https://github.com/Pedro-Pathing/PedroPathing/releases/tag/v3.0.0`，
  publisher=`Pedro Pathing`，version=`v3.0.0`；
- 官方 Quickstart Git 证据：repository=`Pedro-Pathing/Quickstart`，
  commit=`b4312385b7d0cc5e8dd263ec3927c9ef0cb48f36`，
  file=`build.dependencies.gradle`；
- 本设计文档的最终 commit，作为维护者选择 global/soft 执法方式的治理证据。

## 3. 运行时行为与责任边界

变更前：

```text
任何 build.dependencies.gradle diff
→ official.keep-customizations-in-teamcode hard violation
→ check exit 1
```

变更后：

```text
build.common.gradle diff
→ official hard violation
→ check exit 1

build.dependencies.gradle diff
→ global.vendor-documented-build-dependencies soft
→ 若没有其他 hard violation，check exit 0
```

soft 触发只证明“这个受保护依赖文件被触及，需要检查证据”，不能自动证明 URL 属于官方来源，也不能
自动证明依赖与版本正确。Agent 必须实际打开第一方来源并完成语义审阅：

- 证据完整且 diff 一致：报告 soft、证据与验证边界，可以继续交付；
- 缺少第一方证据、版本不对应或 diff 超出官方步骤：明确告诉用户不满足规则，并撤回或修正变更；
- 来源冲突：不得自行猜测，以版本化官方 Release／固定官方仓库内容为准并请求用户决定。

这保持了用户已定义的 hard/soft 产品语义：规则引擎不会因无法机器验证 URL 而误报 hard；模型与人工复核
仍把“必须有官方背书”作为交付要求，而不是把 soft 当作自动放行。

## 4. Pedro Pathing 3 示例

当前合格的 Pedro 3 证据链是：

```groovy
repositories {
    maven { url 'https://repo.dairy.foundation/releases/' }
}

dependencies {
    implementation 'com.pedropathing:revhub:3.0.0'
    implementation 'com.pedropathing:tuning:1.0.0'
}
```

安装流程来自 Pedro 官方安装页；精确 artifact 版本来自上述官方 Quickstart 固定 commit，并与官方
v3.0.0 Release 对应。Agent 不能仅凭本知识库中的示例升级未来版本，必须重新读取当时的第一方文档。

`knowledge/guides/tools/pedro-pathing.md` 需要明确区分：

- Pedro 3 当前安装证据与依赖坐标；
- 现有 v2.1.2 教程、fixture 和 `SafePedroAuto` 的已验证范围。

本功能不把现有 v2.1.2 Java API 示例静默描述成 Pedro 3 已验证代码。完整 Pedro 3 API／调参教程迁移是
独立功能，必须单独设计和验证。

## 5. 版本、计数和文档

该变化新增一种可复用的依赖证据工作流并改变 hard/soft 输出，因此仓库版本提升到 V0.7.0。命令、字段、
schema 与接入协议不变，所以 CLI 2.0.0、YAML v4、kernel JSON v2、项目接入协议 v2 均不提升。

新增一条 approved 规则后：

- 总规则数：48；
- approved：42；
- candidate：6；
- 2025-2026 两队 active 数量：generic 26、command-based 29、rookiebot 38、ftclib-command 30；
- 带 hard checks 的 active 规则数仍为 5；
- 修改 `build.dependencies.gradle` 时新增一条条件式 soft，其他 diff 不产生该 soft。

同步更新 README、AGENTS、CLI/集成/标准器文档、手册、网站说明、真实 validate/resolve fixtures、
项目运行时 Skill 与版本／计数验收。迁移台账必须说明：这是 2026-09-17 的维护者策略变化，不是 FIRST
撤销了对 SDK 文件的保护，也不是 Pedro 官方定义了 FTC Knowledge Bank 的优先级。

## 6. 验收

至少证明：

1. `official.keep-customizations-in-teamcode` 只剩 `build.common.gradle` hard check，原 ID、authority、
   FIRST 证据和审批保持不变；
2. 新规则为 approved/shared/global/cross-season/cross-profile，包含维护者审批和完整官方证据；
3. 触及 `build.common.gradle` 仍产生 official hard violation，退出 1；
4. Pedro 3 官方依赖 patch 触发新规则 soft、不产生该 official hard violation，并在无其他 hard 时退出 0；
5. 删除、重命名或修改 `build.dependencies.gradle` 同样触发一次 soft，因为 path-only trigger 覆盖所有触及路径；
6. 普通 TeamCode/Java diff 不产生新 soft；
7. 同一 diff 多处命中仍按 rule ID 去重并确定性排序；
8. `resolve --json` 中新规则 `checks=[]` 且包含一个 path-only `reviewTriggers`；
9. Pedro 指南准确区分 v3 安装证据与 v2.1.2 代码验证范围；
10. `validate` 为 48 条、无冲突，两个团队四种 profile 的 active 数量与本设计一致；
11. fixtures 与构建后 CLI stdout 字节一致，完整 Kotlin、Python、smoke 和 diff 检查通过；
12. 不把 Gradle sync／build 通过描述成 Robot Controller、Driver Station、部署或真机验证通过。

## 7. 非目标

- 不修改 `build.common.gradle` 的 hard 禁止；
- 不允许以第三方教程或模型输出替代第一方来源；
- 不让规则引擎联网鉴别域名、抓取网页或判断文档真伪；
- 不新增证据 manifest、check kind、YAML 字段或 kernel JSON 字段；
- 不完成 Pedro Pathing 3 的完整 Java API、AutoTune、Foresight 或现有示例迁移；
- 不升级 FTC SDK、Gradle、AGP、JDK 或其他依赖；
- 不自动 tag、push、部署或进行真机验证。
