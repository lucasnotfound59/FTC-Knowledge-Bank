package org.ftckb.cli

import java.io.PrintStream
import java.nio.file.Path
import org.ftckb.domain.RuleStatus
import org.ftckb.knowledge.KnowledgeWorkspace

/** Opt-in review projection; the legacy candidates and kernel formats remain independent. */
internal fun runCandidateReviewExport(args:List<String>,out:PrintStream):Int {
    val jsonMode="--json" in args
    val mapper=DashboardJson.mapper
    fun envelope(ok:Boolean)=linkedMapOf<String,Any?>(
        "format" to "ftckb-candidate-review","formatVersion" to 1,
        "command" to "candidates","ok" to ok
    )
    fun failure(code:String,message:String,exit:Int):Int {
        val body=if (jsonMode) mapper.writeValueAsString(envelope(false).apply {
            put("error",linkedMapOf("code" to code,"message" to message))
        }) else "candidate-review error=$code: $message"
        out.println(body)
        return exit
    }
    fun usage()=failure("usage",
        "Expected candidates <knowledge-root> --details [--id RULE_ID] [--json] with no duplicate options",64)

    if (args.isEmpty() || args[0].isBlank() || args[0].startsWith("--")) return usage()
    val seen=mutableSetOf<String>()
    var selectedId:String?=null
    var index=1
    while (index<args.size) {
        val option=args[index++]
        if (option !in setOf("--details","--json","--id") || !seen.add(option)) return usage()
        if (option=="--id") {
            val value=args.getOrNull(index++) ?: return usage()
            if (!Regex("^[a-z0-9]+(?:[.-][a-z0-9]+)*$").matches(value)) return usage()
            selectedId=value
        }
    }
    if ("--details" !in seen) return usage()

    // Capture once: decoded rules, sources and all digests must describe these same bytes.
    val snapshot=try {
        KnowledgeWorkspace(Path.of(args[0])).snapshot()
    } catch (_:Exception) {
        return failure("load-error","Knowledge snapshot could not be read safely",2)
    }
    if (!snapshot.valid) return failure("invalid-knowledge","Knowledge snapshot contains invalid rules or YAML",2)
    val selected=if (selectedId!=null) {
        val rule=snapshot.rules.singleOrNull { it.id==selectedId }
            ?: return failure("not-found","Selected rule does not exist",2)
        if (rule.status!=RuleStatus.CANDIDATE) return failure("not-candidate","Selected rule is not a candidate",2)
        listOf(rule)
    } else snapshot.rules.filter { it.status==RuleStatus.CANDIDATE }
    val cards=selected.sortedBy { it.id }.map { rule ->
        val source=snapshot.sources[rule.id]
        linkedMapOf<String,Any?>(
            "rule" to DashboardJson.rule(rule),
            "source" to source?.let { linkedMapOf("path" to it.path,"fileDigest" to it.fileDigest) }
        )
    }
    val result=envelope(true).apply {
        put("readOnly",true)
        put("contextEvaluated",false)
        put("knowledge",linkedMapOf("root" to snapshot.root.toString(),"version" to snapshot.version))
        put("candidates",cards)
    }
    val body=if (jsonMode) mapper.writeValueAsString(result) else buildString {
        append("本次为候选审阅导出，未批准、未裁决项目适用性。")
        append("空 teams/seasons 表示该维度不限制，空 profiles 表示无架构要求；非空 profiles 是全部要求。")
        append("checkMode 只描述规则若获批并生效后的检查形式。")
        append("source 为 null 表示无法从本次快照安全定位单条来源。")
        append("真实审批需用户明确批准最终正文与范围，并提供姓名和角色声明。\n")
        append("导出后若规则变动，须重新导出、重审并做当前预览；旧卡片不能保证当前内容未变化。\n")
        append("knowledge.root=${snapshot.root}\n")
        append("knowledge.version=${snapshot.version}\n")
        append("candidates=${cards.size}")
        val writer=mapper.writerWithDefaultPrettyPrinter()
        cards.forEach { card -> append('\n'); append(writer.writeValueAsString(card)) }
    }
    val rendered=body+"\n"
    if (rendered.toByteArray(Charsets.UTF_8).size>1024*1024) {
        return failure("output-too-large","Candidate review exceeds 1 MiB; select one rule with --id",2)
    }
    out.print(rendered)
    return 0
}
