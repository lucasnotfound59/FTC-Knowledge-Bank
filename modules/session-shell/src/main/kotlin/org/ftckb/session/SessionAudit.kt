package org.ftckb.session

import java.time.Instant
import java.util.UUID
import org.ftckb.audit.*
import org.ftckb.domain.KnowledgeRule
import org.ftckb.knowledge.KnowledgeSnapshot
import org.ftckb.agent.RuntimeStageEvidence

/** Only structured policy evidence enters this sink; model content and exception text never do. */
internal class SessionAudit(val store:AuditStore,val metadata:AuditMetadata) {
    val id:String=UUID.randomUUID().toString()
    private val started=System.nanoTime()
    private val seen=mutableSetOf<String>()
    init { event(AuditEventType.STARTED,metadata=metadata,status=AuditRunStatus.STARTED) }
    fun stage(name:String,status:AuditStageStatus,category:String?=null,hits:List<AuditHit> =emptyList(),detail:String?=null,codeVersion:AuditCodeVersion?=null) {
        if(store.append(AuditEvent(id,AuditEventType.STAGE,Instant.now().toString(),stage=AuditStage(name,status,category,detail,hits,codeVersion)))) seen+=name
    }
    fun has(name:String):Boolean=name in seen
    fun observe(evidence:RuntimeStageEvidence) {
        stage(evidence.name,AuditStageStatus.valueOf(evidence.status.name),evidence.category,detail=evidence.detail)
    }
    fun snapshot(snapshot:KnowledgeSnapshot,rules:List<KnowledgeRule>) {
        store.append(AuditEvent(id,AuditEventType.SNAPSHOT,Instant.now().toString(),knowledgeVersion=snapshot.version,
            ruleSnapshots=rules.map { rule -> AuditRuleSnapshot(rule.id,rule.topic,rule.title,rule.instruction,
                rule.authority.name.lowercase(),rule.policyLevel.name.lowercase(),rule.applicability.teams.sorted(),
                rule.applicability.seasons.sorted(),rule.applicability.profiles.sorted()) }))
    }
    fun finish(failed:Boolean=false,exit:Int=if(failed) 2 else 0,input:Int?=null,output:Int?=null) {
        listOf("knowledge_load","knowledge_validate","resolve","diff_read","standardizer","path_guard","branch_guard",
            "citation_guard","baseline_guard","write","restore","model").filterNot { it in seen }.forEach {
            stage(it,if ((metadata.command=="ask" && it in setOf("diff_read","standardizer","write","restore")) || (metadata.command in setOf("undo","discard") && it in setOf("diff_read","standardizer","write")))
                AuditStageStatus.NOT_APPLICABLE else AuditStageStatus.NOT_RUN)
        }
        store.append(AuditEvent(id,AuditEventType.FINISHED,Instant.now().toString(),
            status=if(failed) AuditRunStatus.FAILED else AuditRunStatus.COMPLETED,exitCode=exit,
            durationMillis=(System.nanoTime()-started)/1_000_000,inputTokens=input,outputTokens=output))
    }
    private fun event(type:AuditEventType,metadata:AuditMetadata?=null,status:AuditRunStatus?=null) {
        store.append(AuditEvent(id,type,Instant.now().toString(),metadata=metadata,status=status))
    }
}
