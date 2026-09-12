package org.ftckb.cli

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import org.ftckb.domain.*
import org.ftckb.knowledge.FileKnowledgeRepository
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TeamChinaRuleMigrationAcceptanceTest {
    private val root=Path.of("..","..")
    private val teamChinaCommit="9be3eb7776f35d71e60cec4cb47be6bd7892acee"
    private val javaRoot="TeamCode/src/main/java/org/firstinspires/ftc/teamcode/"
    private val candidates=setOf("global.hardware-access-candidate","global.mechanism-state-machine-candidate")
    private val commands=setOf("global.command-responsibilities","global.command-live-input","global.command-requirements-cleanup")
    private val migrated=mapOf(
        "global.hardware-access-candidate" to "team-20827.hardware-layer-candidate",
        "global.hardware-container" to "team-20827.hardware-container",
        "global.motor-configuration" to "team-20827.motor-init-safety",
        "global.constants-centralized" to "team-20827.constants-centralized",
        "global.documentation-intent" to "team-20827.chinese-javadoc",
        "global.telemetry-organization" to "team-20827.telemetry-multiple",
        "global.naming-conventions" to "team-20827.naming-conventions",
        "global.mechanism-state-machine-candidate" to "team-16093.fsm-candidate"
    )

    private fun rules():List<KnowledgeRule> {
        val loaded=FileKnowledgeRepository.load(root.resolve("knowledge"))
        assertTrue(loaded.violations.isEmpty(),loaded.violations.joinToString())
        return loaded.rules
    }

    private fun resolve(profiles:Set<String> =emptySet(),team:String="20827",season:String="2025-2026"):ResolutionResult {
        val result=RuleResolver.resolve(rules(),RuleContext(team,season,profiles))
        assertTrue(result.conflicts.isEmpty(),result.conflicts.joinToString())
        return result
    }

    @Test
    fun `both teams and an unrelated team receive the same global standards`() {
        val generic=resolve().activeRules.map { it.id }
        for (team in listOf("16093","99999")) assertEquals(generic,resolve(team=team).activeRules.map { it.id })
        assertTrue("global.hardware-container" in generic)
        assertTrue(generic.none { it.startsWith("team-") })
    }

    @Test
    fun `migration preserves exact counts statuses approvals and season boundaries`() {
        val all=rules()
        assertEquals(46,all.size)
        assertEquals(40,all.count { it.status==RuleStatus.APPROVED })
        assertEquals(6,all.count { it.status==RuleStatus.CANDIDATE })
        val global=all.filter { it.id.startsWith("global.") }
        assertEquals(migrated.keys+commands,global.map { it.id }.toSet())
        for (rule in global) {
            assertEquals(RuleAuthority.SHARED,rule.authority,rule.id)
            assertEquals(PolicyLevel.GLOBAL,rule.policyLevel,rule.id)
            assertEquals(emptySet<String>(),rule.applicability.teams,rule.id)
            assertEquals(setOf("2025-2026"),rule.applicability.seasons,rule.id)
            assertEquals(if (rule.id in commands) setOf("command-based") else emptySet(),rule.applicability.profiles,rule.id)
            assertEquals(migrated[rule.id],rule.supersedes,rule.id)
            assertTrue(rule.checks.isEmpty(),"semantic guidance must remain soft: ${rule.id}")
            if (rule.id in candidates) {
                assertEquals(RuleStatus.CANDIDATE,rule.status,rule.id)
                assertNull(rule.approval,rule.id)
            } else {
                assertEquals(RuleStatus.APPROVED,rule.status,rule.id)
                val approval=rule.approval!!
                assertEquals("lucasnotfound59",approval.approver,rule.id)
                assertEquals(ApproverRole.OVERALL_SOFTWARE_LEAD,approval.role,rule.id)
                assertNull(approval.team,rule.id)
                assertTrue(approval.approvedAt>=Instant.parse("2026-09-11T00:00:00Z"),rule.id)
            }
        }
        assertTrue(resolve(season="2026-2027").activeRules.none { it.id.startsWith("global.") })
    }

    @Test
    fun `command profile adds exactly three standards without selecting a framework`() {
        val generic=resolve().activeRules.map { it.id }.toSet()
        val command=resolve(setOf("command-based")).activeRules.map { it.id }.toSet()
        assertEquals(commands,command-generic)
        assertEquals(emptySet<String>(),generic-command)
        assertFalse("shared.ftclib-command-candidate" in command)
    }

    @Test
    fun `architecture profiles isolate RookieBot and FTCLib rules`() {
        val generic=resolve().activeRules.map { it.id }.toSet()
        val rookieIds=rules().filter { it.id.startsWith("shared.rookiebot-") }.map { it.id }.toSet()
        assertEquals(12,rookieIds.size)
        assertTrue(generic.intersect(rookieIds+"shared.ftclib-command-candidate").isEmpty())
        val rookie=resolve(setOf("rookiebot"))
        assertEquals(setOf("rookiebot","simple-opmode"),rookie.profiles)
        assertEquals(rookieIds,rookie.activeRules.map { it.id }.toSet()-generic)
        val ftclib=resolve(setOf("ftclib-command"))
        assertEquals(setOf("ftclib-command","command-based"),ftclib.profiles)
        assertEquals(commands+"shared.ftclib-command-candidate",ftclib.activeRules.map { it.id }.toSet()-generic)
        assertTrue(ftclib.activeRules.none { it.id in rookieIds })
        for (rule in rules().filter { it.id in rookieIds || it.id=="shared.ftclib-command-candidate" }) {
            val isRookie=rule.id in rookieIds
            assertEquals(if (isRookie) PolicyLevel.LOCAL else PolicyLevel.SHARED,rule.policyLevel,rule.id)
            assertEquals(setOf(if (isRookie) "rookiebot" else "ftclib-command"),rule.applicability.profiles,rule.id)
            assertEquals(Instant.parse(if (isRookie) "2026-09-06T16:03:37Z" else "2026-09-06T03:06:57.152813Z"),rule.approval!!.approvedAt,rule.id)
        }
    }

    @Test
    fun `candidates never activate under any supported project profile`() {
        assertEquals(candidates,rules().filter { it.id.startsWith("global.") && it.status==RuleStatus.CANDIDATE }.map { it.id }.toSet())
        for (profile in listOf(emptySet(),setOf("simple-opmode"),setOf("command-based"),setOf("rookiebot"),setOf("ftclib-command"))) {
            val result=resolve(profile)
            assertTrue(result.activeRules.none { it.id in candidates })
            assertEquals(candidates,result.excludedRules.filter { it.ruleId in candidates && "status" in it.reasons }.map { it.ruleId }.toSet())
        }
    }

    @Test
    fun `global evidence retains old pins and exact TeamChina symbols`() {
        val global=rules().filter { it.id.startsWith("global.") }
        assertEquals(11,global.size)
        val expectedChina=mapOf(
            "global.hardware-container" to setOf("Hardwares.java:Hardwares"),
            "global.motor-configuration" to setOf("subsystems/Shooter.java:init"),
            "global.constants-centralized" to setOf("subsystems/Constants.java:Constants"),
            "global.documentation-intent" to setOf("subsystems/Intake.java:Intake"),
            "global.telemetry-organization" to setOf("opmodes/TeleOpSolo.java:TeleOpSolo"),
            "global.naming-conventions" to setOf("Hardwares.java:Hardwares"),
            "global.command-responsibilities" to setOf("opmodes/TeleOpSolo.java:TeleOpSolo","subsystems/Intake.java:Intake"),
            "global.command-live-input" to setOf("commands/DriveCommand.java:DriveCommand"),
            "global.command-requirements-cleanup" to setOf("commands/DriveCommand.java:DriveCommand","commands/DriveCommand.java:end")
        )
        for (rule in global) {
            assertTrue(rule.evidence.isNotEmpty(),rule.id)
            assertTrue(rule.evidence.all { it is GitRuleEvidence },rule.id)
            val git=rule.evidence.filterIsInstance<GitRuleEvidence>()
            assertTrue(git.all { it.commit.matches(Regex("[0-9a-f]{40}")) },rule.id)
            if (rule.id in migrated) {
                val isFsm=rule.id=="global.mechanism-state-machine-candidate"
                assertTrue(git.any {
                    it.repository==if (isFsm) "tqdmye/FTC2026-16093National" else "xiaokai-lyk/FTC20827-2026Decode"
                },rule.id)
                assertTrue(git.any { it.commit==if (isFsm) "3e6de8944081ef347fbb76b2f97c89b89b10b669" else "118c28e137334bbbea510d77f1fa384e8b1b5779" },rule.id)
            }
            val china=git.filter { it.repository=="OLeslieO/FGC2026-TeamChina" }
            assertTrue(china.all { it.commit==teamChinaCommit && it.file.startsWith(javaRoot) },rule.id)
            assertEquals(expectedChina[rule.id]?:emptySet<String>(),china.map { it.file.removePrefix(javaRoot)+":"+it.symbol }.toSet(),rule.id)
        }
    }

    @Test
    fun `approved instructions match the reviewed policies exactly`() {
        val expected=mapOf(
            "global.hardware-container" to "所有硬件引用集中在一个 Hardwares 容器类中，并按 Sensors、Motors、Servos 等职责分组；子系统接收该容器，不直接从 HardwareMap 获取设备。",
            "global.motor-configuration" to "每个电机必须在机构初始化阶段明确配置方向、运行模式和 ZeroPowerBehavior；BRAKE 或 FLOAT 应按机构需要选择，不得无依据统一套用。",
            "global.constants-centralized" to "调参与机构参数集中存放并使用能表达用途或单位的名称；可以使用 enum、static final 或配置对象，不在逻辑代码中散落魔数。",
            "global.documentation-intent" to "为子系统、工具类和不明显的机构逻辑编写中文说明，重点解释用途、单位、有效范围和安全理由；不强制固定 Javadoc 或分节模板。",
            "global.telemetry-organization" to "项目使用 FtcDashboard 时，通过 MultipleTelemetry 同时提供 Driver Station 与 Dashboard 遥测，并按机构或用途组织关键数据；不强制固定分隔符。",
            "global.naming-conventions" to "类使用 PascalCase，字段、方法和硬件配置名使用表达机构语义的 lowerCamelCase；不强制电机字段使用 m 前缀。本赛季自动类继续使用 <Side><Color>[Mini] 与 Base 抽象类的组合命名，不据此推断未来赛季路线。",
            "global.command-responsibilities" to "命令式项目中，OpMode 负责生命周期与输入绑定，Subsystem 封装机构能力，Command 协调动作与调度。",
            "global.command-live-input" to "需要连续响应手柄的 Command 通过 Supplier 在执行时读取输入，不在创建 Command 时固定一次性快照。",
            "global.command-requirements-cleanup" to "控制 SubsystemBase 的 CommandBase 显式声明 requirements；产生持续机构输出的命令在结束或中断时恢复安全输出。"
        )
        val actual=rules().filter { it.id in expected }.associate { it.id to it.instruction }
        assertEquals(expected,actual)
    }

    @Test
    fun `old loadable files are replaced by an auditable migration ledger`() {
        for (path in listOf("20827/rules.yaml","20827/style-rules.yaml","16093/rules.yaml")) {
            assertFalse(Files.exists(root.resolve("knowledge/teams/$path")),path)
        }
        val ledger=Files.readString(root.resolve("docs/rule-migrations/2026-09-11-team-rules-global-teamchina.md"))
        for (id in migrated.keys+migrated.values+commands) assertTrue(id in ledger,id)
        for (timestamp in listOf("11:09:36.390654","11:09:36.722832","11:09:37.064018","11:09:37.403584","11:09:37.746027","11:09:38.091448")) {
            assertTrue("2026-08-27T${timestamp}Z" in ledger,timestamp)
        }
        assertTrue(teamChinaCommit in ledger)
        assertTrue("team_software_lead" in ledger)
        assertTrue("overall_software_lead" in ledger)
    }
}
