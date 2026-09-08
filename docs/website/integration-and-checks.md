# 项目接入与确定性检查

本文是文档站新增功能的技术正文，按网站七个栏目组织。安装细节以 [项目接入指南](../project-integration.md) 为准，机器输出以 [Kernel 契约](../kernel-contract.md) 为准。接入功能的公开可用版本需在发布前确认，不能将 README 中的 V0.1.1 当作包含新功能的已发布 tag。

## 一、项目介绍：将规范接入日常编码

FTC Knowledge Bank 将有证据、经审批的队伍规范交给编码 Agent 使用。Agent 在修改前取得当前队伍与赛季的生效规则，修改后对 diff 执行检查。规则选择由确定性 CLI 完成，而不是让模型自行解释规则优先级。

项目级接入适合已有 FTC Git 工程、希望队员和不同编码 Agent 共用规范的队伍。安装器固定知识库版本并生成项目配置；知识库作为独立工具运行，不加入机器人 Android Gradle 模块。

安装与 validate、resolve、check 不调用模型，不需要额外 API key。编码 Agent 自身的登录与费用仍由其供应商决定。使用知识库自带的聊天或网页 Agent 时，需另行配置模型供应商。

## 二、快速开始：安装与第一次校验

目标项目必须有至少一个 Git 提交，包含 TeamCode 和 settings.gradle 或 settings.gradle.kts。环境需要 Git、Python 3.10+、JDK 21+，以及安装在同一 Python 环境中的 jsonschema>=4.18,<5。首次构建需要依赖缓存或网络。

先在已下载的知识库根目录构建并校验：

```bash
./gradlew :apps:knowledge-cli:installDist
apps/knowledge-cli/build/install/ftckb/bin/ftckb validate knowledge --json
```

validate 退出 0 且返回 ok:true 表示知识文件通过校验，不代表已经接入目标项目。

接入时向 Agent 提供目标项目、实际队号和赛季，并让它读取上游 `.agents/skills/ftckb-integrate/SKILL.md`。不同 Agent 是否自动发现 Skill 取决于产品；必要时显式要求读取文件。

从包含接入工具且已审阅的上游版本运行安装预览：

```bash
python3 .agents/skills/ftckb-integrate/scripts/integrate.py \
  --project "/path/to/FTC project" --team 16093 --season 2025-2026 \
  --ref FULL_COMMIT_SHA --dry-run
```

路径、队号、赛季和 FULL_COMMIT_SHA 都是示例，必须替换。ref 接受实际存在的 tag 或完整 40 位 commit SHA，不接受分支名。预览可能查询远程，但不写目标文件、index 或 Git 配置，也不构建。审阅后去掉 --dry-run 执行同一命令。

## 三、核心概念：版本、规则与审批

队号和赛季决定适用规则，示例队号不是默认值。候选规则不进入生效结果；OFFICIAL、TEAM、SHARED 的优先级及冲突检测由 resolve 实现。不同队伍的结果可能不同。

规则证据用于追溯来源，审批记录用于表达认可状态。证据存在不等于网页内容已经被在线验证，规则通过静态检查也不等于机器人行为已通过真机验证。

项目配置固定完整 commit，使队员使用同一知识版本。仓库发布版本、CLI 版本和 kernel schemaVersion 是独立概念；接入包装器要求受支持的版本及配置一致性。

## 四、使用指南：接入工具与交互客户端

项目级接入供现有编码 Agent 调用 CLI。知识库自带的聊天、Edit、本地网页及 Android Studio 插件分别在使用指南中说明，避免将客户端配置与项目安装混在一起。

ftckb serve 是本机单会话界面。文档网站提供操作说明与参考内容，网页访问本身不会启动本地 Agent 或连接机器人项目。

## 五、Agent 接入：配置、编码和验收

安装器生成 `.ftckb/project.yaml`、`.agents/skills/ftc-knowledge-bank/SKILL.md` 和 AGENTS.md 托管段，并在 `tools/FTC-Knowledge-Bank` 安装固定 commit 的 submodule。

配置文件使用 JSON 语法（合法 YAML 1.2）；当前包装器不支持普通缩进 YAML。配置记录队号、赛季、来源版本、submodule 路径和托管内容摘要，不保存模型 key。摘要用于避免意外覆盖，不是防篡改签名。

相同输入重复执行不会产生新 diff。已有配置缺省复用固定 SHA；升级必须显式给出新 ref。遇到托管内容人工修改、路径冲突或 submodule 脏改动时，安装器停止。AGENTS.md 托管段以外的内容保留。

