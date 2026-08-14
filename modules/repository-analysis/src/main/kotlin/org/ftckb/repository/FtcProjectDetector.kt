package org.ftckb.repository

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.FileVisitResult
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.Locale

object FtcProjectDetector {
    private const val maxInspectedFileBytes=1_048_576L

    fun detect(root:Path):FtcProjectProfile {
        val normalizedRoot=root.toRealPath()
        val markers=mutableListOf<ProjectMarker>()
        val sourceModules=linkedSetOf<String>()

        Files.walkFileTree(normalizedRoot,object:SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(directory:Path,attributes:BasicFileAttributes):FileVisitResult {
                if (directory==normalizedRoot) return FileVisitResult.CONTINUE
                if (Files.isSymbolicLink(directory)) return FileVisitResult.SKIP_SUBTREE
                val relative=normalizedRoot.relativize(directory).invariantSeparatorsPathString()
                if (isExcluded(relative)) return FileVisitResult.SKIP_SUBTREE
                if (directory.fileName.toString()=="TeamCode" && hasBuildFile(directory)) {
                    markers+=ProjectMarker(ProjectMarkerKind.TEAMCODE_MODULE,relative,"Gradle module")
                    sourceModules+="TeamCode"
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file:Path,attributes:BasicFileAttributes):FileVisitResult {
                if (!attributes.isRegularFile || Files.isSymbolicLink(file)) return FileVisitResult.CONTINUE
                val relative=normalizedRoot.relativize(file).invariantSeparatorsPathString()
                if (isExcluded(relative) || Files.size(file)>maxInspectedFileBytes) return FileVisitResult.CONTINUE
                val name=file.fileName.toString()
                val text=readUtf8(file) ?: return FileVisitResult.CONTINUE
                when {
                    name=="settings.gradle" || name=="settings.gradle.kts" ->
                        markers+=ProjectMarker(ProjectMarkerKind.GRADLE_SETTINGS,relative,"Gradle settings")
                    name.endsWith(".gradle") || name.endsWith(".gradle.kts") -> {
                        if (FTC_DEPENDENCY.containsMatchIn(text)) {
                            markers+=ProjectMarker(ProjectMarkerKind.FTC_DEPENDENCY,relative,"FTC SDK dependency")
                        }
                    }
                    name.endsWith(".java") || name.endsWith(".kt") -> {
                        if (OPMODE_ANNOTATION.containsMatchIn(text)) {
                            markers+=ProjectMarker(ProjectMarkerKind.OPMODE_ANNOTATION,relative,"FTC OpMode annotation")
                        }
                    }
                }
                return FileVisitResult.CONTINUE
            }
        })
        val kinds=markers.map { it.kind }.toSet()
        val supported=kinds.size>=2 && (ProjectMarkerKind.FTC_DEPENDENCY in kinds || ProjectMarkerKind.OPMODE_ANNOTATION in kinds)
        return FtcProjectProfile(
            supported,
            immutableSetCopy(sourceModules),
            immutableListCopy(markers.sortedWith(compareBy({ it.path },{ it.kind.name })))
        )
    }

    private fun hasBuildFile(directory:Path):Boolean=
        Files.isRegularFile(directory.resolve("build.gradle")) || Files.isRegularFile(directory.resolve("build.gradle.kts"))

    private fun readUtf8(path:Path):String?=try {
        val bytes=Files.readAllBytes(path)
        if (bytes.size>maxInspectedFileBytes || bytes.any { it==0.toByte() }) null else String(bytes,StandardCharsets.UTF_8)
    } catch (_:Exception) {
        null
    }

    private fun isExcluded(relative:String):Boolean=relative.split('/').any {
        it.lowercase(Locale.ROOT) in setOf(".git",".gradle","build","generated",".idea","out")
    }

    private fun Path.invariantSeparatorsPathString():String=toString().replace('\\','/')

    private val FTC_DEPENDENCY=Regex("""(?i)(org\.firstinspires\.ftc|com\.qualcomm\.robotcore)""")
    private val OPMODE_ANNOTATION=Regex("""@(?:[A-Za-z_][\w.]*\.)?(?:TeleOp|Autonomous)\b""")
}
