# 架构、开发与验证

## 当前模块

模块清单以 settings.gradle.kts 为准。知识内核独立于 IDE；项目级接入通过固定版本 CLI 调用，不加入机器人 Android 工程。

| 模块 | 职责 |
| --- | --- |
| modules/domain | 规则值对象、校验、审批策略与确定性裁决 |
| modules/knowledge | 严格 YAML 解码与文件系统知识加载 |
| modules/repository-analysis | FTC 工程识别、安全索引与本地检索 |
| modules/model-provider | Provider 中立类型、配置与脱敏 |
| modules/model-provider-openai-compatible | OpenAI-compatible 模型适配 |
| modules/agent-runtime | 会话、检索、引用校验和 Ask/Edit 编排 |
| modules/tooling-git | Diff 与受保护的本地提交 |
| modules/standardizer | 确定性 diff 检查 |
| modules/session-shell | CLI 与网页会话交互支持 |
| apps/knowledge-cli | ftckb 命令行入口 |
| apps/android-studio-plugin | Android Studio 客户端 |

## 开发验证

在仓库根目录、JDK 21 环境下运行：

```bash
./gradlew clean test
```

该命令可能包含额外平台依赖下载。仅验证核心与 CLI 时，使用[项目接入指南中的分模块命令](../project-integration.md#开发验收)。接入脚本的 Python 测试、真实固定源码 CLI 测试和插件测试需分别记录，不能混为同一个覆盖范围。

文档网站的构建在 website 目录执行：

```bash
npm ci
npm run build
```

最新已有测试快照见[版本与验证范围](https://ftckb.lucasxl.com/reference/status/)。文档构建或链接检查通过不代表 Kotlin 核心重新测试，也不代表部署到机器人或实机运行成功。

## 后续方向

Ask、Edit、本地网页、候选提取和 Android Studio 插件已经实现。Run 模式、官方文档联网检索、审批 UI/不可变历史、Control Hub 自动部署仍未实现。

后续设计方向包括独立的构建与部署适配层、更多 IDE 客户端、受控工具编排及验证循环。这些是规划，不是当前可用功能。机器人整体软件架构、机械与硬件方案由队员决定。

Control Hub 与未来控制平台的构建、日志和部署接口应分别适配，知识和审批机制保持独立。Systemcore 的具体支持范围以 FIRST 与相关项目正式文档为准；本仓库尚未实现对应部署适配器。

- [FIRST 控制系统介绍](https://community.firstinspires.org/control-system-update-first-tech-challenge-edition)
- [Systemcore 测试仓库](https://github.com/wpilibsuite/SystemcoreTesting)
- [项目任务记录](../../todolist.md)
