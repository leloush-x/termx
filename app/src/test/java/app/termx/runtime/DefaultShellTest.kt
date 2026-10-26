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

        assertEquals(listOf("/bin/bash", "--login", "-i"), ProotCommand.defaultShell(rootfs))
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

    @Test
    fun installsHistoryHooksWithoutReplacingStartupFilesOrDuplicatingSourceLines() {
        val rootfs = temporaryFolder.newFolder("history-rootfs")
        val home = File(rootfs, "root").apply { mkdirs() }
        val bashrc = File(home, ".bashrc").apply { writeText("# existing user setup\n") }
        val zshrc = File(home, ".zshrc").apply { writeText("# existing zsh setup\n") }

        ProotCommand.installHistoryHooks(rootfs)
        val bashOnce = bashrc.readText()
        val zshOnce = zshrc.readText()
        ProotCommand.installHistoryHooks(rootfs)

        assertTrue(File(home, ".bashrc.d/termx-history.sh").readText().contains("history.tsv"))
        assertTrue(File(home, ".zshrc.d/termx-history.zsh").readText().contains("precmd_functions"))
        assertTrue(bashrc.readText().startsWith("# existing user setup"))
        assertTrue(zshrc.readText().startsWith("# existing zsh setup"))
        assertEquals(bashOnce, bashrc.readText())
        assertEquals(zshOnce, zshrc.readText())
    }
}
