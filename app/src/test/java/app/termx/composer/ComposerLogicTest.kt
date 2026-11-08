package app.termx.composer

import org.junit.Assert.assertEquals
import org.junit.Test

class ComposerLogicTest {
    @Test
    fun historyMovesDuplicateToFrontAndStaysBounded() {
        val values = (0 until 200).map { "command-$it" }
        val updated = ComposerHistory.append(values, "command-100")
        assertEquals(200, updated.size)
        assertEquals("command-100", updated.first())
        assertEquals(1, updated.count { it == "command-100" })
    }

    @Test
    fun namedPlaceholdersAreUniqueAndCanBeFilled() {
        val template = "echo {{name}} {{name}} {{branch}}"
        assertEquals(listOf("name", "branch"), SnippetTemplates.placeholders(template))
        assertEquals("echo termx termx debug", SnippetTemplates.fill(template, mapOf("name" to "termx", "branch" to "debug")))
    }
}
