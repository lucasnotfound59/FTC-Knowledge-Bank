package org.ftckb.agent.edit

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.Locale

enum class EditScope { NORMAL,PROJECT_LEVEL }

data class ResolvedEditPath(val relative:String,val absolute:Path,val scope:EditScope)

class SafeEditPath(root:Path) {
    private val root=root.toRealPath()

    fun resolve(value:String):ResolvedEditPath {
        require(value.isNotBlank() && value.length<=MAX_PATH_LENGTH) { "edit path must be a non-blank path up to $MAX_PATH_LENGTH characters" }
        require('\u0000' !in value && '\\' !in value) { "edit path contains unsafe path syntax" }
        require(!value.startsWith('/') && !WINDOWS_ABSOLUTE.containsMatchIn(value)) { "edit path must be relative" }
        val components=value.split('/')
        require(components.none { it.isEmpty() || it=="." || it==".." }) { "edit path contains unsafe path syntax" }
        require(components.none { it.lowercase(Locale.ROOT) in PROTECTED_DIRECTORIES }) { "edit path is protected" }
        val basename=components.last().lowercase(Locale.ROOT)
        require(!isProtectedBasename(basename)) { "edit path is protected" }
        require(isApprovedExtension(basename)) { "edit path must use a supported text extension" }

        val relative=Path.of(value)
        require(!relative.isAbsolute) { "edit path must be relative" }
        val absolute=root.resolve(relative).normalize()
        require(absolute.startsWith(root)) { "edit path escapes the repository" }
        rejectSymbolicLinkComponents(relative)
        require(Files.isDirectory(absolute.parent,LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(absolute.parent)) {
            "edit parent must be an existing directory"
        }
        if (Files.exists(absolute,LinkOption.NOFOLLOW_LINKS)) {
            require(Files.isRegularFile(absolute,LinkOption.NOFOLLOW_LINKS)) { "edit path must identify a regular file" }
        }
        val invariant=relative.toString().replace('\\','/')
        val scope=if (invariant.startsWith("TeamCode/")) EditScope.NORMAL else EditScope.PROJECT_LEVEL
        return ResolvedEditPath(invariant,absolute,scope)
    }

    private fun rejectSymbolicLinkComponents(relative:Path) {
        var current=root
        relative.forEach { component ->
            current=current.resolve(component)
            require(!Files.isSymbolicLink(current)) { "edit path contains a symbolic link" }
        }
    }

    private fun isProtectedBasename(name:String):Boolean {
        val extension=name.substringAfterLast('.',"")
        return name.startsWith(".env") || name=="local.properties" || extension in PROTECTED_EXTENSIONS
    }

    private fun isApprovedExtension(name:String):Boolean=
        name.endsWith(".gradle.kts") || name.substringAfterLast('.',"") in APPROVED_EXTENSIONS

    companion object {
        private const val MAX_PATH_LENGTH=512
        private val WINDOWS_ABSOLUTE=Regex("^[A-Za-z]:")
        private val APPROVED_EXTENSIONS=setOf("java","kt","gradle","xml","yaml","yml","properties","md")
        private val PROTECTED_EXTENSIONS=setOf("jks","keystore","p12","pfx","pem","key","der","crt")
        private val PROTECTED_DIRECTORIES=setOf(".git",".gradle","build","generated",".idea",".vscode","out","target","node_modules")
    }
}
