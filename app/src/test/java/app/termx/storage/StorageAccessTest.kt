package app.termx.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageAccessTest {
    @Test
    fun t0NeedsNoPermissionAndT2NeedsOnlyAWorkspaceChoice() {
        val snapshot = state(direct = false, api = 35)

        assertEquals(TierAvailability.ACTIVE, StorageAccess.status(StorageTier.T0, snapshot).availability)
        assertEquals(TierAvailability.UNAVAILABLE, StorageAccess.status(StorageTier.T1, snapshot).availability)
        assertEquals(TierAvailability.AVAILABLE, StorageAccess.status(StorageTier.T2, snapshot).availability)
        assertEquals(StorageTier.T2, StorageAccess.effectiveTier(StorageTier.T2, snapshot))
    }

    @Test
    fun directT1RequiresGrantOnAndroid11AndLater() {
        val denied = state(direct = true, api = 35)
        val granted = state(direct = true, api = 35, allFiles = true)

        assertEquals(TierAvailability.NEEDS_PERMISSION, StorageAccess.status(StorageTier.T1, denied).availability)
        assertEquals(TierAvailability.ACTIVE, StorageAccess.status(StorageTier.T1, granted).availability)
        assertEquals(StorageTier.T0, StorageAccess.effectiveTier(StorageTier.T1, denied))
        assertEquals(StorageTier.T1, StorageAccess.effectiveTier(StorageTier.T1, granted))
    }

    @Test
    fun android10UsesSafInsteadOfUnavailableAllFilesTier() {
        val snapshot = state(direct = true, api = 29, allFiles = true)

        assertEquals(TierAvailability.UNAVAILABLE, StorageAccess.status(StorageTier.T1, snapshot).availability)
        assertEquals(StorageTier.T0, StorageAccess.effectiveTier(StorageTier.T1, snapshot))
    }

    @Test
    fun legacyTierNeedsReadAndWriteGrantsOnlyOnAndroid8And9() {
        assertEquals(
            TierAvailability.NEEDS_PERMISSION,
            StorageAccess.status(StorageTier.T1, state(direct = true, api = 28)).availability,
        )
        assertEquals(
            TierAvailability.ACTIVE,
            StorageAccess.status(StorageTier.T1, state(direct = true, api = 28, legacyRead = true, legacy = true)).availability,
        )
        assertFalse(StorageAccess.canUse(StorageTier.T1, state(direct = true, api = 28)))
    }

    @Test
    fun permissionFailuresGetOneLineStorageHint() {
        assertTrue(StorageAccess.permissionHint(SecurityException("denied"))!!.contains("Storage Health"))
        assertTrue(StorageAccess.permissionHint(IllegalStateException("EACCES"))!!.contains("SAF workspace"))
        assertEquals(null, StorageAccess.permissionHint(IllegalStateException("file not found")))
    }

    private fun state(
        direct: Boolean,
        api: Int,
        allFiles: Boolean = false,
        legacyRead: Boolean = false,
        legacy: Boolean = false,
        workspaces: Int = 0,
    ) = StorageSnapshot(
        allowsAllFiles = direct,
        apiLevel = api,
        allFilesGranted = allFiles,
        legacyReadGranted = legacyRead,
        legacyWriteGranted = legacy,
        workspaceCount = workspaces,
        sharedDirectoryAvailable = true,
    )
}
