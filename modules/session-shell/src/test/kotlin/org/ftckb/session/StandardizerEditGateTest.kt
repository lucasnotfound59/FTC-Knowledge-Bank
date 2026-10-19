package org.ftckb.session

import java.nio.file.Files
import java.nio.file.Path
import org.ftckb.agent.*
import org.ftckb.agent.edit.*
import org.ftckb.domain.WorkMode
import org.ftckb.audit.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class StandardizerEditGateTest {
    @Test fun `hard block is completed check and dev retains location without persistent default`(@TempDir root:Path) {
        val f=SessionTestFixture(root);Files.writeString(f.ruleFile,f.rule(hard=true));val runtime=f.runtime()
        assertNull(runtime.controller().setMode(AgentMode.EDIT))
        assertThrows(EditGateException::class.java) { runtime.submit("guard") }
        val blocked=f.store().page(limit=100).items.first { it.metadata.command=="edit" }
        assertEquals(AuditRunStatus.COMPLETED,blocked.status);assertEquals(1,blocked.exitCode)
        val report=(runtime.submit("guard",WorkMode.DEV) as EditResult).report
        assertTrue(report.gateNotices.any { it.kind=="dev_downgrade" && it.path==f.sourcePath && it.line!=null })
        runtime.restore(false)
        assertThrows(EditGateException::class.java) { runtime.submit("guard") }
    }
    @Test fun `knowledge changed during generation aborts before write`(@TempDir root:Path) {
        val f=SessionTestFixture(root);val runtime=f.runtime();val original=Files.readString(f.source)
        assertNull(runtime.controller().setMode(AgentMode.EDIT))
        f.beforeResponse={ Files.writeString(f.ruleFile,f.rule()+"# changed during response\n") }
        val failure=assertThrows(EditGateException::class.java) { runtime.submit("guard") }
        assertEquals("stale_knowledge",failure.code)
        assertEquals(original,Files.readString(f.source));assertFalse(runtime.hasEditChanges())
    }
    @Test fun `head changed during generation aborts even while branch remains named`(@TempDir root:Path) {
        val f=SessionTestFixture(root);val runtime=f.runtime();val original=Files.readString(f.source)
        assertNull(runtime.controller().setMode(AgentMode.EDIT))
        f.beforeResponse={ org.eclipse.jgit.api.Git.open(f.repository.toFile()).use { git ->
            git.commit().setAllowEmpty(true).setMessage("external head advance").setAuthor("test","test@example.invalid").call()
        } }
        val failure=assertThrows(EditGateException::class.java) { runtime.submit("guard") }
        assertEquals("stale_baseline",failure.code)
        assertEquals(original,Files.readString(f.source));assertFalse(runtime.hasEditChanges())
    }
    @Test fun `restore conflict is observed without applying new-edit checks`(@TempDir root:Path) {
        val f=SessionTestFixture(root);val runtime=f.runtime();runtime.controller().setMode(AgentMode.EDIT)
        runtime.submit("guard")
        Files.writeString(f.source,Files.readString(f.source)+"// external user edit\n")
        val result=runtime.restore(false) as HistoryAppliedResult
        assertFalse(result.result.succeeded)
        val record=f.store().page(limit=100).items.first { it.metadata.command=="discard" }
        assertEquals(AuditRunStatus.FAILED,record.status);assertEquals(2,record.exitCode)
        assertEquals(AuditStageStatus.BLOCKED,record.stages.last { it.name=="restore" }.status)
        assertEquals(AuditStageStatus.NOT_APPLICABLE,record.stages.last { it.name=="standardizer" }.status)
        assertTrue(Files.readString(f.source).contains("external user edit"))
    }
    @Test fun `two file preview is fully gated and restores both paths`(@TempDir root:Path) {
        val f=SessionTestFixture(root);val runtime=f.runtime();runtime.controller().setMode(AgentMode.EDIT)
        val second="TeamCode/src/main/java/example/Helper.java"
        f.extraOperation=""",{"kind":"create","path":"$second","expectedAbsent":true,"content":"class Helper {}","reason":"helper","citations":["CODE:C1"]}"""
        val report=(runtime.submit("guard and helper") as EditResult).report
        assertEquals(setOf(f.sourcePath,second),report.changedPaths)
        assertTrue(Files.exists(f.repository.resolve(second)))
        assertTrue((runtime.restore(false) as HistoryAppliedResult).result.succeeded)
        assertFalse(Files.exists(f.repository.resolve(second)))
    }
    @Test fun `explicit test mode exempts architecture rule but retains safety rule`(@TempDir root:Path) {
        val f=SessionTestFixture(root)
        Files.writeString(f.ruleFile,f.rule(hard=true).replace("shared.safety","global.command-responsibilities"))
        val runtime=f.runtime();runtime.controller().setMode(AgentMode.EDIT)
        assertThrows(EditGateException::class.java) { runtime.submit("normal") }
        assertTrue(runtime.submit("test",WorkMode.TEST) is EditResult)
        runtime.restore(false)
        Files.writeString(f.ruleFile,f.rule(hard=true))
        assertThrows(EditGateException::class.java) { runtime.submit("safety still applies",WorkMode.TEST) }
    }

}
