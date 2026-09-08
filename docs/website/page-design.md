# 文档站页面设计

目标域名：`ftckb.lucasxl.com`。一级导航严格采用下表的七个栏目。网站已于 2026-09-08 发布；以下为原页面拆分设计，当前实现将聊天与编辑合并在 /guides/cli/，实际导航以 website/scripts/content-map.mjs 为准。

| 一级栏目 | 子页面与拟定路由 | 正文来源 |
| --- | --- | --- |
| 项目介绍 | 项目概览 `/introduction/`；能力与适用人群 `/introduction/use-cases/` | README 中经当前实现核对的介绍；integration-and-checks.md 第一节 |
| 快速开始 | 环境与第一次校验 `/getting-started/`；接入 FTC 项目 `/getting-started/integrate/` | docs/project-integration.md；integration-and-checks.md 第二节 |
| 核心概念 | 规则与证据 `/concepts/rules/`；队伍、赛季与裁决 `/concepts/resolution/`；候选与审批 `/concepts/approval/` | docs/kernel-contract.md；integration-and-checks.md 第三节 |
| 使用指南 | CLI `/guides/cli/`；聊天与编辑 `/guides/chat-edit/`；本地网页 `/guides/local-web/`；Android Studio `/guides/android-studio/` | docs/cli-agent.md、docs/android-studio-plugin.md；integration-and-checks.md 第四节 |
| Agent 接入 | 编码工作流 `/agent/workflow/`；固定版本与配置 `/agent/configuration/`；验收 `/agent/verification/`；协作与升级 `/agent/upgrades/` | docs/project-integration.md；integration-and-checks.md 第五节 |
| FTC 教程 | SDK `/tutorials/sdk/`；FTCLib `/tutorials/ftclib/`；Pedro `/tutorials/pedro/`；Dashboard `/tutorials/dashboard/`；Limelight `/tutorials/limelight/`；goBILDA `/tutorials/gobilda/`；Control Hub 排障 `/tutorials/control-hub/`；RookieBot `/tutorials/rookiebot/` | knowledge/guides/ 中对应的八篇教程 |
| 维护与参考 | 添加知识 `/reference/contributing/`；候选审批 `/reference/candidates/`；CLI 与 JSON `/reference/kernel/`；检查语义 `/reference/checks/`；CI `/reference/ci/`；版本与限制 `/reference/status/` | docs/candidate-extraction.md、docs/kernel-contract.md、docs/standardizer-check.md、docs/project-integration.md；integration-and-checks.md 第七节 |

## 页面组织

桌面端使用左侧七组导航、中间正文、右侧页内目录；移动端将导航折叠为菜单。首页给出项目简介和“快速开始”“Agent 接入”两个入口。提供全文搜索、代码复制和前后页导航。

新增技术正文按上述栏目拆分到路由中；本文和内容规划属于内部设计，不作为公开文档正文。发布稿保留每页的标题、简介、适用版本及必要的操作结果说明。命令参数、错误语义集中在参考页，教程通过链接引用，避免多处维护相同接口定义。

## 内容与构建

以仓库内 Markdown 为内容源，采用文档站静态构建。现有技术文档和教程按明确清单导入或迁移，保留内部工具依赖的原路径；网站链接转换为目标路由。禁止直接扫描并公开整个 docs 目录，以免发布内部计划。

README 最终只保留简介、最短上手命令、版本状态与文档入口。文档站实际可访问后，再加入正式网站链接。

## 发布验收

- 七个一级栏目名称、顺序与本表一致，每个导航目标都存在。
- 构建通过，站内链接和 Markdown 资源路径有效；JSON 示例可解析，并与 kernel schema 核对。
- 搜索索引覆盖公开正文，不包含内部设计、安装凭据或本机路径。
- 安装指令使用实际可取得且包含接入功能的版本；未发布内容明确标记。
- 将已有测试记录标注为带日期的历史验收，不宣称本次重新执行。
- 完成本地预览审阅后，再发布到 Cloudflare 并绑定子域名。网站构建、发布和 DNS 操作属于后续实施。
