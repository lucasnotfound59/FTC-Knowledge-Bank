package org.ftckb.cli

import com.fasterxml.jackson.databind.JsonNode
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.ftckb.audit.*
import org.ftckb.domain.*
import org.ftckb.knowledge.*
import org.ftckb.standardizer.Standardizer
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/** A single-library loopback service. Test callers can inject both audit stores. */
class DashboardServer(
    private val options:DashboardOptions,
    private val auditStore:AuditStore=dashboardAuditStore(options.knowledge),
    private val unscopedStore:AuditStore=dashboardAuditStore(null),
    private val diagnostics:(String)->Unit={ System.err.println(it) }
):AutoCloseable {
    private val workspace=KnowledgeWorkspace(options.knowledge)
    init {
        require(options.port in 0..65535)
        require(Files.isDirectory(options.knowledge) && Files.isReadable(options.knowledge)) { "Knowledge directory is unavailable" }
        workspace.snapshot()
    }
    private val closed=java.util.concurrent.atomic.AtomicBoolean(false)
    private val server=HttpServer.create(InetSocketAddress("127.0.0.1",options.port),0)
    private val executor=Executors.newFixedThreadPool(4)
    private val shutdown=CountDownLatch(1)
    private val mutationLock=Any()
    private data class CachedPreview(val preview:RuleMutationPreview,val context:RuleContext?)
    private val previews=object:LinkedHashMap<String,CachedPreview>(16,0.75f,true) {
        override fun removeEldestEntry(eldest:MutableMap.MutableEntry<String,CachedPreview>?):Boolean=size>128
    }
    val token:String=Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
    val origin:String get()="http://127.0.0.1:${server.address.port}"
    val url:String get()="$origin/#token=$token"

    init {
        server.executor=executor
        server.createContext("/",::handle)
    }
    fun start():DashboardServer { server.start(); return this }
    fun awaitShutdown()=shutdown.await()
    override fun close() { if (closed.compareAndSet(false,true)) { server.stop(0); executor.shutdownNow(); shutdown.countDown() } }

    private fun handle(x:HttpExchange) {
        try {
            guardHostOrigin(x)
            val path=x.requestURI.rawPath
            if (path.startsWith("/api/dashboard/")) {
                if (!authorized(x)) throw DashboardProblem(401,"unauthorized","A valid dashboard token is required")
                val expected=routes[path] ?: throw DashboardProblem(404,"not_found","Unknown dashboard route")
                if (x.requestMethod!=expected) {
                    x.responseHeaders.set("Allow",expected)
                    throw DashboardProblem(405,"method_not_allowed","Unsupported method")
                }
                val query=query(x)
                if (expected=="GET") {
                    reply(x,200,get(path,query))
                } else {
                    if (x.requestHeaders["Origin"]!=listOf(origin)) throw DashboardProblem(403,"forbidden_origin","POST requires this dashboard Origin")
                    val contentType=x.requestHeaders["Content-Type"]
                    var body:JsonNode?=null
                    var bodyProblem:DashboardProblem?=when {
                        query.isNotEmpty() -> DashboardProblem(400,"malformed_request","POST routes do not accept query parameters")
                        contentType?.size!=1 || !contentType.single().substringBefore(';').trim().equals("application/json",true) -> DashboardProblem(400,"malformed_request","POST requires application/json")
                        else -> null
                    }
                    if (bodyProblem==null) try { body=readBody(x) } catch (p:DashboardProblem) { bodyProblem=p }
                    val command=when(path.substringAfterLast('/')) {
                        "preview" -> mutationCommand(body?.get("action")?.takeIf { it.isTextual }?.asText())
                        "apply" -> synchronized(mutationLock) { previews[body?.get("previewId")?.asText()]?.preview?.action?.name?.lowercase(java.util.Locale.ROOT)?.let(::mutationCommand) } ?: "rule_edit"
                        else -> path.substringAfterLast('/')
                    }
                    val payload=if (path.endsWith("/shutdown")) {
                        bodyProblem?.let { throw it }; DashboardJson.fields(body!!,emptySet()); emptyMap()
                    } else audited(command,body) { trace ->
                        bodyProblem?.let { throw it }
                        post(path,body!!,trace)
                    }
                    reply(x,200,payload)
                    if (path.endsWith("/shutdown")) {
                        shutdown.countDown()
                        Thread { Thread.sleep(50); close() }.apply { isDaemon=true; start() }
                    }
                }
            } else serveStatic(x,path)
        } catch (p:DashboardProblem) {
            reply(x,p.status,mapOf("error" to mapOf("code" to p.code,"message" to p.message)),false)
        } catch (_:Exception) {
            diagnostics("dashboard: request failed (internal_error)")
            reply(x,500,mapOf("error" to mapOf("code" to "internal_error","message" to "Dashboard operation failed")),false)
        } finally { x.close() }
    }

    private fun guardHostOrigin(x:HttpExchange) {
        if (x.requestHeaders["Host"]!=listOf("127.0.0.1:${server.address.port}")) throw DashboardProblem(403,"forbidden_host","Host must match this dashboard")
        val origins=x.requestHeaders["Origin"]
        if (origins!=null && origins!=listOf(origin)) throw DashboardProblem(403,"forbidden_origin","Foreign Origin is forbidden")
        if (x.requestHeaders["Sec-Fetch-Site"]?.any { it.equals("cross-site",true) }==true)
            throw DashboardProblem(403,"forbidden_origin","Cross-site requests are forbidden")
    }
    private fun authorized(x:HttpExchange):Boolean {
        val values=x.requestHeaders["Authorization"] ?: return false
        return values.size==1 && MessageDigest.isEqual(values.single().toByteArray(StandardCharsets.UTF_8),("Bearer $token").toByteArray(StandardCharsets.UTF_8))
    }
    private fun readBody(x:HttpExchange):JsonNode {
        val length=x.requestHeaders.getFirst("Content-Length")
        if (length!=null && (length.toLongOrNull()==null || length.toLong()>MAX_BODY)) throw DashboardProblem(400,"body_too_large","Body exceeds 1 MiB")
        val bytes=x.requestBody.readNBytes(MAX_BODY+1)
        if (bytes.size>MAX_BODY) throw DashboardProblem(400,"body_too_large","Body exceeds 1 MiB")
        return try {
            DashboardJson.mapper.readTree(bytes)?.also { if (!it.isObject) throw IllegalArgumentException() }
                ?: throw IllegalArgumentException()
        } catch (_:Exception) { throw DashboardProblem(400,"malformed_request","Body must be one JSON object") }
    }
    private fun query(x:HttpExchange):Map<String,String> {
        val result=linkedMapOf<String,String>()
        x.requestURI.rawQuery?.split('&')?.forEach {
            if (it.isEmpty()) throw DashboardProblem(400,"malformed_request","Invalid query")
            val pair=it.split('=',limit=2)
            val key=try { URLDecoder.decode(pair[0],StandardCharsets.UTF_8) } catch (_:Exception) { throw DashboardProblem(400,"malformed_request","Invalid query encoding") }
            val value=try { URLDecoder.decode(pair.getOrElse(1) { "" },StandardCharsets.UTF_8) } catch (_:Exception) { throw DashboardProblem(400,"malformed_request","Invalid query encoding") }
            if (result.put(key,value)!=null) throw DashboardProblem(400,"malformed_request","Duplicate query parameter")
        }
        return result
    }
    private fun queryFields(q:Map<String,String>,allowed:Set<String>) {
        if (q.keys.any { it !in allowed }) throw DashboardProblem(400,"malformed_request","Unsupported query parameter")
    }
    private fun selectedStore(q:Map<String,String>):AuditStore=when(q["scope"] ?: "knowledge") {
        "knowledge" -> auditStore; "unscoped" -> unscopedStore
        else -> throw DashboardProblem(400,"malformed_request","Unknown audit scope")
    }
    private fun get(path:String,q:Map<String,String>):Map<String,Any?> =when(path) {
        "/api/dashboard/summary" -> {
            queryFields(q,emptySet()); val s=workspace.snapshot()
            mapOf("knowledge" to mapOf("root" to s.root.toString(),"version" to s.version,"valid" to s.valid,
                "ruleCount" to s.rules.size,"approved" to s.rules.count { it.status==RuleStatus.APPROVED },"candidate" to s.rules.count { it.status==RuleStatus.CANDIDATE },
                "errors" to (s.violations.map { mapOf("ruleId" to it.ruleId,"field" to it.field,"message" to it.message) }+s.loadErrors.map { mapOf("path" to it.path,"message" to it.message) })),
                "audit" to DashboardJson.summary(auditStore.summary()),"unscopedAudit" to DashboardJson.summary(unscopedStore.summary()),"serverTime" to Instant.now().toString())
        }
        "/api/dashboard/rules" -> {
            queryFields(q,emptySet()); val s=workspace.snapshot()
            mapOf("version" to s.version,"rules" to s.rules.sortedBy { it.id }.map(DashboardJson::rule))
        }
        "/api/dashboard/rule" -> {
            queryFields(q,setOf("id")); val id=q["id"] ?: throw DashboardProblem(400,"malformed_request","Missing id")
            val s=workspace.snapshot(); val r=s.rules.singleOrNull { it.id==id } ?: throw DashboardProblem(404,"not_found","Rule is unavailable or ambiguous")
            val source=s.sources[id] ?: throw DashboardProblem(422,"invalid_rule","Rule source is ambiguous or unavailable")
            mapOf("version" to s.version,"rule" to DashboardJson.rule(r),"source" to source)
        }
        "/api/dashboard/audit" -> {
            queryFields(q,setOf("cursor","limit","scope")); val limit=q["limit"]?.let { it.toIntOrNull() ?: throw DashboardProblem(400,"malformed_request","Invalid limit") } ?: 50
            if (limit !in 1..100) throw DashboardProblem(400,"malformed_request","limit must be 1–100")
            val p=try { selectedStore(q).page(q["cursor"],limit) } catch (_:IllegalArgumentException) { throw DashboardProblem(400,"malformed_request","Invalid cursor") }
            mapOf("items" to p.items.map(DashboardJson::record),"nextCursor" to p.nextCursor,"revision" to p.revision,"health" to p.health)
        }
        "/api/dashboard/audit/detail" -> {
            queryFields(q,setOf("id","scope")); val id=q["id"] ?: throw DashboardProblem(400,"malformed_request","Missing id")
            val store=selectedStore(q); val r=store.find(id) ?: throw DashboardProblem(404,"not_found","Audit record not found")
            mapOf("record" to DashboardJson.record(r),"children" to store.children(id).map(DashboardJson::record))
        }
        else -> throw DashboardProblem(404,"not_found","Unknown route")
    }

    private fun post(path:String,b:JsonNode,t:Trace):Map<String,Any?> =when(path) {
        "/api/dashboard/resolve" -> {
            DashboardJson.fields(b,setOf("context")); val context=DashboardJson.context(b.path("context")); t.parameters()
            val s=checkedSnapshot(t); val r=resolve(s,context,t)
            t.stage("standardizer",AuditStageStatus.NOT_APPLICABLE)
            mapOf("version" to s.version,"resolution" to DashboardJson.resolution(r))
        }
        "/api/dashboard/validate" -> {
            DashboardJson.fields(b,emptySet()); t.parameters(); val s=captured(t)
            t.current="knowledge_validate"
            t.stage("knowledge_validate",if (s.valid) AuditStageStatus.PASSED else AuditStageStatus.BLOCKED,if (s.valid) null else "invalid_knowledge")
            t.stage("standardizer",AuditStageStatus.NOT_APPLICABLE)
            t.exit=if (s.valid) 0 else 2
            if (!s.valid) t.status=AuditRunStatus.FAILED
            mapOf("version" to s.version,"valid" to s.valid,"violations" to s.violations,"loadErrors" to s.loadErrors,"invocationId" to t.id)
        }
        "/api/dashboard/check" -> {
            DashboardJson.fields(b,setOf("context","diff")); val context=DashboardJson.context(b.path("context"))
            val diff=b.get("diff")?.takeIf { it.isTextual }?.asText() ?: throw DashboardProblem(400,"malformed_request","diff string is required")
            t.parameters(); val s=checkedSnapshot(t); val r=resolve(s,context,t)
            t.current="diff_read"
            val changes=try { Standardizer.parsePatch(diff) } catch (_:Exception) { throw DashboardProblem(400,"invalid_diff","Malformed supplied patch") }
            t.stage("diff_read",AuditStageStatus.PASSED); t.current="standardizer"
            val evaluated=Standardizer.evaluate(r.activeRules,changes)
            val dev=context.workMode==WorkMode.DEV
            val violations=if (dev) emptyList() else evaluated.violations
            val soft=mutableListOf<Map<String,Any?>>()
            soft+=evaluated.soft.map { mapOf("kind" to "soft","ruleId" to it.first,"note" to it.second) }
            if (dev) evaluated.violations.forEach { finding ->
                soft+=mapOf("kind" to "dev_downgrade","ruleId" to finding.ruleId,"note" to Standardizer.downgradeToSoft(listOf(finding)).single().second,
                    "check" to finding.check,"path" to finding.path,"line" to finding.line,"detail" to finding.detail)
            }
            soft.sortWith(compareBy({ it["ruleId"].toString() },{ it["note"].toString() }))
            val hits=evaluated.violations.map { AuditHit(if (dev) "dev_downgrade" else "hard",it.ruleId,it.check,it.path,it.line,it.detail) }+
                evaluated.soft.map { AuditHit("soft",it.first,null,null,null,it.second) }
            t.stage("standardizer",if (violations.isEmpty()) AuditStageStatus.PASSED else AuditStageStatus.BLOCKED,
                when { violations.isNotEmpty() -> "hard"; dev && evaluated.violations.isNotEmpty() -> "dev_downgrade"; soft.isNotEmpty() -> "soft"; else -> null },hits)
            t.exit=if (violations.isEmpty()) 0 else 1
            mapOf("version" to s.version,"passed" to violations.isEmpty(),"violations" to violations,"soft" to soft,"invocationId" to t.id)
        }
        "/api/dashboard/preview" -> synchronized(mutationLock) {
            DashboardJson.fields(b,setOf("action","ruleId","expectedVersion","ruleYaml","approver","context"))
            val action=when(DashboardJson.text(b,"action")) { "create" -> RuleMutationAction.CREATE; "edit" -> RuleMutationAction.EDIT; "approve" -> RuleMutationAction.APPROVE; "reject" -> RuleMutationAction.REJECT; else -> throw DashboardProblem(400,"malformed_request","Unknown action") }
            val id=DashboardJson.text(b,"ruleId"); val version=DashboardJson.text(b,"expectedVersion")
            val yaml=if (b.has("ruleYaml")) b.get("ruleYaml").takeIf { it.isTextual }?.asText() ?: throw DashboardProblem(400,"malformed_request","ruleYaml must be a string") else null
            val approver=if (b.has("approver")) parseApprover(b.get("approver")) else null
            val context=if (b.has("context")) DashboardJson.context(b.get("context")) else null
            t.parameters(); t.current="baseline_guard"
            val p=workspace.acquireSnapshotLease(version).use {
                captured(t); t.current="knowledge_validate"
                workspace.preview(RuleMutationRequest(action,id,version,yaml,approver,context))
            }
            t.stage("knowledge_validate",if (p.violations.isEmpty() && p.loadErrors.isEmpty()) AuditStageStatus.PASSED else AuditStageStatus.BLOCKED,if (p.canApply) null else "invalid_knowledge")
            p.resolution?.let { t.stage("resolve",if (it.conflicts.isEmpty()) AuditStageStatus.PASSED else AuditStageStatus.BLOCKED,if (it.conflicts.isEmpty()) null else "conflict") }
            t.stage("standardizer",AuditStageStatus.NOT_APPLICABLE)
            previews[p.previewId]=CachedPreview(p,context)
            if (!p.canApply) { t.exit=2; t.status=AuditRunStatus.FAILED }
            mapOf("preview" to DashboardJson.preview(p))
        }
        "/api/dashboard/apply" -> synchronized(mutationLock) {
            DashboardJson.fields(b,setOf("previewId")); val previewId=DashboardJson.text(b,"previewId"); t.parameters()
            captured(t); t.current="baseline_guard"
            // KnowledgeWorkspace performs the authoritative full-version recheck under its file lock.
            val preview=previews[previewId]?.preview
            val r=workspace.apply(previewId)
            // The saved summary comes from the exact preview bytes, never a racy post-save reload.
            t.snapshot(r.version,preview?.let { runCatching { RuleYamlCodec.decode(it.afterYaml) }.getOrNull() } ?: emptyList())
            t.stage("baseline_guard",AuditStageStatus.PASSED); t.stage("knowledge_validate",AuditStageStatus.PASSED)
            t.stage("write",AuditStageStatus.PASSED,hits=listOf(AuditHit("mutation",r.ruleId,null,null,null,"Applied ${r.action.name.lowercase(java.util.Locale.ROOT)}"))); t.stage("standardizer",AuditStageStatus.NOT_APPLICABLE)
            previews.remove(previewId)
            mapOf("ruleId" to r.ruleId,"version" to r.version,"action" to r.action.name.lowercase(java.util.Locale.ROOT))
        }
        else -> throw DashboardProblem(404,"not_found","Unknown route")
    }

    private fun parseApprover(n:JsonNode):Approver {
        DashboardJson.fields(n,setOf("id","role","team"))
        val role=when(DashboardJson.text(n,"role")) { "overall_software_lead" -> ApproverRole.OVERALL_SOFTWARE_LEAD; "team_software_lead" -> ApproverRole.TEAM_SOFTWARE_LEAD; else -> throw DashboardProblem(422,"approval_denied","Unknown approver role") }
        val team=if (n.has("team")) DashboardJson.text(n,"team") else null
        if (team!=null && !RuleIdentity.isCanonicalTeam(team)) throw DashboardProblem(422,"approval_denied","Invalid approver team")
        return Approver(DashboardJson.text(n,"id"),role,team)
    }
    private fun captured(t:Trace):KnowledgeSnapshot {
        t.current="knowledge_load"; val s=workspace.snapshot(); t.snapshot(s.version,s.rules)
        t.stage("knowledge_load",if (s.loadErrors.isEmpty()) AuditStageStatus.PASSED else AuditStageStatus.BLOCKED,if (s.loadErrors.isEmpty()) null else "load_error")
        return s
    }
    private fun checkedSnapshot(t:Trace):KnowledgeSnapshot {
        val s=captured(t)
        t.current="knowledge_validate"
        if (!s.valid) throw DashboardProblem(422,"invalid_knowledge","Knowledge has load or validation errors; inspect the summary")
        t.stage("knowledge_validate",AuditStageStatus.PASSED); return s
    }
    private fun resolve(s:KnowledgeSnapshot,c:RuleContext,t:Trace):ResolutionResult {
        t.current="resolve"; val r=RuleResolver.resolve(s.rules,c)
        t.stage("resolve",if (r.conflicts.isEmpty()) AuditStageStatus.PASSED else AuditStageStatus.BLOCKED,if (r.conflicts.isEmpty()) null else "conflict")
        // resolve is a successful explanation even when it reports policy conflicts.
        if (r.conflicts.isNotEmpty() && t.command=="check") throw DashboardProblem(422,"conflict","Context has conflicting rules")
        if (r.conflicts.isNotEmpty()) t.exit=2
        return r
    }

    private fun audited(command:String,b:JsonNode?,operation:(Trace)->Map<String,Any?>):Map<String,Any?> {
        val c=runCatching { b?.get("context")?.let(DashboardJson::context) }.getOrNull()
            ?: synchronized(mutationLock) { previews[b?.get("previewId")?.asText()]?.context }
        val t=Trace(command,c)
        try { return operation(t) }
        catch (failure:Exception) {
            val p=when(failure) {
                is DashboardProblem -> failure
                is KnowledgeMutationException -> {
                    val code=failure.code
                    t.current=when(code) { "stale_preview" -> "baseline_guard"; "unsafe_path" -> "path_guard"; "write_failed" -> "write"; "approval_denied" -> "parameters"; else -> "knowledge_validate" }
                    DashboardProblem(when(code) { "stale_preview" -> 409; "invalid_rule","approval_denied" -> 422; "unsafe_path" -> 403; else -> 500 },code,
                        when(code) { "stale_preview" -> "Knowledge changed or preview expired; preview again"; "invalid_rule" -> "Rule mutation is invalid"; "approval_denied" -> "Declared approver is not authorized"; "unsafe_path" -> "Unsafe knowledge path"; else -> "Knowledge write failed" })
                }
                else -> DashboardProblem(500,"internal_error","Dashboard operation failed")
            }
            t.exit=if (p.status==400) 64 else 2; t.status=AuditRunStatus.FAILED
            t.stage(t.current,AuditStageStatus.BLOCKED,when(p.code) { "invalid_context","malformed_request","invalid_diff","body_too_large" -> "usage"; "stale_preview" -> "stale_knowledge"; "unsafe_path" -> "path_guard"; "approval_denied" -> "usage"; "invalid_rule","invalid_knowledge" -> "invalid_knowledge"; "conflict" -> "conflict"; else -> "storage_error" },detail=p.message)
            throw p
        } finally { t.finish() }
    }
    private inner class Trace(val command:String,c:RuleContext?) {
        val id=UUID.randomUUID().toString()
        private val start=System.nanoTime()
        var current="parameters"
        var exit=0
        var status=AuditRunStatus.COMPLETED
        init {
            append(AuditEvent(id,AuditEventType.STARTED,Instant.now().toString(),metadata=AuditMetadata("dashboard",command,options.knowledge.toAbsolutePath().normalize().toString(),c?.let { AuditContext(it.team,it.season,it.profiles?.sorted(),it.workMode.id) })))
        }
        fun parameters()=stage("parameters",AuditStageStatus.PASSED)
        fun snapshot(version:String,rules:List<KnowledgeRule>) {
            append(AuditEvent(id,AuditEventType.SNAPSHOT,Instant.now().toString(),knowledgeVersion=version,
                ruleSnapshots=rules.sortedBy { it.id }.take(512).map { AuditRuleSnapshot(it.id,it.topic,it.title.take(512),it.instruction.take(4096),it.authority.name.lowercase(java.util.Locale.ROOT),it.policyLevel.name.lowercase(java.util.Locale.ROOT),it.applicability.teams.sorted(),it.applicability.seasons.sorted(),it.applicability.profiles.sorted()) }))
        }
        fun stage(name:String,status:AuditStageStatus,category:String?=null,hits:List<AuditHit> = emptyList(),detail:String?=null) {
            append(AuditEvent(id,AuditEventType.STAGE,Instant.now().toString(),stage=AuditStage(name,status,category,detail=detail,hits=hits)))
        }
        fun finish()=append(AuditEvent(id,AuditEventType.FINISHED,Instant.now().toString(),status=status,exitCode=exit,durationMillis=(System.nanoTime()-start)/1_000_000))
        private fun append(event:AuditEvent) { if (!auditStore.append(event)) diagnostics("dashboard: audit storage unavailable") }
    }

    private fun serveStatic(x:HttpExchange,path:String) {
        if (x.requestMethod!="GET") throw DashboardProblem(405,"method_not_allowed","Static resources require GET")
        if (x.requestURI.rawQuery!=null) throw DashboardProblem(400,"malformed_request","Static resource query parameters are forbidden")
        val resource=when(path) { "/","/index.html" -> "index.html"; "/app.js" -> "app.js"; "/styles.css" -> "styles.css"; else -> throw DashboardProblem(404,"not_found","Resource not found") }
        val bytes=DashboardServer::class.java.getResourceAsStream("/dashboard/$resource")?.use { it.readBytes() }
            ?: throw DashboardProblem(404,"not_found","Dashboard resource unavailable")
        securityHeaders(x)
        x.responseHeaders.set("Content-Type",when(resource) { "app.js" -> "text/javascript; charset=utf-8"; "styles.css" -> "text/css; charset=utf-8"; else -> "text/html; charset=utf-8" })
        x.sendResponseHeaders(200,bytes.size.toLong()); x.responseBody.use { it.write(bytes) }
    }
    private fun reply(x:HttpExchange,status:Int,payload:Map<String,Any?>,ok:Boolean=true) {
        securityHeaders(x); x.responseHeaders.set("Content-Type","application/json; charset=utf-8")
        val bytes=DashboardJson.mapper.writeValueAsBytes(linkedMapOf<String,Any?>("apiVersion" to 1,"ok" to ok)+payload)
        x.sendResponseHeaders(status,bytes.size.toLong()); x.responseBody.use { it.write(bytes) }
    }
    private fun securityHeaders(x:HttpExchange) {
        x.responseHeaders.set("Cache-Control","no-store")
        x.responseHeaders.set("X-Content-Type-Options","nosniff")
        x.responseHeaders.set("Referrer-Policy","no-referrer")
        x.responseHeaders.set("Content-Security-Policy","default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self' data:; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'")
    }
    companion object {
        const val MAX_BODY=1024*1024
        private val routes=mapOf("summary" to "GET","rules" to "GET","rule" to "GET","resolve" to "POST","preview" to "POST","apply" to "POST","audit" to "GET","audit/detail" to "GET","validate" to "POST","check" to "POST","shutdown" to "POST").mapKeys { "/api/dashboard/${it.key}" }
        private fun mutationCommand(action:String?)=when(action) { "create" -> "rule_create"; "approve" -> "rule_approve"; "reject" -> "rule_reject"; else -> "rule_edit" }
    }
}

private fun dashboardAuditStore(root:Path?):AuditStore {
    return FileAuditStore(defaultAuditDirectory(),root)
}
