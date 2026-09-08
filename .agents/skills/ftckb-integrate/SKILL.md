---
name: ftckb-integrate
description: 将固定版本的 FTC Knowledge Bank 接入现有 FTC Git 项目，安装项目配置、submodule 和运行时规则 Skill，或明确升级已接入版本。
---

# FTC Knowledge Bank 项目级接入

适用于用户要求“把此仓库接入当前 FTC 项目”。这不是全局 Skill 安装，也不把知识库作为机器人 Android 运行依赖。

1. 确认目标 Git 根目录、`TeamCode` 和 Gradle 项目结构。目标须已有提交。保留当前分支及所有用户修改。
2. 获取明确队号。用户未提供且 `.ftckb/project.yaml` 不存在时，询问，不能默认 README 的 20827。赛季仅接受用户明确值或现有项目配置；无法唯一确定时询问，不按当前日期猜测。
3. 选定包含本 Skill 的发布 tag 或完整 commit SHA，向用户说明所选版本。若从仓库链接开始，可以在临时目录克隆上游读取本入口；将已审阅 checkout 的完整 SHA 传给安装器。不传 `main`，不把旧 V0.1.1 文案当成已有 tag。
4. Python 3.10+、Git、JDK 21+ 是前提；正式安装还需同一 Python 环境中的 `jsonschema`（见 `scripts/requirements.txt`）。可在用户认可的虚拟环境安装，不写个人 SDK/JDK 路径或 API key。首次 Gradle 构建需要缓存或网络。
5. 用安装器先 dry-run，再执行。用户已经授权接入且预览范围一致时可继续；只有参数不明或将覆盖用户内容时停下来说明。

```bash
python3 /path/to/FTC-Knowledge-Bank/.agents/skills/ftckb-integrate/scripts/integrate.py \
  --project /path/to/FTC-project --team TEAM --season YYYY-YYYY --ref FULL_COMMIT_SHA --dry-run
```

去掉 `--dry-run` 执行相同命令。Windows 使用 `py -3`，路径含空格须加引号。安装器将创建 `.ftckb/project.yaml`、项目 Skill、AGENTS 托管段及固定版本 submodule，构建独立 CLI，并执行验收。submodule add 会暂存 `.gitmodules` 和 gitlink；其他文件不自动暂存，不 commit/push，不更改目标分支。

6. 阅读输出，不只看最后一行：`installationOk=true` 表示接入有效；`projectCheck.ok=false` / exit 1 表示目标有硬违规，不能宣布代码合规。exit 2 是接入/加载/冲突错误。soft 提醒需报告；构建通过不代表部署或真机验证。
7. 构建失败时文件保留，修复 JDK/缓存/网络后重跑同一命令或 `scripts/verify.py --project PATH`。生成文件/托管段人工修改时不要强制覆盖；让用户决定保留方式，通常把项目补充说明放在托管段外。

已安装项目重跑可省略 team/season/ref，默认重用配置中的完整 SHA，不重新追踪 tag。升级须明确 `--ref TAG_OR_SHA`；不要执行 `submodule update --remote`。

完整命令、配置格式、恢复和可选 CI：读取上游 `docs/project-integration.md`。目标 Agent 是否自动发现 `.agents/skills` 取决于平台；不支持时让其显式读取目标 Skill，不能承诺给任意 Agent 链接就自动触发。
