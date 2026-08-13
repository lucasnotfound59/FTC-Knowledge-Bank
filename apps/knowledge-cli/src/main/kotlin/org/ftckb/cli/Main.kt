package org.ftckb.cli

import java.io.PrintStream
import java.nio.file.Path
import kotlin.system.exitProcess
import org.ftckb.domain.RuleContext
import org.ftckb.domain.RuleResolver
import org.ftckb.knowledge.FileKnowledgeRepository

fun runCli(args:List<String>,out:PrintStream=System.out):Int {
    if (args.size<2) {
        out.println("usage: knowledge-cli <validate|resolve> <knowledge-root> [--team N --season S]")
        return 64
    }
    val loaded=FileKnowledgeRepository.load(Path.of(args[1]))
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
        else -> 64.also { out.println("unknown command: ${args[0]}") }
    }
}

fun main(args:Array<String>) {
    exitProcess(runCli(args.toList()))
}
