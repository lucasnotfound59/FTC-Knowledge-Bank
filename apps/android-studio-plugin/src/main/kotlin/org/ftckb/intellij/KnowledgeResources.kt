package org.ftckb.intellij

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Extracts the knowledge/ directory bundled in the plugin jar into a cache
 * directory so the knowledge loader can consume a real directory tree.
 * The cache is keyed by plugin version, so upgrading the plugin refreshes rules. */
object KnowledgeResources {
    private const val VERSION_KEY="ftckb-knowledge-v1"

    fun extractOrDefault():Path {
        val home=Path.of(System.getProperty("user.home"))
        val cache=home.resolve("Library/Caches/ftckb-as").let { root ->
            if (Files.isDirectory(home.resolve("Library"))) root
            else Path.of(System.getProperty("java.io.tmpdir"),"ftckb-as")
        }
        val target=cache.resolve(VERSION_KEY)
        if (Files.isRegularFile(target.resolve("knowledge/rules.yaml").let { it })
            || Files.isRegularFile(target.resolve("knowledge/official/rules.yaml"))) return target.resolve("knowledge")
        runCatching {
            val stream=KnowledgeResources::class.java.getResourceAsStream("/knowledge-file-list.txt")
            if (stream!=null) {
                val entries=stream.bufferedReader().readLines()
                entries.forEach { entry ->
                    val resource=KnowledgeResources::class.java.getResourceAsStream("/$entry")
                    if (resource!=null) {
                        val destination=target.resolve("knowledge").resolve(entry)
                        destination.parent?.let(Files::createDirectories)
                        resource.use { input -> Files.copy(input,destination,StandardCopyOption.REPLACE_EXISTING) }
                    }
                }
            }
        }
        return target.resolve("knowledge")
    }
}
