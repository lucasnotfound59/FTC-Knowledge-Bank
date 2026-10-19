package org.ftckb.knowledge

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.*
import java.security.MessageDigest
import java.time.Instant
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import org.ftckb.domain.*
import org.snakeyaml.engine.v2.api.LoadSettings
import org.snakeyaml.engine.v2.api.lowlevel.Compose
import org.snakeyaml.engine.v2.nodes.*

/** Captures bytes once per operation; decoding, validation, source locations and digests share that capture. */
class KnowledgeWorkspace(root:Path) {
    private val root=root.toAbsolutePath().normalize().let { selected ->
        checkAncestors(selected)
        try { selected.toRealPath() } catch(_:Exception) { fail("unsafe_path","knowledge root is not a readable directory") }
    }
    private data class Captured(val snapshot:KnowledgeSnapshot,val files:Map<String,ByteArray>,val spans:Map<String,Span>)
    private data class Span(val path:String,val start:Int,val end:Int,val indent:String)
    private data class Pending(val preview:RuleMutationPreview,val bytes:ByteArray,val targetDigest:String?,val expires:Instant)
    private val pending=LinkedHashMap<String,Pending>()

    fun snapshot():KnowledgeSnapshot=capture().snapshot

    fun preview(request:RuleMutationRequest):RuleMutationPreview {
        val captured=capture()
        val base=captured.snapshot
        if (request.expectedVersion!=base.version) fail("stale_preview","knowledge library has changed")
        if (!safeId.matches(request.ruleId)) fail("invalid_rule","invalid rule id")
        val existing=base.rules.filter { it.id==request.ruleId }
        if (existing.size>1) fail("invalid_rule","ambiguous rule id")
        val before=existing.singleOrNull()
        if (request.action!=RuleMutationAction.CREATE && before==null) fail("invalid_rule","rule does not exist")
        if (request.action==RuleMutationAction.CREATE && before!=null) fail("invalid_rule","rule already exists")
        val source=base.sources[request.ruleId]
        val path=if (request.action==RuleMutationAction.CREATE) "candidates/${request.ruleId}.yaml"
            else source?.path ?: fail("invalid_rule","rule source is not safely writable")
        safePath(path)
        if (request.action==RuleMutationAction.CREATE && Files.exists(root.resolve(path),LinkOption.NOFOLLOW_LINKS)) {
            fail("invalid_rule","candidate target already exists")
        }
        var reset=false
        val editor=when(request.action) {
            RuleMutationAction.CREATE,RuleMutationAction.EDIT -> {
                val supplied=request.ruleYaml ?: fail("invalid_rule","ruleYaml is required")
                val proposed=singleRule(supplied)
                if (proposed.id!=request.ruleId) fail("invalid_rule","ordinary edits cannot rename a rule")
                if (request.action==RuleMutationAction.CREATE) {
                    if (proposed.status!=RuleStatus.CANDIDATE || proposed.approval!=null) fail("invalid_rule","new rules must be candidates without approval")
                    ruleBody(supplied) // New candidates must remain safely editable after creation.
                    supplied
                } else {
                    before!!
                    if (proposed.status!=before.status || proposed.approval!=before.approval) fail("invalid_rule","use the approval or rejection action to change status or approval")
                    reset=before.status==RuleStatus.APPROVED && proposed!=before
                    if (reset) updateStatus(supplied,"candidate",null) else supplied
                }
            }
            RuleMutationAction.APPROVE,RuleMutationAction.REJECT -> {
                if (before!!.status!=RuleStatus.CANDIDATE) fail("approval_denied","only candidates can be approved or rejected")
                val approver=request.approver ?: fail("approval_denied","an explicit approver is required")
                if (approver.id.isBlank() || !ApprovalPolicy.authorize(before.authority,before.applicability.teams,approver)) {
                    fail("approval_denied","approver declaration is not authorized for this rule")
                }
                val approval=if(request.action==RuleMutationAction.APPROVE) Approval(approver.id,approver.role,approver.team,Instant.now()) else null
                updateStatus(source!!.ruleYaml,if(approval==null) "rejected" else "approved",approval)
            }
        }
        // Decode again after server-controlled approval/status changes.
        singleRule(editor)
        val bytes=if(before==null) editor.toByteArray(StandardCharsets.UTF_8) else {
            val span=captured.spans[request.ruleId] ?: fail("invalid_rule","ambiguous rule source")
            val old=decodeUtf8(captured.files.getValue(path))
            val replacement=ruleBody(editor).let { body -> body.lines().joinToString("\n") { span.indent+it } }.trimEnd('\n','\r')
            (old.substring(0,span.start)+replacement+old.substring(span.end)).toByteArray(StandardCharsets.UTF_8)
        }
        val proposedFiles=captured.files.toMutableMap().also { it[path]=bytes }
        val changed=decodeCapture(proposedFiles).snapshot
        var resolution:ResolutionResult?=null
        val errors=changed.loadErrors.toMutableList()
        if(request.context!=null && changed.valid) {
            if(request.context.team==null || request.context.season==null || request.context.profiles==null) {
                errors+=KnowledgeSourceError(path,"complete team, season and profile context is required")
            } else try { resolution=RuleResolver.resolve(changed.rules,request.context) }
            catch(_:IllegalArgumentException) { errors+=KnowledgeSourceError(path,"invalid resolution context") }
        }
        val allowed=changed.violations.isEmpty() && errors.isEmpty() && resolution?.conflicts?.isNotEmpty()!=true
        val preview=RuleMutationPreview(UUID.randomUUID().toString(),request.action,request.ruleId,base.version,path,
            source?.ruleYaml,editor,changed.violations,immutableList(errors),resolution,reset,allowed)
        synchronized(pending) {
            pending.entries.removeIf { it.value.expires.isBefore(Instant.now()) }
            while(pending.size>=128) pending.remove(pending.keys.first())
            pending[preview.previewId]=Pending(preview,bytes.copyOf(),base.fileDigests[path],Instant.now().plusSeconds(600))
        }
        return preview
    }

