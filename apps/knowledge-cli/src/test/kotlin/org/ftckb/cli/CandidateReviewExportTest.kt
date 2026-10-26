package org.ftckb.cli

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.json.JsonMapper
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import org.ftckb.knowledge.KnowledgeWorkspace
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CandidateReviewExportTest {
    @TempDir lateinit var temp:Path
    private val mapper=JsonMapper.builder().build()
    private fun root(name:String="knowledge")=Files.createDirectories(temp.resolve(name)).toRealPath()
    private fun call(vararg args:String,audit:Path=temp.resolve("audit")):Pair<Int,String> {
        val output=ByteArrayOutputStream()
        val code=runCli(listOf("candidates")+args,PrintStream(output,true,Charsets.UTF_8),auditDirectory=audit)
        return code to output.toString(Charsets.UTF_8)
    }
    private fun json(root:Path,vararg options:String):JsonNode {
        val (code,text)=call(root.toString(),"--details","--json",*options)
        assertEquals(0,code,text)
        return mapper.readTree(text)
    }
    private fun simple(id:String="shared.small",instruction:String="保持安全。",status:String="candidate"):String="""
        schemaVersion: 4
        rules:
          - id: $id
            topic: ${id.substringAfterLast('.')}
            title: 小规则
            instruction: ${mapper.writeValueAsString(instruction)}
            rationale: 原因。
            status: $status
            authority: shared
            policyLevel: shared
            applicability:
              teams: []
              seasons: []
              profiles: []
            evidence:
              - type: git
                repository: owner/repo
                commit: abcdef1234567890
                file: TeamCode/Robot.java
                symbol: Robot
    """.trimIndent()+"\n"+if (status=="approved") """
            approval:
              approver: fixture-reviewer
              role: overall_software_lead
              approvedAt: 2026-01-01T00:00:00Z
    """.trimIndent().prependIndent("    ")+"\n" else ""
    private fun put(root:Path,text:String=simple(),name:String="rules.yaml")=Files.writeString(root.resolve(name),text)
    private fun rich(root:Path):String {
        val instruction="连续读取输入；保留原始故障。".repeat(120)
        put(root,simple("shared.rich",instruction)+"""
                line: 17
              - type: web
                url: https://vendor.example/docs
                title: Vendor reference
                publisher: Vendor
                accessedAt: 2026-10-23
                section: Lifecycle
                version: v2.1.1
                product: Controller
                sku: exact-sku
            supersedes: shared.previous
            positiveExample: 每周期读取新输入。
            negativeExample: 构造时保存旧输入。
            checks:
              - kind: regex-forbidden
                pattern: UNSAFE
                appliesTo: '**/*.java'
                note: 禁止不安全调用。
            reviewTriggers:
              - paths: ['TeamCode/**/*.java', 'TeamCode/**/Robot.java']
                addedLinePatterns: ['schedule', 'cancel']
        """.trimIndent().prependIndent("    ")+"\n")
        return instruction
    }
    private fun error(root:Path,expectedCode:String,expectedExit:Int=2,vararg args:String):JsonNode {
        val (exit,text)=call(root.toString(),"--details","--json",*args)
        assertEquals(expectedExit,exit,text)
        val node=mapper.readTree(text)
        assertEquals("ftckb-candidate-review",node["format"].asText())
        assertEquals(1,node["formatVersion"].asInt())
        assertEquals("candidates",node["command"].asText())
        assertFalse(node["ok"].asBoolean())
        assertFalse(node.has("schemaVersion"))
        assertFalse(node.has("candidates"))
        assertEquals(expectedCode,node["error"]["code"].asText())
        assertEquals(1,text.lineSequence().filter { it.isNotEmpty() }.count())
        return node
    }

    @Test fun `legacy text json errors and duplicate json retain exact bytes`() {
        val root=root()
        put(root,simple("shared.zeta"),"z.yaml")
        put(root,simple("shared.alpha"),"a.yaml")
        val text="candidates=2\ncandidate shared.alpha topic=alpha title=小规则\ncandidate shared.zeta topic=zeta title=小规则\n"
        val json="{\"schemaVersion\":1,\"command\":\"candidates\",\"ok\":true,\"candidates\":[{\"id\":\"shared.alpha\",\"topic\":\"alpha\",\"title\":\"小规则\",\"authority\":\"shared\"},{\"id\":\"shared.zeta\",\"topic\":\"zeta\",\"title\":\"小规则\",\"authority\":\"shared\"}]}\n"
        assertEquals(0 to text,call(root.toString()))
        assertEquals(0 to json,call(root.toString(),"--json"))
        assertEquals(0 to json,call(root.toString(),"--json","--json"))
        assertEquals(64 to "missing <knowledge-root>\n",call())
        assertEquals(64 to "candidates accepts exactly one knowledge root\n",call(root.toString(),"--unknown","--json"))
        assertEquals(64 to "candidates accepts exactly one knowledge root\n",call(root.toString(),"--id","shared.alpha"))
        val empty=root("empty")
        assertEquals(0 to "candidates=0\n",call(empty.toString()))
        val missing=temp.resolve("missing")
        assertEquals(2 to "error loading knowledge: knowledge root is not a directory: $missing\n",call(missing.toString(),"--json"))
    }

    @Test fun `details preserves all semantic fields and UTF8 text without truncation`() {
        val root=root()
        val instruction=rich(root)
        val node=json(root)
        assertEquals("ftckb-candidate-review",node["format"].asText())
        assertEquals(1,node["formatVersion"].asInt())
        assertFalse(node.has("schemaVersion"))
        assertTrue(node["readOnly"].asBoolean())
        assertFalse(node["contextEvaluated"].asBoolean())
        val rule=node["candidates"].single()["rule"]
        assertEquals(setOf("id","topic","title","instruction","rationale","status","authority","policyLevel",
            "applicability","evidence","approval","supersedes","positiveExample","negativeExample","checks","reviewTriggers","checkMode"),
            rule.fieldNames().asSequence().toSet())
        assertEquals(instruction,rule["instruction"].asText())
        assertEquals("原因。",rule["rationale"].asText())
        assertEquals("shared.previous",rule["supersedes"].asText())
        assertEquals("每周期读取新输入。",rule["positiveExample"].asText())
        assertEquals("构造时保存旧输入。",rule["negativeExample"].asText())
        assertEquals("candidate",rule["status"].asText())
        assertTrue(rule["approval"].isNull)
        assertEquals("hard",rule["checkMode"].asText())
        assertEquals(listOf("git","web"),rule["evidence"].map { it["type"].asText() })
        assertEquals(17,rule["evidence"][0]["line"].asInt())
        assertEquals("Robot",rule["evidence"][0]["symbol"].asText())
        val web=rule["evidence"][1]
        for ((key,value) in mapOf("url" to "https://vendor.example/docs","title" to "Vendor reference","publisher" to "Vendor",
            "accessedAt" to "2026-10-23","section" to "Lifecycle","version" to "v2.1.1","product" to "Controller","sku" to "exact-sku")) {
            assertEquals(value,web[key].asText(),key)
        }
        assertEquals("UNSAFE",rule["checks"][0]["pattern"].asText())
        assertEquals("**/*.java",rule["checks"][0]["appliesTo"].asText())
        assertEquals("禁止不安全调用。",rule["checks"][0]["note"].asText())
        assertEquals(listOf("cancel","schedule"),rule["reviewTriggers"][0]["addedLinePatterns"].map { it.asText() })
        assertTrue(rule["applicability"].all { it.isArray && it.isEmpty })
    }

    @Test fun `declared scope is sorted without inherited profiles and selected rule remains candidate`() {
        val root=root()
        put(root,simple("shared.limited").replace("teams: []","teams: ['20827', '16093']")
            .replace("seasons: []","seasons: ['2026-2027', '2025-2026']")
            .replace("profiles: []","profiles: [ftclib-command]"))
        val rule=json(root,"--id","shared.limited")["candidates"].single()["rule"]
        assertEquals(listOf("16093","20827"),rule["applicability"]["teams"].map { it.asText() })
        assertEquals(listOf("2025-2026","2026-2027"),rule["applicability"]["seasons"].map { it.asText() })
        assertEquals(listOf("ftclib-command"),rule["applicability"]["profiles"].map { it.asText() })
        assertEquals("unconditional_soft",rule["checkMode"].asText())
        assertTrue(rule["supersedes"].isNull)
        assertTrue(rule["checks"].isEmpty)
    }

    @Test fun `source hashes and version match snapshot and unreadable legacy source remains null`() {
        val root=root()
        put(root,simple("shared.modern"),"modern.yaml")
        put(root,"""
            schemaVersion: 1
            rules:
              - id: shared.legacy
                topic: legacy
                title: Legacy
                instruction: Preserve legacy semantics.
                rationale: Still readable.
                status: candidate
                authority: shared
                evidence:
                  - repository: owner/repo
                    commit: abcdef123
                    file: Legacy.java
                    line: 1
        """.trimIndent()+"\n","legacy.yaml")
        val snapshot=KnowledgeWorkspace(root).snapshot()
        assertTrue(snapshot.valid)
        assertNull(snapshot.sources["shared.legacy"])
        val node=json(root)
        assertEquals(snapshot.root.toString(),node["knowledge"]["root"].asText())
        assertEquals(snapshot.version,node["knowledge"]["version"].asText())
        val cards=node["candidates"].associateBy { it["rule"]["id"].asText() }
        assertTrue(cards.getValue("shared.legacy")["source"].isNull)
        assertEquals("shared",cards.getValue("shared.legacy")["rule"]["policyLevel"].asText())
        assertTrue(cards.getValue("shared.legacy")["rule"]["applicability"]["profiles"].isEmpty)
        val source=snapshot.sources.getValue("shared.modern")
        assertEquals(source.path,cards.getValue("shared.modern")["source"]["path"].asText())
        assertEquals(source.fileDigest,cards.getValue("shared.modern")["source"]["fileDigest"].asText())
        assertFalse(node.toString().contains("ruleYaml"))
    }

    @Test fun `selection distinguishes not found noncandidate and valid empty library`() {
        val root=root()
        put(root,simple("shared.approved",status="approved"))
        assertTrue(json(root)["candidates"].isEmpty)
        error(root,"not-found",2,"--id","shared.missing")
        error(root,"not-candidate",2,"--id","shared.approved")
        put(root,simple("shared.small"),"second.yaml")
        assertEquals(listOf("shared.small"),json(root,"--id","shared.small")["candidates"].map { it["rule"]["id"].asText() })
    }

    @Test fun `invalid full library refuses partial candidates and unsafe source paths fail closed`() {
        val root=root()
        put(root)
        put(root,"schemaVersion: [broken","broken.yaml")
        error(root,"invalid-knowledge",2,"--id","shared.small")
        Files.delete(root.resolve("broken.yaml"))
        put(root,simple(),"duplicate.yaml")
        error(root,"invalid-knowledge")
        Files.delete(root.resolve("duplicate.yaml"))
        put(root,simple("shared.bad",status="approved").replace("status: approved","status: candidate"),"bad.yaml")
        error(root,"invalid-knowledge")
        error(temp.resolve("missing"),"load-error")
        val target=root("target")
        put(target)
        val link=temp.toRealPath().resolve("link")
        Files.createSymbolicLink(link,target)
        error(link,"load-error")
    }

    @Test fun `details parser emits complete usage JSON for every invalid option combination`() {
        val root=root()
        val cases=listOf(
            listOf("--id"),listOf("--id",""),listOf("--id","UPPER.case"),listOf("--id","../bad"),
            listOf("--details"),listOf("--json"),listOf("--id","shared.a","--id","shared.b"),
            listOf("--unknown"),listOf("extra"),listOf("--help"),listOf("--id","--help")
        )
        cases.forEach { error(root,"usage",64,*it.toTypedArray()) }
        for (args in listOf(listOf("--details","--json"),listOf("","--json","--details"),listOf("--json","--details"))) {
            val (code,text)=call(*args.toTypedArray())
            assertEquals(64,code,text)
            assertEquals("usage",mapper.readTree(text)["error"]["code"].asText())
        }
        val (code,text)=call(root.toString(),"--json","--id","shared.missing","--details")
        assertEquals(2,code,text)
        assertEquals("not-found",mapper.readTree(text)["error"]["code"].asText())
    }

    @Test fun `UTF8 output limit refuses full and individual oversized cards without partial output`() {
        val root=root()
        put(root,simple("shared.large","汉".repeat(360_000)),"large.yaml")
        put(root,simple("shared.small"),"small.yaml")
        error(root,"output-too-large")
        error(root,"output-too-large",2,"--id","shared.large")
        assertEquals(1,json(root,"--id","shared.small")["candidates"].size())
        val (code,text)=call(root.toString(),"--details")
        assertEquals(2,code,text)
        assertFalse(text.contains("汉"))
        assertFalse(text.contains("\"rule\""))
        assertTrue(text.contains("--id"))
        assertTrue(text.toByteArray(Charsets.UTF_8).size<1024)
    }

    @Test fun `human cards reuse full JSON objects with explicit nonapproval and source caveats`() {
        val root=root()
        val instruction=rich(root)
        val node=json(root)
        val (code,text)=call(root.toString(),"--details")
        assertEquals(0,code,text)
        for (phrase in listOf("未批准","未裁决项目适用性","空 teams/seasons","空 profiles","全部要求","source 为 null","姓名和角色","knowledge.version=")) {
            assertTrue(text.contains(phrase),phrase)
        }
        assertTrue(text.contains(instruction))
        val cardStart=text.indexOf('{')
        assertTrue(cardStart>=0)
        assertEquals(node["candidates"].single(),mapper.readTree(text.substring(cardStart)))
        assertFalse(text.contains("ftckb approve"))
    }

    @Test fun `repeated exports are deterministic sorted and leave files directories and audit untouched`() {
        val root=root()
        put(root,simple("shared.zeta"),"a.yaml")
        put(root,simple("shared.alpha"),"z.yaml")
        fun bytes()=Files.walk(temp).use { paths -> paths.sorted().toList().associate { path ->
            temp.relativize(path).toString() to if (Files.isRegularFile(path)) Files.readAllBytes(path).toList() else null
        } }
        val before=bytes()
        val first=call(root.toString(),"--details","--json")
        val second=call(root.toString(),"--details","--json")
        assertEquals(0,first.first,first.second)
        assertEquals(first,second)
        assertEquals(listOf("shared.alpha","shared.zeta"),mapper.readTree(first.second)["candidates"].map { it["rule"]["id"].asText() })
        assertEquals(before,bytes())
        assertFalse(Files.exists(temp.resolve("audit")))
    }

    @Test fun `help discovers opt in detail interface and its independent source boundary`() {
        val (code,text)=call("--help")
        assertEquals(0,code)
        assertTrue(text.contains("[--details [--id RULE_ID]] [--json]"),text)
        assertTrue(text.contains("symbolic"),text)
        val output=ByteArrayOutputStream()
        assertEquals(0,runCli(listOf("--help"),PrintStream(output)))
        assertTrue(output.toString().contains("candidates <knowledge-root> [--details [--id RULE_ID]] [--json]"))
    }
}
