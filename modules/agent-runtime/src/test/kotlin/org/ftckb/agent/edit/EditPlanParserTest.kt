package org.ftckb.agent.edit

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EditPlanParserTest {
    @Test
    fun `parses a replace operation`() {
        val plan=EditPlanParser.parse("""
            {"summary":"Guard result.","operations":[{
              "kind":"replace",
              "path":"TeamCode/src/main/java/example/Vision.java",
              "expectedSha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
              "oldText":"use(result);",
              "newText":"if(result!=null && result.isValid()) use(result);",
              "reason":"Avoid an absent result.",
              "citations":["CODE:C1","RULE:R1"]
            }]}
        """.trimIndent())

        assertTrue(plan.operations.single() is ReplaceText)
    }

    @Test
    fun `parses create delete and move operations`() {
        val plan=EditPlanParser.parse(plan(
            create("TeamCode/New.java"),
            delete("TeamCode/Old.java"),
            move("TeamCode/Before.java","TeamCode/After.java")
        ))

        assertTrue(plan.operations[0] is CreateText)
        assertTrue(plan.operations[1] is DeleteText)
        assertTrue(plan.operations[2] is MoveText)
    }

    @Test
    fun `rejects unknown fields and missing preconditions`() {
        assertInvalid(plan(create("TeamCode/New.java",",\"extra\":true")))
        assertInvalid(plan("""
            {"kind":"replace","path":"TeamCode/Test.java","oldText":"old","newText":"new",
            "reason":"Change it.","citations":[]}
        """.trimIndent()))
        assertInvalid(plan("""
            {"kind":"create","path":"TeamCode/New.java","content":"new",
            "reason":"Create it.","citations":[]}
        """.trimIndent()))
        assertInvalid(plan("""
            {"path":"TeamCode/New.java","expectedAbsent":true,"content":"new",
            "reason":"Create it.","citations":[]}
        """.trimIndent()))
    }

    @Test
    fun `rejects blank and oversized summary or reasons`() {
        assertInvalid("""{"summary":" ","operations":[]}""")
        assertInvalid("""{"summary":"${"a".repeat(2_001)}","operations":[]}""")
        assertInvalid(plan(create("TeamCode/New.java",reason=" ")))
        assertInvalid(plan(create("TeamCode/New.java",reason="a".repeat(2_001))))
    }

    @Test
    fun `rejects malformed hashes and false absence preconditions`() {
        assertInvalid(plan(replace("TeamCode/Test.java",hash="A".repeat(64))))
        assertInvalid(plan(replace("TeamCode/Test.java",hash="a".repeat(63))))
        assertInvalid(plan(create("TeamCode/New.java",expectedAbsent="false")))
        assertInvalid(plan(move("TeamCode/Before.java","TeamCode/After.java",expectedAbsent="false")))
    }

    @Test
    fun `rejects duplicate destinations and oversized operation or citation lists`() {
        assertInvalid(plan(create("TeamCode/Same.java"),move("TeamCode/Before.java","TeamCode/Same.java")))
        assertInvalid(plan(*List(25) { index -> create("TeamCode/New$index.java") }.toTypedArray()))
        assertInvalid(plan(create("TeamCode/New.java",citations=List(17) { index -> "\"CODE:C$index\"" }.joinToString(","))))
    }

    @Test
    fun `rejects unsafe and oversized paths`() {
        listOf(
            "/tmp/Test.java",
            "C:/tmp/Test.java",
            "TeamCode/../Test.java",
            "TeamCode/./Test.java",
            "TeamCode//Test.java"
        ).forEach { path -> assertInvalid(plan(create(path))) }
        assertInvalid("""{"summary":"Safe edits.","operations":[{
            "kind":"create","path":"TeamCode\\Test.java","expectedAbsent":true,"content":"new",
            "reason":"Create it.","citations":[]}]}""".replace("\n",""))
        assertInvalid(plan(create("a".repeat(513))))
    }

    private fun assertInvalid(text:String) {
        assertThrows(IllegalArgumentException::class.java) { EditPlanParser.parse(text) }
    }

    private fun plan(vararg operations:String)=
        """{"summary":"Safe edits.","operations":[${operations.joinToString(",")}]}"""

    private fun create(
        path:String,
        suffix:String="",
        expectedAbsent:String="true",
        reason:String="Create the file.",
        citations:String=""
    )="""{"kind":"create","path":"$path","expectedAbsent":$expectedAbsent,"content":"new",
        "reason":"$reason","citations":[$citations]$suffix}""".replace("\n","")

    private fun replace(path:String,hash:String=HASH)=
        """{"kind":"replace","path":"$path","expectedSha256":"$hash","oldText":"old","newText":"new",
        "reason":"Replace it.","citations":[]}""".replace("\n","")

    private fun delete(path:String)=
        """{"kind":"delete","path":"$path","expectedSha256":"$HASH","reason":"Delete it.","citations":[]}"""

    private fun move(path:String,destination:String,expectedAbsent:String="true")=
        """{"kind":"move","sourcePath":"$path","destinationPath":"$destination","expectedSha256":"$HASH",
        "destinationExpectedAbsent":$expectedAbsent,"reason":"Move it.","citations":[]}""".replace("\n","")

    private companion object {
        const val HASH="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
