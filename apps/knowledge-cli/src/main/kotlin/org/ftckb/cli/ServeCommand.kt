package org.ftckb.cli

import com.sun.net.httpserver.HttpServer
import java.io.PrintStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Path
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import org.ftckb.model.ModelProvider
import org.ftckb.model.ProviderProfile
import org.ftckb.model.SecretResolver
import org.ftckb.model.openai.ProviderFactory
import org.ftckb.repository.RepositoryIndex
import org.ftckb.session.SessionAssemblyException
import org.ftckb.session.SessionRuntime

/**
 * Production runner for `ftckb serve`: binds a loopback-only HTTP server,
 * prints the one-time URL/token, optionally opens the browser, and blocks
 * until /api/shutdown (or process termination) releases the latch.
 */
class ServeCommand(
    private val environment:(String)->String?=System::getenv,
    private val providerCreator:(ProviderProfile,SecretResolver)->ModelProvider={ profile,resolver ->
        ProviderFactory.create(profile,resolver)
    },
    private val sessionsDirectory:()->Path={ Path.of(System.getProperty("user.home"),".ftckb","sessions") },
    private val secretPrompt:(String)->String?={ env -> readSecretFromConsole(env) },
    private val browserOpener:(String)->Unit={ url -> openBrowser(url) },
    private val awaitShutdown:(CountDownLatch)->Int={ latch -> latch.await(); 0 }
):ServeRunner {
    override fun run(options:ServeOptions,out:PrintStream):Int {
        val runtime=try {
            SessionRuntime(
                options.config,
                { name ->
                    environment(name)?.takeIf(String::isNotBlank)
                        ?:secretPrompt(name)?.takeIf(String::isNotBlank)
                },
                providerCreator,sessionsDirectory,
                { index -> { paths -> index.refresh(paths) } },
                options.repository,options.knowledge,options.team,options.season,options.provider
            )
        } catch (failure:SessionAssemblyException) {
            out.println("error starting serve: ${failure.message}")
            return 2
        }
        val token=generateToken()
        val api=ServeApi(runtime,token)
        val server=HttpServer.create(
            InetSocketAddress(InetAddress.getLoopbackAddress(),options.port),0
        )
        server.createContext("/",api)
        server.executor=Executors.newCachedThreadPool()
        server.start()
        val url="http://127.0.0.1:${server.address.port}/?token=$token"
        out.println("serve=ok")
        out.println("url=$url")
        out.println("token=$token")
        if (!options.noBrowser) browserOpener(url)
        val code=awaitShutdown(api.shutdownLatch)
        server.stop(1)
        api.shutdownAgentThread()
        (server.executor as? java.util.concurrent.ExecutorService)?.shutdownNow()
        return code
    }

    private fun generateToken():String {
        val bytes=ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}

private fun readSecretFromConsole(envName:String):String? {
    val console=System.console() ?: return null
    val chars=console.readPassword("Enter API key for $envName (hidden): ")
    return if (chars==null) null else String(chars)
}

private fun openBrowser(url:String) {
    val os=System.getProperty("os.name").lowercase()
    val command=when {
        os.contains("mac") -> listOf("open",url)
        os.contains("win") -> listOf("cmd","/c","start",url)
        else -> listOf("xdg-open",url)
    }
    runCatching { ProcessBuilder(command).start() }
}