安装会暂存 .gitmodules 和 submodule gitlink，不自动 commit/push、切换分支或安装 hooks。

### 日常编码

在已接入的目标项目根目录运行：

```bash
python3 tools/FTC-Knowledge-Bank/.agents/skills/ftckb-integrate/scripts/project.py resolve --project .
```

Agent 读取 activeRules 后修改代码，再运行：

```bash
python3 tools/FTC-Knowledge-Bank/.agents/skills/ftckb-integrate/scripts/project.py check --project .
python3 tools/FTC-Knowledge-Bank/.agents/skills/ftckb-integrate/scripts/verify.py --project .
```

包装器核对固定版本、托管内容、schema、退出码和输出一致性。构建日志写 stderr，kernel JSON 保持 stdout 可解析。非 JSON 或不支持的 schema 不得当作成功。

| verify 结果 | 含义 |
| --- | --- |
| installationOk=true，projectCheck.ok=true，退出 0 | 接入有效，当前 diff 无硬违规；soft 仍需评估 |
| installationOk=true，projectCheck.ok=false，退出 1 | 接入有效，代码存在硬违规 |
| installationOk=false，退出 2 | 配置、版本、构建、schema、知识加载或冲突等错误 |

project.py 成功调用透传 kernel JSON；包装器自身失败使用独立的 integrationOk=false 输出，不能与 kernel 错误混淆。

### 团队协作与升级

新队员克隆已提交接入配置的项目后执行：

```bash
git submodule update --init -- tools/FTC-Knowledge-Bank
python3 tools/FTC-Knowledge-Bank/.agents/skills/ftckb-integrate/scripts/verify.py --project .
```

升级时在安装器中显式指定新 tag 或 SHA，先 dry-run，再安装并验收。不要用 submodule update --remote 代替版本升级流程。构建失败后可修复 JDK、权限或网络并重跑；路径和版本冲突应先检查配置与 Git 状态。

## 六、FTC 教程：在实例中使用接入流程

SDK、Pedro Pathing、Dashboard 和故障排查等教程继续归入 FTC 教程栏目。涉及目标项目代码修改时，链接到 Agent 接入的日常编码流程，并保留各教程的适用环境和实际验证步骤。软件检查通过不能替代坐标、方向、传感器和执行机构的真机验证。

## 七、维护与参考：检查语义与接口

### diff 覆盖范围

默认检查合并 HEAD→index 和 HEAD→工作区的变化，包含非忽略 untracked 文件并去重。暂存的违规不会因为只在工作区撤销而漏检。仅存在于 index 的行号需结合 staged diff 阅读。

路径规则检查触及路径，包括删除、只删行、空文件和重命名前后路径；正则规则只检查新增行。显式 --diff 使用补丁作为检查输入；空补丁合法，无法解析的非空补丁报错。

| 检查类型 | 语义 |
| --- | --- |
| path-forbidden | 变化集合触及禁止路径即违规 |
| path-required | 变化集合必须包含匹配路径；本身没有“改 X 才触发”的条件 |
| regex-required | 适用文件的新增行集合必须包含匹配模式 |
| regex-forbidden | 新增行出现禁止模式即违规 |

无 checks 的生效规则返回 soft 提醒。已知两条 Limelight regex-required 的 Java 适用范围过宽，可能误报无关新增代码；应报告并交维护者处理，不能插入无意义调用绕过。

### JSON 与退出码

kernel schemaVersion 当前为 1。check 的 violations 包含 ruleId、check、pattern、detail，以及可选 path/line；soft 包含 ruleId/note。退出码 0 表示无硬违规，1 表示存在硬违规，2 表示加载、校验或冲突等错误，64 表示参数错误。

兼容细节：resolve 的 checks[].kind 使用下划线，例如 path_forbidden；知识 YAML 和 check 违规字段使用连字符，例如 path-forbidden。消费者应使用仓库 Schema 与 fixtures 对拍，不能自行统一字符串后假定兼容。

### CI 和维护入口

Skill 依赖 Agent 遵循，不能强制阻止所有不合规编辑。CI 和分支保护需另行配置；PR 已是提交状态，必须用可信 base/head 生成明确差异并通过 --diff 检查，不能用空工作区 diff 充当 PR 验收。

添加知识和候选审批的操作链接到 [候选提取文档](../candidate-extraction.md)。修改知识后必须 validate 成功。完整 CI 示例、恢复流程和带日期的验收记录集中在 [项目接入指南](../project-integration.md)，避免重复维护测试数量。
