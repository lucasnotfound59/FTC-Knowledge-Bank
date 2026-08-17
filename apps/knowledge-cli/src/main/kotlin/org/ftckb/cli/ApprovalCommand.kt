package org.ftckb.cli

import com.fasterxml.jackson.databind.json.JsonMapper
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.regex.Pattern
import org.ftckb.domain.Approval
import org.ftckb.domain.ApprovalPolicy
import org.ftckb.domain.Approver
import org.ftckb.domain.ApproverRole
import org.ftckb.domain.KnowledgeRule
import org.ftckb.domain.RuleStatus
import org.ftckb.domain.RuleValidator
import org.ftckb.knowledge.FileKnowledgeRepository
import org.ftckb.knowledge.RuleYamlCodec

/**
 * Approval flow for candidate rules: list candidates, approve (candidate ->
 * approved with an authorization-checked approval block), reject.
 * Edits are surgical (only the target rule block) and atomic (temp file
 * validated before the original is replaced).
 */
internal fun runApprovalCommand(command:String,args:List<String>,out:PrintStream):Int {
    return when (command) {
        "candidates" -> runCandidates(args,out)
        "approve" -> runApproveReject(args,out,approve=true)
        "reject" -> runApproveReject(args,out,approve=false)
        else -> error("unreachable approval command: $command")
    }
}

private fun runCandidates(args:List<String>,out:PrintStream):Int {
    if (args==listOf("--help")) {
        out.println("usage: knowledge-cli candidates <knowledge-root> [--json]")
        return 0
    }
    val optionArgs=args.drop(1).filterNot { it=="--json" }
    if (args.isEmpty()) {
        out.println("missing <knowledge-root>")
        return 64
    }
    if (optionArgs.isNotEmpty()) {
        out.println("candidates accepts exactly one knowledge root")
        return 64
    }
    val loaded=try {
        FileKnowledgeRepository.load(Path.of(args[0]))
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
    val candidates=loaded.rules.filter { it.status==RuleStatus.CANDIDATE }.sortedBy { it.id }
    val jsonMode=args.contains("--json")
    if (jsonMode) {
        val mapper=JsonMapper.builder().build()
        val root=mapper.createObjectNode()
        root.put("schemaVersion",1)
        root.put("command","candidates")
        root.put("ok",true)
        val array=root.putArray("candidates")
        candidates.forEach { candidate ->
            array.addObject().apply {
                put("id",candidate.id)
                put("topic",candidate.topic)
                put("title",candidate.title)
                put("authority",candidate.authority.name.lowercase())
            }
        }
        out.println(mapper.writeValueAsString(root))
    } else {
        out.println("candidates=${candidates.size}")
        candidates.forEach { candidate ->
            out.println("candidate ${candidate.id} topic=${candidate.topic} title=${candidate.title}")
        }
    }
    return 0
}

private fun runApproveReject(args:List<String>,out:PrintStream,approve:Boolean):Int {
    val verb=if (approve) "approve" else "reject"
    if (args==listOf("--help")) {
        out.println("usage: knowledge-cli $verb <knowledge-root> --id RULE_ID --approver NAME "+
            "--role team_software_lead|overall_software_lead [--team N]")
        return 0
    }
    if (args.isEmpty()) {
        out.println("missing <knowledge-root>")
        return 64
    }
    val root=Path.of(args[0])
    val pairs=args.drop(1)
    if (pairs.size%2!=0) {
        out.println("$verb options must be flag-value pairs")
        return 64
    }
    val optionPairs=pairs.chunked(2)
    val allowed=setOf("--id","--approver","--role","--team")
    val unknown=optionPairs.firstOrNull { it[0] !in allowed }
    if (unknown!=null) {
        out.println("unknown $verb option: ${unknown[0]}")
        return 64
    }
    val duplicate=optionPairs.groupBy { it[0] }.entries.firstOrNull { it.value.size>1 }
    if (duplicate!=null) {
        out.println("duplicate $verb option: ${duplicate.key}")
        return 64
    }
    val values=optionPairs.associate { it[0] to it[1] }
    listOf("--id","--approver","--role").forEach { required ->
        if (required !in values) {
            out.println("missing $required")
            return 64
        }
    }
    if (values.getValue("--approver").isBlank()) {
        out.println("empty value for --approver")
        return 64
    }
    val role=when (values.getValue("--role")) {
        "overall_software_lead" -> ApproverRole.OVERALL_SOFTWARE_LEAD
        "team_software_lead" -> ApproverRole.TEAM_SOFTWARE_LEAD
        else -> {
            out.println("invalid value for --role: expected team_software_lead or overall_software_lead")
            return 64
        }
    }
    if (values["--team"]!=null && !org.ftckb.domain.RuleIdentity.isCanonicalTeam(values.getValue("--team"))) {
        out.println("invalid value for --team: expected digits only")
        return 64
    }
    val loaded=try {
        FileKnowledgeRepository.load(root)
    } catch (exception:Exception) {
        val detail=exception.message?.lineSequence()?.firstOrNull()?.trim().orEmpty()
        out.println("error loading knowledge: ${detail.ifEmpty { exception.javaClass.simpleName }}")
        return 2
    }
    val rule=loaded.rules.firstOrNull { it.id==values.getValue("--id") }
    if (rule==null) {
        out.println("$verb refused: rule not found: ${values.getValue("--id")}")
        return 2
    }
    if (rule.status!=RuleStatus.CANDIDATE) {
        out.println("$verb refused: rule is not a candidate (status=${rule.status.name.lowercase()})")
        return 2
    }
    val approver=Approver(values.getValue("--approver"),role,values["--team"])
    if (!ApprovalPolicy.authorize(rule.authority,rule.applicability.teams,approver)) {
        out.println("$verb refused: approval is not authorized for rule authority and teams")
        return 2
    }
    val approval=if (approve) Approval(
        approver=values.getValue("--approver"),
        role=role,
        team=values["--team"],
        approvedAt=Instant.now()
    ) else null
    val target=locateRuleFile(root,rule.id)
    if (target==null) {
        out.println("$verb refused: unable to locate the rule file")
        return 2
    }
    val original=try {
        Files.readString(target)
    } catch (_:Exception) {
        out.println("$verb refused: unable to read the rule file")
        return 2
    }
    val edited=editRuleBlock(original,rule.id,approve,approval)
    if (edited==null) {
        out.println("$verb refused: unable to locate the rule block in the file")
        return 2
    }
    val decoded=try {
        RuleYamlCodec.decode(edited)
    } catch (exception:Exception) {
        val detail=exception.message?.lineSequence()?.firstOrNull()?.trim().orEmpty()
        out.println("$verb refused: edited file does not parse: ${detail.ifEmpty { exception.javaClass.simpleName }}")
        return 2
    }
    val violations=decoded.flatMap(RuleValidator::validate)
    if (violations.isNotEmpty()) {
        out.println("$verb refused: edited rule fails validation")
        violations.sortedWith(compareBy({ it.ruleId },{ it.field })).forEach {
            out.println("error rule=${it.ruleId} field=${it.field} message=${it.message}")
        }
        return 2
    }
    try {
        val temporary=Files.createTempFile(target.parent,"ftckb-approve",".tmp")
        Files.writeString(temporary,edited)
        Files.move(temporary,target,java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    } catch (_:Exception) {
        out.println("$verb refused: unable to write the rule file")
        return 2
    }
    out.println(if (approve) "approved=${rule.id}" else "rejected=${rule.id}")
    return 0
}

private fun locateRuleFile(root:Path,id:String):Path? {
    val files=try {
        Files.walk(root).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.fileName.toString().let { name -> name.endsWith(".yaml")||name.endsWith(".yml") } }
                .sorted()
                .toList()
        }
    } catch (_:Exception) {
        return null
    }
    return files.firstOrNull { file ->
        runCatching { RuleYamlCodec.decode(Files.readString(file)).any { it.id==id } }.getOrDefault(false)
    }
}

