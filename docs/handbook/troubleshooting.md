# 常见问题与故障排查

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

## 提交问题

请在 [GitHub Issues](https://github.com/lucasnotfound59/FTC-Knowledge-Bank/issues) 提供命令、退出码、版本和脱敏后的错误信息。不要包含 API key、敏感队伍数据或个人路径。

当前两条 Limelight Java regex-required 的适用范围较宽，可能误报无关新增代码。应报告问题，不要插入无意义调用绕过检查。
