package org.ftckb.repository

import java.io.ByteArrayInputStream
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

        fun load(root:Path):GitIgnoreRules {
            val normalizedRoot=root.toRealPath()
            val nodes=mutableListOf<IgnoreNodeAtPath>()
            Files.walkFileTree(normalizedRoot,object:SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(directory:Path,attributes:BasicFileAttributes):FileVisitResult {
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

            })
            return GitIgnoreRules(nodes.sortedWith(compareBy({ it.directory.nameCount },{ it.directory.invariantSeparatorsPathString() })))
        }

        private fun loadIgnoreNode(root:Path,directory:Path,nodes:MutableList<IgnoreNodeAtPath>) {
            val ignoreFile=directory.resolve(".gitignore")
            if (!Files.isRegularFile(ignoreFile) || Files.isSymbolicLink(ignoreFile) || Files.size(ignoreFile)>maxIgnoreFileBytes) return
            val bytes=try { Files.readAllBytes(ignoreFile) } catch (_:Exception) { return }
            if (bytes.size>maxIgnoreFileBytes || bytes.any { it==0.toByte() }) return
            val node=IgnoreNode()
            try {
                node.parse(ByteArrayInputStream(bytes))
            } catch (_:Exception) {
                return
            }
            nodes+=IgnoreNodeAtPath(root.relativize(directory),node)
        }

        private val protectedDirectories=setOf(".git",".gradle","build","generated",".idea",".vscode","out","target","node_modules")
    }
}

private data class IgnoreNodeAtPath(val directory:Path,val node:IgnoreNode)

private fun Path.invariantSeparatorsPathString():String=toString().replace('\\','/')
