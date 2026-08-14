package org.ftckb.repository

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.Locale

class SafeRepositoryWalker(root:Path) {
    private val root=root.toRealPath()

    fun walk():List<SafeTextFile> {
        val ignoreRules=GitIgnoreRules.load(root)
        val files=mutableListOf<SafeTextFile>()
        Files.walkFileTree(root,object:SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(directory:Path,attributes:BasicFileAttributes):FileVisitResult {
                if (directory==root) return FileVisitResult.CONTINUE
                if (Files.isSymbolicLink(directory)) return FileVisitResult.SKIP_SUBTREE
                val relative=relativePath(directory)
                if (hasProtectedSegment(relative) || ignoreRules.isIgnored(relative,true)) return FileVisitResult.SKIP_SUBTREE
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file:Path,attributes:BasicFileAttributes):FileVisitResult {
                if (!attributes.isRegularFile || Files.isSymbolicLink(file)) return FileVisitResult.CONTINUE
                val safe=readFile(file,ignoreRules)
                if (safe!=null) files+=safe
                return FileVisitResult.CONTINUE
            }
        })
        return files.sortedBy { it.path }
    }

    fun readRelative(relativePath:String):SafeTextFile? {
        val relative=Path.of(relativePath).normalize()
        if (relative.isAbsolute || relative.startsWith("..")) return null
        val file=root.resolve(relative).normalize()
        if (!file.startsWith(root) || !Files.isRegularFile(file) || Files.isSymbolicLink(file)) return null
        return readFile(file,GitIgnoreRules.load(root))
    }

    private fun readFile(file:Path,ignoreRules:GitIgnoreRules):SafeTextFile? {
        val relative=relativePath(file)
        if (hasProtectedSegment(relative) || isProtectedFile(file.fileName.toString()) || !isApprovedExtension(file.fileName.toString())) return null
        if (ignoreRules.isIgnored(relative,false) || Files.size(file)>maxFileBytes) return null
        val realFile=try { file.toRealPath() } catch (_:Exception) { return null }
        if (!realFile.startsWith(root)) return null
        val bytes=try { Files.readAllBytes(file) } catch (_:Exception) { return null }
        if (bytes.size>maxFileBytes || bytes.any { it==0.toByte() }) return null
        val text=decodeUtf8(bytes) ?: return null
        return SafeTextFile(relative,bytes,text)
    }

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

    private fun decodeUtf8(bytes:ByteArray):String?=try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_:CharacterCodingException) {
        null
    }

    companion object {
        const val maxFileBytes=1_048_576L
        private val approvedExtensions=setOf("java","kt","gradle","xml","yaml","yml","properties","md")
        private val protectedExtensions=setOf("jks","keystore","p12","pfx","pem","key","der","crt")
        private val protectedDirectories=setOf(".git",".gradle","build","generated",".idea",".vscode","out","target","node_modules")
    }
}

data class SafeTextFile(val path:String,val bytes:ByteArray,val text:String)

private fun Path.invariantSeparatorsPathString():String=toString().replace('\\','/')
