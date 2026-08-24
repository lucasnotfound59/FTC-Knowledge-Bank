package org.ftckb.cli

import com.fasterxml.jackson.databind.json.JsonMapper
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import org.ftckb.domain.RuleContext
import org.ftckb.domain.RuleResolver
import org.ftckb.domain.RuleIdentity
import org.ftckb.knowledge.FileKnowledgeRepository
import org.ftckb.standardizer.Standardizer

internal fun runCheckCommand(args:List<String>,out:PrintStream):Int {
    val jsonMode=args.contains("--json")
    fun fail(message:String,code:String,exit:Int):Int {
        if (jsonMode) out.println(KernelJson.errorJson("check",code,message))
        else out.println(message)
        return exit
    }
    if (args==listOf("--help")) {
        out.println("usage: knowledge-cli check <repo-root> [--knowledge PATH] --team N --season YYYY-YYYY [--diff FILE] [--json]")
        return 0
    }
    if (args.isEmpty()) return fail("missing <repo-root>","usage",64)
    val optionArgs=args.drop(1).filterNot { it=="--json" }
    if (optionArgs.size%2!=0) return fail("check options must be flag-value pairs","usage",64)
    val optionPairs=optionArgs.chunked(2)
    val allowed=setOf("--knowledge","--team","--season","--diff")
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
    val repoRoot=Path.of(args[0])
    val knowledgeRoot=Path.of(values["--knowledge"] ?: "knowledge")
    val loaded=try {
        FileKnowledgeRepository.load(knowledgeRoot)
    } catch (exception:Exception) {
        val detail=exception.message?.lineSequence()?.firstOrNull()?.trim().orEmpty()
        return fail("error loading knowledge: ${detail.ifEmpty { exception.javaClass.simpleName }}","load-error",2)
    }
    if (loaded.violations.isNotEmpty()) {
        if (jsonMode) {
            out.println(KernelJson.violationsJson("check",loaded.violations))
        } else {
            loaded.violations.sortedWith(compareBy({ it.ruleId },{ it.field })).forEach {
                out.println("error rule=${it.ruleId} field=${it.field} message=${it.message}")
            }
        }
        return 2
    }
    val resolved=RuleResolver.resolve(loaded.rules,RuleContext(values.getValue("--team"),values.getValue("--season")))
    if (resolved.conflicts.isNotEmpty()) {
        val detail=resolved.conflicts.joinToString("; ") { conflict ->
            "conflict topic=${conflict.topic} rules=${conflict.ruleIds.sorted().joinToString(",")}"
        }
        return fail(detail,"conflict",2)
    }
    val changes=try {
        values["--diff"]?.let { Standardizer.parsePatch(Files.readString(Path.of(it))) } ?: Standardizer.worktreeChanges(repoRoot)
    } catch (exception:Exception) {
        val detail=exception.message?.lineSequence()?.firstOrNull()?.trim().orEmpty()
        return fail("error reading diff: ${detail.ifEmpty { exception.javaClass.simpleName }}","load-error",2)
    }
    val outcome=Standardizer.evaluate(resolved.activeRules,changes)
    val mapper=JsonMapper.builder().build()
    if (jsonMode) {
        val root=mapper.createObjectNode()
        root.put("schemaVersion",1)
        root.put("command","check")
        root.put("team",values.getValue("--team"))
        root.put("season",values.getValue("--season"))
        root.put("ok",outcome.violations.isEmpty())
        val violations=root.putArray("violations")
        outcome.violations.sortedWith(compareBy({ it.ruleId },{ it.path.orEmpty() },{ it.line ?: 0 })).forEach { violation ->
            violations.addObject().apply {
                put("ruleId",violation.ruleId)
                put("check",violation.check)
                violation.path?.let { put("path",it) }
                violation.line?.let { put("line",it) }
                put("pattern",violation.pattern)
                put("detail",violation.detail)
            }
        }
        val soft=root.putArray("soft")
        outcome.soft.sortedBy { it.first }.forEach { (ruleId,note) ->
            soft.addObject().apply {
                put("ruleId",ruleId)
                put("note",note)
            }
        }
        out.println(mapper.writeValueAsString(root))
        return if (outcome.violations.isEmpty()) 0 else 1
    }
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
