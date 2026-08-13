package org.ftckb.cli

import java.io.PrintStream
import java.nio.file.Path
import kotlin.system.exitProcess
import org.ftckb.domain.RuleContext
import org.ftckb.domain.RuleIdentity
import org.ftckb.domain.RuleResolver
import org.ftckb.knowledge.FileKnowledgeRepository

fun runCli(args:List<String>,out:PrintStream=System.out):Int {
    if (args.size<2) {
        out.println("usage: knowledge-cli <validate|resolve> <knowledge-root> [--team N --season S]")
        return 64
    }
    if (args[0] !in setOf("validate","resolve")) {
        out.println("unknown command: ${args[0]}")
        return 64
    }
    if (args[0]=="validate" && args.size!=2) {
        out.println("validate accepts exactly one knowledge root")
        return 64
    }
    if (args[0]=="resolve" && "--team" !in args.drop(2)) {
        out.println("missing --team")
        return 64
    }
    if (args[0]=="resolve" && "--season" !in args.drop(2)) {
        out.println("missing --season")
        return 64
    }
    if (args[0]=="resolve" && args.drop(2).size%2!=0) {
        out.println("resolve options must be flag-value pairs")
        return 64
    }
    if (args[0]=="resolve") {
        val optionPairs=args.drop(2).chunked(2)
        val unknown=optionPairs.firstOrNull { it[0] !in setOf("--team","--season") }
        if (unknown!=null) {
            out.println("unknown resolve option: ${unknown[0]}")
            return 64
        }
        val duplicate=optionPairs.groupBy { it[0] }.entries.firstOrNull { it.value.size>1 }
        if (duplicate!=null) {
            out.println("duplicate resolve option: ${duplicate.key}")
            return 64
        }
        val empty=optionPairs.firstOrNull { it[1].isEmpty() }
        if (empty!=null) {
            out.println("empty value for ${empty[0]}")
            return 64
        }
        val flagValue=optionPairs.firstOrNull { it[1].startsWith("--") }
        if (flagValue!=null) {
            out.println("invalid value for ${flagValue[0]}: ${flagValue[1]}")
            return 64
        }
        val options=optionPairs.associate { it[0] to it[1] }
        if (!RuleIdentity.isCanonicalTeam(options.getValue("--team"))) {
            out.println("invalid value for --team: expected digits only")
            return 64
        }
        if (!RuleIdentity.isCanonicalSeason(options.getValue("--season"))) {
            out.println("invalid value for --season: expected YYYY-YYYY")
            return 64
        }
    }
    val loaded=try {
        FileKnowledgeRepository.load(Path.of(args[1]))
    } catch (exception:Exception) {
        val detail=exception.message?.lineSequence()?.firstOrNull()?.trim().orEmpty()
        out.println("error loading knowledge: ${detail.ifEmpty { exception.javaClass.simpleName }}")
        return 2
    }
    if (loaded.violations.isNotEmpty()) {
        loaded.violations.sortedWith(compareBy({ it.ruleId },{ it.field })).forEach {
            out.println("error rule=${it.ruleId} field=${it.field} message=${it.message}")
        }
        return 2
    }
    return when (args[0]) {
        "validate" -> {
            out.println("validation=ok rules=${loaded.rules.size}")
            0
        }
        "resolve" -> {
            val options=args.drop(2).chunked(2).associate { pair -> pair[0] to pair.getOrElse(1) { "" } }
            val team=options["--team"] ?: return 64.also { out.println("missing --team") }
            val season=options["--season"] ?: return 64.also { out.println("missing --season") }
            val result=RuleResolver.resolve(loaded.rules,RuleContext(team,season))
            if (result.conflicts.isNotEmpty()) {
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

fun main(args:Array<String>) {
    exitProcess(runCli(args.toList()))
}
