package org.ftckb.agent

import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import org.ftckb.model.ModelProvider
import org.ftckb.model.ModelRequest
import org.ftckb.model.ModelResponse
import org.ftckb.repository.RepositoryIndex
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class RetrievalPlannerTest {
    @TempDir
    lateinit var tempDir:Path

    @Test
    fun `repairs malformed retrieval JSON once`() {
        val provider=ScriptedProvider(
            "not json",
            """{"concepts":["drive"],"symbols":["Drive"],"pathGlobs":["TeamCode/**/*.java"],"ruleTopics":["hardware-access"],"guideTopics":["pedro"]}"""
        )

        val intent=RetrievalPlanner(provider).plan(input())

        assertEquals(setOf("drive"),intent.concepts)
        assertEquals(setOf("Drive"),intent.symbols)
        assertEquals(2,provider.requests.size)
    }

    @Test
    fun `uses deterministic concepts-only fallback after a second malformed plan`() {
        val provider=ScriptedProvider("[]","still not json")

        val intent=RetrievalPlanner(provider).plan(input(question="How does DriveRobot use Pedro?",references=setOf("FollowPath")))

        assertEquals(setOf("driveRobot","pedro","followPath"),intent.concepts)
        assertEquals(emptySet<String>(),intent.symbols)
        assertEquals(emptySet<String>(),intent.pathGlobs)
        assertEquals(2,provider.requests.size)
    }

    @Test
    fun `uses normalized non-Latin fallback concepts after malformed plans`() {
        val provider=ScriptedProvider("[]","still not json")

        val intent=RetrievalPlanner(provider).plan(input(question="电机如何工作？"))

        assertEquals(setOf("电机如何工作"),intent.concepts)
        assertEquals(2,provider.requests.size)
    }

    @Test
    fun `rejects unsafe retrieval globs and JSON trailing outside a fence`() {
        assertThrows(IllegalArgumentException::class.java) {
            RetrievalIntent(emptySet(),emptySet(),setOf("../TeamCode/*.java"),emptySet(),emptySet())
        }
        assertThrows(IllegalArgumentException::class.java) {
            ModelJson.objectNode("```json\n{}\n``` unexpected")
        }
    }

    @Test
    fun `retrieves stable bounded code rules and guide evidence locally`() {
        write("knowledge/rules.yaml","""
            schemaVersion: 1
            rules:
              - id: official.drive-safety
                topic: drive-safety
                title: Drive safely
                instruction: Guard drive values.
                rationale: Safe drive behavior needs validation.
                status: approved
                authority: official
                applicability: {}
                evidence:
                  - repository: first/robot
                    commit: abcdef1
                    file: TeamCode/Drive.java
                    symbol: Drive
                approval:
                  approver: overall-lead
                  role: overall_software_lead
                  approvedAt: 2026-08-14T00:00:00Z
        """.trimIndent())
        write("knowledge/guides/tools/drive.md","# Pedro drive\n\nUse Pedro for safe drive paths.")
        write("repo/TeamCode/Drive.java","class Drive { void drive() {} }")
        val index=RepositoryIndex()
        index.build(tempDir.resolve("repo"))
        val retriever=ContextRetriever(index,KnowledgeRetriever(tempDir.resolve("knowledge"),"20827","2025-2026"))

        val context=retriever.retrieve(RetrievalIntent(setOf("drive"),emptySet(),emptySet(),setOf("drive-safety"),setOf("pedro")))

        assertEquals(listOf("CODE:C1","RULE:R1","GUIDE:G1"),context.evidence.map { it.id })
        assertEquals("guides/tools/drive.md",(context.evidence.last() as GuideEvidence).path)
        assertEquals(EvidenceSerialization.payload(context.evidence).length,context.estimatedCharacters)
    }

    @Test
    fun `reserves the exact serialized budget for explicitly requested active rules before broad code`() {
        write("saturated-knowledge/rules.yaml","""
            schemaVersion: 1
            rules:
              - id: official.saturation-safety
                topic: saturation-safety
                title: Saturation safety
                instruction: Preserve the explicitly requested safety rule.
                rationale: Requested approved policy must remain visible.
                status: approved
                authority: official
                applicability: {}
                evidence:
                  - repository: first/robot
                    commit: abcdef1
                    file: TeamCode/Saturation.java
                    symbol: Saturation
                approval:
                  approver: overall-lead
                  role: overall_software_lead
                  approvedAt: 2026-08-14T00:00:00Z
        """.trimIndent())
        write(
            "saturated-repo/TeamCode/Saturation.java",
            "class Saturation { // saturation "+"x".repeat(47_850)+" }"
        )
        val index=RepositoryIndex()
        index.build(tempDir.resolve("saturated-repo"))
        val context=ContextRetriever(
            index,KnowledgeRetriever(tempDir.resolve("saturated-knowledge"),"20827","2025-2026")
        ).retrieve(
            RetrievalIntent(setOf("saturation"),emptySet(),emptySet(),setOf("saturation-safety"),emptySet())
        )

        assertTrue(context.evidence.any { it is RuleEvidenceItem && it.rule.id=="official.saturation-safety" })
        assertTrue(context.estimatedCharacters<=48_000)
        assertEquals(EvidenceSerialization.payload(context.evidence).length,context.estimatedCharacters)
    }

    @Test
    fun `planner prompt requires concrete symbols and paths`() {
        val provider=ScriptedProvider(
            """{"concepts":[],"symbols":[],"pathGlobs":[],"ruleTopics":[],"guideTopics":[]}"""
        )

        RetrievalPlanner(provider).plan(input())

        val system=provider.requests.first().messages.first().content
        assertTrue(system.startsWith("Return exactly one JSON object"))
        assertTrue(system.contains("symbols must list every concrete class"))
    }

    private fun input(question:String="How does Drive work?",references:Set<String> =emptySet())=
        PlanningInput(question,null,references,"FTC repository")

    private fun write(path:String,text:String) {
        val file=tempDir.resolve(path)
        file.parent.createDirectories()
        file.writeText(text)
    }

    private class ScriptedProvider(vararg responses:String):ModelProvider {
        private val queue=ArrayDeque(responses.toList())
        val requests=mutableListOf<ModelRequest>()

        override fun complete(request:ModelRequest):ModelResponse {
            requests+=request
            return ModelResponse(queue.removeFirst())
        }
    }
}
