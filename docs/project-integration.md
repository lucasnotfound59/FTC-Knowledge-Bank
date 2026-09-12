# 把知识库接入 FTC 项目

安装入口：[`ftckb-integrate` Skill](../.agents/skills/ftckb-integrate/SKILL.md)。这是项目级接入，不是全局插件安装，也不会把知识库 Kotlin 模块加入机器人的 Android Gradle 工程。

## 队员怎么用，需要 API key 吗？

给正在使用的编码 Agent 仓库链接，并明确目标项目和队号，例如：

```text
把 https://github.com/lucasnotfound59/FTC-Knowledge-Bank
接入当前 FTC 项目，队号 16093，赛季 2025-2026，显式使用 generic profile。
请读取上游 .agents/skills/ftckb-integrate/SKILL.md，并锁定已审阅的版本。
```

接入后的 `validate / resolve / check` 在本机确定性执行，**不需要 API key、不调用大模型、没有 token 费用**。队员正在用的编码 Agent 如何登录或计费，仍由那个 Agent 自己决定。只有另行运行本仓库自带的聊天/网页 Agent，才需要配置模型供应商的 key；本接入配置不保存 key。

能运行 shell、读文件的 Agent 可按此契约调用 CLI，但是否自动发现 `.agents/skills` 取决于产品。不能保证任意 Agent 仅看链接就自动安装；不支持自动发现时，让它显式读取目标 Skill。此机制不是不可绕过的编辑器拦截器。

## 前提与版本

- 目标是已有至少一个 Git 提交的 FTC Gradle 项目，包含 `TeamCode` 与 `settings.gradle` 或 `settings.gradle.kts`。
- Git、Python 3.10+、运行用的 JDK 21+。Gradle 工具链会请求 JDK 21；首次构建需要相应缓存或网络。不是任意 JDK 都能运行已编译 CLI。
- `jsonschema>=4.18,<5` 安装在运行脚本的同一 Python 环境。推荐仓库外虚拟环境，避免污染机器人项目；例如：

```bash
python3 -m venv /path/to/ftckb-venv
/path/to/ftckb-venv/bin/python -m pip install -r /path/to/FTC-Knowledge-Bank/.agents/skills/ftckb-integrate/scripts/requirements.txt
```

下文 `python3` 替换为该虚拟环境 Python。Windows 使用环境内 `python.exe`，或 `py -3`。不要向共享配置提交个人 `JAVA_HOME`、`sdk.dir` 或 SDK 绝对路径。这个独立 CLI 构建不需要 Android SDK；机器人编译仍需要队员自己的 Android SDK。

首次安装须指定包含本功能的 tag 或完整 40 位 commit SHA。仓库 V0.4.0、CLI 2.0.0、YAML v4、kernel JSON v2、项目接入协议 v2 是独立版本轴；README 的版本不代表同名 tag 已发布。正式 tag 发布前，请使用包含本 Skill 和脚本的已提交版本；审阅所选 checkout 后用 `git rev-parse HEAD` 获取完整 SHA，不要把旧版本 SHA 或 `main` 传给安装器。

## 安装与 dry-run

在已下载并审阅的上游版本中运行：

```bash
python3 .agents/skills/ftckb-integrate/scripts/integrate.py \
  --project "/path/to/FTC project" --team 16093 --season 2025-2026 \
  --ref FULL_COMMIT_SHA --generic-profile --dry-run
```

`FULL_COMMIT_SHA` 必须替换为实际完整 SHA，也可传实际存在的 tag。默认来源是官方 GitHub 仓库；测试镜像可显式 `--repository URL`。不接受 branch 名，不把 README 示例队号作为默认值；缺少 team/season 时脚本报错，Agent 应询问。

dry-run 会查询远程或在临时目录获取选定版本，以检查模板与冲突，但**不写目标文件、index、Git 配置，也不构建**。计划说明创建/更新哪些文件和 submodule。去掉 `--dry-run` 执行同一命令：

```text
目标项目/
├── .gitmodules
├── AGENTS.md                                  # 只插入/替换托管段
├── .agents/skills/ftc-knowledge-bank/SKILL.md
├── .ftckb/project.yaml
└── tools/FTC-Knowledge-Bank/                   # 固定 commit 的 submodule
```

