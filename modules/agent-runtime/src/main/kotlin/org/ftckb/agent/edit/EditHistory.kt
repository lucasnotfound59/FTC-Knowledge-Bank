package org.ftckb.agent.edit

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.util.Collections
import org.ftckb.git.GitWorkspace
import org.ftckb.git.TextChange

data class HistoryResult(
    val changedPaths:Set<String>,val conflicts:Set<String>,val warnings:List<String> =emptyList()
) {
    val succeeded:Boolean get()=conflicts.isEmpty()
}

private const val OBSERVATION_WARNING=
    "Some edited paths could not be safely inspected; no files were overwritten"

class EditHistory(
    root:Path,
    private val engine:FileEditEngine=FileEditEngine(root),
    private val firstTouchGitRoot:Path?=null,
    private val beforeObservation:(Path)->Unit={},
    private val afterObservationAttributes:(Path)->Unit={},
    private val beforePermissionObservation:(Path)->Unit={},
    private val posixPermissionsReader:(Path)->Set<PosixFilePermission>?=::readPosixPermissions
) {
    private val paths=SafeEditPath(root)
    private var firstTouch=linkedMapOf<String,FileSnapshot>()
    private var expected=linkedMapOf<String,FileSnapshot>()
    private var scopes=linkedMapOf<String,EditScope>()
    private var expectedExecutable=linkedMapOf<String,Boolean?>()
    private var firstTouchExecutable=linkedMapOf<String,Boolean?>()
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
        val applyExecutable=LinkedHashMap(batch.expectedExecutable).apply {
            changes.forEach { change->
                if (expectedExecutable.containsKey(change.path)) {
                    this[change.path]=expectedExecutable[change.path]
                }
            }
        }
        val guardedBatch=ValidatedEditBatch(
            batch.summary,changes,batch.desiredPermissions,applyPermissions,
            batch.desiredExecutable,applyExecutable
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
        val nextFirstTouchExecutable=LinkedHashMap(firstTouchExecutable).apply {
            newPaths.forEach { path->this[path]=applied.beforeExecutable[path] }
        }
        val nextExpectedPermissions=LinkedHashMap(expectedPermissions).apply {
            applied.afterPermissions.forEach { (path,permissions)->this[path]=permissions }
        }
        val nextBatches=ArrayDeque(batches).apply { addLast(applied) }
        firstTouch=nextFirstTouch
        expected=nextExpected
        scopes=nextScopes
        expectedExecutable=nextExecutable
        firstTouchExecutable=nextFirstTouchExecutable
        firstTouchPermissions=nextFirstTouchPermissions
        expectedPermissions=nextExpectedPermissions
        batches=nextBatches
        dirtyAtFirstTouch=LinkedHashSet(dirtyAtFirstTouch).apply { addAll(observedDirty) }
        return applied
    }

    @Synchronized
    fun undo(authorizationGuard:()->Unit={}):HistoryResult {
        val batch=batches.lastOrNull() ?:return HistoryResult(emptySet(),emptySet())
        val inspection=conflicts(
            batch.changes.associate { it.path to it.after },batch.afterPermissions,batch.afterExecutable
        )
        if (inspection.conflicts.isNotEmpty()) {
            return HistoryResult(emptySet(),inspection.conflicts,inspection.warnings)
        }
        val reverse=batch.changes.map { change ->
            PlannedFileChange(change.path,change.after,change.before,change.scope)
        }
        val applied=try {
            engine.apply(
                ValidatedEditBatch(
                    "undo ${batch.summary}",reverse,batch.beforePermissions,batch.afterPermissions,
                    batch.beforeExecutable,batch.afterExecutable
                ),authorizationGuard
            )
        } catch (failure:Exception) {
            if (!failure.isCleanLiveConflict()) throw failure
            val raced=conflicts(
                batch.changes.associate { it.path to it.after },batch.afterPermissions,batch.afterExecutable
            )
            if (raced.conflicts.isNotEmpty()) {
                return HistoryResult(emptySet(),raced.conflicts,raced.warnings)
            }
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
        val inspection=conflicts(expected,expectedPermissions,expectedExecutable)
        if (inspection.conflicts.isNotEmpty()) {
            return HistoryResult(emptySet(),inspection.conflicts,inspection.warnings)
        }
        val reverse=firstTouch.mapNotNull { (path,baseline) ->
            val current=expected.getValue(path)
            if (
                current==baseline && expectedPermissions[path]==firstTouchPermissions[path] &&
                expectedExecutable[path]==firstTouchExecutable[path]
            ) null else PlannedFileChange(path,current,baseline,scopes.getValue(path))
        }
        val applied=if (reverse.isNotEmpty()) try {
            val baselinePermissions=reverse.associate { change->
                change.path to firstTouchPermissions[change.path]
            }
            val currentPermissions=reverse.associate { change->
                change.path to expectedPermissions[change.path]
            }
            val baselineExecutable=reverse.associate { change->
                change.path to firstTouchExecutable[change.path]
            }
            val currentExecutable=reverse.associate { change->
                change.path to expectedExecutable[change.path]
            }
            engine.apply(
                ValidatedEditBatch(
                    "discard Agent edits",reverse,baselinePermissions,currentPermissions,
                    baselineExecutable,currentExecutable
                ),authorizationGuard
            )
        } catch (failure:Exception) {
            if (!failure.isCleanLiveConflict()) throw failure
            val raced=conflicts(expected,expectedPermissions,expectedExecutable)
            if (raced.conflicts.isNotEmpty()) {
                return HistoryResult(emptySet(),raced.conflicts,raced.warnings)
            }
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
        if (
            before==after && firstTouchPermissions[path]==expectedPermissions[path] &&
            firstTouchExecutable[path]==expectedExecutable[path]
        ) null else TextChange(
            path,before.textOrNull(),after.textOrNull(),scopes.getValue(path)==EditScope.PROJECT_LEVEL,
            expectedExecutable.getValue(path)
        )
    }

    private fun conflicts(
        snapshots:Map<String,FileSnapshot>,
        permissions:Map<String,Set<PosixFilePermission>?>,
        executable:Map<String,Boolean?>
    ):PreflightResult {
        val conflicts=linkedSetOf<String>()
        var unavailable=false
        snapshots.forEach { (path,snapshot)->
            when (val observation=current(path)) {
                CurrentObservation.Unavailable -> {
                    conflicts+=path
                    unavailable=true
                }
                is CurrentObservation.Observed -> if (
                    observation.state.snapshot!=snapshot ||
                    permissions.containsKey(path) && permissions[path]!=null &&
                    observation.state.permissions!=permissions[path] ||
                    executable.containsKey(path) && executable[path]!=null &&
                    observation.state.executable!=executable[path]
                ) conflicts+=path
            }
        }
        return PreflightResult(conflicts,if (unavailable) listOf(OBSERVATION_WARNING) else emptyList())
    }

    private fun Exception.isCleanLiveConflict():Boolean=
        this is FileEditApplyException && rollbackFailures.isEmpty() && cleanupFailures.isEmpty() &&
            (originalFailure is EditContentRaceException || originalFailure is EditPermissionRaceException)

    private data class CurrentFileState(
        val snapshot:FileSnapshot,
        val permissions:Set<PosixFilePermission>?,
        val executable:Boolean?
    )

    private sealed interface CurrentObservation {
        data class Observed(val state:CurrentFileState):CurrentObservation
        data object Unavailable:CurrentObservation
    }

    private data class PreflightResult(val conflicts:Set<String>,val warnings:List<String>)

    private fun current(path:String):CurrentObservation=try {
        val absolute=paths.resolve(path).absolute
        beforeObservation(absolute)
        val before=try {
            Files.readAttributes(absolute,BasicFileAttributes::class.java,LinkOption.NOFOLLOW_LINKS)
        } catch (_:NoSuchFileException) {
            return CurrentObservation.Observed(CurrentFileState(FileSnapshot.Missing,null,null))
        }
        require(before.isRegularFile && !before.isSymbolicLink) {
            "edit history path is not a regular file"
        }
        afterObservationAttributes(absolute)
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
        beforePermissionObservation(absolute)
        val permissions=posixPermissionsReader(absolute)
        val executable=permissions?.isExecutable() ?: Files.isExecutable(absolute)
        val after=Files.readAttributes(
            absolute,BasicFileAttributes::class.java,LinkOption.NOFOLLOW_LINKS
        )
        require(after.isRegularFile && !after.isSymbolicLink) {
            "edit history path is not a regular file"
        }
        require(before.fileKey()==null || after.fileKey()==null || before.fileKey()==after.fileKey()) {
            "edit history path changed during inspection"
        }
        CurrentObservation.Observed(
            CurrentFileState(FileSnapshot.Text(content,sha256(bytes)),permissions,executable)
        )
    } catch (_:Exception) {
        CurrentObservation.Unavailable
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

    private fun Set<PosixFilePermission>.isExecutable():Boolean=any { permission->
        permission==PosixFilePermission.OWNER_EXECUTE||
            permission==PosixFilePermission.GROUP_EXECUTE||
            permission==PosixFilePermission.OTHERS_EXECUTE
    }

    private fun sha256(bytes:ByteArray):String=MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private companion object { const val MAX_FILE_BYTES=1_048_576 }
}
