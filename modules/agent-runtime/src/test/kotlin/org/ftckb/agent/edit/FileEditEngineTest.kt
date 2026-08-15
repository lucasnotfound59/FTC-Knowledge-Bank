package org.ftckb.agent.edit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.util.AbstractList

class FileEditEngineTest {
    @Test
    fun `edits a file that was already dirty`(@TempDir root:Path) {
        val file=root.resolve("TeamCode/Test.java")
        Files.createDirectories(file.parent)
        Files.writeString(file,"user change\n")
        val engine=FileEditEngine(root)
        val batch=engine.preview(EditPlan("edit",listOf(
            ReplaceText(
                "TeamCode/Test.java",sha256("user change\n"),"user change","user + agent","reason",emptyList()
            )
        )))

        val applied=engine.apply(batch)

        assertEquals("user + agent\n",Files.readString(file))
        assertEquals(batch.summary,applied.summary)
        assertEquals(batch.changes,applied.changes)
    }

    @Test
    fun `validates and applies create replace delete and move as one batch`(@TempDir root:Path) {
        val teamCode=root.resolve("TeamCode")
        Files.createDirectories(teamCode)
        Files.writeString(teamCode.resolve("Existing.java"),"old\n")
        Files.writeString(teamCode.resolve("Delete.java"),"delete\n")
        Files.writeString(teamCode.resolve("Move.java"),"move\n")
        val engine=FileEditEngine(root)
        val batch=engine.preview(EditPlan("all operations",listOf(
            CreateText("TeamCode/New.java",true,"new\n","reason",emptyList()),
            ReplaceText("TeamCode/Existing.java",sha256("old\n"),"old","changed","reason",emptyList()),
            DeleteText("TeamCode/Delete.java",sha256("delete\n"),"reason",emptyList()),
            MoveText("TeamCode/Move.java","TeamCode/Moved.java",sha256("move\n"),true,"reason",emptyList())
        )))

        engine.apply(batch)

        assertEquals(5,batch.changes.size)
        assertEquals("new\n",Files.readString(teamCode.resolve("New.java")))
        assertEquals("changed\n",Files.readString(teamCode.resolve("Existing.java")))
        assertFalse(Files.exists(teamCode.resolve("Delete.java")))
        assertFalse(Files.exists(teamCode.resolve("Move.java")))
        assertEquals("move\n",Files.readString(teamCode.resolve("Moved.java")))
        assertTrue(batch.changes.all { it.scope==EditScope.NORMAL })
    }

    @Test
    fun `restores byte exact originals and removes temporary files after a later write fails`(@TempDir root:Path) {
        val teamCode=root.resolve("TeamCode")
        Files.createDirectories(teamCode)
        val first=teamCode.resolve("First.java")
        val second=teamCode.resolve("Second.java")
        val firstBytes="first user bytes\r\n".toByteArray(StandardCharsets.UTF_8)
        val secondBytes="second user bytes\n".toByteArray(StandardCharsets.UTF_8)
        Files.write(first,firstBytes)
        Files.write(second,secondBytes)
        val engine=FileEditEngine(root) { _,writeNumber ->
            if (writeNumber==2) throw IOException("injected second write failure")
        }
        val secondFileKey=Files.readAttributes(second,BasicFileAttributes::class.java).fileKey()
        val batch=engine.preview(EditPlan("rollback",listOf(
            ReplaceText("TeamCode/First.java",sha256(firstBytes),"first","agent first","reason",emptyList()),
            ReplaceText("TeamCode/Second.java",sha256(secondBytes),"second","agent second","reason",emptyList())
        )))

        val failure=assertThrows<FileEditApplyException> { engine.apply(batch) }

        assertEquals("injected second write failure",failure.originalFailure.message)
        assertTrue(failure.rollbackFailures.isEmpty())
        assertArrayEquals(firstBytes,Files.readAllBytes(first))
        assertArrayEquals(secondBytes,Files.readAllBytes(second))
        if (secondFileKey!=null) {
            assertEquals(secondFileKey,Files.readAttributes(second,BasicFileAttributes::class.java).fileKey())
        }
        Files.list(teamCode).use { files ->
            assertTrue(files.noneMatch { it.fileName.toString().contains(".ftckb-") })
        }
    }

