package org.ftckb.cli

import com.fasterxml.jackson.databind.json.JsonMapper
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import org.eclipse.jgit.api.Git
import org.ftckb.domain.KnowledgeRule
import org.ftckb.domain.RuleAuthority
import org.ftckb.domain.RuleApplicability
import org.ftckb.domain.RuleStatus
import org.ftckb.domain.RuleValidator
import org.ftckb.knowledge.FileKnowledgeRepository
import org.ftckb.knowledge.RuleYamlCodec
import org.ftckb.model.ModelMessage
import org.ftckb.model.MessageRole
import org.ftckb.model.ModelProvider
import org.ftckb.model.ModelProviderException
import org.ftckb.model.ModelRequest
import org.ftckb.model.ModelResponse
import org.ftckb.model.ProviderConfigLoader
import org.ftckb.model.ProviderProfile
import org.ftckb.model.SecretResolver
import org.ftckb.model.openai.ProviderFactory
import org.ftckb.repository.IndexedDocument
import org.ftckb.repository.RepositoryIndex
import org.ftckb.repository.RepositorySnapshot

data class ExtractOptions(
    val repository:Path,
    val team:String,
    val season:String?,
    val provider:String,
    val config:Path,
    val knowledge:Path?,
    val output:Path?,
    val maxCandidates:Int
)

fun interface ExtractRunner {
    fun run(options:ExtractOptions,out:PrintStream):Int
}

internal fun runExtractCommand(
    args:List<String>,out:PrintStream,runner:ExtractRunner
):Int {
    if (args==listOf("--help")) {
        printExtractUsage(out)
        return 0
    }
    if (args.size%2!=0) {
        out.println("extract options must be flag-value pairs")
        return 64
    }
    val pairs=args.chunked(2)
    val allowed=setOf("--repo","--team","--season","--provider","--config","--knowledge","--output","--max-candidates")
    val unknown=pairs.firstOrNull { it[0] !in allowed }
    if (unknown!=null) {
        out.println("unknown extract option: ${unknown[0]}")
        return 64
    }
    val duplicate=pairs.groupBy { it[0] }.entries.firstOrNull { it.value.size>1 }
    if (duplicate!=null) {
        out.println("duplicate extract option: ${duplicate.key}")
        return 64
    }
    val empty=pairs.firstOrNull { it[1].isEmpty() }
    if (empty!=null) {
        out.println("empty value for ${empty[0]}")
        return 64
    }
    val flagValue=pairs.firstOrNull { it[1].startsWith("--") }
    if (flagValue!=null) {
        out.println("invalid value for ${flagValue[0]}: ${flagValue[1]}")
        return 64
    }
    val values=pairs.associate { it[0] to it[1] }
    listOf("--repo","--team","--provider").forEach { required ->
        if (required !in values) {
            out.println("missing $required")
            return 64
        }
    }
    if (!org.ftckb.domain.RuleIdentity.isCanonicalTeam(values.getValue("--team"))) {
        out.println("invalid value for --team: expected digits only")
        return 64
    }
    if (values["--season"]!=null && !org.ftckb.domain.RuleIdentity.isCanonicalSeason(values.getValue("--season"))) {
        out.println("invalid value for --season: expected YYYY-YYYY")
        return 64
    }
    val maxCandidates=when (val text=values["--max-candidates"]) {
        null -> 8
        else -> text.toIntOrNull() ?: run {
            out.println("invalid value for --max-candidates: expected a positive integer")
            return 64
        }
    }
    if (maxCandidates<1) {
        out.println("invalid value for --max-candidates: expected a positive integer")
        return 64
    }
    val options=ExtractOptions(
        repository=Path.of(values.getValue("--repo")),
        team=values.getValue("--team"),
        season=values["--season"],
        provider=values.getValue("--provider"),
        config=values["--config"]?.let(Path::of)
            ?:Path.of(System.getProperty("user.home"),".ftckb","config.yaml"),
        knowledge=values["--knowledge"]?.let(Path::of),
        output=values["--output"]?.let(Path::of),
        maxCandidates=maxCandidates
    )
    return runner.run(options,out)
}

