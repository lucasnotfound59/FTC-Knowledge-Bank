package org.ftckb.repository

import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.Locale

class SafeRepositoryWalker(root:Path,private val limits:RepositoryTraversalLimits=RepositoryTraversalLimits()) {
    private val root=root.toRealPath()

    fun walk():List<SafeTextFile> {
        val ignoreRules=GitIgnoreRules.load(root)
        val files=mutableListOf<SafeTextFile>()
        var visitedFiles=0
        var visitedBytes=0L
        Files.walkFileTree(root,object:SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(directory:Path,attributes:BasicFileAttributes):FileVisitResult {
                if (directory==root) return FileVisitResult.CONTINUE
                enforceDepth(directory)
                if (Files.isSymbolicLink(directory)) return FileVisitResult.SKIP_SUBTREE
                val relative=relativePath(directory)
                if (hasProtectedSegment(relative) || ignoreRules.isIgnored(relative,true)) return FileVisitResult.SKIP_SUBTREE
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file:Path,attributes:BasicFileAttributes):FileVisitResult {
                enforceDepth(file)
                visitedFiles++
                if (visitedFiles>limits.maxFiles) throw RepositoryTraversalException("repository traversal exceeds file-count limit")
                visitedBytes=safeAdd(visitedBytes,attributes.size())
                if (visitedBytes>limits.maxTotalBytes) throw RepositoryTraversalException("repository traversal exceeds byte-count limit")
                if (!attributes.isRegularFile || Files.isSymbolicLink(file)) return FileVisitResult.CONTINUE
                val safe=readFile(file,ignoreRules)
                if (safe!=null) files+=safe
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file:Path,error:IOException):FileVisitResult {
                if (file==root) throw RepositoryAccessException()
                return FileVisitResult.CONTINUE
            }
        })
        return files.sortedBy { it.path }
    }

    fun readRelative(relativePath:String):SafeTextFile? {
        val relative=Path.of(relativePath).normalize()
        if (relative.isAbsolute || relative.startsWith("..")) return null
        if (relative.nameCount>limits.maxDepth) return null
        val file=root.resolve(relative).normalize()
        if (!file.startsWith(root) || hasSymbolicLinkComponent(relative)) return null
        if (!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)) return null
        return readFile(file,GitIgnoreRules.load(root))
    }

    private fun hasSymbolicLinkComponent(relative:Path):Boolean {
        var current=root
        relative.forEach { component ->
            current=current.resolve(component)
            if (Files.isSymbolicLink(current)) return true
        }
        return false
    }

    private fun readFile(file:Path,ignoreRules:GitIgnoreRules):SafeTextFile? {
        val relative=relativePath(file)
        if (hasProtectedSegment(relative) || isProtectedFile(file.fileName.toString()) || !isApprovedExtension(file.fileName.toString())) return null
        if (ignoreRules.isIgnored(relative,false)) return null
        val read=readBoundedTextNoFollow(file,maxFileBytes) ?: return null
        return SafeTextFile(relative,read.sha256,read.text,read.byteCount)
    }

    private fun enforceDepth(path:Path) {
        if (root.relativize(path).nameCount>limits.maxDepth) {
            throw RepositoryTraversalException("repository traversal exceeds depth limit")
        }
    }

    private fun safeAdd(left:Long,right:Long):Long=if (Long.MAX_VALUE-left<right) Long.MAX_VALUE else left+right

    private fun relativePath(path:Path):String=root.relativize(path).invariantSeparatorsPathString()

    private fun hasProtectedSegment(relative:String):Boolean=relative.split('/').any { it.lowercase(Locale.ROOT) in protectedDirectories }

    private fun isProtectedFile(name:String):Boolean {
        val normalized=name.lowercase(Locale.ROOT)
        val extension=normalized.substringAfterLast('.',"")
        return normalized==".env" || normalized.startsWith(".env.") || normalized=="local.properties" || extension in protectedExtensions
    }

    private fun isApprovedExtension(name:String):Boolean {
        val normalized=name.lowercase(Locale.ROOT)
        return normalized.endsWith(".gradle.kts") || normalized.substringAfterLast('.',"") in approvedExtensions
    }

    companion object {
        const val maxFileBytes=1_048_576L
        private val approvedExtensions=setOf("java","kt","gradle","xml","yaml","yml","properties","md")
        private val protectedExtensions=setOf("jks","keystore","p12","pfx","pem","key","der","crt")
        private val protectedDirectories=setOf(".git",".gradle","build","generated",".idea",".vscode","out","target","node_modules")
    }
}

class SafeTextFile(val path:String,val sha256:String,val text:String,val byteCount:Long)

private fun Path.invariantSeparatorsPathString():String=toString().replace('\\','/')
