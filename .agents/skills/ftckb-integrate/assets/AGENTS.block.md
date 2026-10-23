<!-- BEGIN FTC-KNOWLEDGE-BANK -->
## FTC Knowledge Bank

修改 FTC 代码时，先读取 `.agents/skills/ftc-knowledge-bank/SKILL.md`。
配置：`.ftckb/project.yaml`；知识库是固定 commit 的 `tools/FTC-Knowledge-Bank` submodule。
v2 配置三个版本字段都为 2，并明确保存 `profiles`；`[]` 仅代表用户明确选择 generic，不能从队号推断或默认补写。
安装/升级参数 `--profile NAME` 可重复，与 `--generic-profile` 互斥。支持 rookiebot/simple-opmode/command-based/ftclib-command；rookiebot 隐含 simple-opmode，ftclib-command 隐含 command-based，simple/command 两类互斥。
用该 Skill 的项目运行脚本执行 `doctor → Dashboard 已连接 → work resolve → 修改 → work check --receipt ID`；不自行解释 YAML 决定规则优先级。
doctor 是只读接入诊断（独立 doctorSchemaVersion 1），不证明规则/代码/构建来源或连接通过；先处理诊断问题，再核对实际 knowledge 路径与审计目录一致的 Dashboard 页面已连接。未初始化或旧 pin 无 doctor 时，用外部已审阅 checkout 的绝对 project.py doctor 入口指向目标；不调用不存在的脚本，不改 pin，后续仍用固定源码。
可选 `project.py dashboard verify --project . --connection-file PRIVATE_JSON` 只GET显式既有服务，核对知识与实际审计存储；凭据只在当前用户私有url JSON，不进argv/仓库。成功也不能代替浏览器确认或证明binary pin；沿nextSteps对resolve/check保留同一显式审计env。
推荐 `project.py work resolve --project .`，按返回的完整 nextSteps 执行 `work check --receipt ID`；显式本地收据绑定配置原字节、固定版本、实际 YAML 输入及工作模式，配置/规则变化须重新 resolve，不能猜配 audit。旧 resolve/check 仍是无收据的兼容入口；收据不是身份/审批/语义或真机证明。
脚本按配置传 profile flags，核对固定源码能力、kernelSchemaVersion 和已规范化的返回 profiles。既有 v1 pin 保留；只有显式升级并选择 profile/generic 才迁移到 v2。v2 重跑/升级省略选择保留原值，不自动降级。
硬违规或规则冲突不能声称交付通过；soft 提醒如实报告。编译通过不等于真机验证通过。
不自动 commit/push，不切换用户分支；不要编辑本段，项目补充说明写在托管段外。
<!-- END FTC-KNOWLEDGE-BANK -->
