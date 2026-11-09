package org.ftckb.standardizer

import java.nio.file.FileSystems
import java.nio.file.PathMatcher

// Compiles rule path globs (appliesTo, path-* patterns, review-trigger paths) with the
// conventional globstar meaning: a standalone double-star directory segment may also stand
// for zero directories. java.nio's glob syntax alone requires at least one slash there, so
// the Dashboard pin rule's gradle-file glob silently skipped a root-level
// build.dependencies.gradle while still matching TeamCode/build.gradle.
internal object RuleGlobs {
    private const val MAX_OPTIONAL_SEGMENTS=4
    private const val SEGMENT="**/"

    /** Returns null for an invalid glob, matching the previous behaviour of the callers. */
    fun compile(glob:String):PathMatcher? {
        val fileSystem=FileSystems.getDefault()
        val primary=runCatching { fileSystem.getPathMatcher("glob:$glob") }.getOrNull() ?: return null
        val alternatives=zeroDirectoryVariants(glob).mapNotNull { variant ->
            runCatching { fileSystem.getPathMatcher("glob:$variant") }.getOrNull()
        }
        if (alternatives.isEmpty()) return primary
        return PathMatcher { path -> primary.matches(path) || alternatives.any { it.matches(path) } }
    }

    // Every way of dropping one or more standalone double-star directory segments, so that
    // "a/<double-star>/b" also yields "a/b". Segments inside brace groups or directly after
    // other characters are left alone; globs with more than MAX_OPTIONAL_SEGMENTS such
    // segments keep plain java.nio semantics.
    internal fun zeroDirectoryVariants(glob:String):List<String> {
        val starts=optionalSegmentStarts(glob)
        if (starts.isEmpty() || starts.size>MAX_OPTIONAL_SEGMENTS) return emptyList()
        return (1 until (1 shl starts.size)).map { mask ->
            buildString(glob.length) {
                var offset=0
                starts.forEachIndexed { index,start ->
                    if (mask and (1 shl index)!=0) {
                        append(glob,offset,start)
                        offset=start+SEGMENT.length
                    }
                }
                append(glob,offset,glob.length)
            }
        }.filter { it.isNotEmpty() && it!=glob }.distinct()
    }

    private fun optionalSegmentStarts(glob:String):List<Int> {
        val starts=mutableListOf<Int>()
        var index=glob.indexOf(SEGMENT)
        while (index>=0) {
            if (index==0 || glob[index-1]=='/') starts+=index
            index=glob.indexOf(SEGMENT,index+SEGMENT.length)
        }
        return starts
    }
}