`git submodule add` 会暂存 `.gitmodules` 和 gitlink；这是明确的 Git 副作用，不是 commit。安装器不自动暂存其余文件，不 commit/push，不创建/切换目标分支，不安装 hooks。已有 `.gitmodules` 用户改动会导致预检停止，避免意外暂存。

## 配置与重复执行

`.ftckb/project.yaml` 使用 **JSON 语法（合法 YAML 1.2）**，由脚本生成；普通缩进 YAML 不受本包装器支持。

```json
{
  "schemaVersion": 2,
  "kernelSchemaVersion": 2,
  "integrationVersion": 2,
  "team": "16093",
  "season": "2025-2026",
  "profiles": [],
  "source": {
    "repository": "https://github.com/lucasnotfound59/FTC-Knowledge-Bank.git",
    "ref": "RELEASE_TAG_OR_FULL_SHA",
    "commit": "FULL_40_CHARACTER_COMMIT_SHA",
    "path": "tools/FTC-Knowledge-Bank"
  },
  "managedFiles": {
    ".agents/skills/ftc-knowledge-bank/SKILL.md": "GENERATED_SHA256",
    "AGENTS.md#ftckb": "GENERATED_SHA256"
  }
}
```

以上仅字段示意，占位字符串不能直接使用。`managedFiles` 摘要用于防止无意覆盖，不是对恶意篡改的签名。不要手改摘要来强行通过预检。

新安装必须选择 `--generic-profile` 或可重复的 `--profile NAME`（例如 `--profile command-based`）。`profiles:[]` 是显式 generic；缺失/null 不合法。rookiebot 隐含 simple-opmode，ftclib-command 隐含 command-based；simple-opmode 与 command-based 不兼容，generic 与命名 profile 互斥。不从代码或依赖猜测 profile。

重跑时可省略 team/season/ref，缺省重用配置中的 SHA，不重新解析 tag。相同输入不会产生新 diff。AGENTS 托管段之外的原文及队伍补充保留；项目补充写在段外，不改生成的 Skill。若生成文件/段落有人工修改、路径冲突、symlink 或 submodule 脏改动，脚本停止而非覆盖。

## 每次编码与验收

从目标根目录运行：

```bash
python3 tools/FTC-Knowledge-Bank/.agents/skills/ftckb-integrate/scripts/project.py resolve --project .
# Agent 根据 activeRules 修改代码
python3 tools/FTC-Knowledge-Bank/.agents/skills/ftckb-integrate/scripts/project.py check --project .
```

包装器验证固定版本、知识路径、托管内容、schema 和退出码，并验证返回的 normalized profiles 与配置相符；resolve/check 自动附加配置的显式 profile 参数。CLI 尚未构建或版本改变时才重新构建；构建日志走 stderr，kernel JSON 保持 stdout 可解析。

完整验收：

```bash
python3 tools/FTC-Knowledge-Bank/.agents/skills/ftckb-integrate/scripts/verify.py --project .
```

| 输出/退出码 | 意义 |
| --- | --- |
| `installationOk=true`、`projectCheck.ok=true`，0 | 接入有效且本次 diff 硬检查通过；仍有 soft 待评估 |
| `installationOk=true`、`projectCheck.ok=false`，1 | 接入有效，但目标已有或新增硬违规，不能交付为通过 |
| `installationOk=false`，2 | 配置/版本/构建/schema/知识加载或规则冲突等错误 |

`project.py` 的成功调用透传 kernel JSON 和退出码（check 0/1/2/64）；包装器自身失败是独立 `integrationOk=false` 输出，不冒充 kernel 成功。验收不推断违规是谁引入的；Agent 应记录开始时的工作区基线并如实区分。

## 规则会不会太严格？

硬规则与软建议应区分：接入参数、版本一致性、避免覆盖用户内容是工程保护；只有适用范围明确、可可靠判定的代码规范才适合硬检查。命名、架构、调参经验等当前多数是 soft。

已知限制：两条 Limelight `regex-required` 的 `appliesTo` 当前是 `**/*.java`，对无关 Java 新增行也可能产生误报。此接入版本不擅自修改规则审批结果，Agent 不应插入无意义 `isValid()`/时间戳代码来通过。后续需维护者确认后缩小触发范围或转为 soft；见 roadmap。check 是静态文本检查，不是完整 Java 语义分析，也不能证明硬件安全。

