package org.ftckb.repository

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class RepositoryIndexTest {
    @TempDir
    lateinit var tempDir:Path

    @Test
    fun `indexes only safe nonignored approved text files`() {
        write(".gitignore","ignored/\n*.java\n!Keep.java\n")
        write("Keep.java","class Keep { void driveRobot() {} }")
        write("Other.java","class Other {}")
        write("ignored/Visible.java","class Visible {}")
        write("nested/.gitignore","*.kt\n!Allowed.kt\n")
        write("nested/Blocked.kt","class Blocked")
        write("nested/Allowed.kt","class Allowed")
        write(".git/History.java","class History {}")
        write(".gradle/Cache.java","class Cache {}")
        write("build/Generated.java","class Generated {}")
        write(".env","secret=value")
        write("local.properties","sdk.dir=/private")
        write("arbitrary.kts","println(\"not an approved Gradle script\")")
        write("nul.java",byteArrayOf(0x41,0x00,0x42))
        write("large.md","x".repeat(1_048_577))
        val outside=Files.createTempFile("repository-index-outside",".java")
        outside.writeText("class Outside {}")
        try {
            Files.createSymbolicLink(tempDir.resolve("outside.java"),outside)

            val snapshot=RepositoryIndex().build(tempDir)

            assertEquals(setOf("Keep.java","nested/Allowed.kt"),snapshot.documents.keys)
        assertFalse(snapshot.documents.containsKey(".env"))
        assertFalse(snapshot.documents.containsKey("arbitrary.kts"))
            assertFalse(snapshot.documents.containsKey("outside.java"))
        } finally {
            Files.deleteIfExists(outside)
        }
    }

    @Test
    fun `ranks exact path and symbols above lexical matches and refreshes changed files`() {
        write("Alpha.java","class Alpha { void drive() {} }")
        write("Keep.java","class Keep { void driveRobot() { driveRobot(); } }")
        write("zeta/Term.java","class Term { void drive() {} }")
        val index=RepositoryIndex()
        index.build(tempDir)

        val bySymbol=index.search(LocalQuery(setOf("drive"),setOf("driveRobot")),10)
        val byPath=index.search(LocalQuery(setOf("drive"),pathGlobs=setOf("zeta/Term.java")),10)

        assertEquals("Keep.java",bySymbol.first().path)
        assertEquals("zeta/Term.java",byPath.first().path)
        assertTrue(bySymbol.all { it.endLine-it.startLine<80 })

        write("Keep.java","class Keep { void refreshedSymbol() {} }")
        val refreshed=index.refresh(setOf("Keep.java"))
        assertTrue("refreshedsymbol" in refreshed.documents.getValue("Keep.java").terms)
        assertEquals("Keep.java",index.search(LocalQuery(setOf("refreshedSymbol")),10).first().path)
    }

    @Test
    fun `does not apply nested negation within an ignored parent during refresh`() {
        write(".gitignore","ignored/\n")
        write("ignored/.gitignore","!reinclude.java\n")
        write("ignored/reinclude.java","class Reinclude {}")
        val index=RepositoryIndex()
        val initial=index.build(tempDir)

        val refreshed=index.refresh(setOf("ignored/reinclude.java"))

        assertFalse(initial.documents.containsKey("ignored/reinclude.java"))
        assertFalse(refreshed.documents.containsKey("ignored/reinclude.java"))
    }

    @Test
    fun `caps lexical relevance below exact symbol and path relevance`() {
        val terms=(1..10_000).map { "term$it" }.toSet()
        write("Terms.java",terms.joinToString(" "))
        write("Symbol.java","class Symbol { void exactSymbol() {} }")
        write("path/Match.java","class Match {}")
        val index=RepositoryIndex()
        index.build(tempDir)

        val symbolResults=index.search(LocalQuery(terms,setOf("exactSymbol")),10)
        val pathResults=index.search(LocalQuery(terms,pathGlobs=setOf("path/Match.java")),10)

        assertEquals("Symbol.java",symbolResults.first().path)
        assertEquals("path/Match.java",pathResults.first().path)
    }

    @Test
    fun `refresh rejects internal and external intermediate symlinks`() {
        write("actual/Inside.java","class Inside {}")
        val outside=Files.createTempDirectory("repository-index-outside")
        outside.resolve("Outside.java").writeText("class Outside {}")
        try {
            Files.createSymbolicLink(tempDir.resolve("inside-link"),tempDir.resolve("actual"))
            Files.createSymbolicLink(tempDir.resolve("outside-link"),outside)
            val index=RepositoryIndex()
            index.build(tempDir)

            val refreshed=index.refresh(setOf("inside-link/Inside.java","outside-link/Outside.java"))

            assertFalse(refreshed.documents.containsKey("inside-link/Inside.java"))
            assertFalse(refreshed.documents.containsKey("outside-link/Outside.java"))
            assertTrue(refreshed.documents.containsKey("actual/Inside.java"))
        } finally {
            Files.deleteIfExists(outside.resolve("Outside.java"))
            Files.deleteIfExists(outside)
        }
    }

    @Test
    fun `current hashing never follows a replaced leaf symlink`() {
        write("Drive.java","class Drive {}")
        val index=RepositoryIndex()
        index.build(tempDir)
        val outside=Files.createTempFile("repository-current-hash-outside",".java")
        outside.writeText("class Outside {}")
        try {
            Files.delete(tempDir.resolve("Drive.java"))
            Files.createSymbolicLink(tempDir.resolve("Drive.java"),outside)

            assertEquals(null,index.currentSha256("Drive.java"))
        } finally {
            Files.deleteIfExists(outside)
        }
    }

    @Test
    fun `enforces aggregate file byte and depth limits`() {
        val fileRoot=Files.createDirectories(tempDir.resolve("file-limit"))
        Files.writeString(fileRoot.resolve("A.java"),"class A {}")
        Files.writeString(fileRoot.resolve("B.java"),"class B {}")
        assertThrows(RepositoryTraversalException::class.java) {
            RepositoryIndex(RepositoryTraversalLimits(maxFiles=1)).build(fileRoot)
        }

        val byteRoot=Files.createDirectories(tempDir.resolve("byte-limit"))
        Files.writeString(byteRoot.resolve("Large.java"),"class Large {}")
        assertThrows(RepositoryTraversalException::class.java) {
            RepositoryIndex(RepositoryTraversalLimits(maxTotalBytes=8)).build(byteRoot)
        }

        val depthRoot=Files.createDirectories(tempDir.resolve("depth-limit"))
        Files.createDirectories(depthRoot.resolve("one/two"))
        Files.writeString(depthRoot.resolve("one/two/Deep.java"),"class Deep {}")
        assertThrows(RepositoryTraversalException::class.java) {
            RepositoryIndex(RepositoryTraversalLimits(maxDepth=1)).build(depthRoot)
        }
    }

    @Test
    fun `skips an inaccessible repository entry and continues deterministically`() {
        val inaccessible=tempDir.resolve("Blocked.java")
        write("Blocked.java","class Blocked {}")
        write("Readable.java","class Readable {}")
        assumeTrue(Files.getFileStore(tempDir).supportsFileAttributeView("posix"))
        Files.setPosixFilePermissions(inaccessible,emptySet<PosixFilePermission>())
        val reallyInaccessible=runCatching { Files.newByteChannel(inaccessible).use { } }.isFailure
        assumeTrue(reallyInaccessible,"filesystem still permits reading mode 000 files")
        try {
            val snapshot=RepositoryIndex().build(tempDir)

            assertEquals(setOf("Readable.java"),snapshot.documents.keys)
        } finally {
            Files.setPosixFilePermissions(inaccessible,setOf(PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_WRITE))
        }
    }

    @Test
    fun `published repository collections reject mutation through mutable casts`() {
        write("TeamCode/build.gradle","implementation 'org.firstinspires.ftc:RobotCore:9.0.1'")
        write("TeamCode/src/main/java/Drive.java","@TeleOp class Drive {}")
        write("settings.gradle","include ':TeamCode'")
        val snapshot=RepositoryIndex().build(tempDir)

        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (snapshot.profile.sourceModules as MutableSet<String>).add("mutated")
        }
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (snapshot.documents as MutableMap<String,IndexedDocument>)["mutated.java"]=snapshot.documents.getValue("TeamCode/build.gradle")
        }
        assertFalse(snapshot.profile.sourceModules.contains("mutated"))
        assertFalse(snapshot.documents.containsKey("mutated.java"))
    }

    @Test
    fun `public model constructors make defensive immutable collection copies`() {
        val sourceModules=linkedSetOf("TeamCode")
        val markers=mutableListOf(ProjectMarker(ProjectMarkerKind.TEAMCODE_MODULE,"TeamCode","module"))
        val profile=FtcProjectProfile(true,sourceModules,markers)
        val documents=linkedMapOf(
            "Drive.java" to IndexedDocument("Drive.java","hash","class Drive",listOf("class Drive"),setOf("drive"))
        )
        val snapshot=RepositorySnapshot(tempDir,profile,documents)
        val lines=mutableListOf("class Drive")
        val terms=mutableSetOf("drive")
        val document=IndexedDocument("Drive.java","hash","class Drive",lines,terms)

        sourceModules.add("mutated")
        markers.clear()
        documents.clear()
        lines.add("mutated")
        terms.add("mutated")

        assertEquals(setOf("TeamCode"),profile.sourceModules)
        assertEquals(1,profile.markers.size)
        assertEquals(setOf("Drive.java"),snapshot.documents.keys)
        assertEquals(listOf("class Drive"),document.lines)
        assertEquals(setOf("drive"),document.terms)
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (profile.sourceModules as MutableSet<String>).add("cast-mutated")
        }
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (snapshot.documents as MutableMap<String,IndexedDocument>).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (document.lines as MutableList<String>).add("cast-mutated")
        }
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (document.terms as MutableSet<String>).add("cast-mutated")
        }
    }

    private fun write(path:String,text:String) {
        val file=tempDir.resolve(path)
        file.parent.createDirectories()
        file.writeText(text)
    }

    private fun write(path:String,bytes:ByteArray) {
        val file=tempDir.resolve(path)
        file.parent.createDirectories()
        file.writeBytes(bytes)
    }
}
