package org.ftckb.session

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import org.eclipse.jgit.api.Git
import org.ftckb.audit.*
import org.ftckb.model.*

internal class SessionTestFixture(val root:Path) {
    val knowledge=Files.createDirectories(root.resolve("knowledge")).toRealPath()
    val ruleFile=knowledge.resolve("rule.yaml")
    val repository=root.resolve("repo")
    val auditBase=root.resolve("audit")
    val sourcePath="TeamCode/src/main/java/example/Vision.java"
    val source:Path get()=repository.resolve(sourcePath)
    var beforeResponse:()->Unit={}
    var failModel=false
    var malformedEditPlan=false
    var citation="CODE:C1"
    var extraOperation=""
    var calls=0
    var replacement="if(result!=null) consume(result);"
    val provider=ModelProvider { request ->
        calls++
        if(failModel) throw ModelProviderException.Transport(IllegalStateException("PRIVATE_EXCEPTION_SENTINEL"))
        if(request.messages.first().content.startsWith("Return exactly one JSON object")) ModelResponse("""{"concepts":["Vision"],"symbols":["Vision"],"pathGlobs":["$sourcePath"],"ruleTopics":["safety"],"guideTopics":[]}""",TokenUsage(3,4))
        else if(request.messages.first().content.startsWith("Return exactly one JSON edit plan")) {
            val text=Files.readString(source)
            val hash=MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
            beforeResponse()
            if(malformedEditPlan) ModelResponse("{ PRIVATE_MALFORMED_PLAN_SENTINEL") else
            ModelResponse("""{"summary":"PRIVATE_COMPLETION_SENTINEL","operations":[{"kind":"replace","path":"$sourcePath","expectedSha256":"$hash","oldText":"consume(result);","newText":"$replacement","reason":"safe edit","citations":["$citation"]}$extraOperation]}""",TokenUsage(5,6))
        } else ModelResponse("""{"claims":[{"kind":"model_inference","text":"PRIVATE_COMPLETION_SENTINEL","citations":[]}]}""",TokenUsage(7,8))
    }
    init {
        val fixtures=Path.of("..","..","fixtures","agent","edit-repo")
        Files.walk(fixtures).use { paths -> paths.forEach { from ->
            val to=repository.resolve(fixtures.relativize(from).toString())
            if(Files.isDirectory(from)) Files.createDirectories(to) else Files.copy(from,to)
        } }
        Git.init().setDirectory(repository.toFile()).setInitialBranch("team-work").call().use { git ->
            git.add().addFilepattern(".").call()
            git.commit().setMessage("baseline").setAuthor("test","test@example.invalid").call()
        }
        Files.writeString(ruleFile,rule())
    }
    fun rule(hard:Boolean=false,instruction:String="Review safety")="""
        schemaVersion: 4
        rules:
          - id: shared.safety
            topic: safety
            title: Safety policy
            instruction: $instruction
            rationale: Safety rationale
            status: approved
            authority: shared
            policyLevel: shared
            approval:
              approver: test
              role: overall_software_lead
              approvedAt: 2026-10-19T00:00:00Z
            applicability:
              teams: []
              seasons: []
              profiles: []
            evidence:
              - type: git
                repository: test/repo
                commit: abc1234
                file: src/Main.java
                line: 1
    """.trimIndent()+"\n"+(if(hard) "    checks:\n      - kind: regex-forbidden\n        pattern: consume\\(result\\)\n        note: Forbidden consume\n" else "")
    fun runtime(entrypoint:String="chat"):SessionRuntime {
        val captured=org.ftckb.knowledge.KnowledgeWorkspace(knowledge).snapshot()
        check(captured.valid) { "Fixture invalid: ${captured.violations}; ${captured.loadErrors}; source=${Files.readString(ruleFile)}" }
        val config=Files.writeString(root.resolve("config.yaml"),"""
            defaultProvider: fake
            providers:
              fake:
                baseUrl: https://example.invalid/v1
                model: fake-model
                apiKeyEnv: FTC_TEST_KEY
        """.trimIndent())
        return SessionRuntime(config,{ "PRIVATE_KEY_SENTINEL" },{ _,_->provider },{root},
            { index->{ paths->index.refresh(paths) } },repository,knowledge,"20827","2025-2026","fake",emptySet(),
            entrypoint,{ path->FileAuditStore(auditBase,path) })
    }
    fun store()=FileAuditStore(auditBase,knowledge)
    fun auditText():String=Files.walk(auditBase).use { paths-> paths.filter { it.fileName.toString()=="events.jsonl" }.map(Files::readString).toList().joinToString("\n") }
}
