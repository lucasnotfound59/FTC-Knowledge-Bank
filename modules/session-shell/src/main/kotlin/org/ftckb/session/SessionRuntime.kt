package org.ftckb.session

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import org.ftckb.agent.AgentAnswer
import org.ftckb.agent.AnswerGenerator
import org.ftckb.agent.AskAgent
import org.ftckb.agent.ConversationSaver
import org.ftckb.agent.ConversationState
import org.ftckb.agent.ContextRetriever
import org.ftckb.agent.GuideTraversalException
import org.ftckb.agent.KnowledgeAccessException
import org.ftckb.agent.KnowledgeRetriever
import org.ftckb.agent.RetrievalPlanner
import org.ftckb.agent.SessionController
import org.ftckb.agent.edit.EditAgent
import org.ftckb.agent.edit.EditHistory
import org.ftckb.agent.edit.FileEditEngine
import org.ftckb.git.GitWorkspace
import org.ftckb.model.ModelProvider
import org.ftckb.model.ProviderConfigLoader
import org.ftckb.model.ProviderProfile
import org.ftckb.model.SecretResolver
import org.ftckb.repository.RepositoryIndex

/**
 * Assembled agent session shared by the chat REPL and the web serve mode.
 * Supports live reconfigure (provider / team / season / knowledge / repository)
 * while keeping the conversation history unless the user clears it explicitly.
 */
