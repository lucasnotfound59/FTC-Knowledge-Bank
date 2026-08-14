package org.ftckb.agent.edit

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest

sealed interface FileSnapshot {
    data object Missing:FileSnapshot
    data class Text(val content:String,val sha256:String):FileSnapshot
}

data class PlannedFileChange(
    val path:String,val before:FileSnapshot,val after:FileSnapshot,val scope:EditScope
) {
    internal var permissionSourcePath:String?=null
        private set

    internal fun withPermissionSource(path:String):PlannedFileChange=also { permissionSourcePath=path }
}

data class ValidatedEditBatch(val summary:String,val changes:List<PlannedFileChange>)

data class AppliedEditBatch(val summary:String,val changes:List<PlannedFileChange>)

class FileEditApplyException(
    val originalFailure:Throwable,
    val rollbackFailures:List<Throwable>,
    val cleanupFailures:List<Throwable> =emptyList()
):IOException(
    when {
        rollbackFailures.isNotEmpty() -> "edit batch failed; rollback also failed"
        cleanupFailures.isNotEmpty() -> "edit batch failed; rollback succeeded but temporary cleanup failed"
        else -> "edit batch failed; rollback succeeded"
    },
    originalFailure
) {
    init {
        rollbackFailures.forEach(::addSuppressed)
        cleanupFailures.forEach(::addSuppressed)
    }
}

