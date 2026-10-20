package org.ftckb.audit

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption.*
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** A separate lock file coordinates replay, CAS and durable append across processes. */
class FileReviewStore(
    baseDirectory: Path,
    knowledgeRoot: Path,
    private val diagnostics: (String) -> Unit = { System.err.println(it) }
) : ReviewStore {
    val partitionPath: Path = canonical(baseDirectory).resolve(sha256(canonical(knowledgeRoot).toString()))
    private val logPath = partitionPath.resolve("reviews.jsonl")
    private val lockPath = partitionPath.resolve("reviews.lock")
    private val mutex = locks.computeIfAbsent(lockPath) { ReentrantLock() }
    private val histories = HashMap<String, MutableList<ReviewRecord>>()
    private val byId = HashMap<String, ReviewRecord>()
    private var scanOffset = 0L
    private var offset = 0L
    private var seenSize = -1L
    private var seenModified: String? = null
    private var identity: String? = null
    private var observedFile = false
    private var revision = "empty"
    private val pending = ByteArrayOutputStream()
    private var tooLarge = false
    private var partial = false
    private var corrupt = 0L
    private var invalidSequence = 0L
    private var storageChanged = false
    private var readFailed = false
    private var writeFailed = false
    /** Test seam for fsync failures; production always calls FileChannel.force(true). */
    internal var force: (FileChannel) -> Unit = { it.force(true) }

    override fun history(itemId: String): ReviewHistory = mutex.withLock {
        try { ReviewCodec.digest(itemId) } catch (_: Exception) { throw ReviewProblem("invalid_input") }
        readRefresh()
        ReviewHistory(histories[itemId]?.toList().orEmpty(), revision, currentHealth())
    }
    override fun health(): AuditHealth = mutex.withLock { readRefresh(); currentHealth() }

    override fun record(input: ReviewInput, validateSource: (ReviewTarget) -> Boolean): ReviewWriteResult = mutex.withLock {
        val normalized = try { ReviewCodec.safe(input) } catch (_: Exception) { throw ReviewProblem("invalid_input") }
        val safe = normalized.first
        try {
            Files.createDirectories(partitionPath)
            FileChannel.open(lockPath, CREATE, WRITE).use { lockChannel ->
                lockChannel.lock().use {
                    refresh()
                    if (!integrityHealthy()) throw ReviewProblem("storage_unavailable")
                    val previous = byId[safe.reviewId]
                    if (previous != null) {
                        if (!sameContent(previous, safe)) throw ReviewProblem("conflict")
                        // Visibility after a failed fsync is not durability. Every replay must force successfully.
                        FileChannel.open(logPath, WRITE).use { force(it) }
                        writeFailed = false
                        return@withLock ReviewWriteResult(previous, true)
                    }
                    if (writeFailed) throw ReviewProblem("storage_unavailable")
                    val latest = histories[safe.target.itemId]?.lastOrNull()
                    if ((latest?.revision ?: 0L) != safe.expectedRevision) throw ReviewProblem("conflict")
                    if (!validateSource(safe.target)) throw ReviewProblem("source_changed")
                    if (latest != null && latest.target != safe.target) throw ReviewProblem("source_changed")
                    if (safe.expectedRevision == Long.MAX_VALUE) throw ReviewProblem("conflict")
                    val record = ReviewRecord(safe.reviewId, safe.target, safe.expectedRevision + 1, safe.decision, safe.reason,
                        safe.evidenceUrls, safe.reviewer, Instant.now().toString(), normalized.second)
                    val bytes = try { ReviewCodec.encode(record) } catch (_: Exception) { throw ReviewProblem("invalid_input") }
                    FileChannel.open(logPath, CREATE, WRITE).use { channel ->
                        channel.position(channel.size())
                        writeFully(channel, bytes)
                        writeFully(channel, byteArrayOf(10))
                        force(channel)
                    }
                    // Replay the exact bytes persisted, so source of truth is identical after restart.
                    refresh()
                    if (!integrityHealthy() || byId[record.reviewId] != record) throw ReviewProblem("storage_unavailable")
                    ReviewWriteResult(record, false)
                }
            }
        } catch (problem: ReviewProblem) {
            throw problem
        } catch (_: Exception) {
            writeFailed = true
            try { diagnostics("ftckb review: storage write failed; review was not confirmed saved") } catch (_: Exception) { }
            throw ReviewProblem("storage_unavailable")
        }
    }

    private fun sameContent(record: ReviewRecord, input: ReviewInput): Boolean = record.reviewId == input.reviewId &&
        record.target == input.target && record.decision == input.decision && record.reason == input.reason &&
        record.evidenceUrls == input.evidenceUrls && record.reviewer == input.reviewer

    private fun readRefresh() {
        try {
            if (!Files.exists(logPath)) { refresh(); return }
            FileChannel.open(lockPath, CREATE, WRITE).use { channel -> channel.lock().use { refresh() } }
        } catch (_: Exception) { readFailed = true }
    }
    private fun integrityHealthy() = !storageChanged && !readFailed && corrupt == 0L && invalidSequence == 0L && !partial
    private fun currentHealth(): AuditHealth {
        val warnings = buildList {
            if (writeFailed) add("A review storage write failed; saved state has not been confirmed durable.")
            if (storageChanged) add("Review storage was replaced, truncated or removed; earlier history may be missing.")
            if (readFailed) add("Review storage cannot currently be read; displayed history may be stale.")
            if (corrupt > 0) add("$corrupt corrupt or unsupported review lines were skipped.")
            if (invalidSequence > 0) add("$invalidSequence duplicate or out-of-order review revisions were skipped.")
            if (partial) add("An incomplete trailing review line is pending and is not a saved review.")
        }
        return AuditHealth(warnings.isEmpty(), warnings)
    }
    /** Caller owns reviews.lock. Do not recursively lock the data file. */
    private fun refresh() {
        try {
            val attrs = try { Files.readAttributes(logPath, BasicFileAttributes::class.java) } catch (_: NoSuchFileException) { null }
            if (attrs == null) {
                if (observedFile) storageChanged = true
                readFailed = false
                return
            }
            FileChannel.open(logPath, READ).use { channel ->
                val size = channel.size()
                val key = attrs.fileKey()?.toString()
                val modified = attrs.lastModifiedTime().toString()
                if (observedFile && (key != identity || size < seenSize || (size == seenSize && modified != seenModified))) storageChanged = true
                // Preserve confirmed readable history if the underlying log was replaced or shortened.
                if (storageChanged) return
                if (size != seenSize) readIncrement(channel, size)
                observedFile = true; seenSize = size; identity = key; seenModified = modified
                revision = "$size:$offset:$modified"
                readFailed = false
            }
        } catch (_: Exception) { readFailed = true }
    }
    private fun readIncrement(channel: FileChannel, size: Long) {
        channel.position(scanOffset)
        val buffer = ByteBuffer.allocate(16 * 1024)
        var position = scanOffset
        while (position < size) {
            buffer.clear(); buffer.limit(minOf(buffer.capacity().toLong(), size - position).toInt())
            if (channel.read(buffer) <= 0) throw IllegalStateException("Unexpected EOF")
            buffer.flip()
            while (buffer.hasRemaining()) {
                val b = buffer.get(); position++
                if (b == 10.toByte()) {
                    if (tooLarge || pending.size() == 0) corrupt++
                    else try { accept(ReviewCodec.decode(pending.toByteArray())) } catch (_: Exception) { corrupt++ }
                    pending.reset(); tooLarge = false; offset = position
                } else if (!tooLarge) {
                    if (pending.size() >= ReviewCodec.MAX_LINE_BYTES) { tooLarge = true; pending.reset() }
                    else pending.write(b.toInt())
                }
            }
        }
        scanOffset = position; partial = position > offset
    }
    private fun accept(record: ReviewRecord) {
        val history = histories[record.target.itemId]
        val previous = history?.lastOrNull()
        if (byId.containsKey(record.reviewId) || record.revision != (previous?.revision ?: 0L) + 1 ||
            (previous != null && previous.target != record.target)) { invalidSequence++; return }
        histories.getOrPut(record.target.itemId) { mutableListOf() }.add(record)
        byId[record.reviewId] = record
    }
    companion object {
        private val locks = ConcurrentHashMap<Path, ReentrantLock>()
        private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        private fun canonical(path: Path): Path {
            val absolute = path.toAbsolutePath().normalize()
            var existing = absolute
            val suffix = ArrayDeque<String>()
            while (!Files.exists(existing)) {
                suffix.addFirst(existing.fileName.toString())
                existing = existing.parent ?: return absolute
            }
            return suffix.fold(existing.toRealPath()) { parent, name -> parent.resolve(name) }
        }
        private fun writeFully(channel: FileChannel, bytes: ByteArray) {
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
        }
    }
}