class SessionRuntime(
    private val configPath:Path,
    private val secretResolver:(String)->String?,
    private val providerCreator:(ProviderProfile,SecretResolver)->ModelProvider,
    private val sessionsDirectory:()->Path,
    private val historyIndexRefresher:(RepositoryIndex)->(Set<String>)->Unit,
    initialRepository:Path,
    initialKnowledge:Path,
    initialTeam:String,
    initialSeason:String,
    initialProvider:String,
    initialRuleProfiles:Set<String>
) {
    private val swapProvider=SwapModelProvider(ModelProvider {
        throw IllegalStateException("session is not configured")
    })
    private var conversation=ConversationState(swapProvider,emptySet())
    private var config=decodeConfig()
    private lateinit var profile:ProviderProfile
    private lateinit var secret:String
    private var repositoryIndex=RepositoryIndex()
    private lateinit var knowledgeRetriever:KnowledgeRetriever
    private lateinit var history:EditHistory
    private lateinit var knowledgeRoot:Path
    private lateinit var repositorySummary:String
    private lateinit var retrievalPlanner:RetrievalPlanner
    private lateinit var contextRetriever:ContextRetriever
    private lateinit var askAgent:AskAgent
    private lateinit var editAgent:EditAgent
    private lateinit var controller:SessionController
    private lateinit var askSession:AskChatSession

    var ruleProfiles:Set<String> =normalizeProfiles(initialRuleProfiles); private set
    lateinit var providerName:String; private set
    lateinit var team:String; private set
    lateinit var season:String; private set
    lateinit var repositoryRoot:Path; private set
    var baselineDirtyPaths:Set<String>?=null; private set

    init {
        reconfigure(ruleProfiles,initialKnowledge,initialTeam,initialSeason,initialRepository,initialProvider)
    }

    fun controller():SessionController=controller
    fun session():AskChatSession=askSession
    fun redact(text:String):String=
        org.ftckb.agent.CredentialRedactor.redact(text,swapProvider.currentSecrets())

    /** Preflights the whole configuration before publishing any new session state. */
    fun reconfigure(
        nextRuleProfiles:Set<String>,
        knowledge:Path?=null,
        nextTeam:String=team,
        nextSeason:String=season,
        repository:Path?=null,
        provider:String?=null
    ) {
        val profiles=normalizeProfiles(nextRuleProfiles)
        val nextConfig=if (provider!=null) decodeConfig() else config
        val nextProfile=if (provider!=null) try { nextConfig.profile(provider) } catch (_:Exception) {
            throw SessionAssemblyException.UnknownProvider()
        } else profile
        val nextSecret=if (provider!=null) secretResolver(nextProfile.apiKeyEnv)?.takeIf(String::isNotBlank)
            ?:throw SessionAssemblyException.MissingSecret(nextProfile.apiKeyEnv) else secret
        val nextKnowledgeRoot=knowledge ?: knowledgeRoot
        val nextKnowledge=loadKnowledge(nextKnowledgeRoot,nextTeam,nextSeason,profiles)
        val nextIndex=if (repository!=null) RepositoryIndex() else repositoryIndex
        val snapshot=if (repository!=null) try {
            nextIndex.build(repository).also {
                if (!it.profile.supported) throw SessionAssemblyException.UnsupportedRepository()
            }
        } catch (error:SessionAssemblyException) {
            throw error
        } catch (_:Exception) {
            throw SessionAssemblyException.RepositoryUnreadable()
        } else null
        val nextHistory=snapshot?.let { EditHistory(it.root,FileEditEngine(it.root),it.root) }
        val nextBaseline=snapshot?.let { runCatching { GitWorkspace.inspect(it.root).dirtyPaths }.getOrNull() }
        val contextChanged=knowledge!=null || repository!=null || !this::knowledgeRoot.isInitialized ||
            nextTeam!=team || nextSeason!=season || profiles!=ruleProfiles
        if (contextChanged && this::askSession.isInitialized &&
            (currentMode()==org.ftckb.agent.AgentMode.EDIT || hasEditChanges())
        ) throw SessionAssemblyException.ReconfigurationBlocked()
        val nextRoot=snapshot?.root ?: repositoryRoot
        val nextSummary=if (snapshot!=null) buildString {
            append("supported=true")
            append("; sourceModules=").append(snapshot.profile.sourceModules.sorted().joinToString(","))
            append("; markerCount=").append(snapshot.profile.markers.size)
            append("; documentCount=").append(snapshot.documents.size)
        } else repositorySummary
        val nextStatus=ChatStatus(nextRoot,nextTeam,nextSeason,provider ?: providerName,nextProfile.model,profiles)
        val nextGraph=if (contextChanged) assembleAgents(
            nextIndex,nextKnowledge,nextRoot,nextHistory ?: history,nextSummary,nextProfile,nextStatus,conversation
        ) else null
        val nextSession=nextGraph?.session ?: makeSession(askAgent,nextProfile,nextStatus,nextIndex)
        val nextProvider=if (provider!=null) createProvider(nextProfile,nextSecret) else null
        config=nextConfig
        profile=nextProfile
        secret=nextSecret
        if (provider!=null) providerName=provider
        if (nextProvider!=null) {
            swapProvider.replace(nextProvider,setOf(nextSecret))
            conversation.replaceSecrets(swapProvider.currentSecrets())
        }
        knowledgeRoot=nextKnowledgeRoot
        team=nextTeam
        season=nextSeason
        ruleProfiles=profiles
        if (snapshot!=null) {
            repositoryIndex=nextIndex
            repositoryRoot=snapshot.root
            baselineDirtyPaths=nextBaseline
            history=nextHistory!!
            repositorySummary=nextSummary
        }
        if (nextGraph!=null) {
            knowledgeRetriever=nextKnowledge
            installAgents(nextGraph)
        }
        askSession=nextSession
    }

    /** Changes provider while preserving explicit rule context and conversation. */
    fun reconfigureProvider(name:String)=reconfigure(ruleProfiles,provider=name)

    /** Applies persisted UI settings; an unchanged knowledge path is not an explicit reload. */
    fun reconfigureSettings(
        nextRuleProfiles:Set<String>,knowledge:Path,nextTeam:String,nextSeason:String,provider:String
    )=reconfigure(
        nextRuleProfiles,
        knowledge.takeUnless { it.toAbsolutePath().normalize()==knowledgeRoot.toAbsolutePath().normalize() },
        nextTeam,nextSeason,provider=provider
    )

    fun reconfigureKnowledge(knowledge:Path,nextTeam:String,nextSeason:String,nextRuleProfiles:Set<String>)=
        reconfigure(nextRuleProfiles,knowledge,nextTeam,nextSeason)

    /** Repository changes always require an explicit profile selection. */
    fun reconfigureRepository(repository:Path,nextRuleProfiles:Set<String>)=
        reconfigure(nextRuleProfiles,repository=repository)

    fun clearConversation() {
        val nextConversation=ConversationState(swapProvider,swapProvider.currentSecrets())
        val nextGraph=assembleAgents(
            repositoryIndex,knowledgeRetriever,repositoryRoot,history,repositorySummary,
            profile,askSession.status(),nextConversation
        )
        conversation=nextConversation
        installAgents(nextGraph)
    }

    private fun normalizeProfiles(profiles:Set<String>):Set<String> =try {
        org.ftckb.domain.RuleProfiles.normalize(profiles)
    } catch (error:IllegalArgumentException) {
        throw SessionAssemblyException.KnowledgeInvalid(error.message ?: "invalid rule profiles")
    }

    private fun loadKnowledge(knowledge:Path,nextTeam:String,nextSeason:String,profiles:Set<String>):KnowledgeRetriever=try {
        KnowledgeRetriever(knowledge,nextTeam,nextSeason,ruleProfiles=profiles)
    } catch (error:IllegalArgumentException) {
        throw SessionAssemblyException.KnowledgeInvalid(error.message ?: "invalid knowledge root")
    } catch (_:Exception) {
        throw SessionAssemblyException.KnowledgeInvalid()
    }

    private data class AgentGraph(
        val planner:RetrievalPlanner,val context:ContextRetriever,val ask:AskAgent,val edit:EditAgent,
        val controller:SessionController,val session:AskChatSession
    )

    private fun assembleAgents(
        index:RepositoryIndex,knowledge:KnowledgeRetriever,root:Path,editHistory:EditHistory,
        summary:String,providerProfile:ProviderProfile,status:ChatStatus,transcript:ConversationState
    ):AgentGraph=try {
        val planner=RetrievalPlanner(swapProvider)
        val context=ContextRetriever(index,knowledge)
        val ask=AskAgent(
            planner,context,AnswerGenerator(swapProvider,index),transcript,summary
        )
        val edit=EditAgent(
            planner,context,swapProvider,index,FileEditEngine(root),editHistory,transcript,summary
        )
        val nextController=SessionController(
            ask,edit,editHistory,root,index,indexRefresher=historyIndexRefresher(index)
        )
        AgentGraph(planner,context,ask,edit,nextController,makeSession(ask,providerProfile,status,index))
    } catch (_:java.io.IOException) {
        throw SessionAssemblyException.RepositoryUnreadable()
    } catch (_:Exception) {
        throw SessionAssemblyException.AgentAssembly()
    }

    private fun installAgents(graph:AgentGraph) {
        retrievalPlanner=graph.planner
        contextRetriever=graph.context
        askAgent=graph.ask
        editAgent=graph.edit
        controller=graph.controller
        askSession=graph.session
    }

    private fun makeSession(agent:AskAgent,providerProfile:ProviderProfile,status:ChatStatus,index:RepositoryIndex):AskChatSession=
        RuntimeAskChatSession(agent,ConversationSaver(providerProfile.name,providerProfile.model),status,sessionsDirectory,index)

    private fun createProvider(profile:ProviderProfile,secret:String):ModelProvider=try {
        providerCreator(profile,SecretResolver { name-> if (name==profile.apiKeyEnv) secret else null })
    } catch (_:Exception) {
        throw SessionAssemblyException.ProviderInit()
    }

    private fun decodeConfig():org.ftckb.model.ProviderConfig=try {
        ProviderConfigLoader.decode(Files.readString(configPath))
    } catch (_:Exception) {
        throw SessionAssemblyException.InvalidConfig()
    }

    fun hasEditChanges():Boolean=controller.changes().isNotEmpty()

    fun currentMode()=controller.mode

    fun currentKnowledgeRoot():Path=knowledgeRoot
}

