package app.termx.runtime

import app.termx.storage.StorageTier
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BindTableTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun t0ContainsOnlySystemAndAppSharedBinds() {
        val shared = File(temporaryFolder.root, "shared")
        val binds = BindTable.build(StorageTier.T0, shared)

        assertEquals(
            listOf(
                Bind("/dev", "/dev"),
                Bind("/proc", "/proc"),
                Bind("/sys", "/sys"),
                Bind(shared.absolutePath, "/root/shared"),
            ),
            binds,
        )
        assertFalse(binds.any { it.host == "/sdcard" || it.host.startsWith("/storage") })
    }

    @Test
    fun t1AddsDeviceStorageOnlyForT1() {
        val shared = File(temporaryFolder.root, "shared")
        val external = temporaryFolder.newFolder("external")

        val binds = BindTable.build(StorageTier.T1, shared, external)

        assertEquals(Bind(external.absolutePath, "/root/storage/shared"), binds.last())
        assertEquals(5, binds.size)
        assertFalse(BindTable.build(StorageTier.T2, shared).any { it.host == external.absolutePath })
    }

    @Test
    fun t1RequiresAnAvailableExternalDirectory() {
        val shared = File(temporaryFolder.root, "shared")
        val error = runCatching { BindTable.build(StorageTier.T1, shared) }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
    }
}
