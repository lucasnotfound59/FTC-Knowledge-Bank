# 知识目录与规则字段

当前发布为 V0.8.0：CLI 2.1.0、YAML v4、kernel JSON v2、项目接入协议 v2；知识总数为 48（42 已批准 + 6 候选）。

CLI 会递归读取知识根目录中扩展名为小写 `.yaml` 或 `.yml` 的文件。当前约定布局如下：

| 路径 | 含义 |
| --- | --- |
| `knowledge/global/team-coding-standards.yaml` | 经审批的全局工程规范，仍受赛季/profile 限制 |
| `knowledge/official/rules.yaml` | FIRST 官方约束 |
| `knowledge/shared/rules.yaml` | 跨队共享规则与候选规则 |
| `knowledge/shared/setup/` | Android Studio、FTC SDK 与第三方依赖共享规则 |
| `knowledge/shared/tools/` | Pedro、goBILDA 与 Limelight 工具共享规则 |
| `knowledge/shared/practices/` | 有固定代码来源、明确适用范围与审批记录的项目实践规则 |
| `knowledge/guides/` | 面向队员的中文教程；不会被规则加载器解析 |
| `knowledge/teams/<team>/rules.yaml` | 队号专属规则与候选规则 |
| `knowledge/schema/examples/rule-example.yaml.example` | schema v1 Git 证据示例；扩展名不会被递归加载 |
| `knowledge/schema/examples/web-rule-example.yaml.example` | schema v2 网页证据示例；扩展名不会被递归加载 |

新规则使用 YAML v4；解码器兼容 schema v1、v2、v3。v1 仅支持旧式 Git 证据；v2 通过必填的 `type` 区分 Git 与网页证据。不要在这些文件中保存密钥、机器人凭据或其他秘密。

## 规则字段

根对象有两个必填字段：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `schemaVersion` | 整数 | `1`、`2`、`3` 或 `4`；网页证据使用 v2+，checks 使用 v3+，policyLevel/profiles/reviewTriggers 仅 v4 |
| `rules` | 列表 | 规则列表，可以为空 |

v1 使用旧式 Git 证据；v2 引入带 type 的 Git/网页证据；v3 在 v2 基础上允许 checks。规则字段如下：

| 字段 | 必填 | 类型/可选值 | 说明 |
| --- | --- | --- | --- |
| `id` | 是 | 字符串 | 全库唯一、稳定的规则标识 |
| `topic` | 是 | 字符串 | 用于优先级与冲突解析的规范主题 slug |
| `title` | 是 | 非空字符串 | 便于人阅读的短标题 |
| `instruction` | 是 | 非空字符串 | 代码或使用者应执行的明确动作 |
| `rationale` | 是 | 非空字符串 | 采用这条规则的原因 |
| `status` | 是 | `candidate` / `approved` / `deprecated` / `rejected` | 规则生命周期状态 |
| `authority` | 是 | `official` / `team` / `shared` | 来源身份，不等于策略层级 |
| `policyLevel` | v4 是 | `global` / `local` / `shared` | 策略层级；official 来源有效层级始终最高 |
| `applicability` | v4 是 | 对象 | v4 必须显式包含 teams/seasons/profiles；v1-v3 省略时无范围限制 |
| `evidence` | 是 | 非空列表 | 一个或多个可追溯证据对象 |
| `approval` | 否 | 对象 | 只有 `approved` 规则必须且可以包含 |
| `supersedes` | 否 | 字符串 | 被替代规则的标识元数据；当前解析器不会仅凭此字段改变优先级 |
| `positiveExample` | 否 | 字符串 | 正确做法示例 |
| `negativeExample` | 否 | 字符串 | 错误做法示例 |
| `checks` | 否 | 列表，schema v3/v4 | 机器检查；类型与语义见[规范器参考](../../docs/standardizer-check.md) |
| `reviewTriggers` | 否 | 非空列表，仅 YAML v4 | 审阅触发元数据；每项含 paths/addedLinePatterns；无 checks 的规则据此成为条件式 soft |

`applicability` 字段：

## V0.6.0 TeamCode tests/utils 布局规则

`global.test-utility-layout` 是跨赛季规则，并使当前带 hard checks 的 active 规则总数为 5。它用已有的 `path-forbidden` 和 `regex-forbidden` 可靠拦截错误 TeamCode 路径与 JUnit 新增（退出码 1）；机器人侧 OpMode 的规范路径是 `TeamCode/src/main/java/org/firstinspires/ftc/teamcode/tests/`，可复用工具的规范路径是 `TeamCode/src/main/java/org/firstinspires/ftc/teamcode/utils/`。完整 Agent instruction 仍负责不能从路径/新增行可靠判断的文件语义和 package；目标 TeamCode 的 JUnit/source-set 限制不禁止 Knowledge Bank 自身用于验证 Kotlin/CLI 的 JUnit 测试，也不证明部署或机器人运行。

## V0.7.0 Vendor-documented dependency 规则

`global.vendor-documented-build-dependencies` 是 V0.7.0 新增的 approved/shared/global 规则：`official.keep-customizations-in-teamcode` 的 hard 保护收窄到 `build.common.gradle`，不再无条件禁止 `build.dependencies.gradle`。新规则 teams/seasons/profiles 全空，`checks: []`，只有一个 path-only `reviewTriggers`：`paths: ["build.dependencies.gradle"]`、`addedLinePatterns: []`。任何触及该文件的 diff 都会产生一条条件式 soft，要求第一方厂商官方文档或官方仓库固定 commit、精确依赖版本、官方要求新增/修改的 repository 或 dependency，以及实际 diff 的逐项对应；第三方教程、博客、论坛、其他队伍代码、搜索摘要和模型回答不能单独满足要求。规则引擎不会联网鉴别域名或验证证据真伪，soft 只请求 Agent/人工打开第一方来源复核，不是机器已证明合规，也不是自动放行。Pedro Pathing 3 的当前证据是官方安装页 Manual Installation、官方 v3.0.0 Release 与官方 Quickstart 固定 commit `b4312385b7d0cc5e8dd263ec3927c9ef0cb48f36`；这属于厂商要求，不是 FIRST 撤销了 SDK 文件保护。

