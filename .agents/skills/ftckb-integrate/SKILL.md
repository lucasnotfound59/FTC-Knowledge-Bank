---
name: ftckb-integrate
description: 将固定版本的 FTC Knowledge Bank 接入现有 FTC Git 项目，安装项目配置、submodule 和运行时规则 Skill，或明确升级已接入版本。
---

# FTC Knowledge Bank 项目级接入

适用于用户要求“把此仓库接入当前 FTC 项目”。这不是全局 Skill 安装，也不把知识库作为机器人 Android 运行依赖。

首次接入顺序：**doctor → dry-run → prepare → 目标实际 Dashboard 页面已连接 → accept**。接入后的日常编码顺序：**doctor → Dashboard 已连接 → work resolve → 修改 → work check --receipt ID**。先运行已审阅 checkout 的只读入口：

```bash
python3 /path/to/reviewed-FTC-Knowledge-Bank/.agents/skills/ftckb-integrate/scripts/project.py doctor --project /path/to/FTC-project
```

缺配置会报告需安装；仍须下列明确参数。doctor 不构建、不联网、不运行内核、不改配置/pin；独立 `doctorSchemaVersion:1`，退出 0/1/64 分别表示接入检查通过/问题或未知/参数错误。`nextSteps.cwd/argv` 仅建议。未初始化或旧 pin 无 doctor 均用此外部入口，后续 resolve/check 仍走原固定源码，不隐式升级。接入检查通过不代表规则、代码或真机通过。

启动或复用实际 knowledge 路径及审计目录一致的 Dashboard，确认本次链接可访问、页面已连接与规则目录正确后再裁决/编码；不要将 token 写入仓库或公开日志。旧 pin 不支持 Dashboard 时明确说明限制并显式选择升级，不暗改 pin。安装/升级才重新生成托管模板，不用新模板覆盖旧项目文件。

1. 确认目标 Git 根目录、`TeamCode` 和 Gradle 项目结构。目标须已有提交。保留当前分支及所有用户修改。
2. 获取明确队号。用户未提供且 `.ftckb/project.yaml` 不存在时，询问，不能默认 README 的 20827。赛季仅接受用户明确值或现有项目配置；无法唯一确定时询问，不按当前日期猜测。
   新安装还必须明确 profile：`--profile rookiebot`、`--profile simple-opmode`、`--profile command-based`、`--profile ftclib-command`，或明确无项目风格的 `--generic-profile`。不能从队号猜测 profile；选择不明时询问。`--profile` 可重复但不可重复同名；与 `--generic-profile` 互斥。`rookiebot` 隐含 `simple-opmode`，`ftclib-command` 隐含 `command-based`；隐含后 simple/command 两类不可混用。
3. 选定包含本 Skill 的发布 tag 或完整 commit SHA，向用户说明所选版本。若从仓库链接开始，可以在临时目录克隆上游读取本入口；将已审阅 checkout 的完整 SHA 传给安装器。不传 `main`，不把旧 V0.1.1 文案当成已有 tag。
4. Python 3.10+、Git、JDK 21+ 是前提；正式安装还需同一 Python 环境中的 `jsonschema`（见 `scripts/requirements.txt`）。可在用户认可的虚拟环境安装，不写个人 SDK/JDK 路径或 API key。首次 Gradle 构建需要缓存或网络。
5. 用实际 help 已支持分阶段参数的已审阅入口，先 dry-run，再 prepare。用户已经授权接入且预览范围一致时可继续；只有参数不明或将覆盖用户内容时停下来说明。

```bash
python3 /path/to/FTC-Knowledge-Bank/.agents/skills/ftckb-integrate/scripts/integrate.py \
  --project /path/to/FTC-project --team TEAM --season YYYY-YYYY --ref FULL_COMMIT_SHA \
  --profile USER_SELECTED_PROFILE --prepare-only --dry-run
```

把 `USER_SELECTED_PROFILE` 替换为用户明确选择的支持值；若明确不选风格，用 `--generic-profile` 替换整个 `--profile USER_SELECTED_PROFILE`。不把 generic 当作缺省值。

去掉 `--dry-run`、保留 `--prepare-only` 执行同一命令。Windows 用 `py -3`，含空格路径加引号。准备创建配置、固定 submodule、Skill 和 AGENTS 托管段，并构建独立 CLI；不运行内核、不创建 work receipt、不启动 Dashboard。submodule add 暂存 `.gitmodules` 和 gitlink；其他文件不自动暂存，不 commit/push，不改分支。dry-run 只有计划，没有可验收的摘要。

6. 准备成功输出 `installationWorkflowSchemaVersion:1`、`stage="prepared"`、`ok=true`，`kernelStatus` 和 `acceptanceStatus` 均为 `not_performed`，不能宣布 installationOk 或代码通过。保存真实 `preparedContextSha256`。从 `nextSteps` 数组的 `code="workflow-env"` 项取得明确 `FTCKB_AUDIT_DIR`，Dashboard、accept 和后续 work 保持同一值。
7. 先复用身份一致的实例；没有实例且 `code="dashboard"` 项给出了实际 `cwd/argv/env` 时才按其启动。受保护 argv 使用当前已审阅 checkout 中 `dashboard_runtime.py` 的绝对路径，传目标固定源码的完整 distribution 与实际 knowledge；cwd 为项目根，使用同一显式绝对审计目录。旧 pin 不必有 helper，运行的二进制仍来自旧 pin，不改 pin。能力 unknown、缺 helper 或平台不支持而未给受保护 argv 时，核对支持或显式选择升级，不猜命令。必须使用目标实际 canonical knowledgePath，上游同内容服务不能代替。打开完整链接，实际确认页面已连接、路径正确、规则已加载。完整链接不放 argv、仓库或公开日志。
   新保护入口只支持 Python 3.10+ 的 POSIX（macOS/Linux），从构建完成的完整 distribution 私有副本启动；knowledge/audit 不复制。直接 CLI 仍兼容但无快照保护，Windows 新入口不支持，不因此新增 doctor 必需失败。正常结束及处理 SIGINT/SIGTERM 后仅清理本次已退出 child 的目录，SIGKILL/断电可能残留，不自动回收用户实例。任务收尾保留用户需要的 Dashboard；详见上游 `docs/dashboard.md`。
