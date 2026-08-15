package org.ftckb.git

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Locale
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.dircache.DirCache
import org.eclipse.jgit.dircache.DirCacheEditor
import org.eclipse.jgit.dircache.DirCacheEntry
import org.eclipse.jgit.lib.CommitBuilder
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.RefUpdate
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.lib.RepositoryState
import org.eclipse.jgit.revwalk.RevWalk

data class CommitRequest(
    val repositoryRoot:Path,val paths:Set<String>,val baselineDirtyPaths:Set<String>,val message:String
)

object GitCommitService {
    fun commit(request:CommitRequest):String {
        require(request.message.isNotBlank()) { "commit message must not be blank" }
        require(request.paths.isNotEmpty()) { "commit paths must not be empty" }
        val overlap=request.paths.intersect(request.baselineDirtyPaths).toSortedSet()
        require(overlap.isEmpty()) {
            "cannot commit paths that were dirty before Agent edits: ${overlap.joinToString(", ")}"
        }
        openSelectedRepository(request.repositoryRoot).use { repository->
            val fullBranch=repository.fullBranch
            require(fullBranch!=null && fullBranch.startsWith(Constants.R_HEADS)) {
                "cannot commit from detached HEAD"
            }
            require(repository.repositoryState==RepositoryState.SAFE) {
                "cannot commit while a repository operation is in progress"
            }
            val root=repository.workTree.toPath().toRealPath()
            val paths=request.paths.toSortedSet()
            val resolved=paths.map { path->validatePath(root,path) }
            require(resolved.toSet().size==resolved.size) { "commit paths contain aliases" }
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
                    return createCommit(repository,fullBranch,paths,request.message)
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

    private fun createCommit(repository:Repository,branch:String,paths:Set<String>,message:String):String {
        val head=repository.resolve(Constants.HEAD)
        val commitIndex=repository.newObjectReader().use { reader->
            if (head==null) DirCache.newInCore()
            else RevWalk(repository).use { walk->DirCache.read(reader,walk.parseCommit(head).tree) }
        }
        val staged=DirCache.read(repository)
        val editor=commitIndex.editor()
        paths.forEach { path->
            val stagedEntry=staged.getEntry(path)
            if (stagedEntry==null) editor.add(DirCacheEditor.DeletePath(path))
            else {
                require(stagedEntry.stage==DirCacheEntry.STAGE_0) { "commit path remains conflicted: $path" }
                editor.add(object:DirCacheEditor.PathEdit(path) {
                    override fun apply(entry:DirCacheEntry) {
                        entry.copyMetaData(stagedEntry)
                        entry.setObjectId(stagedEntry.objectId)
                        entry.fileMode=stagedEntry.fileMode
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
    private val WINDOWS_ABSOLUTE=Regex("^[A-Za-z]:")
    private val TEXT_EXTENSIONS=setOf("java","kt","kts","gradle","xml","yaml","yml","properties","md","txt","json","toml")
    private val PROTECTED_EXTENSIONS=setOf("jks","keystore","p12","pfx","pem","key","der","crt")
    private val PROTECTED_DIRECTORIES=setOf(".git",".gradle","build","generated",".idea",".vscode","out","target","node_modules")
}
