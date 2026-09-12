# 从零安装与第一次运行

### 1. 获取仓库

```bash
git clone https://github.com/lucasnotfound59/FTC-Knowledge-Bank.git
cd FTC-Knowledge-Bank
```

以下所有命令都在刚进入的仓库根目录运行。

### 2. 准备环境

需要安装：

- Git；
- JDK 21+ 用于运行 CLI（构建所需 JDK 21 工具链可自动下载）；
- 无需安装系统级 Gradle，仓库已包含 Gradle Wrapper。

先确认当前 Java 版本：

```bash
java -version
```

输出中的主版本应为 `21`。如果 macOS 已安装 Android Studio，可以临时使用它自带的 JDK 21：

```bash
export JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home'
```

如果 Android Studio 安装在其他位置，或使用单独安装的 JDK 21，请把 `JAVA_HOME` 指向相应目录。

Windows PowerShell 不应假设 Android Studio 的安装位置。将下面的占位路径换成实际 JDK 21 目录，再重新检查版本：

```powershell
$env:JAVA_HOME='C:\Path\To\jdk-21'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
java -version
```

### 3. 校验并解析规则

在 macOS 或 Linux 的仓库根目录运行：

```bash
./gradlew :apps:knowledge-cli:run --args="validate knowledge" --quiet
./gradlew :apps:knowledge-cli:run --args="resolve knowledge --team 20827 --season 2025-2026 --generic-profile" --quiet
```

Windows PowerShell 使用 wrapper 的 `.bat` 文件：

```powershell
.\gradlew.bat :apps:knowledge-cli:run --args="validate knowledge" --quiet
.\gradlew.bat :apps:knowledge-cli:run --args="resolve knowledge --team 20827 --season 2025-2026 --generic-profile" --quiet
```

后文所有 `./gradlew` 命令在 Windows 上都替换为 `.\gradlew.bat`。

关键输出会包括：

```text
validation=ok rules=46
active official.keep-customizations-in-teamcode
active shared.dashboard-pin-stable-dependency
...
active shared.pedro-tune-current-robot
```

知识总数为 46（40 已批准 + 6 候选）；validate 包含候选计数，6 条 `candidate` 不进入 activeRules。2025-2026 赛季下，20827 与 16093 选择相同 profile 时，active IDs 与数量相同：

| profile 选择 | 两队各自的 active 数量 |
| --- | --- |
| generic | 24 |
| command-based | 27 |
| rookiebot | 36 |
| ftclib-command | 28 |

`--generic-profile` 显式选择空 profile 集，只纳入无架构要求且队号/赛季匹配的已批准规则，不表示启用全部规则。命名选择使用 `--profile command-based` 等，可重复 `--profile NAME`；两种选择不能混用，不从依赖猜测架构。

rookiebot（RookieBot）隐含 simple-opmode，ftclib-command（FTCLib command）隐含 command-based；simple-opmode 与 command-based 互斥。RookieBot 规则受 profile 范围限制，不要求其他项目更换架构。规则的 profiles 非空时必须全部匹配；候选或队号/赛季/profile 不匹配进入 excludedRules，适用但低层级规则进入 overriddenRules。同主题最高有效层级并列进入 conflicts，不由 Agent 猜胜者。

8 条原队伍规则已移到 global，仍保留 2025-2026 赛季范围；其他赛季应重新裁决，始终传真实队号。同一项目 chat/serve/resolve/check 使用同一显式 profile 选择。机器 JSON 当前为 schemaVersion=2，包含规范化的 profiles；完整字段与错误（含缺少选择时的 context-required）见[机器契约](../kernel-contract.md)。

### 4. 构建启动器并检查项目

```bash
./gradlew :apps:knowledge-cli:installDist
apps/knowledge-cli/build/install/ftckb/bin/ftckb check /path/to/FtcRobotController --knowledge knowledge --team 20827 --season 2025-2026 --generic-profile --json
```

`validate`、`resolve`、`check` 不需要 API key，不调用模型。编译、静态检查与 JSON Schema 验证不等于 Robot Controller、Driver Station、部署或真机验证。

## 受限构建环境

如果默认 Gradle 缓存不可写，可将 GRADLE_USER_HOME 设置到可写目录；运行 CLI 仍需 JDK 21+。构建后使用 `apps/knowledge-cli/build/install/ftckb/bin/ftckb`，Windows 使用同目录下的 `ftckb.bat`。

## 两种使用方式

接入现有编码 Agent 不需要额外模型 key；使用知识库自带的聊天客户端需要供应商配置。项目接入参见[接入指南](../../docs/project-integration.md)，聊天与编辑参见[CLI 指南](../../docs/cli-agent.md)。