    @Test
    fun `validates the complete virtual batch before creating any file`(@TempDir root:Path) {
        val teamCode=root.resolve("TeamCode")
        Files.createDirectories(teamCode)
        Files.writeString(teamCode.resolve("Existing.java"),"current\n")
        val engine=FileEditEngine(root)
        val plan=EditPlan("invalid later operation",listOf(
            CreateText("TeamCode/New.java",true,"new\n","reason",emptyList()),
            ReplaceText("TeamCode/Existing.java",sha256("stale\n"),"current","changed","reason",emptyList())
        ))

        assertThrows<IllegalArgumentException> { engine.preview(plan) }

        assertFalse(Files.exists(teamCode.resolve("New.java")))
        assertEquals("current\n",Files.readString(teamCode.resolve("Existing.java")))
    }

    @Test
    fun `rejects binary and malformed UTF 8 sources`(@TempDir root:Path) {
        val teamCode=root.resolve("TeamCode")
        Files.createDirectories(teamCode)
        val nul=byteArrayOf('a'.code.toByte(),0,'b'.code.toByte())
        val malformed=byteArrayOf(0xc3.toByte(),0x28)
        Files.write(teamCode.resolve("Nul.java"),nul)
        Files.write(teamCode.resolve("Malformed.java"),malformed)
        val engine=FileEditEngine(root)

        assertThrows<IllegalArgumentException> {
            engine.preview(replacePlan("TeamCode/Nul.java",nul,"a","b"))
        }
        assertThrows<IllegalArgumentException> {
            engine.preview(replacePlan("TeamCode/Malformed.java",malformed,"a","b"))
        }
    }

    @Test
    fun `requires a unique replacement and absent create and move destinations`(@TempDir root:Path) {
        val teamCode=root.resolve("TeamCode")
        Files.createDirectories(teamCode)
        Files.writeString(teamCode.resolve("Repeated.java"),"same same\n")
        Files.writeString(teamCode.resolve("Source.java"),"source\n")
        Files.writeString(teamCode.resolve("Exists.java"),"exists\n")
        val engine=FileEditEngine(root)

        assertThrows<IllegalArgumentException> {
            engine.preview(replacePlan("TeamCode/Repeated.java","same same\n".toByteArray(),"same","changed"))
        }
        assertThrows<IllegalArgumentException> {
            engine.preview(EditPlan("create",listOf(
                CreateText("TeamCode/Exists.java",true,"new\n","reason",emptyList())
            )))
        }
        assertThrows<IllegalArgumentException> {
            engine.preview(EditPlan("move",listOf(
                MoveText("TeamCode/Source.java","TeamCode/Exists.java",sha256("source\n"),true,"reason",emptyList())
            )))
        }
    }

    @Test
    fun `rechecks all preconditions immediately before apply writes`(@TempDir root:Path) {
        val teamCode=root.resolve("TeamCode")
        Files.createDirectories(teamCode)
        val first=teamCode.resolve("First.java")
        val second=teamCode.resolve("Second.java")
        Files.writeString(first,"first\n")
        Files.writeString(second,"second\n")
        val engine=FileEditEngine(root)
        val batch=engine.preview(EditPlan("race",listOf(
            ReplaceText("TeamCode/First.java",sha256("first\n"),"first","changed first","reason",emptyList()),
            ReplaceText("TeamCode/Second.java",sha256("second\n"),"second","changed second","reason",emptyList())
        )))
        Files.writeString(second,"concurrent\n")

        assertThrows<IllegalArgumentException> { engine.apply(batch) }

        assertEquals("first\n",Files.readString(first))
        assertEquals("concurrent\n",Files.readString(second))
        Files.list(teamCode).use { files ->
            assertTrue(files.noneMatch { it.fileName.toString().contains(".ftckb-") })
        }
    }