8. 在仓库外用编辑器创建真实私有 JSON，仅含 `url` 与本次完整链接。POSIX 创建前先 `umask 077`，权限 `0600` 或更严格，拒绝 symlink。不要让凭据进入命令历史。实际观察页面后，用 `code="accept"` 项的 `entrypoint`、真实摘要和私有文件组成命令：

```bash
FTCKB_AUDIT_DIR="/actual/audit/root" python3 /path/to/reviewed-FTC-Knowledge-Bank/.agents/skills/ftckb-integrate/scripts/verify.py \
  --project "/path/to/FTC-project" --accept \
  --prepared-context-sha256 RETURNED_64_CHARACTER_SHA256 \
  --connection-file "/private/path/dashboard.json" --browser-confirmed
```

占位全部替换，audit env 使用准备输出的原值。`--browser-confirmed` 记录操作者已实际观察页面的声明，未观察不能设置，不是机器证明。accept 不构建、不 fetch、不改配置/pin/托管内容，先重核准备摘要与实际服务知识/审计身份，再以 normal 执行 validate→resolve→check。服务 binary pin 仍为 unknown。缺连接文件或页面观察时，不将 accept 描述拼成假可执行 argv。

9. `installationOk=true` 表示接入有效；`projectCheck.ok=false` / exit 1 表示目标有硬违规，不能宣布代码合规。exit 2 是准备/连接/加载/冲突或审计未完整核验，exit 64 为参数错误。`checks.<command>.kernel` 保留实际内核输出。soft 必须逐项报告，构建不代表部署或真机通过。
10. 构建失败、缺产物或输出丢失时，修复原因后以相同明确参数重跑 prepare。`preparation_changed` 先审阅配置/pin/managed/knowledge/CLI/audit变化再 prepare；`connection_failed` 修复连接，不跳过。普通 TeamCode diff 不使摘要失效。人工修改托管内容时不强制覆盖，项目补充通常放托管段外。

新 flag 仅传给实际 help 已支持的已审阅入口；可用外部新工具处理旧目标，不以新模板替换旧 pin 模板。无新 flag 的旧 integrate/verify 保留合并构建/验收，供已有自动化兼容，没有首次安装中连接目标 Dashboard 的窗口；新手优先分阶段流程。目标缺能力时明确限制、显式决定升级，不暗改 pin。

新配置是 JSON 语法的 YAML 1.2，版本字段为 `schemaVersion:2`、`kernelSchemaVersion:2`、`integrationVersion:2`，并包含 `profiles` 字符串数组；generic 写为 `profiles:[]`，不是缺失或 null。数组保留用户原选项，脚本为 resolve/check 展开隐含 profile 并验证内核返回的已规范化数组。

版本来自所选固定源码中的 `assets/integration.json`，不是运行安装器的当前 checkout。仅支持全 1 或全 2 的版本组合。无 manifest 仅兼容既有 v1 固定安装：重跑保留 v1 配置和 SHA，按该 pin 的 v1 Schema 验证且不传 profile flags；不允许新装 v1、向 v1 传 profile 或自动将 v2 降为 v1。已有 v1 项目使用新版安装器显式 `--ref TAG_OR_SHA` 升至 v2 时，必须明确选 profile/generic。

已安装项目重跑可省略 team/season/ref，默认重用配置中的完整 SHA，不重新追踪 tag。有效 v2 配置在重跑或升级时省略 profile 会保留原选择。升级须明确 `--ref TAG_OR_SHA`；不要执行 `submodule update --remote`。verify 输出协议版本和 v2 原选 profiles；仍须区分安装成功、硬检查通过与真机验证。

可选的机器核验入口是 `project.py dashboard verify --project . --connection-file PRIVATE_JSON`；它只对显式已知现有服务做 authenticated GET，核对实际知识输入与审计存储身份，不扫描/启动/关闭实例。私有文件只含本次完整链接的 `url`，POSIX须当前用户所有、无组/其他权限且路径无symlink；token不进argv或仓库。旧pin无入口时用已审阅新checkout的绝对project.py入口，保留目标pin。exit0仍不代表浏览器页面已连接或服务binary pin已证明；旧API/未知store不能全绿。成功返回的workflow-env适用于后续resolve和收据返回的check argv，必须保留同一显式FTCKB_AUDIT_DIR。详见 `docs/dashboard.md`「核验一个已有连接」。

接入后的推荐流程使用 `project.py work resolve --project .`，按返回的 `nextSteps` 明确 `work check --receipt UUID`。原 resolve/check 默认行为兼容；工作收据位于 Git metadata，绑定配置原字节和全部实际 YAML 输入，不猜配 audit，不代表身份/语义/真机。work 不自动构建，mode 必须显式匹配。

完整命令、配置格式、恢复和可选 CI：读取上游 `docs/project-integration.md`。目标 Agent 是否自动发现 `.agents/skills` 取决于平台；不支持时让其显式读取目标 Skill，不能承诺给任意 Agent 链接就自动触发。