sealed class SessionAssemblyException(detail:String):RuntimeException(detail) {
    class InvalidConfig:SessionAssemblyException("invalid provider configuration")
    class UnknownProvider:SessionAssemblyException("unknown or invalid provider profile")
    class MissingSecret(envName:String):SessionAssemblyException(
        "missing API key environment variable: $envName")
    class RepositoryUnreadable:SessionAssemblyException("repository is not readable")
    class UnsupportedRepository:SessionAssemblyException("unsupported FTC repository")
    class KnowledgeInvalid(detail:String="invalid knowledge root"):SessionAssemblyException(detail)
    class ReconfigurationBlocked:SessionAssemblyException("rule context changes require Ask mode without outstanding Agent changes")
    class ProviderInit:SessionAssemblyException("model provider initialization failed")
    class AgentAssembly:SessionAssemblyException("session agent initialization failed")
}

internal class RuntimeAskChatSession(
    private val agent:AskAgent,
    private val saver:ConversationSaver,
    private val chatStatus:ChatStatus,
    private val sessionsDirectory:()->Path,
    private val repositoryIndex:RepositoryIndex
):AskChatSession {
    override fun ask(question:String):AgentAnswer {
        try {
            repositoryIndex.build(chatStatus.repository)
        } catch (_:Exception) {
            throw AskChatSessionException.RepositoryRead()
        }
        try {
            agent.ask(question)
        } catch (_:KnowledgeAccessException) {
            throw AskChatSessionException.KnowledgeRead()
        } catch (_:GuideTraversalException) {
            throw AskChatSessionException.KnowledgeRead()
        }
        return agent.conversation.context().recentTurns.last().answer
    }

    override fun status():ChatStatus=chatStatus

    override fun save(path:Path?):Path {
        val destination=saveDestination(path)
        return saver.save(agent.conversation,destination)
    }

    private fun saveDestination(path:Path?):Path {
        val directory=sessionsDirectory()
            .toAbsolutePath()
            .normalize()
        val destination=when {
            path==null -> directory.resolve("${saveTimestamp.format(Instant.now())}.md")
            path.isAbsolute -> path.normalize()
            else -> directory.resolve(path).normalize().also {
                require(it.startsWith(directory)) { "relative save path escapes the sessions directory" }
            }
        }.toAbsolutePath().normalize()
        require(!insideRepository(destination)) { "save path is inside the FTC repository" }
        if (path==null || !path.isAbsolute) Files.createDirectories(directory)
        return destination
    }

    private fun insideRepository(destination:Path):Boolean {
        val repository=chatStatus.repository.toRealPath()
        if (destination.startsWith(repository)) return true
        return canonicalizeThroughExistingAncestor(destination)?.startsWith(repository)==true
    }

    private fun canonicalizeThroughExistingAncestor(destination:Path):Path? {
        var ancestor:Path?=destination.parent
        while (ancestor!=null && !Files.exists(ancestor)) ancestor=ancestor.parent
        val existing=ancestor ?:return null
        val suffix=existing.relativize(destination)
        return runCatching { existing.toRealPath().resolve(suffix).normalize() }.getOrNull()
    }

    private companion object {
        val saveTimestamp:DateTimeFormatter=DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC)
    }
}