    @Test
    fun `rejects a parent replaced by an outside link before apply`(@TempDir container:Path) {
        val root=container.resolve("repo")
        val teamCode=root.resolve("TeamCode")
        val movedTeamCode=root.resolve("OriginalTeamCode")
        val outside=container.resolve("outside")
        Files.createDirectories(teamCode)
        Files.createDirectories(outside)
        Files.writeString(teamCode.resolve("Test.java"),"before\n")
        Files.writeString(outside.resolve("Test.java"),"outside\n")
        val engine=FileEditEngine(root)
        val batch=engine.preview(replacePlan("TeamCode/Test.java","before\n".toByteArray(),"before","after"))
        Files.move(teamCode,movedTeamCode)
        Files.createSymbolicLink(teamCode,outside)

        assertThrows<IllegalArgumentException> { engine.apply(batch) }
        assertEquals("before\n",Files.readString(movedTeamCode.resolve("Test.java")))
        assertEquals("outside\n",Files.readString(outside.resolve("Test.java")))
    }

    @Test
    fun `ancestor swap after final validation cannot escape or leave a partial batch`(@TempDir container:Path) {
        val root=container.resolve("repo")
        val teamCode=root.resolve("TeamCode")
        val parked=root.resolve("ParkedTeamCode")
        val outside=container.resolve("outside")
        Files.createDirectories(teamCode.resolve("nested"))
        Files.createDirectories(outside.resolve("nested"))
        Files.writeString(root.resolve("build.gradle"),"first\n")
        Files.writeString(teamCode.resolve("nested/Second.java"),"second\n")
        Files.writeString(outside.resolve("outside.marker"),"outside\n")
        val engine=FileEditEngine(root) { _,writeNumber ->
            if (writeNumber==2) {
                Files.move(teamCode,parked)
                Files.createSymbolicLink(teamCode,outside)
            }
        }
        val batch=engine.preview(EditPlan("race",listOf(
            ReplaceText("build.gradle",sha256("first\n"),"first","changed first","reason",emptyList()),
            ReplaceText(
                "TeamCode/nested/Second.java",sha256("second\n"),"second","changed second","reason",emptyList()
            )
        )))

        assertThrows<FileEditApplyException> { engine.apply(batch) }

        assertEquals("first\n",Files.readString(root.resolve("build.gradle")))
        assertEquals("second\n",Files.readString(parked.resolve("nested/Second.java")))
        assertEquals("outside\n",Files.readString(outside.resolve("outside.marker")))
        assertNoEditTemporaryFiles(parked)
        assertNoEditTemporaryFiles(outside)
    }

    @Test
    fun `destination created during apply is preserved and prior writes roll back`(@TempDir root:Path) {
        val teamCode=root.resolve("TeamCode")
        Files.createDirectories(teamCode)
        Files.writeString(root.resolve("build.gradle"),"first\n")
        val destination=teamCode.resolve("Created.java")
        val engine=FileEditEngine(root) { _,writeNumber ->
            if (writeNumber==2) Files.writeString(destination,"concurrent\n")
        }
        val batch=engine.preview(EditPlan("race",listOf(
            ReplaceText("build.gradle",sha256("first\n"),"first","changed first","reason",emptyList()),
            CreateText("TeamCode/Created.java",true,"batch\n","reason",emptyList())
        )))

        assertThrows<FileEditApplyException> { engine.apply(batch) }

        assertEquals("first\n",Files.readString(root.resolve("build.gradle")))
        assertEquals("concurrent\n",Files.readString(destination))
        assertNoEditTemporaryFiles(root)
    }

    @Test
    fun `apply snapshots a hostile changing batch list exactly once`(@TempDir root:Path) {
        val teamCode=root.resolve("TeamCode")
        Files.createDirectories(teamCode)
        Files.writeString(teamCode.resolve("First.java"),"first\n")
        Files.writeString(teamCode.resolve("Second.java"),"second\n")
        val changes=listOf(
            PlannedFileChange(
                "TeamCode/First.java",FileSnapshot.Text("first\n",sha256("first\n")),
                FileSnapshot.Text("changed first\n",sha256("changed first\n")),EditScope.NORMAL
            ),
            PlannedFileChange(
                "TeamCode/Second.java",FileSnapshot.Text("second\n",sha256("second\n")),
                FileSnapshot.Text("changed second\n",sha256("changed second\n")),EditScope.NORMAL
            )
        )
        val hostile=ChangingIterationList(changes)

        val applied=FileEditEngine(root).apply(ValidatedEditBatch("hostile",hostile))

        assertEquals(1,hostile.iteratorCalls)
        assertEquals("changed first\n",Files.readString(teamCode.resolve("First.java")))
        assertEquals("changed second\n",Files.readString(teamCode.resolve("Second.java")))
        assertEquals(2,applied.changes.size)
        @Suppress("UNCHECKED_CAST")
        assertThrows<UnsupportedOperationException> { (applied.changes as MutableList<PlannedFileChange>).clear() }
    }

