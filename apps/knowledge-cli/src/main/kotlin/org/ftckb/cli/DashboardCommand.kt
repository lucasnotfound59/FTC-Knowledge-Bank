package org.ftckb.cli

import java.io.PrintStream
import java.nio.file.Path

data class DashboardOptions(val knowledge:Path,val port:Int=0,val noBrowser:Boolean=false)

/** No provider configuration, key lookup or model is involved in the dashboard. */
fun runDashboardCommand(args:List<String>,out:PrintStream):Int {
    val options=try { parseDashboardOptions(args) } catch (_:IllegalArgumentException) {
        out.println("usage: ftckb dashboard --knowledge PATH [--port N] [--no-browser]"); return 64
    }
    val server=try { DashboardServer(options).start() } catch (_:Exception) {
        out.println("error starting dashboard: knowledge directory or local server unavailable"); return 2
    }
    out.println("dashboard=ok"); out.println("url=${server.url}")
    if (!options.noBrowser) {
        val command=when {
            System.getProperty("os.name").lowercase().contains("mac") -> listOf("open",server.url)
            System.getProperty("os.name").lowercase().contains("win") -> listOf("cmd","/c","start","",server.url)
            else -> listOf("xdg-open",server.url)
        }
        runCatching { ProcessBuilder(command).start() }
    }
    val hook=Thread { server.close() }
    Runtime.getRuntime().addShutdownHook(hook)
    return try { server.awaitShutdown(); 0 } catch (_:InterruptedException) { Thread.currentThread().interrupt(); 0 }
    finally { server.close(); runCatching { Runtime.getRuntime().removeShutdownHook(hook) } }
}

internal fun parseDashboardOptions(args:List<String>):DashboardOptions {
    var knowledge:Path?=null; var port=0; var noBrowser=false; val seen=mutableSetOf<String>(); var i=0
    while (i<args.size) {
        val option=args[i++]
        require(seen.add(option))
        when(option) {
            "--knowledge" -> { require(i<args.size && args[i].isNotBlank() && !args[i].startsWith("--")); knowledge=Path.of(args[i++]) }
            "--port" -> { require(i<args.size); val raw=args[i++]; require(Regex("[0-9]+").matches(raw)); port=raw.toIntOrNull() ?: throw IllegalArgumentException(); require(port in 0..65535) }
            "--no-browser" -> noBrowser=true
            else -> throw IllegalArgumentException()
        }
    }
    return DashboardOptions(requireNotNull(knowledge),port,noBrowser)
}
