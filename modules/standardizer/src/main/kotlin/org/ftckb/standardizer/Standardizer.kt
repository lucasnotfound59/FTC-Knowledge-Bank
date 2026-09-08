package org.ftckb.standardizer

import java.nio.file.FileSystems
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.nio.file.Path
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.dircache.DirCacheIterator
import org.eclipse.jgit.patch.FileHeader
import org.eclipse.jgit.patch.Patch
import org.eclipse.jgit.treewalk.AbstractTreeIterator
import org.eclipse.jgit.treewalk.CanonicalTreeParser
import org.eclipse.jgit.treewalk.EmptyTreeIterator
import org.eclipse.jgit.treewalk.FileTreeIterator
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
        val bytes=text.toByteArray(Charsets.UTF_8)
        val patch=Patch().apply { parse(ByteArrayInputStream(bytes)) }
        require(patch.errors.isEmpty()) { "malformed patch: ${patch.errors.joinToString("; ")}" }
        require(text.isBlank() || patch.files.isNotEmpty()) { "malformed patch: no file headers" }
        return mergeChanges(patch.files.flatMap(::changesFromFileHeader))
    }

    /** Union of HEAD-to-index and HEAD-to-working-tree changes. */
    fun worktreeChanges(root:Path):List<DiffChange> {
        val git=Git.open(root.toFile())
        git.use {
            val repository=git.repository
            repository.newObjectReader().use { reader ->
                val head=repository.resolve("HEAD^{tree}")?.let {
                    CanonicalTreeParser().apply { reset(reader,it) }
                } ?: EmptyTreeIterator()
                val changes=mutableListOf<DiffChange>()
                changes+=diffChanges(repository,head,DirCacheIterator(repository.readDirCache()))
                val worktreeHead=repository.resolve("HEAD^{tree}")?.let {
                    CanonicalTreeParser().apply { reset(reader,it) }
                } ?: EmptyTreeIterator()
                changes+=diffChanges(repository,worktreeHead,FileTreeIterator(repository))
                return mergeChanges(changes)
            }
        }
    }

    private fun diffChanges(
        repository:org.eclipse.jgit.lib.Repository,
        oldTree:AbstractTreeIterator,
        newTree:AbstractTreeIterator
    ):List<DiffChange> {
        val output=ByteArrayOutputStream()
        DiffFormatter(output).use { formatter ->
            formatter.setRepository(repository)
            formatter.setDetectRenames(true)
            formatter.setQuotePaths(false)
            val entries=formatter.scan(oldTree,newTree)
            formatter.format(entries)
        }
        return parsePatch(output.toString(Charsets.UTF_8))
    }

    private fun changesFromFileHeader(header:FileHeader):List<DiffChange> {
        val added=mutableListOf<Pair<Int,String>>()
        var newLine=0
        var inHunk=false
        val hunkHeader=Regex("^@@ -[0-9]+(?:,[0-9]+)? \\+([0-9]+)(?:,[0-9]+)? @@")
        header.scriptText.lineSequence().forEach { line ->
            val match=hunkHeader.find(line)
            require(!line.startsWith("@@") || match!=null) { "malformed patch: invalid hunk header" }
            when {
                match!=null -> {
                    newLine=match.groupValues[1].toInt()
                    inHunk=true
                }
                !inHunk -> Unit
                line.startsWith("+") -> {
                    added+=newLine to line.substring(1)
                    newLine++
                }
                line.startsWith("-") || line=="\\ No newline at end of file" -> Unit
                else -> newLine++
            }
        }
        val paths=linkedSetOf<String>()
        if (header.oldPath!=DiffEntry.DEV_NULL) paths+=header.oldPath
        if (header.newPath!=DiffEntry.DEV_NULL) paths+=header.newPath
        return paths.map { path ->
            DiffChange(path,if (path==header.newPath) added else emptyList())
        }
    }

    private fun mergeChanges(changes:List<DiffChange>):List<DiffChange> =changes
        .groupBy { it.path }
        .map { (path,items) ->
            DiffChange(path,items.flatMap { it.addedLines }.distinct().sortedWith(compareBy({ it.first },{ it.second })))
        }
        .sortedBy { it.path }

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
