package org.ftckb.agent

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import org.ftckb.domain.KnowledgeRule
import org.ftckb.domain.RuleContext
import org.ftckb.domain.RuleResolver
import org.ftckb.knowledge.FileKnowledgeRepository

class KnowledgeRetriever(knowledgeRoot:Path,team:String?,season:String?) {
    private val activeRules:List<KnowledgeRule>
    private val guidesRoot:Path=knowledgeRoot.resolve("guides")

    init {
        val loaded=FileKnowledgeRepository.load(knowledgeRoot)
        require(loaded.violations.isEmpty()) {
            "knowledge validation failed: "+loaded.violations.joinToString("; ") { "${it.ruleId}:${it.field}:${it.message}" }
        }
        activeRules=RuleResolver.resolve(loaded.rules,RuleContext(team,season)).activeRules
    }

    fun retrieveRules(intent:RetrievalIntent):List<KnowledgeRule> =activeRules.filter { rule ->
        val text="${rule.topic} ${rule.title} ${rule.instruction} ${rule.rationale}".lowercase()
        intent.ruleTopics.any { it.equals(rule.topic,ignoreCase=true) } ||
            intent.concepts.any { it.lowercase() in text } ||
            intent.symbols.any { it.lowercase() in text }
    }

    fun retrieveGuides(intent:RetrievalIntent):List<GuideEvidence> {
        if (!Files.isDirectory(guidesRoot)) return emptyList()
        val terms=(intent.guideTopics+intent.concepts+intent.symbols).map(String::lowercase).filter(String::isNotBlank)
        if (terms.isEmpty()) return emptyList()
        return Files.walk(guidesRoot).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.extension.equals("md",true) }
                .sorted()
                .flatMap { guideSections(it,terms).stream() }
                .toList()
        }.sortedWith(compareBy({ it.path },{ it.heading }))
    }

    private fun guideSections(path:Path,terms:List<String>):List<GuideEvidence> {
        val text=try { Files.readString(path,StandardCharsets.UTF_8) } catch (_:Exception) { return emptyList() }
        val lines=text.lines()
        val headings=lines.mapIndexedNotNull { index,line ->
            heading.matchEntire(line)?.let { index to it.groupValues[1].trim() }
        }
        return headings.mapIndexedNotNull { index,(start,headingText) ->
            val end=headings.getOrNull(index+1)?.first ?: lines.size
            val section=lines.subList(start,end).joinToString("\n")
            val headingMatches=terms.any { it in headingText.lowercase() }
            val textMatches=terms.any { it in section.lowercase() }
            if (!headingMatches && !textMatches) null else GuideEvidence("",guidesRoot.parent.relativize(path).toString().replace('\\','/'),headingText,section)
        }
    }

    private companion object {
        val heading=Regex("^#{1,6}\\s+(.+?)\\s*$")
    }
}
