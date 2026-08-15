package org.ftckb.agent.edit

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class EditHistoryTest {
    @Test
    fun `undo reverses only the latest batch on an initially dirty file`(@TempDir root:Path) {
        val file=root.resolve("TeamCode/Drive.java")
        Files.createDirectories(file.parent)
        Files.writeString(file,"user dirty\n")
        val engine=FileEditEngine(root)
        val history=EditHistory(root,engine)

        history.applyAndRecord(replaceBatch(engine,"user dirty\n","agent one\n"))
        history.applyAndRecord(replaceBatch(engine,"agent one\n","agent two\n"))

        val result=history.undo()

        assertTrue(result.succeeded)
        assertEquals(setOf("TeamCode/Drive.java"),result.changedPaths)
        assertEquals("agent one\n",Files.readString(file))
        assertEquals(
            listOf(org.ftckb.git.TextChange("TeamCode/Drive.java","user dirty\n","agent one\n",false)),
            history.changes()
        )
    }

    @Test
    fun `discard restores exact first-touch dirty bytes across batches`(@TempDir root:Path) {
        val file=root.resolve("TeamCode/Drive.java")
        Files.createDirectories(file.parent)
        Files.writeString(file,"user dirty\r\nwith spacing  \n")
        val engine=FileEditEngine(root)
        val history=EditHistory(root,engine)

        history.applyAndRecord(replaceBatch(engine,"user dirty\r\nwith spacing  \n","agent one\r\nwith spacing  \n"))
        history.applyAndRecord(replaceBatch(engine,"agent one\r\nwith spacing  \n","agent two\r\nwith spacing  \n"))

        val result=history.discard()

        assertTrue(result.succeeded)
        assertEquals("user dirty\r\nwith spacing  \n",Files.readString(file))
        assertTrue(history.changes().isEmpty())
    }

    @Test
    fun `changes and discard preserve create move and delete first-touch states`(@TempDir root:Path) {
        val teamCode=root.resolve("TeamCode")
        Files.createDirectories(teamCode)
        Files.writeString(teamCode.resolve("Source.java"),"source\n")
        Files.writeString(teamCode.resolve("Deleted.java"),"deleted\n")
        val engine=FileEditEngine(root)
        val history=EditHistory(root,engine)
        val candidate=engine.preview(EditPlan("mixed",listOf(
            CreateText("TeamCode/Created.java",true,"created\n","create",emptyList()),
            MoveText("TeamCode/Source.java","TeamCode/Moved.java",sha256("source\n"),true,"move",emptyList()),
            DeleteText("TeamCode/Deleted.java",sha256("deleted\n"),"delete",emptyList())
        )))

        history.applyAndRecord(candidate)

        val changes=history.changes().associateBy { it.path }
        assertEquals(null,changes.getValue("TeamCode/Created.java").before)
        assertEquals("created\n",changes.getValue("TeamCode/Created.java").after)
        assertEquals("source\n",changes.getValue("TeamCode/Source.java").before)
        assertEquals(null,changes.getValue("TeamCode/Source.java").after)
        assertEquals(null,changes.getValue("TeamCode/Moved.java").before)
        assertEquals("source\n",changes.getValue("TeamCode/Moved.java").after)
        assertEquals("deleted\n",changes.getValue("TeamCode/Deleted.java").before)
        assertEquals(null,changes.getValue("TeamCode/Deleted.java").after)

        assertTrue(history.discard().succeeded)
        assertTrue(Files.notExists(teamCode.resolve("Created.java")))
        assertEquals("source\n",Files.readString(teamCode.resolve("Source.java")))
        assertTrue(Files.notExists(teamCode.resolve("Moved.java")))
        assertEquals("deleted\n",Files.readString(teamCode.resolve("Deleted.java")))
    }

    @Test
    fun `undo conflict performs zero writes and retains the batch for retry`(@TempDir root:Path) {
        val teamCode=root.resolve("TeamCode")
        Files.createDirectories(teamCode)
        val first=teamCode.resolve("First.java")
        val second=teamCode.resolve("Second.java")
        Files.writeString(first,"first\n")
        Files.writeString(second,"second\n")
        val engine=FileEditEngine(root)
        val history=EditHistory(root,engine)
        history.applyAndRecord(engine.preview(EditPlan("both",listOf(
            ReplaceText("TeamCode/First.java",sha256("first\n"),"first","agent first","reason",emptyList()),
            ReplaceText("TeamCode/Second.java",sha256("second\n"),"second","agent second","reason",emptyList())
        ))))
        Files.writeString(second,"IDE change\n")

        val conflicted=history.undo()

        assertEquals(setOf("TeamCode/Second.java"),conflicted.conflicts)
        assertEquals("agent first\n",Files.readString(first))
        assertEquals("IDE change\n",Files.readString(second))

        Files.writeString(second,"agent second\n")
        assertTrue(history.undo().succeeded)
        assertEquals("first\n",Files.readString(first))
        assertEquals("second\n",Files.readString(second))
    }

    @Test
    fun `undo propagates an unsafe current snapshot read instead of reporting a conflict`(@TempDir root:Path) {
        val file=root.resolve("TeamCode/Drive.java")
        val outside=root.resolve("outside.txt")
        Files.createDirectories(file.parent)
        Files.writeString(file,"baseline\n")
        Files.writeString(outside,"outside\n")
        val engine=FileEditEngine(root)
        val history=EditHistory(root,engine)
        history.applyAndRecord(replaceBatch(engine,"baseline\n","agent\n"))
        Files.delete(file)
        Files.createSymbolicLink(file,outside)

        assertThrows(IllegalArgumentException::class.java) { history.undo() }

        assertEquals("outside\n",Files.readString(outside))
        assertEquals("agent\n",history.changes().single().after)
    }

    @Test
    fun `discard propagates a malformed current snapshot read instead of reporting a conflict`(@TempDir root:Path) {
        val file=root.resolve("TeamCode/Drive.java")
        Files.createDirectories(file.parent)
        Files.writeString(file,"baseline\n")
        val engine=FileEditEngine(root)
        val history=EditHistory(root,engine)
        history.applyAndRecord(replaceBatch(engine,"baseline\n","agent\n"))
        Files.write(file,byteArrayOf(0xC3.toByte()))

        assertThrows(IllegalArgumentException::class.java) { history.discard() }

        assertEquals(byteArrayOf(0xC3.toByte()).toList(),Files.readAllBytes(file).toList())
        assertEquals("agent\n",history.changes().single().after)
    }

    @Test
    fun `discard conflict performs zero writes and retains first-touch state for retry`(@TempDir root:Path) {
        val teamCode=root.resolve("TeamCode")
        Files.createDirectories(teamCode)
        val first=teamCode.resolve("First.java")
        val second=teamCode.resolve("Second.java")
        Files.writeString(first,"first dirty\n")
        Files.writeString(second,"second dirty\n")
        val engine=FileEditEngine(root)
        val history=EditHistory(root,engine)
        history.applyAndRecord(engine.preview(EditPlan("both",listOf(
            ReplaceText("TeamCode/First.java",sha256("first dirty\n"),"first dirty","agent first","reason",emptyList()),
            ReplaceText("TeamCode/Second.java",sha256("second dirty\n"),"second dirty","agent second","reason",emptyList())
        ))))
        Files.writeString(second,"IDE change\n")

        val conflicted=history.discard()

        assertEquals(setOf("TeamCode/Second.java"),conflicted.conflicts)
        assertEquals("agent first\n",Files.readString(first))
        assertEquals("IDE change\n",Files.readString(second))
        assertEquals(2,history.changes().size)

        Files.writeString(second,"agent second\n")
        assertTrue(history.discard().succeeded)
        assertEquals("first dirty\n",Files.readString(first))
        assertEquals("second dirty\n",Files.readString(second))
    }

    @Test
    fun `IDE race during undo rolls back prior reversals and reports the conflict`(@TempDir root:Path) {
        val teamCode=root.resolve("TeamCode")
        Files.createDirectories(teamCode)
        val first=teamCode.resolve("First.java")
        val second=teamCode.resolve("Second.java")
        Files.writeString(first,"first\n")
        Files.writeString(second,"second\n")
        var raceEnabled=false
        val racingEngine=FileEditEngine(root,beforeWrite={ _,writeNumber ->
            if (raceEnabled && writeNumber==2) Files.writeString(second,"IDE race\n")
        })
        val history=EditHistory(root,racingEngine)
        history.applyAndRecord(racingEngine.preview(EditPlan("both",listOf(
            ReplaceText("TeamCode/First.java",sha256("first\n"),"first","agent first","reason",emptyList()),
            ReplaceText("TeamCode/Second.java",sha256("second\n"),"second","agent second","reason",emptyList())
        ))))
        raceEnabled=true

        val result=history.undo()

        assertEquals(setOf("TeamCode/Second.java"),result.conflicts)
        assertEquals("agent first\n",Files.readString(first))
        assertEquals("IDE race\n",Files.readString(second))
        assertEquals(2,history.changes().size)
    }

    @Test
    fun `undo rethrows a transactional failure when rollback is incomplete`(@TempDir root:Path) {
        val teamCode=root.resolve("TeamCode")
        Files.createDirectories(teamCode)
        val first=teamCode.resolve("First.java")
        val second=teamCode.resolve("Second.java")
        Files.writeString(first,"first\n")
        Files.writeString(second,"second\n")
        var failUndo=false
        val engine=FileEditEngine(root,beforeMutation={ _,mutationNumber ->
            if (failUndo && mutationNumber==2) {
                Files.writeString(first,"external after reverse\n")
                throw IOException("abort second reverse")
            }
        })
        val history=EditHistory(root,engine)
        history.applyAndRecord(engine.preview(EditPlan("both",listOf(
            ReplaceText("TeamCode/First.java",sha256("first\n"),"first","agent first","reason",emptyList()),
            ReplaceText("TeamCode/Second.java",sha256("second\n"),"second","agent second","reason",emptyList())
        ))))
        failUndo=true

        val failure=assertThrows(FileEditApplyException::class.java) { history.undo() }

        assertEquals("abort second reverse",failure.originalFailure.message)
        assertTrue(failure.rollbackFailures.isNotEmpty())
        assertEquals(2,history.changes().size)
    }

    @Test
    fun `discard rethrows a transactional failure when temporary cleanup is incomplete`(@TempDir root:Path) {
        val teamCode=root.resolve("TeamCode")
        Files.createDirectories(teamCode)
        val first=teamCode.resolve("First.java")
        val second=teamCode.resolve("Second.java")
        Files.writeString(first,"first\n")
        Files.writeString(second,"second\n")
        var failDiscard=false
        val engine=FileEditEngine(root,beforeMutation={ path,mutationNumber ->
            if (failDiscard && mutationNumber==2) {
                val temporary=Files.list(path.parent).use { files ->
                    files.filter { it.fileName.toString().startsWith(".${path.fileName}.ftckb-write-") }
                        .findFirst().orElseThrow()
                }
                Files.delete(temporary)
                Files.createDirectory(temporary)
                Files.writeString(second,"external during discard\n")
                throw IOException("abort second discard")
            }
        })
        val history=EditHistory(root,engine)
        history.applyAndRecord(engine.preview(EditPlan("both",listOf(
            ReplaceText("TeamCode/First.java",sha256("first\n"),"first","agent first","reason",emptyList()),
            ReplaceText("TeamCode/Second.java",sha256("second\n"),"second","agent second","reason",emptyList())
        ))))
        failDiscard=true

        val failure=assertThrows(FileEditApplyException::class.java) { history.discard() }

        assertEquals("abort second discard",failure.originalFailure.message)
        assertTrue(failure.cleanupFailures.isNotEmpty())
        assertEquals(2,history.changes().size)
    }

    @Test
    fun `authorization abort without a byte conflict propagates and retains history`(@TempDir root:Path) {
        val file=root.resolve("TeamCode/Drive.java")
        Files.createDirectories(file.parent)
        Files.writeString(file,"baseline\n")
        val engine=FileEditEngine(root)
        val history=EditHistory(root,engine)
        history.applyAndRecord(replaceBatch(engine,"baseline\n","agent\n"))

        val failure=assertThrows(FileEditApplyException::class.java) {
            history.undo { throw TestAuthorizationException() }
        }

        assertTrue(failure.originalFailure is TestAuthorizationException)
        assertTrue(failure.rollbackFailures.isEmpty())
        assertTrue(failure.cleanupFailures.isEmpty())
        assertEquals("agent\n",Files.readString(file))
        assertEquals("agent\n",history.changes().single().after)
    }

    @Test
    fun `authorization abort is not converted into an ordinary conflict when bytes also change`(@TempDir root:Path) {
        val file=root.resolve("TeamCode/Drive.java")
        Files.createDirectories(file.parent)
        Files.writeString(file,"baseline\n")
        val engine=FileEditEngine(root)
        val history=EditHistory(root,engine)
        history.applyAndRecord(replaceBatch(engine,"baseline\n","agent\n"))

        val failure=assertThrows(FileEditApplyException::class.java) {
            history.undo {
                Files.writeString(file,"external during authorization\n")
                throw TestAuthorizationException()
            }
        }

        assertTrue(failure.originalFailure is TestAuthorizationException)
        assertEquals("external during authorization\n",Files.readString(file))
        assertEquals("agent\n",history.changes().single().after)
    }

    @Test
    fun `non race IllegalArgumentException propagates even when bytes also change`(@TempDir root:Path) {
        val teamCode=root.resolve("TeamCode")
        Files.createDirectories(teamCode)
        val first=teamCode.resolve("First.java")
        val second=teamCode.resolve("Second.java")
        Files.writeString(first,"first\n")
        Files.writeString(second,"second\n")
        var failUndo=false
        val engine=FileEditEngine(root,beforeWrite={ _,writeNumber ->
            if (failUndo && writeNumber==2) {
                Files.writeString(second,"external non-race change\n")
                throw IllegalArgumentException("synthetic invariant failure")
            }
        })
        val history=EditHistory(root,engine)
        history.applyAndRecord(engine.preview(EditPlan("both",listOf(
            ReplaceText("TeamCode/First.java",sha256("first\n"),"first","agent first","reason",emptyList()),
            ReplaceText("TeamCode/Second.java",sha256("second\n"),"second","agent second","reason",emptyList())
        ))))
        failUndo=true

        val failure=assertThrows(FileEditApplyException::class.java) { history.undo() }

        assertEquals("synthetic invariant failure",failure.originalFailure.message)
        assertTrue(failure.rollbackFailures.isEmpty())
        assertTrue(failure.cleanupFailures.isEmpty())
        assertEquals("agent first\n",Files.readString(first))
        assertEquals("external non-race change\n",Files.readString(second))
        assertEquals(2,history.changes().size)
    }

    @Test
    fun `external IDE change between batches causes zero untracked writes and leaves coherent retry state`(@TempDir root:Path) {
        val file=root.resolve("TeamCode/Drive.java")
        Files.createDirectories(file.parent)
        Files.writeString(file,"baseline\n")
        val engine=FileEditEngine(root)
        val history=EditHistory(root,engine)
        history.applyAndRecord(engine.preview(EditPlan("first",listOf(
            ReplaceText("TeamCode/Drive.java",sha256("baseline\n"),"baseline","agent one","reason",emptyList())
        ))))
        Files.writeString(file,"IDE between batches\n")
        val staleHistoryCandidate=engine.preview(EditPlan("second",listOf(
            ReplaceText(
                "TeamCode/Drive.java",sha256("IDE between batches\n"),
                "IDE between batches","agent two","reason",emptyList()
            )
        )))

        assertThrows(IllegalArgumentException::class.java) {
            history.applyAndRecord(staleHistoryCandidate)
        }

        assertEquals("IDE between batches\n",Files.readString(file))
        assertEquals("agent one\n",history.changes().single().after)

        Files.writeString(file,"agent one\n")
        history.applyAndRecord(engine.preview(EditPlan("retry",listOf(
            ReplaceText("TeamCode/Drive.java",sha256("agent one\n"),"agent one","agent two","reason",emptyList())
        ))))
        assertEquals("agent two\n",Files.readString(file))
        assertEquals("agent two\n",history.changes().single().after)
    }

    @Test
    fun `caller mutation during apply cannot add a late history change`(@TempDir root:Path) {
        Files.createDirectories(root.resolve("TeamCode"))
        val stable=PlannedFileChange(
            "TeamCode/Stable.java",FileSnapshot.Missing,
            FileSnapshot.Text("class Stable {}\n",sha256("class Stable {}\n")),EditScope.NORMAL
        )
        val callerChanges=mutableListOf(stable)
        val engine=FileEditEngine(root,beforeWrite={ _,_->
            callerChanges+=PlannedFileChange(
                ".env",FileSnapshot.Missing,
                FileSnapshot.Text("late=value\n",sha256("late=value\n")),EditScope.PROJECT_LEVEL
            )
        })
        val history=EditHistory(root,engine)

        history.applyAndRecord(ValidatedEditBatch("mutable caller",callerChanges))

        assertEquals(setOf("TeamCode/Stable.java"),history.changes().mapTo(linkedSetOf()) { it.path })
        assertEquals("class Stable {}\n",Files.readString(root.resolve("TeamCode/Stable.java")))
        assertTrue(Files.notExists(root.resolve(".env")))
        assertEquals(2,callerChanges.size)
    }

    @Test
    fun `invalid later change is rejected before filesystem or history mutation`(@TempDir root:Path) {
        val drive=root.resolve("TeamCode/Drive.java")
        Files.createDirectories(drive.parent)
        Files.writeString(drive,"baseline\n")
        val engine=FileEditEngine(root)
        val history=EditHistory(root,engine)
        history.applyAndRecord(engine.preview(EditPlan("first",listOf(
            ReplaceText("TeamCode/Drive.java",sha256("baseline\n"),"baseline","agent one","reason",emptyList())
        ))))
        val candidate=ValidatedEditBatch("invalid later",listOf(
            PlannedFileChange(
                "TeamCode/Other.java",FileSnapshot.Missing,
                FileSnapshot.Text("class Other {}\n",sha256("class Other {}\n")),EditScope.NORMAL
            ),
            PlannedFileChange(
                "TeamCode/Drive.java",
                FileSnapshot.Text("baseline\n",sha256("baseline\n")),
                FileSnapshot.Text("agent two\n",sha256("agent two\n")),EditScope.NORMAL
            )
        ))

        assertThrows(IllegalArgumentException::class.java) { history.applyAndRecord(candidate) }

        assertTrue(Files.notExists(root.resolve("TeamCode/Other.java")))
        assertEquals("agent one\n",Files.readString(drive))
        assertEquals(
            listOf(org.ftckb.git.TextChange("TeamCode/Drive.java","baseline\n","agent one\n",false)),
            history.changes()
        )
    }

    private fun replaceBatch(engine:FileEditEngine,before:String,after:String):ValidatedEditBatch=
        engine.preview(EditPlan("edit",listOf(
            ReplaceText(
                "TeamCode/Drive.java",sha256(before),before.trimEnd(),after.trimEnd(),"reason",emptyList()
            )
        )))

    private fun sha256(value:String):String=MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private class TestAuthorizationException:RuntimeException()
}
