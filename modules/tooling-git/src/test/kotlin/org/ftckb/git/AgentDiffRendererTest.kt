package org.ftckb.git

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AgentDiffRendererTest {
    @Test
    fun `render shows only passed dirty snapshot transition with three context lines`() {
        val before="""one
            |two
            |user change
            |four
            |five
            |six
            |seven""".trimMargin()
        val after=before.replace("user change","user + agent")

        val rendered=AgentDiffRenderer.render(
            listOf(TextChange("TeamCode/src/Drive.kt",before,after,projectLevel=false))
        )

        assertEquals(
            """--- a/TeamCode/src/Drive.kt
                |+++ b/TeamCode/src/Drive.kt
                |@@ -1,6 +1,6 @@
                | one
                | two
                |-user change
                |+user + agent
                | four
                | five
                | six
                |""".trimMargin(),
            rendered
        )
        assertFalse(rendered.contains("repository baseline"))
    }

    @Test
    fun `render is path-stable and prefixes project-level changes visibly`() {
        val changes=listOf(
            TextChange("zeta.txt",null,"zeta",projectLevel=false),
            TextChange("build.gradle.kts","old","new",projectLevel=true)
        )

        val first=AgentDiffRenderer.render(changes)
        val second=AgentDiffRenderer.render(changes.reversed())

        assertEquals(first,second)
        assertTrue(first.startsWith("PROJECT-LEVEL CHANGE: build.gradle.kts\n"))
        assertTrue(first.indexOf("b/build.gradle.kts")<first.indexOf("b/zeta.txt"))
        assertTrue(first.contains("--- /dev/null\n+++ b/zeta.txt"))
    }
}
