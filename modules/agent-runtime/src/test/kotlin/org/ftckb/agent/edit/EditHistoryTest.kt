package org.ftckb.agent.edit

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import org.junit.jupiter.api.Assertions.assertEquals
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

        history.record(applyReplace(engine,"user dirty\n","agent one\n"))
        history.record(applyReplace(engine,"agent one\n","agent two\n"))

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

        history.record(applyReplace(engine,"user dirty\r\nwith spacing  \n","agent one\r\nwith spacing  \n"))
        history.record(applyReplace(engine,"agent one\r\nwith spacing  \n","agent two\r\nwith spacing  \n"))

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
        val applied=engine.apply(engine.preview(EditPlan("mixed",listOf(
            CreateText("TeamCode/Created.java",true,"created\n","create",emptyList()),
            MoveText("TeamCode/Source.java","TeamCode/Moved.java",sha256("source\n"),true,"move",emptyList()),
            DeleteText("TeamCode/Deleted.java",sha256("deleted\n"),"delete",emptyList())
        ))))

        history.record(applied)

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
        history.record(engine.apply(engine.preview(EditPlan("both",listOf(
            ReplaceText("TeamCode/First.java",sha256("first\n"),"first","agent first","reason",emptyList()),
            ReplaceText("TeamCode/Second.java",sha256("second\n"),"second","agent second","reason",emptyList())
        )))))
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
    fun `discard conflict performs zero writes and retains first-touch state for retry`(@TempDir root:Path) {
        val teamCode=root.resolve("TeamCode")
        Files.createDirectories(teamCode)
        val first=teamCode.resolve("First.java")
        val second=teamCode.resolve("Second.java")
        Files.writeString(first,"first dirty\n")
        Files.writeString(second,"second dirty\n")
        val engine=FileEditEngine(root)
        val history=EditHistory(root,engine)
        history.record(engine.apply(engine.preview(EditPlan("both",listOf(
            ReplaceText("TeamCode/First.java",sha256("first dirty\n"),"first dirty","agent first","reason",emptyList()),
            ReplaceText("TeamCode/Second.java",sha256("second dirty\n"),"second dirty","agent second","reason",emptyList())
        )))))
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
        val applyingEngine=FileEditEngine(root)
        val applied=applyingEngine.apply(applyingEngine.preview(EditPlan("both",listOf(
            ReplaceText("TeamCode/First.java",sha256("first\n"),"first","agent first","reason",emptyList()),
            ReplaceText("TeamCode/Second.java",sha256("second\n"),"second","agent second","reason",emptyList())
        ))))
        val racingEngine=FileEditEngine(root,beforeWrite={ _,writeNumber ->
            if (writeNumber==2) Files.writeString(second,"IDE race\n")
        })
        val history=EditHistory(root,racingEngine)
        history.record(applied)

        val result=history.undo()

        assertEquals(setOf("TeamCode/Second.java"),result.conflicts)
        assertEquals("agent first\n",Files.readString(first))
        assertEquals("IDE race\n",Files.readString(second))
        assertEquals(2,history.changes().size)
    }

    private fun applyReplace(engine:FileEditEngine,before:String,after:String):AppliedEditBatch=engine.apply(
        engine.preview(EditPlan("edit",listOf(
            ReplaceText(
                "TeamCode/Drive.java",sha256(before),before.trimEnd(),after.trimEnd(),"reason",emptyList()
            )
        )))
    )

    private fun sha256(value:String):String=MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
