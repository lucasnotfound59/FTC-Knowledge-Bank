package org.ftckb.cli

import com.fasterxml.jackson.databind.JsonNode
import org.ftckb.audit.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

class DashboardReviewTest {
    @TempDir lateinit var temporary:Path
    private val client=HttpClient.newHttpClient()
    private val context=mapOf("team" to "20827","season" to "2025-2026","profiles" to emptyList<String>(),"workMode" to "normal")
    private val auditContext=AuditContext("20827","2025-2026",emptyList())
    private fun root()=Files.createDirectories(temporary.resolve("knowledge")).toRealPath().also { Files.writeString(it.resolve("rules.yaml"),rule()) }
    private fun audit(root:Path)=FileAuditStore(temporary.resolve("audit"),root)
    private fun reviews(root:Path)=FileReviewStore(temporary.resolve("audit"),root)
    private fun server(root:Path)=DashboardServer(DashboardOptions(root),audit(root),FileAuditStore(temporary.resolve("audit"),null),reviews(root)).start()
    private fun request(s:DashboardServer,path:String,body:Any?=null,auth:Boolean=true,origin:String=s.origin):HttpResponse<String> {
        val builder=HttpRequest.newBuilder(URI.create(s.origin+"/api/dashboard/"+path))
        if (auth) builder.header("Authorization","Bearer ${s.token}")
        if (body!=null) builder.header("Origin",origin).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(if(body is String) body else DashboardJson.mapper.writeValueAsString(body)))
        return client.send(builder.build(),HttpResponse.BodyHandlers.ofString())
    }
    private fun json(r:HttpResponse<String>):JsonNode=DashboardJson.mapper.readTree(r.body())
    private fun check(s:DashboardServer,diff:String="",extra:Map<String,Any?> = emptyMap()):String {
        val result=request(s,"check",mapOf("context" to context,"diff" to diff)+extra)
        assertEquals(200,result.statusCode(),result.body()); return json(result)["invocationId"].asText()
    }
    private fun item(s:DashboardServer,id:String)=json(request(s,"audit/detail?id=$id"))["reviewItems"].first()
    private fun input(item:JsonNode,revision:Long=0,decision:String="verified",reviewId:String=UUID.randomUUID().toString())=mapOf(
        "reviewId" to reviewId,"itemId" to item["itemId"].asText(),"invocationId" to item["invocationId"].asText(),
        "sourceDigest" to item["sourceDigest"].asText(),"expectedRevision" to revision,"decision" to decision,
        "reason" to "已核对本次历史快照","evidenceUrls" to listOf("https://example.org/evidence"),"reviewer" to "test reviewer")
    private fun detail(s:DashboardServer,item:JsonNode)=json(request(s,"reviews/detail?itemId=${item["itemId"].asText()}"))

    @Test fun `review statements preserve original events counters and history across restart`() {
        val root=root(); lateinit var savedItem:JsonNode
        server(root).use { s ->
            savedItem=item(s,check(s)); val path=audit(root).partitionPath.resolve("events.jsonl")
            val original=Files.readAllBytes(path); val summary=audit(root).summary()
            val body=input(savedItem); val first=request(s,"reviews/record",body)
            assertEquals(200,first.statusCode(),first.body()); assertEquals(1,json(first)["record"]["revision"].asInt())
            val replay=request(s,"reviews/record",body); assertTrue(json(replay)["replayed"].asBoolean())
            assertEquals(409,request(s,"reviews/record",input(savedItem)).statusCode())
            assertEquals(200,request(s,"reviews/record",input(savedItem,1,"needs_changes")).statusCode())
            val current=detail(s,savedItem); assertEquals(2,current["history"].size()); assertEquals("needs_changes",current["item"]["latestReview"]["decision"].asText())
            assertArrayEquals(original,Files.readAllBytes(path)); assertEquals(summary,audit(root).summary())
            repeat(3) { request(s,"reviews?filter=all"); detail(s,savedItem) }
            assertArrayEquals(original,Files.readAllBytes(path))
        }
        server(root).use { s -> assertEquals(2,detail(s,savedItem)["history"].size()) }
    }
    @Test fun `manual predecessor compares only same context and creates new pending items`() {
        val root=root()
        server(root).use { s ->
            val first=check(s); val old=item(s,first); request(s,"reviews/record",input(old))
            val second=check(s,patch("class Changed {}"),mapOf("predecessorInvocationId" to first)); val fresh=item(s,second)
            assertEquals("stale",detail(s,old)["item"]["versionState"].asText())
            assertTrue(fresh["latestReview"].isNull); assertNotEquals(old["itemId"].asText(),fresh["itemId"].asText())
            assertEquals(old["codeVersion"]["subjectId"],fresh["codeVersion"]["subjectId"])
            val independent=item(s,check(s)); assertNotEquals(independent["codeVersion"]["subjectId"],old["codeVersion"]["subjectId"])
            assertEquals(422,request(s,"check",mapOf("context" to context+mapOf("workMode" to "test"),"diff" to "","predecessorInvocationId" to first)).statusCode())
            assertEquals(404,request(s,"check",mapOf("context" to context,"diff" to "","predecessorInvocationId" to UUID.randomUUID().toString())).statusCode())
        }
    }
    @Test fun `legacy unknown declarations leave pending while source remains a historical snapshot`() {
        val root=root(); val id=seed(root,null)
        server(root).use { s ->
            val old=item(s,id); assertEquals("unknown",old["versionState"].asText()); assertTrue(old["canReview"].asBoolean())
            assertEquals(200,request(s,"reviews/record",input(old)).statusCode())
            assertEquals(0,json(request(s,"reviews?filter=pending"))["items"].size())
            assertEquals(1,json(request(s,"reviews?filter=recorded"))["items"].size())
        }
    }
    @Test fun `incomplete failed sources are read only and completed blocked checks retain original hard state`() {
        val root=root(); val started=seed(root,null,finish=false); val failed=seed(root,null,status=AuditRunStatus.FAILED)
        val blocked=seed(root,null,hits=listOf(AuditHit("hard","shared.hard","path-forbidden","unsafe.java",1,"blocked"),soft()),stageStatus=AuditStageStatus.BLOCKED,exit=1)
        server(root).use { s ->
            listOf(started,failed).forEach { id -> val found=item(s,id); assertFalse(found["canReview"].asBoolean()); assertEquals(409,request(s,"reviews/record",input(found)).statusCode()) }
            val found=item(s,blocked); assertTrue(found["canReview"].asBoolean()); assertEquals("blocked",found["originalStatus"].asText())
            assertEquals(200,request(s,"reviews/record",input(found)).statusCode()); assertEquals(1,audit(root).find(blocked)!!.exitCode)
            val dev=json(request(s,"check",mapOf("context" to context+mapOf("workMode" to "dev"),"diff" to patch("bad"))))["invocationId"].asText()
            val hits=json(request(s,"audit/detail?id=$dev"))["reviewItems"]
            assertTrue(hits.any { it["hit"]["kind"].asText()=="dev_downgrade" })
        }
    }
    @Test fun `strict authenticated review route rejects hostile and oversized inputs without creating audit events`() {
        val root=root()
        server(root).use { s ->
            val found=item(s,check(s)); val before=Files.readAllBytes(audit(root).partitionPath.resolve("events.jsonl")); val body=input(found)
            assertEquals(401,request(s,"reviews/record",body,auth=false).statusCode())
            assertEquals(403,request(s,"reviews/record",body,origin="https://evil.invalid").statusCode())
            assertEquals(400,request(s,"reviews/record",body+mapOf("path" to "/etc/passwd")).statusCode())
            assertEquals(400,request(s,"reviews/record","{\"itemId\":\"a\",\"itemId\":\"b\"}").statusCode())
            assertEquals(400,request(s,"reviews/record",body+mapOf("reason" to " ")).statusCode())
            assertEquals(422,request(s,"reviews/record",body+mapOf("evidenceUrls" to listOf("https://x.invalid/?api_key=hidden"))).statusCode())
            assertEquals(400,request(s,"reviews/record","x".repeat(65537)).statusCode())
            assertEquals(409,request(s,"reviews/record",body+mapOf("sourceDigest" to "b".repeat(64))).statusCode())
            assertEquals(200,request(s,"reviews/record",body+mapOf("reason" to "token=private-value")).statusCode())
            val saved=detail(s,found)["history"][0]; assertTrue(saved["sanitized"].asBoolean()); assertFalse(saved["reason"].asText().contains("private-value"))
            assertArrayEquals(before,Files.readAllBytes(audit(root).partitionPath.resolve("events.jsonl")))
        }
    }
    @Test fun `review cursor preserves hundreds of items in a single source and rejects source replacement`() {
        val root=root(); seed(root,null,hits=(0 until 231).map { soft("shared.soft-$it") })
        server(root).use { s ->
            val ids=mutableSetOf<String>(); var cursor:String?=null
            do {
                val response=request(s,"reviews?filter=all&limit=17"+(cursor?.let { "&cursor=$it" } ?: "")); assertEquals(200,response.statusCode(),response.body())
                val page=json(response); page["items"].forEach { assertTrue(ids.add(it["itemId"].asText())) }
                cursor=page["nextCursor"].takeUnless { it.isNull }?.asText()
            } while(cursor!=null)
            assertEquals(231,ids.size)
            val first=json(request(s,"reviews?filter=all&limit=1")); val token=first["nextCursor"].asText()
            Files.writeString(audit(root).partitionPath.resolve("events.jsonl"),"")
            assertEquals(409,request(s,"reviews?filter=all&cursor=$token").statusCode())
        }
    }
    @Test fun `fresh detail never calls old source current while version index has more than one hundred records`() {
        val root=root(); val version=AuditCodeVersion("worktree","a".repeat(64),"b".repeat(64))
        val old=seed(root,version); val source=audit(root).find(old)!!; val oldId=ReviewIdentity.itemId(source,0,source.stages[0].hits[0])
        val target=ReviewTarget(oldId,old,ReviewIdentity.sourceDigest(source,0,source.stages[0].hits[0]))
        reviews(root).record(ReviewInput(UUID.randomUUID().toString(),target,0,"verified","Historical review",emptyList(),"reviewer")) { it==target }
        repeat(103) { seed(root,null) }
        seed(root,version.copy(inputDigest="c".repeat(64)))
        server(root).use { s ->
            // The detail path is the first request. No prior list browsing may supply comparison state.
            val first=request(s,"reviews/detail?itemId=$oldId")
            assertEquals(200,first.statusCode(),first.body()); assertEquals("unknown",json(first)["item"]["versionState"].asText())
            val second=request(s,"reviews/detail?itemId=$oldId"); assertEquals(200,second.statusCode(),second.body())
            assertEquals("stale",json(second)["item"]["versionState"].asText())
        }
    }
    @Test fun `orphaned statements survive missing source and cannot be rewritten`() {
        val root=root(); lateinit var found:JsonNode
        server(root).use { s ->
            found=item(s,check(s)); assertEquals(200,request(s,"reviews/record",input(found)).statusCode())
            Files.delete(audit(root).partitionPath.resolve("events.jsonl"))
            val orphan=detail(s,found); assertTrue(orphan["item"]["orphaned"].asBoolean()); assertEquals(1,orphan["history"].size())
            assertFalse(orphan["item"]["canReview"].asBoolean()); assertEquals(409,request(s,"reviews/record",input(found,1)).statusCode())
        }
    }
    @Test fun `HTTP retry reaches durable replay after a storage confirmation failure`() {
        val root=root(); val delegate=reviews(root)
        val uncertain=object:ReviewStore {
            var failed=false
            override fun history(itemId:String)=delegate.history(itemId).let { if(failed) it.copy(health=health()) else it }
            override fun health()=if(failed) AuditHealth(false,listOf("A review storage write failed; saved state has not been confirmed durable.")) else delegate.health()
            override fun record(input:ReviewInput,validateSource:(ReviewTarget)->Boolean):ReviewWriteResult {
                val result=delegate.record(input,validateSource)
                if (!failed) { failed=true; throw ReviewProblem("storage_unavailable") }
                failed=false; return result
            }
        }
        DashboardServer(DashboardOptions(root),audit(root),FileAuditStore(temporary.resolve("audit"),null),uncertain).start().use { s ->
            val found=item(s,check(s)); val body=input(found)
            assertEquals(503,request(s,"reviews/record",body).statusCode())
            val pending=detail(s,found)["item"]; assertFalse(pending["canReview"].asBoolean()); assertTrue(pending["canRetry"].asBoolean())
            val retry=request(s,"reviews/record",body); assertEquals(200,retry.statusCode(),retry.body()); assertTrue(json(retry)["replayed"].asBoolean())
            assertEquals(1,detail(s,found)["history"].size()); assertEquals(1,Files.readAllLines(delegate.partitionPath.resolve("reviews.jsonl")).size)
        }
    }
    @Test fun `changed source digest stays read only after a fresh server replays valid edited source`() {
        val root=root(); lateinit var found:JsonNode
        server(root).use { s -> found=item(s,check(s)); assertEquals(200,request(s,"reviews/record",input(found)).statusCode()) }
        val path=audit(root).partitionPath.resolve("events.jsonl")
        val changed=Files.readAllLines(path).map { line ->
            val event=DashboardJson.mapper.readTree(line)
            if (event["type"].asText()=="snapshot") (event as com.fasterxml.jackson.databind.node.ObjectNode).put("knowledgeVersion","e".repeat(64))
            DashboardJson.mapper.writeValueAsString(event)
        }
        Files.writeString(path,changed.joinToString("\n")+"\n")
        server(root).use { s ->
            val response=detail(s,found); assertEquals(1,response["history"].size()); assertFalse(response["item"]["canReview"].asBoolean())
            assertEquals("unknown",response["item"]["versionState"].asText()); assertEquals("verified",response["item"]["latestReview"]["decision"].asText())
            assertEquals(1,json(request(s,"reviews?filter=pending"))["items"].size())
            assertEquals(409,request(s,"reviews/record",input(response["item"],1)).statusCode())
        }
    }
    private fun soft(id:String="shared.soft")=AuditHit("soft",id,null,null,null,"Review intent.")
    private fun seed(root:Path,version:AuditCodeVersion?,hits:List<AuditHit> = listOf(soft()),finish:Boolean=true,status:AuditRunStatus=AuditRunStatus.COMPLETED,stageStatus:AuditStageStatus=AuditStageStatus.PASSED,exit:Int=0):String {
        val store=audit(root); val id=UUID.randomUUID().toString(); val time=Instant.now().toString()
        assertTrue(store.append(AuditEvent(id,AuditEventType.STARTED,time,metadata=AuditMetadata("cli","check",root.toString(),auditContext))))
        assertTrue(store.append(AuditEvent(id,AuditEventType.SNAPSHOT,time,knowledgeVersion="d".repeat(64))))
        assertTrue(store.append(AuditEvent(id,AuditEventType.STAGE,time,stage=AuditStage("standardizer",stageStatus,"soft",hits=hits,codeVersion=version))))
        if(finish) assertTrue(store.append(AuditEvent(id,AuditEventType.FINISHED,time,status=status,exitCode=exit)))
        return id
    }
    private fun patch(text:String)="diff --git a/blocked.java b/blocked.java\nnew file mode 100644\n--- /dev/null\n+++ b/blocked.java\n@@ -0,0 +1 @@\n+$text\n"
    private fun rule()="""
        schemaVersion: 4
        rules:
          - id: shared.soft
            topic: soft
            title: Review intent
            instruction: Review intent.
            rationale: Verify independent history.
            status: approved
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
                file: Example.java
                line: 1
            approval:
              approver: tester
              role: overall_software_lead
              approvedAt: 2026-01-01T00:00:00Z
          - id: shared.hard
            topic: hard
            title: Block path
            instruction: Block this path.
            rationale: Preserve original block.
            status: approved
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
                file: Example.java
                line: 1
            approval:
              approver: tester
              role: overall_software_lead
              approvedAt: 2026-01-01T00:00:00Z
            checks:
              - kind: path-forbidden
                pattern: blocked.java
                note: Block this path.
    """.trimIndent()+"\n"
}