class FileEditEngine(
    root:Path,
    private val beforeWrite:(Path,Int)->Unit={ _,_-> }
) {
    private val paths=SafeEditPath(root)

    fun preview(plan:EditPlan):ValidatedEditBatch {
        require(plan.operations.size<=MAX_FILES) { "edit batch contains too many operations" }
        val initial=linkedMapOf<String,FileSnapshot>()
        val current=linkedMapOf<String,FileSnapshot>()
        val scopes=linkedMapOf<String,EditScope>()
        val permissionSources=mutableMapOf<String,String>()
        fun snapshot(path:ResolvedEditPath):FileSnapshot {
            val snapshot=current.getOrPut(path.relative) { readSnapshot(path.absolute) }
            initial.putIfAbsent(path.relative,snapshot)
            scopes.putIfAbsent(path.relative,path.scope)
            require(initial.size<=MAX_FILES) { "edit batch contains too many files" }
            return snapshot
        }
        plan.operations.forEach { operation ->
            when (operation) {
                is CreateText -> {
                    require(operation.expectedAbsent) { "create destination must be expected absent: ${operation.path}" }
                    val resolved=paths.resolve(operation.path)
                    require(snapshot(resolved)==FileSnapshot.Missing) { "create destination already exists: ${resolved.relative}" }
                    current[resolved.relative]=textSnapshot(operation.content)
                }
                is ReplaceText -> {
                    val resolved=paths.resolve(operation.path)
                    val text=requireText(snapshot(resolved),resolved.relative)
                    require(text.sha256==operation.expectedSha256) { "stale file hash: ${resolved.relative}" }
                    require(operation.oldText.isNotEmpty()) { "replacement text must not be empty: ${resolved.relative}" }
                    val first=text.content.indexOf(operation.oldText)
                    require(first>=0 && text.content.indexOf(operation.oldText,first+1)<0) {
                        "replacement text must occur exactly once: ${resolved.relative}"
                    }
                    val content=text.content.replaceRange(first,first+operation.oldText.length,operation.newText)
                    current[resolved.relative]=textSnapshot(content)
                }
                is DeleteText -> {
                    val resolved=paths.resolve(operation.path)
                    val text=requireText(snapshot(resolved),resolved.relative)
                    require(text.sha256==operation.expectedSha256) { "stale file hash: ${resolved.relative}" }
                    current[resolved.relative]=FileSnapshot.Missing
                }
                is MoveText -> {
                    require(operation.destinationExpectedAbsent) {
                        "move destination must be expected absent: ${operation.destinationPath}"
                    }
                    val source=paths.resolve(operation.sourcePath)
                    val destination=paths.resolve(operation.destinationPath)
                    require(source.relative!=destination.relative) { "move source and destination must differ" }
                    val sourceText=requireText(snapshot(source),source.relative)
                    require(sourceText.sha256==operation.expectedSha256) { "stale file hash: ${source.relative}" }
                    require(snapshot(destination)==FileSnapshot.Missing) {
                        "move destination already exists: ${destination.relative}"
                    }
                    current[source.relative]=FileSnapshot.Missing
                    current[destination.relative]=sourceText
                    permissionSources[destination.relative]=source.relative
                }
            }
        }
        val changes=current.mapNotNull { (path,after) ->
            val before=initial.getValue(path)
            if (before==after) null else PlannedFileChange(path,before,after,scopes.getValue(path)).also { change ->
                permissionSources[path]?.let(change::withPermissionSource)
            }
        }
        validateLimits(changes)
        return ValidatedEditBatch(plan.summary,changes)
    }

    fun apply(batch:ValidatedEditBatch):AppliedEditBatch {
        require(batch.changes.map { it.path }.toSet().size==batch.changes.size) { "edit batch contains duplicate files" }
        require(batch.changes.size<=MAX_FILES) { "edit batch contains too many files" }
        val resolved=batch.changes.associateWith { change -> paths.resolve(change.path) }
        resolved.forEach { (change,path) ->
            require(path.relative==change.path) { "edit path is not canonical: ${change.path}" }
            require(path.scope==change.scope) { "edit scope changed: ${change.path}" }
            validateSnapshot(change.before,change.path)
            validateSnapshot(change.after,change.path)
            require(readSnapshot(path.absolute)==change.before) { "edit precondition changed: ${change.path}" }
        }
        validateLimits(batch.changes)
        val originalPermissions=resolved.mapValues { (_,path) -> permissions(path.absolute) }
        val changesByPath=batch.changes.associateBy(PlannedFileChange::path)
        val permissionSourcePaths=batch.changes.mapNotNull(PlannedFileChange::permissionSourcePath)
        require(permissionSourcePaths.toSet().size==permissionSourcePaths.size) { "edit batch reuses a permission source" }
        val writePermissions=batch.changes.associateWith { change ->
            originalPermissions[change] ?: change.permissionSourcePath?.let { sourcePath ->
                val source=changesByPath[sourcePath]
                require(
                    change.before==FileSnapshot.Missing && change.after is FileSnapshot.Text &&
                        source!=null && source.after==FileSnapshot.Missing && source.before==change.after
                ) { "edit permission provenance is invalid: ${change.path}" }
                originalPermissions[source]
            }
        }
        val prepared=mutableMapOf<PlannedFileChange,Path>()
        val attempted=mutableListOf<PlannedFileChange>()
        try {
            batch.changes.forEach { change ->
                val after=change.after
                if (after is FileSnapshot.Text) {
                    val path=resolved.getValue(change).absolute
                    prepared[change]=prepareSibling(path,after,writePermissions[change])
                }
            }
            batch.changes.forEachIndexed { index,change ->
                val expectedPath=resolved.getValue(change)
                val path=paths.resolve(change.path)
                require(path==expectedPath) { "edit path changed: ${change.path}" }
                require(readSnapshot(path.absolute)==change.before) { "edit precondition changed: ${change.path}" }
                beforeWrite(path.absolute,index+1)
                attempted+=change
                when (change.after) {
                    FileSnapshot.Missing -> Files.delete(path.absolute)
                    is FileSnapshot.Text -> replace(prepared.getValue(change),path.absolute)
                }
                prepared.remove(change)
            }
        } catch (failure:Throwable) {
            val rollbackFailures=rollback(attempted,resolved,originalPermissions)
            val cleanupFailures=mutableListOf<Throwable>()
            cleanup(prepared.values,cleanupFailures)
            throw FileEditApplyException(failure,rollbackFailures,cleanupFailures)
        }
        val cleanupFailures=mutableListOf<Throwable>()
        cleanup(prepared.values,cleanupFailures)
        if (cleanupFailures.isNotEmpty()) {
            throw FileEditApplyException(IOException("temporary-file cleanup failed"),emptyList(),cleanupFailures)
        }
        return AppliedEditBatch(batch.summary,batch.changes)
    }

    private fun rollback(
        attempted:List<PlannedFileChange>,
        resolved:Map<PlannedFileChange,ResolvedEditPath>,
        permissions:Map<PlannedFileChange,Set<PosixFilePermission>?>
    ):MutableList<Throwable> {
        val failures=mutableListOf<Throwable>()
        attempted.asReversed().forEach { change ->
            try {
                val expectedPath=resolved.getValue(change)
                val path=paths.resolve(change.path)
                require(path==expectedPath) { "edit path changed during rollback: ${change.path}" }
                when (val before=change.before) {
                    FileSnapshot.Missing -> Files.deleteIfExists(path.absolute)
                    is FileSnapshot.Text -> {
                        val temporary=prepareSibling(path.absolute,before,permissions[change])
                        try {
                            replace(temporary,path.absolute)
                        } finally {
                            Files.deleteIfExists(temporary)
                        }
                    }
                }
            } catch (failure:Throwable) {
                failures+=failure
            }
        }
        return failures
    }

    private fun prepareSibling(
        target:Path,
        snapshot:FileSnapshot.Text,
        permissions:Set<PosixFilePermission>?
    ):Path {
        val parent=target.parent
        require(Files.isDirectory(parent,LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(parent)) {
            "edit parent must be an existing directory"
        }
        val temporary=Files.createTempFile(parent,".${target.fileName}.ftckb-",".tmp")
        try {
            Files.newByteChannel(
                temporary,setOf(StandardOpenOption.WRITE,StandardOpenOption.TRUNCATE_EXISTING,LinkOption.NOFOLLOW_LINKS)
            ).use { channel ->
                val bytes=ByteBuffer.wrap(encodeUtf8(snapshot.content))
                while (bytes.hasRemaining()) channel.write(bytes)
            }
            if (permissions!=null) Files.setAttribute(
                temporary,"posix:permissions",permissions,LinkOption.NOFOLLOW_LINKS
            )
            return temporary
        } catch (failure:Throwable) {
            try {
                Files.deleteIfExists(temporary)
            } catch (cleanupFailure:Throwable) {
                failure.addSuppressed(cleanupFailure)
            }
            throw failure
        }
    }

    private fun replace(source:Path,target:Path) {
        try {
            Files.move(source,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
        } catch (_:AtomicMoveNotSupportedException) {
            Files.move(source,target,StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun permissions(path:Path):Set<PosixFilePermission>?=try {
        if (Files.exists(path,LinkOption.NOFOLLOW_LINKS)) Files.getPosixFilePermissions(path,LinkOption.NOFOLLOW_LINKS) else null
    } catch (_:UnsupportedOperationException) {
        null
    }

    private fun cleanup(paths:Collection<Path>,failures:MutableList<Throwable>) {
        paths.forEach { path ->
            try {
                Files.deleteIfExists(path)
            } catch (failure:Throwable) {
                failures+=failure
            }
        }
    }

    private fun validateLimits(changes:List<PlannedFileChange>) {
        require(changes.size<=MAX_FILES) { "edit batch contains too many files" }
        var total=0L
        changes.forEach { change ->
            val after=change.after
            if (after is FileSnapshot.Text) {
                val size=encodeUtf8(after.content).size.toLong()
                require(size<=MAX_FILE_BYTES) { "edit result exceeds per-file size limit: ${change.path}" }
                total+=size
                require(total<=MAX_TOTAL_BYTES) { "edit batch exceeds aggregate size limit" }
            }
        }
    }

    private fun validateSnapshot(snapshot:FileSnapshot,path:String) {
        if (snapshot is FileSnapshot.Text) {
            require('\u0000' !in snapshot.content) { "edit snapshot contains binary NUL data: $path" }
            val bytes=encodeUtf8(snapshot.content)
            require(bytes.size<=MAX_FILE_BYTES) { "edit snapshot exceeds per-file size limit: $path" }
            require(snapshot.sha256==sha256(bytes)) { "edit snapshot hash is invalid: $path" }
        }
    }

    private fun readSnapshot(path:Path):FileSnapshot {
        if (!Files.exists(path,LinkOption.NOFOLLOW_LINKS)) return FileSnapshot.Missing
        require(Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)) { "edit path must identify a regular file" }
        val bytes=readBounded(path)
        require(bytes.none { it==0.toByte() }) { "binary files cannot be edited" }
        val content=decodeUtf8(bytes)
        return FileSnapshot.Text(content,sha256(bytes))
    }

    private fun readBounded(path:Path):ByteArray {
        Files.newByteChannel(path,setOf(StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)).use { channel ->
            val output=ByteArrayOutputStream()
            val buffer=ByteBuffer.allocate(8_192)
            var total=0L
            while (true) {
                buffer.clear()
                val count=channel.read(buffer)
                if (count<0) break
                if (count==0) continue
                total+=count
                require(total<=MAX_FILE_BYTES) { "edit source exceeds per-file size limit" }
                output.write(buffer.array(),0,count)
            }
            return output.toByteArray()
        }
    }

    private fun textSnapshot(content:String):FileSnapshot.Text {
        require('\u0000' !in content) { "edit result contains binary NUL data" }
        val bytes=encodeUtf8(content)
        require(bytes.size<=MAX_FILE_BYTES) { "edit result exceeds per-file size limit" }
        return FileSnapshot.Text(content,sha256(bytes))
    }

    private fun requireText(snapshot:FileSnapshot,path:String):FileSnapshot.Text {
        require(snapshot is FileSnapshot.Text) { "edit source does not exist: $path" }
        return snapshot
    }

    private fun encodeUtf8(text:String):ByteArray=try {
        val buffer=StandardCharsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(java.nio.CharBuffer.wrap(text))
        ByteArray(buffer.remaining()).also(buffer::get)
    } catch (failure:CharacterCodingException) {
        throw IllegalArgumentException("edit result is not valid UTF-8 text",failure)
    }

    private fun decodeUtf8(bytes:ByteArray):String=try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (failure:CharacterCodingException) {
        throw IllegalArgumentException("binary files cannot be edited",failure)
    }

    private fun sha256(bytes:ByteArray):String=MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    companion object {
        private const val MAX_FILES=24
        private const val MAX_FILE_BYTES=1_048_576L
        private const val MAX_TOTAL_BYTES=4_194_304L
    }
}