private fun editRuleBlock(original:String,id:String,approve:Boolean,approval:Approval?):String? {
    val lines=original.lines()
    val idPattern=Regex("^\\s*-\\s+id:\\s*$id\\s*$")
    val start=lines.indexOfFirst { idPattern.matches(it) }
    if (start<0) return null
    val indent=" ".repeat(lines[start].indexOfFirst { it=='-' })
    val blockEnd=lines.drop(start+1).indexOfFirst { line ->
        line.startsWith(indent+"- ") || line.trimStart().startsWith("schemaVersion")
    }.let { if (it<0) lines.size else start+1+it }
    val statusPattern=Regex("^\\s*status:\\s*[a-z]+\\s*$")
    val statusIndex=(start+1 until blockEnd).firstOrNull { statusPattern.matches(lines[it]) } ?: return null
    val statusIndent=lines[statusIndex].takeWhile { it==' ' }.length
    val newStatus=if (approve) "approved" else "rejected"
    val approvalInfo=if (approve) approval ?: return null else null
    val result=lines.toMutableList()
    result[statusIndex]=" ".repeat(statusIndent)+"status: $newStatus"
    if (approvalInfo!=null) {
        val block=mutableListOf<String>()
        block+=" ".repeat(statusIndent)+"approval:"
        block+=" ".repeat(statusIndent+2)+"approver: "+quote(approvalInfo.approver)
        block+=" ".repeat(statusIndent+2)+"role: "+approvalInfo.role.name.lowercase()
        approvalInfo.team?.let { block+=" ".repeat(statusIndent+2)+"team: "+quote(it) }
        block+=" ".repeat(statusIndent+2)+"approvedAt: "+approvalInfo.approvedAt.toString()
        result.addAll(statusIndex+1,block)
    }
    return result.joinToString("\n")+"\n"
}

private fun quote(value:String):String="\""+value.replace("\\","\\\\").replace("\"","\\\"")+"\""
