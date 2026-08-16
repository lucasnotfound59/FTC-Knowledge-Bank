package org.ftckb.cli

import java.io.BufferedReader
import java.io.PrintStream
import java.nio.file.Path

data class ChatOptions(
    val repository:Path,
    val knowledge:Path,
    val team:String,
    val season:String,
    val provider:String,
    val config:Path
)

fun interface ChatLauncher {
    fun run(options:ChatOptions,input:BufferedReader,out:PrintStream):Int
}

internal fun runChatCommand(
    args:List<String>,input:BufferedReader,out:PrintStream,launcher:ChatLauncher
):Int {
    if (args==listOf("--help")) {
        printChatUsage(out)
        return 0
    }
    if (args.size%2!=0) {
        out.println("chat options must be flag-value pairs")
        return 64
    }
    val pairs=args.chunked(2)
    val allowed=setOf("--repo","--knowledge","--team","--season","--provider","--config")
    val unknown=pairs.firstOrNull { it[0] !in allowed }
    if (unknown!=null) {
        out.println("unknown chat option: ${unknown[0]}")
        return 64
    }
    val duplicate=pairs.groupBy { it[0] }.entries.firstOrNull { it.value.size>1 }
    if (duplicate!=null) {
        out.println("duplicate chat option: ${duplicate.key}")
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
    listOf("--knowledge","--team","--season","--provider").forEach { required ->
        if (required !in values) {
            out.println("missing $required")
            return 64
        }
    }
    if (!org.ftckb.domain.RuleIdentity.isCanonicalTeam(values.getValue("--team"))) {
        out.println("invalid value for --team: expected digits only")
        return 64
    }
    if (!org.ftckb.domain.RuleIdentity.isCanonicalSeason(values.getValue("--season"))) {
        out.println("invalid value for --season: expected YYYY-YYYY")
        return 64
    }
    val options=ChatOptions(
        repository=values["--repo"]?.let(Path::of) ?: Path.of(System.getProperty("user.dir")),
        knowledge=Path.of(values.getValue("--knowledge")),
        team=values.getValue("--team"),
        season=values.getValue("--season"),
        provider=values.getValue("--provider"),
        config=values["--config"]?.let(Path::of)
            ?:Path.of(System.getProperty("user.home"),".ftckb","config.yaml")
    )
    return launcher.run(options,input,out)
}

internal fun printChatUsage(out:PrintStream) {
    out.println(
        "usage: knowledge-cli chat --knowledge PATH --team N --season YYYY-YYYY --provider NAME "+
            "[--repo PATH] [--config PATH]"
    )
}
