package org.ftckb.cli

import com.fasterxml.jackson.databind.JsonNode
import org.ftckb.audit.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

class DashboardServerTest {
    @TempDir lateinit var temporary:Path
    private val client=HttpClient.newHttpClient()
    private val context=mapOf("team" to "20827","season" to "2025-2026","profiles" to emptyList<String>(),"workMode" to "normal")
    private fun root():Path=Files.createDirectories(temporary.resolve("knowledge")).toRealPath().also { Files.writeString(it.resolve("rule.yaml"),rule()) }
    private fun store(root:Path?)=FileAuditStore(temporary.resolve("audit"),root)
    private fun server(root:Path=root())=DashboardServer(DashboardOptions(root,noBrowser=true),store(root),store(null)).start()
    private fun request(server:DashboardServer,path:String,method:String="GET",body:Any?=null,auth:Boolean=true,origin:String?=null,contentType:String?="application/json",extra:Map<String,String> = emptyMap()):HttpResponse<String> {
        val b=HttpRequest.newBuilder(URI.create(server.origin+path))
        if (auth) b.header("Authorization","Bearer ${server.token}")
        if (origin!=null) b.header("Origin",origin)
        if (contentType!=null) b.header("Content-Type",contentType)
        extra.forEach { (k,v) -> b.header(k,v) }
        b.method(method,if (body==null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(if (body is String) body else DashboardJson.mapper.writeValueAsString(body)))
        return client.send(b.build(),HttpResponse.BodyHandlers.ofString())
    }
    private fun json(r:HttpResponse<String>):JsonNode=DashboardJson.mapper.readTree(r.body())
    private fun post(s:DashboardServer,path:String,body:Any)=request(s,"/api/dashboard/$path","POST",body,origin=s.origin)
    private fun patch(path:String="blocked.java")="""
        diff --git a/$path b/$path
        new file mode 100644
        --- /dev/null
        +++ b/$path
        @@ -0,0 +1 @@
        +class Demo {}
    """.trimIndent()+"\n"

    @Test fun `loopback startup uses fragment token and API is authenticated`() {
        server().use { s ->
            assertTrue(s.url.startsWith("http://127.0.0.1:")); assertTrue(s.url.contains("/#token=")); assertFalse(s.url.contains("?token="))
            assertEquals(401,request(s,"/api/dashboard/summary",auth=false).statusCode())
            assertEquals(400,request(s,"/api/dashboard/summary?token=${s.token}").statusCode())
            val response=request(s,"/api/dashboard/summary")
            assertEquals(200,response.statusCode()); assertEquals("no-store",response.headers().firstValue("Cache-Control").orElse(null))
            assertEquals(1,json(response)["apiVersion"].asInt()); assertTrue(json(response)["knowledge"]["valid"].asBoolean())
            assertEquals(0,json(response)["audit"]["total"].asInt())
        }
    }
    @Test fun `host origin fetch-site methods content type and size are enforced`() {
        server().use { s ->
            assertEquals(403,request(s,"/api/dashboard/summary",origin="https://evil.invalid").statusCode())
            assertEquals(403,request(s,"/api/dashboard/summary",extra=mapOf("Sec-Fetch-Site" to "cross-site")).statusCode())
            assertEquals(405,request(s,"/api/dashboard/summary","POST",emptyMap<String,String>(),origin=s.origin).statusCode())
            assertEquals(403,request(s,"/api/dashboard/validate","POST",emptyMap<String,String>()).statusCode())
            assertEquals(400,request(s,"/api/dashboard/validate","POST","{}",origin=s.origin,contentType="text/plain").statusCode())
            assertEquals(400,post(s,"validate","x".repeat(DashboardServer.MAX_BODY+1)).statusCode())
            Socket("127.0.0.1",URI.create(s.origin).port).use { socket ->
                socket.getOutputStream().write("GET /api/dashboard/summary HTTP/1.1\r\nHost: evil.invalid\r\nAuthorization: Bearer ${s.token}\r\nConnection: close\r\n\r\n".toByteArray())
                assertTrue(socket.getInputStream().bufferedReader().readLine().contains("403"))
            }
        }
    }
    @Test fun `strict body and context validation does not guess profile or accept paths`() {
        server().use { s ->
            listOf("{\"context\":{},\"repository\":\"/tmp\"}","{\"context\":{},\"context\":{}}","{} {}").forEach {
                assertEquals(400,post(s,"resolve",it).statusCode())
            }
            assertEquals(422,post(s,"resolve",mapOf("context" to context-"profiles")).statusCode())
            assertEquals(422,post(s,"resolve",mapOf("context" to context+mapOf("profiles" to listOf("command-based","rookiebot")))).statusCode())
            assertEquals(422,post(s,"resolve",mapOf("context" to context+mapOf("season" to "2025-2027"))).statusCode())
            assertEquals(400,post(s,"check",mapOf("context" to context,"diff" to "not a patch")).statusCode())
            assertEquals(400,post(s,"check",mapOf("context" to context,"path" to "/etc/passwd")).statusCode())
        }
    }
    @Test fun `catalog retains semantic fields and resolver calls deterministic engine`() {
        server().use { s ->
            val r=json(request(s,"/api/dashboard/rule?id=shared.demo"))["rule"]
            assertEquals("hard",r["checkMode"].asText()); assertTrue(r["positiveExample"].isNull)
            assertTrue(r.has("approval")); assertEquals("shared",r["authority"].asText()); assertEquals("global",r["policyLevel"].asText())
            val resolved=json(post(s,"resolve",mapOf("context" to context)))
            assertEquals("shared.demo",resolved["resolution"]["activeRules"][0]["id"].asText())
            assertEquals(0,resolved["resolution"]["profiles"].size())
        }
    }
    @Test fun `supplied patch hard block and explicit dev preserve audit findings`() {
        val root=root()
        DashboardServer(DashboardOptions(root),store(root),store(null)).start().use { s ->
            val blocked=json(post(s,"check",mapOf("context" to context,"diff" to patch())))
            assertFalse(blocked["passed"].asBoolean()); assertEquals(1,blocked["violations"].size())
            val id=blocked["invocationId"].asText(); val detail=json(request(s,"/api/dashboard/audit/detail?id=$id"))["record"]
            assertEquals("completed",detail["status"].asText()); assertEquals(1,detail["exitCode"].asInt())
            assertTrue(detail["stages"].any { it["name"].asText()=="standardizer" && it["status"].asText()=="blocked" })
            val dev=json(post(s,"check",mapOf("context" to context+mapOf("workMode" to "dev"),"diff" to patch())))
            assertTrue(dev["passed"].asBoolean()); assertEquals(0,dev["violations"].size()); assertTrue(dev["soft"][0]["note"].asText().contains("downgraded hard check"))
            assertEquals("dev_downgrade",dev["soft"][0]["kind"].asText()); assertEquals("blocked.java",dev["soft"][0]["path"].asText()); assertEquals(1,dev["soft"][0]["line"].asInt())
            val text=Files.readString(store(root).partitionPath.resolve("events.jsonl"))
            assertFalse(text.contains(s.token)); assertFalse(text.contains("class Demo"))
            assertEquals(2L,store(root).summary().diffChecked); assertEquals(1L,store(root).summary().diffBlocked)
        }
    }
    @Test fun `resolve validate and read polling never claim diff coverage`() {
        server().use { s ->
            post(s,"resolve",mapOf("context" to context)); post(s,"validate",emptyMap<String,String>())
            repeat(3) { request(s,"/api/dashboard/summary"); request(s,"/api/dashboard/rules"); request(s,"/api/dashboard/audit") }
            val summary=json(request(s,"/api/dashboard/summary"))["audit"]
            assertEquals(2,summary["total"].asInt()); assertEquals(0,summary["diffChecked"].asInt()); assertEquals(2,summary["diffNotApplicable"].asInt())
        }
    }
    @Test fun `external invalid edits are visible and library does not serve cached valid state`() {
        val root=root()
        server(root).use { s ->
            val first=json(request(s,"/api/dashboard/summary"))["knowledge"]["version"].asText()
            Files.writeString(root.resolve("rule.yaml"),"rules: [unterminated")
            val changed=json(request(s,"/api/dashboard/summary"))["knowledge"]
            assertFalse(changed["valid"].asBoolean()); assertNotEquals(first,changed["version"].asText())
            assertTrue(changed["errors"].size()>0)
            assertEquals(422,post(s,"resolve",mapOf("context" to context)).statusCode())
        }
        server(root).use { assertFalse(json(request(it,"/api/dashboard/summary"))["knowledge"]["valid"].asBoolean()) }
    }
    @Test fun `candidate create preview save and stale save are single file transactions`() {
        val root=root()
        server(root).use { s ->
            val v=json(request(s,"/api/dashboard/summary"))["knowledge"]["version"].asText()
            val p=json(post(s,"preview",mapOf("action" to "create","ruleId" to "shared.new","expectedVersion" to v,"ruleYaml" to rule("shared.new",false))))["preview"]
            assertTrue(p["canApply"].asBoolean()); assertEquals("create",p["action"].asText())
            assertFalse(Files.exists(root.resolve("candidates/shared.new.yaml")))
            assertEquals(200,post(s,"apply",mapOf("previewId" to p["previewId"].asText())).statusCode())
            assertTrue(Files.exists(root.resolve("candidates/shared.new.yaml")))
            assertEquals(409,post(s,"apply",mapOf("previewId" to p["previewId"].asText())).statusCode())
            val detail=json(request(s,"/api/dashboard/rule?id=shared.new"))
            assertEquals("candidate",detail["rule"]["status"].asText())
            val p2=json(post(s,"preview",mapOf("action" to "edit","ruleId" to "shared.new","expectedVersion" to detail["version"].asText(),"ruleYaml" to rule("shared.new",false).replace("Demo title","Updated title"))))["preview"]
            Files.writeString(root.resolve("external.yaml"),rule("shared.external",false))
            assertEquals(409,post(s,"apply",mapOf("previewId" to p2["previewId"].asText())).statusCode())
            assertFalse(Files.readString(root.resolve("candidates/shared.new.yaml")).contains("Updated title"))
        }
    }
    @Test fun `audit scope children unfinished status and unhealthy storage remain explicit`() {
        val root=root(); val scoped=store(root); val unscoped=store(null)
        val id=UUID.randomUUID().toString(); val child=UUID.randomUUID().toString(); val time=Instant.now().toString()
        scoped.append(AuditEvent(id,AuditEventType.STARTED,time,metadata=AuditMetadata("cli","check",root.toString())))
        scoped.append(AuditEvent(child,AuditEventType.STARTED,time,metadata=AuditMetadata("chat","model",root.toString(),parentInvocationId=id)))
        unscoped.append(AuditEvent(UUID.randomUUID().toString(),AuditEventType.STARTED,time,metadata=AuditMetadata("cli","resolve",null)))
        DashboardServer(DashboardOptions(root),scoped,unscoped).start().use { s ->
            val j=json(request(s,"/api/dashboard/summary")); assertEquals(1,j["audit"]["total"].asInt()); assertEquals(1,j["unscopedAudit"]["total"].asInt())
            val detail=json(request(s,"/api/dashboard/audit/detail?id=$id")); assertEquals("started",detail["record"]["status"].asText()); assertTrue(detail["record"]["endedAt"].isNull); assertEquals(1,detail["children"].size())
            assertEquals(400,request(s,"/api/dashboard/audit?scope=all").statusCode()); assertEquals(400,request(s,"/api/dashboard/audit?limit=101").statusCode())
        }
    }
    @Test fun `normal soft notices stay visible without becoming hard blocks`() {
        val root=root()
        val soft=rule("shared.soft").replace("    checks:\n      - kind: path-forbidden\n        pattern: blocked.java\n        note: Block this file.\n","")
        Files.writeString(root.resolve("soft.yaml"),soft)
        server(root).use { s ->
            val result=post(s,"check",mapOf("context" to context,"diff" to patch("allowed.java")))
            assertEquals(200,result.statusCode(),result.body())
            val j=json(result); assertTrue(j["passed"].asBoolean()); assertEquals(1,j["soft"].size()); assertEquals("soft",j["soft"][0]["kind"].asText())
            val r=store(root).find(j["invocationId"].asText())!!
            assertEquals("soft",r.stages.last { it.name=="standardizer" }.category)
            assertEquals(0L,store(root).summary().diffBlocked)
        }
    }
    @Test fun `approval declaration edit reset and rejection use preview before apply`() {
        val root=root(); Files.writeString(root.resolve("candidate.yaml"),rule("shared.candidate",false))
        server(root).use { s ->
            fun version()=json(request(s,"/api/dashboard/summary"))["knowledge"]["version"].asText()
            val denied=post(s,"preview",mapOf("action" to "approve","ruleId" to "shared.candidate","expectedVersion" to version(),"approver" to mapOf("id" to "team-lead","role" to "team_software_lead","team" to "20827")))
            assertEquals(422,denied.statusCode()); assertEquals("approval_denied",json(denied)["error"]["code"].asText())
            val approval=post(s,"preview",mapOf("action" to "approve","ruleId" to "shared.candidate","expectedVersion" to version(),"approver" to mapOf("id" to "overall-lead","role" to "overall_software_lead")))
            assertEquals(200,approval.statusCode(),approval.body()); val p=json(approval)["preview"]
            assertTrue(p["canApply"].asBoolean(),approval.body()); assertEquals(200,post(s,"apply",mapOf("previewId" to p["previewId"].asText())).statusCode())
            val approved=json(request(s,"/api/dashboard/rule?id=shared.candidate")); assertEquals("approved",approved["rule"]["status"].asText())
            val edited=post(s,"preview",mapOf("action" to "edit","ruleId" to "shared.candidate","expectedVersion" to version(),"ruleYaml" to approved["source"]["ruleYaml"].asText().replace("Preserve safety.","Preserve safety with a new instruction.")))
            assertEquals(200,edited.statusCode(),edited.body()); val edit=json(edited)["preview"]
            assertTrue(edit["approvalReset"].asBoolean()); assertEquals(200,post(s,"apply",mapOf("previewId" to edit["previewId"].asText())).statusCode())
            val candidate=json(request(s,"/api/dashboard/rule?id=shared.candidate"))["rule"]; assertEquals("candidate",candidate["status"].asText()); assertTrue(candidate["approval"].isNull)
            val rejected=post(s,"preview",mapOf("action" to "reject","ruleId" to "shared.candidate","expectedVersion" to version(),"approver" to mapOf("id" to "overall-lead","role" to "overall_software_lead")))
            assertEquals(200,rejected.statusCode(),rejected.body()); assertEquals(200,post(s,"apply",mapOf("previewId" to json(rejected)["preview"]["previewId"].asText())).statusCode())
            assertEquals("rejected",json(request(s,"/api/dashboard/rule?id=shared.candidate"))["rule"]["status"].asText())
            assertTrue(store(root).page(limit=100).items.any { it.metadata.command=="rule_approve" && it.stages.any { stage -> stage.name=="write" && stage.status==AuditStageStatus.PASSED } })
        }
    }
    @Test fun `invalid proposed rule cannot be saved and original library stays unchanged`() {
        val root=root()
        server(root).use { s ->
            val version=json(request(s,"/api/dashboard/summary"))["knowledge"]["version"].asText()
            val bad=post(s,"preview",mapOf("action" to "create","ruleId" to "shared.bad","expectedVersion" to version,"ruleYaml" to rule("shared.bad",false).replace("title: Demo title","title: \"\"")))
            assertEquals(200,bad.statusCode(),bad.body()); val p=json(bad)["preview"]
            assertFalse(p["canApply"].asBoolean()); assertTrue(p["violations"].any { it["field"].asText()=="title" })
            assertEquals(422,post(s,"apply",mapOf("previewId" to p["previewId"].asText())).statusCode())
            assertEquals(version,json(request(s,"/api/dashboard/summary"))["knowledge"]["version"].asText())
            assertFalse(Files.exists(root.resolve("candidates/shared.bad.yaml")))
        }
    }
    @Test fun `audit storage failure is exposed without changing detection result`() {
        val root=root(); val diagnostics=mutableListOf<String>()
        val baseFile=Files.writeString(temporary.resolve("audit-is-file"),"occupied")
        val broken=FileAuditStore(baseFile,root) { diagnostics+=it }
        DashboardServer(DashboardOptions(root),broken,store(null)) { diagnostics+=it }.start().use { s ->
            val response=post(s,"check",mapOf("context" to context,"diff" to ""))
            assertEquals(200,response.statusCode(),response.body()); assertTrue(json(response)["passed"].asBoolean())
            val health=json(request(s,"/api/dashboard/summary"))["audit"]["health"]
            assertFalse(health["ok"].asBoolean()); assertTrue(health["warnings"].size()>0)
            assertTrue(diagnostics.isNotEmpty()); assertTrue(diagnostics.none { it.contains(s.token) || it.contains("occupied") })
        }
    }
    @Test fun `shutdown is authenticated same origin POST`() {
        server().use { s ->
            assertEquals(405,request(s,"/api/dashboard/shutdown").statusCode())
            assertEquals(200,post(s,"shutdown",emptyMap<String,String>()).statusCode())
        }
    }
    private fun rule(id:String="shared.demo",approved:Boolean=true):String {
        val base="""
            schemaVersion: 4
            rules:
              - id: $id
                topic: ${id.replace('.','-')}
                title: Demo title
                instruction: Preserve safety.
                rationale: Exercise deterministic rules.
                status: ${if (approved) "approved" else "candidate"}
                authority: shared
                policyLevel: global
                applicability:
                  teams: []
                  seasons: []
                  profiles: []
                evidence:
                  - type: git
                    repository: owner/repo
                    commit: abcdef1
                    file: README.md
                    line: 1
                checks:
                  - kind: path-forbidden
                    pattern: blocked.java
                    note: Block this file.
        """.trimIndent()+"\n"
        return base+if (approved) "    approval:\n      approver: test-lead\n      role: overall_software_lead\n      approvedAt: 2026-10-19T00:00:00Z\n" else ""
    }
}
