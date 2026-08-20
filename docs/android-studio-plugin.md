# Android Studio 插件（ftckb AS）设计文档

目标：把 `ftckb` 的会话能力装进 Android Studio 工具窗口，让队员在 IDE 里直接问/改代码，
零终端、复用 IDE 自带 diff。插件只是壳：所有裁决、检索、编辑、校验逻辑复用核心（Agent Runtime +
SessionRuntime），插件不复制任何业务逻辑。

## 1. 范围（第一版，克制）

包含：

- 工具窗口「FTC 知识库」：中文聊天界面（问/答、引用标注、模式切换）；
- Ask 模式（两阶段检索 + 引用校验）与 Edit 模式（事务式修改 + undo/discard），直接复用 `SessionRuntime`；
- 仓库自动识别：取 AS 当前打开项目的根目录，`RepositoryIndex` 判定非 FTC 仓库时显示提示条；
- Edit 结果进 **IDE 自带 diff viewer**（用 `SessionController.changes()` 的 `TextChange{path,before,after}` 构造前后版本，`DiffManager` 展示，支持逐文件切换）；
- 设置：队伍/赛季/Provider 名称/配置文件路径（`PersistentStateComponent` 持久化到项目级配置）；
- API key：优先环境变量；否则首次使用时弹输入框，存入 **IntelliJ PasswordSafe**（系统钥匙串加密，不落明文）。

不包含（避免维护成本爆炸）：

- 不替换 RepositoryIndex 的 PSI 深度集成（继续用我们自己的索引，与 CLI/serve 行为一致）；
- 不做构建/Gradle 集成、Run 模式、Control Hub/ADB 部署；
- 不做 commit/push（队员用 IDE 自带 VCS 提交；插件只 undo/discard/diff）；
- 不做候选提取/审批 UI（`ftckb extract` 已在 CLI，后续再加）。

## 2. 模块结构

前置重构（先做，再写插件）：

- 新建 `modules/session-shell`（纯 kotlin("jvm") 库）：把 `SessionRuntime`、`SwapModelProvider`、
  `AskChatSession`、`ChatStatus`、`RuntimeAskChatSession` 从 `apps/knowledge-cli` 移入（包名 `org.ftckb.session`）；
  `apps/knowledge-cli` 改为依赖它。动机：应用模块不能被插件当库依赖，共享会话层必须是库。
- 全部既有 406 项离线测试保持绿（测试仍挂在 knowledge-cli 下，随依赖关系自动覆盖）。

新模块：

```
apps/android-studio-plugin/
  build.gradle.kts              # org.jetbrains.intellij 插件 + 依赖 modules/{session-shell,agent-runtime,...}
  src/main/resources/META-INF/plugin.xml
  src/main/kotlin/org/ftckb/intellij/
    FtckbService.kt             # ProjectService：持有 SessionRuntime、串行执行器、配置状态、密钥读取
    FtckbSettings.kt            # PersistentStateComponent + DialogWrapper（队伍/赛季/Provider/配置路径/key）
    FtckbToolWindow.kt          # 工具窗口 UI（聊天区/输入区/按钮/状态条）
    FtckbDiff.kt                # DiffManager 集成（TextChange → ContentRevision）
    FtckbProject.kt             # 项目根目录判定 + FTC 仓库检测
```

依赖模块（全部复用核心）：`domain`、`knowledge`、`model-provider(-openai-compatible)`、
`repository-analysis`、`tooling-git`、`agent-runtime`、`session-shell`。

## 3. 关键决策

1. **平台版本**：IntelliJ Gradle 插件以 Community SDK 为目标；`sinceBuild/untilBuild` 按当前
   Android Studio 对应的 IntelliJ 版本设定（实施时确认你本机 AS 版本后锁定）。
2. **Kotlin stdlib**：IntelliJ 自带 Kotlin runtime，插件依赖里把 kotlin-stdlib 标为不打包
   （避免双 stdlib 冲突）；插件代码用与核心一致的 Kotlin 2.x 编译。
3. **JGit 冲突（已知风险点）**：`tooling-git` 依赖 jgit 7.7；IDE 自带的 git4idea 也用 jgit。
   第一版选择：把我们的 jgit 版本随插件打包（插件类加载器优先），并在 README 注明已知风险；
   若实测冲突，退路是给插件内 jgit 做 relocate，或把 branch/dirty/HEAD 检查改用 IDE VCS API。
4. **线程模型**：模型调用永远在后台（`ProgressManager` + 单线程执行器，与 serve 相同），
   UI 事件只提交任务、不阻塞 EDT；插件不重复实现并发控制——`SessionController` 本身 @Synchronized。
5. **不跑 HTTP**：插件直连 `SessionRuntime`，不起本地服务器，不需要 token。
6. **配置与密钥**：项目级 `PersistentStateComponent` 存 team/season/provider/configPath；
   API key 只进 PasswordSafe（key 用 provider 的 apiKeyEnv 变量名），绝不写入任何文件。

## 4. UI 草案

```
┌ FTC 知识库 ────────────────────────────┐
│ 状态条：队伍20827 · 2025-2026 · deepseek │ 询问 ▸ / 编辑 ▸  有改动● │
├────────────────────────────────────────┤
│ （对话流：用户问题 / 回答按声明类别着色、  │
│   引用以 [RULE:R1][CODE:C1] 徽标展示）    │
├────────────────────────────────────────┤
│ [输入框………………………] [发送]              │
│ [撤销] [放弃] [显示差异] [保存会话] [清空] [设置] │
└────────────────────────────────────────┘
```

- 「显示差异」在 IDE diff viewer 打开本轮 Agent 改动的 before/after（多个文件分页）；
- 「设置」弹窗改队伍/赛季/Provider/配置路径，改参数不清空对话（与 serve 一致）；
- 全部界面文案中文；回答内容用 `textContent` 等价方式渲染（IntelliJ 用 styled text，不注入 HTML）。

## 5. 验证方式（谁验证什么）

我能自证的（沙箱内）：

- 前置重构后 406 项离线测试全绿；
- 插件模块 `./gradlew :apps/android-studio-plugin:buildPlugin` 编译 + 打包 zip 成功；
- 非 UI 逻辑（配置持久化、项目判定、diff 构造）配 headless 单元测试。

需要你在本机做的（我无法替代）：

- `./gradlew :apps/android-studio-plugin:runIde` 启动带插件的 IDE 实例，确认工具窗口出现；
- 打开你们的 FTC 项目：问一个问题、切编辑模式改一次、看 diff viewer、undo；
- 首次输入 API key 后确认存入系统钥匙串（Keychain Access 可见）。

插件安装：`build/distributions/ftckb-as-<version>.zip` → AS 设置 → Plugins → Install from Disk。

## 6. 里程碑

1. M1 前置重构：`modules/session-shell` 抽取 + 全部测试绿（无行为变化）；
2. M2 插件骨架：gradle 插件模块 + plugin.xml + 空工具窗口可编译打包；
3. M3 会话接通：FtckbService + 聊天 UI + 设置（Ask 先跑通）；
4. M4 Edit + IDE diff viewer + undo/discard；
5. M5 文档（docs/android-studio-plugin.md 使用说明 + 验收清单）+ 推送合并。