| 字段 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- |
| `teams` | v4 是 | 字符串列表 | 适用队号；空列表表示不限制队号。`team` 权威规则至少要有一个队号 |
| `seasons` | v4 是 | 字符串列表 | 适用赛季；空列表表示不限制赛季 |
| `profiles` | v4 是 | 字符串列表 | 空集无架构限制；否则 normalized context 必须包含全部 profiles |

v4 可选 `reviewTriggers`：出现时必须是非空列表，每项必含 `paths`（非空 glob 列表）和 `addedLinePatterns`（可为空的 Java 正则列表）。执行时，无 checks/无 trigger 是无条件 soft；无 checks/有 trigger 是**条件式 soft**，只有同一 trigger 的路径和新增行模式都匹配才输出 soft；有 checks 才是硬检查。空 `addedLinePatterns` 使 trigger 仅按路径触发；多个 trigger 之间为 OR；单个 trigger 内，路径约束和新增行模式约束必须同时满足。未知字段/重复键/错误类型/null 均拒绝；v1-v3 不允许声明 v4 字段。

支持 profiles：rookiebot、simple-opmode、command-based、ftclib-command。rookiebot 隐含 simple-opmode，ftclib-command 隐含 command-based；simple-opmode 与 command-based 互斥。规则空 profiles 不是仅限 generic，表示无架构要求。

policyLevel 约束：official 必须 global；team 必须 local 且有队号；shared 可为三者。local 必须有 teams 或 profiles 限制。v1-v3 按 authority 映射 official→global、team→local、shared→shared，profiles 为空。有效优先级 OFFICIAL > GLOBAL > LOCAL > SHARED，不从文件位置推断。

schema v1 的每个 `evidence` 对象都表示 Git 证据：

| 字段 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- |
| `repository` | 是 | 非空字符串 | 来源仓库，例如 `owner/repository` |
| `commit` | 是 | 字符串 | 精确 Git commit，7–64 位十六进制字符 |
| `file` | 是 | 字符串 | 仓库相对路径 |
| `symbol` | 条件必填 | 非空字符串 | 来源类、方法或符号；它与正整数 `line` 至少提供一个 |
| `line` | 条件必填 | 正整数 | 来源行号；它与非空 `symbol` 至少提供一个 |

schema v2 的每个证据都必须先声明 `type`。`type: git` 继续使用上表字段；不能混入网页字段：

```yaml
evidence:
  - type: git
    repository: owner/repository
    commit: abcdef1234567890
    file: TeamCode/src/main/java/example/Example.java
    symbol: Example
```

`type: web` 用于供应商或项目的官方文档：

| 字段 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- |
| `type` | 是 | `web` | 证据类型判别字段 |
| `url` | 是 | HTTPS URL | 绝对 HTTPS 地址；不能带用户名或密码 |
| `title` | 是 | 非空字符串 | 官方页面标题 |
| `publisher` | 是 | 非空字符串 | 发布者或厂商名称 |
| `accessedAt` | 是 | `YYYY-MM-DD` | 最后核验页面的日期 |
| `section` | 是 | 非空字符串 | 支撑该规则的页面章节 |
| `version` | 否 | 非空字符串 | 页面对应的库、SDK 或文档版本；数字形式应加引号 |
| `product` | 否 | 非空字符串 | 产品名称 |
| `sku` | 否 | 非空字符串 | 精确硬件 SKU |

网页证据只记录可追溯来源，不会在 `validate` 时联网，也不会认证 `publisher` 身份或证明页面一定是官方来源。维护者应在更新规则时亲自打开官方页面，核对域名、内容与发布者并更新 `accessedAt`。

`approval` 对象：

| 字段 | 必填 | 类型/可选值 | 说明 |
| --- | --- | --- | --- |
| `approver` | 是 | 非空字符串 | 审批人标识 |
| `role` | 是 | `overall_software_lead` / `team_software_lead` | 审批角色 |
| `team` | 否 | 仅数字字符串 | 队伍软件负责人审批 team 规则时必须提供并匹配唯一适用队号 |
| `approvedAt` | 是 | ISO-8601 时间字符串 | 可被 `Instant` 解析的审批时间，例如 `2026-08-13T00:00:00Z` |

`status`、`authority` 和 `role` 的值解析时不区分大小写，但仓库统一使用表中的小写形式，避免无意义的格式差异。

字段格式必须严格满足：

```text
id: ^[a-z0-9]+(?:[.-][a-z0-9]+)*$
topic: ^[a-z0-9]+(?:-[a-z0-9]+)*$
team: ^[0-9]+$
season: ^[0-9]{4}-[0-9]{4}$
commit: 7–64 hexadecimal characters
file: repository-relative, / separators only, no absolute path, backslash, empty segment, . or .. segment
line: positive integer
```

schema v1/v2/v3/v4 都采取严格解码：未知字段、重复 YAML 键、错误的集合或标量类型，以及不安全的 YAML 对象标签都会报错，而不会被静默忽略。schema v2 还会拒绝未知证据类型和 Git/网页字段混用。规则 `id` 在整个知识根目录中也不能重复。
