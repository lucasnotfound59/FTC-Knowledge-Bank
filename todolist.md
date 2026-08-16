# FTC Knowledge Bank Roadmap

本文记录项目任务与长期方向。它是路线草案，不表示对应功能已经实现，也不构成固定发布日期。

## MVP：队内代码 Agent 闭环

- [x] 定义知识条目、候选规范、正式规范和弃用规范的数据格式；
- [x] 定义共享规范、队号专属规范和赛季配置的覆盖规则；
- [x] 建立 `20827` 与 `16093` 队号档案；
- [x] 导入代码仓库并识别 FTC SDK 版本、依赖、目录和代码结构（repository-analysis 模块 + RepositoryIndex）；
- [ ] 从代码中提取带文件证据和可信度的候选规范；
- [ ] 实现总软件负责人和队伍软件负责人的审批流程；
- [x] 实现命令行连续聊天 Agent，由本地 Kotlin Runtime 管理会话、检索、引用和权限；
- [x] 实现 OpenAI-compatible Chat Completions provider，支持 OpenAI、DeepSeek 和自定义 `baseUrl` / `model`；
- [x] 实现 Ask 模式：使用两阶段本地检索解释现有代码，并区分正式规则、代码观察和模型推测；
- [x] 实现 Edit 模式：在用户当前 Git branch 内自动修改，引用采用的规范，展示 Agent 本轮 diff，并支持 `/undo` 与 `/discard`；
- [ ] 实现 Run 模式：运行 Gradle 构建并诊断失败原因；
- [x] 保护仓库外路径、凭据和 `.git`；允许当前 branch 有既有改动，但 Agent 不得自动 commit、push 或 merge；
- [x] 稳定机器接口：`ftckb validate/resolve --json`（schemaVersion=1、确定性排序、统一 JSON 错误形状），供外部 Agent 把知识库当确定性“策略裁决器”调用（契约文档 `docs/kernel-contract.md`）；
- [x] 本地网页会话 `ftckb serve`：127.0.0.1 单会话中文界面，随机端口 + 一次性 token，页内改参数保留对话历史，API key 只进内存；
- [x] 固定场景质量评估 `ftckb eval`（5 个场景、逐条 PASS/FAIL）；离线脚本化全绿，线上 deepseek-v4-pro 连续多轮 5/5（预算提升 + 检索兜底 + 提示词加固后稳定）；
- [ ] 建立测试样例，验证 Agent 不会把遗留代码、重复依赖或单次写法误判为正式规范（现有 eval 场景尚未覆盖此类负例）；
- [ ] 制作 Android Studio 插件的最小交互界面；

## 后续组件：联网查询官方文档

第一版命令行 Agent 不联网，只使用本地 FTC 仓库、已审批规则和已入库教程。下列联网能力必须由 Agent Runtime 统一控制，不能依赖某个模型供应商自带的网页搜索。

- [ ] 实现仅允许 FIRST、FTC SDK、Pedro Pathing、FTCLib、FTC Dashboard、Limelight 和 goBILDA 官方域名的 HTTP 获取器；
- [ ] 限制重定向、协议、响应类型、页面大小和超时，并拒绝私有地址与凭据 URL；
- [ ] 将网页内容视为不可信数据，防止网页中的提示词改变 Agent 权限或工具调用；
- [ ] 为联网结论保留 URL、发布者、版本、章节和获取时间，并在回答中生成可验证引用；
- [ ] 联网内容只能作为一般建议或候选规则证据，不得在未经审批时自动变成正式队伍规则；
- [ ] 为白名单、网页清理、提示注入、引用验证和网络失败建立离线测试。

## 下一阶段：FTC 工具链助手

- [ ] 从最新 FIRST 官方仓库创建新赛季工程；
- [ ] 图形化安装 FTC Dashboard、FTCLib、Pedro Pathing 等批准依赖；
- [ ] 根据 SDK 版本生成 Gradle 修改并检测依赖冲突；
- [ ] 支持 Gradle Sync、Build、日志收集和错误解释；
- [ ] 支持连接 Control Hub；
- [ ] 在不可绕过的人工确认后执行部署；
- [ ] 记录构建、部署、失败和回滚审计信息；

## 新人知识内容：Pedro Pathing 与 Limelight

Pedro 的内容契约与隔离编译基线已经入库，实机验收和后续升级仍未完成。Limelight 与 goBILDA 目前仍是文档教程，尚未达到 Pedro 示例的分阶段可运行验收层级。后续录入继续优先使用官方文档，并为结论保留可复核的来源、版本与页面位置证据。知识库的核心验收目标之一，是让 FTC 新生能够回答“该填什么参数、在哪里填、为什么这样填，以及如何写对应代码”。

