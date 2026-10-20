package org.ftckb.standardizer

import java.nio.file.FileSystems
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Path
import java.security.MessageDigest
import org.eclipse.jgit.dircache.DirCache
import org.eclipse.jgit.dircache.DirCacheEntry
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.ObjectInserter
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.treewalk.TreeWalk
import org.eclipse.jgit.treewalk.WorkingTreeIterator
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
                if (rule.reviewTriggers.isEmpty() || reviewTriggered(rule,changes,matchers)) {
                    soft+=rule.id to rule.instruction
                }
            } else {
                rule.checks.forEach { check -> evaluateCheck(rule.id,check,changes,matchers,violations) }
            }
        }
        return Outcome(violations,soft.distinctBy { it.first }.sortedBy { it.first })
    }

    /** DEV work mode: hard findings never disappear. Each one becomes a deterministic soft
     * notice whose note keeps the original check kind and location so an Agent can report
     * every downgraded item instead of presenting the patch as production-compliant. */
    fun downgradeToSoft(violations:List<Violation>):List<Pair<String,String>> {
        return violations
            .sortedWith(compareBy({ it.ruleId },{ it.path.orEmpty() },{ it.line ?: 0 },{ it.check },{ it.pattern },{ it.detail }))
            .map { violation ->
                violation.ruleId to buildString {
                    append("work-mode=dev; downgraded hard check=").append(violation.check)
                    violation.path?.let { append(" path=").append(it) }
                    violation.line?.let { append(" line=").append(it) }
                    append(" pattern=").append(violation.pattern)
                    append(" detail=").append(violation.detail)
                }
            }
    }

    private fun reviewTriggered(
        rule:KnowledgeRule,
        changes:List<DiffChange>,
        matchers:(String)->java.nio.file.PathMatcher?
    ):Boolean=rule.reviewTriggers.any { trigger ->
        val regexes=trigger.addedLinePatterns.mapNotNull { pattern ->
            runCatching { Regex(pattern) }.getOrNull()
        }
        trigger.paths.any { glob ->
            val matcher=matchers(glob)
            matcher!=null && changes.any { change ->
                matcher.matches(Path.of(change.path)) && when {
                    trigger.addedLinePatterns.isEmpty() -> true
                    regexes.isEmpty() -> false
                    else -> change.addedLines.any { (_,text) -> regexes.any { it.containsMatchIn(text) } }
                }
            }
        }
    }

    /** Parses a unified diff/patch and returns the added lines per file. */
    fun parsePatch(text:String):List<DiffChange> {
        val bytes=text.toByteArray(Charsets.UTF_8)
        val patch=Patch().apply { parse(ByteArrayInputStream(bytes)) }
        require(patch.errors.isEmpty()) { "malformed patch: ${patch.errors.joinToString("; ")}" }
        require(text.isBlank() || patch.files.isNotEmpty()) { "malformed patch: no file headers" }
        return mergeChanges(patch.files.flatMap(::changesFromFileHeader))
    }

    data class CapturedWorktree(
        val changes:List<DiffChange>,
        val patches:List<String>,
        val headBefore:String?,
        val headAfter:String?,
        val repositoryIdentity:String,
        val contentDigests:List<List<String?>> =emptyList()
    )

    /** Union of HEAD-to-index and HEAD-to-working-tree changes. */
    fun worktreeChanges(root:Path):List<DiffChange> =captureWorktree(root).changes

    /** Freeze object bytes in memory before rendering. Neither fingerprinting nor evaluation
     * rereads mutable working files, and no temporary blobs are written to the user's Git. */
    fun captureWorktree(root:Path):CapturedWorktree=Git.open(root.toFile()).use { git ->
        val repository=git.repository
        try {
            val before=repository.resolve("HEAD")?.name
            val identity=repository.workTree.toPath().toRealPath().toString()
            InMemoryRepository(DfsRepositoryDescription("standardizer-capture")).use { frozen ->
                // Retain source diff settings (context, prefixes, rename limits, etc.).
                frozen.config.fromText(repository.config.toText())
                frozen.newObjectInserter().use { inserter ->
                    val budget=CaptureBudget()
                    val head=freezeHead(repository,inserter,budget)
                    val index=freezeIndex(repository,inserter,budget)
                    val tracked=(0 until head.entryCount).map { head.getEntry(it).pathString }.toSet()+
                        (0 until index.entryCount).map { index.getEntry(it).pathString }
                    val worktree=freezeWorktree(repository,inserter,tracked,budget)
                    inserter.flush()
                    val staged=capturedDiff(frozen,DirCacheIterator(head),DirCacheIterator(index))
                    val working=capturedDiff(frozen,DirCacheIterator(head),DirCacheIterator(worktree))
                    CapturedWorktree(mergeChanges(staged.changes+working.changes),listOf(staged.patch,working.patch),
                        before,repository.resolve("HEAD")?.name,identity,staged.contentDigests+working.contentDigests)
                }
            }
        } catch (_:Exception) {
            // Enrichment is observational. If a stable frozen snapshot is unavailable, retain
            // the original check behavior and deliberately omit a comparable version.
            repository.newObjectReader().use { reader ->
                fun head():AbstractTreeIterator=repository.resolve("HEAD^{tree}")?.let {
                    CanonicalTreeParser().apply { reset(reader,it) }
                } ?: EmptyTreeIterator()
                val staged=capturedDiff(repository,head(),DirCacheIterator(repository.readDirCache()),false)
                val working=capturedDiff(repository,head(),FileTreeIterator(repository),false)
                CapturedWorktree(mergeChanges(staged.changes+working.changes),emptyList(),null,null,"")
            }
        }
    }

    private fun freezeHead(repository:Repository,inserter:ObjectInserter,budget:CaptureBudget):DirCache {
        val cache=DirCache.newInCore()
        val builder=cache.builder()
        repository.resolve("HEAD^{tree}")?.let { tree ->
            TreeWalk(repository).use { walk ->
                walk.addTree(tree)
                walk.isRecursive=true
                while (walk.next()) builder.add(frozenEntry(repository,inserter,budget,walk.pathString,walk.getFileMode(0),walk.getObjectId(0)))
            }
        }
        builder.finish()
        return cache
    }

    private fun freezeIndex(repository:Repository,inserter:ObjectInserter,budget:CaptureBudget):DirCache {
        val original=repository.readDirCache()
        val cache=DirCache.newInCore()
        val builder=cache.builder()
        for (i in 0 until original.entryCount) {
            val entry=original.getEntry(i)
            // Unmerged entries keep their stage, so JGit retains its normal conflict behavior.
            builder.add(frozenEntry(repository,inserter,budget,entry.pathString,entry.fileMode,entry.objectId,entry.stage))
        }
        builder.finish()
        return cache
    }

    private fun frozenEntry(repository:Repository,inserter:ObjectInserter,budget:CaptureBudget,path:String,mode:FileMode,id:ObjectId,stage:Int=0):DirCacheEntry {
        budget.reserve(256)
        val frozenId=if (mode.objectType==Constants.OBJ_BLOB) {
            val objectLoader=repository.open(id)
            // ObjectLoader.bytes allocates the whole decompressed blob: check before it does.
            budget.reserve(objectLoader.size)
            inserter.insert(Constants.OBJ_BLOB,objectLoader.bytes)
        } else id
        return DirCacheEntry(path,stage).apply { fileMode=mode;setObjectId(frozenId) }
    }

    private fun freezeWorktree(repository:Repository,inserter:ObjectInserter,tracked:Set<String>,budget:CaptureBudget):DirCache {
        val cache=DirCache.newInCore()
        val builder=cache.builder()
        TreeWalk(repository).use { walk ->
            walk.addTree(FileTreeIterator(repository))
            while (walk.next()) {
                val iterator=walk.getTree(0,WorkingTreeIterator::class.java) ?: continue
                val path=walk.pathString
                if (walk.isSubtree) {
                    if (!iterator.isEntryIgnored || tracked.any { it.startsWith("$path/") }) walk.enterSubtree()
                    continue
                }
                if (iterator.isEntryIgnored && path !in tracked) continue
                budget.reserve(256)
                val mode=walk.getFileMode(0)
                val id=if (mode.objectType==Constants.OBJ_BLOB) {
                    budget.ensureFits(iterator.entryLength)
                    iterator.openEntryStream().use { stream -> inserter.insert(Constants.OBJ_BLOB,budget.read(stream)) }
                } else iterator.entryObjectId
                builder.add(DirCacheEntry(path).apply { fileMode=mode;setObjectId(id) })
            }
        }
        builder.finish()
        return cache
    }

    private data class CapturedDiff(val changes:List<DiffChange>,val patch:String,val contentDigests:List<List<String?>>)
    private fun capturedDiff(repository:Repository,oldTree:AbstractTreeIterator,newTree:AbstractTreeIterator,digests:Boolean=true):CapturedDiff {
        val output=ByteArrayOutputStream()
        val entries=DiffFormatter(output).use { formatter ->
            formatter.setRepository(repository)
            formatter.setDetectRenames(true)
            formatter.setQuotePaths(false)
            formatter.scan(oldTree,newTree).also { formatter.format(it) }
        }
        val patch=output.toString(Charsets.UTF_8)
        val contents=if (!digests) emptyList() else entries.flatMap { entry ->
            val oldHash=contentHash(repository,entry.oldMode,entry.oldId.toObjectId())
            val newHash=contentHash(repository,entry.newMode,entry.newId.toObjectId())
            listOf(entry.oldPath,entry.newPath).filter { it!=DiffEntry.DEV_NULL }.distinct().map { path ->
                listOf(path,
                    if (path==entry.oldPath) Integer.toOctalString(entry.oldMode.bits) else null,
                    if (path==entry.newPath) Integer.toOctalString(entry.newMode.bits) else null,
                    if (path==entry.oldPath) oldHash else null,
                    if (path==entry.newPath) newHash else null)
            }
        }
        return CapturedDiff(parsePatch(patch),patch,contents)
    }

    private fun contentHash(repository:Repository,mode:FileMode,id:ObjectId):String? {
        if (mode==FileMode.MISSING) return null
        val digest=MessageDigest.getInstance("SHA-256")
        if (mode.objectType==Constants.OBJ_BLOB) repository.open(id).openStream().use { stream ->
            val buffer=ByteArray(8192)
            while (true) {
                val count=stream.read(buffer)
                if (count<0) break
                digest.update(buffer,0,count)
            }
        } else digest.update(id.name.toByteArray(Charsets.UTF_8))
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Enrichment must not turn an unchanged large binary into a heap failure. Keep ample
     * room for JGit's compression, diff rendering and the original checker fallback. */
    private class CaptureBudget {
        private val runtime=Runtime.getRuntime()
        private val headroom=(runtime.maxMemory()-(runtime.totalMemory()-runtime.freeMemory())).coerceAtLeast(0)
        private val limit=minOf(16L*1024*1024,runtime.maxMemory()/32,headroom/8)
        private var used=0L
        fun ensureFits(size:Long) { check(size>=0 && size<=limit-used) { "checked input capture exceeds memory budget" } }
        fun reserve(size:Long) { ensureFits(size);used+=size }
        fun read(stream:InputStream):ByteArray {
            val output=ByteArrayOutputStream()
            val buffer=ByteArray(8192)
            while (true) {
                val count=stream.read(buffer)
                if (count<0) break
                reserve(count.toLong())
                output.write(buffer,0,count)
            }
            return output.toByteArray()
        }
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
                    val hit=regexHit(regex,change.addedLines) ?: return@forEach
                    violations+=Violation(ruleId,"regex-forbidden",change.path,hit.first,check.pattern,check.note)
                }
            }
        }
    }

    private fun regexHit(regex:Regex,lines:List<Pair<Int,String>>):Pair<Int,String>? {
        lines.firstOrNull { (_,text) -> regex.containsMatchIn(text) }?.let { return it }
        val blocks=mutableListOf<MutableList<Pair<Int,String>>>()
        lines.forEach { line ->
            val block=blocks.lastOrNull()
            if (block==null || line.first!=block.last().first+1) blocks+=mutableListOf(line)
            else block+=line
        }
        blocks.forEach { block ->
            val text=block.joinToString("\n") { it.second }
            val match=regex.find(text) ?: return@forEach
            val lineIndex=text.substring(0,match.range.first).count { it=='\n' }
            return block[lineIndex]
        }
        return null
    }
}
