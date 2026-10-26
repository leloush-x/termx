package app.termx.runtime

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DefaultShellTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun usesInteractiveBashWhenAvailableAndLeavesOtherDistrosOnSh() {
        val rootfs = temporaryFolder.newFolder("rootfs")
        File(rootfs, "usr/bin").mkdirs()
        File(rootfs, "usr/bin/bash").writeText("")
        File(rootfs, "bin").mkdirs()
        File(rootfs, "bin/bash").writeText("")

        assertEquals(listOf("/bin/bash", "--login", "--interactive"), ProotCommand.defaultShell(rootfs))
        assertEquals(listOf("/bin/sh", "-l"), ProotCommand.defaultShell(temporaryFolder.newFolder("minimal")))
    }

    @Test
    fun createsColoredPromptOnlyWhenUserHasNoStartupFile() {
        val rootfs = temporaryFolder.newFolder("rootfs")
        ProotCommand.prepareBashDefaults(rootfs)
        val home = File(rootfs, "root")
        val bashrc = File(home, ".bashrc")
        assertTrue(bashrc.readText().contains("PS1='\\[\\e[38;5;213m\\]🐙"))
        assertTrue(File(home, ".bash_profile").readText().contains(". ~/.bashrc"))

        bashrc.writeText("# user's own prompt\n")
        ProotCommand.prepareBashDefaults(rootfs)
        assertEquals("# user's own prompt\n", bashrc.readText())
    }
}
