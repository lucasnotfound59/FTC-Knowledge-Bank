package org.ftckb.session

import org.ftckb.agent.edit.*
import org.ftckb.audit.*
import org.ftckb.domain.KnowledgeRule
import org.ftckb.domain.WorkMode
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import org.eclipse.jgit.api.Git
import org.ftckb.git.AgentDiffRenderer
import org.ftckb.git.TextChange
import org.ftckb.knowledge.KnowledgeSnapshot
import org.ftckb.knowledge.KnowledgeWorkspace
import org.ftckb.standardizer.Standardizer

/** Mandatory normal-mode prewrite policy for production chat/serve. */
internal class StandardizerEditGate(
    private val workspace:KnowledgeWorkspace,
    private val snapshot:KnowledgeSnapshot,
    private val rules:List<KnowledgeRule>,
    private val repositoryRoot:Path,
    private val workMode:WorkMode=WorkMode.NORMAL,
    private val audit:()->SessionAudit?
):EditPrewriteGate {
    private val repositoryBaseline=runCatching { repositoryState() }.getOrNull()
    private fun repositoryState():List<Any?> =Git.open(repositoryRoot.toFile()).use { git ->
        listOf(repositoryRoot.toRealPath(),Files.readAttributes(repositoryRoot,BasicFileAttributes::class.java,LinkOption.NOFOLLOW_LINKS).fileKey(),
            git.repository.directory.toPath().toRealPath(),git.repository.fullBranch,git.repository.resolve("HEAD")?.name)
    }
    override fun inspect(preview:ValidatedEditBatch):EditGateResult {
        // Renderer emits traditional per-file patches without diff --git separators.
        // Parse each file independently so a following header cannot become hunk data.
        val changes=try { preview.changes.flatMap { change ->
            Standardizer.parsePatch(AgentDiffRenderer.render(listOf(
                TextChange(change.path,change.before.text(),change.after.text(),false)
            )))
        } } catch (_:Exception) {
            audit()?.stage("diff_read",AuditStageStatus.BLOCKED,"load_error")
            throw EditGateException("load_error",emptyList(),"unable to parse generated edit diff")
        }
        audit()?.stage("diff_read",AuditStageStatus.PASSED)
        val result=Standardizer.evaluate(rules,changes)
        val notices=result.violations.map { EditGateNotice(if(workMode==WorkMode.DEV) "dev_downgrade" else "hard",it.ruleId,it.check,it.path,it.line,it.detail) }+
            result.soft.map { (id,note)->EditGateNotice("soft",id,null,null,null,note) }
        audit()?.stage("standardizer",if(result.violations.isEmpty() || workMode==WorkMode.DEV) AuditStageStatus.PASSED else AuditStageStatus.BLOCKED,
            if(result.violations.isNotEmpty()) if(workMode==WorkMode.DEV) "dev_downgrade" else "hard" else if(result.soft.isNotEmpty()) "soft" else null,
            notices.map { AuditHit(it.kind,it.ruleId,it.check,it.path,it.line,it.detail) })
        return EditGateResult(result.violations.isEmpty() || workMode==WorkMode.DEV,notices)
    }
    override fun beginWrite(preview:ValidatedEditBatch):AutoCloseable=try {
        workspace.acquireSnapshotLease(snapshot.version)
    } catch (_:Exception) {
        stale()
    }
    override fun verifyCurrent(preview:ValidatedEditBatch) {
        val current=try { workspace.snapshot() } catch (_:Exception) { stale() }
        if (!current.valid || current.version!=snapshot.version) stale()
        if(runCatching { repositoryState()!=repositoryBaseline }.getOrDefault(true)) {
            audit()?.stage("baseline_guard",AuditStageStatus.BLOCKED,"stale_baseline")
            throw EditGateException("stale_baseline",emptyList(),"repository baseline changed; regenerate the edit")
        }
        audit()?.stage("baseline_guard",AuditStageStatus.PASSED)
    }
    private fun stale():Nothing {
        audit()?.stage("baseline_guard",AuditStageStatus.BLOCKED,"stale_knowledge")
        throw EditGateException("stale_knowledge",emptyList(),"knowledge changed; regenerate the edit")
    }
    private fun FileSnapshot.text():String?=when(this) { FileSnapshot.Missing->null; is FileSnapshot.Text->content }
}
