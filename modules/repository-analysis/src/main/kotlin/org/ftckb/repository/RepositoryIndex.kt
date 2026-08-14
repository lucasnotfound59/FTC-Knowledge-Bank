package org.ftckb.repository

import java.nio.file.FileSystems
import java.nio.file.Path
import java.util.Locale

class RepositoryIndex(private val traversalLimits:RepositoryTraversalLimits=RepositoryTraversalLimits()) {
    private var current:RepositorySnapshot?=null

    fun build(root:Path):RepositorySnapshot {
        val normalizedRoot=root.toRealPath()
        val documents=SafeRepositoryWalker(normalizedRoot,traversalLimits).walk()
            .map { toDocument(it) }
            .associateByTo(linkedMapOf()) { it.path }
        return RepositorySnapshot(
            normalizedRoot,FtcProjectDetector.detect(normalizedRoot,traversalLimits),immutableMapCopy(documents)
        ).also { current=it }
    }

    fun search(query:LocalQuery,limit:Int):List<SourceFragment> {
        if (limit<=0) return emptyList()
        val snapshot=current ?: return emptyList()
        val terms=query.terms.map { it.lowercase(Locale.ROOT) }.filter { it.isNotBlank() }.toSet()
        val symbols=query.symbols.filter { it.isNotBlank() }.toSet()
        val matchers=query.pathGlobs.mapNotNull { glob ->
            runCatching { FileSystems.getDefault().getPathMatcher("glob:$glob") }.getOrNull()
        }
        if (terms.isEmpty() && symbols.isEmpty() && matchers.isEmpty()) return emptyList()
        return snapshot.documents.values.flatMap { document ->
            val pathMatch=matchers.any { it.matches(Path.of(document.path)) }
            val matchingLines=document.lines.indices.filter { line ->
                pathMatch || terms.any { term -> document.lines[line].lowercase(Locale.ROOT).contains(term) } ||
                    symbols.any { symbol -> hasSymbol(document.lines[line],symbol) }
            }
            if (matchingLines.isEmpty()) emptyList() else {
                val score=score(document,terms,symbols,pathMatch)
                fragmentRanges(matchingLines,document.lines.size).map { range ->
                    SourceFragment(
                        document.path,
                        range.first+1,
                        range.last+1,
                        document.sha256,
                        document.lines.subList(range.first,range.last+1).joinToString("\n"),
                        score
                    )
                }
            }
        }.sortedWith(compareByDescending<SourceFragment> { it.score }.thenBy { it.path }.thenBy { it.startLine }).take(limit)
    }

    fun refresh(changedPaths:Set<String>):RepositorySnapshot {
        val snapshot=current ?: error("Build the repository index before refreshing it")
        if (changedPaths.isEmpty()) return snapshot
        if (changedPaths.any { it.endsWith(".gitignore") }) return build(snapshot.root)
        val walker=SafeRepositoryWalker(snapshot.root,traversalLimits)
        val updated=snapshot.documents.toMutableMap()
        changedPaths.mapNotNull { safeRelativePath(it) }.forEach { path ->
            val file=walker.readRelative(path)
            if (file==null) updated.remove(path) else updated[path]=toDocument(file)
        }
        val stableDocuments=updated.toSortedMap().toMap(linkedMapOf())
        return RepositorySnapshot(
            snapshot.root,FtcProjectDetector.detect(snapshot.root,traversalLimits),immutableMapCopy(stableDocuments)
        ).also { current=it }
    }

    fun currentSha256(path:String):String? {
        val snapshot=current ?: return null
        val exactPath=exactRelativePath(path) ?: return null
        return runCatching {
            SafeRepositoryWalker(snapshot.root,traversalLimits).readRelative(exactPath)?.sha256
        }.getOrNull()
    }

    private fun toDocument(file:SafeTextFile):IndexedDocument=IndexedDocument(
        file.path,
        file.sha256,
        file.text,
        file.text.split("\n"),
        TOKEN.findAll(file.text).map { it.value.lowercase(Locale.ROOT) }.toSet()
    )

    private fun score(document:IndexedDocument,terms:Set<String>,symbols:Set<String>,pathMatch:Boolean):Int {
        val text=document.text.lowercase(Locale.ROOT)
        val termScore=minOf(9_999,terms.count { it in text })*10
        val symbolScore=if (symbols.any { hasSymbol(document.text,it) }) 100_000 else 0
        val pathScore=if (pathMatch) 200_000 else 0
        return pathScore+symbolScore+termScore
    }

    private fun fragmentRanges(hits:List<Int>,lineCount:Int):List<IntRange> {
        val ranges=mutableListOf<IntRange>()
        hits.forEach { hit ->
            val next=(maxOf(0,hit-5)..minOf(lineCount-1,hit+5))
            val previous=ranges.lastOrNull()
            if (previous!=null && next.first<=previous.last+1 && next.last-previous.first+1<=80) {
                ranges[ranges.lastIndex]=previous.first..next.last
            } else {
                ranges+=next
            }
        }
        return ranges
    }

    private fun safeRelativePath(path:String):String?=runCatching {
        val normalized=Path.of(path).normalize()
        if (normalized.isAbsolute || normalized.startsWith("..")) null else normalized.invariantSeparatorsPathString()
    }.getOrNull()

    private fun exactRelativePath(path:String):String? {
        if (path.isBlank() || '\\' in path) return null
        val normalized=safeRelativePath(path) ?: return null
        return normalized.takeIf { it==path }
    }

    private fun hasSymbol(text:String,symbol:String):Boolean=Regex("(?<![A-Za-z0-9_])${Regex.escape(symbol)}(?![A-Za-z0-9_])").containsMatchIn(text)

    companion object {
        private val TOKEN=Regex("[A-Za-z_][A-Za-z0-9_]*")
    }
}

private fun Path.invariantSeparatorsPathString():String=toString().replace('\\','/')
