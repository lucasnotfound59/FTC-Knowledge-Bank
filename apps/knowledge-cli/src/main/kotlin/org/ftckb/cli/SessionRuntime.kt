package org.ftckb.cli

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
    initialProvider:String
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

    lateinit var providerName:String; private set
    lateinit var team:String; private set
    lateinit var season:String; private set
    lateinit var repositoryRoot:Path; private set
    var baselineDirtyPaths:Set<String>?=null; private set

    init {
        providerName=initialProvider
        profile=try { config.profile(initialProvider) } catch (_:Exception) {
            throw SessionAssemblyException.UnknownProvider()
        }
        secret=secretResolver(profile.apiKeyEnv)?.takeIf(String::isNotBlank)
            ?:throw SessionAssemblyException.MissingSecret(profile.apiKeyEnv)
        team=initialTeam
        season=initialSeason
        bootstrap(initialRepository,initialKnowledge,initialTeam,initialSeason)
        swapProvider.replace(createProvider(), setOf(secret))
        conversation.replaceSecrets(swapProvider.currentSecrets())
        rebuildAgents()
    }

    fun controller():SessionController=controller
    fun session():AskChatSession=askSession
    fun redact(text:String):String=
        org.ftckb.agent.CredentialRedactor.redact(text,swapProvider.currentSecrets())

    /** Changes the model provider profile; conversation history is retained. */
    fun reconfigureProvider(name:String) {
        config=decodeConfig()
        val nextProfile=try { config.profile(name) } catch (_:Exception) {
            throw SessionAssemblyException.UnknownProvider()
        }
        val nextSecret=secretResolver(nextProfile.apiKeyEnv)?.takeIf(String::isNotBlank)
            ?:throw SessionAssemblyException.MissingSecret(nextProfile.apiKeyEnv)
        profile=nextProfile
        secret=nextSecret
        providerName=name
        swapProvider.replace(createProvider(), setOf(secret))
        conversation.replaceSecrets(swapProvider.currentSecrets())
        refreshSession()
    }

    /** Changes team/season (and optionally the knowledge root); history is retained. */
    fun reconfigureKnowledge(knowledge:Path,nextTeam:String,nextSeason:String) {
        knowledgeRetriever=try {
            KnowledgeRetriever(knowledge,nextTeam,nextSeason)
        } catch (_:Exception) {
            throw SessionAssemblyException.KnowledgeInvalid()
        }
        team=nextTeam
        season=nextSeason
        contextRetriever=ContextRetriever(repositoryIndex,knowledgeRetriever)
        rebuildAgents()
    }

    /** Changes the repository; requires a clean Edit state (caller checks). */
    fun reconfigureRepository(repository:Path) {
        bootstrap(repository,knowledgeRoot,team,season)
        rebuildAgents()
    }

    /** Resets the conversation transcript; Edit history is kept. */
    fun clearConversation() {
        conversation=ConversationState(swapProvider,swapProvider.currentSecrets())
        rebuildAgents()
    }

    private fun bootstrap(repository:Path,knowledge:Path,nextTeam:String,nextSeason:String) {
        repositoryIndex=RepositoryIndex()
        val snapshot=try {
            repositoryIndex.build(repository)
        } catch (_:Exception) {
            throw SessionAssemblyException.RepositoryUnreadable()
        }
        if (!snapshot.profile.supported) throw SessionAssemblyException.UnsupportedRepository()
        repositoryRoot=snapshot.root
        baselineDirtyPaths=runCatching { GitWorkspace.inspect(snapshot.root).dirtyPaths }.getOrNull()
        knowledgeRoot=knowledge
        repositorySummary=buildString {
            append("supported=true")
            append("; sourceModules=").append(snapshot.profile.sourceModules.sorted().joinToString(","))
            append("; markerCount=").append(snapshot.profile.markers.size)
            append("; documentCount=").append(snapshot.documents.size)
        }
        knowledgeRetriever=try {
            KnowledgeRetriever(knowledge,nextTeam,nextSeason)
        } catch (_:Exception) {
            throw SessionAssemblyException.KnowledgeInvalid()
        }
        history=EditHistory(snapshot.root,FileEditEngine(snapshot.root),snapshot.root)
        retrievalPlanner=RetrievalPlanner(swapProvider)
        contextRetriever=ContextRetriever(repositoryIndex,knowledgeRetriever)
    }

    private fun rebuildAgents() {
        askAgent=AskAgent(
            retrievalPlanner,contextRetriever,
            AnswerGenerator(swapProvider,repositoryIndex),
            conversation,repositorySummary
        )
        editAgent=EditAgent(
            retrievalPlanner,contextRetriever,swapProvider,repositoryIndex,
            FileEditEngine(repositoryRoot),history,conversation,repositorySummary
        )
        controller=SessionController(
            askAgent,editAgent,history,repositoryRoot,repositoryIndex,
            indexRefresher=historyIndexRefresher(repositoryIndex)
        )
        refreshSession()
    }

    private fun refreshSession() {
        askSession=RuntimeAskChatSession(
            askAgent,
            ConversationSaver(profile.name,profile.model),
            ChatStatus(repositoryRoot,team,season,providerName,profile.model),
            sessionsDirectory,repositoryIndex
        )
    }

    private fun createProvider():ModelProvider=try {
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
    class KnowledgeInvalid:SessionAssemblyException("invalid knowledge root")
    class ProviderInit:SessionAssemblyException("model provider initialization failed")
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
