package org.ftckb.agent.edit

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.util.Collections
import java.util.HashSet
import java.util.UUID

sealed interface FileSnapshot {
    data object Missing:FileSnapshot
    data class Text(val content:String,val sha256:String):FileSnapshot
}

@ConsistentCopyVisibility
data class PlannedFileChange internal constructor(
    val path:String,
    val before:FileSnapshot,
    val after:FileSnapshot,
    val scope:EditScope,
    internal val permissionSourcePath:String?
) {
    constructor(path:String,before:FileSnapshot,after:FileSnapshot,scope:EditScope):
        this(path,before,after,scope,null)
}

data class ValidatedEditBatch(val summary:String,val changes:List<PlannedFileChange>)

data class AppliedEditBatch(val summary:String,val changes:List<PlannedFileChange>)

internal class EditContentRaceException(val path:String):IOException("edit content changed during apply: $path")

internal fun readSnapshotOrMissing(path:Path,readExisting:(Path)->FileSnapshot):FileSnapshot=try {
    readExisting(path)
} catch (_:NoSuchFileException) {
    FileSnapshot.Missing
}

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
    private val beforeMutation:(Path,Int)->Unit={ _,_-> },
    private val beforeWrite:(Path,Int)->Unit={ _,_-> }
) {
    private val root=root.toRealPath()
    private val paths=SafeEditPath(this.root)

    private data class VirtualFile(val snapshot:FileSnapshot,val permissionSourcePath:String?)

    fun preview(plan:EditPlan):ValidatedEditBatch {
        require(plan.operations.size<=MAX_FILES) { "edit batch contains too many operations" }
        val initial=linkedMapOf<String,VirtualFile>()
        val current=linkedMapOf<String,VirtualFile>()
        val scopes=linkedMapOf<String,EditScope>()
        fun state(path:ResolvedEditPath):VirtualFile {
            val state=current.getOrPut(path.relative) {
                val snapshot=readSnapshot(path.absolute)
                VirtualFile(snapshot,if (snapshot is FileSnapshot.Text) path.relative else null)
            }
            initial.putIfAbsent(path.relative,state)
            scopes.putIfAbsent(path.relative,path.scope)
            require(initial.size<=MAX_FILES) { "edit batch contains too many files" }
            return state
        }
        plan.operations.forEach { operation ->
            when (operation) {
                is CreateText -> {
                    require(operation.expectedAbsent) { "create destination must be expected absent: ${operation.path}" }
                    val resolved=paths.resolve(operation.path)
                    require(state(resolved).snapshot==FileSnapshot.Missing) { "create destination already exists: ${resolved.relative}" }
                    current[resolved.relative]=VirtualFile(textSnapshot(operation.content),null)
                }
                is ReplaceText -> {
                    val resolved=paths.resolve(operation.path)
                    val currentState=state(resolved)
                    val text=requireText(currentState.snapshot,resolved.relative)
                    require(text.sha256==operation.expectedSha256) { "stale file hash: ${resolved.relative}" }
                    require(operation.oldText.isNotEmpty()) { "replacement text must not be empty: ${resolved.relative}" }
                    val first=text.content.indexOf(operation.oldText)
                    require(first>=0 && text.content.indexOf(operation.oldText,first+1)<0) {
                        "replacement text must occur exactly once: ${resolved.relative}"
                    }
                    val content=text.content.replaceRange(first,first+operation.oldText.length,operation.newText)
                    current[resolved.relative]=currentState.copy(snapshot=textSnapshot(content))
                }
                is DeleteText -> {
                    val resolved=paths.resolve(operation.path)
                    val text=requireText(state(resolved).snapshot,resolved.relative)
                    require(text.sha256==operation.expectedSha256) { "stale file hash: ${resolved.relative}" }
                    current[resolved.relative]=VirtualFile(FileSnapshot.Missing,null)
                }
                is MoveText -> {
                    require(operation.destinationExpectedAbsent) {
                        "move destination must be expected absent: ${operation.destinationPath}"
                    }
                    val source=paths.resolve(operation.sourcePath)
                    val destination=paths.resolve(operation.destinationPath)
                    require(source.relative!=destination.relative) { "move source and destination must differ" }
                    val sourceState=state(source)
                    val sourceText=requireText(sourceState.snapshot,source.relative)
                    require(sourceText.sha256==operation.expectedSha256) { "stale file hash: ${source.relative}" }
                    require(state(destination).snapshot==FileSnapshot.Missing) {
                        "move destination already exists: ${destination.relative}"
                    }
                    current[source.relative]=VirtualFile(FileSnapshot.Missing,null)
                    current[destination.relative]=sourceState
                }
            }
        }
        val changes=current.mapNotNull { (path,afterState) ->
            val before=initial.getValue(path).snapshot
            val after=afterState.snapshot
            if (before==after) null else PlannedFileChange(
                path,before,after,scopes.getValue(path),
                if (after is FileSnapshot.Text) afterState.permissionSourcePath else null
            )
        }
        validateLimits(changes)
        return ValidatedEditBatch(plan.summary,Collections.unmodifiableList(java.util.ArrayList(changes)))
    }

    fun apply(batch:ValidatedEditBatch,authorizationGuard:()->Unit={}):AppliedEditBatch {
        val changes=Collections.unmodifiableList(java.util.ArrayList(batch.changes))
        require(changes.map { it.path }.toSet().size==changes.size) { "edit batch contains duplicate files" }
        require(changes.size<=MAX_FILES) { "edit batch contains too many files" }
        val resolved=changes.associateWith { change -> paths.resolve(change.path) }
        resolved.forEach { (change,path) ->
            require(path.relative==change.path) { "edit path is not canonical: ${change.path}" }
            require(path.scope==change.scope) { "edit scope changed: ${change.path}" }
            validateSnapshot(change.before,change.path)
            validateSnapshot(change.after,change.path)
        }
        validateLimits(changes)
        val verified=resolved.mapValues { (_,path) -> VerifiedEditPath(path) }
        try {
            verified.forEach { (change,path) ->
                path.verifyBoundary()
                path.requireSnapshot(change.before)
            }
        } catch (failure:EditContentRaceException) {
            throw FileEditApplyException(failure,emptyList())
        }
        val originalPermissions=verified.mapValues { (change,path) ->
            if (change.before is FileSnapshot.Text) path.permissions() else null
        }
        val writePermissions=permissionMap(changes,originalPermissions)
        val applied=mutableListOf<PlannedFileChange>()
        val temporaryFiles=mutableMapOf<PlannedFileChange,Path>()
        try {
            changes.forEachIndexed { index,change ->
                val path=verified.getValue(change)
                path.verifyBoundary()
                path.requireSnapshot(change.before)
                beforeWrite(path.absolute,index+1)
                path.verifyBoundary()
                path.requireSnapshot(change.before)
                val after=change.after
                if (after is FileSnapshot.Text) {
                    temporaryFiles[change]=path.prepare(after,writePermissions[change])
                }
                path.verifyBoundary()
                path.requireSnapshot(change.before)
                beforeMutation(path.absolute,index+1)
                path.verifyBoundary()
                path.requireSnapshot(change.before)
                authorizationGuard()
                when (after) {
                    FileSnapshot.Missing -> Files.delete(path.absolute)
                    is FileSnapshot.Text -> {
                        val temporary=temporaryFiles.getValue(change)
                        if (change.before==FileSnapshot.Missing) moveNoReplace(temporary,path.absolute)
                        else replace(temporary,path.absolute)
                        temporaryFiles.remove(change)
                    }
                }
                applied+=change
                path.verifyBoundary()
                path.requireSnapshot(after)
            }
        } catch (failure:Throwable) {
            val rollbackFailures=rollback(applied,verified,originalPermissions)
            val cleanupFailures=cleanup(temporaryFiles,verified)
            throw FileEditApplyException(failure,rollbackFailures,cleanupFailures)
        }
        return AppliedEditBatch(batch.summary,changes)
    }

    private fun permissionMap(
        changes:List<PlannedFileChange>,
        originalPermissions:Map<PlannedFileChange,Set<PosixFilePermission>?>
    ):Map<PlannedFileChange,Set<PosixFilePermission>?> {
        val changesByPath=changes.associateBy(PlannedFileChange::path)
        val permissionOrigins=changes.associateWith { change ->
            change.permissionSourcePath ?: if (
                change.before is FileSnapshot.Text && change.after is FileSnapshot.Text
            ) change.path else null
        }
        val permissionSourcePaths=permissionOrigins.values.filterNotNull()
        require(permissionSourcePaths.toSet().size==permissionSourcePaths.size) { "edit batch reuses a permission source" }
        return changes.associateWith { change ->
            permissionOrigins.getValue(change)?.let { sourcePath ->
                if (sourcePath==change.path) {
                    require(change.before is FileSnapshot.Text) { "edit permission provenance is invalid: ${change.path}" }
                    return@let originalPermissions[change]
                }
                val source=changesByPath[sourcePath]
                require(
                    change.before==FileSnapshot.Missing && change.after is FileSnapshot.Text &&
                        source!=null && source.before is FileSnapshot.Text
                ) { "edit permission provenance is invalid: ${change.path}" }
                originalPermissions[source]
            }
        }
    }

    private fun rollback(
        applied:List<PlannedFileChange>,
        verified:Map<PlannedFileChange,VerifiedEditPath>,
        permissions:Map<PlannedFileChange,Set<PosixFilePermission>?>
    ):MutableList<Throwable> {
        val failures=mutableListOf<Throwable>()
        applied.asReversed().forEach { change ->
            try {
                verified.getValue(change).rollback(change,permissions[change])
            } catch (failure:Throwable) {
                failures+=failure
            }
        }
        return failures
    }

    private fun cleanup(
        temporaryFiles:Map<PlannedFileChange,Path>,
        verified:Map<PlannedFileChange,VerifiedEditPath>
    ):MutableList<Throwable> {
        val failures=mutableListOf<Throwable>()
        temporaryFiles.forEach { (change,temporary) ->
            try {
                verified.getValue(change).cleanup(temporary)
            } catch (failure:Throwable) {
                failures+=failure
            }
        }
        return failures
    }

    private fun VerifiedEditPath.requireSnapshot(expected:FileSnapshot) {
        if (snapshot()!=expected) throw EditContentRaceException(resolved.relative)
    }

    private data class DirectoryIdentity(val path:Path,val fileKey:Any)

    /*
     * Standard Java has no descriptor-relative mutation API on every supported provider.
     * This portable boundary detects ordinary IDE/concurrent ancestor changes before and
     * after each operation. It does not claim to defeat a malicious same-account process
     * swapping a directory in the microseconds between the final check and the path call.
     * Rollback relocation is limited to captured file keys under the verified repository
     * parent and then under each captured repository ancestor.
     */
    private inner class VerifiedEditPath(val resolved:ResolvedEditPath) {
        val absolute:Path=resolved.absolute
        private val repositoryParent=captureDirectory(requireNotNull(root.parent))
        private val directories=captureDirectories()

        fun verifyBoundary() {
            directories.forEach { identity ->
                val attributes=Files.readAttributes(
                    identity.path,BasicFileAttributes::class.java,LinkOption.NOFOLLOW_LINKS
                )
                require(
                    attributes.isDirectory && !attributes.isSymbolicLink &&
                        attributes.fileKey()==identity.fileKey && identity.path.toRealPath()==identity.path
                ) { "edit directory changed during apply: ${resolved.relative}" }
            }
        }

        fun snapshot():FileSnapshot=readSnapshot(absolute)

        fun permissions():Set<PosixFilePermission>?=try {
            Collections.unmodifiableSet(HashSet(Files.getPosixFilePermissions(absolute,LinkOption.NOFOLLOW_LINKS)))
        } catch (_:UnsupportedOperationException) {
            null
        }

        fun prepare(snapshot:FileSnapshot.Text,permissions:Set<PosixFilePermission>?):Path=
            prepareAt(absolute,snapshot,permissions,::verifyBoundary)

        fun rollback(change:PlannedFileChange,permissions:Set<PosixFilePermission>?) {
            val target=locateCapturedTarget()
            require(readSnapshot(target)==change.after) { "edit result changed before rollback: ${change.path}" }
            val verify={ require(locateCapturedTarget()==target) { "edit directory changed during rollback: ${change.path}" } }
            when (val before=change.before) {
                FileSnapshot.Missing -> Files.delete(target)
                is FileSnapshot.Text -> {
                    val temporary=prepareAt(target,before,permissions,verify)
                    try {
                        replace(temporary,target)
                    } finally {
                        Files.deleteIfExists(temporary)
                    }
                }
            }
            verify()
            require(readSnapshot(target)==change.before) { "edit rollback changed: ${change.path}" }
        }

        fun cleanup(temporary:Path) {
            val parent=locateCapturedTarget().parent
            val relocated=parent.resolve(temporary.fileName)
            val attributes=try {
                Files.readAttributes(relocated,BasicFileAttributes::class.java,LinkOption.NOFOLLOW_LINKS)
            } catch (_:NoSuchFileException) {
                null
            }
            if (attributes!=null) {
                require(
                    attributes.isRegularFile && !attributes.isSymbolicLink
                ) { "edit temporary file changed during cleanup" }
                Files.delete(relocated)
            }
            require(locateCapturedTarget().parent==parent) { "edit directory changed during cleanup" }
        }

        private fun prepareAt(
            target:Path,
            snapshot:FileSnapshot.Text,
            permissions:Set<PosixFilePermission>?,
            verify:()->Unit
        ):Path {
            repeat(TEMP_NAME_ATTEMPTS) {
                verify()
                val temporary=target.parent.resolve(
                    ".${target.fileName}.ftckb-write-${UUID.randomUUID()}.tmp"
                )
                var created=false
                try {
                    Files.newByteChannel(
                        temporary,setOf(
                            StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS
                        )
                    ).use { channel ->
                        created=true
                        val bytes=ByteBuffer.wrap(encodeUtf8(snapshot.content))
                        while (bytes.hasRemaining()) channel.write(bytes)
                    }
                    if (permissions!=null) Files.setAttribute(
                        temporary,"posix:permissions",permissions,LinkOption.NOFOLLOW_LINKS
                    )
                    verify()
                    return temporary
                } catch (_:FileAlreadyExistsException) {
                    // Try a new unguessable sibling name.
                } catch (failure:Throwable) {
                    if (created) {
                        try {
                            verify()
                            Files.deleteIfExists(temporary)
                        } catch (cleanupFailure:Throwable) {
                            failure.addSuppressed(cleanupFailure)
                        }
                    }
                    throw failure
                }
            }
            throw IOException("could not allocate a safe temporary sibling")
        }

        private fun locateCapturedTarget():Path {
            require(matches(repositoryParent.path,repositoryParent)) { "edit repository parent changed during rollback" }
            var current=directories.first().path
            if (!matches(current,directories.first())) {
                current=findCapturedChild(repositoryParent.path,directories.first())
            }
            directories.drop(1).forEach { identity ->
                val expected=current.resolve(identity.path.fileName)
                current=if (matches(expected,identity)) expected else findCapturedChild(current,identity)
            }
            return current.resolve(absolute.fileName)
        }

        private fun findCapturedChild(parent:Path,identity:DirectoryIdentity):Path {
            val matches=Files.newDirectoryStream(parent).use { entries ->
                entries.filter { matches(it,identity) }.toList()
            }
            require(matches.size==1) { "edit directory cannot be located safely during rollback" }
            return matches.single()
        }

        private fun matches(path:Path,identity:DirectoryIdentity):Boolean=try {
            val attributes=Files.readAttributes(path,BasicFileAttributes::class.java,LinkOption.NOFOLLOW_LINKS)
            attributes.isDirectory && !attributes.isSymbolicLink && attributes.fileKey()==identity.fileKey &&
                path.toRealPath()==path
        } catch (_:IOException) {
            false
        }

        private fun captureDirectories():List<DirectoryIdentity> {
            val pathsToCapture=mutableListOf(root)
            var current=root
            Path.of(resolved.relative).parent?.forEach { component ->
                current=current.resolve(component)
                pathsToCapture.add(current)
            }
            val identities=pathsToCapture.map(::captureDirectory)
            return Collections.unmodifiableList(identities)
        }

        private fun captureDirectory(directory:Path):DirectoryIdentity {
            val attributes=Files.readAttributes(
                directory,BasicFileAttributes::class.java,LinkOption.NOFOLLOW_LINKS
            )
            require(
                attributes.isDirectory && !attributes.isSymbolicLink && directory.toRealPath()==directory
            ) { "edit path contains an unsafe directory" }
            return DirectoryIdentity(
                directory,requireNotNull(attributes.fileKey()) { "edit directory identity is unavailable" }
            )
        }
    }

    private fun replace(source:Path,target:Path) {
        try {
            Files.move(source,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
        } catch (_:AtomicMoveNotSupportedException) {
            Files.move(source,target,StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun moveNoReplace(source:Path,target:Path) {
        Files.move(source,target)
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

    private fun readSnapshot(path:Path):FileSnapshot=readSnapshotOrMissing(path) { existing ->
        val attributes=Files.readAttributes(
            existing,BasicFileAttributes::class.java,LinkOption.NOFOLLOW_LINKS
        )
        require(attributes.isRegularFile && !attributes.isSymbolicLink) {
            "edit path must identify a regular file"
        }
        snapshot(readBounded(existing))
    }

    private fun snapshot(bytes:ByteArray):FileSnapshot.Text {
        require(bytes.none { it==0.toByte() }) { "binary files cannot be edited" }
        val content=decodeUtf8(bytes)
        return FileSnapshot.Text(content,sha256(bytes))
    }

    private fun readBounded(path:Path):ByteArray=
        Files.newByteChannel(path,setOf(StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)).use(::readBounded)

    private fun readBounded(channel:SeekableByteChannel):ByteArray {
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
        private const val TEMP_NAME_ATTEMPTS=8
    }
}
