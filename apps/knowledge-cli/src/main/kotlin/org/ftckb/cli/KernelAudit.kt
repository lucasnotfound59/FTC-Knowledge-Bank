package org.ftckb.cli

import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import org.ftckb.audit.*
import org.ftckb.domain.KnowledgeRule
import org.ftckb.domain.GitRuleEvidence
import org.ftckb.domain.WebRuleEvidence
import org.ftckb.domain.RuleConflict
import org.ftckb.domain.RuleIdentity
import org.ftckb.domain.RuleViolation
import org.ftckb.standardizer.Standardizer

/** Observes kernel execution; it never formats kernel output or changes its return value. */
internal class KernelAudit(command:String, args:List<String>, baseDirectory:Path?, private val diagnostics:(String)->Unit) {
    private val id=UUID.randomUUID().toString()
    private val start=System.nanoTime()
    private var failed=false
    private var currentStage="parameters"
    private val store:AuditStore?=try {
        val root=discoverRoot(command,args)
        FileAuditStore(baseDirectory ?: defaultAuditDirectory(),root,diagnostics).also { target ->
            target.append(AuditEvent(id,AuditEventType.STARTED,now(),metadata=AuditMetadata(
                "cli",command,root?.toString(),context(command,args)
            )))
        }
    } catch (_:Exception) { warn(); null }

    init {
        listOf("parameters","knowledge_load","knowledge_validate","resolve","diff_read","standardizer").forEach { name ->
            stage(name,if (command!="check" && name in setOf("diff_read","standardizer") || command=="validate" && name=="resolve")
                AuditStageStatus.NOT_APPLICABLE else AuditStageStatus.NOT_RUN)
        }
    }

