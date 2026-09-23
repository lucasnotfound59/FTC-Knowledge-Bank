package org.ftckb.standardizer

import java.nio.file.Files
import java.nio.file.Path
import org.eclipse.jgit.api.Git
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.ftckb.domain.KnowledgeRule
import org.ftckb.domain.RuleApplicability
import org.ftckb.domain.RuleAuthority
import org.ftckb.domain.RuleCheck
import org.ftckb.domain.RuleCheckKind
import org.ftckb.domain.RuleReviewTrigger
import org.ftckb.domain.RuleStatus

class StandardizerTest {
    private fun softRule(id:String,triggers:List<RuleReviewTrigger> =emptyList())=KnowledgeRule(
        id=id,
        topic=id.substringAfter('.').replace('.','-'),
        title=id,
        instruction="Review $id",
        rationale="Test conditional soft review.",
        status=RuleStatus.APPROVED,
        authority=RuleAuthority.SHARED,
        applicability=RuleApplicability(),
        evidence=emptyList(),
        reviewTriggers=triggers
    )

    private fun forbiddenPathRule(pattern:String)=softRule("shared.forbidden-path").copy(
        checks=listOf(RuleCheck(RuleCheckKind.PATH_FORBIDDEN,pattern,null,"Forbidden path"))
    )

    private fun repository(root:Path):Pair<Path,Git> {
        val repo=Files.createDirectory(root.resolve("repo"))
        Files.writeString(repo.resolve("tracked.txt"),"base\n")
        Files.writeString(repo.resolve("rename me.txt"),"same\n")
        Files.writeString(repo.resolve("delete.txt"),"delete\n")
        val git=Git.init().setDirectory(repo.toFile()).call()
        git.add().addFilepattern(".").call()
        git.commit().setMessage("base").call()
        return repo to git
    }

    @Test
    fun `collects staged unstaged untracked deletion empty and unicode paths`(@TempDir root:Path) {
        val (repo,git)=repository(root)
        git.use {
            Files.writeString(repo.resolve("build.common.gradle"),"staged\n")
            git.add().addFilepattern("build.common.gradle").call()
            Files.delete(repo.resolve("build.common.gradle"))
            Files.writeString(repo.resolve("tracked.txt"),"base\nunstaged\n")
            Files.writeString(repo.resolve("未跟踪 file.txt"),"new\n")
            Files.writeString(repo.resolve("empty.txt"),"")
            Files.delete(repo.resolve("delete.txt"))

            val changes=Standardizer.worktreeChanges(repo).associateBy { it.path }
            assertTrue("build.common.gradle" in changes)
            assertEquals(listOf(2 to "unstaged"),changes.getValue("tracked.txt").addedLines)
            assertEquals(listOf(1 to "new"),changes.getValue("未跟踪 file.txt").addedLines)
            assertTrue("empty.txt" in changes)
            assertTrue("delete.txt" in changes)
        }
    }

    @Test
    fun `pure rename reports both paths and mode only reports path`(@TempDir root:Path) {
        val (repo,git)=repository(root)
        git.use {
            Files.move(repo.resolve("rename me.txt"),repo.resolve("renamed 中文.txt"))
            repo.resolve("tracked.txt").toFile().setExecutable(true,false)
            val paths=Standardizer.worktreeChanges(repo).map { it.path }
            assertTrue("rename me.txt" in paths,paths.toString())
            assertTrue("renamed 中文.txt" in paths,paths.toString())
            assertTrue("tracked.txt" in paths,paths.toString())
        }
    }

    @Test
    fun `parser includes delete and rejects malformed patches`() {
        val deletion="""
            diff --git a/gone.txt b/gone.txt
            deleted file mode 100644
            --- a/gone.txt
            +++ /dev/null
            @@ -1 +0,0 @@
            -gone
        """.trimIndent()+"\n"
        assertEquals(listOf(Standardizer.DiffChange("gone.txt",emptyList())),Standardizer.parsePatch(deletion))
        assertThrows(IllegalArgumentException::class.java) { Standardizer.parsePatch("not a patch\n") }
        assertThrows(IllegalArgumentException::class.java) {
            Standardizer.parsePatch("diff --git a/a b/a\n--- a/a\n+++ b/a\n@@ broken\n")
        }
    }

