# FTC Knowledge Bank

面向 FTC 队伍与编码 Agent 的工程知识库：把有来源、经审批的规范接入开发流程，在写码前取得规则，完成后检查改动。

**[完整文档 → ftckb.lucasxl.com](https://ftckb.lucasxl.com)**

## 快速接入

把下面的指令交给你使用的编码 Agent，替换目标项目、队号和赛季：

```text
将 https://github.com/lucasnotfound59/FTC-Knowledge-Bank 接入我的 FTC 项目。
目标项目：<项目路径>；队号：<实际队号>；赛季：<YYYY-YYYY>。
请读取 .agents/skills/ftckb-integrate/SKILL.md，固定已审阅的版本，先预览再安装。
```

接入后的日常流程：**`resolve` → 按规则写代码 → `check`**。这些确定性命令在本地运行，不需要额外模型 API key；编码 Agent 自身的登录与费用由其供应商决定。

需要 Git、Python 3.10+、JDK 21+ 和 jsonschema。安装步骤、依赖配置与版本升级见[接入指南](https://ftckb.lucasxl.com/getting-started/integrate/)。

## 文档导航

| 栏目 | 内容 |
| --- | --- |
| [项目介绍](https://ftckb.lucasxl.com/introduction/) | 目标、适用人群与能力边界 |
| [快速开始](https://ftckb.lucasxl.com/getting-started/) | 安装、第一次校验与项目接入 |
| [核心概念](https://ftckb.lucasxl.com/concepts/rules/) | 规则、证据、队伍与赛季、审批与裁决 |
| [使用指南](https://ftckb.lucasxl.com/guides/cli/) | CLI、聊天编辑、本地网页与 Android Studio |
| [Agent 接入](https://ftckb.lucasxl.com/agent/workflow/) | 编码流程、固定版本、验收与升级 |
| [FTC 教程](https://ftckb.lucasxl.com/tutorials/sdk/) | SDK、Pedro、Dashboard 与故障排查 |
| [维护与参考](https://ftckb.lucasxl.com/reference/contributing/) | 添加知识、审批、字段、命令与 JSON 契约 |

## 项目状态

已实现规则裁决、diff 检查、项目级接入、候选提取与审批、Ask/Edit、本地网页及 Android Studio 插件。Run 模式、官方文档联网检索和 Control Hub 自动部署尚未实现。静态检查不替代真机验证。

发布版本、已知限制与测试记录见[版本与验证范围](https://ftckb.lucasxl.com/reference/status/)。

- Agent 入口：[AGENTS.md](AGENTS.md) · [接入 Skill](.agents/skills/ftckb-integrate/SKILL.md)
- 离线参考：[项目接入](docs/project-integration.md) · [机器契约](docs/kernel-contract.md) · [维护手册](docs/handbook/)
- 问题反馈：[GitHub Issues](https://github.com/lucasnotfound59/FTC-Knowledge-Bank/issues)
