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
./gradlew :apps:knowledge-cli:run --args="resolve knowledge --team 20827 --season 2025-2026" --quiet
```

Windows PowerShell 使用 wrapper 的 `.bat` 文件：

```powershell
.\gradlew.bat :apps:knowledge-cli:run --args="validate knowledge" --quiet
.\gradlew.bat :apps:knowledge-cli:run --args="resolve knowledge --team 20827 --season 2025-2026" --quiet
```

后文所有 `./gradlew` 命令在 Windows 上都替换为 `.\gradlew.bat`。

关键输出会包括：

```text
validation=ok rules=43
active official.keep-customizations-in-teamcode
active shared.dashboard-pin-stable-dependency
...
active shared.pedro-tune-current-robot
```

20827 在该赛季会输出 37 条 active 规则（1 条官方、30 条共享、6 条队伍规则）；16093 为 31 条。其余 6 条规则是 `candidate`，保持 inactive。12 条 RookieBot 规则已批准，但其 instruction 仅适用于采用该新手教程约定的项目，不要求其他项目更换架构。

## 受限构建环境

如果默认 Gradle 缓存不可写，可将 GRADLE_USER_HOME 设置到可写目录；运行 CLI 仍需 JDK 21+。构建后使用 `apps/knowledge-cli/build/install/ftckb/bin/ftckb`，Windows 使用同目录下的 `ftckb.bat`。

## 两种使用方式

接入现有编码 Agent 不需要额外模型 key；使用知识库自带的聊天客户端需要供应商配置。项目接入参见[接入指南](../../docs/project-integration.md)，聊天与编辑参见[CLI 指南](../../docs/cli-agent.md)。
