# 本地知识库管理台

`ftckb dashboard` 管理启动时指定的知识库，提供规则编辑、候选审批、裁决解释和调用审计。它不加载模型配置、不要求模型 API key；`ftckb serve` 是另一个带 Ask/Edit 的聊天服务，仍需要配置模型。

## 启动与访问

在仓库根目录运行：

```bash
./gradlew :apps:knowledge-cli:installDist
apps/knowledge-cli/build/install/ftckb/bin/ftckb dashboard --knowledge knowledge
```

默认分配空闲端口并尝试打开浏览器。只打印链接、自行打开：

```bash
apps/knowledge-cli/build/install/ftckb/bin/ftckb dashboard --knowledge knowledge --port 8080 --no-browser
```

监听地址固定为 `127.0.0.1`。终端输出 `dashboard=ok` 及含 `#token=` 的本次启动链接；页面读取 token 后从地址栏移除，只保存在当前页面内存中。数据 API 都要求 token，写操作还校验同源请求。若刷新浏览器后提示缺少凭据，重新打开终端输出的完整链接；页面内的刷新按钮会保留凭据。停止终端中的服务可用 `Ctrl+C`。

已有知识库必须可读取。外部修改导致 YAML 无效时，页面显示当前错误，不能把旧快照当作最新有效结果。页面可见时约每两秒检查更新；失联时保留最后数据并标记过期。

## 规则与候选审批

1. 在「规则管理」中搜索 ID、标题或正文，按状态、来源、策略层级、适用范围及检查类型筛选。详情分别展示 `authority`、`policyLevel`、`applicability`，以及证据、批准记录、检查和源文件；文件夹与 ID 前缀不能代替这些字段。
2. 新增规则先建立 candidate。编辑器接受一条规则的完整 YAML 文档，包含 `schemaVersion: 4` 和 `rules` 序列；普通编辑不允许修改 ID，也不允许直接把状态改成 approved。新候选的文件路径由服务端生成。
3. 点击「预览差异与校验」，检查完整知识库的错误、修改前后内容，以及可选上下文下的裁决影响。只有可应用的预览才能保存。预览十分钟后失效；任一规则文件发生外部修改后需要重新预览。
4. 保存只替换目标规则所在文件，保留其他规则内容；路径越界、symlink、旧版本预览和校验失败都会阻止保存。修改已批准规则的实质内容或归属会清除旧 approval，并转回 candidate，必须重新批准。
5. 对候选选择「批准候选」或「拒绝候选」，明确填写批准人 ID、角色和适用队伍，再预览并应用。操作不会自动 commit 或 push。

角色检查沿用 ApprovalPolicy：official/shared 来源要求 `overall_software_lead`；team 来源要求 `team_software_lead`，且声明队伍必须等于该规则唯一适用队伍。该检查验证提交的角色声明，不验证操作者身份。候选不参与生效裁决。

## 查看裁决与手动检查补丁

在「归属与裁决」中填写真实 team、season，并显式选择 generic 或命名 profile。generic 表示空 profile 集合；没有完整上下文时，只看规则目录，不推测哪些规则生效。

结果由现有 RuleResolver 生成，展示 active、excluded 及原因、overridden 及 winner IDs，以及同主题最高有效层级的冲突。优先级为 `OFFICIAL > GLOBAL > LOCAL > SHARED`；official 由来源取得最高级，冲突不会交给前端或模型猜胜者。

页面的补丁检测检查手动粘贴的 unified diff，不读取任意本地项目路径。需要检查工作区暂存、未暂存和未忽略的新文件时，继续使用 CLI：

```bash
ftckb check <repo-root> --knowledge <knowledge-root> --team <真实队号> --season <YYYY-YYYY> --generic-profile --json
```

使用项目真实 profile；不要把示例的 generic 当成自动推断。默认工作模式为 normal。只有用户明确要求当前为测试或 dev 任务时，才选择 test/dev；它们不保存为项目默认。test 继续执行安全和目录等硬检查；dev 对明确给出的补丁保留每一条降级提醒。soft 表示需要复核，dev 通过不等于符合正式规则。

## 审计存储与共享

默认位置：

```text
~/.ftckb/audit/<知识库规范路径的 SHA-256>/events.jsonl
~/.ftckb/audit/unscoped/events.jsonl
```

分区标识取自知识库路径，不是当前规则内容版本。调用记录另存本次知识库版本摘要；历史详情使用调用时的规则与命中快照，不拿当前规则覆盖旧结果。无法确定知识库的参数错误等记录放在 unscoped，界面单独显示。