    @Test
    fun `enforces file count per result and aggregate byte limits`(@TempDir root:Path) {
        val teamCode=root.resolve("TeamCode")
        Files.createDirectories(teamCode)
        val engine=FileEditEngine(root)
        assertThrows<IllegalArgumentException> {
            engine.preview(EditPlan("large file",listOf(
                CreateText("TeamCode/Large.java",true,"x".repeat(1_048_577),"reason",emptyList())
            )))
        }
        assertThrows<IllegalArgumentException> {
            engine.preview(EditPlan("large batch",(1..5).map { index ->
                CreateText("TeamCode/Large$index.java",true,"x".repeat(900_000),"reason",emptyList())
            }))
        }
        (1..13).forEach { index -> Files.writeString(teamCode.resolve("Source$index.java"),"source $index\n") }
        assertThrows<IllegalArgumentException> {
            engine.preview(EditPlan("too many files",(1..13).map { index ->
                val content="source $index\n"
                MoveText(
                    "TeamCode/Source$index.java","TeamCode/Destination$index.java",sha256(content),true,"reason",emptyList()
                )
            }))
        }
    }

    @Test
    fun `preserves POSIX permissions when replacing a file`(@TempDir root:Path) {
        val teamCode=root.resolve("TeamCode")
        Files.createDirectories(teamCode)
        val file=teamCode.resolve("Permissions.java")
        Files.writeString(file,"before\n")
        if (!Files.getFileStore(file).supportsFileAttributeView("posix")) return
        val permissions=setOf(
            PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_WRITE,PosixFilePermission.GROUP_READ
        )
        Files.setPosixFilePermissions(file,permissions)
        val engine=FileEditEngine(root)

        engine.apply(engine.preview(replacePlan("TeamCode/Permissions.java","before\n".toByteArray(),"before","after")))

        assertEquals(permissions,Files.getPosixFilePermissions(file))
    }

    @Test
    fun `preserves each move source mode without transferring an unrelated deleted mode`(@TempDir root:Path) {
        val teamCode=root.resolve("TeamCode")
        Files.createDirectories(teamCode)
        val first=teamCode.resolve("First.java")
        val second=teamCode.resolve("Second.java")
        val deleted=teamCode.resolve("Deleted.java")
        Files.writeString(first,"identical\n")
        Files.writeString(second,"identical\n")
        Files.writeString(deleted,"created content\n")
        if (!Files.getFileStore(first).supportsFileAttributeView("posix")) return
        val firstMode=setOf(PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_WRITE)
        val secondMode=setOf(PosixFilePermission.OWNER_READ,PosixFilePermission.GROUP_READ)
        val unrelatedMode=PosixFilePermission.entries.toSet()
        Files.setPosixFilePermissions(first,firstMode)
        Files.setPosixFilePermissions(second,secondMode)
        Files.setPosixFilePermissions(deleted,unrelatedMode)
        val engine=FileEditEngine(root)
        val batch=engine.preview(EditPlan("permissions",listOf(
            MoveText("TeamCode/First.java","TeamCode/MovedFirst.java",sha256("identical\n"),true,"reason",emptyList()),
            MoveText("TeamCode/Second.java","TeamCode/MovedSecond.java",sha256("identical\n"),true,"reason",emptyList()),
            DeleteText("TeamCode/Deleted.java",sha256("created content\n"),"reason",emptyList()),
            CreateText("TeamCode/Created.java",true,"created content\n","reason",emptyList())
        )))

        engine.apply(batch)

        assertEquals(firstMode,Files.getPosixFilePermissions(teamCode.resolve("MovedFirst.java")))
        assertEquals(secondMode,Files.getPosixFilePermissions(teamCode.resolve("MovedSecond.java")))
        assertFalse(Files.getPosixFilePermissions(teamCode.resolve("Created.java")).contains(PosixFilePermission.OWNER_EXECUTE))
    }

