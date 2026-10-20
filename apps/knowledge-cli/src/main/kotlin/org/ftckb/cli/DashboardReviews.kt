package org.ftckb.cli

import com.fasterxml.jackson.databind.JsonNode
import org.ftckb.audit.*
import java.util.Base64
import java.util.UUID

/** Projects independent declarations onto immutable check evidence. Never opens a source repository. */
internal class DashboardReviews(private val audit:AuditStore,private val reviews:ReviewStore) {
    private data class Source(val record:AuditRecord,val stageIndex:Int,val hit:AuditHit) {
        val itemId=ReviewIdentity.itemId(record,stageIndex,hit)
        val digest=ReviewIdentity.sourceDigest(record,stageIndex,hit)
        val version get()=record.stages[stageIndex].codeVersion
    }
    private data class Position(val invocationId:String,val digest:String,val nextAuditCursor:String?,val hitIndex:Int)
    private var indexRevision:String?=null
    private var indexCursor:String?=null
    private var indexComplete=false
    private val indexed=linkedMapOf<String,AuditRecord>()
    private val loading=linkedMapOf<String,AuditRecord>()
    private var sources=emptyMap<String,Source>()

    /** One bounded indexing step per request; incomplete comparisons stay explicitly unknown. */
    @Synchronized private fun refreshIndex() {
        val revision=audit.summary().revision
        if (revision!=indexRevision) {
            indexRevision=revision; indexCursor=null; indexComplete=false; loading.clear()
        }
        if (indexComplete) return
        val page=audit.page(indexCursor,100)
        if (page.revision!=indexRevision) { indexRevision=null; return }
        page.items.forEach { loading[it.invocationId]=it }
        indexCursor=page.nextCursor
        if (indexCursor==null) {
            indexed.clear(); indexed.putAll(loading); indexComplete=true
            sources=indexed.values.flatMap(::items).associateBy { it.itemId }
        }
    }
    private fun items(record:AuditRecord):List<Source> =record.stages.flatMapIndexed { index,stage ->
        if (stage.name!="standardizer" || stage.status !in setOf(AuditStageStatus.PASSED,AuditStageStatus.BLOCKED)) emptyList()
        else stage.hits.filter { it.kind in setOf("soft","dev_downgrade") }.distinct().map { Source(record,index,it) }
    }
    private fun complete(record:AuditRecord)=record.status==AuditRunStatus.COMPLETED && record.endedAt!=null
    private fun contextKey(record:AuditRecord)=record.metadata.context?.let { listOf(it.team,it.season,it.profiles?.sorted(),it.workMode) }
    private fun versionFormat(value:String?):String?=when {
        value==null -> null
        value.matches(Regex("[0-9a-f]{64}")) -> "source-sha256"
        value.matches(Regex("rules-sha256:[0-9a-f]{64}")) -> "rules-sha256"
        else -> null
    }
    private fun state(source:Source,auditHealthy:Boolean):String {
        val version=source.version ?: return "unknown"
        if (!auditHealthy || !complete(source.record) || !indexComplete || version.subjectId==null || versionFormat(source.record.knowledgeVersion)==null) return "unknown"
        var unknown=false
        indexed.values.takeWhile { it.invocationId!=source.record.invocationId }.filter { complete(it) && contextKey(it)==contextKey(source.record) }.forEach { later ->
            later.stages.filter { it.name=="standardizer" && it.status in setOf(AuditStageStatus.PASSED,AuditStageStatus.BLOCKED) }.forEach { stage ->
                val candidate=stage.codeVersion
                if (candidate?.kind==version.kind && candidate.subjectId==version.subjectId) {
                    if (versionFormat(later.knowledgeVersion)!=versionFormat(source.record.knowledgeVersion)) unknown=true
                    else if (candidate.inputDigest!=version.inputDigest || later.knowledgeVersion!=source.record.knowledgeVersion) return "stale"
                }
            }
        }
        return if (unknown) "unknown" else "snapshot"
    }
    private fun projected(source:Source,auditHealth:AuditHealth,reviewHealth:AuditHealth):Map<String,Any?> {
        val history=reviews.history(source.itemId)
        val latest=history.items.lastOrNull()
        val sourceChanged=latest!=null && latest.target!=ReviewTarget(source.itemId,source.record.invocationId,source.digest)
        val reason=when {
            sourceChanged -> "来源检查摘要与已有声明不一致；保留历史，不能新增声明。"
            !auditHealth.ok -> "原始审计历史不完整，暂时不能保存复核。"
            !reviewHealth.ok || !history.health.ok -> "复核历史不完整，暂时不能保存复核。"
            !complete(source.record) -> "只有完整结束的检查才能记录复核。"
            else -> null
        }
        return linkedMapOf("itemId" to source.itemId,"invocationId" to source.record.invocationId,"sourceDigest" to source.digest,
            "stageIndex" to source.stageIndex,"hit" to source.hit,"originalStatus" to source.record.stages[source.stageIndex].status.wireValue(),
            "codeVersion" to source.version,"versionState" to if (sourceChanged) "unknown" else state(source,auditHealth.ok),"latestReview" to latest,
            "canReview" to (reason==null),"canRetry" to (!sourceChanged && auditHealth.ok && complete(source.record) && (!reviewHealth.ok || !history.health.ok)),"disabledReason" to reason,
            "source" to mapOf("startedAt" to source.record.startedAt,"metadata" to source.record.metadata,
                "knowledgeVersion" to source.record.knowledgeVersion,"exitCode" to source.record.exitCode,"status" to source.record.status.wireValue()))
    }
    @Synchronized fun forRecord(record:AuditRecord):List<Map<String,Any?>> {
        refreshIndex(); val ah=audit.summary().health; val rh=reviews.health()
        return items(record).map { projected(it,ah,rh) }
    }
    @Synchronized fun detail(itemId:String):Map<String,Any?> {
        hash(itemId); refreshIndex()
        val history=reviews.history(itemId)
        val cached=sources[itemId] ?: loading.values.asSequence().flatMap { items(it).asSequence() }.firstOrNull { it.itemId==itemId }
        val source=cached?.let { existing -> audit.find(existing.record.invocationId)?.let(::items)?.firstOrNull { it.itemId==itemId } }
            ?: history.items.lastOrNull()?.target?.let { audit.find(it.invocationId)?.let(::items)?.firstOrNull { s -> s.itemId==itemId } }
        val ah=audit.summary().health; val rh=reviews.health()
        if (source==null) {
            if (history.items.isNotEmpty()) return mapOf("item" to mapOf("itemId" to itemId,"invocationId" to history.items.last().target.invocationId,
                "sourceDigest" to history.items.last().target.sourceDigest,"source" to null,"hit" to null,"codeVersion" to null,
                "versionState" to "unknown","latestReview" to history.items.last(),"canReview" to false,
                "disabledReason" to "来源检查已缺失；保留独立历史，不能新增声明。","orphaned" to true),
                "history" to history.items,"auditHealth" to ah,"reviewHealth" to rh,"indexComplete" to indexComplete)
            if (!indexComplete) throw DashboardProblem(503,"index_loading","正在读取检查索引，请稍后重试；尚不能确认此事项来源。")
            throw DashboardProblem(404,"not_found","Review item not found")
        }
        return mapOf("item" to projected(source,ah,rh),"history" to history.items,"auditHealth" to ah,"reviewHealth" to rh,"indexComplete" to indexComplete)
    }
    @Synchronized fun page(cursor:String?,limit:Int,filter:String):Map<String,Any?> {
        if (limit !in 1..100 || filter !in setOf("pending","all","recorded")) throw DashboardProblem(400,"malformed_request","Invalid review page selection")
        refreshIndex(); val ah=audit.summary().health; val rh=reviews.health()
        var position=cursor?.let(::decode)
        var record:AuditRecord?
        var nextAudit:String?
        var hitIndex:Int
        if (position==null) {
            val first=audit.page(null,1); record=first.items.firstOrNull(); nextAudit=first.nextCursor; hitIndex=0
        } else {
            record=audit.find(position.invocationId)
            if (record==null || recordDigest(record)!=position.digest) throw DashboardProblem(409,"stale_cursor","检查历史已变化，请刷新复核列表。")
            nextAudit=position.nextAuditCursor; hitIndex=position.hitIndex
        }
        val result=mutableListOf<Map<String,Any?>>()
        var scanned=0
        var next:String?=null
        while (record!=null && scanned<100) {
            val current=record; val recordItems=items(current)
            if (hitIndex>recordItems.size) throw DashboardProblem(400,"malformed_request","Invalid review cursor position")
            while (hitIndex<recordItems.size) {
                val item=projected(recordItems[hitIndex++],ah,rh)
                val latest=item["latestReview"] as? ReviewRecord
                val pending=latest==null || latest.decision=="needs_changes" || item["versionState"]=="stale" || item["canReview"]!=true
                if (filter=="all" || filter=="pending" && pending || filter=="recorded" && latest!=null) result+=item
                if (result.size==limit) {
                    if (hitIndex<recordItems.size || nextAudit!=null) next=encode(Position(current.invocationId,recordDigest(current),nextAudit,hitIndex))
                    break
                }
            }
            if (result.size==limit) break
            scanned++
            if (nextAudit==null) { record=null; break }
            if (scanned>=100) { next=encode(Position(current.invocationId,recordDigest(current),nextAudit,hitIndex)); break }
            val more=audit.page(nextAudit,1); record=more.items.firstOrNull(); nextAudit=more.nextCursor; hitIndex=0
        }
        return mapOf("items" to result,"nextCursor" to next,"revision" to "${audit.summary().revision}:${result.mapNotNull { (it["latestReview"] as? ReviewRecord)?.reviewId }.joinToString(",")}:${indexComplete}",
            "auditHealth" to ah,"reviewHealth" to rh,"indexComplete" to indexComplete)
    }
    @Synchronized fun record(body:JsonNode):Map<String,Any?> {
        DashboardJson.fields(body,setOf("reviewId","itemId","invocationId","sourceDigest","expectedRevision","decision","reason","evidenceUrls","reviewer"))
        val revision=body.get("expectedRevision")?.takeIf { it.isIntegralNumber && it.canConvertToLong() && it.longValue()>=0 }?.longValue()
            ?: throw DashboardProblem(400,"malformed_request","expectedRevision must be a nonnegative integer")
        val urls=body.get("evidenceUrls")?.takeIf { it.isArray && it.all { u -> u.isTextual } }?.map { it.asText() }
            ?: throw DashboardProblem(400,"malformed_request","evidenceUrls must be an array of strings")
        val target=ReviewTarget(DashboardJson.text(body,"itemId"),DashboardJson.text(body,"invocationId"),DashboardJson.text(body,"sourceDigest"))
        val input=ReviewInput(DashboardJson.text(body,"reviewId"),target,revision,DashboardJson.text(body,"decision"),DashboardJson.text(body,"reason"),urls,DashboardJson.text(body,"reviewer"))
        uuid(input.reviewId); uuid(target.invocationId); hash(target.itemId); hash(target.sourceDigest)
        val current=detail(target.itemId)["item"] as Map<*,*>
        if (current["invocationId"]!=target.invocationId || current["sourceDigest"]!=target.sourceDigest || (current["canReview"]!=true && current["canRetry"]!=true))
            throw DashboardProblem(409,"source_changed","原始检查不可确认或已变化；草稿未保存。")
        val saved=try { reviews.record(input) { expected ->
            val source=audit.find(expected.invocationId)?.let(::items)?.firstOrNull { it.itemId==expected.itemId }
            source!=null && source.digest==expected.sourceDigest && complete(source.record) && audit.summary().health.ok
        } } catch (problem:ReviewProblem) {
            throw DashboardProblem(when(problem.code) { "invalid_input" -> 422; "conflict","source_changed" -> 409; else -> 503 },problem.code,
                when(problem.code) { "invalid_input" -> "复核字段无效；请检查姓名、理由与链接。"; "conflict" -> "另一条复核已经保存；草稿已保留，请核对最新历史后重试。"; "source_changed" -> "原始检查已变化；草稿未保存。"; else -> "复核存储不可用；草稿未保存，请稍后重试。" })
        }
        return mapOf("record" to saved.record,"replayed" to saved.replayed,"item" to detail(target.itemId)["item"])
    }
    fun predecessor(id:String,context:AuditContext):AuditCodeVersion {
        uuid(id)
        val source=audit.find(id) ?: throw DashboardProblem(404,"not_found","Previous dashboard check not found")
        val versions=source.stages.filter { it.name=="standardizer" && it.status in setOf(AuditStageStatus.PASSED,AuditStageStatus.BLOCKED) }.mapNotNull { it.codeVersion }
        if (!complete(source) || source.metadata.entrypoint!="dashboard" || source.metadata.command!="check" || source.metadata.context!=context || versions.size!=1 || versions.single().kind!="supplied_patch" || versions.single().subjectId==null)
            throw DashboardProblem(422,"invalid_predecessor","重新检查必须选择同一上下文中的管理台补丁检查。")
        return versions.single()
    }
    private fun recordDigest(record:AuditRecord)=ReviewFingerprint.sha256(listOf(record))
    private fun encode(p:Position):String=Base64.getUrlEncoder().withoutPadding().encodeToString(DashboardJson.mapper.writeValueAsBytes(listOf(p.invocationId,p.digest,p.nextAuditCursor,p.hitIndex)))
    private fun decode(value:String):Position=try {
        require(value.length<=2048)
        val node=DashboardJson.mapper.readTree(Base64.getUrlDecoder().decode(value)); require(node.isArray && node.size()==4)
        val id=node[0].takeIf { it.isTextual }!!.asText(); uuid(id)
        val digest=node[1].takeIf { it.isTextual }!!.asText(); hash(digest)
        val cursor=node[2].takeUnless { it.isNull }?.takeIf { it.isTextual }?.asText()
        require(node[2].isNull || cursor?.toLongOrNull()?.let { it>=0 }==true)
        require(node[3].isIntegralNumber && node[3].canConvertToInt() && node[3].intValue()>=0)
        Position(id,digest,cursor,node[3].intValue())
    } catch (_:Exception) { throw DashboardProblem(400,"malformed_request","Invalid review cursor") }
    private fun hash(value:String) { if (!value.matches(Regex("[0-9a-f]{64}"))) throw DashboardProblem(400,"malformed_request","Invalid review identifier") }
    private fun uuid(value:String) { if (runCatching { UUID.fromString(value).toString()==value }.getOrDefault(false).not()) throw DashboardProblem(400,"malformed_request","Invalid invocation identifier") }
}