    fun apply(previewId:String):RuleMutationResult {
        val entry=synchronized(pending) { pending[previewId] } ?: fail("stale_preview","preview is missing or expired")
        if(entry.expires.isBefore(Instant.now())) fail("stale_preview","preview has expired")
        if(!entry.preview.canApply) fail("invalid_rule","preview did not pass full-library validation")
        return withLock {
            if(synchronized(pending) { pending[previewId]!==entry }) fail("stale_preview","preview has already been applied or evicted")
            if(entry.expires.isBefore(Instant.now())) fail("stale_preview","preview has expired")
            val current=capture()
            val preview=entry.preview
            if(current.snapshot.version!=preview.baseVersion || current.snapshot.fileDigests[preview.targetPath]!=entry.targetDigest) {
                fail("stale_preview","knowledge library changed after preview")
            }
            val proposed=current.files.toMutableMap().also { it[preview.targetPath]=entry.bytes }
            if(!decodeCapture(proposed).snapshot.valid) fail("invalid_rule","proposed library is invalid")
            val target=safePath(preview.targetPath)
            var temp:Path?=null
            try {
                Files.createDirectories(target.parent)
                safePath(preview.targetPath)
                temp=Files.createTempFile(target.parent,".ftckb-rule-",".tmp")
                FileChannel.open(temp,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS).use { channel ->
                    val buffer=ByteBuffer.wrap(entry.bytes)
                    while(buffer.hasRemaining()) channel.write(buffer)
                    channel.force(true)
                }
                // A second full byte comparison also catches non-cooperating external editors during preparation.
                if(capture().snapshot.version!=preview.baseVersion) fail("stale_preview","knowledge library changed before write")
                safePath(preview.targetPath)
                Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
                temp=null
                synchronized(pending) { pending.remove(previewId) }
                RuleMutationResult(preview.ruleId,decodeCapture(proposed).snapshot.version,preview.action)
            } catch(e:KnowledgeMutationException) { throw e }
            catch(_:Exception) { fail("write_failed","atomic knowledge write failed; original file was preserved") }
            finally { temp?.let { runCatching { Files.deleteIfExists(it) } } }
        }
    }

    /** Holds the same mutation lock until the caller finishes its entire checked code-write batch. */
    fun acquireSnapshotLease(expectedVersion:String):AutoCloseable {
        val lease=lock()
        try {
            if(capture().snapshot.version!=expectedVersion) fail("stale_preview","knowledge library changed")
            return lease
        } catch(e:Exception) { lease.close(); throw e }
    }

