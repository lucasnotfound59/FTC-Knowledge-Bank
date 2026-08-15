package org.ftckb.cli

import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import org.eclipse.jgit.api.Git
import org.ftckb.agent.AgentMode
import org.ftckb.agent.AnswerGenerator
import org.ftckb.agent.AskAgent
import org.ftckb.agent.AskResult
import org.ftckb.agent.ContextRetriever
import org.ftckb.agent.ConversationState
import org.ftckb.agent.CredentialRedactor
import org.ftckb.agent.EditResult
import org.ftckb.agent.KnowledgeRetriever
import org.ftckb.agent.RedactingModelProvider
import org.ftckb.agent.RejectedResult
import org.ftckb.agent.RetrievalPlanner
import org.ftckb.agent.SessionController
import org.ftckb.agent.edit.EditAgent
import org.ftckb.agent.edit.EditHistory
import org.ftckb.agent.edit.FileEditEngine
import org.ftckb.domain.RuleIdentity
import org.ftckb.git.GitBranchState
import org.ftckb.git.GitWorkspace
import org.ftckb.model.ModelProvider
import org.ftckb.model.ProviderConfigLoader
import org.ftckb.model.ProviderProfile
import org.ftckb.model.SecretResolver
import org.ftckb.model.openai.ProviderFactory
import org.ftckb.repository.RepositoryIndex
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings

data class EvalTurnExpectation(
    val mode:String,
    val prompt:String,
    val requiredClaimKinds:Set<String>,
    val requiredPaths:Set<String>,
    val requiredRuleIds:Set<String>,
    val requiredChangedPaths:Set<String>,
    val forbiddenPaths:Set<String>
)

data class EvalCase(
    val id:String,
    val repository:String,
    val team:String,
    val season:String,
    val turns:List<EvalTurnExpectation>
)

data class EvalExpectationDetail(val label:String,val passed:Boolean,val detail:String)

data class EvalCaseResult(
    val id:String,
    val passed:Boolean,
    val expectations:List<EvalExpectationDetail>,
    val inputTokens:Long,
    val outputTokens:Long
)

object EvalCasesCodec {
    private val load=Load(LoadSettings.builder().setAllowDuplicateKeys(false).build())
    private val rootKeys=setOf("schemaVersion","cases")
    private val caseKeys=setOf("id","repository","team","season","turns")
    private val turnKeys=setOf(
        "mode","prompt","requiredClaimKinds","requiredPaths","requiredRuleIds",
        "requiredChangedPaths","forbiddenPaths"
    )
    private val claimKinds=setOf("code_observation","approved_rule","model_inference","insufficient_evidence")
    private val idPattern=Regex("^[a-z0-9]+(?:[.-][a-z0-9]+)*$")

    fun decode(text:String):List<EvalCase> {
        val root=load.loadFromString(text).asMap("root")
        root.rejectUnknownFields(rootKeys,"root")
        val schemaVersion=root.int("schemaVersion")
        check(schemaVersion==1) { "unsupported schemaVersion" }
        val cases=root.requiredList("cases").mapIndexed { index,value ->
            decodeCase(value.asMap("cases[${index}]"),"cases[${index}]")
        }
        val ids=cases.map { it.id }
        check(ids.distinct().size==ids.size) { "duplicate eval case id" }
        return cases
    }

    private fun decodeCase(map:Map<String,Any?>,name:String):EvalCase {
        map.rejectUnknownFields(caseKeys,name)
        val id=map.string("id")
        check(idPattern.matches(id)) { "${name}.id must be a canonical id" }
        val repository=map.string("repository")
        check(repository.isNotBlank() && !repository.startsWith('/') && '\\' !in repository) {
            "${name}.repository must be a relative repository path"
        }
        val team=map.string("team")
        check(RuleIdentity.isCanonicalTeam(team)) { "${name}.team must contain digits only" }
        val season=map.string("season")
        check(RuleIdentity.isCanonicalSeason(season)) { "${name}.season must use YYYY-YYYY" }
        val turns=map.requiredList("turns").mapIndexed { index,value ->
            decodeTurn(value.asMap("${name}.turns[${index}]"),"${name}.turns[${index}]")
        }
        check(turns.isNotEmpty()) { "${name} requires at least one turn" }
        return EvalCase(id,repository,team,season,turns)
    }

