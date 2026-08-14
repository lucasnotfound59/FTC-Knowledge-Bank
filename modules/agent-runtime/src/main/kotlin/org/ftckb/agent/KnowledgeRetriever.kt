package org.ftckb.agent

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import org.ftckb.domain.KnowledgeRule
import org.ftckb.domain.RuleContext
import org.ftckb.domain.RuleResolver
import org.ftckb.knowledge.FileKnowledgeRepository

class KnowledgeRetriever(knowledgeRoot:Path,team:String?,season:String?) {
    private val activeRules:List<KnowledgeRule>
    private val guidesRoot:Path?

    init {
        val canonicalKnowledgeRoot=knowledgeRoot.toRealPath()
        val loaded=FileKnowledgeRepository.load(canonicalKnowledgeRoot)
        require(loaded.violations.isEmpty()) {
            "knowledge validation failed: "+loaded.violations.joinToString("; ") { "${it.ruleId}:${it.field}:${it.message}" }
        }
        activeRules=RuleResolver.resolve(loaded.rules,RuleContext(team,season)).activeRules
        val candidate=canonicalKnowledgeRoot.resolve("guides")
        guidesRoot=if (Files.isDirectory(candidate,LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(candidate)) {
            candidate.toRealPath()
        } else {
            null
        }
    }

    fun retrieveRules(intent:RetrievalIntent):List<KnowledgeRule> =activeRules.filter { rule ->
        val text="${rule.topic} ${rule.title} ${rule.instruction} ${rule.rationale}".lowercase()
        intent.ruleTopics.any { it.equals(rule.topic,ignoreCase=true) } ||
            intent.concepts.any { it.lowercase() in text } ||
            intent.symbols.any { it.lowercase() in text }
    }

    fun retrieveGuides(intent:RetrievalIntent):List<GuideEvidence> {
        val root=guidesRoot ?: return emptyList()
        val terms=(intent.guideTopics+intent.concepts+intent.symbols).map(String::lowercase).filter(String::isNotBlank)
        if (terms.isEmpty()) return emptyList()
        val guides=mutableListOf<GuideEvidence>()
        Files.walk(root).use { paths ->
            paths.sorted().forEach { path ->
                val file=readGuide(path,root) ?: return@forEach
                guides+=guideSections(file,terms)
            }
        }
        return guides.sortedWith(compareBy({ it.path },{ it.heading }))
    }

    private fun readGuide(path:Path,root:Path):GuideFile? {
        if (!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) return null
        val size=try { Files.size(path) } catch (_:Exception) { return null }
        if (!path.fileName.toString().endsWith(".md",true) || size>maxGuideBytes) return null
        val realFile=try { path.toRealPath() } catch (_:Exception) { return null }
        if (!realFile.startsWith(root)) return null
        val bytes=try { Files.readAllBytes(path) } catch (_:Exception) { return null }
        if (bytes.size>maxGuideBytes || bytes.any { it==0.toByte() }) return null
        val text=decodeUtf8(bytes) ?: return null
        return GuideFile(root.parent.relativize(realFile).toString().replace('\\','/'),text)
    }

    private fun guideSections(file:GuideFile,terms:List<String>):List<GuideEvidence> {
        val lines=file.text.lines()
        val headings=lines.mapIndexedNotNull { index,line ->
            heading.matchEntire(line)?.let { index to it.groupValues[1].trim() }
        }
        return headings.mapIndexedNotNull { index,(start,headingText) ->
            val end=headings.getOrNull(index+1)?.first ?: lines.size
            val section=lines.subList(start,end).joinToString("\n")
            val headingMatches=terms.any { it in headingText.lowercase() }
            val textMatches=terms.any { it in section.lowercase() }
            if (!headingMatches && !textMatches) null else GuideEvidence("",file.path,headingText,section)
        }
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

    private companion object {
        const val maxGuideBytes=1_048_576L
        val heading=Regex("^#{1,6}\\s+(.+?)\\s*$")
    }
}

private data class GuideFile(val path:String,val text:String)