### Pedro Pathing 官方文档接入

- [x] 固定 Pedro 官方文档、仓库证据和已审查的 FTC SDK/Pedro 版本矩阵；
- [x] 建立新人参数字典，逐项记录填写内容、获取方法、单位或范围和验证方式；
- [x] 提供默认锁定的 canonical `SafePedroAuto.java` 安全示例；
- [x] 添加规范示例与教程的 source-contract 自动化测试；
- [x] 添加 FTC SDK 11.2.0 + Pedro 2.1.2 隔离编译 fixture；
- [x] 加入 20827 Auto 架构的固定 commit、非规范映射案例；
- [ ] 在真实机器人上依次完成 `CONFIG_CHECK`、`SERVO_ONLY`、`SHORT_DRIVE`、`FULL_AUTO` 四阶段验收并记录结果；
- [ ] 建立 Pedro 版本升级兼容 lane，升级依赖后重新执行内容、编译和实机验收；
- [ ] 编写更完整的常见配置、构建、运行和路径跟随错误诊断案例；
- [ ] 按 season、SDK、Pedro Pathing 版本和硬件前提持续维护内容适用范围；
- [ ] 扩展新人验收练习，使队员能独立填写参数、定位配置、解释原因并完成最小路径代码；

### Limelight 官方文档接入

- [ ] 记录官方文档、官方软件/固件与硬件版本元数据，保留抓取或核验时间和来源证据；
- [ ] 建立参数词典，逐项记录单位、有效范围或默认值、设备或代码中的配置位置以及参数作用；
- [ ] 提供与受支持版本对应的最小可运行代码，并标明 pipeline、连接方式和必要依赖；
- [ ] 编写分步安装、标定、pipeline 配置和结果验证流程，解释每一步的判断依据；
- [ ] 汇总常见连接、坐标系、延迟、识别和代码集成错误，并给出可观察症状与诊断步骤；
- [ ] 标注每条内容适用的 FTC season、SDK、Limelight 型号、软件/固件和 hardware 前提；
- [ ] 设计新人学习路径与验收练习，要求能独立填写参数、定位配置、解释原因并完成最小视觉代码；

- [ ] 为 Limelight 与 goBILDA 教程补充和 Pedro 同级的可运行、分阶段安全示例及自动化契约；

## 长期方向一：完整 FTC 全流程 IDE

- [ ] 评估从插件演进为独立 FTC IDE 的必要性与维护成本；
- [ ] 集成项目创建、代码编辑、依赖管理、Git、构建、日志、设备和机器人配置；
- [ ] 提供适合新队员的引导式工作流和适合高级队员的完整控制能力；
- [ ] 复用 FTC Agent Core，避免把知识、规则和工具逻辑绑定在编辑器界面中；

## 长期方向二：适配所有 FTC 队伍

- [ ] 支持导入任意 FTC 代码仓库；
- [ ] 自动识别所用 SDK、库、架构、命名习惯和工具；
- [ ] 自动生成带来源和可信度的候选编码规范；
- [ ] 区分高质量模式、队伍特例、冲突写法、重复配置和遗留代码；
- [ ] 允许每支队伍建立私有队号档案、审批人和规范覆盖层；
- [ ] 设计通用知识包格式及安全的导入、导出和版本升级机制；
- [ ] 在队内版本验证成熟之前，不引入公开账号、多队伍托管或知识市场；

## 平台演进与研究

- [ ] 持续跟踪 FIRST、WPILib 和 SystemcoreTesting 的正式变化；
- [ ] 实现 Control Hub Adapter：Android Gradle、FTC SDK、ADB 和 Logcat；
- [ ] 在接口稳定后实现 Systemcore Adapter：WPILib、GradleRIO、Linux Deploy 和 WPILog；
- [ ] 研究 VS Code 客户端，以支持 Systemcore 的官方工具链；
- [ ] 研究知识和算法层面的迁移工具，不假设 FTCLib、Pedro Pathing 或 FTC Dashboard 可直接移植；

## 尚待设计

- [ ] 未来 Android Studio 插件与 Agent Core 的调用接口和生命周期；
- [ ] 规则冲突解释、审批历史和规范版本回滚；
- [ ] Run 模式中构建验证之外的单元测试、静态检查和模拟策略；
