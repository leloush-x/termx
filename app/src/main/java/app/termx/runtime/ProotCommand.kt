package app.termx.runtime

import java.io.File

data class Bind(val host: String, val guest: String)

data class ProotLaunch(
    val executable: File,
    val workingDirectory: String,
    val arguments: Array<String>,
    val environment: Array<String>,
)

/** Phase 2 allows only app-owned T0 binds. */
object BindTable {
    fun t0(sharedDirectory: File): List<Bind> = listOf(
        Bind("/dev", "/dev"),
        Bind("/proc", "/proc"),
        Bind("/sys", "/sys"),
        Bind(sharedDirectory.absolutePath, "/root/shared"),
    )
}

object ProotCommand {
    fun build(
        nativeLibraryDirectory: File,
        rootfs: File,
        temporaryDirectory: File,
        sharedDirectory: File,
        command: List<String> = listOf("/bin/sh", "-l"),
    ): ProotLaunch {
        val proot = File(nativeLibraryDirectory, "libproot.so")
        val loader = File(nativeLibraryDirectory, "libloader.so")
        require(proot.canExecute()) { "proot executable is missing" }
        require(loader.isFile) { "proot loader is missing" }
        require(rootfs.isDirectory) { "Distro rootfs is missing" }
        require(command.isNotEmpty()) { "A guest command is required" }
        temporaryDirectory.mkdirs()
        val argv = buildList {
            add(proot.absolutePath)
            add("--kill-on-exit")
            add("--link2symlink")
            add("-0")
            add("-r")
            add(rootfs.absolutePath)
            add("-w")
            add("/root")
            BindTable.t0(sharedDirectory).forEach { bind ->
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
        return ProotLaunch(proot, rootfs.absolutePath, argv, environment)
    }
}
