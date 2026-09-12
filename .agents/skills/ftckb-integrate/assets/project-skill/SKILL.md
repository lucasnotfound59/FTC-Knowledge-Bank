---
name: ftc-knowledge-bank
description: 在已接入 FTC Knowledge Bank 的项目中编写、修改或检查 FTC 代码时，使用项目配置调用确定性的规则裁决和 diff 检查。
---

# FTC 项目规则工作流

从 Git 根目录读取 `.ftckb/project.yaml`（JSON 语法的 YAML 1.2）。它提供 team、season、固定 source.commit 和协议版本；v2 还提供明确的 profiles。不可从 README 示例、日期或其他队伍猜测。

v2 的 `schemaVersion`、`kernelSchemaVersion`、`integrationVersion` 都为 2，`profiles` 必须是无重复的字符串数组。支持 `rookiebot`、`simple-opmode`、`command-based`、`ftclib-command`；`profiles:[]` 表示用户明确选择 generic，不等同于未配置。`rookiebot` 隐含 `simple-opmode`，`ftclib-command` 隐含 `command-based`，隐含后 simple/command 两类互斥。配置保留原选项，由脚本规范化并传给内核，不由 Agent 自行裁决。

## 开始修改前

记录 `git status --short` 和已有 staged/unstaged/untracked 修改，区分用户已有改动与本次变更。直接使用当前分支；不要求新建分支，也不因文件已有改动而一律拒绝协作，但不得覆盖/撤销用户修改。

未初始化 submodule 时先执行：

```bash
git submodule update --init -- tools/FTC-Knowledge-Bank
```

运行下列脚本（Python 3.10+；同一 Python 环境安装 `jsonschema>=4.18,<5`）：

```bash
python3 tools/FTC-Knowledge-Bank/.agents/skills/ftckb-integrate/scripts/project.py resolve --project .
```

脚本检查配置、submodule URL/HEAD/gitlink、固定源码能力声明、托管文件、JSON schema 和退出码；需要时独立构建知识库 CLI。v2 resolve/check 自动添加重复 `--profile` flags 或 `--generic-profile`，并核对内核 schemaVersion 与配置 kernelSchemaVersion、返回 profiles 与配置规范化结果一致。使用 JDK 21+ 运行，首次构建可能需要联网；不需要模型 API key，也不使用机器人的 Android SDK。Windows 可用 `py -3` 替换 `python3`。

只依据 `activeRules` 裁决结果工作；`conflicts` 非空、schemaVersion 不支持、脚本报错或退出非 0 时先解决，不继续假装规则已经载入。按规则的实际适用范围编写代码，不强迫所有项目迁移到某个队伍的风格。

## 修改后交付前

```bash
python3 tools/FTC-Knowledge-Bank/.agents/skills/ftckb-integrate/scripts/project.py check --project .
```

- 退出 0：硬检查通过；逐项评估并如实报告相关 soft 提醒。
- 退出 1：硬违规，修复后重跑。已有违规属于用户基线时明确说明，不将其冒充本次引入，也不声称最终检查通过。
- 退出 2/64：加载、配置、规则冲突或调用错误，不等同于代码通过。

默认 check 覆盖 staged、unstaged 和非忽略 untracked 变更。路径规则看触及路径（含删除/重命名），regex 仅看新增行。`--diff /path/to/change.patch` 只用于明确范围的检查，并披露排除的项目改动；交付仍运行默认全工作区 check，不靠缩小 diff 隐藏违规。

硬检查不等于完整语义验证。遇到看似误报的规则，展示 ruleId、适用代码与原因，请维护者调整规则；不要擅改知识库或插入无意义代码来过检查。

报告本次改动、resolve/check 结果、重要 soft/未验证项以及实际执行的构建/测试命令。编译、部署、真机运行分别说明；没有硬件测试就写未验证。

## 维护边界

不修改 submodule 中的规则来绕过检查；升级必须明确指定 tag/commit，并重跑安装和验收。不执行 `git submodule update --remote`，不自动 commit/push，不安装 Git hooks。
安装器的 `--profile NAME` 可重复且与 `--generic-profile` 互斥；新安装必须明确选择。v2 重跑/升级省略选择会保留原 profiles。既有 v1 pin 不自动升级，不补写空 profiles，也不传 v2 flags；显式 v1→v2 升级必须提供 profile/generic。不支持混合版本号、新装 v1 或自动 v2→v1 降级。版本不支持、选择缺失或冲突时停下来询问/解决，不用 README 示例代替用户选择。
此 Skill 依靠当前 Agent 遵循，不会自动拦截所有编辑器或所有成员提交。合并强制门禁需另行配置 CI。
