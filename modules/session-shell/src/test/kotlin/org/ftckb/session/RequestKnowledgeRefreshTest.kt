package org.ftckb.session

import java.nio.file.Files
import java.nio.file.Path
import org.ftckb.agent.*
import org.ftckb.agent.edit.EditGateException
import org.ftckb.audit.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class RequestKnowledgeRefreshTest {
    @Test fun `refresh preserves edit mode authorization and outstanding history then recovery restores bytes`(@TempDir root:Path) {
        val f=SessionTestFixture(root);val runtime=f.runtime();val original=Files.readString(f.source)
        assertNull(runtime.controller().setMode(AgentMode.EDIT))
        val edit=runtime.submit("guard Vision") as EditResult
        assertTrue(edit.report.gateNotices.any { it.kind=="soft" })
        Files.writeString(f.ruleFile,f.rule(instruction="New request safety"))
        runtime.ask("explain Vision")
        assertEquals(AgentMode.EDIT,runtime.currentMode());assertTrue(runtime.hasEditChanges())
        assertEquals("team-work",runtime.controller().authorizedEditBranch)
        assertTrue((runtime.restore(true) as HistoryAppliedResult).result.succeeded)
        assertEquals(original,Files.readString(f.source))
        val records=f.store().page(limit=100).items.filter { it.metadata.command!="model" }
        assertEquals("New request safety",records.single { it.metadata.command=="ask" }.ruleSnapshots.single().instruction)
        assertEquals(AuditStageStatus.NOT_APPLICABLE,records.single { it.metadata.command=="undo" }.stages.last { it.name=="standardizer" }.status)
    }
    @Test fun `invalid external update blocks next request before model and preserves last edit state`(@TempDir root:Path) {
        val f=SessionTestFixture(root);val runtime=f.runtime();runtime.ask("first")
        val calls=f.calls;Files.writeString(f.ruleFile,"rules: [broken")
        assertThrows(AskChatSessionException.KnowledgeRead::class.java) { runtime.ask("second") }
        assertEquals(calls,f.calls)
        val failed=f.store().page(limit=100).items.first { it.metadata.command=="ask" }
        assertEquals(AuditRunStatus.FAILED,failed.status)
        assertEquals(AuditStageStatus.BLOCKED,failed.stages.last { it.name=="knowledge_load" }.status)
    }
    @Test fun `changed rule becomes hard before next edit and leaves files and history exact`(@TempDir root:Path) {
        val f=SessionTestFixture(root);val runtime=f.runtime();val original=Files.readString(f.source)
        runtime.ask("first");Files.writeString(f.ruleFile,f.rule(hard=true))
        assertNull(runtime.controller().setMode(AgentMode.EDIT))
        val failure=assertThrows(EditGateException::class.java) { runtime.submit("guard Vision") }
        assertTrue(failure.notices.any { it.kind=="hard" && it.path==f.sourcePath && it.line!=null })
        assertEquals(original,Files.readString(f.source));assertFalse(runtime.hasEditChanges())
    }
}
