package org.ftckb.knowledge

import java.nio.file.Files
import java.nio.file.Path

/** Standalone child JVM used to exercise the actual OS lock rather than the JVM mutex. */
object KnowledgeWorkspaceProcessMain {
    @JvmStatic fun main(args:Array<String>) {
        val workspace=KnowledgeWorkspace(Path.of(args[1]))
        val ready=Path.of(args[2])
        if(args[0]=="lease") {
            workspace.acquireSnapshotLease(workspace.snapshot().version).use {
                Files.writeString(ready,"ready")
                val release=Path.of(args[3])
                val deadline=System.nanoTime()+15_000_000_000L
                while(!Files.exists(release)) {
                    check(System.nanoTime()<deadline) { "lease release timed out" }
                    Thread.sleep(10)
                }
            }
        } else {
            val snapshot=workspace.snapshot()
            val source=snapshot.sources.getValue("shared.one")
            val preview=workspace.preview(RuleMutationRequest(RuleMutationAction.EDIT,"shared.one",snapshot.version,
                source.ruleYaml.replace("Keep it safe","Cross process edit")))
            Files.writeString(ready,"ready")
            workspace.apply(preview.previewId)
        }
    }
}