CLI 的 `rules-sha256:` 摘要标识当次实际加载、解码后的规则集合；dashboard 与 chat/serve 使用知识库源文件路径和内容的摘要。两种格式不能直接比较是否相等：例如只修改 YAML 注释会改变源文件摘要，但不会改变规则语义摘要。CLI 摘要不通过事后重读文件来推测当次执行版本。

所有入口使用 `FTCKB_AUDIT_DIR` 更换审计根目录。例如：

```bash
FTCKB_AUDIT_DIR=/absolute/path/to/ftckb-audit ftckb dashboard --knowledge /absolute/path/to/knowledge
FTCKB_AUDIT_DIR=/absolute/path/to/ftckb-audit ftckb validate /absolute/path/to/knowledge --json
```

需要在同一管理台看到 CLI 记录时，相关进程必须使用相同审计目录和同一知识库路径。复制知识库到另一路径会成为独立分区。记录跨进程安全追加，列表分页并增量读取；普通页面轮询不创建检测调用。进程中断留下未完成记录，日志尾部中断、损坏或读取失败会显示存储状态；写入失败通过独立诊断报告。审计错误不改变确定性 CLI 的 kernel JSON stdout 或退出码。

审计记录调用 ID、入口、阶段、上下文、时间、结果、规则/位置及可用 token 用量。模型子调用关联实际的 chat/serve 请求 ID。不保存 API key、访问 token、完整提示词、模型正文或原始 diff。

## 如何理解检测轨迹

| 记录 | 可以说明什么 |
| --- | --- |
| validate | 已执行知识库加载与规则校验；不表示检查过代码 diff |
| resolve | 已对指定上下文裁决规则；不表示执行过 Standardizer |
| check / 管理台补丁检测 | 各阶段标明加载、校验、裁决、diff 读取与 Standardizer 是否实际执行 |
| chat/serve 询问 | 记录实际规则与模型阶段；diff 检测不适用 |
| chat/serve 新编辑 | 对本次预览 diff 执行写入前检查；normal 下硬违规阻止写入，soft 随结果返回 |
| undo/discard | 独立恢复事务，展示分支、路径与冲突等实际结果；不冒充新编辑通过检查 |
| 未完成 | 没有结束事件；不能算通过 |

内置聊天服务在下次请求开始前重新加载和校验规则，并为该请求固定快照。编辑检查与写入之间会复查规则版本和文件基线；过期后需要重新生成。规则保存与内置编辑写入使用共同锁协调，但无法控制任意外部编辑器；边界检查发现变化会拒绝或回滚。

chat 可用 `/work-mode normal|test|dev` 明确选择下一条提交消息的工作模式；消息提交后立即重置为 normal，包括请求失败的情况。serve 的 ask/submit API 可在本次请求传 `workMode`，省略即 normal，不写入持久设置。dev 只对该次生成的预览 diff 降级，仍返回每个硬命中的规则、检查和位置；知识无效、冲突及过期预览仍会失败。只有用户明确指定本次为 test/dev 时才能使用，不能从文件名推断。

概览统计的是「已记录调用中的 diff 检测状态」。被拦截的 diff 是已检测的一部分；soft、dev 降级提醒、参数错误、知识无效、冲突和模型失败含义不同。界面没有证据证明 soft 已经人工复核，也不会将两个没有关联 ID 的 resolve/check 自动配成一组。

## 覆盖与版本边界

- 审计启用前、旧固定 commit/旧构建、未接入进程和绕过 CLI 的操作不可观测。升级本仓库不会自动升级其他 FTC 项目中固定的副本；需按既有接入流程明确升级和重新构建。旧可执行文件可能不认识 `dashboard`。
- 外部编码 Agent 调用本版本 CLI 时，可以记录这些 CLI 调用；它的全部对话、任意工具执行和私自写文件并不因此受管理台监督。写入前门禁覆盖这里集成的 chat/serve 编辑流程。
- 当前聚合审计入口是新版 validate/resolve/check、chat/serve 用户请求及模型子调用，以及 dashboard 手动检测和规则事务；不要据此宣称所有其他命令或 Android Studio 插件操作均有同等审计覆盖。
- Dashboard HTTP API、审计事件与 kernel JSON 是不同契约。管理台新增接口不修改 kernel JSON v2 或项目接入协议 v2，也不代表已经创建 tag、Release 或更新公开文档站。
- 构建、静态检查、规则校验和浏览器操作均不能替代 Robot Controller、Driver Station 或真机验证。测试结果按实际执行范围报告，不将历史全量测试当作本次新增功能已被验证的证据。

更多：[`AGENTS.md`](../AGENTS.md)、[机器契约](kernel-contract.md)、[diff 检查](standardizer-check.md)、[聊天 Agent](cli-agent.md)。
