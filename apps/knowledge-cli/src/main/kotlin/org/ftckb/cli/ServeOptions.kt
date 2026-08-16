package org.ftckb.cli

import java.io.PrintStream
import java.nio.file.Path

data class ServeOptions(
    val repository:Path,
    val knowledge:Path,
    val team:String,
    val season:String,
    val provider:String,
    val config:Path,
    val port:Int,
    val noBrowser:Boolean
)

fun interface ServeRunner {
    fun run(options:ServeOptions,out:PrintStream):Int
}

internal fun runServeCommand(
    args:List<String>,out:PrintStream,runner:ServeRunner
):Int {
    if (args==listOf("--help")) {
        printServeUsage(out)
        return 0
    }
    if (args.count { it=="--no-browser" }>1) {
        out.println("duplicate serve option: --no-browser")
        return 64
    }
    val valueArgs=args.filterNot { it=="--no-browser" }
    if (valueArgs.size%2!=0) {
        out.println("serve options must be flag-value pairs")
        return 64
    }
    val pairs=valueArgs.chunked(2)
    val allowed=setOf("--repo","--knowledge","--team","--season","--provider","--config","--port")
    val unknown=pairs.firstOrNull { it[0] !in allowed }
    if (unknown!=null) {
        out.println("unknown serve option: ${unknown[0]}")
        return 64
    }
    val duplicate=pairs.groupBy { it[0] }.entries.firstOrNull { it.value.size>1 }
    if (duplicate!=null) {
        out.println("duplicate serve option: ${duplicate.key}")
        return 64
    }
    val valuePairs=pairs
    val empty=valuePairs.firstOrNull { it[1].isEmpty() }
    if (empty!=null) {
        out.println("empty value for ${empty[0]}")
        return 64
    }
    val flagValue=valuePairs.firstOrNull { it[1].startsWith("--") }
    if (flagValue!=null) {
        out.println("invalid value for ${flagValue[0]}: ${flagValue[1]}")
        return 64
    }
    val values=valuePairs.associate { it[0] to it[1] }
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
    val portText=values["--port"]
    val port=when {
        portText==null -> 0
        portText.toIntOrNull()==null -> {
            out.println("invalid value for --port: expected 0-65535")
            return 64
        }
        else -> portText.toInt()
    }
    if (port<0 || port>65535) {
        out.println("invalid value for --port: expected 0-65535")
        return 64
    }
    val options=ServeOptions(
        repository=values["--repo"]?.let(Path::of) ?: Path.of(System.getProperty("user.dir")),
        knowledge=Path.of(values.getValue("--knowledge")),
        team=values.getValue("--team"),
        season=values.getValue("--season"),
        provider=values.getValue("--provider"),
        config=values["--config"]?.let(Path::of)
            ?:Path.of(System.getProperty("user.home"),".ftckb","config.yaml"),
        port=port,
        noBrowser=args.any { it=="--no-browser" }
    )
    return runner.run(options,out)
}

internal fun printServeUsage(out:PrintStream) {
    out.println(
        "usage: knowledge-cli serve --knowledge PATH --team N --season YYYY-YYYY --provider NAME "+
            "[--repo PATH] [--config PATH] [--port 0-65535] [--no-browser]"
    )
}
