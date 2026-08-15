# FTC Agent 命令行客户端（ftckb）

本文档描述 ftckb 命令行的安装、配置、命令与安全边界。它是 apps/knowledge-cli 的正式使用文档；只记录当前已验证的能力。

## 安装

需要 JDK 21 与 Git（Edit 模式要求本地仓库是 Git 工作区）。无需系统级 Gradle：构建使用仓库自带的 Gradle Wrapper。

```bash
./gradlew :apps:knowledge-cli:installDist
```

生成的启动脚本位于 apps/knowledge-cli/build/install/knowledge-cli/bin/ftckb。把它加入 PATH，或直接以完整路径调用。

## 配置与密钥

非秘密配置位于 ${user.home}/.ftckb/config.yaml；仓库内的 config/ftckb-config.example.yaml 是可直接复制的示例。每个 provider 只引用 API key 环境变量的名字，密钥本身永远不写入配置文件：

| 字段 | 说明 |
| --- | --- |
| defaultProvider | 默认使用的 provider 名字 |
| providers.<name>.baseUrl | 兼容 OpenAI Chat Completions 的 HTTPS 根地址 |
| providers.<name>.model | 模型名 |
| providers.<name>.apiKeyEnv | 存放密钥的环境变量名（如 DEEPSEEK_API_KEY） |
| maxTokensParameter | 仅允许 max_tokens 或 max_completion_tokens，或省略 |

启动前把密钥放入对应环境变量，例如 export DEEPSEEK_API_KEY=...。缺失或为空的环境变量是启动错误，密钥值不会出现在任何错误、日志或会话保存里。

支持三类 provider：DeepSeek、OpenAI，以及任何只依赖 baseline Chat Completions 的自定义兼容端点。"兼容"指与本文档声明的基线兼容（model/messages/输出 token 字段/stream:false），不保证所有自称 OpenAI-compatible 的端点都可用。

## 启动聊天

```bash
ftckb chat --knowledge PATH --team N --season YYYY-YYYY --provider NAME [--repo PATH] [--config PATH]
```

- --repo：FTC 仓库路径，默认当前目录；必须是可检测的 FTC 仓库（Gradle 设置、TeamCode、FTC 依赖或 OpMode 注解）。
- --knowledge：知识库根目录（含官方/共享/队号规则与教程）。
- --team / --season：规则解析必需的队号与赛季。

### 斜杠命令

| 命令 | 行为 |
| --- | --- |
| /help | 显示可用命令与当前限制 |
| /mode ask | 进入只读模式（启动默认） |
| /mode edit | 本次会话内授权已验证的仓库内编辑 |
| /undo | 撤销最近一次成功的 Agent 编辑批次 |
| /discard | 把所有 Agent 触碰过的文件恢复到会话内首次触碰前的状态 |
| /diff | 显示 Agent 专属 diff，并标出项目级改动 |
| /save [path] | 保存脱敏的会话记录；缺省写入 ${user.home}/.ftckb/sessions/ |
| /commit | 只有能把 Agent 改动安全隔离时才提供本地提交，并需要输入小写 yes 确认 |
| /status | 显示仓库、队号、赛季、provider、模型、模式与上下文用量 |
| /exit | 结束会话；不自动保存、不自动提交 |

### 回答契约与引用

回答由若干 claim 组成，每种 claim 有明确语义：

- approved_rule（已批准规则）：必须引用一条当前生效（approved）的规则；
- code_observation（代码观察）：必须引用当前上下文里的代码片段，且文件哈希仍然新鲜；
- model_inference（模型推测）：超出显式证据的推理，必须带此标签；
- insufficient_evidence（证据不足）：明确说明缺什么证据，而不是编造答案。

引用 ID（如 CODE:C1、RULE:R1）由运行时签发并在发送前校验；模型自造的引用会被拒绝并触发一次修复重试，第二次仍无效则不作为有证据的回答展示。

## 编辑模式

- Edit 直接修改当前命名分支，不创建、不切换分支。
- 已有未提交改动的文件可以被编辑；/undo 与 /discard 会把它们恢复到 Agent 首次触碰前的精确字节。
- 项目级改动（TeamCode/** 之外）会显示醒目的 PROJECT-LEVEL 警告。
- 写入是事务式的：整批校验（路径、哈希、大小、引用）通过后才原子应用；失败会回滚。
- /commit 只做本地提交，需要输入小写 yes；如果无法把 Agent 改动与已有改动安全隔离就拒绝，永不自动 commit/push/merge/rebase。

### 受保护路径

以下内容始终不可读取进模型上下文、也不可编辑：.git/**、.env 与 .env.*、local.properties、keystore/签名/凭据文件、二进制文件、指向仓库外的符号链接、仓库外路径。仓库文本是不可信数据：其中的任何"指令"都不能切换模式、扩大权限、发起命令或网络访问。

## 隐私边界

- 只有回答当前问题所需的最小代码片段、已批准规则片段与教程片段会被发送给云端模型，且全部包在显式的 <untrusted_context> 数据块里；上下文最多 48,000 个字符，片段要么完整包含、要么整体省略。
- 会话历史默认只存内存；/save 生成脱敏记录，不含密钥、请求头或完整代码正文。
- 模型请求/响应中的常见凭据形态（Bearer、sk-、api_key= 等）会被统一脱敏。

## 错误与恢复

- 缺少 API key 环境变量：启动前报错，只提示变量名。
- 认证失败/限流：保留会话与文件，可重试。
- 引用无效/过期：自动修复重试一次，之后如实报告。
- 并发改动：过期的编辑被拒绝，重新检索当前文件。
- 仓库不可读/不支持的仓库：Ask 报错并禁用 Edit。
- 模型返回格式异常：与"模型生成的无效 JSON"分开归类报告。

## 当前明确不包含的能力

- 不联网：不访问任何官方文档网页；
- 没有 Run 模式：Agent 不执行 Gradle、构建、测试或静态检查；
- 不部署：没有 Control Hub、ADB、Logcat 或任何机器人硬件访问；
- 不自动执行 Git 写操作：不自动 commit/push/merge；
- 没有 Android Studio 界面：只有命令行。