    @Test
    fun `path forbidden blocks deletion and both sides of rename`() {
        val forbidden="TeamCode/src/test/java/org/firstinspires/ftc/teamcode/DriveTest.java"
        val canonical="TeamCode/src/main/java/org/firstinspires/ftc/teamcode/tests/DriveTest.java"
        val rule=forbiddenPathRule("TeamCode/src/test/**")
        val deletion=Standardizer.parsePatch("""
            diff --git a/$forbidden b/$forbidden
            deleted file mode 100644
            --- a/$forbidden
            +++ /dev/null
            @@ -1 +0,0 @@
            -class DriveTest {}
        """.trimIndent()+"\n")
        val renameAway=Standardizer.parsePatch("""
            diff --git a/$forbidden b/$canonical
            similarity index 100%
            rename from $forbidden
            rename to $canonical
        """.trimIndent()+"\n")
        val renameInto=Standardizer.parsePatch("""
            diff --git a/$canonical b/$forbidden
            similarity index 100%
            rename from $canonical
            rename to $forbidden
        """.trimIndent()+"\n")
        val modification=Standardizer.parsePatch("""
            diff --git a/$forbidden b/$forbidden
            --- a/$forbidden
            +++ b/$forbidden
            @@ -1 +1 @@
            -class DriveTest {}
            +class DriveTest { void changed() {} }
        """.trimIndent()+"\n")

        assertEquals(1,Standardizer.evaluate(listOf(rule),deletion).violations.size)
        assertEquals(1,Standardizer.evaluate(listOf(rule),renameAway).violations.size)
        assertEquals(1,Standardizer.evaluate(listOf(rule),renameInto).violations.size)
        assertEquals(1,Standardizer.evaluate(listOf(rule),modification).violations.size)
    }

    @Test
    fun `regex forbidden keeps legacy per line matching before joined blocks`() {
        val rule=softRule("shared.forbidden-import").copy(checks=listOf(RuleCheck(
            RuleCheckKind.REGEX_FORBIDDEN,
            "^\\s*import\\s+org\\.junit\\.Test;",
            "**/*.java",
            "JUnit imports are forbidden"
        )))
        val outcome=Standardizer.evaluate(listOf(rule),listOf(Standardizer.DiffChange(
            "TeamCode/src/main/java/org/firstinspires/ftc/teamcode/Vision.java",
            listOf(1 to "package org.firstinspires.ftc.teamcode;",2 to "import org.junit.Test;")
        )))

        assertEquals(1,outcome.violations.size)
        assertEquals(2,outcome.violations.single().line)
    }

    @Test
    fun `review triggers emit soft only for matching added lines`() {
        val always=softRule("shared.always")
        val triggered=softRule("shared.limelight",listOf(RuleReviewTrigger(
            listOf("**/*.java"),
            listOf("\\bLLResult\\b","\\.getLatestResult\\s*\\(")
        )))
        val unrelated=listOf(Standardizer.DiffChange(
            "TeamCode/src/main/java/example/DriveSubsystem.java",listOf(10 to "motor.setPower(power);")
        ))
        val relevant=listOf(Standardizer.DiffChange(
            "TeamCode/src/main/java/example/Vision.java",listOf(7 to "LLResult result=limelight.getLatestResult();")
        ))

        assertEquals(listOf("shared.always"),Standardizer.evaluate(listOf(triggered,always),unrelated).soft.map { it.first })
        assertEquals(
            listOf("shared.always","shared.limelight"),
            Standardizer.evaluate(listOf(triggered,always),relevant).soft.map { it.first }
        )
    }

    @Test
    fun `dev downgrade keeps rule check path line pattern and detail deterministically`() {
        val violations=listOf(
            Standardizer.Violation("shared.b","regex-forbidden","TeamCode/B.java",7,"pat-b","detail b"),
            Standardizer.Violation("shared.a","path-forbidden","build.common.gradle",1,"build.common.gradle","detail a"),
            Standardizer.Violation("shared.a","regex-required","TeamCode/A.java",3,"pat-a","detail a2"),
            Standardizer.Violation("shared.c","path-required",null,null,"TeamCode/required/**","detail c")
        )

        assertEquals(
            listOf(
                "shared.a" to "work-mode=dev; downgraded hard check=regex-required path=TeamCode/A.java line=3 pattern=pat-a detail=detail a2",
                "shared.a" to "work-mode=dev; downgraded hard check=path-forbidden path=build.common.gradle line=1 pattern=build.common.gradle detail=detail a",
                "shared.b" to "work-mode=dev; downgraded hard check=regex-forbidden path=TeamCode/B.java line=7 pattern=pat-b detail=detail b",
                "shared.c" to "work-mode=dev; downgraded hard check=path-required pattern=TeamCode/required/** detail=detail c"
            ),
            Standardizer.downgradeToSoft(violations)
        )
        assertEquals(
            Standardizer.downgradeToSoft(violations),
            Standardizer.downgradeToSoft(violations.reversed())
        )
        assertTrue(Standardizer.downgradeToSoft(emptyList()).isEmpty())
    }

    @Test
    fun `path-only triggers and duplicate matches emit one sorted soft entry`() {
        val pathOnly=softRule("shared.z-rule",listOf(RuleReviewTrigger(listOf("TeamCode/**"),emptyList())))
        val repeated=softRule("shared.a-rule",listOf(
            RuleReviewTrigger(listOf("**/*.java"),listOf("LLResult")),
            RuleReviewTrigger(listOf("**/Vision.java"),listOf("getLatestResult"))
        ))
        val changes=listOf(Standardizer.DiffChange(
            "TeamCode/src/main/java/example/Vision.java",
            listOf(1 to "LLResult result=limelight.getLatestResult();")
        ))

        assertEquals(
            listOf("shared.a-rule","shared.z-rule"),
            Standardizer.evaluate(listOf(pathOnly,repeated),changes).soft.map { it.first }
        )
    }
}