internal fun printExtractUsage(out:PrintStream) {
    out.println(
        "usage: knowledge-cli extract --repo PATH --team N --provider NAME "+
            "[--season YYYY-YYYY] [--config PATH] [--knowledge PATH] [--output PATH] [--max-candidates N]"
    )
}

class ExtractCommand(
    private val environment:(String)->String?=System::getenv,
    private val providerCreator:(ProviderProfile,SecretResolver)->ModelProvider={ profile,resolver ->
        ProviderFactory.create(profile,resolver)
    },
    private val clock:()->Instant={ Instant.now() },
    private val headResolver:(Path)->String?={ root ->
        Git.open(root.toFile()).use { git -> git.repository.resolve("HEAD")?.name }
    },
    private val repositoryName:(Path)->String={ root ->
        Git.open(root.toFile()).use { git ->
            git.repository.config.getString("remote","origin","url")
        }?.removePrefix("https://")?.removePrefix("http://")?.removeSuffix(".git")
            ?: "local/${root.fileName}"
    }
):ExtractRunner {
    private val mapper=JsonMapper.builder().build()

    override fun run(options:ExtractOptions,out:PrintStream):Int {
        val config=try {
            ProviderConfigLoader.decode(Files.readString(options.config))
        } catch (_:Exception) {
            out.println("error starting extract: invalid provider configuration")
            return 2
        }
        val profile=try {
            config.profile(options.provider)
        } catch (_:Exception) {
            out.println("error starting extract: unknown or invalid provider profile")
            return 2
        }
        val secret=environment(profile.apiKeyEnv)?.takeIf(String::isNotBlank)
        if (secret==null) {
            out.println("error starting extract: missing API key environment variable: ${profile.apiKeyEnv}")
            return 2
        }
        val provider=try {
            providerCreator(profile,SecretResolver { name -> if (name==profile.apiKeyEnv) secret else null })
        } catch (_:Exception) {
            out.println("error starting extract: model provider initialization failed")
            return 2
        }
        val index=RepositoryIndex()
        val snapshot=try {
            index.build(options.repository)
        } catch (_:Exception) {
            out.println("error starting extract: repository is not readable")
            return 2
        }
        if (!snapshot.profile.supported) {
            out.println("error starting extract: unsupported FTC repository")
            return 2
        }
        val head=runCatching { headResolver(snapshot.root) }.getOrNull()
        if (head==null) {
            out.println("error starting extract: repository HEAD is unavailable")
            return 2
        }
        val existingRules=options.knowledge?.let { knowledgeRoot ->
            try {
                FileKnowledgeRepository.load(knowledgeRoot).rules
            } catch (_:Exception) {
                out.println("error starting extract: invalid knowledge root")
                return 2
            }
        } ?: emptyList()
        val proposals=try {
            propose(snapshot,existingRules,provider)
        } catch (failure:ModelProviderException) {
            out.println("model provider error: request failed")
            return 2
        } catch (_:Exception) {
            out.println("extract error: model response could not be decoded")
            return 2
        }
        val repository=runCatching { repositoryName(snapshot.root) }.getOrNull() ?: "local/${snapshot.root.fileName}"
        val skipped=mutableListOf<Pair<String,String>>()
        val validated=mutableListOf<ValidatedCandidate>()
        for (proposal in proposals) {
            val validation=validate(proposal,snapshot,existingRules,options.team,head,repository)
            when (validation) {
                is CandidateValidation.Accepted -> {
                    if (validated.size<options.maxCandidates) validated+=validation.candidate
                }
                is CandidateValidation.Skipped -> skipped+=proposal.topic to validation.reason
            }
        }
        if (validated.isEmpty()) {
            out.println("extract=0/${proposals.size} no candidates survived host validation")
            skipped.forEach { (topic,reason) -> out.println("skipped topic=$topic reason=$reason") }
            return 0
        }
        val timestamp=clock()
        val rendered=renderYaml(validated,options.team,options.season,head,repositoryName(snapshot.root),timestamp)
        val violations=runCatching { RuleYamlCodec.decode(rendered) }.getOrElse {
            out.println("extract error: generated candidates are not valid YAML")
            return 2
        }.flatMap(RuleValidator::validate)
        if (violations.isNotEmpty()) {
            out.println("extract error: generated candidates fail validation")
            violations.sortedWith(compareBy({ it.ruleId },{ it.field })).forEach {
                out.println("error rule=${it.ruleId} field=${it.field} message=${it.message}")
            }
            return 2
        }
        val output=options.output ?: defaultOutput(options,timestamp)
        try {
            output.parent?.let(Files::createDirectories)
            Files.writeString(output,rendered)
        } catch (_:Exception) {
            out.println("extract error: unable to write output file")
            return 2
        }
        out.println("extract=${validated.size}/${proposals.size} output=$output")
        validated.forEach { candidate ->
            out.println("candidate ${candidate.id} topic=${candidate.topic} confidence=${candidate.confidence}" +
                if (candidate.needsStrongerEvidence) " needs-stronger-evidence" else "")
        }
        skipped.forEach { (topic,reason) -> out.println("skipped topic=$topic reason=$reason") }
        return 0
    }

    private fun defaultOutput(options:ExtractOptions,timestamp:Instant):Path {
        val stamp=saveTimestamp.format(timestamp)
        return options.knowledge?.resolve("teams/${options.team}/extracted-$stamp.yaml")
            ?: Path.of("extracted-$stamp.yaml")
    }

    private fun propose(snapshot:RepositorySnapshot,existingRules:List<KnowledgeRule>,provider:ModelProvider):List<ProposedCandidate> {
        val inventory=snapshot.documents.values.sortedBy { it.path }.take(120)
        val inventoryText=inventory.joinToString("\n") { "- ${it.path} (${it.lines.size} lines)" }
        val slices=buildString {
            var budget=12_000
            for (document in inventory) {
                if (budget<=0) break
                val block=document.path+"\n"+document.text+"\n"
                if (block.length>budget) break
                append(block)
                budget-=block.length
            }
        }
        val existingTopics=existingRules.map { it.topic }.distinct().sorted().joinToString(",").ifEmpty { "none" }
        val response=provider.complete(ModelRequest(
            listOf(
                ModelMessage(MessageRole.SYSTEM,"""
                    Return exactly one JSON object with the array candidates.
                    Each candidate has topic, title, instruction, rationale, confidence (high, medium, or low), and evidence (array of objects with file, optional symbol, optional line).
                    topic must be a lowercase hyphenated slug using only a-z 0-9 and hyphens.
                    evidence.file must be a repository path listed in the inventory below.
                    Do not propose: one-off fixes for a single commit, commented-out code, legacy SDK artifacts, dependency version numbers, or anything already covered by the existing rule topics listed below.
                    Each array is host-validated. Do not request files or tools.
                """.trimIndent()),
                ModelMessage(MessageRole.USER,buildString {
                    append("Repository summary: ").append(summary(snapshot)).append('\n')
                    append("Existing rule topics: ").append(existingTopics).append('\n')
                    append("File inventory:\n").append(inventoryText).append('\n')
                    append("Code slices:\n").append(slices)
                })
            ),
            32768
        ))
        return decodeProposals(response.content)
    }

    private fun summary(snapshot:RepositorySnapshot):String=buildString {
        append("supported=true")
        append("; sourceModules=").append(snapshot.profile.sourceModules.sorted().joinToString(","))
        append("; markerCount=").append(snapshot.profile.markers.size)
        append("; documentCount=").append(snapshot.documents.size)
    }

    private fun decodeProposals(text:String):List<ProposedCandidate> {
        val root=mapper.readTree(text)
        if (!root.has("candidates") || !root["candidates"].isArray) error("candidates must be an array")
        return root["candidates"].mapIndexed { index,node ->
            listOf("topic","title","instruction","rationale","confidence","evidence").forEach { field ->
                if (!node.has(field)) error("candidates[$index] missing $field")
            }
            if (!node["topic"].isTextual) error("candidates[$index].topic must be a string")
            if (!node["title"].isTextual) error("candidates[$index].title must be a string")
            if (!node["instruction"].isTextual) error("candidates[$index].instruction must be a string")
            if (!node["rationale"].isTextual) error("candidates[$index].rationale must be a string")
            if (!node["evidence"].isArray) error("candidates[$index].evidence must be an array")
            ProposedCandidate(
                topic=node["topic"].asText().trim(),
                title=node["title"].asText().trim(),
                instruction=node["instruction"].asText().trim(),
                rationale=node["rationale"].asText().trim(),
                confidence=node["confidence"].asText().trim().lowercase(),
                evidence=node["evidence"].mapIndexed { evidenceIndex,evidenceNode ->
                    if (!evidenceNode.has("file") || !evidenceNode["file"].isTextual) {
                        error("candidates[$index].evidence[$evidenceIndex] missing file")
                    }
                    ProposedEvidence(
                        file=evidenceNode["file"].asText().trim(),
                        symbol=evidenceNode["symbol"]?.takeIf { it.isTextual }?.asText()?.trim(),
                        line=evidenceNode["line"]?.takeIf { it.isNumber }?.asInt()
                    )
                },
            )
        }
    }

    private fun validate(
        proposal:ProposedCandidate,
        snapshot:RepositorySnapshot,
        existingRules:List<KnowledgeRule>,
        team:String,
        head:String,
        repository:String
    ):CandidateValidation {
        val topic=proposal.topic.lowercase()
            .replace(Regex("[^a-z0-9-]+"),"-")
            .replace(Regex("-+"),"-")
            .trim('-' )
        if (topic.isEmpty() || !org.ftckb.domain.RuleIdentity.isCanonicalTopic(topic)) {
            return CandidateValidation.Skipped("non-canonical topic")
        }
        existingRules.firstOrNull { it.topic==topic }?.let { existing ->
            return CandidateValidation.Skipped("topic already covered by ${existing.id}")
        }
        val id="team-$team.$topic"
        if (existingRules.any { it.id==id }) return CandidateValidation.Skipped("id already exists")
        val evidenceRejections=mutableListOf<String>()
        val validEvidence=proposal.evidence.mapNotNull { evidence ->
            val rejection=validateEvidence(evidence,snapshot)
            if (rejection!=null) { evidenceRejections+=rejection; null }
            else CandidateEvidence(
                repository,head,evidence.file,evidence.symbol,evidence.line
            )
        }
        if (validEvidence.isEmpty()) {
            val detail=evidenceRejections.firstOrNull()?.let { ": $it" } ?: ""
            return CandidateValidation.Skipped("no valid evidence$detail")
        }
        var confidence=when (proposal.confidence) {
            "high","medium","low" -> proposal.confidence
            else -> "low"
        }
        var needsStrongerEvidence=false
        if (validEvidence.size==1 && confidence=="high") {
            confidence="low"
            needsStrongerEvidence=true
        }
        val candidate=ValidatedCandidate(
            id,topic,proposal.title,proposal.instruction,proposal.rationale,
            confidence,needsStrongerEvidence,validEvidence
        )
        val violations=RuleValidator.validate(candidate.toRule(team))
        if (violations.isNotEmpty()) {
            return CandidateValidation.Skipped("fails rule validation: ${violations.first().field}")
        }
        return CandidateValidation.Accepted(candidate)
    }

    private fun validateEvidence(evidence:ProposedEvidence,snapshot:RepositorySnapshot):String? {
        if (evidence.file.isBlank()) return "blank file"
        if (evidence.file.startsWith("/") || ".." in evidence.file.split('/','\\')) return "path escapes repository"
        val document=snapshot.documents[evidence.file] ?: return "file not indexed: ${evidence.file}"
        if (evidence.line!=null && evidence.line !in 1..document.lines.size) return "line out of bounds"
        if (evidence.line!=null && document.lines[evidence.line-1].isBlank()) return "line is blank"
        if (evidence.symbol!=null) {
            val appears=document.lines.any { line ->
                !line.trimStart().startsWith("//") && symbolPattern(evidence.symbol).containsMatchIn(line)
            }
            if (!appears) return "symbol only in comments or absent: ${evidence.symbol}"
        }
        return null
    }

    private fun symbolPattern(symbol:String)=Regex("(?<![A-Za-z0-9_])${Regex.escape(symbol)}(?![A-Za-z0-9_])")

    private fun renderYaml(
        candidates:List<ValidatedCandidate>,
        team:String,
        season:String?,
        head:String,
        repository:String,
        timestamp:Instant
    ):String=buildString {
        append("# generated by ftckb extract at ").append(timestamp.toString()).append('\n')
        append("# team=").append(team).append(" commit=").append(head).append('\n')
        append("schemaVersion: 1\n")
        append("rules:\n")
        candidates.forEach { candidate ->
            append("  # confidence: ").append(candidate.confidence).append('\n')
            if (candidate.needsStrongerEvidence) append("  # needs-stronger-evidence\n")
            append("  - id: ").append(candidate.id).append('\n')
            append("    topic: ").append(candidate.topic).append('\n')
            append("    title: ").append(quote(candidate.title)).append('\n')
            append("    instruction: ").append(quote(candidate.instruction)).append('\n')
            append("    rationale: ").append(quote(candidate.rationale)).append('\n')
            append("    status: candidate\n")
            append("    authority: team\n")
            append("    applicability:\n")
            append("      teams: [\"").append(team).append("\"]\n")
            append("      seasons: ").append(if (season==null) "[]" else "[\"$season\"]").append('\n')
            append("    evidence:\n")
            candidate.evidence.forEach { evidence ->
                append("      - repository: ").append(quote(evidence.repository)).append('\n')
                append("        commit: ").append(evidence.commit).append('\n')
                append("        file: ").append(evidence.file).append('\n')
                evidence.symbol?.let { append("        symbol: ").append(it).append('\n') }
                evidence.line?.let { append("        line: ").append(it.toString()).append('\n') }
            }
        }
    }

    private fun quote(value:String):String="\""+value.replace("\\","\\\\").replace("\"","\\\"")+"\""

    private data class ProposedEvidence(val file:String,val symbol:String?,val line:Int?)
    private data class ProposedCandidate(
        val topic:String,val title:String,val instruction:String,val rationale:String,
        val confidence:String,val evidence:List<ProposedEvidence>
    )
    private data class CandidateEvidence(
        val repository:String,val commit:String,val file:String,val symbol:String?,val line:Int?
    )
    private data class ValidatedCandidate(
        val id:String,val topic:String,val title:String,val instruction:String,val rationale:String,
        val confidence:String,val needsStrongerEvidence:Boolean,val evidence:List<CandidateEvidence>
    ) {
        fun toRule(team:String):KnowledgeRule=KnowledgeRule(
            id,topic,title,instruction,rationale,RuleStatus.CANDIDATE,RuleAuthority.TEAM,
            RuleApplicability(setOf(team),emptySet()),
            evidence.map { evidence ->
                org.ftckb.domain.GitRuleEvidence(
                    evidence.repository,evidence.commit,evidence.file,evidence.symbol,evidence.line
                )
            }
        )
    }
    private sealed interface CandidateValidation {
        data class Accepted(val candidate:ValidatedCandidate):CandidateValidation
        data class Skipped(val reason:String):CandidateValidation
    }

    companion object {
        val saveTimestamp:DateTimeFormatter=DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC)
    }
}
