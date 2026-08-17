package org.ftckb.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.io.StringReader
import java.nio.file.Files
import java.nio.file.Path
import org.eclipse.jgit.api.Git
import org.ftckb.knowledge.RuleYamlCodec
import org.ftckb.domain.RuleValidator
import org.ftckb.model.ModelMessage
import org.ftckb.model.ModelProvider
import org.ftckb.model.ModelRequest
import org.ftckb.model.ModelResponse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ExtractAcceptanceTest {
    @Test
    fun `extract writes host-validated candidate rules with confidence comments`(@TempDir root:Path) {
        val repository=writeRepository(root.resolve("repo"))
        val provider=ExtractProvider(proposals="""
            {
              "candidates":[
                {
                  "topic":"fsm-autonomous-phases",
                  "title":"Use an FSM for autonomous phases",
                  "instruction":"Keep autonomous phases in one state machine class.",
                  "rationale":"Predictable transitions between phases.",
                  "confidence":"high",
                  "evidence":[
                    {"file":"TeamCode/src/main/java/example/FsmRunner.java","symbol":"advance","line":6},
                    {"file":"TeamCode/src/main/java/example/FsmRunner.java","symbol":"FsmRunner","line":3}
                  ]
                },
                {
                  "topic":"teleop-null-guard",
                  "title":"Null-check teleop hardware",
                  "instruction":"Check motor references before use.",
                  "rationale":"Avoid null dereferences at runtime.",
                  "confidence":"high",
                  "evidence":[
                    {"file":"TeamCode/src/main/java/example/SampleTeleOp.java","symbol":"motor"}
                  ]
                }
              ]
            }
        """.trimIndent())
        val output=ByteArrayOutputStream()
        val config=writeConfig(root.resolve("config.yaml"))
        val options=ExtractOptions(
            repository,"20827",null,"fake",config,null,root.resolve("extracted.yaml"),8
        )

        val code=ExtractCommand(
            environment={ "fixture-secret" },
            providerCreator={ _,_ -> provider },
            clock={ java.time.Instant.parse("2026-08-16T12:00:00Z") }
        ).run(options,PrintStream(output))

        assertEquals(0,code,output.toString())
        val text=output.toString()
        assertTrue(text.contains("extract=2/2"),text)
        assertTrue(text.contains("team-20827.fsm-autonomous-phases"),text)
        assertTrue(text.contains("confidence=high"),text)
        assertTrue(text.contains("confidence=low needs-stronger-evidence"),text)
        assertTrue(Files.exists(root.resolve("extracted.yaml")))
        val yaml=Files.readString(root.resolve("extracted.yaml"))
        assertTrue(yaml.contains("# confidence: high"),yaml)
        assertTrue(yaml.contains("# confidence: low"),yaml)
        assertTrue(yaml.contains("# needs-stronger-evidence"),yaml)
        assertTrue(yaml.contains("id: team-20827.fsm-autonomous-phases"),yaml)
        assertTrue(yaml.contains("status: candidate"),yaml)
        assertFalse(yaml.contains("approval:"),yaml)
        val rules=RuleYamlCodec.decode(yaml)
        assertEquals(2,rules.size)
        assertTrue(rules.flatMap(RuleValidator::validate).isEmpty())
        val fsm=rules.first { it.id=="team-20827.fsm-autonomous-phases" }
        assertEquals(2,fsm.evidence.size)
        val head=headOf(repository)
        assertTrue(fsm.evidence.all { (it as org.ftckb.domain.GitRuleEvidence).commit==head })
    }

    @Test
    fun `extract uses the repository HEAD sha as evidence commit`(@TempDir root:Path) {
        val repository=writeRepository(root.resolve("repo"))
        val head=headOf(repository)
        val provider=ExtractProvider(proposals="""
            {"candidates":[{"topic":"fsm-autonomous-phases","title":"Use an FSM","instruction":"Keep phases in one FSM.","rationale":"Predictable.","confidence":"high","evidence":[{"file":"TeamCode/src/main/java/example/FsmRunner.java","symbol":"advance","line":6}]}]}
        """.trimIndent())
        val output=ByteArrayOutputStream()
        val config=writeConfig(root.resolve("config.yaml"))
        val options=ExtractOptions(
            repository,"20827",null,"fake",config,null,root.resolve("extracted.yaml"),8
        )

        val code=ExtractCommand(environment={ "fixture-secret" },providerCreator={ _,_ -> provider }
        ).run(options,PrintStream(output))

        assertEquals(0,code,output.toString())
        val yaml=Files.readString(root.resolve("extracted.yaml"))
        assertTrue(yaml.contains("commit: $head"),yaml)
    }

    @Test
    fun `extract skips topics already covered by existing rules`(@TempDir root:Path) {
        val repository=writeRepository(root.resolve("repo"))
        val knowledge=root.resolve("knowledge")
        Files.createDirectories(knowledge)
        Files.writeString(knowledge.resolve("rules.yaml"),"""
            schemaVersion: 1
            rules:
              - id: team-20827.fsm-autonomous-phases
                topic: fsm-autonomous-phases
                title: Existing FSM rule
                instruction: Already covered.
                rationale: Existing.
                status: candidate
                authority: team
                applicability:
                  teams: ["20827"]
                  seasons: []
                evidence:
                  - repository: owner/repo
                    commit: abcdef1
                    file: TeamCode/src/main/java/example/FsmRunner.java
                    symbol: FsmRunner
        """.trimIndent())
        val provider=ExtractProvider(proposals="""
            {"candidates":[
              {"topic":"fsm-autonomous-phases","title":"Duplicate","instruction":"Duplicate instruction.","rationale":"Duplicate.","confidence":"high","evidence":[{"file":"TeamCode/src/main/java/example/FsmRunner.java","symbol":"advance"}]},
              {"topic":"teleop-null-guard","title":"Null-check","instruction":"Check motor references.","rationale":"Avoid null dereferences.","confidence":"medium","evidence":[{"file":"TeamCode/src/main/java/example/SampleTeleOp.java","symbol":"motor"}]}
            ]}
        """.trimIndent())
        val output=ByteArrayOutputStream()
        val config=writeConfig(root.resolve("config.yaml"))
        val options=ExtractOptions(
            repository,"20827",null,"fake",config,knowledge,root.resolve("extracted.yaml"),8
        )

        val code=ExtractCommand(environment={ "fixture-secret" },providerCreator={ _,_ -> provider }
        ).run(options,PrintStream(output))

        assertEquals(0,code,output.toString())
        val text=output.toString()
        assertTrue(text.contains("extract=1/2"),text)
        assertTrue(text.contains("skipped topic=fsm-autonomous-phases reason=topic already covered by team-20827.fsm-autonomous-phases"),text)
        val yaml=Files.readString(root.resolve("extracted.yaml"))
        assertTrue(yaml.contains("id: team-20827.teleop-null-guard"),yaml)
        assertFalse(yaml.contains("fsm-autonomous-phases\n"),yaml)
    }

    @Test
    fun `extract drops evidence whose symbol only appears in comments`(@TempDir root:Path) {
        val repository=writeRepository(root.resolve("repo"))
        val provider=ExtractProvider(proposals="""
            {"candidates":[{"topic":"legacy-runner","title":"Legacy runner","instruction":"Keep the legacy runner.","rationale":"Because.","confidence":"high","evidence":[{"file":"TeamCode/src/main/java/example/FsmRunner.java","symbol":"LegacyRunner"}]}]}
        """.trimIndent())
        val output=ByteArrayOutputStream()
        val config=writeConfig(root.resolve("config.yaml"))
        val options=ExtractOptions(
            repository,"20827",null,"fake",config,null,root.resolve("extracted.yaml"),8
        )

        val code=ExtractCommand(environment={ "fixture-secret" },providerCreator={ _,_ -> provider }
        ).run(options,PrintStream(output))

        assertEquals(0,code,output.toString())
        val text=output.toString()
        assertTrue(text.contains("extract=0/1"),text)
        assertTrue(text.contains("reason=no valid evidence: symbol only in comments or absent: LegacyRunner"),text)
        assertFalse(Files.exists(root.resolve("extracted.yaml")))
    }

    @Test
    fun `extract prompt forbids one-off fixes commented code legacy artifacts and dependency versions`(@TempDir root:Path) {
        val repository=writeRepository(root.resolve("repo"))
        val provider=ExtractProvider(proposals="""{"candidates":[]}""".trimIndent())
        val output=ByteArrayOutputStream()
        val config=writeConfig(root.resolve("config.yaml"))
        val options=ExtractOptions(
            repository,"20827",null,"fake",config,null,root.resolve("extracted.yaml"),8
        )

        val code=ExtractCommand(environment={ "fixture-secret" },providerCreator={ _,_ -> provider }
        ).run(options,PrintStream(output))

        assertEquals(0,code,output.toString())
        val system=provider.requests.single().messages.first().content
        assertTrue(system.contains("one-off fixes for a single commit"),system)
        assertTrue(system.contains("commented-out code"),system)
        assertTrue(system.contains("legacy SDK artifacts"),system)
        assertTrue(system.contains("dependency version numbers"),system)
    }

    @Test
    fun `extract parse failures exit sixty four`() {
        val cases=mapOf(
            listOf("extract","--repo","r","--team","20827","--provider","fake","--extra","x") to
                "unknown extract option: --extra\n",
            listOf("extract","--repo","r","--team","20827") to
                "missing --provider\n",
            listOf("extract","--repo","r","--team","t-x","--provider","fake") to
                "invalid value for --team: expected digits only\n",
            listOf("extract","--repo","r","--team","20827","--season","2025-26","--provider","fake") to
                "invalid value for --season: expected YYYY-YYYY\n",
            listOf("extract","--repo","r","--team","20827","--provider","fake","--max-candidates","zero") to
                "invalid value for --max-candidates: expected a positive integer\n"
        )
        cases.forEach { (args,expected) ->
            val output=ByteArrayOutputStream()
            val code=runCli(args,PrintStream(output),StringReader("").buffered(),
                extractCommand=ExtractRunner { _,_ -> error("must not run") })
            assertEquals(64,code,"args=$args")
            assertEquals(expected,output.toString(),"args=$args")
        }
    }

    private fun writeRepository(root:Path):Path {
        val teamCode=root.resolve("TeamCode")
        val source=teamCode.resolve("src/main/java/example")
        Files.createDirectories(source)
        Files.writeString(root.resolve("settings.gradle"),"include ':TeamCode'\n")
        Files.writeString(
            teamCode.resolve("build.gradle"),
            "dependencies { implementation 'org.firstinspires.ftc:RobotCore:10.3.0' }\n"
        )
        Files.writeString(source.resolve("SampleTeleOp.java"),"""
            package example;

            import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode;
            import com.qualcomm.robotcore.eventloop.opmode.TeleOp;
            import com.qualcomm.robotcore.hardware.DcMotor;

            @TeleOp(name="Sample TeleOp")
            public final class SampleTeleOp extends LinearOpMode {
                private DcMotor motor;
                void run(){ motor.setPower(1.0); }
            }
        """.trimIndent()+"\n")
        Files.writeString(source.resolve("FsmRunner.java"),"""
            package example;

            public final class FsmRunner {
                private int phase = 0;
                public void advance() { phase = (phase + 1) % 3; }
                public int phase() { return phase; }
                // LegacyRunner was replaced by FsmRunner
            }
        """.trimIndent()+"\n")
        Git.init().setDirectory(root.toFile()).setInitialBranch("team-work").call().use { git ->
            git.add().addFilepattern(".").call()
            git.commit().setMessage("fixture").call()
        }
        return root
    }

    private fun writeConfig(path:Path):Path {
        Files.writeString(path,"""
            defaultProvider: fake
            providers:
              fake:
                baseUrl: https://example.invalid/v1
                model: offline-model
                apiKeyEnv: FTC_KB_FAKE_KEY
        """.trimIndent())
        return path
    }

    private fun headOf(repository:Path):String=
        Git.open(repository.toFile()).use { git -> git.repository.resolve("HEAD")?.name ?: error("no HEAD") }

    private class ExtractProvider(private val proposals:String):ModelProvider {
        val requests=mutableListOf<ModelRequest>()

        override fun complete(request:ModelRequest):ModelResponse {
            requests+=request.copy(messages=request.messages.map(ModelMessage::copy))
            val system=request.messages.first().content
            if (!system.startsWith("Return exactly one JSON object with the array candidates")) {
                error("unexpected extract provider request")
            }
            return ModelResponse(proposals)
        }
    }
}
