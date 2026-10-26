package org.ftckb.audit

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.attribute.PosixFilePermissions
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class FileReviewStoreTest {
    @TempDir lateinit var temp: Path
    private fun store() = FileReviewStore(temp.resolve("audit"), temp)
    private fun input(target: ReviewTarget = reviewTarget()) = ReviewInput(UUID.randomUUID().toString(), target, 0, "verified", "已查看证据", emptyList(), "tester")
    private fun problem(code: String, block: () -> Unit) = assertEquals(code, assertThrows(ReviewProblem::class.java, block).code)

    @Test fun `reading absent history creates no files and starts healthy`() {
        val s = store(); val target = reviewTarget()
        assertTrue(s.history(target.itemId).items.isEmpty()); assertTrue(s.health().ok)
        assertFalse(Files.exists(s.partitionPath))
    }
    @Test fun `append corrections survive restart and replay ignores stale expected revision`() {
        val s = store(); val i = input(); val first = s.record(i) { it == i.target }
        assertEquals(1L, first.record.revision); assertFalse(first.replayed)
        assertEquals(first.record, s.record(i) { false }.record)
        assertTrue(s.record(i.copy(expectedRevision = 99)) { false }.replayed)
        val second = s.record(i.copy(reviewId = UUID.randomUUID().toString(), expectedRevision = 1, decision = "needs_changes")) { true }
        assertEquals(2L, second.record.revision)
        assertEquals(listOf(first.record, second.record), FileReviewStore(temp.resolve("audit"), temp).history(i.target.itemId).items)
    }
    @Test fun `two stores compare and swap under one process lock`() {
        val a = store(); val b = store(); val i = input(); val gate = CountDownLatch(1); val pool = Executors.newFixedThreadPool(2)
        try {
            val tasks = listOf(a, b).map { s -> pool.submit<String> { gate.await(); try { s.record(i.copy(reviewId = UUID.randomUUID().toString())) { true }; "ok" } catch (p: ReviewProblem) { p.code } } }
            gate.countDown()
            assertEquals(listOf("conflict", "ok"), tasks.map { it.get(10, TimeUnit.SECONDS) }.sorted())
            assertEquals(1, a.history(i.target.itemId).items.size)
        } finally { pool.shutdownNow() }
    }
    @Test fun `source and conflicting id reject without appending`() {
        val s = store(); val i = input()
        problem("source_changed") { s.record(i) { false } }
        s.record(i) { true }
        problem("conflict") { s.record(i.copy(reason = "different")) { true } }
        assertEquals(1, s.history(i.target.itemId).items.size)
    }
    @Test fun `input rejects credential urls controls oversized fields and unsupported values`() {
        val s = store(); val i = input()
        listOf("javascript:alert(1)", "https://user:pass@example.com/", "https://example.com/?%74oken=secret",
            "https://example.com/#API%5FKEY=secret", "https://example.com/?access_token=value",
            "https://example.com/?%61uthorization=value", "https://example.com/#?password=value").forEach { bad ->
            problem("invalid_input") { s.record(i.copy(evidenceUrls = listOf(bad))) { true } }
        }
        problem("invalid_input") { s.record(i.copy(reason = "x".repeat(4001))) { true } }
        problem("invalid_input") { s.record(i.copy(reviewer = "x".repeat(121))) { true } }
        problem("invalid_input") { s.record(i.copy(reason = "bad\u0000value")) { true } }
        problem("invalid_input") { s.record(i.copy(decision = "approved")) { true } }
        problem("invalid_input") { s.record(i.copy(expectedRevision = -1)) { true } }
        problem("invalid_input") { s.record(i.copy(reviewId = i.reviewId.uppercase())) { true } }
        problem("invalid_input") { s.record(i.copy(evidenceUrls = List(9) { "https://example.com" })) { true } }
        assertTrue(s.history(i.target.itemId).items.isEmpty())
    }
    @Test fun `secrets are redacted before persistence and idempotent comparison`() {
        val s = store(); val i = input().copy(reason = "Checked token=privatevalue", reviewer = "Bearer privatevalue")
        val result = s.record(i) { true }
        assertTrue(result.record.sanitized)
        assertFalse(Files.readString(s.partitionPath.resolve("reviews.jsonl")).contains("privatevalue"))
        assertEquals(result.record, s.record(i) { false }.record)
    }
    @Test fun `force failure never becomes success merely because a full line is visible`() {
        val s = store(); val i = input(); s.force = { throw java.io.IOException("force failed") }
        problem("storage_unavailable") { s.record(i) { true } }
        assertEquals(1, s.history(i.target.itemId).items.size)
        assertFalse(s.health().ok)
        problem("storage_unavailable") { s.record(i) { true } }
        s.force = { it.force(true) }
        assertTrue(s.record(i) { false }.replayed)
        assertTrue(s.health().ok)
    }
    @Test fun `partial tail blocks writes and a completed valid line resumes incrementally`() {
        val s = store(); val i = input(); val first = s.record(i) { true }.record
        val second = first.copy(reviewId = UUID.randomUUID().toString(), revision = 2)
        val bytes = ReviewCodec.encode(second); val path = s.partitionPath.resolve("reviews.jsonl")
        Files.write(path, bytes.copyOfRange(0, bytes.size / 2), APPEND)
        assertEquals(1, s.history(i.target.itemId).items.size)
        problem("storage_unavailable") { s.record(i.copy(reviewId = UUID.randomUUID().toString(), expectedRevision = 1)) { true } }
        Files.write(path, bytes.copyOfRange(bytes.size / 2, bytes.size) + byteArrayOf(10), APPEND)
        assertEquals(2, s.history(i.target.itemId).items.size); assertTrue(s.health().ok)
    }
    @Test fun `corrupt duplicate revision and future schema are skipped and forbid further writes`() {
        val s = store(); val i = input(); val first = s.record(i) { true }.record
        val path = s.partitionPath.resolve("reviews.jsonl")
        Files.write(path, ReviewCodec.encode(first.copy(reviewId = UUID.randomUUID().toString())) + byteArrayOf(10), APPEND)
        Files.writeString(path, "{bad}\n", APPEND)
        Files.writeString(path, String(ReviewCodec.encode(first)).replace("\"schemaVersion\":1", "\"schemaVersion\":2") + "\n", APPEND)
        Files.writeString(path, String(ReviewCodec.encode(first)).replace("\"revision\":1", "\"revision\":1,\"revision\":2") + "\n", APPEND)
        assertEquals(1, s.history(i.target.itemId).items.size)
        assertTrue(s.health().warnings.any { it.contains("duplicate") })
        assertTrue(s.health().warnings.any { it.contains("corrupt") })
        problem("storage_unavailable") { s.record(i.copy(reviewId = UUID.randomUUID().toString(), expectedRevision = 1)) { true } }
    }
    @Test fun `UTF8 limit applies to encoded bytes including oversized corrupt line`() {
        val s = store(); val i = input().copy(reason = "证".repeat(4000), reviewer = "名".repeat(120),
            evidenceUrls = List(8) { "https://example.com/" + "证".repeat(2028) })
        val record = s.record(i) { true }.record
        val bytes = ReviewCodec.encode(record)
        assertTrue(bytes.size <= 64 * 1024); assertTrue(bytes.size > 55000)
        assertEquals(record, FileReviewStore(temp.resolve("audit"), temp).history(i.target.itemId).items.single())
        assertThrows(IllegalArgumentException::class.java) { ReviewCodec.decode(ByteArray(64 * 1024 + 1) { 32 }) }
        Files.write(s.partitionPath.resolve("reviews.jsonl"), ByteArray(64 * 1024 + 1) { 32 } + byteArrayOf(10), APPEND)
        assertFalse(s.health().ok)
        problem("storage_unavailable") { s.record(i) { true } }
    }
    @Test fun `unknown fields trailing data and duplicate target keys are invalid records`() {
        val s = store(); val first = s.record(input()) { true }.record; val json = String(ReviewCodec.encode(first))
        listOf(json.dropLast(1) + ",\"unexpected\":1}", json + " {}", json.replace("\"itemId\":", "\"itemId\":\"" + "a".repeat(64) + "\",\"itemId\":"))
            .forEach { assertThrows(Exception::class.java) { ReviewCodec.decode(it.toByteArray()) } }
    }
    @Test fun `observed truncation replacement and removal retain confirmed history and forbid writes`() {
        listOf("truncate", "replace", "remove").forEach { mode ->
            val base = temp.resolve(mode); val s = FileReviewStore(base, temp); val i = input(); s.record(i) { true }; s.health()
            val path = s.partitionPath.resolve("reviews.jsonl")
            when (mode) {
                "truncate" -> Files.writeString(path, "")
                "replace" -> { val other = temp.resolve("replacement"); Files.writeString(other, ""); Files.move(other, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
                else -> Files.delete(path)
            }
            assertEquals(1, s.history(i.target.itemId).items.size)
            assertFalse(s.health().ok)
            problem("storage_unavailable") { s.record(i) { true } }
        }
    }
    @Test fun `read failure and read-only storage do not report saved`() {
        val s = store(); val i = input(); s.record(i) { true }; s.health()
        Files.delete(s.partitionPath.resolve("reviews.jsonl")); Files.createDirectory(s.partitionPath.resolve("reviews.jsonl"))
        assertFalse(s.health().ok)
        problem("storage_unavailable") { s.record(i) { true } }
        val readonly = temp.resolve("readonly"); Files.createDirectory(readonly)
        Files.setPosixFilePermissions(readonly, PosixFilePermissions.fromString("r-x------"))
        try {
            assumeTrue(!Files.isWritable(readonly), "OS grants privileged permission bypass")
            val noWrite = FileReviewStore(readonly, temp)
            problem("storage_unavailable") { noWrite.record(input()) { true } }
        } finally { Files.setPosixFilePermissions(readonly, PosixFilePermissions.fromString("rwx------")) }
    }
    @Test fun `separate JVMs serialize competing revisions`() {
        val target = reviewTarget(); val base = temp.resolve("processes")
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val processes = (1..2).map { ProcessBuilder(java, "-cp", System.getProperty("audit.test.classpath"), ReviewProcessWriter::class.java.name,
            base.toString(), temp.toString(), target.itemId, target.invocationId, target.sourceDigest).redirectErrorStream(true).start() }
        val outcomes = processes.map { p ->
            assertTrue(p.waitFor(30, TimeUnit.SECONDS)); val output = String(p.inputStream.readAllBytes()).trim()
            assertEquals(0, p.exitValue(), output); output
        }
        assertEquals(listOf("conflict", "saved"), outcomes.sorted())
        val s = FileReviewStore(base, temp)
        assertEquals(1, s.history(target.itemId).items.size); assertTrue(s.health().ok)
    }

}


object ReviewProcessWriter {
    @JvmStatic fun main(args: Array<String>) {
        val store = FileReviewStore(Path.of(args[0]), Path.of(args[1]))
        val target = ReviewTarget(args[2], args[3], args[4])
        try { store.record(ReviewInput(UUID.randomUUID().toString(), target, 0, "verified", "Checked", emptyList(), "tester")) { it == target }; println("saved") }
        catch (p: ReviewProblem) { check(p.code == "conflict"); println("conflict") }
    }
}
