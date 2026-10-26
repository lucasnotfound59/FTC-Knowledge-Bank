package org.ftckb.cli

import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import org.ftckb.audit.AuditCodeVersion
import org.ftckb.audit.ReviewFingerprint
import org.ftckb.domain.RuleContext
import org.ftckb.domain.RuleContextException
import org.ftckb.domain.RuleResolver
import org.ftckb.domain.RuleIdentity
import org.ftckb.domain.WorkMode
import org.ftckb.knowledge.FileKnowledgeRepository
import org.ftckb.standardizer.Standardizer

internal fun runCheckCommand(args:List<String>,out:PrintStream,audit:KernelAudit?=null):Int {
    val jsonMode=args.contains("--json")
    fun fail(message:String,code:String,exit:Int):Int {
        audit?.failure(code)
        if (jsonMode) out.println(KernelJson.errorJson("check",code,message))
        else out.println(message)
        return exit
    }
    if (args==listOf("--help")) {
        out.println("usage: knowledge-cli check <repo-root> [--knowledge PATH] --team N --season YYYY-YYYY (--profile NAME [--profile NAME ...] | --generic-profile) [--diff FILE] [--work-mode normal|test|dev] [--json]")
        return 0
    }
    if (args.isEmpty()) return fail("missing <repo-root>","usage",64)
    val profileSelection=try {
        ProfileArguments.extract(args.drop(1).filterNot { it=="--json" })
    } catch (exception:IllegalArgumentException) {
        return fail(exception.message.orEmpty(),"usage",64)
    }
    val optionArgs=profileSelection.remaining
    if (optionArgs.size%2!=0) return fail("check options must be flag-value pairs","usage",64)
    val optionPairs=optionArgs.chunked(2)
    val allowed=setOf("--knowledge","--team","--season","--diff","--work-mode")
    val unknown=optionPairs.firstOrNull { it[0] !in allowed }
    if (unknown!=null) return fail("unknown check option: ${unknown[0]}","usage",64)
    val duplicate=optionPairs.groupBy { it[0] }.entries.firstOrNull { it.value.size>1 }
    if (duplicate!=null) return fail("duplicate check option: ${duplicate.key}","usage",64)
    val values=optionPairs.associate { it[0] to it[1] }
    listOf("--team","--season").forEach { required ->
        if (required !in values) return fail("missing $required","usage",64)
    }
    if (!RuleIdentity.isCanonicalTeam(values.getValue("--team"))) {
        return fail("invalid value for --team: expected digits only","usage",64)
    }
    if (!RuleIdentity.isCanonicalSeason(values.getValue("--season"))) {
        return fail("invalid value for --season: expected YYYY-YYYY","usage",64)
    }
    val workMode=values["--work-mode"]?.let { selected ->
        WorkMode.parse(selected) ?: return fail("invalid value for --work-mode: expected normal|test|dev","usage",64)
    } ?: WorkMode.NORMAL
    // DEV never relaxes a full dirty checkout: the caller must name the scoped patch first.
    if (workMode==WorkMode.DEV && "--diff" !in values) {
        return fail("check --work-mode dev requires --diff FILE","usage",64)
    }
    val profiles=try {
        profileSelection.requiredProfiles()
    } catch (exception:RuleContextException) {
        return fail(exception.message.orEmpty(),exception.code,2)
    }
    val repoRoot=Path.of(args[0])
    val knowledgeRoot=Path.of(values["--knowledge"] ?: "knowledge")
    audit?.passed("parameters")
    audit?.begin("knowledge_load")
    val loaded=try {
        FileKnowledgeRepository.load(knowledgeRoot)
    } catch (exception:Exception) {
        val detail=exception.message?.lineSequence()?.firstOrNull()?.trim().orEmpty()
        return fail("error loading knowledge: ${detail.ifEmpty { exception.javaClass.simpleName }}","load-error",2)
    }
    audit?.loaded(loaded.rules)
    audit?.begin("knowledge_validate")
    if (loaded.violations.isNotEmpty()) {
        audit?.invalid(loaded.violations)
        if (jsonMode) {
            out.println(KernelJson.violationsJson("check",loaded.violations))
        } else {
            loaded.violations.sortedWith(compareBy({ it.ruleId },{ it.field })).forEach {
                out.println("error rule=${it.ruleId} field=${it.field} message=${it.message}")
            }
        }
        return 2
    }
    audit?.passed("knowledge_validate")
    audit?.begin("resolve")
    val resolved=RuleResolver.resolve(
        loaded.rules,RuleContext(values.getValue("--team"),values.getValue("--season"),profiles,workMode)
    )
    if (resolved.conflicts.isEmpty()) audit?.active(resolved.activeRules)
    if (resolved.conflicts.isNotEmpty()) {
        audit?.conflicts(resolved.conflicts,loaded.rules,resolved.activeRules)
        val detail=resolved.conflicts.joinToString("; ") { conflict ->
            "conflict topic=${conflict.topic} rules=${conflict.ruleIds.sorted().joinToString(",")}"
        }
        return fail(detail,"conflict",2)
    }
    audit?.passed("resolve")
    audit?.begin("diff_read")
    var codeVersion:AuditCodeVersion?=null
    fun version(block:()->AuditCodeVersion):AuditCodeVersion?=runCatching(block).onFailure { audit?.codeVersionUnavailable() }.getOrNull()
    val changes=try {
        values["--diff"]?.let {
            val patch=Files.readString(Path.of(it))
            val parsed=Standardizer.parsePatch(patch)
            codeVersion=version {
                val identity=runCatching { repoRoot.toRealPath().toString() }.getOrNull()
                AuditCodeVersion("supplied_patch",identity?.let { source -> ReviewFingerprint.sha256(listOf(source)) },
                    ReviewFingerprint.sha256(listOf("supplied-patch-v1",identity,patch)))
            }
            parsed
        } ?: Standardizer.captureWorktree(repoRoot).let { capture ->
            codeVersion=version {
                require(capture.repositoryIdentity.isNotBlank() && capture.headBefore==capture.headAfter)
                AuditCodeVersion("worktree",ReviewFingerprint.sha256(listOf(capture.repositoryIdentity)),
                    ReviewFingerprint.sha256(listOf("worktree-v1",capture.repositoryIdentity,
                        capture.headBefore,capture.headAfter,capture.patches,capture.contentDigests)),capture.headAfter)
            }
            capture.changes
        }
    } catch (exception:Exception) {
        val detail=exception.message?.lineSequence()?.firstOrNull()?.trim().orEmpty()
        return fail("error reading diff: ${detail.ifEmpty { exception.javaClass.simpleName }}","load-error",2)
    }
    audit?.passed("diff_read")
    audit?.begin("standardizer")
    val evaluated=Standardizer.evaluate(resolved.activeRules,changes)
    audit?.checked(evaluated,workMode==WorkMode.DEV,codeVersion)
    // DEV is a per-task downgrade: hard findings stay visible as location-rich soft notices,
    // pre-existing soft notices are retained, and only usage/load/validation/conflict errors
    // still fail. It never claims production-rule compliance.
    val outcome=if (workMode==WorkMode.DEV) {
        Standardizer.Outcome(
            emptyList(),
            (evaluated.soft+Standardizer.downgradeToSoft(evaluated.violations))
                .sortedWith(compareBy({ it.first },{ it.second }))
        )
    } else evaluated
    if (jsonMode) {
        out.println(KernelJson.checkJson(values.getValue("--team"),values.getValue("--season"),profiles,outcome,workMode))
        return if (outcome.violations.isEmpty()) 0 else 1
    }
    if (workMode!=WorkMode.NORMAL) out.println("work-mode=${workMode.id}")
    outcome.violations.sortedWith(compareBy({ it.ruleId },{ it.path.orEmpty() },{ it.line ?: 0 })).forEach { violation ->
        val location=buildString {
            violation.path?.let { append(" path=").append(it) }
            violation.line?.let { append(" line=").append(it) }
        }
        out.println("violation rule=${violation.ruleId} check=${violation.check}$location: ${violation.detail}")
    }
    outcome.soft.sortedBy { it.first }.forEach { (ruleId,note) ->
        out.println("soft rule=$ruleId: $note")
    }
    out.println("check=${if (outcome.violations.isEmpty()) "pass" else "fail"} violations=${outcome.violations.size} soft=${outcome.soft.size}")
    return if (outcome.violations.isEmpty()) 0 else 1
}