## fresh clone、升级和失败恢复

其他队员克隆已经提交了接入配置的项目后：

```bash
git submodule update --init -- tools/FTC-Knowledge-Bank
python3 tools/FTC-Knowledge-Bank/.agents/skills/ftckb-integrate/scripts/verify.py --project .
```

旧 v1 项目保持原固定 SHA，不自动迁移。v1→v2 升级必须显式选择 profile；已有有效 v2 配置升级时可以保留原选择。升级时显式选择新版本，先 dry-run，再运行安装器的 `--ref NEW_TAG_OR_SHA`。它更新 gitlink、配置和未被人工修改的模板，重新构建并验收。没有 `--ref` 不升级；不要 `git pull` submodule 或 `git submodule update --remote`。

构建失败：托管文件和固定 submodule 保留，修正 JDK、缓存权限或网络后重跑安装或 verify。版本/路径冲突：先查看 `git status`、`.gitmodules`、配置与 submodule HEAD，不直接 reset/删除目录。安装中途被强制终止可能留下 Git 注册或部分托管文件；脚本宁可拒绝未知残留，也不猜测并删除。审阅具体残留后人工恢复；不要执行全仓库 `git clean -fdx` 或 `git reset --hard`。

## 可选 CI 门禁（不自动安装）

Skill 依靠 Agent 遵循；要阻止不合规 PR 合并，需维护者另行配置 CI 和分支保护。PR checkout 已经是提交状态，默认工作区 check 会看到空 diff，所以 CI 必须检查 PR 的明确差异范围：

```bash
# BASE_SHA/HEAD_SHA 必须由可信 CI 提供；checkout/fetch 应包含相关历史。
git submodule update --init -- tools/FTC-Knowledge-Bank
git diff --no-ext-diff --binary "$BASE_SHA" "$HEAD_SHA" -- > /tmp/ftckb-pr.patch
python3 tools/FTC-Knowledge-Bank/.agents/skills/ftckb-integrate/scripts/project.py \
  check --project . --diff /tmp/ftckb-pr.patch
```

维护者应保护 `.ftckb/project.yaml`、`.gitmodules`、Skill 和知识库版本升级的审阅权限；否则 PR 自改门禁同样可绕过。此示例不部署机器人，不把 CI/编译通过描述为真机通过。

## 开发验收

```bash
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s tests/integration -v
# 显式启用较重的固定源码构建 + 真实 CLI 验收：
FTCKB_REAL_INTEGRATION=1 PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s tests/integration -k test_real_cli_from_pinned_source -v
# 核心/CLI 全套（不拉取 Android Studio 插件测试平台）：
./gradlew :modules:domain:test :modules:knowledge:test :modules:model-provider:test \
  :modules:model-provider-openai-compatible:test :modules:repository-analysis:test \
  :modules:tooling-git:test :modules:agent-runtime:test :modules:standardizer:test \
  :modules:session-shell:test :apps:knowledge-cli:test :apps:knowledge-cli:installDist
```

普通 Python 用例使用真实 Git、隔离构建/CLI 夹具检验安装行为。真实 CLI 与发布验证结果单独记录，不把夹具成功或 Windows launcher 参数测试称为 Windows 实机通过。

历史基线（不是 V0.4.0 当前验收），2026-09-08 验收：Python 常规 22 项通过（含已安装项目 dry-run 不刷新 index 回归）；单独开启的真实源码安装测试 1 项通过（临时 Git 源仓库固定 SHA→真实 Gradle installDist→validate/resolve→故意违规的 check exit 1），在最终 Git dry-run 修复后再次通过。Kotlin 核心与 CLI 422 项，421 通过、1 项原有 installDist 预期路径假设检查跳过；无失败。10 份 kernel JSON fixtures 均通过 jsonschema；两份 Skill 通过结构校验；本知识库 validate/resolve/check 通过。`./gradlew test` 全仓库尝试长时间无输出后中止，因此 Android Studio 插件全量测试未完成；Windows 原生环境、部署和真机运行未验证。
