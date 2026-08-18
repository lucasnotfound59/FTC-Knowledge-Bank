package org.ftckb.cli

import java.io.BufferedReader
import java.io.PrintStream
import java.nio.file.Path
import kotlin.system.exitProcess
import org.ftckb.domain.RuleContext
import org.ftckb.domain.RuleIdentity
import org.ftckb.domain.RuleResolver
import org.ftckb.knowledge.FileKnowledgeRepository

fun runCli(
    args:List<String>,
    out:PrintStream=System.out,
    input:BufferedReader=System.`in`.bufferedReader(),
    chatLauncher:ChatLauncher=ProductionChatLauncher(),
    evalCommand:EvalCommand=EvalCommand(),
    serveCommand:ServeRunner=ServeCommand(),
    extractCommand:ExtractRunner=ExtractCommand()
):Int {
    if (args.isEmpty() || args==listOf("--help") || args==listOf("-h") || args==listOf("help")) {
        printTopLevelHelp(out)
        return 0
    }
    if (args==listOf("--version") || args==listOf("-V") || args==listOf("version")) {
        out.println("ftckb $FTCKB_VERSION (kernel contract schemaVersion ${KernelJson.SCHEMA_VERSION})")
        return 0
    }
    if (args.firstOrNull()=="chat") return runChatCommand(args.drop(1),input,out,chatLauncher)
    if (args.firstOrNull()=="eval") return evalCommand.run(args.drop(1),out)
    if (args.firstOrNull()=="serve") return runServeCommand(args.drop(1),out,serveCommand)
    if (args.firstOrNull()=="extract") return runExtractCommand(args.drop(1),out,extractCommand)
    if (args.firstOrNull() in setOf("candidates","approve","reject")) {
        return runApprovalCommand(args.first(),args.drop(1),out)
    }
    if (args.firstOrNull() in setOf("validate","resolve") && args.contains("--help")) {
        out.println("usage: knowledge-cli <validate|resolve> <knowledge-root> [--team N --season S] [--json]")
        return 0
    }
    // In --json mode every failure path emits the same stable error shape:
    // {"schemaVersion":1,"command":"<validate|resolve>","ok":false,"error":{"code","message"}}.
    val jsonMode=args.contains("--json")
    fun fail(message:String,code:String,exit:Int):Int {
        if (jsonMode) out.println(KernelJson.errorJson(args.firstOrNull(),code,message))
        else out.println(message)
        return exit
    }
    if (args.size<2) {
        return fail("usage: knowledge-cli <validate|resolve> <knowledge-root> [--team N --season S] [--json]","usage",64)
    }
    if (args[0] !in setOf("validate","resolve")) {
        return fail("unknown command: ${args[0]}","usage",64)
    }
    val optionArgs=args.drop(2).filterNot { it=="--json" }
    if (args[0]=="validate" && optionArgs.isNotEmpty()) {
        return fail("validate accepts exactly one knowledge root","usage",64)
    }
    if (args[0]=="resolve" && "--team" !in optionArgs) {
        return fail("missing --team","usage",64)
    }
    if (args[0]=="resolve" && "--season" !in optionArgs) {
        return fail("missing --season","usage",64)
    }
    if (args[0]=="resolve" && optionArgs.size%2!=0) {
        return fail("resolve options must be flag-value pairs","usage",64)
    }
    if (args[0]=="resolve") {
        val optionPairs=optionArgs.chunked(2)
        val unknown=optionPairs.firstOrNull { it[0] !in setOf("--team","--season") }
        if (unknown!=null) return fail("unknown resolve option: ${unknown[0]}","usage",64)
        val duplicate=optionPairs.groupBy { it[0] }.entries.firstOrNull { it.value.size>1 }
        if (duplicate!=null) return fail("duplicate resolve option: ${duplicate.key}","usage",64)
        val empty=optionPairs.firstOrNull { it[1].isEmpty() }
        if (empty!=null) return fail("empty value for ${empty[0]}","usage",64)
        val flagValue=optionPairs.firstOrNull { it[1].startsWith("--") }
        if (flagValue!=null) return fail("invalid value for ${flagValue[0]}: ${flagValue[1]}","usage",64)
        val options=optionPairs.associate { it[0] to it[1] }
        if (!RuleIdentity.isCanonicalTeam(options.getValue("--team"))) {
            return fail("invalid value for --team: expected digits only","usage",64)
        }
        if (!RuleIdentity.isCanonicalSeason(options.getValue("--season"))) {
            return fail("invalid value for --season: expected YYYY-YYYY","usage",64)
        }
    }
    val loaded=try {
        FileKnowledgeRepository.load(Path.of(args[1]))
    } catch (exception:Exception) {
        val detail=exception.message?.lineSequence()?.firstOrNull()?.trim().orEmpty()
        return fail("error loading knowledge: ${detail.ifEmpty { exception.javaClass.simpleName }}","load-error",2)
    }
    if (loaded.violations.isNotEmpty()) {
        if (jsonMode) {
            out.println(KernelJson.violationsJson(args[0],loaded.violations))
        } else {
            loaded.violations.sortedWith(compareBy({ it.ruleId },{ it.field })).forEach {
                out.println("error rule=${it.ruleId} field=${it.field} message=${it.message}")
            }
        }
        return 2
    }
    return when (args[0]) {
        "validate" -> {
            if (jsonMode) out.println(KernelJson.validateJson(loaded.rules.size))
            else out.println("validation=ok rules=${loaded.rules.size}")
            0
        }
        "resolve" -> {
            val options=optionArgs.chunked(2).associate { pair -> pair[0] to pair.getOrElse(1) { "" } }
            val team=options["--team"] ?: return 64.also { out.println("missing --team") }
            val season=options["--season"] ?: return 64.also { out.println("missing --season") }
            val result=RuleResolver.resolve(loaded.rules,RuleContext(team,season))
            if (jsonMode) {
                out.println(KernelJson.resolveJson(team,season,result.activeRules,result.conflicts))
                if (result.conflicts.isNotEmpty()) 2 else 0
            } else if (result.conflicts.isNotEmpty()) {
                result.conflicts.forEach { out.println("conflict topic=${it.topic} rules=${it.ruleIds.sorted().joinToString(",")}") }
                2
            } else {
                result.activeRules.forEach { out.println("active ${it.id}") }
                0
            }
        }
        else -> error("unreachable command: ${args[0]}")
    }
}

