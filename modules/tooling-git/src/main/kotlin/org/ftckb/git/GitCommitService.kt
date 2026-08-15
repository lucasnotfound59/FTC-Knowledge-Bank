package org.ftckb.git

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.charset.StandardCharsets
import java.nio.file.attribute.BasicFileAttributes
import java.util.Locale
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.dircache.DirCache
import org.eclipse.jgit.dircache.DirCacheEditor
import org.eclipse.jgit.dircache.DirCacheEntry
import org.eclipse.jgit.lib.CommitBuilder
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.RefUpdate
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.lib.RepositoryState
import org.eclipse.jgit.revwalk.RevWalk

data class CommitRequest(
    val repositoryRoot:Path,val paths:Set<String>,val baselineDirtyPaths:Set<String>,val message:String,
    val authorizedBranch:String,val expectedContents:Map<String,String?>
)

object GitCommitService {
    fun commit(request:CommitRequest):String=commit(request) { }

    internal fun commit(request:CommitRequest,afterIndexVerified:(Repository)->Unit):String {
        require(request.message.isNotBlank()) { "commit message must not be blank" }
        require(request.paths.isNotEmpty()) { "commit paths must not be empty" }
        require(request.expectedContents.keys==request.paths) { "commit expectations must match exact paths" }
        val overlap=request.paths.intersect(request.baselineDirtyPaths).toSortedSet()
        require(overlap.isEmpty()) {
            "cannot commit paths that were dirty before Agent edits: ${overlap.joinToString(", ")}"
        }
        openSelectedRepository(request.repositoryRoot).use { repository->
            val fullBranch=repository.fullBranch
            require(fullBranch!=null && fullBranch.startsWith(Constants.R_HEADS)) {
                "cannot commit from detached HEAD"
            }
            require(fullBranch==Constants.R_HEADS+request.authorizedBranch) {
                "cannot commit outside the authorized branch"
            }
            require(repository.repositoryState==RepositoryState.SAFE) {
                "cannot commit while a repository operation is in progress"
            }
            val root=repository.workTree.toPath().toRealPath()
            val paths=request.paths.toSortedSet()
            val resolved=paths.map { path->validatePath(root,path) }
            require(resolved.toSet().size==resolved.size) { "commit paths contain aliases" }
            requireExpectedWorktree(root,request.expectedContents)
            Git.wrap(repository).use { git->
                val dirty=git.status().call().let { status->
                    status.added+status.changed+status.modified+status.removed+status.missing+
                        status.untracked+status.conflicting
                }
                require(paths.all { it in dirty }) { "every commit path must contain a change" }
                val indexPath=repository.indexFile.toPath()
                val indexBefore=if (Files.exists(indexPath,LinkOption.NOFOLLOW_LINKS)) {
                    Files.readAllBytes(indexPath)
                } else null
                val headBefore=repository.resolve(Constants.HEAD)?.name
                try {
                    paths.forEach { path->
                        val absolute=root.resolve(path)
                        if (Files.exists(absolute,LinkOption.NOFOLLOW_LINKS)) {
                            git.add().addFilepattern(path).call()
                        } else {
                            git.add().setUpdate(true).addFilepattern(path).call()
                        }
                    }
                    val verified=requireExpectedIndex(repository,request.expectedContents)
                    afterIndexVerified(repository)
                    return createCommit(repository,fullBranch,verified,request.message)
                } catch (failure:Throwable) {
                    if (repository.resolve(Constants.HEAD)?.name==headBefore) {
                        if (indexBefore==null) Files.deleteIfExists(indexPath)
                        else Files.write(
                            indexPath,indexBefore,
                            StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE
                        )
                    }
                    throw failure
                }
            }
        }
    }

    private fun requireExpectedWorktree(root:Path,expected:Map<String,String?>) {
        expected.forEach { (path,content)->
            val absolute=root.resolve(path)
            val actual=readStrictTextOrMissing(absolute)
            require(actual==content) { "commit path changed after Agent diff: $path" }
        }
    }

    private fun readStrictTextOrMissing(path:Path):String?=try {
        val attributes=Files.readAttributes(
            path,BasicFileAttributes::class.java,LinkOption.NOFOLLOW_LINKS
        )
        require(attributes.isRegularFile && !attributes.isSymbolicLink) {
            "commit path must identify a regular file"
        }
        val bytes=Files.newByteChannel(
            path,setOf(StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)
        ).use { channel->
            val output=ByteArrayOutputStream()
            val buffer=ByteBuffer.allocate(8_192)
            var total=0
            while (true) {
                buffer.clear()
                val count=channel.read(buffer)
                if (count<0) break
                if (count==0) continue
                total+=count
                require(total<=MAX_TEXT_BYTES) { "commit path exceeds text size limit" }
                output.write(buffer.array(),0,count)
            }
            output.toByteArray()
        }
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_:NoSuchFileException) {
        null
    }

    private data class VerifiedCommitEntry(val objectId:ObjectId,val fileMode:FileMode)

