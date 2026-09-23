# 常见问题与故障排查

当前发布为 V0.8.0（CLI 2.1.0、YAML v4、kernel JSON v2、项目接入协议 v2），知识总数为 48（42 已批准 + 6 候选）。

| 现象 | 常见原因 | 处理方法 |
| --- | --- | --- |
| Gradle 找不到 JDK 21 toolchain | 旧版仓库或网络受限 | 通常构建会自动下载 JDK 21 工具链（Foojay）；如仍失败，运行 `java -version` 确认使用 JDK 21+，或把 `JAVA_HOME` 指向 JDK 21 |
| `error loading knowledge`（退出 `2`） | YAML 语法错误、重复键、未知字段、类型错误、错误 schema 版本，或 `<knowledge-root>` 指向不存在/非目录路径 | 阅读冒号后的首行详情，确认目录存在并检查最近编辑的 `.yaml`/`.yml`，再运行 `validate` |
| `invalid rule id` 或 `topic must be a canonical slug` | `id`/`topic` 含大写、空格、下划线或不允许的分隔方式 | 按上文正则改成小写 canonical 格式；`id` 可用 `.`/`-`，`topic` 只用 `-` |
| `commit must be a Git SHA` 或 evidence file 错误 | commit 不是 7–64 位十六进制，或 file 是绝对路径、含反斜杠、空段、`.`/`..` | 使用真实 commit SHA 和 `/` 分隔的仓库相对路径 |
| `approved rule requires approval` | 已将状态改为 approved，但审批对象缺失 | 根据 authority 添加正确的 `approval`；不要伪造审批角色 |
| `approval is not authorized for rule authority and teams` | official/shared 使用了队伍负责人，或 team 审批队号与唯一适用队号不一致 | official/shared 改由 `overall_software_lead` 审批；team 由匹配队号的 `team_software_lead` 审批 |
| candidate 校验通过但没有 active 输出 | 这是预期行为，候选规则尚未获批 | 由授权负责人审查；获批后添加 approval、改为 approved，再 validate/resolve |
| 输出 `conflict topic=... rules=...` | 同一 topic 的最高有效权威层级有多条适用规则 | 调整适用范围、合并/废弃冲突规则或保留一个获批规则，然后重新解析 |
| CLI 退出 `64` | 缺少命令或 `<knowledge-root>`，或选项/参数格式错误 | 对照通用语法；resolve 必须各提供一次 `--team <digits>` 和 `--season <YYYY-YYYY>`。已提供但不存在的目录属于 load failure，退出 `2` |
| `global.test-utility-layout`，退出 `1` | 目标 TeamCode 改动使用了错误 tests/utils 路径，或新增了 JUnit import/dependency | 机器人侧 OpMode 移到 `TeamCode/src/main/java/org/firstinspires/ftc/teamcode/tests/`，可复用工具移到 `TeamCode/src/main/java/org/firstinspires/ftc/teamcode/utils/`；不要用 JUnit、`src/test` 或 `src/androidTest`。这是当前第 5 条带硬检查的 active rule；完整 Agent instruction 仍负责语义分类，不影响 Knowledge Bank 自身 Kotlin/CLI JUnit 测试，也不证明部署/机器人运行。 |
| `check --work-mode dev requires --diff FILE`，退出 `64` | 指定了 dev 工作模式但没有给本次改动的补丁 | 用 `--diff <file.patch>` 明确本次改动范围；dev 不会自动放松整个脏工作区。 |
| test/dev 工作模式被误用或输出被当作正式通过 | 模式是逐任务临时参数，不能按文件名、路径或依赖推断；dev 只把 hard 转 soft 并退 0 | 只有用户明确说明当前是测试代码或 dev 代码时才选择；test 只豁免两条架构规则，其余硬检查（含 tests/utils 与 JUnit）继续生效，dev 报告必须逐项说明被降级的命中。 |
| 改动 `build.dependencies.gradle` 后出现 `global.vendor-documented-build-dependencies` soft、退出 `0` | 新增、修改、删除或重命名根依赖文件都会触发跨赛季条件式 soft | 打开依赖厂商的第一方官方文档或官方仓库固定 commit，核对 repository/dependency、精确版本和 diff 逐项对应，并把 Gradle sync/build 结果与部署/真机验证分开报告。规则引擎不联网鉴别来源，也不验证证据真伪；soft 不是自动放行，缺少第一方证据时应撤回或修正改动。 |

## 提交问题

请在 [GitHub Issues](https://github.com/lucasnotfound59/FTC-Knowledge-Bank/issues) 提供命令、退出码、版本和脱敏后的错误信息。不要包含 API key、敏感队伍数据或个人路径。

Limelight validity/freshness 是 approved 的**条件式 soft**：只有 `reviewTriggers` 匹配相机类型或结果读取的新增行才出现；无关 Java 不产生 Limelight soft，命中后若无其他硬违规仍是退出码 0。`global.vendor-documented-build-dependencies` 同样是条件式 soft：任何触及根目录 `build.dependencies.gradle` 的 diff 都进入 soft，要求第一方厂商证据，但机器不验证来源真伪。soft 只要求人工/模型复核，不是机器证明的违规或真机验证；**Agent 必须向用户报告**命中的 soft，不要插入无意义调用改变结果。
