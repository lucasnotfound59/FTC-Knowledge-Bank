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
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.util.Collections
import org.ftckb.git.GitWorkspace
import org.ftckb.git.TextChange

data class HistoryResult(val changedPaths:Set<String>,val conflicts:Set<String>) {
    val succeeded:Boolean get()=conflicts.isEmpty()
}

class EditHistory(
    root:Path,
    private val engine:FileEditEngine=FileEditEngine(root),
    private val firstTouchGitRoot:Path?=null
) {
    private val paths=SafeEditPath(root)
    private var firstTouch=linkedMapOf<String,FileSnapshot>()
    private var expected=linkedMapOf<String,FileSnapshot>()
    private var scopes=linkedMapOf<String,EditScope>()
    private var expectedExecutable=linkedMapOf<String,Boolean?>()
    private var firstTouchPermissions=linkedMapOf<String,Set<PosixFilePermission>?>()
    private var expectedPermissions=linkedMapOf<String,Set<PosixFilePermission>?>()
    private var batches=ArrayDeque<AppliedEditBatch>()
    private var dirtyAtFirstTouch=linkedSetOf<String>()

    val hasTrackedPaths:Boolean
        @Synchronized get()=firstTouch.isNotEmpty()

    val firstTouchDirtyPaths:Set<String>
        @Synchronized get()=Collections.unmodifiableSet(LinkedHashSet(dirtyAtFirstTouch))

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
        val newPaths=changes.mapTo(linkedSetOf()) { it.path }.filterTo(linkedSetOf()) { it !in firstTouch }
        var observedDirty=emptySet<String>()
        val applyPermissions=LinkedHashMap(batch.expectedPermissions).apply {
            changes.forEach { change->
                if (expectedPermissions.containsKey(change.path)) {
                    this[change.path]=expectedPermissions[change.path]
                }
            }
        }
        val guardedBatch=ValidatedEditBatch(
            batch.summary,changes,batch.desiredPermissions,applyPermissions
        )
        val applied=if (firstTouchGitRoot!=null && newPaths.isNotEmpty()) {
            val expectedContents=changes
                .filter { it.path in newPaths }
                .associate { it.path to it.before.textOrNull() }
            GitWorkspace.withFirstTouchDirtyPaths(firstTouchGitRoot,expectedContents) {
                engine.apply(guardedBatch,authorizationGuard)
            }.let { result->
                require(result.dirtyPaths.all { it in newPaths }) {
                    "first-touch dirty inspection returned an unrelated path"
                }
                observedDirty=result.dirtyPaths.toCollection(linkedSetOf()).apply {
                    newPaths.forEach { path->
                        if (result.beforeExecutable[path]!=result.value.beforeExecutable[path]) add(path)
                    }
                }
                result.value
            }
        } else {
            engine.apply(guardedBatch,authorizationGuard)
        }
        val nextExecutable=LinkedHashMap(expectedExecutable).apply {
            applied.changes.forEach { change->this[change.path]=change.expectedExecutable }
        }
        val nextFirstTouchPermissions=LinkedHashMap(firstTouchPermissions).apply {
            newPaths.forEach { path->this[path]=applied.beforePermissions[path] }
        }
        val nextExpectedPermissions=LinkedHashMap(expectedPermissions).apply {
            applied.afterPermissions.forEach { (path,permissions)->this[path]=permissions }
        }
        val nextBatches=ArrayDeque(batches).apply { addLast(applied) }
        firstTouch=nextFirstTouch
        expected=nextExpected
        scopes=nextScopes
        expectedExecutable=nextExecutable
        firstTouchPermissions=nextFirstTouchPermissions
        expectedPermissions=nextExpectedPermissions
        batches=nextBatches
        dirtyAtFirstTouch=LinkedHashSet(dirtyAtFirstTouch).apply { addAll(observedDirty) }
        return applied
    }

    @Synchronized
    fun undo(authorizationGuard:()->Unit={}):HistoryResult {
        val batch=batches.lastOrNull() ?:return HistoryResult(emptySet(),emptySet())
        val conflicts=conflicts(batch.changes.associate { it.path to it.after },batch.afterPermissions)
        if (conflicts.isNotEmpty()) return HistoryResult(emptySet(),conflicts)
        val reverse=batch.changes.map { change ->
            PlannedFileChange(change.path,change.after,change.before,change.scope)
        }
        val applied=try {
            engine.apply(
                ValidatedEditBatch(
                    "undo ${batch.summary}",reverse,batch.beforePermissions,batch.afterPermissions
                ),authorizationGuard
            )
        } catch (failure:Exception) {
            if (!failure.isCleanLiveConflict()) throw failure
            val raced=conflicts(batch.changes.associate { it.path to it.after },batch.afterPermissions)
            if (raced.isNotEmpty()) return HistoryResult(emptySet(),raced)
            throw failure
        }
        batches.removeLast()
        reverse.forEach { change -> expected[change.path]=change.after }
        applied.changes.forEach { change->expectedExecutable[change.path]=change.expectedExecutable }
        applied.afterPermissions.forEach { (path,permissions)->expectedPermissions[path]=permissions }
        return HistoryResult(reverse.mapTo(linkedSetOf()) { it.path },emptySet())
    }

    @Synchronized
    fun discard(authorizationGuard:()->Unit={}):HistoryResult {
        val conflicts=conflicts(expected,expectedPermissions)
        if (conflicts.isNotEmpty()) return HistoryResult(emptySet(),conflicts)
        val reverse=firstTouch.mapNotNull { (path,baseline) ->
            val current=expected.getValue(path)
            if (
                current==baseline && expectedPermissions[path]==firstTouchPermissions[path]
            ) null else PlannedFileChange(path,current,baseline,scopes.getValue(path))
        }
        val applied=if (reverse.isNotEmpty()) try {
            val baselinePermissions=reverse.associate { change->
                change.path to firstTouchPermissions[change.path]
            }
            val currentPermissions=reverse.associate { change->
                change.path to expectedPermissions[change.path]
            }
            engine.apply(
                ValidatedEditBatch(
                    "discard Agent edits",reverse,baselinePermissions,currentPermissions
                ),authorizationGuard
            )
        } catch (failure:Exception) {
            if (!failure.isCleanLiveConflict()) throw failure
            val raced=conflicts(expected,expectedPermissions)
            if (raced.isNotEmpty()) return HistoryResult(emptySet(),raced)
            throw failure
        } else AppliedEditBatch("discard Agent edits",emptyList())
        reverse.forEach { change -> expected[change.path]=change.after }
        applied.changes.forEach { change->expectedExecutable[change.path]=change.expectedExecutable }
        applied.afterPermissions.forEach { (path,permissions)->expectedPermissions[path]=permissions }
        batches.clear()
        return HistoryResult(reverse.mapTo(linkedSetOf()) { it.path },emptySet())
    }

    @Synchronized
    fun changes():List<TextChange> =firstTouch.mapNotNull { (path,before) ->
        val after=expected.getValue(path)
        if (before==after && firstTouchPermissions[path]==expectedPermissions[path]) null else TextChange(
            path,before.textOrNull(),after.textOrNull(),scopes.getValue(path)==EditScope.PROJECT_LEVEL,
            expectedExecutable.getValue(path)
        )
    }

    private fun conflicts(
        snapshots:Map<String,FileSnapshot>,permissions:Map<String,Set<PosixFilePermission>?>
    ):Set<String> =snapshots
        .filter { (path,snapshot) ->
            val current=current(path)
            current.snapshot!=snapshot ||
                permissions.containsKey(path) && permissions[path]!=null && current.permissions!=permissions[path]
        }
        .keys
        .toCollection(linkedSetOf())

    private fun Exception.isCleanLiveConflict():Boolean=
        this is FileEditApplyException && rollbackFailures.isEmpty() && cleanupFailures.isEmpty() &&
            (originalFailure is EditContentRaceException || originalFailure is EditPermissionRaceException)

    private data class CurrentFileState(
        val snapshot:FileSnapshot,val permissions:Set<PosixFilePermission>?
    )

    private fun current(path:String):CurrentFileState {
        val absolute=paths.resolve(path).absolute
        val snapshot=readSnapshotOrMissing(absolute) { existing ->
            val attributes=Files.readAttributes(
                existing,BasicFileAttributes::class.java,LinkOption.NOFOLLOW_LINKS
            )
            require(attributes.isRegularFile && !attributes.isSymbolicLink) {
                "edit history path is not a regular file"
            }
            val bytes=Files.newByteChannel(
                existing,setOf(StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)
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
            FileSnapshot.Text(content,sha256(bytes))
        }
        val permissions=if (snapshot is FileSnapshot.Text) try {
            Collections.unmodifiableSet(
                LinkedHashSet(Files.getPosixFilePermissions(absolute,LinkOption.NOFOLLOW_LINKS))
            )
        } catch (_:UnsupportedOperationException) {
            null
        } else null
        return CurrentFileState(snapshot,permissions)
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
