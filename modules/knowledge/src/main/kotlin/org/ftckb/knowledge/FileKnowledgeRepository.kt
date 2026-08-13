package org.ftckb.knowledge

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.readText
import org.ftckb.domain.KnowledgeRule
import org.ftckb.domain.RuleValidator
import org.ftckb.domain.RuleViolation

data class KnowledgeLoadResult(val rules:List<KnowledgeRule>,val violations:List<RuleViolation>)

object FileKnowledgeRepository {
    fun load(root:Path):KnowledgeLoadResult {
        require(Files.isDirectory(root)) { "knowledge root is not a directory: $root" }
        val files=Files.walk(root).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.extension in setOf("yaml","yml") }
                .sorted()
                .toList()
        }
        val rules=files.flatMap { RuleYamlCodec.decode(it.readText()) }
        val duplicateViolations=rules.groupBy { it.id }
            .filterValues { it.size>1 }
            .keys
            .sorted()
            .map { RuleViolation(it,"id","duplicate rule id") }
        return KnowledgeLoadResult(rules,duplicateViolations+rules.flatMap(RuleValidator::validate))
    }
}
