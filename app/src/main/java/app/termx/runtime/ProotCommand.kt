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
        dnsResolverFile: File? = null,
        command: List<String>? = null,
    ): ProotLaunch {
        val proot = File(nativeLibraryDirectory, "libproot.so")
        val loader = File(nativeLibraryDirectory, "libloader.so")
        require(proot.canExecute()) { "proot executable is missing" }
        require(loader.isFile) { "proot loader is missing" }
        require(rootfs.isDirectory) { "Distro rootfs is missing" }
        val shellCommand = command ?: defaultShell(rootfs)
        require(shellCommand.isNotEmpty()) { "A guest command is required" }
        if (command == null && shellCommand.first() == "/bin/bash") prepareBashDefaults(rootfs)
        temporaryDirectory.mkdirs()
        val binds = BindTable.build(storageTier, sharedDirectory, externalStorageRoot, dnsResolverFile)
        binds.filter { it.guest.startsWith("/root/") }.forEach { bind ->
            val mountPoint = File(rootfs, bind.guest.removePrefix("/"))
            require((mountPoint.isDirectory || mountPoint.mkdirs()) && mountPoint.isDirectory) {
                "Could not prepare guest bind mount point: ${bind.guest}"
            }
        }
        binds.filter { it.guest == "/etc/resolv.conf" }.forEach { bind ->
            require(File(bind.host).isFile) { "Android DNS configuration is unavailable" }
            val guestResolver = File(rootfs, "etc/resolv.conf")
            require(!java.nio.file.Files.isSymbolicLink(guestResolver.toPath())) {
                "Distro resolver path must not be a symbolic link"
            }
            if (!guestResolver.exists()) {
                require(
                    (guestResolver.parentFile?.isDirectory == true || guestResolver.parentFile?.mkdirs() == true) &&
                        guestResolver.createNewFile(),
                ) { "Could not prepare distro resolver path" }
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
            addAll(shellCommand)
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

    internal fun defaultShell(rootfs: File): List<String> =
        if (File(rootfs, "bin/bash").isFile) listOf("/bin/bash", "--login", "--interactive")
        else listOf("/bin/sh", "-l")

    /** Create user-editable Bash defaults only when the distro has no existing startup files. */
    internal fun prepareBashDefaults(rootfs: File) {
        val home = File(rootfs, "root").apply { mkdirs() }
        val bashrc = File(home, ".bashrc")
        if (!bashrc.exists()) {
            bashrc.writeText(
                """# TermX defaults. Edit this file to customize your interactive Bash shell.
shopt -s checkwinsize
if command -v dircolors >/dev/null 2>&1; then
    eval "$(dircolors -b 2>/dev/null)"
fi
alias ls='ls --color=auto' 2>/dev/null || true
PS1='\[\e[38;5;213m\]🐙 \[\e[38;5;81m\]\u@\h\[\e[38;5;244m\]:\[\e[38;5;114m\]\w\[\e[38;5;244m\]$ \[\e[0m\]'
""".trimIndent() + "\n",
            )
        }
        val profile = File(home, ".bash_profile")
        if (!profile.exists()) profile.writeText("# TermX: load interactive Bash defaults.\n[ -r ~/.bashrc ] && . ~/.bashrc\n")
    }
}

/** The only place where host storage paths become PRoot binds. */
object BindTable {
    fun build(
        tier: StorageTier,
        sharedDirectory: File,
        externalStorageRoot: File? = null,
        dnsResolverFile: File? = null,
    ): List<Bind> = buildList {
        add(Bind("/dev", "/dev"))
        add(Bind("/proc", "/proc"))
        add(Bind("/sys", "/sys"))
        add(Bind(sharedDirectory.absolutePath, "/root/shared"))
        dnsResolverFile?.let { add(Bind(it.absolutePath, "/etc/resolv.conf")) }
        if (tier == StorageTier.T1) {
            val deviceStorage = requireNotNull(externalStorageRoot) {
                "T1 requires granted access to shared device storage"
            }
            require(deviceStorage.isDirectory) { "Shared device storage is unavailable" }
            add(Bind(deviceStorage.absolutePath, "/root/storage/shared"))
        }
    }
}
