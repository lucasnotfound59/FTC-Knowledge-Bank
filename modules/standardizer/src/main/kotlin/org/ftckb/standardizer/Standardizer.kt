package org.ftckb.standardizer

import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import org.eclipse.jgit.api.Git
import org.ftckb.domain.KnowledgeRule
import org.ftckb.domain.RuleCheck
import org.ftckb.domain.RuleCheckKind

/** The machine-enforceable standardizer: evaluates the checks attached to active
 * rules against added lines of a diff. Deterministic, no model involved. */
object Standardizer {
    data class DiffChange(val path:String,val addedLines:List<Pair<Int,String>>)

    data class Violation(
        val ruleId:String,val check:String,val path:String?,val line:Int?,val pattern:String,val detail:String
    )

    data class Outcome(val violations:List<Violation>,val soft:List<Pair<String,String>>)

    fun evaluate(rules:List<KnowledgeRule>,changes:List<DiffChange>):Outcome {
        val violations=mutableListOf<Violation>()
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
        return Outcome(violations,soft)
    }

    /** Parses a unified diff/patch and returns the added lines per file. */
    fun parsePatch(text:String):List<DiffChange> {
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

    /** Working-tree diff vs HEAD (tracked files) with added-line numbers. */
    fun worktreeChanges(root:Path):List<DiffChange> {
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

    private fun evaluateCheck(
        ruleId:String,check:RuleCheck,changes:List<DiffChange>,
        matchers:(String)->java.nio.file.PathMatcher?,
        violations:MutableList<Violation>
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
                    violations+=Violation(
                        ruleId,"path-forbidden",change.path,change.addedLines.firstOrNull()?.first,
                        check.pattern,check.note
                    )
                }
            }
            RuleCheckKind.PATH_REQUIRED -> {
                if (changes.none { pathMatches(it.path) }) {
                    violations+=Violation(ruleId,"path-required",null,null,check.pattern,check.note)
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
                    violations+=Violation(
                        ruleId,"regex-required",first.path,first.addedLines.firstOrNull()?.first,
                        check.pattern,check.note
                    )
                }
            }
            RuleCheckKind.REGEX_FORBIDDEN -> {
                val regex=runCatching { Regex(check.pattern) }.getOrNull() ?: return
                changes.filter { fileApplies(it.path) }.forEach { change ->
                    val hit=change.addedLines.firstOrNull { (_,text) -> regex.containsMatchIn(text) } ?: return@forEach
                    violations+=Violation(ruleId,"regex-forbidden",change.path,hit.first,check.pattern,check.note)
                }
            }
        }
    }
}
