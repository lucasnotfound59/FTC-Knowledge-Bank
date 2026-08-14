package org.ftckb.repository

import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.Locale
import org.eclipse.jgit.ignore.IgnoreNode

class GitIgnoreRules private constructor(private val nodes:List<IgnoreNodeAtPath>) {
    fun isIgnored(relativePath:String,isDirectory:Boolean):Boolean {
        val relative=Path.of(relativePath).normalize()
        require(!relative.isAbsolute && !relative.startsWith("..")) { "Path must stay within the repository" }
        (0 until relative.nameCount-1).forEach { index ->
            if (evaluate(relative.subpath(0,index+1),true)==true) return true
        }
        return evaluate(relative,isDirectory)==true
    }

    private fun evaluate(relative:Path,isDirectory:Boolean):Boolean? {
        var ignored:Boolean?=null
        nodes.forEach { entry ->
            if (entry.directory.toString().isNotEmpty() && !relative.startsWith(entry.directory)) return@forEach
            val pathFromOwner=entry.directory.relativize(relative).invariantSeparatorsPathString()
            if (pathFromOwner.isEmpty()) return@forEach
            val decision=entry.node.checkIgnored(pathFromOwner,isDirectory)
            if (decision!=null) ignored=decision
        }
        return ignored
    }

    companion object {
        private const val maxIgnoreFileBytes=1_048_576L

        fun load(
            root:Path,
            limits:RepositoryTraversalLimits=RepositoryTraversalLimits()
        ):GitIgnoreRules {
            val normalizedRoot=root.toRealPath()
            val nodes=mutableListOf<IgnoreNodeAtPath>()
            var visitedFiles=0
            var visitedBytes=0L
            Files.walkFileTree(normalizedRoot,object:SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(directory:Path,attributes:BasicFileAttributes):FileVisitResult {
                    if (directory!=normalizedRoot && normalizedRoot.relativize(directory).nameCount>limits.maxDepth) {
                        throw RepositoryTraversalException("gitignore traversal exceeds depth limit")
                    }
                    if (directory!=normalizedRoot && Files.isSymbolicLink(directory)) return FileVisitResult.SKIP_SUBTREE
                    if (directory!=normalizedRoot && directory.fileName.toString().lowercase(Locale.ROOT) in protectedDirectories) return FileVisitResult.SKIP_SUBTREE
                    if (directory!=normalizedRoot) {
                        val relative=normalizedRoot.relativize(directory)
                        if (GitIgnoreRules(nodes.toList()).isIgnored(relative.invariantSeparatorsPathString(),true)) {
                            return FileVisitResult.SKIP_SUBTREE
                        }
                    }
                    loadIgnoreNode(normalizedRoot,directory,nodes)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file:Path,attributes:BasicFileAttributes):FileVisitResult {
                    if (normalizedRoot.relativize(file).nameCount>limits.maxDepth) {
                        throw RepositoryTraversalException("gitignore traversal exceeds depth limit")
                    }
                    visitedFiles++
                    if (visitedFiles>limits.maxFiles) {
                        throw RepositoryTraversalException("gitignore traversal exceeds file-count limit")
                    }
                    visitedBytes=safeAdd(visitedBytes,attributes.size())
                    if (visitedBytes>limits.maxTotalBytes) {
                        throw RepositoryTraversalException("gitignore traversal exceeds byte-count limit")
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file:Path,error:IOException):FileVisitResult {
                    if (file==normalizedRoot) throw RepositoryAccessException()
                    return FileVisitResult.CONTINUE
                }
            })
            return GitIgnoreRules(nodes.sortedWith(compareBy({ it.directory.nameCount },{ it.directory.invariantSeparatorsPathString() })))
        }

        private fun loadIgnoreNode(root:Path,directory:Path,nodes:MutableList<IgnoreNodeAtPath>) {
            val ignoreFile=directory.resolve(".gitignore")
            if (!Files.isRegularFile(ignoreFile,java.nio.file.LinkOption.NOFOLLOW_LINKS)) return
            val text=readBoundedTextNoFollow(ignoreFile,maxIgnoreFileBytes)?.text ?: return
            val node=IgnoreNode()
            try {
                node.parse(ByteArrayInputStream(text.toByteArray(StandardCharsets.UTF_8)))
            } catch (_:Exception) {
                return
            }
            nodes+=IgnoreNodeAtPath(root.relativize(directory),node)
        }

        private fun safeAdd(left:Long,right:Long):Long=
            if (Long.MAX_VALUE-left<right) Long.MAX_VALUE else left+right

        private val protectedDirectories=setOf(".git",".gradle","build","generated",".idea",".vscode","out","target","node_modules")
    }
}

private data class IgnoreNodeAtPath(val directory:Path,val node:IgnoreNode)

private fun Path.invariantSeparatorsPathString():String=toString().replace('\\','/')
