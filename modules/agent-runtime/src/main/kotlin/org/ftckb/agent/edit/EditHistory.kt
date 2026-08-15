package org.ftckb.agent.edit

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Collections
import org.ftckb.git.TextChange

data class HistoryResult(val changedPaths:Set<String>,val conflicts:Set<String>) {
    val succeeded:Boolean get()=conflicts.isEmpty()
}

class EditHistory(root:Path,private val engine:FileEditEngine=FileEditEngine(root)) {
    private val paths=SafeEditPath(root)
    private var firstTouch=linkedMapOf<String,FileSnapshot>()
    private var expected=linkedMapOf<String,FileSnapshot>()
    private var scopes=linkedMapOf<String,EditScope>()
    private var batches=ArrayDeque<AppliedEditBatch>()

    @Synchronized
    fun applyAndRecord(batch:ValidatedEditBatch,authorizationGuard:()->Unit={}):AppliedEditBatch {
        val changes=Collections.unmodifiableList(ArrayList(batch.changes))
        require(changes.map { it.path }.toSet().size==changes.size) { "edit history batch contains duplicate files" }
        val nextFirstTouch=LinkedHashMap(firstTouch)
        val nextExpected=LinkedHashMap(expected)
        val nextScopes=LinkedHashMap(scopes)
        changes.forEach { change ->
            val prior=nextExpected[change.path]
            require(prior==null || prior==change.before) { "edit history is out of sequence: ${change.path}" }
            require(nextScopes[change.path]?.let { it==change.scope }!=false) {
                "edit history scope changed: ${change.path}"
            }
            nextFirstTouch.putIfAbsent(change.path,change.before)
            nextScopes.putIfAbsent(change.path,change.scope)
            nextExpected[change.path]=change.after
        }
        val recorded=AppliedEditBatch(batch.summary,changes)
        val nextBatches=ArrayDeque(batches).apply { addLast(recorded) }
        val applied=engine.apply(ValidatedEditBatch(batch.summary,changes),authorizationGuard)
        firstTouch=nextFirstTouch
        expected=nextExpected
        scopes=nextScopes
        batches=nextBatches
        return applied
    }

    @Synchronized
    fun undo(authorizationGuard:()->Unit={}):HistoryResult {
        val batch=batches.lastOrNull() ?:return HistoryResult(emptySet(),emptySet())
        val conflicts=conflicts(batch.changes.associate { it.path to it.after })
        if (conflicts.isNotEmpty()) return HistoryResult(emptySet(),conflicts)
        val reverse=batch.changes.map { change ->
            PlannedFileChange(change.path,change.after,change.before,change.scope)
        }
        try {
            engine.apply(ValidatedEditBatch("undo ${batch.summary}",reverse),authorizationGuard)
        } catch (failure:Exception) {
            if (!failure.isCleanLiveConflict()) throw failure
            val raced=conflicts(batch.changes.associate { it.path to it.after })
            if (raced.isNotEmpty()) return HistoryResult(emptySet(),raced)
            throw failure
        }
        batches.removeLast()
        reverse.forEach { change -> expected[change.path]=change.after }
        return HistoryResult(reverse.mapTo(linkedSetOf()) { it.path },emptySet())
    }

    @Synchronized
    fun discard(authorizationGuard:()->Unit={}):HistoryResult {
        val conflicts=conflicts(expected)
        if (conflicts.isNotEmpty()) return HistoryResult(emptySet(),conflicts)
        val reverse=firstTouch.mapNotNull { (path,baseline) ->
            val current=expected.getValue(path)
            if (current==baseline) null else PlannedFileChange(path,current,baseline,scopes.getValue(path))
        }
        if (reverse.isNotEmpty()) try {
            engine.apply(ValidatedEditBatch("discard Agent edits",reverse),authorizationGuard)
        } catch (failure:Exception) {
            if (!failure.isCleanLiveConflict()) throw failure
            val raced=conflicts(expected)
            if (raced.isNotEmpty()) return HistoryResult(emptySet(),raced)
            throw failure
        }
        reverse.forEach { change -> expected[change.path]=change.after }
        batches.clear()
        return HistoryResult(reverse.mapTo(linkedSetOf()) { it.path },emptySet())
    }

    @Synchronized
    fun changes():List<TextChange> =firstTouch.mapNotNull { (path,before) ->
        val after=expected.getValue(path)
        if (before==after) null else TextChange(
            path,before.textOrNull(),after.textOrNull(),scopes.getValue(path)==EditScope.PROJECT_LEVEL
        )
    }

    private fun conflicts(snapshots:Map<String,FileSnapshot>):Set<String> =snapshots
        .filter { (path,snapshot) -> runCatching { current(path) }.getOrNull()!=snapshot }
        .keys
        .toCollection(linkedSetOf())

    private fun Exception.isCleanLiveConflict():Boolean=when (this) {
        is FileEditApplyException ->
            rollbackFailures.isEmpty() && cleanupFailures.isEmpty() && originalFailure is IllegalArgumentException
        is IllegalArgumentException -> true
        else -> false
    }

    private fun current(path:String):FileSnapshot {
        val absolute=paths.resolve(path).absolute
        if (!Files.exists(absolute,LinkOption.NOFOLLOW_LINKS)) return FileSnapshot.Missing
        require(Files.isRegularFile(absolute,LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(absolute)) {
            "edit history path is not a regular file"
        }
        val bytes=Files.newByteChannel(
            absolute,setOf(StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)
        ).use { channel ->
            val output=ByteArrayOutputStream()
            val buffer=ByteBuffer.allocate(8_192)
            var total=0
            while (true) {
                buffer.clear()
                val count=channel.read(buffer)
                if (count<0) break
                if (count==0) continue
                total+=count
                require(total<=MAX_FILE_BYTES) { "edit history file exceeds size limit" }
                output.write(buffer.array(),0,count)
            }
            output.toByteArray()
        }
        val content=decodeUtf8(bytes)
        return FileSnapshot.Text(content,sha256(bytes))
    }

    private fun decodeUtf8(bytes:ByteArray):String=try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (failure:CharacterCodingException) {
        throw IllegalArgumentException("edit history file is not UTF-8 text",failure)
    }

    private fun FileSnapshot.textOrNull():String?=when (this) {
        FileSnapshot.Missing -> null
        is FileSnapshot.Text -> content
    }

    private fun sha256(bytes:ByteArray):String=MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private companion object { const val MAX_FILE_BYTES=1_048_576 }
}
