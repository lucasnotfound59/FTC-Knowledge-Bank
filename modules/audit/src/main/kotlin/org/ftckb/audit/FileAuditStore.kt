package org.ftckb.audit

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.NoSuchFileException
import java.nio.file.StandardOpenOption.*
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.TreeMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Append-only, process-locked JSONL with an in-memory index advanced only over new complete lines. */
class FileAuditStore(
    baseDirectory: Path,
    knowledgeRoot: Path?,
    private val diagnostics: (String) -> Unit = { System.err.println(it) }
) : AuditStore {
    val partitionPath: Path = canonical(baseDirectory).resolve(
        knowledgeRoot?.let { sha256(canonical(it).toString()) } ?: "unscoped"
    )
    private val logPath = partitionPath.resolve("events.jsonl")
    private val mutex = locks.computeIfAbsent(logPath) { ReentrantLock() }
    private val records = HashMap<String, AuditRecord>()
    private val order = TreeMap<Long, String>()
    private val childIds = HashMap<String, MutableList<String>>()
    private var offset = 0L
    private var scanOffset = 0L
    private val pendingLine = ByteArrayOutputStream()
    private var pendingTooLarge = false
    private var seenSize = -1L
    private var seenModified: String? = null
    private var fileIdentity: String? = null
    private var corrupt = 0L
    private var orphaned = 0L
    private var partial = false
    private var writeFailed = false
    private var readFailed = false
    private var storageChanged = false
    private var revision = "empty"
    private var total = 0L
    private var unfinished = 0L
    private var checked = 0L
    private var blocked = 0L
    private var notRun = 0L
    private var notApplicable = 0L

    override fun append(event: AuditEvent): Boolean = mutex.withLock {
        try {
            val bytes = AuditCodec.encode(event)
            Files.createDirectories(partitionPath)
            FileChannel.open(logPath, CREATE, READ, WRITE).use { channel ->
                channel.lock().use {
                    val length = channel.size()
                    channel.position(length)
                    // Preserve evidence of interrupted writes while allowing later complete events to recover.
                    if (length > 0) {
                        val last = ByteBuffer.allocate(1)
                        channel.read(last, length - 1)
                        if (last.array()[0] != '\n'.code.toByte()) writeFully(channel, byteArrayOf('\n'.code.toByte()))
                    }
                    writeFully(channel, bytes)
                    writeFully(channel, byteArrayOf('\n'.code.toByte()))
                    channel.force(true)
                }
            }
            true
        } catch (_: Exception) {
            writeFailed = true
            // Exception messages can contain paths, credentials or provider response bodies.
            try { diagnostics("ftckb audit: storage write failed; invocation audit is incomplete") } catch (_: Exception) { }
            false
        }
    }

    override fun page(cursor: String?, limit: Int): AuditPage = mutex.withLock {
        require(limit in 1..100) { "Audit page limit must be between 1 and 100" }
        val before = cursor?.let { requireNotNull(it.toLongOrNull()?.takeIf { n -> n >= 0 }) { "Invalid audit cursor" } }
        refresh()
        val candidates = if (before == null) order.descendingMap() else order.headMap(before, false).descendingMap()
        val entries = candidates.entries.take(limit + 1)
        val selected = entries.take(limit)
        AuditPage(selected.mapNotNull { records[it.value] }, if (entries.size > limit) selected.last().key.toString() else null, revision, health())
    }

    override fun find(invocationId: String): AuditRecord? = mutex.withLock { refresh(); records[invocationId] }

    override fun children(parentInvocationId: String): List<AuditRecord> = mutex.withLock {
        refresh()
        childIds[parentInvocationId].orEmpty().mapNotNull { records[it] }
    }

    override fun summary(): AuditSummary = mutex.withLock {
        refresh()
        AuditSummary(revision, total, unfinished, checked, blocked, notRun, notApplicable, health())
    }

    private fun health(): AuditHealth {
        val warnings = buildList {
            if (writeFailed) add("A storage write failed in this process; recorded history may be incomplete.")
            if (storageChanged) add("Audit storage was replaced, truncated or removed; earlier history may be missing.")
            if (readFailed) add("Audit storage cannot currently be read; displayed records may be stale.")
            if (corrupt > 0) add("$corrupt corrupt or unsupported audit lines were skipped.")
            if (orphaned > 0) add("$orphaned audit events have no valid lifecycle and were skipped.")
            if (partial) add("An incomplete trailing audit line is pending; it is not counted as a completed event.")
        }
        return AuditHealth(warnings.isEmpty(), warnings)
    }

    private fun reset() {
        records.clear(); order.clear(); childIds.clear(); offset = 0; scanOffset = 0; pendingLine.reset(); pendingTooLarge = false; corrupt = 0; orphaned = 0
        total = 0; unfinished = 0; checked = 0; blocked = 0; notRun = 0; notApplicable = 0
        partial = false
    }

    private fun refresh() {
        try {
            val initialAttributes = try { Files.readAttributes(logPath, BasicFileAttributes::class.java) } catch (_: NoSuchFileException) { null }
            if (initialAttributes == null) {
                if (seenSize > 0) storageChanged = true
                reset(); seenSize = -1; fileIdentity = null; seenModified = null; revision = "empty"; readFailed = false
                return
            }
            FileChannel.open(logPath, READ).use { channel ->
                channel.lock(0L, Long.MAX_VALUE, true).use {
                    val attributes = Files.readAttributes(logPath, BasicFileAttributes::class.java)
                    val identity = attributes.fileKey()?.toString()
                    val modified = attributes.lastModifiedTime().toString()
                    val size = channel.size()
                    if (identity != fileIdentity || size < seenSize || (size == seenSize && modified != seenModified)) {
                        if (seenSize >= 0) storageChanged = true
                        reset()
                    }
                    if (size != seenSize || modified != seenModified) readIncrement(channel, size)
                    seenSize = size; seenModified = modified; fileIdentity = identity
                    revision = "$size:$offset:$modified"
                    readFailed = false
                }
            }
        } catch (_: Exception) {
            readFailed = true
        }
    }

    private fun readIncrement(channel: FileChannel, size: Long) {
        channel.position(scanOffset)
        val buffer = ByteBuffer.allocate(16 * 1024)
        val line = pendingLine
        var position = scanOffset
        var tooLarge = pendingTooLarge
        partial = false
        while (position < size) {
            buffer.clear()
            buffer.limit(minOf(buffer.capacity().toLong(), size - position).toInt())
            if (channel.read(buffer) <= 0) break
            buffer.flip()
            while (buffer.hasRemaining()) {
                val b = buffer.get()
                position++
                if (b == '\n'.code.toByte()) {
                    if (tooLarge || line.size() == 0) corrupt++
                    else try { accept(AuditCodec.decode(line.toByteArray()), offset) } catch (_: Exception) { corrupt++ }
                    offset = position
                    line.reset(); tooLarge = false
                } else if (!tooLarge) {
                    if (line.size() >= AuditCodec.MAX_LINE_BYTES) { tooLarge = true; line.reset() }
                    else line.write(b.toInt())
                }
            }
        }
        scanOffset = position
        pendingTooLarge = tooLarge
        partial = position > offset
    }

    private fun accept(event: AuditEvent, sequence: Long) {
        val previous = records[event.invocationId]
        if (event.type == AuditEventType.STARTED) {
            if (previous != null) { orphaned++; return }
            val record = AuditRecord(event.invocationId, event.timestamp, null, requireNotNull(event.metadata), event.knowledgeVersion, event.ruleSnapshots, emptyList(), AuditRunStatus.STARTED, null, null, null, null)
            records[event.invocationId] = record
            order[sequence] = event.invocationId
            record.metadata.parentInvocationId?.let { childIds.getOrPut(it) { mutableListOf() }.add(event.invocationId) }
            count(record, 1)
            return
        }
        if (previous == null || previous.status != AuditRunStatus.STARTED) { orphaned++; return }
        val record = when (event.type) {
            AuditEventType.SNAPSHOT -> previous.copy(knowledgeVersion = event.knowledgeVersion ?: previous.knowledgeVersion, ruleSnapshots = event.ruleSnapshots)
            AuditEventType.STAGE -> previous.copy(stages = previous.stages + requireNotNull(event.stage))
            AuditEventType.FINISHED -> previous.copy(endedAt = event.timestamp, status = requireNotNull(event.status), exitCode = event.exitCode, durationMillis = event.durationMillis, inputTokens = event.inputTokens, outputTokens = event.outputTokens)
            AuditEventType.STARTED -> error("Unreachable")
        }
        count(previous, -1)
        records[event.invocationId] = record
        count(record, 1)
    }

    private fun count(record: AuditRecord, amount: Long) {
        if (record.metadata.parentInvocationId != null || record.metadata.command == "model") return
        total += amount
        if (record.status == AuditRunStatus.STARTED) unfinished += amount
        val standardizer = record.stages.filter { it.name == "standardizer" }
        when {
            standardizer.any { it.status == AuditStageStatus.PASSED || it.status == AuditStageStatus.BLOCKED } -> {
                checked += amount
                if (standardizer.any { it.status == AuditStageStatus.BLOCKED }) blocked += amount
            }
            standardizer.any { it.status == AuditStageStatus.NOT_APPLICABLE } -> notApplicable += amount
            else -> notRun += amount
        }
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
