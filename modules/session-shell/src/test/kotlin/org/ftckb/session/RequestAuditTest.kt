package org.ftckb.session

import java.nio.file.Path
import org.ftckb.audit.*
import org.ftckb.model.ModelProviderException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class RequestAuditTest {
    @Test fun `ask links two model children and persists no prompt completion key or exception`(@TempDir root:Path) {
        val f=SessionTestFixture(root);val runtime=f.runtime("serve")
        runtime.ask("PRIVATE_PROMPT_SENTINEL token=PRIVATE_TOKEN_SENTINEL")
        val records=f.store().page(limit=100).items
        val parent=records.single { it.metadata.command=="ask" }
        assertEquals("serve",parent.metadata.entrypoint)
        assertEquals(2,f.store().children(parent.invocationId).size)
        assertTrue(f.store().children(parent.invocationId).all { it.inputTokens!=null && it.outputTokens!=null })
        assertEquals(AuditStageStatus.NOT_APPLICABLE,parent.stages.last { it.name=="standardizer" }.status)
        listOf("PRIVATE_PROMPT_SENTINEL","PRIVATE_COMPLETION_SENTINEL","PRIVATE_KEY_SENTINEL","PRIVATE_TOKEN_SENTINEL").forEach { assertFalse(f.auditText().contains(it)) }
    }
    @Test fun `failed model closes parent and child and next request has new clean scope`(@TempDir root:Path) {
        val f=SessionTestFixture(root);val runtime=f.runtime()
        f.failModel=true
        assertThrows(ModelProviderException::class.java) { runtime.ask("private") }
        f.failModel=false;runtime.ask("retry")
        val parents=f.store().page(limit=100).items.filter { it.metadata.command=="ask" }
        assertEquals(setOf(AuditRunStatus.COMPLETED,AuditRunStatus.FAILED),parents.map { it.status }.toSet())
        assertTrue(parents.all { it.endedAt!=null })
        assertFalse(f.auditText().contains("PRIVATE_EXCEPTION_SENTINEL"))
        assertEquals(3,f.store().page(limit=100).items.count { it.metadata.command=="model" })
    }
    @Test fun `citation rejection is retained by real file store as failed evidence`(@TempDir root:Path) {
        val f=SessionTestFixture(root);val runtime=f.runtime();f.citation="CODE:DOES_NOT_EXIST"
        runtime.controller().setMode(org.ftckb.agent.AgentMode.EDIT)
        val result=runtime.submit("guard") as org.ftckb.agent.RejectedResult
        assertEquals("citation_guard",result.category)
        val record=f.store().page(limit=100).items.first { it.metadata.command=="edit" }
        assertEquals(AuditRunStatus.FAILED,record.status);assertEquals(2,record.exitCode)
        assertTrue(record.stages.any { it.name=="citation_guard" && it.status==AuditStageStatus.BLOCKED && it.category=="citation_guard" })
        assertEquals(AuditStageStatus.NOT_RUN,record.stages.last { it.name=="standardizer" }.status)
    }
    @Test fun `changed branch refusal records failed parent and actual branch block`(@TempDir root:Path) {
        val f=SessionTestFixture(root);val runtime=f.runtime()
        runtime.controller().setMode(org.ftckb.agent.AgentMode.EDIT)
        org.eclipse.jgit.api.Git.open(f.repository.toFile()).use { it.checkout().setCreateBranch(true).setName("other").call() }
        assertTrue(runtime.submit("guard") is org.ftckb.agent.RejectedResult)
        assertEquals(0,f.calls)
        val record=f.store().page(limit=100).items.first { it.metadata.command=="edit" }
        assertEquals(AuditRunStatus.FAILED,record.status);assertEquals(2,record.exitCode)
        assertTrue(record.stages.any { it.name=="branch_guard" && it.status==AuditStageStatus.BLOCKED })
    }

    @Test fun `malformed model plan has actual blocked model evidence and no plan body`(@TempDir root:Path) {
        val f=SessionTestFixture(root);val runtime=f.runtime();f.malformedEditPlan=true
        runtime.controller().setMode(org.ftckb.agent.AgentMode.EDIT)
        assertTrue(runtime.submit("guard") is org.ftckb.agent.RejectedResult)
        val record=f.store().page(limit=100).items.first { it.metadata.command=="edit" }
        assertEquals(AuditRunStatus.FAILED,record.status);assertEquals(2,record.exitCode)
        assertEquals(AuditStageStatus.BLOCKED,record.stages.last { it.name=="model" }.status)
        assertEquals("model_failure",record.stages.last { it.name=="model" }.category)
        assertEquals(AuditStageStatus.NOT_RUN,record.stages.last { it.name=="standardizer" }.status)
        assertFalse(f.auditText().contains("PRIVATE_MALFORMED_PLAN_SENTINEL"))
    }

}
