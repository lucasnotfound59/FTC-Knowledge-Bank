# 20827 BIOBUZZ 代码参考候选

规则文件：[biobuzz-2026-2027.yaml](biobuzz-2026-2027.yaml)。本批共 13 条，全部为 `candidate`，没有 `approval`；`validate` 通过不代表已批准，也不会让它们进入 `resolve.activeRules`。本次不安装目标项目接入配置，不改变旧赛季或其他队伍规则。

## 固定来源与范围

- 来源仓库：[lucasnotfound59/FTC20827-BioBuzz](https://github.com/lucasnotfound59/FTC20827-BioBuzz)。
- 参考提交：[`092afccf13bbe97c13b6f0d90ab59e6a27f8fbfd`](https://github.com/lucasnotfound59/FTC20827-BioBuzz/commit/092afccf13bbe97c13b6f0d90ab59e6a27f8fbfd)。本次核对本地 Git 对象；远端可访问性不由 validate 保证。
- 每条证据记录完整 commit、文件以及 symbol 或 README 的具体章节行号；不引用未提交工作区。
- `authority: team`、`policyLevel: local`、`teams: ["20827"]`、`seasons: ["2026-2027"]`。
- 架构相关规则限定 `command-based`；坐标、点位读取、命名、排版、调参来源规则不限制 profile。空 profiles 表示无架构限制，不表示仅 generic。
- 本批只提炼代码组织、接口与行为约定，不把电机功率、射速、坐标、PID 系数或场地尺寸推广到新机器人和新赛季。前期直接驱动与后期 Pedro 两方案都保留；默认旋转生成 BLUE，并允许单点独立精调；允许 `hMap`。
- 每条只设置路径触发的 `reviewTriggers`，不添加硬检查。批准后，触发仅表示应复核，不能证明违规；candidate 阶段不产生本批规则的有效 soft 检查。

## 待审阅条目

| 规则 ID | 内容 | Profile |
| --- | --- | --- |
| `team-20827.biobuzz-source-layout` | 按队内职责组织 TeamCode | command-based |
| `team-20827.biobuzz-solverslib-hardware-ownership` | 使用现有 SolversLib 运行时并明确硬件归属 | command-based |
| `team-20827.biobuzz-drivetrain-stages` | 按调试阶段选择直接驱动或 Pedro 底盘 | command-based |
| `team-20827.biobuzz-follower-update-owner` | 每个 Follower 明确唯一周期更新者 | command-based |
| `team-20827.biobuzz-field-points` | 统一场地单位，默认旋转生成蓝方并允许单点精调 | 不限 |
| `team-20827.biobuzz-point-read-timing` | 明确点位调参的读取时机 | 不限 |
| `team-20827.biobuzz-mechanism-tuning` | 机构共享参数归属 subsystem | command-based |
| `team-20827.biobuzz-shoot-feed-gating` | 沿用就绪门控与停止清理的射击语义 | command-based |
| `team-20827.biobuzz-autonomous-composition` | 使用 BaseAuto 的生命周期与命令组合 | command-based |
| `team-20827.biobuzz-live-controls` | 集中控制映射并区分按住与按下沿 | command-based |
| `team-20827.biobuzz-java-naming` | 使用完整语义 API 名称并允许 hMap | 不限 |
| `team-20827.biobuzz-java-formatting` | 统一队内 Java 排版并排除官方副本 | 不限 |
| `team-20827.biobuzz-vendor-tuning-provenance` | 保留 Pedro 调参副本来源，按流程更新配置 | 不限 |

## 验收与后续审批

在 FTCKB 根目录运行：

```bash
apps/knowledge-cli/build/install/ftckb/bin/ftckb validate knowledge --json
apps/knowledge-cli/build/install/ftckb/bin/ftckb resolve knowledge --team 20827 --season 2026-2027 --profile command-based --json
```

本批加入后预期为 65 条（46 approved + 19 candidate），13 条新增候选均进入 excludedRules，不改变 activeRules。批准前由 20827 软件负责人逐项审阅内容、范围和代码证据；取得明确审批身份、角色与本批授权后，在原条目内修改 status 并添加 approval，保持 ID 不变。不要复制旧审批或为让规则立即生效而自行转正；参见[贡献流程](../../../docs/handbook/contributing.md)和[审批格式](../../../docs/handbook/approval.md)。

注意：FTCKB 只提供 `command-based` profile，没有 SolversLib 专用 profile；本批 instruction 明确限定本队现有 SolversLib 运行时，并要求复用目标项目实际选定版本。候选不能作为 FIRST 或厂商规则的替代。校验、编译与静态审阅不代表 Robot Controller、Driver Station 或真机验证。

本批实际验收：全库 `validate` 返回 `ok: true`、`ruleCount: 65`、`violations: []`；45 处 Git 证据均已对照上述提交核对。对照新增前基线，20827/2026-2027 的 command-based、generic、simple-opmode，以及 16093/2026-2027 command-based、20827/2025-2026 command-based 的 activeRules 全部不变且无冲突；新增 13 条均因 candidate 状态排除，并按上下文附加 team/season/profile 排除原因。未修改任何审批，也未 commit/push FTCKB 变更。
