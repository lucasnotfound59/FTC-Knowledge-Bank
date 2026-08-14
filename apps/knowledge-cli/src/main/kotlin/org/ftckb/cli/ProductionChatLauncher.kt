package org.ftckb.cli

import java.io.BufferedReader
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import org.ftckb.agent.AgentAnswer
import org.ftckb.agent.AnswerGenerator
import org.ftckb.agent.AskAgent
import org.ftckb.agent.ContextRetriever
import org.ftckb.agent.ConversationSaver
import org.ftckb.agent.ConversationState
import org.ftckb.agent.KnowledgeRetriever
import org.ftckb.agent.RetrievalPlanner
import org.ftckb.model.ModelProvider
import org.ftckb.model.ProviderConfigLoader
import org.ftckb.model.ProviderProfile
import org.ftckb.model.SecretResolver
import org.ftckb.model.openai.ProviderFactory
import org.ftckb.repository.RepositoryIndex

class ProductionChatLauncher(
    private val environment:(String)->String?={ name -> System.getenv(name) },
    private val providerCreator:(ProviderProfile,SecretResolver)->ModelProvider={ profile,resolver ->
        ProviderFactory.create(profile,resolver)
    },
    private val sessionsDirectory:()->Path={ Path.of(System.getProperty("user.home"),".ftckb","sessions") }
):ChatLauncher {
    override fun run(options:ChatOptions,input:BufferedReader,out:PrintStream):Int {
        val config=try {
            ProviderConfigLoader.decode(Files.readString(options.config))
        } catch (_:Exception) {
            out.println("error starting chat: invalid provider configuration")
            return 2
        }
        val profile=try {
            config.profile(options.provider)
        } catch (_:Exception) {
            out.println("error starting chat: unknown provider profile: ${options.provider}")
            return 2
        }
        val secret=environment(profile.apiKeyEnv)?.takeIf { it.isNotBlank() }
        if (secret==null) {
            out.println("error starting chat: missing API key environment variable: ${profile.apiKeyEnv}")
            return 2
        }
        val repositoryIndex=RepositoryIndex()
        val snapshot=try {
            repositoryIndex.build(options.repository)
        } catch (_:Exception) {
            out.println("error starting chat: repository is not readable")
            return 2
        }
        if (!snapshot.profile.supported) {
            out.println("error starting chat: unsupported FTC repository")
            return 2
        }
        val knowledgeRetriever=try {
            KnowledgeRetriever(options.knowledge,options.team,options.season)
        } catch (_:Exception) {
            out.println("error starting chat: invalid knowledge root")
            return 2
        }
        val provider=try {
            providerCreator(profile,SecretResolver { name -> if (name==profile.apiKeyEnv) secret else null })
        } catch (_:Exception) {
            out.println("error starting chat: model provider initialization failed")
            return 2
        }
        val conversation=ConversationState(provider,setOf(secret))
        val agent=AskAgent(
            RetrievalPlanner(provider),
            ContextRetriever(repositoryIndex,knowledgeRetriever),
            AnswerGenerator(provider,repositoryIndex),
            conversation,
            repositorySummary(snapshot.root,snapshot.documents.keys,snapshot.profile.sourceModules)
        )
        val session=ProductionAskChatSession(
            agent,
            ConversationSaver(profile.name,profile.model),
            ChatStatus(snapshot.root,options.team,options.season,profile.name,profile.model),
            sessionsDirectory
        )
        return ChatRepl(session,input,out).run()
    }

    private fun repositorySummary(root:Path,paths:Set<String>,sourceModules:Set<String>):String=buildString {
        append("root=").append(root)
        append("; sourceModules=").append(sourceModules.sorted().joinToString(","))
        append("; indexedPaths=").append(paths.sorted().joinToString(","))
    }
}

private class ProductionAskChatSession(
    private val agent:AskAgent,
    private val saver:ConversationSaver,
    private val chatStatus:ChatStatus,
    private val sessionsDirectory:()->Path
):AskChatSession {
    override fun ask(question:String):AgentAnswer {
        agent.ask(question)
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
            path==null -> {
                Files.createDirectories(directory)
                directory.resolve("${saveTimestamp.format(Instant.now())}.md")
            }
            path.isAbsolute -> path.normalize()
            else -> {
                Files.createDirectories(directory)
                directory.resolve(path).normalize().also {
                    require(it.startsWith(directory)) { "relative save path escapes the sessions directory" }
                }
            }
        }.toAbsolutePath().normalize()
        require(!insideRepository(destination)) { "save path is inside the FTC repository" }
        return destination
    }

    private fun insideRepository(destination:Path):Boolean {
        val repository=chatStatus.repository.toRealPath()
        if (destination.startsWith(repository)) return true
        val parent=destination.parent ?:return false
        val realParent=runCatching { parent.toRealPath() }.getOrNull() ?:return false
        return realParent.resolve(destination.fileName).normalize().startsWith(repository)
    }

    private companion object {
        val saveTimestamp:DateTimeFormatter=DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC)
    }
}
