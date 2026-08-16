package org.ftckb.cli

import java.io.BufferedReader
import java.io.PrintStream
import java.nio.file.Path
import org.ftckb.model.ModelProvider
import org.ftckb.model.ProviderProfile
import org.ftckb.model.SecretResolver
import org.ftckb.model.openai.ProviderFactory
import org.ftckb.repository.RepositoryIndex

class ProductionChatLauncher(
    private val environment:(String)->String?={ name -> System.getenv(name) },
    private val providerCreator:(ProviderProfile,SecretResolver)->ModelProvider={ profile,resolver ->
        ProviderFactory.create(profile,resolver)
    },
    private val sessionsDirectory:()->Path={ Path.of(System.getProperty("user.home"),".ftckb","sessions") },
    private val historyIndexRefresher:(RepositoryIndex)->(Set<String>)->Unit={ index->
        { paths->index.refresh(paths) }
    }
):ChatLauncher {
    override fun run(options:ChatOptions,input:BufferedReader,out:PrintStream):Int {
        val runtime=try {
            SessionRuntime(
                options.config,
                environment,
                providerCreator,
                sessionsDirectory,
                historyIndexRefresher,
                options.repository,options.knowledge,options.team,options.season,options.provider
            )
        } catch (failure:SessionAssemblyException) {
            out.println("error starting chat: ${failure.message}")
            return 2
        }
        return ChatRepl(
            runtime.session(),input,out,runtime.controller(),runtime.repositoryRoot,
            runtime.baselineDirtyPaths,runtime::redact
        ).run()
    }
}
