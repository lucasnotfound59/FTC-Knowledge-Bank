# FTC Knowledge Bank 文档站内容规划

目标地址：`https://ftckb.lucasxl.com`。本站提供中文项目介绍、使用指南和技术参考；README 保留简介、最短上手路径和文档入口。

## 首页介绍草稿

FTC Knowledge Bank 将 FTC 队伍认可的代码规范、工具经验和工程约束整理成有来源、可审批的知识。编码 Agent 在写代码前取得适用于当前队伍和赛季的规则，在完成改动后执行确定性的 diff 检查，让项目规范贯穿日常开发。

项目级接入工具可以将审阅过的知识库版本固定到机器人项目中，并生成项目配置和 Agent 使用说明。队员继续使用自己的编码 Agent；知识库的 validate、resolve 和 check 在本地运行，不需要额外的模型 API key。编码 Agent 本身的登录和费用由其供应商决定；知识库自带的聊天功能另需模型配置。

## 文档导航

| 栏目 | 页面与重点 | 主要内容来源 |
| --- | --- | --- |
| 项目介绍 | 为什么做这个知识库、解决什么问题、适合谁 | README.md、docs/cli-agent.md |
| 快速开始 | 安装、第一次校验、接入 FTC 项目；包含前提、接入指令和 dry-run | docs/project-integration.md |
| 核心概念 | 规则、证据、队伍与赛季、审批和确定性裁决 | docs/kernel-contract.md、README.md |
| 使用指南 | CLI、聊天与编辑、本地网页、Android Studio 插件 | docs/cli-agent.md、docs/android-studio-plugin.md |
| Agent 接入 | resolve → 写代码 → check；固定版本、项目配置、verify、协作与升级 | docs/project-integration.md、docs/standardizer-check.md |
| FTC 教程 | SDK、FTCLib、Dashboard、Pedro Pathing、Limelight、goBILDA、Control Hub 排障、RookieBot 实践 | knowledge/guides/ |
| 维护与参考 | 添加知识、候选审批、命令参数、JSON 契约；配置字段、CI、发布记录与已知限制 | docs/candidate-extraction.md、docs/kernel-contract.md、docs/kernel-contract.schema.json、fixtures/kernel/、docs/project-integration.md |

以上七个栏目为网站一级导航，名称和顺序固定。项目级接入、日常编码、状态与限制均作为对应栏目下的子页面，不新增一级栏目。

技术正文见 [项目接入与检查技术文档](website/integration-and-checks.md)，页面路由与来源映射见 [网站页面设计](website/page-design.md)。

## 2026-09-08 新增功能的呈现

1. **项目级接入 Skill**：将“把仓库链接交给 Agent，并指定目标项目、队号与赛季”作为快速开始入口；说明不同 Agent 的 Skill 自动发现支持不同。
2. **固定版本安装**：介绍 tag/完整 commit SHA、submodule、项目配置和托管说明；明确不会把知识库 Kotlin 模块加入机器人 Android Gradle 工程。
3. **安装预览与可重复执行**：解释 dry-run、相同配置重复执行、保留用户内容及冲突时停止的行为。
4. **日常包装器与验收**：介绍 project.py 的 resolve/check 和 verify.py，区分“接入有效”与“代码检查通过”。
5. **协作与升级**：单列新队员克隆、初始化 submodule、明确选择新版本及失败恢复流程。
6. **更完整的 diff 检查**：说明暂存区、工作区和非忽略 untracked 文件的覆盖；路径规则覆盖删除与重命名，正则规则检查新增行；非空坏补丁不会静默通过。
7. **机器接口与 CI**：补充 check 的 Schema、输出 fixture、退出码，以及对 PR 明确差异范围执行检查的示例。CI 与分支保护需另行配置。

## 发布与准确性要求

- 上述新增功能当前存在于本地工作区，尚不能宣称已包含在公开发布的 V0.1.1 中。提供可复制的远程安装示例前，必须确认包含这些文件的实际已发布 tag 或 commit。
- 仓库发布版本、CLI 版本、kernel schemaVersion 分别说明。
- 检查属于静态文本执法；soft 建议和机器人行为仍需人工评估。记录现有 Limelight 规则适用范围过宽的已知误报。
- 测试结果引用带日期的现有验收记录，不能描述为文档站搭建时重新运行的结果。
- 现有 README 有历史规划与当前能力混杂的段落，迁移时逐项核对；不能将旧规划原样当作当前状态。
- 用户文档精选迁移，不将内部设计草稿和实施计划自动发布为产品说明。

## 当前交付范围

本文为内容设计记录。2026-09-08 已实现 website/ 静态文档站，29 个内容页面按上述七栏目发布到 Cloudflare Pages，并通过 ftckb.lucasxl.com 验证 HTTPS 访问。维护与更新说明见 website/README.md。
