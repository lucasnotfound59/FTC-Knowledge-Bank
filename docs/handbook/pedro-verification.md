# Pedro 示例与发布验证

- [在 Android Studio 中配置 FTC SDK](../../knowledge/guides/setup/android-studio-ftc-sdk.md)
- [安装 FTCLib](../../knowledge/guides/setup/ftclib.md)
- [安装和验证 FTC Dashboard](../../knowledge/guides/setup/ftc-dashboard.md)
- [Pedro Pathing 配置、定位与调参](../../knowledge/guides/tools/pedro-pathing.md)
- [goBILDA 电机与舵机：从精确 SKU 到代码](../../knowledge/guides/tools/gobilda-motors-servos.md)
- [Limelight 3A：接线、pipeline、结果与定位](../../knowledge/guides/tools/limelight-3a.md)
- [RookieBot 新手代码约定：Hardwares、Auto、中文注释与舵机角度](../../knowledge/guides/practices/rookiebot-tutorial.md)

RookieBot 的 12 项项目实践已于 2026-09-07 经用户确认批准，记录在 [结构化已批准规则](../../knowledge/shared/practices/rookiebot-tutorial.yaml) 中，来源固定到 `5581415`；保留项目适用边界和 `flaw` 旧版记录。解析器会返回这些 active 条目，但项目适用范围仍以 instruction 为准，不自动替代其他项目的正式架构。

Pedro 新人 Auto 工作流从完整的 [参数字典](../../knowledge/guides/tools/pedro-pathing.md#safepedroauto-参数字典) 和 [四阶段实车测试清单](../../knowledge/guides/tools/pedro-pathing.md#四阶段实车测试清单) 开始。仓库只保留一份 [SafePedroAuto.java](../../knowledge/examples/pedro/SafePedroAuto.java) 规范示例；它默认锁定，在完成机器人专属配置并逐阶段验证前不可运行。

只检查教程与规范示例的内容契约，可运行快速测试：

```bash
./gradlew :apps:knowledge-cli:test --tests 'org.ftckb.cli.PedroTutorialAcceptanceTest'
```

完整发布门还会在隔离 fixture 中使用固定的 FTC SDK 11.2.0 与 Pedro 2.1.2 编译规范示例。它需要 JDK 21，并要求 `ANDROID_HOME` 或 `ANDROID_SDK_ROOT` 指向已安装的 Android SDK；macOS 使用 Android Studio 默认位置时可运行：

```bash
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
ANDROID_HOME="$HOME/Library/Android/sdk" \
./gradlew clean verifyPedroRelease
```

快速测试通过只证明内容与源码契约通过；`verifyPedroRelease` 通过进一步证明 Android 编译 fixture 通过。二者都不代表部署或实机验证通过；实机结论必须由队员按四阶段清单在对应机器人上另行验证和记录。本仓库当前没有已完成的 Pedro 实机验证记录。
