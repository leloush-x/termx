package app.termx.productivity

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductivityLogicTest {
    @Test
    fun paletteFuzzySearchKeepsStableActionLabelFindable() {
        assertTrue(fuzzyMatches("storage Storage Health", "sth"))
        assertTrue(fuzzyMatches("history Search history", "search hi"))
        assertFalse(fuzzyMatches("open files", "palette"))
    }

    @Test
    fun profileRejectsParentTraversalAndRuntimeEnvironmentOverrides() {
        val base = TermXProfile("p1", "Developer", "wolfi", "/root/project", mapOf("EDITOR" to "vim"))
        ProfileStore.validate(base)

        runCatching { ProfileStore.validate(base.copy(workingDirectory = "/root/../etc")) }
            .onSuccess { error("parent traversal was accepted") }
        runCatching { ProfileStore.validate(base.copy(environment = mapOf("PROOT_LOADER" to "/tmp/loader"))) }
            .onSuccess { error("TermX runtime variable override was accepted") }
    }
}
