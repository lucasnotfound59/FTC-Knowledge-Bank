<!-- BEGIN FTC-KNOWLEDGE-BANK -->
## FTC Knowledge Bank

修改 FTC 代码时，先读取 `.agents/skills/ftc-knowledge-bank/SKILL.md`。
配置：`.ftckb/project.yaml`；知识库是固定 commit 的 `tools/FTC-Knowledge-Bank` submodule。
v2 配置三个版本字段都为 2，并明确保存 `profiles`；`[]` 仅代表用户明确选择 generic，不能从队号推断或默认补写。
安装/升级参数 `--profile NAME` 可重复，与 `--generic-profile` 互斥。支持 rookiebot/simple-opmode/command-based/ftclib-command；rookiebot 隐含 simple-opmode，ftclib-command 隐含 command-based，simple/command 两类互斥。
用该 Skill 的项目运行脚本执行 `resolve → 修改 → check`；不自行解释 YAML 决定规则优先级。
脚本按配置传 profile flags，核对固定源码能力、kernelSchemaVersion 和已规范化的返回 profiles。既有 v1 pin 保留；只有显式升级并选择 profile/generic 才迁移到 v2。v2 重跑/升级省略选择保留原值，不自动降级。
硬违规或规则冲突不能声称交付通过；soft 提醒如实报告。编译通过不等于真机验证通过。
不自动 commit/push，不切换用户分支；不要编辑本段，项目补充说明写在托管段外。
<!-- END FTC-KNOWLEDGE-BANK -->