const val FTCKB_VERSION="1.0.0"

private fun printTopLevelHelp(out:PrintStream) {
    out.println("ftckb - FTC Knowledge Bank command line agent (v$FTCKB_VERSION)")
    out.println()
    out.println("commands:")
    out.println("  validate <knowledge-root> [--json]")
    out.println("      load and validate knowledge rules")
    out.println("  resolve <knowledge-root> --team N --season YYYY-YYYY [--json]")
    out.println("      resolve active rules deterministically (OFFICIAL > TEAM > SHARED)")
    out.println("  candidates <knowledge-root> [--json]")
    out.println("      list candidate rules awaiting approval")
    out.println("  approve | reject <knowledge-root> --id X --approver NAME --role ROLE [--team N]")
    out.println("      approve or reject a candidate rule")
    out.println("  extract --repo PATH --team N --provider NAME [options]")
    out.println("      propose candidate rules from a repository")
    out.println("  chat --knowledge PATH --team N --season YYYY-YYYY --provider NAME [options]")
    out.println("      interactive chat agent (Ask / Edit modes)")
    out.println("  serve --knowledge PATH --team N --season YYYY-YYYY --provider NAME [options]")
    out.println("      local web session on 127.0.0.1")
    out.println("  eval --cases PATH --knowledge PATH --provider NAME --output PATH")
    out.println("      run the fixed evaluation scenarios")
    out.println()
    out.println("exit codes: 0 ok | 2 knowledge/conflict failure | 64 usage error")
    out.println("machine contract for external agents: docs/kernel-contract.md")
    out.println("note: the CLI version ($FTCKB_VERSION) is independent of the kernel contract schemaVersion (${KernelJson.SCHEMA_VERSION})")
    out.println("run 'ftckb <command> --help' for command usage")
}

fun main(args:Array<String>) {
    exitProcess(runCli(args.toList()))
}
