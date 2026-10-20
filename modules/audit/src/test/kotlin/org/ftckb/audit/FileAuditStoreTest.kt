package org.ftckb.audit

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class FileAuditStoreTest {
    @TempDir lateinit var temp: Path
    private fun id() = UUID.randomUUID().toString()
    private fun start(id: String = id(), parent: String? = null, command: String = "check") = AuditEvent(id, AuditEventType.STARTED, Instant.now().toString(), AuditMetadata("cli", command, null, parentInvocationId = parent))
    private fun finish(id: String, failed: Boolean = false) = AuditEvent(id, AuditEventType.FINISHED, Instant.now().toString(), status = if (failed) AuditRunStatus.FAILED else AuditRunStatus.COMPLETED, exitCode = if (failed) 2 else 0, durationMillis = 5)
    private fun stage(id: String, status: AuditStageStatus, name: String = "standardizer") = AuditEvent(id, AuditEventType.STAGE, Instant.now().toString(), stage = AuditStage(name, status))
    private fun store(root: Path? = null) = FileAuditStore(temp.resolve("audit"), root)

    @Test fun `lifecycle remains incomplete until real finish and stages never imply success`() {
        val store = store(); val start = start()
        assertTrue(store.append(start))
        assertEquals(1, store.summary().unfinished)
        assertEquals(AuditRunStatus.STARTED, store.find(start.invocationId)!!.status)
        assertNull(store.find(start.invocationId)!!.endedAt)
        assertEquals(1, store.summary().diffNotRun)
        store.append(stage(start.invocationId, AuditStageStatus.BLOCKED))
        store.append(finish(start.invocationId))
        val summary = store.summary()
        assertEquals(1, summary.diffChecked); assertEquals(1, summary.diffBlocked)
        assertEquals(0, summary.unfinished); assertEquals(0, summary.diffNotRun)
        assertEquals(AuditRunStatus.COMPLETED, store.find(start.invocationId)!!.status)
        assertEquals(summary, store().summary())
    }

    @Test fun `citation guard failure category preserves blocked evidence`() {
        val store = store(); val s = start(command = "edit")
        assertTrue(store.append(s))
        assertTrue(store.append(AuditEvent(s.invocationId, AuditEventType.STAGE, Instant.now().toString(),
            stage = AuditStage("citation_guard", AuditStageStatus.BLOCKED, "citation_guard", "Edit citation validation failed"))))
        assertTrue(store.append(finish(s.invocationId, true)))
        val record = store.find(s.invocationId)!!
        assertEquals(AuditRunStatus.FAILED, record.status)
        assertEquals(AuditStageStatus.BLOCKED, record.stages.single().status)
        assertEquals("citation_guard", record.stages.single().category)
        assertEquals(1, store.summary().diffNotRun)
        assertTrue(store.summary().health.ok)
    }

    @Test fun `wire schema and all enum values are lowercase`() {
        val store = store(); val s = start()
        store.append(s); store.append(stage(s.invocationId, AuditStageStatus.NOT_APPLICABLE)); store.append(finish(s.invocationId))
        val lines = Files.readAllLines(store.partitionPath.resolve("events.jsonl"))
        val json = ObjectMapper()
        assertEquals(1, json.readTree(lines[0]).get("schemaVersion").asInt())
        assertEquals("started", json.readTree(lines[0]).get("type").asText())
        assertEquals("not_applicable", json.readTree(lines[1]).get("stage").get("status").asText())
        assertEquals("completed", json.readTree(lines[2]).get("status").asText())
        for (value in AuditEventType.entries) assertEquals("\"${value.name.lowercase()}\"", json.writeValueAsString(value))
        for (value in AuditStageStatus.entries) assertEquals("\"${value.name.lowercase()}\"", json.writeValueAsString(value))
        assertEquals(1, store.summary().diffNotApplicable)
    }

    @Test fun `canonical roots share a partition and unscoped remains separate`() {
        val root = Files.createDirectory(temp.resolve("knowledge"))
        val alias = temp.resolve("alias"); Files.createSymbolicLink(alias, root)
        val a = store(root); val b = store(alias)
        assertEquals(a.partitionPath, b.partitionPath)
        assertEquals(64, a.partitionPath.fileName.toString().length)
        assertEquals("unscoped", store().partitionPath.fileName.toString())
        a.append(start())
        assertEquals(1, b.summary().total); assertEquals(0, store().summary().total)
    }

    @Test fun `model children indexed but excluded from aggregate denominator`() {
        val store = store(); val parent = start(command = "ask"); val child = start(parent = parent.invocationId, command = "model")
        store.append(parent); store.append(stage(parent.invocationId, AuditStageStatus.NOT_APPLICABLE))
        store.append(child); store.append(finish(child.invocationId, true))
        assertEquals(1, store.summary().total); assertEquals(1, store.summary().unfinished)
        assertEquals(listOf(child.invocationId), store.children(parent.invocationId).map { it.invocationId })
        assertEquals(AuditRunStatus.FAILED, store.children(parent.invocationId).single().status)
    }

    @Test fun `cursor stays stable as new invocations append and existing invocations finish`() {
        val store = store(); val ids = (1..5).map { start().also { store.append(it) }.invocationId }
        val first = store.page(limit = 2)
        assertEquals(ids.takeLast(2).reversed(), first.items.map { it.invocationId })
        store.append(start()); store.append(finish(ids[2]))
        val second = store.page(first.nextCursor, 2)
        assertEquals(listOf(ids[2], ids[1]), second.items.map { it.invocationId })
        val last = store.page(second.nextCursor, 2)
        assertEquals(listOf(ids[0]), last.items.map { it.invocationId }); assertNull(last.nextCursor)
        assertThrows(IllegalArgumentException::class.java) { store.page("bad", 2) }
    }

    @Test fun `corrupt unsupported and partial lines are visible and later appends recover`() {
        val store = store(); val first = start(); store.append(first)
        val path = store.partitionPath.resolve("events.jsonl")
        Files.writeString(path, "garbage\n{\"schemaVersion\":2}\n{\"partial\":", java.nio.file.StandardOpenOption.APPEND)
        val health = store.summary().health
        assertFalse(health.ok); assertTrue(health.warnings.any { it.contains("2 corrupt") }); assertTrue(health.warnings.any { it.contains("trailing") })
        val second = start(); assertTrue(store.append(second)); store.append(finish(second.invocationId))
        val summary = store.summary()
        assertEquals(2, summary.total); assertEquals(1, summary.unfinished)
        assertTrue(summary.health.warnings.any { it.contains("3 corrupt") }); assertFalse(summary.health.warnings.any { it.contains("trailing") })
    }

    @Test fun `orphan and duplicate lifecycle events never invent success`() {
        val store = store(); val s = start()
        store.append(finish(id())); store.append(s); store.append(s); store.append(finish(s.invocationId)); store.append(stage(s.invocationId, AuditStageStatus.PASSED))
        assertEquals(1, store.summary().total); assertEquals(1, store.summary().diffNotRun)
        assertTrue(store.summary().health.warnings.any { it.contains("3 audit events") })
    }

    @Test fun `metadata is bounded and secrets and raw diff are never persisted`() {
        val store = store(); val s = start()
        store.append(s.copy(metadata = s.metadata!!.copy(model = "api_key=supersecret", context = AuditContext("20827", "2025-2026", List(100) { "p".repeat(1000) }))))
        store.append(stage(s.invocationId, AuditStageStatus.PASSED).copy(stage = AuditStage("standardizer", AuditStageStatus.PASSED, detail = "Authorization: Bearer abcdefghijklmnop", hits = listOf(AuditHit("soft", null, null, null, null, "diff --git private content")))))
        val text = Files.readString(store.partitionPath.resolve("events.jsonl"))
        assertFalse(text.contains("supersecret")); assertFalse(text.contains("abcdefghijklmnop")); assertFalse(text.contains("private content"))
        assertEquals(16, store.find(s.invocationId)!!.metadata.context!!.profiles!!.size)
        assertTrue(store.find(s.invocationId)!!.metadata.context!!.profiles!!.all { it.length == 80 })
    }

    @Test fun `write failure and invalid events return false with sanitized diagnostics`() {
        val messages = mutableListOf<String>()
        val base = Files.writeString(temp.resolve("not-directory"), "x")
        val store = FileAuditStore(base, null, messages::add)
        assertFalse(store.append(start()))
        assertEquals(1, messages.size); assertFalse(messages.single().contains(base.toString()))
        assertFalse(store.summary().health.ok)
        val validStore = store()
        assertFalse(validStore.append(start().copy(invocationId = "invalid")))
        assertEquals(0, validStore.summary().total)
    }

    @Test fun `snapshots preserve historical data independent of caller mutation`() {
        val store = store(); val s = start(); store.append(s)
        val rules = mutableListOf(AuditRuleSnapshot("id", "topic", "old title", "instruction", "official", "global", emptyList(), emptyList(), emptyList()))
        store.append(AuditEvent(s.invocationId, AuditEventType.SNAPSHOT, Instant.now().toString(), knowledgeVersion = "old-version", ruleSnapshots = rules))
        rules.clear()
        assertEquals("old title", store.find(s.invocationId)!!.ruleSnapshots.single().title)
        assertEquals("old-version", store.find(s.invocationId)!!.knowledgeVersion)
    }

    @Test fun `incremental reader detects replacement and truncation`() {
        val store = store(); val s = start(); store.append(s); val before = store.summary()
        assertEquals(before.revision, store.summary().revision)
        val replacement = temp.resolve("replacement"); val other = start()
        Files.write(replacement, AuditCodec.encode(other) + byteArrayOf(10))
        Files.move(replacement, store.partitionPath.resolve("events.jsonl"), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        assertEquals(1, store.summary().total); assertNull(store.find(s.invocationId)); assertNotNull(store.find(other.invocationId))
        Files.writeString(store.partitionPath.resolve("events.jsonl"), "")
        assertEquals(0, store.summary().total)
    }

    @Test fun `partial JSON is completed incrementally without losing the event`() {
        val store = store(); val s = start(); store.append(s); store.summary()
        val event = AuditCodec.encode(finish(s.invocationId))
        val path = store.partitionPath.resolve("events.jsonl")
        Files.write(path, event.copyOfRange(0, event.size / 2), java.nio.file.StandardOpenOption.APPEND)
        assertEquals(1, store.summary().unfinished)
        assertFalse(store.summary().health.ok)
        Files.write(path, event.copyOfRange(event.size / 2, event.size) + byteArrayOf(10), java.nio.file.StandardOpenOption.APPEND)
        assertEquals(0, store.summary().unfinished)
        assertTrue(store.summary().health.ok)
    }

    @Test fun `unreadable storage and observed deletion remain visible`() {
        val store = store(); store.append(start()); store.summary()
        val path = store.partitionPath.resolve("events.jsonl")
        Files.delete(path)
        assertFalse(store.summary().health.ok)
        Files.createDirectory(path)
        assertTrue(store.summary().health.warnings.any { it.contains("cannot currently be read") })
    }

    @Test fun `concurrent store instances and separate JVM writers do not interleave lines`() {
        val base = temp.resolve("parallel")
        val reader = FileAuditStore(base, null)
        val pool = Executors.newFixedThreadPool(4)
        try {
            val futures = (1..4).map { pool.submit { repeat(10) { val store = FileAuditStore(base, null); val s = start(); assertTrue(store.append(s)); assertTrue(store.append(finish(s.invocationId))) } } }
            futures.forEach { it.get(20, TimeUnit.SECONDS) }
        } finally { pool.shutdownNow() }
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val processes = (1..3).map { ProcessBuilder(java, "-cp", System.getProperty("audit.test.classpath"), AuditProcessWriter::class.java.name, base.toString(), "15").redirectErrorStream(true).start() }
        processes.forEach { assertTrue(it.waitFor(30, TimeUnit.SECONDS)); assertEquals(0, it.exitValue(), String(it.inputStream.readAllBytes())) }
        val summary = reader.summary()
        assertEquals(85, summary.total); assertEquals(0, summary.unfinished); assertTrue(summary.health.ok, summary.health.warnings.toString())
        assertEquals(170, Files.readAllLines(reader.partitionPath.resolve("events.jsonl")).size)
    }

    @Test fun `old stages without code version remain readable and new versions round trip`() {
        val s = start(); val old = stage(s.invocationId, AuditStageStatus.PASSED)
        val oldJson = ObjectMapper().readTree(AuditCodec.encode(old))
        (oldJson.get("stage") as com.fasterxml.jackson.databind.node.ObjectNode).remove("codeVersion")
        assertNull(AuditCodec.decode(ObjectMapper().writeValueAsBytes(oldJson)).stage!!.codeVersion)
        val version = AuditCodeVersion("supplied_patch", "a".repeat(64), "b".repeat(64), predecessorInvocationId = UUID.randomUUID().toString())
        val current = old.copy(stage = old.stage!!.copy(codeVersion = version))
        assertEquals(version, AuditCodec.decode(AuditCodec.encode(current)).stage!!.codeVersion)
        assertThrows(IllegalArgumentException::class.java) { AuditCodec.encode(current.copy(stage = current.stage!!.copy(codeVersion = version.copy(schemaVersion = 2)))) }
        assertThrows(IllegalArgumentException::class.java) { AuditCodec.encode(current.copy(stage = current.stage!!.copy(codeVersion = version.copy(inputDigest = "invalid")))) }
    }

    @Test fun `directory override is consistent and empty override uses home`() {
        assertEquals(Path.of("/chosen/audit"), defaultAuditDirectory(mapOf("FTCKB_AUDIT_DIR" to "/chosen/audit")))
        assertEquals(Path.of(System.getProperty("user.home"), ".ftckb", "audit"), defaultAuditDirectory(mapOf("FTCKB_AUDIT_DIR" to " ")))
    }
}

object AuditProcessWriter {
    @JvmStatic fun main(args: Array<String>) {
        val store = FileAuditStore(Path.of(args[0]), null)
        repeat(args[1].toInt()) {
            val id = UUID.randomUUID().toString()
            check(store.append(AuditEvent(id, AuditEventType.STARTED, Instant.now().toString(), AuditMetadata("cli", "validate", null))))
            check(store.append(AuditEvent(id, AuditEventType.FINISHED, Instant.now().toString(), status = AuditRunStatus.COMPLETED, exitCode = 0)))
        }
    }
}
