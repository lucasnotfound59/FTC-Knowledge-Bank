package org.ftckb.intellij

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import java.nio.file.Path
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import org.ftckb.agent.AgentAnswer
import org.ftckb.agent.CitationValidationException
import org.ftckb.session.AskChatSessionException
import org.ftckb.model.ModelProvider
import org.ftckb.model.ModelProviderException
import org.ftckb.model.ProviderProfile
import org.ftckb.model.SecretResolver
import org.ftckb.model.openai.ProviderFactory
import org.ftckb.repository.RepositoryIndex
import org.ftckb.session.SessionAssemblyException
import org.ftckb.session.SessionRuntime

sealed interface AskOutcome {
    data class Answered(val answer:AgentAnswer):AskOutcome
    data class Failed(val code:String,val message:String):AskOutcome
}

/** Owns the SessionRuntime, the serial executor, configuration state, and
 * credential lookups (env var -> PasswordSafe -> one-time prompt). */
class FtckbService(private val project:Project):Disposable {
    private val executor:ExecutorService=Executors.newSingleThreadExecutor()
    private val settings get() = FtckbSettings.of(project)
    @Volatile private var runtime:SessionRuntime?=null

    fun initialize():String? {
        val root=project.basePath?.let(Path::of)
        if (root==null) return "无法读取当前项目路径"
        val state=settings.snapshot()
        val configPath=state.configPath.ifBlank { defaultConfigPath() }
        val knowledgePath=state.knowledgePath.ifBlank { KnowledgeResources.extractOrDefault().toString() }
        val secretName=providerSecretEnv(configPath,state.provider)
        return try {
            runtime=SessionRuntime(
                Path.of(configPath),
                { name -> resolveSecret(name) },
                { profile,resolver -> ProviderFactory.create(profile,resolver) },
                { Path.of(System.getProperty("user.home"),".ftckb","sessions") },
                { index -> { paths -> index.refresh(paths) } },
                root,Path.of(knowledgePath),state.team,state.season,state.provider
            )
            null
        } catch (failure:SessionAssemblyException) {
            if (failure is SessionAssemblyException.MissingSecret) {
                val secret=promptAndStoreSecret(secretName)
                if (secret!=null) return retryWithSecret(secret,state,root,configPath,knowledgePath)
            }
            failure.message
        }
    }

    fun reconfigure(state:FtckbSettingsState):String? {
        settings.apply(state)
        runtime?.let { current ->
            try {
                current.reconfigureProvider(state.provider)
            } catch (_:Exception) {
                // fall through to full rebuild
                runtime=null
            }
            try {
                current.reconfigureKnowledge(
                    Path.of(state.knowledgePath.ifBlank { KnowledgeResources.extractOrDefault().toString() }),
                    state.team,state.season
                )
            } catch (_:Exception) {
                runtime=null
            }
        }
        if (runtime==null) return initialize()
        return null
    }

    fun initializeAsync(onDone:(String?)->Unit) {
        executor.submit {
            val error=initialize()
            ApplicationManager.getApplication().invokeLater { onDone(error) }
        }
    }

    fun ask(question:String,onDone:(AskOutcome)->Unit) {
        executor.submit {
            val active=runtime
            val outcome=when {
                active==null -> AskOutcome.Failed("configure","会话未初始化，请先检查设置与密钥")
                else -> try {
                    AskOutcome.Answered(active.session().ask(question))
                } catch (_:ModelProviderException) {
                    AskOutcome.Failed("provider","模型请求失败")
                } catch (_:CitationValidationException) {
                    AskOutcome.Failed("citation","引用校验失败")
                } catch (_:AskChatSessionException.RepositoryRead) {
                    AskOutcome.Failed("repository","当前项目不是可读的 FTC 仓库")
                } catch (_:AskChatSessionException.KnowledgeRead) {
                    AskOutcome.Failed("knowledge","知识库不可用")
                } catch (_:Exception) {
                    AskOutcome.Failed("internal","请求未能完成")
                }
            }
            ApplicationManager.getApplication().invokeLater { onDone(outcome) }
        }
    }

    fun clearConversation() {
        executor.submit { runtime?.clearConversation() }
    }

    fun sessionSave():String? {
        val active=runtime ?: return "会话未初始化"
        return try {
            active.session().save(null)
            null
        } catch (failure:Exception) {
            failure.message ?: "保存失败"
        }
    }

    fun settingsSnapshot():FtckbSettingsState=settings.snapshot()