    fun begin(name:String) { currentStage=name }
    fun passed(name:String) { stage(name,AuditStageStatus.PASSED) }
    fun failure(code:String) {
        if (code=="conflict" && failed) return
        failed=true
        val category=when (code) {
            "usage","context-required","invalid-context" -> "usage"
            "invalid-knowledge" -> "invalid_knowledge"
            "conflict" -> "conflict"
            else -> "load_error"
        }
        stage(currentStage,AuditStageStatus.BLOCKED,category,"Kernel stage failed ($category).")
    }
    fun loaded(rules:List<KnowledgeRule>) {
        // The digest is over the immutable decoded rules actually used, never a later disk reread.
        // Prefix distinguishes this semantic version from the dashboard's source-file version.
        val version=version(rules)
        snapshot(rules,version)
        passed("knowledge_load")
    }
    fun active(rules:List<KnowledgeRule>) { snapshot(rules,null) }
    private fun snapshot(rules:List<KnowledgeRule>,version:String?) {
        emit(AuditEvent(id,AuditEventType.SNAPSHOT,now(),knowledgeVersion=version,ruleSnapshots=rules.sortedBy { it.id }.map { rule ->
            AuditRuleSnapshot(bounded(rule.id,256),bounded(rule.topic,256),bounded(rule.title,512),bounded(rule.instruction,8192),
                rule.authority.name.lowercase(),rule.policyLevel.name.lowercase(),rule.applicability.teams.sorted(),
                rule.applicability.seasons.sorted(),rule.applicability.profiles.sorted())
        }))
    }
    fun conflicts(conflicts:List<RuleConflict>,loaded:List<KnowledgeRule>,active:List<KnowledgeRule>) {
        failed=true
        val involved=conflicts.flatMap { it.ruleIds }.toSet()+active.map { it.id }
        snapshot(loaded.filter { it.id in involved },null)
        stage("resolve",AuditStageStatus.BLOCKED,"conflict","Rule resolution has conflicting winners.",
            conflicts.take(256).flatMap { conflict -> conflict.ruleIds.sorted().map { ruleId ->
                AuditHit("conflict",bounded(ruleId,256),null,null,null,
                    "topic=${bounded(conflict.topic,256)}; effectiveLevel=${conflict.effectiveLevel.name.lowercase()}")
            } })
    }
    fun invalid(violations:List<RuleViolation>) {
        failed=true
        stage("knowledge_validate",AuditStageStatus.BLOCKED,"invalid_knowledge","Knowledge validation failed.",
            violations.take(1000).map { AuditHit("invalid_knowledge",bounded(it.ruleId,256),bounded(it.field,256),null,null,"Rule validation failed.") })
    }
    fun checked(evaluated:Standardizer.Outcome,dev:Boolean) {
        val hits=evaluated.violations.map { violation ->
            AuditHit(if (dev) "dev_downgrade" else "hard",bounded(violation.ruleId,256),bounded(violation.check,256),
                violation.path?.let { bounded(it,2048) },violation.line,bounded(violation.detail,2048))
        }+evaluated.soft.map { (ruleId,note) -> AuditHit("soft",bounded(ruleId,256),null,null,null,bounded(note,8192)) }
        val blocked=!dev && evaluated.violations.isNotEmpty()
        stage("standardizer",if (blocked) AuditStageStatus.BLOCKED else AuditStageStatus.PASSED,
            if (blocked) "hard" else if (dev && evaluated.violations.isNotEmpty()) "dev_downgrade" else if (evaluated.soft.isNotEmpty()) "soft" else null,
            hits=hits.take(1000))
    }
    fun finish(exit:Int?) {
        if (exit==null && !failed) failure("load-error")
        emit(AuditEvent(id,AuditEventType.FINISHED,now(),status=if (failed || exit==null || exit !in setOf(0,1)) AuditRunStatus.FAILED else AuditRunStatus.COMPLETED,
            exitCode=exit,durationMillis=(System.nanoTime()-start)/1_000_000))
    }
    private fun stage(name:String,status:AuditStageStatus,category:String?=null,detail:String?=null,hits:List<AuditHit> = emptyList()) {
        emit(AuditEvent(id,AuditEventType.STAGE,now(),stage=AuditStage(name,status,category,detail,hits)))
    }
    private fun emit(event:AuditEvent) { try { store?.append(event) } catch (_:Exception) { warn() } }
    private fun warn() { try { diagnostics("ftckb audit: storage unavailable; invocation audit is incomplete") } catch (_:Exception) { } }
    companion object {
        /** Canonical primitive JSON avoids ambiguous data-class toString delimiters. */
        internal fun version(rules:List<KnowledgeRule>):String {
            val values=rules.sortedBy { it.id }.map { rule -> linkedMapOf<String,Any?>(
                "id" to rule.id,"topic" to rule.topic,"title" to rule.title,"instruction" to rule.instruction,
                "rationale" to rule.rationale,"status" to rule.status.name,"authority" to rule.authority.name,
                "policyLevel" to rule.policyLevel.name,"teams" to rule.applicability.teams.sorted(),
                "seasons" to rule.applicability.seasons.sorted(),"profiles" to rule.applicability.profiles.sorted(),
                "evidence" to rule.evidence.map { evidence -> when(evidence) {
                    is GitRuleEvidence -> listOf("git",evidence.repository,evidence.commit,evidence.file,evidence.symbol,evidence.line)
                    is WebRuleEvidence -> listOf("web",evidence.url,evidence.title,evidence.publisher,evidence.accessedAt.toString(),
                        evidence.section,evidence.version,evidence.product,evidence.sku)
                } },
                "approval" to rule.approval?.let { listOf(it.approver,it.role.name,it.team,it.approvedAt.toString()) },
                "supersedes" to rule.supersedes,"positiveExample" to rule.positiveExample,"negativeExample" to rule.negativeExample,
                "checks" to rule.checks.map { listOf(it.kind.name,it.pattern,it.appliesTo,it.note) },
                "reviewTriggers" to rule.reviewTriggers.map { listOf(it.paths,it.addedLinePatterns) }
            ) }
            return "rules-sha256:"+MessageDigest.getInstance("SHA-256").digest(ObjectMapper().writeValueAsBytes(values))
                .joinToString("") { "%02x".format(it) }
        }
        private fun now()=Instant.now().toString()
        private fun bounded(value:String,max:Int)=value.filter { it=='\n' || it=='\t' || !it.isISOControl() }.take(max)
        private fun path(raw:String?):Path?=raw?.takeIf { it.isNotBlank() && !it.startsWith("--") && it.length<=4096 }?.let {
            runCatching { Path.of(it).toAbsolutePath().normalize().let { p -> runCatching { p.toRealPath() }.getOrDefault(p) } }.getOrNull()
        }
        private fun discoverRoot(command:String,args:List<String>):Path? {
            if (command!="check") return path(args.getOrNull(1))
            val options=args.drop(2)
            val indices=options.indices.filter { options[it]=="--knowledge" }
            return if (indices.size==1) path(options.getOrNull(indices.single()+1))
                else if (indices.isEmpty() && args.getOrNull(1)?.let { !it.startsWith("--") }==true) path("knowledge") else null
        }
        private fun context(command:String,args:List<String>):AuditContext? {
            if (command=="validate") return null
            fun value(flag:String)=args.indexOf(flag).takeIf { it>=0 }?.let { args.getOrNull(it+1) }
            val team=value("--team")?.takeIf(RuleIdentity::isCanonicalTeam)
            val season=value("--season")?.takeIf(RuleIdentity::isCanonicalSeason)
            val profiles=runCatching { ProfileArguments.extract(args.drop(2).filterNot { it=="--json" }).requiredProfiles().sorted() }.getOrNull()
            val mode=value("--work-mode")?.takeIf { it in setOf("normal","test","dev") } ?: "normal"
            return AuditContext(team,season,profiles,mode)
        }
    }
}
