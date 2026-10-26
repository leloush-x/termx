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

    @Test
    fun givesEveryDistroTheTermxPromptAndNamesTheDistroInsteadOfLocalhost() {
        val rootfs = temporaryFolder.newFolder("alpine-like")
        // This distro ships its own prompt, the case that used to skip the TermX banner entirely.
        val home = File(rootfs, "root").apply { mkdirs() }
        File(home, ".bashrc").writeText("# stock distro rc\nPS1='\\u@\\h:\\w\\$ '\n")

        ProotCommand.installPromptHooks(rootfs, "Alpine")

        val snippet = File(home, ".termx/prompt.sh").readText()
        // The host part comes from $TERMX_DISTRO at prompt time, so it names the running distro.
        assertTrue(snippet.contains("TERMX_DISTRO"))
        assertTrue(snippet.contains("🐙"))
        // A user- or distro-defined prompt is left alone.
        assertTrue(!File(home, ".bashrc").readText().contains(".termx/prompt.sh"))
    }

    @Test
    fun installsTheTermxPromptIntoRcFilesThatDefineNoPromptOfTheirOwn() {
        val rootfs = temporaryFolder.newFolder("bare-rootfs")
        ProotCommand.installPromptHooks(rootfs, "Wolfi")
        val home = File(rootfs, "root")

        val bashrc = File(home, ".bashrc").readText()
        assertTrue(bashrc.contains(".termx/prompt.sh"))
        assertTrue(File(home, ".profile").readText().contains(".termx/prompt.sh"))
        assertTrue(File(home, ".zshrc").readText().contains(".termx/prompt.sh"))

        // Idempotent: a second install must not stack duplicate source lines.
        ProotCommand.installPromptHooks(rootfs, "Wolfi")
        assertEquals(bashrc, File(home, ".bashrc").readText())
    }

    @Test
    fun writesASanitizedGuestHostnameAndLeavesSymlinksAlone() {
        val rootfs = temporaryFolder.newFolder("hostname-rootfs")
        ProotCommand.writeGuestHostname(rootfs, "Void Linux")
        assertEquals("void-linux", File(rootfs, "etc/hostname").readText().trim())

        val symlinkRootfs = temporaryFolder.newFolder("hostname-symlink")
        File(symlinkRootfs, "etc").mkdirs()
        java.nio.file.Files.createSymbolicLink(
            File(symlinkRootfs, "etc/hostname").toPath(),
            File(symlinkRootfs, "real-hostname").toPath(),
        )
        ProotCommand.writeGuestHostname(symlinkRootfs, "Debian")
        assertTrue(java.nio.file.Files.isSymbolicLink(File(symlinkRootfs, "etc/hostname").toPath()))
    }
}