    private fun requireExpectedIndex(
        repository:Repository,expected:Map<String,String?>
    ):Map<String,VerifiedCommitEntry?> {
        val staged=DirCache.read(repository)
        return expected.mapValues { (path,content)->
            val entry=staged.getEntry(path)
            if (content==null) {
                require(entry==null) { "commit path changed while staging: $path" }
                null
            } else {
                require(entry!=null && entry.stage==DirCacheEntry.STAGE_0) {
                    "commit path changed while staging: $path"
                }
                val bytes=repository.open(entry.objectId).bytes
                require(bytes.contentEquals(content.toByteArray(StandardCharsets.UTF_8))) {
                    "commit path changed while staging: $path"
                }
                VerifiedCommitEntry(entry.objectId.copy(),entry.fileMode)
            }
        }
    }

    private fun createCommit(
        repository:Repository,branch:String,verified:Map<String,VerifiedCommitEntry?>,message:String
    ):String {
        val head=repository.resolve(Constants.HEAD)
        val commitIndex=repository.newObjectReader().use { reader->
            if (head==null) DirCache.newInCore()
            else RevWalk(repository).use { walk->DirCache.read(reader,walk.parseCommit(head).tree) }
        }
        val editor=commitIndex.editor()
        verified.forEach { (path,verifiedEntry)->
            if (verifiedEntry==null) editor.add(DirCacheEditor.DeletePath(path))
            else {
                editor.add(object:DirCacheEditor.PathEdit(path) {
                    override fun apply(entry:DirCacheEntry) {
                        entry.setObjectId(verifiedEntry.objectId)
                        entry.fileMode=verifiedEntry.fileMode
                        entry.stage=DirCacheEntry.STAGE_0
                    }
                })
            }
        }
        editor.finish()
        val commitId=repository.newObjectInserter().use { inserter->
            val treeId=commitIndex.writeTree(inserter)
            val identity=PersonIdent(repository)
            val builder=CommitBuilder().apply {
                setTreeId(treeId)
                if (head!=null) setParentId(head)
                author=identity
                committer=identity
                setMessage(message)
            }
            val id=inserter.insert(builder)
            inserter.flush()
            id
        }
        val update=repository.updateRef(branch).apply {
            require(repository.fullBranch==branch) { "authorized branch changed before commit" }
            setExpectedOldObjectId(head?:ObjectId.zeroId())
            setNewObjectId(commitId)
            refLogIdent=PersonIdent(repository)
            setRefLogMessage("commit: ${message.lineSequence().first()}",false)
        }
        val result=update.update()
        require(result==RefUpdate.Result.NEW||result==RefUpdate.Result.FAST_FORWARD) {
            "commit ref update failed: $result"
        }
        return commitId.name
    }

    private fun validatePath(root:Path,value:String):Path {
        require(value.isNotBlank() && value.length<=MAX_PATH_LENGTH) { "commit path is empty or too long" }
        require('\u0000' !in value && '\\' !in value) { "commit path contains unsafe syntax" }
        require(!value.startsWith('/') && !WINDOWS_ABSOLUTE.containsMatchIn(value)) { "commit path must be relative" }
        val components=value.split('/')
        require(components.none { it.isEmpty()||it=="."||it==".." }) { "commit path contains unsafe syntax" }
        require(components.none { it.lowercase(Locale.ROOT) in PROTECTED_DIRECTORIES }) { "commit path is protected" }
        val basename=components.last().lowercase(Locale.ROOT)
        val extension=basename.substringAfterLast('.',"")
        require(!basename.startsWith(".env") && basename!="local.properties") { "commit path is protected" }
        require(extension in TEXT_EXTENSIONS && extension !in PROTECTED_EXTENSIONS) {
            "commit path must identify a safe text file"
        }
        val absolute=root.resolve(Path.of(value)).normalize()
        require(absolute.startsWith(root)) { "commit path escapes the repository" }
        var current=root
        components.forEach { component->
            current=current.resolve(component)
            require(!Files.isSymbolicLink(current)) { "commit path contains a symbolic link" }
        }
        require(Files.isDirectory(absolute.parent,LinkOption.NOFOLLOW_LINKS)) { "commit parent must be an existing directory" }
        if (Files.exists(absolute,LinkOption.NOFOLLOW_LINKS)) {
            require(Files.isRegularFile(absolute,LinkOption.NOFOLLOW_LINKS)) { "commit path must identify a regular file" }
            require(absolute.toRealPath(LinkOption.NOFOLLOW_LINKS)==absolute) { "commit path is not canonical" }
        }
        return absolute
    }

    private const val MAX_PATH_LENGTH=512
    private const val MAX_TEXT_BYTES=1_048_576
    private val WINDOWS_ABSOLUTE=Regex("^[A-Za-z]:")
    private val TEXT_EXTENSIONS=setOf("java","kt","kts","gradle","xml","yaml","yml","properties","md","txt","json","toml")
    private val PROTECTED_EXTENSIONS=setOf("jks","keystore","p12","pfx","pem","key","der","crt")
    private val PROTECTED_DIRECTORIES=setOf(".git",".gradle","build","generated",".idea",".vscode","out","target","node_modules")
}