    fun statusText():String {
        val state=settings.snapshot()
        val active=runtime
        return if (active==null) "未初始化"
        else "队伍${state.team} · ${state.season} · ${state.provider} · 模式=${active.currentMode().name.lowercase()}"
    }

    fun setModeAsk() { executor.submit { runtime?.let { it.controller().setMode(org.ftckb.agent.AgentMode.ASK) } } }
    fun setModeEdit():String? {
        val active=runtime ?: return "会话未初始化"
        return try {
            active.controller().setMode(org.ftckb.agent.AgentMode.EDIT)?.message
        } catch (_:Exception) {
            runCatching { active.controller().setMode(org.ftckb.agent.AgentMode.ASK) }
            "Edit 需要可读的 Git 工作树且当前分支有名字"
        }
    }

    fun undo(onDone:(String?)->Unit) { executor.submit {
        val message=runCatching {
            val result=runtime?.controller()?.undo()
            when (result) {
                is org.ftckb.agent.RejectedResult -> result.message
                is org.ftckb.agent.HistoryAppliedResult ->
                    if (result.result.succeeded) null
                    else "冲突，未覆盖文件：${result.result.conflicts.sorted().joinToString()}"
                null -> "会话未初始化"
            }
        }.getOrElse { "undo 请求未能完成" }
        ApplicationManager.getApplication().invokeLater { onDone(message) }
    } }

    fun discard(onDone:(String?)->Unit) { executor.submit {
        val message=runCatching {
            val result=runtime?.controller()?.discard()
            when (result) {
                is org.ftckb.agent.RejectedResult -> result.message
                is org.ftckb.agent.HistoryAppliedResult ->
                    if (result.result.succeeded) null
                    else "冲突，未覆盖文件：${result.result.conflicts.sorted().joinToString()}"
                null -> "会话未初始化"
            }
        }.getOrElse { "discard 请求未能完成" }
        ApplicationManager.getApplication().invokeLater { onDone(message) }
    } }

    fun changedFiles():List<String> =runtime?.controller()?.changes()?.map { it.path }?.sorted().orEmpty()

    fun fileChange(file:String):Pair<String?,String?>? {
        val change=runtime?.controller()?.changes()?.firstOrNull { it.path==file } ?: return null
        return change.before to change.after
    }

    private fun retryWithSecret(secret:String,state:FtckbSettingsState,root:Path,configPath:String,knowledgePath:String):String? {
        return try {
            runtime=SessionRuntime(
                Path.of(configPath),
                { name -> System.getenv(name)?.takeIf(String::isNotBlank) ?: secret },
                { profile,resolver -> ProviderFactory.create(profile,resolver) },
                { Path.of(System.getProperty("user.home"),".ftckb","sessions") },
                { index -> { paths -> index.refresh(paths) } },
                root,Path.of(knowledgePath),state.team,state.season,state.provider
            )
            null
        } catch (failure:SessionAssemblyException) {
            failure.message
        }
    }

    private fun resolveSecret(name:String):String? {
        System.getenv(name)?.takeIf(String::isNotBlank)?.let { return it }
        PasswordSafe.instance.getPassword(credentialAttributes(name))?.takeIf(String::isNotBlank)?.let { return it }
        return null
    }

    private fun promptAndStoreSecret(name:String):String? {
        var value:String?=null
        ApplicationManager.getApplication().invokeAndWait {
            value=Messages.showPasswordDialog(
                project,"请输入 API key（存储到系统钥匙串，不写入任何文件）：$name","FTC 知识库 API Key",null
            )
            value?.takeIf(String::isNotBlank)?.let { PasswordSafe.instance.setPassword(credentialAttributes(name),it) }
        }
        return value?.takeIf(String::isNotBlank)
    }

    private fun providerSecretEnv(configPath:String,provider:String):String {
        return runCatching {
            val config=org.ftckb.model.ProviderConfigLoader.decode(java.nio.file.Files.readString(Path.of(configPath)))
            config.profile(provider).apiKeyEnv
        }.getOrDefault(provider.uppercase()+"_API_KEY")
    }

    private fun defaultConfigPath():String=Path.of(System.getProperty("user.home"),".ftckb","config.yaml").toString()

    private fun credentialAttributes(secretName:String)=CredentialAttributes("ftckb-as:"+secretName)

    override fun dispose() {
        executor.shutdownNow()
    }

    companion object {
        fun of(project:Project):FtckbService=project.getService(FtckbService::class.java)
    }
}
