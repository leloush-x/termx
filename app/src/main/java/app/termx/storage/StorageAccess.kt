package app.termx.storage

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import app.termx.BuildConfig

enum class StorageTier { T0, T1, T2 }

enum class TierAvailability { ACTIVE, AVAILABLE, NEEDS_PERMISSION, UNAVAILABLE }

data class StorageSnapshot(
    val allowsAllFiles: Boolean,
    val apiLevel: Int,
    val allFilesGranted: Boolean,
    val legacyReadGranted: Boolean,
    val legacyWriteGranted: Boolean,
    val workspaceCount: Int,
    val sharedDirectoryAvailable: Boolean,
)

data class StorageTierStatus(
    val tier: StorageTier,
    val availability: TierAvailability,
    val detail: String,
)

/** Pure storage-tier policy, with Android permission checks kept at the boundary. */
object StorageAccess {
    fun snapshot(context: Context, workspaceCount: Int, sharedDirectoryAvailable: Boolean): StorageSnapshot {
        val api = Build.VERSION.SDK_INT
        val direct = BuildConfig.HAS_ALL_FILES
        return StorageSnapshot(
            allowsAllFiles = direct,
            apiLevel = api,
            allFilesGranted = direct && api >= Build.VERSION_CODES.R && Environment.isExternalStorageManager(),
            legacyReadGranted = direct && api in 26..28 &&
                context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED,
            legacyWriteGranted = direct && api in 26..28 &&
                context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED,
            workspaceCount = workspaceCount,
            sharedDirectoryAvailable = sharedDirectoryAvailable,
        )
    }

    fun statuses(snapshot: StorageSnapshot): List<StorageTierStatus> = StorageTier.entries.map { status(it, snapshot) }

    fun status(tier: StorageTier, snapshot: StorageSnapshot): StorageTierStatus = when (tier) {
        StorageTier.T0 -> StorageTierStatus(
            tier,
            if (snapshot.sharedDirectoryAvailable) TierAvailability.ACTIVE else TierAvailability.UNAVAILABLE,
            if (snapshot.sharedDirectoryAvailable) "App-owned shared folder; no storage permission needed."
            else "The app shared folder is unavailable on this storage volume.",
        )
        StorageTier.T1 -> when {
            !snapshot.allowsAllFiles -> StorageTierStatus(
                tier, TierAvailability.UNAVAILABLE, "Available in the direct flavor only.",
            )
            snapshot.apiLevel == 29 -> StorageTierStatus(
                tier, TierAvailability.UNAVAILABLE,
                "Android 10 has no All-files access for this target. Use a SAF workspace instead.",
            )
            snapshot.apiLevel in 26..28 && snapshot.legacyReadGranted && snapshot.legacyWriteGranted -> StorageTierStatus(
                tier, TierAvailability.ACTIVE, "Legacy shared-storage access is granted.",
            )
            snapshot.apiLevel in 26..28 -> StorageTierStatus(
                tier, TierAvailability.NEEDS_PERMISSION, "Grant storage access when you choose to use shared device storage.",
            )
            snapshot.apiLevel >= 30 && snapshot.allFilesGranted -> StorageTierStatus(
                tier, TierAvailability.ACTIVE, "All-files access is granted to the direct build.",
            )
            snapshot.apiLevel >= 30 -> StorageTierStatus(
                tier, TierAvailability.NEEDS_PERMISSION, "Grant All-files access to bind shared device storage into a session.",
            )
            else -> StorageTierStatus(tier, TierAvailability.UNAVAILABLE, "This Android version is not supported by T1.")
        }
        StorageTier.T2 -> StorageTierStatus(
            tier,
            if (snapshot.workspaceCount > 0) TierAvailability.ACTIVE else TierAvailability.AVAILABLE,
            if (snapshot.workspaceCount > 0) "${snapshot.workspaceCount} SAF workspace(s) are saved. Files are copied explicitly."
            else "Choose a folder with the system picker. No broad storage permission is needed.",
        )
    }

    fun canUse(tier: StorageTier, snapshot: StorageSnapshot): Boolean =
        status(tier, snapshot).availability in setOf(TierAvailability.ACTIVE, TierAvailability.AVAILABLE)

    fun effectiveTier(requested: StorageTier, snapshot: StorageSnapshot): StorageTier =
        if (canUse(requested, snapshot)) requested else StorageTier.T0

    fun permissionHint(error: Throwable): String? {
        val messages = generateSequence(error) { it.cause }
            .mapNotNull { it.message }
            .joinToString(" ")
        val denied = generateSequence(error) { it.cause }.any { it is SecurityException } ||
            messages.contains("EACCES", ignoreCase = true) ||
            messages.contains("Permission denied", ignoreCase = true)
        return if (denied) "Storage access is blocked. Open Storage Health to grant access or choose a SAF workspace." else null
    }
}