    private fun decodeTurn(map:Map<String,Any?>,name:String):EvalTurnExpectation {
        map.rejectUnknownFields(turnKeys,name)
        val mode=map.optionalString("mode")?.lowercase() ?: "ask"
        check(mode=="ask"||mode=="edit") { "${name}.mode must be ask or edit" }
        val prompt=map.string("prompt")
        check(prompt.isNotBlank()) { "${name}.prompt must not be blank" }
        val kinds=map.stringSet("requiredClaimKinds")
        check(kinds.all { it.lowercase() in claimKinds }) { "${name}.requiredClaimKinds contains an unknown claim kind" }
        val changed=map.stringSet("requiredChangedPaths")
        check(changed.isEmpty()||mode=="edit") {
            "${name} requires explicit edit mode for requiredChangedPaths"
        }
        return EvalTurnExpectation(
            mode,prompt,
            kinds.mapTo(linkedSetOf()) { it.lowercase() },
            map.stringSet("requiredPaths"),
            map.stringSet("requiredRuleIds"),
            changed,
            map.stringSet("forbiddenPaths")
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun Any?.asMap(name:String)=this as? Map<String,Any?> ?: error("${name} must be a map")
    private fun Map<String,Any?>.string(key:String)=this[key] as? String ?: error("${key} must be a string")
    private fun Map<String,Any?>.optionalString(key:String):String? {
        if (key !in this) return null
        return this[key] as? String ?: error("${key} must be a string")
    }
    private fun Map<String,Any?>.int(key:String)=this[key].strictInt(key)
    private fun Any?.strictInt(key:String):Int {
        val number=this as? Number ?: error("${key} must be an integer")
        return number.toInt()
    }
    private fun Map<String,Any?>.requiredList(key:String)=this[key] as? List<*>
        ?: error("${key} must be a list")
    private fun Map<String,Any?>.optionalList(key:String):List<*> {
        if (key !in this) return emptyList<Any?>()
        return this[key] as? List<*> ?: error("${key} must be a list")
    }
    private fun Map<String,Any?>.stringSet(key:String)=optionalList(key).map {
        it as? String ?: error("${key} values must be strings")
    }.toSet()
    private fun Map<String,Any?>.rejectUnknownFields(allowed:Set<String>,name:String) {
        val unknown=(keys-allowed).sorted()
        if (unknown.isNotEmpty()) error("${name} contains unknown fields: ${unknown.joinToString()}")
    }
}

private data class EvalOptionValues(
    val cases:Path,
    val knowledge:Path,
    val provider:String,
    val output:Path,
    val config:Path
)

private data class EvalPipeline(val agent:AskAgent,val controller:SessionController)

class EvalCommand(
    private val environment:(String)->String?={ name -> System.getenv(name) },
    private val providerCreator:(ProviderProfile,SecretResolver)->ModelProvider={ profile,resolver ->
        ProviderFactory.create(profile,resolver)
    },
    private val workingDirectory:Path=Path.of(System.getProperty("user.dir"))
) {
    fun run(args:List<String>,out:PrintStream):Int {
        if (args==listOf("--help")) {
            printEvalUsage(out)
            return 0
        }
        val values=parseArgs(args,out) ?:return 64
        val config=try {
            ProviderConfigLoader.decode(Files.readString(values.config))
        } catch (_:Exception) {
            out.println("error starting eval: invalid provider configuration")
            return 2
        }
        val profile=try {
            config.profile(values.provider)
        } catch (_:Exception) {
            out.println("error starting eval: unknown or invalid provider profile")
            return 2
        }
        val secret=environment(profile.apiKeyEnv)?.takeIf { it.isNotBlank() }
        if (secret==null) {
            out.println("error starting eval: missing API key environment variable: ${profile.apiKeyEnv}")
            return 2
        }
        val cases=try {
            EvalCasesCodec.decode(Files.readString(values.cases))
        } catch (exception:Exception) {
            val detail=exception.message?.lineSequence()?.firstOrNull()?.trim().orEmpty()
            out.println("error loading eval cases: ${detail.ifEmpty { exception.javaClass.simpleName }}")
            return 2
        }
        val provider=try {
            providerCreator(profile,SecretResolver { name -> if (name==profile.apiKeyEnv) secret else null })
        } catch (_:Exception) {
            out.println("error starting eval: model provider initialization failed")
            return 2
        }
        val results=cases.map { runCase(it,values,provider,secret) }
        val report=renderReport(profile,results)
        val redacted=CredentialRedactor.redact(report,setOf(secret))
        val written=try {
            values.output.toAbsolutePath().normalize().parent?.let { directory -> Files.createDirectories(directory) }
            Files.writeString(values.output,redacted)
            true
        } catch (_:Exception) {
            false
        }
        if (!written) {
            out.println("error writing eval report: ${values.output}")
            return 2
        }
        out.println("eval=${results.count { it.passed }}/${results.size} report=${values.output}")
        return if (results.all { it.passed }) 0 else 2
    }

    private fun parseArgs(args:List<String>,out:PrintStream):EvalOptionValues? {
        if (args.size%2!=0) {
            out.println("eval options must be flag-value pairs")
            return null
        }
        val pairs=args.chunked(2)
        val allowed=setOf("--cases","--knowledge","--provider","--output","--config")
        val unknown=pairs.firstOrNull { it[0] !in allowed }
        if (unknown!=null) {
            out.println("unknown eval option: ${unknown[0]}")
            return null
        }
        val duplicate=pairs.groupBy { it[0] }.entries.firstOrNull { it.value.size>1 }
        if (duplicate!=null) {
            out.println("duplicate eval option: ${duplicate.key}")
            return null
        }
        val empty=pairs.firstOrNull { it[1].isEmpty() }
        if (empty!=null) {
            out.println("empty value for ${empty[0]}")
            return null
        }
        val flagValue=pairs.firstOrNull { it[1].startsWith("--") }
        if (flagValue!=null) {
            out.println("invalid value for ${flagValue[0]}: ${flagValue[1]}")
            return null
        }
        val values=pairs.associate { it[0] to it[1] }
        listOf("--cases","--knowledge","--provider","--output").forEach { required ->
            if (required !in values) {
                out.println("missing ${required}")
                return null
            }
        }
        return EvalOptionValues(
            cases=Path.of(values.getValue("--cases")),
            knowledge=Path.of(values.getValue("--knowledge")),
            provider=values.getValue("--provider"),
            output=Path.of(values.getValue("--output")),
            config=values["--config"]?.let(Path::of)
                ?:Path.of(System.getProperty("user.home"),".ftckb","config.yaml")
        )
    }

    private fun runCase(
        case:EvalCase,
        values:EvalOptionValues,
        provider:ModelProvider,
        secret:String
    ):EvalCaseResult {
        val fixture=workingDirectory.resolve(case.repository).normalize()
        if (!Files.isDirectory(fixture)) {
            return EvalCaseResult(
                case.id,false,
                listOf(EvalExpectationDetail("fixture",false,"missing fixture repository: ${case.repository}")),
                0L,0L
            )
        }
        val temp=try {
            Files.createTempDirectory("ftckb-eval")
        } catch (_:Exception) {
            return EvalCaseResult(case.id,false,listOf(EvalExpectationDetail("fixture",false,"could not create a temp repository")),0L,0L)
        }
        try {
            copyTree(fixture,temp)
            val needsGit=case.turns.any { it.mode=="edit" }
            if (needsGit && !initializeSyntheticRepository(temp)) {
                return EvalCaseResult(
                    case.id,false,
                    listOf(EvalExpectationDetail("fixture",false,"could not initialize the eval Git repository")),
                    0L,0L
                )
            }
            val before=snapshot(temp)
            val pipeline=try {
                buildPipeline(temp,case,values,provider,secret)
            } catch (exception:Exception) {
                val detail=exception.message?.lineSequence()?.firstOrNull()?.trim().orEmpty()
                return EvalCaseResult(
                    case.id,false,
                    listOf(EvalExpectationDetail("pipeline",false,"repository is not evaluable: ${detail.ifEmpty { exception.javaClass.simpleName }}")),
                    0L,0L
                )
            }
            val expectations=mutableListOf<EvalExpectationDetail>()
            val changedPaths=sortedSetOf<String>()
            var inputTokens=0L
            var outputTokens=0L
            var allOk=true
            for (turn in case.turns) {
                val mode=if (turn.mode=="edit") AgentMode.EDIT else AgentMode.ASK
                val refused=pipeline.controller.setMode(mode)
                if (refused!=null) {
                    allOk=false
                    expectations+=EvalExpectationDetail("mode",false,"mode refused: ${refused.message}")
                    break
                }
                val result=try {
                    pipeline.controller.submit(turn.prompt)
                } catch (exception:Exception) {
                    allOk=false
                    expectations+=EvalExpectationDetail("turn",false,"submission failed: ${exception.javaClass.simpleName}")
                    break
                }
                when (result) {
                    is AskResult -> {
                        val claims=result.answer.claims.map { it.kind.name.lowercase() }.toSet()
                        val referenced=pipeline.agent.conversation.context().recentTurns.last().referencedIds
                        val failures=mutableListOf<String>()
                        turn.requiredClaimKinds.forEach { kind ->
                            if (kind !in claims) failures+="missing claim kind: ${kind}"
                        }
                        turn.requiredPaths.forEach { path ->
                            if (path !in referenced) failures+="missing referenced path: ${path}"
                        }
                        turn.requiredRuleIds.forEach { rule ->
                            if (rule !in referenced) failures+="missing referenced rule: ${rule}"
                        }
                        turn.forbiddenPaths.forEach { path ->
                            if (path in referenced) failures+="forbidden path referenced: ${path}"
                        }
                        result.answer.usage?.let { usage ->
                            usage.inputTokens?.let { inputTokens+=it }
                            usage.outputTokens?.let { outputTokens+=it }
                        }
                        if (failures.isEmpty()) {
                            expectations+=EvalExpectationDetail("turn",true,"claims=${claims.sorted().joinToString(",")}")
                        } else {
                            allOk=false
                            expectations+=EvalExpectationDetail("turn",false,failures.joinToString("; "))
                        }
                    }
                    is EditResult -> {
                        changedPaths+=result.report.changedPaths
                        val failures=mutableListOf<String>()
                        turn.requiredChangedPaths.forEach { path ->
                            if (path !in result.report.changedPaths) failures+="missing changed path: ${path}"
                        }
                        turn.forbiddenPaths.forEach { path ->
                            if (path in result.report.changedPaths) failures+="forbidden path changed: ${path}"
                        }
                        if (failures.isEmpty()) {
                            expectations+=EvalExpectationDetail(
                                "turn",true,"changed=${result.report.changedPaths.sorted().joinToString(",")}"
                            )
                        } else {
                            allOk=false
                            expectations+=EvalExpectationDetail("turn",false,failures.joinToString("; "))
                        }
                    }
                    is RejectedResult -> {
                        allOk=false
                        expectations+=EvalExpectationDetail("turn",false,"rejected: ${result.message}")
                    }
                }
            }
            if (needsGit) {
                val branch=try {
                    GitWorkspace.currentBranch(temp)
                } catch (_:Exception) {
                    null
                }
                val stable=branch is GitBranchState.Named && branch.branch==EVAL_BRANCH
                if (!stable) {
                    allOk=false
                    expectations+=EvalExpectationDetail("branch",false,"eval branch changed or became unreadable")
                } else {
                    expectations+=EvalExpectationDetail("branch",true,EVAL_BRANCH)
                }
            }
            val after=snapshot(temp)
            val unrelated=(before.keys+after.keys)
                .filter { path ->
                    val beforeBytes=before[path]
                    val afterBytes=after[path]
                    beforeBytes==null||afterBytes==null||!beforeBytes.contentEquals(afterBytes)
                }
                .toSortedSet()-changedPaths
            if (unrelated.isNotEmpty()) {
                allOk=false
                expectations+=EvalExpectationDetail("files",false,"unrelated files modified: ${unrelated.joinToString(",")}")
            }
            return EvalCaseResult(case.id,allOk,expectations,inputTokens,outputTokens)
        } finally {
            deleteRecursively(temp)
        }
    }

    private fun buildPipeline(
        repositoryRoot:Path,
        case:EvalCase,
        values:EvalOptionValues,
        provider:ModelProvider,
        secret:String
    ):EvalPipeline {
        val repositoryIndex=RepositoryIndex()
        val snapshot=repositoryIndex.build(repositoryRoot)
        check(snapshot.profile.supported) { "unsupported FTC repository" }
        val knowledgeRetriever=KnowledgeRetriever(values.knowledge,case.team,case.season)
        val outboundProvider=RedactingModelProvider(provider,setOf(secret))
        val conversation=ConversationState(outboundProvider,setOf(secret))
        val retrievalPlanner=RetrievalPlanner(outboundProvider)
        val contextRetriever=ContextRetriever(repositoryIndex,knowledgeRetriever)
        val summary=repositorySummary(
            snapshot.profile.sourceModules,
            snapshot.profile.markers.size,
            snapshot.documents.size
        )
        val agent=AskAgent(
            retrievalPlanner,
            contextRetriever,
            AnswerGenerator(outboundProvider,repositoryIndex),
            conversation,
            summary
        )
        val editEngine=FileEditEngine(snapshot.root)
        val history=EditHistory(snapshot.root,editEngine,snapshot.root)
        val editAgent=EditAgent(
            retrievalPlanner,contextRetriever,outboundProvider,repositoryIndex,
            editEngine,history,conversation,summary
        )
        val controller=SessionController(agent,editAgent,history,snapshot.root,repositoryIndex)
        return EvalPipeline(agent,controller)
    }

    private fun initializeSyntheticRepository(repository:Path):Boolean=try {
        val git=Git.init().setDirectory(repository.toFile()).setInitialBranch(EVAL_BRANCH).call()
        git.repository.config.apply {
            setString("user",null,"name","FTC Eval")
            setString("user",null,"email","eval@example.invalid")
            save()
        }
        git.add().addFilepattern(".").call()
        git.commit().setMessage("eval baseline").call()
        git.close()
        true
    } catch (_:Exception) {
        false
    }

    private fun copyTree(source:Path,target:Path) {
        Files.walk(source).use { paths ->
            paths.forEach { path ->
                val destination=target.resolve(source.relativize(path).toString())
                if (Files.isDirectory(path)) Files.createDirectories(destination)
                else Files.copy(path,destination,StandardCopyOption.COPY_ATTRIBUTES)
            }
        }
    }

    private fun deleteRecursively(path:Path) {
        Files.walk(path).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    private fun snapshot(root:Path):Map<String,ByteArray> {
        val result=sortedMapOf<String,ByteArray>()
        Files.walk(root).use { paths ->
            paths
                .filter { path -> path==root || !root.relativize(path).startsWith(GIT_DIRECTORY) }
                .filter(Files::isRegularFile)
                .forEach { path -> result[root.relativize(path).toString()]=Files.readAllBytes(path) }
        }
        return result
    }

    private fun repositorySummary(sourceModules:Set<String>,markerCount:Int,documentCount:Int):String=buildString {
        append("supported=true")
        append("; sourceModules=").append(sourceModules.sorted().joinToString(","))
        append("; markerCount=").append(markerCount)
        append("; documentCount=").append(documentCount)
    }

    private fun renderReport(profile:ProviderProfile,results:List<EvalCaseResult>):String=buildString {
        append("# FTC Knowledge Bank eval\n\n")
        append("provider=").append(profile.name).append(" model=").append(profile.model).append('\n')
        append("cases: ").append(results.size).append(" run, ").append(results.count { it.passed }).append(" passed\n\n")
        results.forEach { result ->
            append("## ").append(result.id).append(if (result.passed) " - PASS" else " - FAIL").append('\n')
            result.expectations.forEach { expectation ->
                append("- ").append(if (expectation.passed) "ok" else "fail").append(": ").append(expectation.label)
                if (expectation.detail.isNotBlank()) append(" - ").append(expectation.detail)
                append('\n')
            }
            if (result.inputTokens>0||result.outputTokens>0) {
                append("- usage: input=").append(result.inputTokens).append(" output=").append(result.outputTokens).append('\n')
            }
            append('\n')
        }
    }

    private companion object {
        const val EVAL_BRANCH="eval"
        val GIT_DIRECTORY=Path.of(".git")
    }
}

internal fun printEvalUsage(out:PrintStream) {
    out.println(
        "usage: knowledge-cli eval --cases PATH --knowledge PATH --provider NAME --output PATH [--config PATH]"
    )
}