    @Test
    fun `permission origins compose through move replace and create chains`(@TempDir root:Path) {
        val teamCode=root.resolve("TeamCode")
        Files.createDirectories(teamCode)
        val chainSource=teamCode.resolve("ChainA.java")
        val replaceSource=teamCode.resolve("ReplaceA.java")
        Files.writeString(chainSource,"identical\n")
        Files.writeString(replaceSource,"identical\n")
        if (!Files.getFileStore(chainSource).supportsFileAttributeView("posix")) return
        val chainMode=setOf(
            PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_WRITE,PosixFilePermission.OWNER_EXECUTE
        )
        val replaceMode=setOf(PosixFilePermission.OWNER_READ,PosixFilePermission.GROUP_READ)
        Files.setPosixFilePermissions(chainSource,chainMode)
        Files.setPosixFilePermissions(replaceSource,replaceMode)
        val engine=FileEditEngine(root)
        val batch=engine.preview(EditPlan("permission chains",listOf(
            MoveText("TeamCode/ChainA.java","TeamCode/ChainB.java",sha256("identical\n"),true,"reason",emptyList()),
            MoveText("TeamCode/ChainB.java","TeamCode/ChainC.java",sha256("identical\n"),true,"reason",emptyList()),
            ReplaceText(
                "TeamCode/ReplaceA.java",sha256("identical\n"),"identical","replaced","reason",emptyList()
            ),
            MoveText(
                "TeamCode/ReplaceA.java","TeamCode/ReplaceB.java",sha256("replaced\n"),true,"reason",emptyList()
            ),
            CreateText("TeamCode/CreateA.java",true,"created\n","reason",emptyList()),
            MoveText("TeamCode/CreateA.java","TeamCode/CreateB.java",sha256("created\n"),true,"reason",emptyList())
        )))

        engine.apply(batch)

        assertEquals(chainMode,Files.getPosixFilePermissions(teamCode.resolve("ChainC.java")))
        assertEquals(replaceMode,Files.getPosixFilePermissions(teamCode.resolve("ReplaceB.java")))
        assertFalse(Files.getPosixFilePermissions(teamCode.resolve("CreateB.java")).contains(PosixFilePermission.OWNER_EXECUTE))
    }

    @Test
    fun `rejects forged validated snapshots before writing`(@TempDir root:Path) {
        val teamCode=root.resolve("TeamCode")
        Files.createDirectories(teamCode)
        val file=teamCode.resolve("Forged.java")
        Files.writeString(file,"before\n")
        val before=FileSnapshot.Text("before\n",sha256("before\n"))
        val forgedAfter=FileSnapshot.Text("after\n","0".repeat(64))
        val batch=ValidatedEditBatch(
            "forged",listOf(PlannedFileChange("TeamCode/Forged.java",before,forgedAfter,EditScope.NORMAL))
        )

        assertThrows<IllegalArgumentException> { FileEditEngine(root).apply(batch) }
        assertEquals("before\n",Files.readString(file))
    }

    private fun sha256(text:String):String=MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun sha256(bytes:ByteArray):String=MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private fun replacePlan(path:String,bytes:ByteArray,oldText:String,newText:String):EditPlan=
        EditPlan("replace",listOf(ReplaceText(path,sha256(bytes),oldText,newText,"reason",emptyList())))

    private fun assertNoEditTemporaryFiles(root:Path) {
        Files.walk(root).use { files ->
            assertTrue(files.noneMatch { it.fileName.toString().contains(".ftckb-") })
        }
    }

    private class ChangingIterationList(
        private val values:List<PlannedFileChange>
    ):AbstractList<PlannedFileChange>() {
        var iteratorCalls=0
            private set

        override val size:Int
            get()=values.size

        override fun get(index:Int):PlannedFileChange=values[index]

        override fun iterator():MutableIterator<PlannedFileChange> {
            iteratorCalls++
            val selected=if (iteratorCalls==1) values else values.take(1)
            return selected.toMutableList().iterator()
        }
    }
}
