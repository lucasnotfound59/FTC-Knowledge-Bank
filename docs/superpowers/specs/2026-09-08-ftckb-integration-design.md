# FTC Knowledge Bank 项目级接入设计

状态：用户已确认，2026-09-08。基线：`edd57b842334a9299cd45cd065f50564a9d2d7f8`（与 origin/main 同步）。

## 目标与边界

用户给 Agent 仓库链接、目标 FTC Git 项目和队号后，Agent 读取安装 Skill，安装固定版本 submodule、项目配置和运行时 Skill。知识库保持独立 CLI，不加入机器人 Android 的 Gradle 依赖。第一版要求目标已有 Git HEAD、TeamCode 及 Gradle 项目结构。

目标布局：`AGENTS.md` 托管段、`.agents/skills/ftc-knowledge-bank/SKILL.md`、`.ftckb/project.yaml`、`tools/FTC-Knowledge-Bank` submodule。上游安装入口为 `.agents/skills/ftckb-integrate/SKILL.md`，附 `integrate.py`、`verify.py`、`project.py` 和生成模板。

## 不变量

- 直接在用户当前分支修改，不创建分支，不自动 commit/push，不安装 hooks。
- 队号来自显式参数或已有项目配置；不能默认 20827。赛季必须来自明确参数或唯一的可靠项目配置；不能从日期或 README 示例猜测。
- 固定完整 commit SHA；tag 解析后也记录 SHA。不追踪 main，不使用 submodule update --remote。
- `--dry-run` 不修改目标文件、index、Git 配置，不执行构建。
- 重复执行不产生新 diff；只替换 AGENTS 托管段，保留原内容。生成文件或托管段被人工改过、目录冲突时失败并解释，不强制覆盖。
- submodule add 可能暂存 `.gitmodules` 和 gitlink，必须在计划中说明，不改其他暂存内容。
- 失败保留可诊断状态，不能删除用户文件或重置整个仓库；重跑应能完成构建失败后的安装。
- 不记录本机 SDK/JDK 路径、API key；确定性 CLI 不需要模型服务。
- 修改前 `resolve`，修改后 `check`。不支持的 schema 或规则冲突停止；硬违规阻止交付；soft 如实报告。
- Skill 是 Agent 协作契约，不是自动强制执行的安全边界；可选 CI 才能提供合并门禁。
- 安装有效与目标代码合规分别报告；不把编译或软件检查称为真机验证。

## 实现决策

Python 3.10+ 负责安装与运行包装，配置采用 JSON 兼容的 YAML 1.2（JSON 语法，仍命名 project.yaml），以免首次安装要求额外的 YAML 包。不实现不完整的通用 YAML 解析器。项目 Skill 从配置获取 team/season，不复制规则或自行裁决。

配置包含 `schemaVersion=1`、`kernelSchemaVersion=1`、`integrationVersion=1`、team、season、source.repository/ref/commit/path，以及托管产物内容摘要，用来识别人工修改。安装器从显式 tag/SHA 取得版本；已安装项目缺省重用锁定版本。升级必须显式 `--ref` 并通过同样的保护与验收。

验证器执行 validate/resolve/check，并检查 JSON Schema、退出码、team/season、路径、submodule URL、HEAD 和 gitlink。JSON Schema 验证由标准 jsonschema 包执行；安装运行的主流程不需要该包，验收命令给出明确的安装提示。完整验收使用隔离 Python 环境而不是全局 pip 安装。

## 先修复的机器契约

补齐 check 在 kernel JSON Schema 的正常、硬违规、冲突及错误输出。默认 check 覆盖 staged 和 unstaged 变更及非忽略 untracked 文件；不漏删除、纯重命名和只删行的变更。路径 checks 检查触及路径（重命名前后都包括），regex checks 只检查新增行。无法读取 diff 时失败，不静默通过。

## 验收与发布

真实临时 Git 项目验证 dry-run、幂等、已有 AGENTS 保留、文件冲突、错误队号/赛季/版本、失败重试、空格/中文路径、fresh clone submodule，以及硬违规 exit 1。Windows 验证命令构造；没有 Windows 实机证据时明确标为未实测。

旧 README V0.1.1 与 CLI 1.0.0、kernel schema 1 属于不同版本层。当前无 v0.1.1 tag。功能完成后作为下一次发布候选；发布 tag 和推送在验收后单独处理，不伪称已发布。