    private fun capture():Captured {
        safeRoot()
        val files=linkedMapOf<String,ByteArray>()
        try {
            Files.walk(root).use { stream -> stream.sorted().forEach { file ->
                if(Files.isSymbolicLink(file)) fail("unsafe_path","knowledge library contains a symbolic link")
                if(Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS) && file.fileName.toString().substringAfterLast('.',"") in setOf("yaml","yml")) {
                    val relative=root.relativize(file).toString().replace('\\','/')
                    safePath(relative)
                    val bytes=FileChannel.open(file,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS).use { channel ->
                        java.nio.channels.Channels.newInputStream(channel).readAllBytes()
                    }
                    safePath(relative)
                    files[relative]=bytes
                }
            } }
        } catch(e:KnowledgeMutationException) { throw e }
        catch(_:Exception) { fail("write_failed","knowledge library could not be read") }
        return decodeCapture(files)
    }

    private fun decodeCapture(files:Map<String,ByteArray>):Captured {
        val rules=mutableListOf<KnowledgeRule>()
        val errors=mutableListOf<KnowledgeSourceError>()
        val sources=linkedMapOf<String,RuleSource>()
        val spans=linkedMapOf<String,Span>()
        val digests=files.toSortedMap().mapValues { hash(it.value) }
        files.toSortedMap().forEach { (path,bytes) ->
            try {
                val text=decodeUtf8(bytes)
                val decoded=RuleYamlCodec.decode(text)
                rules+=decoded
                runCatching {
                    val mappings=ruleNodes(text)
                    decoded.zip(mappings).forEach { (rule,node) ->
                        val start=lineStart(text,index(text,node.startMark.get().index))
                        val end=contentEnd(text,node)
                        val raw=text.substring(start,end)
                        val indent=raw.takeWhile { it==' ' }
                        if(!raw.trimStart().startsWith("- ")) return@forEach
                        val standalone="schemaVersion: 4\nrules:\n"+raw.lines().joinToString("\n") { "  "+it.removePrefix(indent) }+"\n"
                        // Legacy documents are readable; only losslessly v4-compatible editor sources are writable.
                        if(RuleYamlCodec.decode(standalone).single()!=rule) return@forEach
                        sources[rule.id]=RuleSource(path,digests.getValue(path),standalone)
                        spans[rule.id]=Span(path,start,end,indent)
                    }
                }
            } catch(_:Exception) { errors+=KnowledgeSourceError(path,"invalid YAML rule document") }
        }
        val duplicateIds=rules.groupBy { it.id }.filterValues { it.size>1 }.keys
        duplicateIds.forEach { sources.remove(it); spans.remove(it) }
        val violations=duplicateIds.sorted().map { RuleViolation(it,"id","duplicate rule id") }+rules.flatMap(RuleValidator::validate)
        val digest=MessageDigest.getInstance("SHA-256")
        files.toSortedMap().forEach { (path,bytes) ->
            val name=path.toByteArray(StandardCharsets.UTF_8)
            digest.update(ByteBuffer.allocate(4).putInt(name.size).array());digest.update(name)
            digest.update(ByteBuffer.allocate(8).putLong(bytes.size.toLong()).array());digest.update(bytes)
        }
        val snapshot=KnowledgeSnapshot(root,hex(digest.digest()),immutableList(rules),immutableList(violations),immutableList(errors),
            Collections.unmodifiableMap(LinkedHashMap(sources)),Collections.unmodifiableMap(LinkedHashMap(digests)))
        return Captured(snapshot,files,spans)
    }

    private fun singleRule(text:String):KnowledgeRule=try {
        val node=Compose(settings()).composeString(text).orElseThrow() as MappingNode
        val version=node.value.singleOrNull { (it.keyNode as? ScalarNode)?.value=="schemaVersion" }?.valueNode as? ScalarNode
        require(version?.value=="4")
        RuleYamlCodec.decode(text).single()
    } catch(_:Exception) { fail("invalid_rule","ruleYaml must be one schemaVersion 4 rule document") }

    private fun ruleBody(text:String):String {
        val node=ruleNodes(text).single()
        val start=lineStart(text,index(text,node.startMark.get().index))
        val raw=text.substring(start,contentEnd(text,node))
        val indent=raw.takeWhile { it==' ' }
        if(!raw.trimStart().startsWith("- ")) fail("invalid_rule","rule editor requires a block rules sequence")
        return raw.lines().joinToString("\n") { it.removePrefix(indent) }
    }

    private fun updateStatus(text:String,status:String,approval:Approval?):String {
        val node=ruleNodes(text).single()
        val edits=mutableListOf<Triple<Int,Int,String>>()
        node.value.forEach { tuple ->
            when((tuple.keyNode as ScalarNode).value) {
                "status" -> edits+=Triple(index(text,tuple.valueNode.startMark.get().index),index(text,tuple.valueNode.endMark.get().index),status)
                "approval" -> {
                    val start=lineStart(text,index(text,tuple.keyNode.startMark.get().index))
                    val end=contentEnd(text,tuple.valueNode)
                    val indent=text.substring(start,index(text,tuple.keyNode.startMark.get().index))
                    val replacement=approval?.let { approvalYaml(it,indent) } ?: ""
                    edits+=Triple(start,end,replacement.trimEnd('\n'))
                }
            }
        }
        var result=text
        edits.sortedByDescending { it.first }.forEach { (start,end,replacement) -> result=result.substring(0,start)+replacement+result.substring(end) }
        if(approval!=null && node.value.none { (it.keyNode as ScalarNode).value=="approval" }) {
            val end=contentEnd(result,ruleNodes(result).single())
            val indent=" ".repeat(node.startMark.get().column)
            result=result.substring(0,end)+"\n"+approvalYaml(approval,indent).trimEnd('\n')+result.substring(end)
        }
        return result
    }

    private fun approvalYaml(approval:Approval,indent:String):String=buildString {
        append(indent+"approval:\n")
        append(indent+"  approver: "+quote(approval.approver)+"\n")
        append(indent+"  role: "+approval.role.name.lowercase()+"\n")
        approval.team?.let { append(indent+"  team: "+quote(it)+"\n") }
        append(indent+"  approvedAt: "+quote(approval.approvedAt.toString())+"\n")
    }
    private fun quote(value:String)="'"+value.replace("'","''")+"'"
    private fun ruleNodes(text:String):List<MappingNode> {
        val node=Compose(settings()).composeString(text).orElseThrow() as MappingNode
        val sequence=node.value.single { (it.keyNode as? ScalarNode)?.value=="rules" }.valueNode as SequenceNode
        return sequence.value.map { it as MappingNode }
    }
    // YAML marks count Unicode code points, whereas String offsets count UTF-16 code units.
    private fun index(text:String,point:Int)=text.offsetByCodePoints(0,point)
    private fun lineStart(text:String,offset:Int)=text.lastIndexOf('\n',(offset-1).coerceAtLeast(0)).let { if(it<0) 0 else it+1 }
    private fun contentEnd(text:String,node:Node):Int=when(node) {
        is MappingNode -> if(node.flowStyle==org.snakeyaml.engine.v2.common.FlowStyle.FLOW) index(text,node.endMark.get().index)
            else node.value.maxOfOrNull { contentEnd(text,it.valueNode) } ?: index(text,node.endMark.get().index)
        is SequenceNode -> if(node.flowStyle==org.snakeyaml.engine.v2.common.FlowStyle.FLOW) index(text,node.endMark.get().index)
            else node.value.maxOfOrNull { contentEnd(text,it) } ?: index(text,node.endMark.get().index)
        else -> index(text,node.endMark.get().index).let { end -> if(end>0 && text[end-1]=='\n') end-1 else end }
    }

    private fun safeRoot() {
        checkAncestors(root)
        if(!Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS)) fail("unsafe_path","knowledge root is not a directory")
    }
    private fun safePath(relative:String):Path {
        val path=root.resolve(relative).normalize()
        if(!path.startsWith(root) || path==root || Path.of(relative).isAbsolute) fail("unsafe_path","path escapes knowledge root")
        checkAncestors(path)
        return path
    }
    private fun checkAncestors(path:Path) {
        var current:Path?=path
        while(current!=null) {
            if(Files.isSymbolicLink(current)) fail("unsafe_path","symbolic links are not permitted")
            current=current.parent
        }
    }
    private fun lock():AutoCloseable {
        safeRoot()
        val key=root.toString()
        val mutex=locks.computeIfAbsent(key) { ReentrantLock() }
        mutex.lock()
        try {
            // A process-local temp directory would split lock identity across JVMs.
            val directory=Path.of(System.getProperty("user.home"),".ftckb","workspace-locks")
                .toAbsolutePath().normalize()
            checkAncestors(directory)
            Files.createDirectories(directory)
            checkAncestors(directory)
            val file=directory.resolve(hash(key.toByteArray())+".lock")
            val channel=FileChannel.open(file,StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)
            val held=try { channel.lock() } catch(e:Exception) { channel.close();throw e }
            return object:AutoCloseable {
                private var closed=false
                override fun close() { if(!closed) { closed=true;try { held.release() } finally { try { channel.close() } finally { mutex.unlock() } } } }
            }
        } catch(e:KnowledgeMutationException) { mutex.unlock();throw e }
        catch(_:Exception) { mutex.unlock();fail("write_failed","knowledge lock could not be acquired") }
    }
    private fun <T> withLock(body:()->T):T=lock().use { body() }
    companion object {
        private val locks=ConcurrentHashMap<String,ReentrantLock>()
        private val safeId=Regex("^[a-z0-9]+(?:[.-][a-z0-9]+)*$")
        private fun settings()=LoadSettings.builder().setAllowDuplicateKeys(false).build()
        private fun hash(bytes:ByteArray)=hex(MessageDigest.getInstance("SHA-256").digest(bytes))
        private fun hex(bytes:ByteArray)=bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
        private fun decodeUtf8(bytes:ByteArray)=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        private fun <T> immutableList(values:List<T>):List<T> =Collections.unmodifiableList(ArrayList(values))
        private fun fail(code:String,message:String):Nothing=throw KnowledgeMutationException(code,message)
    }
}
