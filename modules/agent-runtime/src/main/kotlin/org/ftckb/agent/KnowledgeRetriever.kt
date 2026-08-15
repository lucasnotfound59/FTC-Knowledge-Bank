package org.ftckb.agent

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.FileVisitResult
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import org.ftckb.domain.KnowledgeRule
import org.ftckb.domain.RuleContext
import org.ftckb.domain.RuleResolver
import org.ftckb.knowledge.FileKnowledgeRepository

class GuideTraversalLimits(
    val maxFiles:Int=5_000,
    val maxTotalBytes:Long=64L*1_048_576L,
    val maxDepth:Int=32
) {
    init {
        require(maxFiles>0) { "maxFiles must be positive" }
        require(maxTotalBytes>0) { "maxTotalBytes must be positive" }
        require(maxDepth>=0) { "maxDepth must not be negative" }
    }
}

class GuideTraversalException(message:String):RuntimeException(message)

class KnowledgeAccessException:RuntimeException("knowledge files are unavailable")

class KnowledgeRetriever(
    knowledgeRoot:Path,
    team:String?,
    season:String?,
    private val guideLimits:GuideTraversalLimits=GuideTraversalLimits()
) {
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
        var visitedFiles=0
        var visitedBytes=0L
        try {
            Files.walkFileTree(root,object:SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(directory:Path,attributes:BasicFileAttributes):FileVisitResult {
                    if (directory!=root && root.relativize(directory).nameCount>guideLimits.maxDepth) {
                        throw GuideTraversalException("guide traversal exceeds depth limit")
                    }
                    if (directory!=root && Files.isSymbolicLink(directory)) return FileVisitResult.SKIP_SUBTREE
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(path:Path,attributes:BasicFileAttributes):FileVisitResult {
                    if (root.relativize(path).nameCount>guideLimits.maxDepth) {
                        throw GuideTraversalException("guide traversal exceeds depth limit")
                    }
                    visitedFiles++
                    if (visitedFiles>guideLimits.maxFiles) throw GuideTraversalException("guide traversal exceeds file-count limit")
                    visitedBytes=safeAdd(visitedBytes,attributes.size())
                    if (visitedBytes>guideLimits.maxTotalBytes) throw GuideTraversalException("guide traversal exceeds byte-count limit")
                    val file=readGuide(path,root) ?: return FileVisitResult.CONTINUE
                    guides+=guideSections(file,terms)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file:Path,error:IOException):FileVisitResult {
                    if (file==root) throw KnowledgeAccessException()
                    return FileVisitResult.CONTINUE
                }
            })
        } catch (error:GuideTraversalException) {
            throw error
        } catch (_:IOException) {
            throw KnowledgeAccessException()
        }
        val headingMatches=guides.filter { section -> terms.any { it in section.heading.lowercase() } }
        val textOnly=guides.filterNot { section -> section in headingMatches }
        return (headingMatches+textOnly)
            .take(MAX_GUIDE_SECTIONS)
            .sortedWith(compareBy({ it.path },{ it.heading }))
    }

    private fun readGuide(path:Path,root:Path):GuideFile? {
        if (!path.fileName.toString().endsWith(".md",true)) return null
        if (!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)) return null
        val bytes=readGuideBytes(path) ?: return null
        if (bytes.any { it==0.toByte() }) return null
        val text=decodeUtf8(bytes) ?: return null
        return GuideFile(root.parent.relativize(path).toString().replace('\\','/'),text)
    }

    private fun readGuideBytes(path:Path):ByteArray?=try {
        Files.newByteChannel(path,setOf(StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)).use { channel ->
            val output=ByteArrayOutputStream()
            val buffer=ByteBuffer.allocate(8_192)
            var total=0
            while (true) {
                buffer.clear()
                val count=channel.read(buffer)
                if (count<0) break
                if (count==0) continue
                total+=count
                if (total>maxGuideBytes) return@use null
                output.write(buffer.array(),0,count)
            }
            output.toByteArray()
        }
    } catch (_:Exception) {
        null
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

    private fun safeAdd(left:Long,right:Long):Long=if (Long.MAX_VALUE-left<right) Long.MAX_VALUE else left+right

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
        const val MAX_GUIDE_SECTIONS=2
        val heading=Regex("^#{1,6}\\s+(.+?)\\s*$")
    }
}

private data class GuideFile(val path:String,val text:String)
