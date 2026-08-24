package org.ftckb.cli

import com.fasterxml.jackson.databind.json.JsonMapper
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.FileSystems
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.diff.Edit
import org.eclipse.jgit.diff.RawTextComparator
import org.eclipse.jgit.util.io.DisabledOutputStream
import org.ftckb.domain.RuleCheck
import org.ftckb.domain.RuleCheckKind
import org.ftckb.domain.RuleContext
import org.ftckb.domain.RuleResolver
import org.ftckb.domain.RuleIdentity
import org.ftckb.knowledge.FileKnowledgeRepository

data class DiffChange(val path:String,val addedLines:List<Pair<Int,String>>)

data class CheckViolation(
    val ruleId:String,val check:String,val path:String?,val line:Int?,val pattern:String,val detail:String
)

data class CheckOutcome(val violations:List<CheckViolation>,val soft:List<Pair<String,String>>)

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
        values["--diff"]?.let { parsePatch(Files.readString(Path.of(it))) } ?: worktreeChanges(repoRoot)
    } catch (exception:Exception) {
        val detail=exception.message?.lineSequence()?.firstOrNull()?.trim().orEmpty()
        return fail("error reading diff: ${detail.ifEmpty { exception.javaClass.simpleName }}","load-error",2)
    }
    val outcome=evaluate(resolved.activeRules,changes)
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

internal fun evaluate(rules:List<org.ftckb.domain.KnowledgeRule>,changes:List<DiffChange>):CheckOutcome {
    val violations=mutableListOf<CheckViolation>()
    val soft=mutableListOf<Pair<String,String>>()
    val matchers={ glob:String ->
        runCatching { FileSystems.getDefault().getPathMatcher("glob:$glob") }.getOrNull()
    }
    rules.forEach { rule ->
        if (rule.checks.isEmpty()) {
            soft+=rule.id to rule.instruction
        } else {
            rule.checks.forEach { check -> evaluateCheck(rule.id,check,changes,matchers,violations) }
        }
    }
    return CheckOutcome(violations,soft)
}

private fun evaluateCheck(
    ruleId:String,check:RuleCheck,changes:List<DiffChange>,
    matchers:(String)->java.nio.file.PathMatcher?,
    violations:MutableList<CheckViolation>
) {
    val appliesTo=check.appliesTo?.let(matchers)
    fun pathMatches(path:String):Boolean {
        val pathMatcher=matchers(check.pattern) ?: return false
        return pathMatcher.matches(Path.of(path))
    }
    fun fileApplies(path:String):Boolean=appliesTo==null || appliesTo.matches(Path.of(path))
    when (check.kind) {
        RuleCheckKind.PATH_FORBIDDEN -> {
            changes.filter { pathMatches(it.path) }.forEach { change ->
                violations+=CheckViolation(
                    ruleId,"path-forbidden",change.path,change.addedLines.firstOrNull()?.first,
                    check.pattern,check.note
                )
            }
        }
        RuleCheckKind.PATH_REQUIRED -> {
            if (changes.none { pathMatches(it.path) }) {
                violations+=CheckViolation(ruleId,"path-required",null,null,check.pattern,check.note)
            }
        }
        RuleCheckKind.REGEX_REQUIRED -> {
            val regex=runCatching { Regex(check.pattern) }.getOrNull() ?: return
            val applicable=changes.filter { fileApplies(it.path) && it.addedLines.isNotEmpty() }
            if (applicable.isEmpty()) return // nothing was added in applicable files
            val matched=applicable.any { change ->
                change.addedLines.any { (_,text) -> regex.containsMatchIn(text) }
            }
            if (!matched) {
                val first=applicable.first()
                violations+=CheckViolation(
                    ruleId,"regex-required",first.path,first.addedLines.firstOrNull()?.first,
                    check.pattern,check.note
                )
            }
        }
        RuleCheckKind.REGEX_FORBIDDEN -> {
            val regex=runCatching { Regex(check.pattern) }.getOrNull() ?: return
            changes.filter { fileApplies(it.path) }.forEach { change ->
                val hit=change.addedLines.firstOrNull { (_,text) -> regex.containsMatchIn(text) } ?: return@forEach
                violations+=CheckViolation(ruleId,"regex-forbidden",change.path,hit.first,check.pattern,check.note)
            }
        }
    }
}

internal fun parsePatch(text:String):List<DiffChange> {
    val changes=mutableListOf<DiffChange>()
    var currentPath:String?=null
    var currentLines=mutableListOf<Pair<Int,String>>()
    var newLineNumber=0
    fun flush() {
        val path=currentPath ?: return
        if (currentLines.isNotEmpty()) changes+=DiffChange(path,currentLines.toList())
        currentPath=null
        currentLines=mutableListOf()
        newLineNumber=0
    }
    text.lineSequence().forEach { line ->
        when {
            line.startsWith("+++ ") -> {
                flush()
                val target=line.removePrefix("+++ ").substringBefore('\t')
                if (target!="/dev/null") currentPath=target.removePrefix("b/")
            }
            line.startsWith("@@ ") -> {
                val range=Regex("@@ -[0-9]+(?:,[0-9]+)? \\+([0-9]+)(?:,([0-9]+))? @@").find(line)
                newLineNumber=(range?.groupValues?.get(1)?.toIntOrNull() ?: 1)
            }
            line.startsWith("+") && !line.startsWith("+++") -> {
                if (currentPath!=null) {
                    currentLines+=newLineNumber to line.removePrefix("+")
                    newLineNumber++
                }
            }
            line.startsWith("-") && !line.startsWith("---") -> Unit
            else -> {
                if (currentPath!=null && line.isNotEmpty()) newLineNumber++
            }
        }
    }
    flush()
    return changes
}

private fun worktreeChanges(root:Path):List<DiffChange> {
    val git=Git.open(root.toFile())
    git.use {
        val entries=git.diff().setCached(false).call()
        val changes=mutableListOf<DiffChange>()
        entries.forEach { entry ->
            val newPath=entry.newPath
            if (newPath==org.eclipse.jgit.diff.DiffEntry.DEV_NULL) return@forEach
            val newLines=runCatching { Files.readAllLines(root.resolve(newPath)) }.getOrElse { emptyList() }
            val oldLines=if (entry.oldId==org.eclipse.jgit.lib.ObjectId.zeroId()) emptyList()
            else runCatching { String(git.repository.open(entry.oldId.toObjectId()).bytes).lines() }.getOrElse { emptyList() }
            val patch=com.github.difflib.DiffUtils.diff(oldLines,newLines)
            val added=mutableListOf<Pair<Int,String>>()
            patch.deltas.forEach { delta ->
                if (delta.type==com.github.difflib.patch.DeltaType.INSERT || delta.type==com.github.difflib.patch.DeltaType.CHANGE) {
                    delta.target.lines.forEachIndexed { index,line ->
                        added+=(delta.target.position+index+1) to line
                    }
                }
            }
            if (added.isNotEmpty()) changes+=DiffChange(newPath,added)
        }
        return changes
    }
}
