package app.termx.runtime

import java.io.File
import app.termx.storage.StorageTier

data class Bind(val host: String, val guest: String)

data class ProotLaunch(
    val executable: File,
    val workingDirectory: String,
    val arguments: Array<String>,
    val environment: Array<String>,
    val binds: List<Bind>,
)

object ProotCommand {
    fun build(
        nativeLibraryDirectory: File,
        rootfs: File,
        temporaryDirectory: File,
        sharedDirectory: File,
        storageTier: StorageTier = StorageTier.T0,
        externalStorageRoot: File? = null,
        command: List<String> = listOf("/bin/sh", "-l"),
    ): ProotLaunch {
        val proot = File(nativeLibraryDirectory, "libproot.so")
        val loader = File(nativeLibraryDirectory, "libloader.so")
        require(proot.canExecute()) { "proot executable is missing" }
        require(loader.isFile) { "proot loader is missing" }
        require(rootfs.isDirectory) { "Distro rootfs is missing" }
        require(command.isNotEmpty()) { "A guest command is required" }
        temporaryDirectory.mkdirs()
        val binds = BindTable.build(storageTier, sharedDirectory, externalStorageRoot)
        binds.filter { it.guest.startsWith("/root/") }.forEach { bind ->
            val mountPoint = File(rootfs, bind.guest.removePrefix("/"))
            require((mountPoint.isDirectory || mountPoint.mkdirs()) && mountPoint.isDirectory) {
                "Could not prepare guest bind mount point: ${bind.guest}"
            }
        }
        val argv = buildList {
            add(proot.absolutePath)
            add("--kill-on-exit")
            add("--link2symlink")
            add("-0")
            add("-r")
            add(rootfs.absolutePath)
            add("-w")
            add("/root")
            binds.forEach { bind ->
                add("-b")
                add("${bind.host}:${bind.guest}")
            }
            addAll(command)
        }.toTypedArray()
        val environment = arrayOf(
            "PROOT_LOADER=${loader.absolutePath}",
            "PROOT_TMP_DIR=${temporaryDirectory.absolutePath}",
            "TERM=xterm-256color",
            "COLORTERM=truecolor",
            "LANG=C.UTF-8",
            "HOME=/root",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        )
        return ProotLaunch(proot, rootfs.absolutePath, argv, environment, binds)
    }
}

/** The only place where host storage paths become PRoot binds. */
object BindTable {
    fun build(
        tier: StorageTier,
        sharedDirectory: File,
        externalStorageRoot: File? = null,
    ): List<Bind> = buildList {
        add(Bind("/dev", "/dev"))
        add(Bind("/proc", "/proc"))
        add(Bind("/sys", "/sys"))
        add(Bind(sharedDirectory.absolutePath, "/root/shared"))
        if (tier == StorageTier.T1) {
            val deviceStorage = requireNotNull(externalStorageRoot) {
                "T1 requires granted access to shared device storage"
            }
            require(deviceStorage.isDirectory) { "Shared device storage is unavailable" }
            add(Bind(deviceStorage.absolutePath, "/root/storage/shared"))
        }
    }
}
