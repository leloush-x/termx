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
        guestWorkingDirectory: String = "/root",
        extraEnvironment: Map<String, String> = emptyMap(),
        distroName: String = "linux",
    ): ProotLaunch {
        val proot = File(nativeLibraryDirectory, "libproot.so")
        val loader = File(nativeLibraryDirectory, "libloader.so")
        require(proot.canExecute()) { "proot executable is missing" }
        require(loader.isFile) { "proot loader is missing" }
        require(rootfs.isDirectory) { "Distro rootfs is missing" }
        require(extraEnvironment.keys.all { it.matches(Regex("[A-Z_][A-Z0-9_]*")) }) { "Invalid environment variable name" }
        require(extraEnvironment.values.none { it.contains('\u0000') }) { "Environment values cannot contain null characters" }
        require(guestWorkingDirectory.startsWith('/') && guestWorkingDirectory.split('/').none { it == ".." }) {
            "Guest working directory must be absolute and cannot traverse to a parent"
        }
        require(guestWorkingDirectory.length <= 1024 && !guestWorkingDirectory.contains('\u0000')) {
            "Guest working directory is too long or contains an invalid character"
        }
        val guestCwd = File(rootfs, guestWorkingDirectory.removePrefix("/"))
        require((guestCwd.isDirectory || guestCwd.mkdirs()) && guestCwd.isDirectory) {
            "Could not prepare guest working directory: $guestWorkingDirectory"
        }
        val shellCommand = command ?: defaultShell(rootfs)
        require(shellCommand.isNotEmpty()) { "A guest command is required" }
        if (command == null) {
            if (shellCommand.first() == "/bin/bash") prepareBashDefaults(rootfs)
            installHistoryHooks(rootfs)
        }
        installPromptHooks(rootfs, distroName)
        writeGuestHostname(rootfs, distroName)
        temporaryDirectory.mkdirs()
        val binds = buildList {
            addAll(BindTable.build(storageTier, sharedDirectory, externalStorageRoot, dnsResolverFile))
            // Wolfi-terminal style workaround: Android restricts /proc/stat (and /proc/vmstat)
            // for non-system apps, so htop fails with "Cannot open /proc/stat: Permission denied".
            // Snapshot the host values (readable by our own process on most devices) into files
            // and bind those over the guest paths. The snapshot is static but keeps htop working.
            snapshotProcFiles(temporaryDirectory)?.let { procBinds ->
                addAll(procBinds)
            }
        }.toMutableList()
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
            add(guestWorkingDirectory)
            binds.forEach { bind ->
                add("-b")
                add("${bind.host}:${bind.guest}")
            }
            addAll(shellCommand)
        }.toTypedArray()
        val environment = linkedMapOf(
            "PROOT_LOADER" to loader.absolutePath,
            "PROOT_TMP_DIR" to temporaryDirectory.absolutePath,
            "TERM" to "xterm-256color",
            "COLORTERM" to "truecolor",
            "LANG" to "C.UTF-8",
            "HOME" to "/root",
            "TERMX_DISTRO" to distroName,
            "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        ).apply { putAll(extraEnvironment) }.map { (key, value) -> "$key=$value" }.toTypedArray()
        return ProotLaunch(proot, rootfs.absolutePath, argv, environment, binds)
    }

    internal fun defaultShell(rootfs: File): List<String> =
        if (File(rootfs, "bin/bash").isFile) listOf("/bin/bash", "--login", "-i")
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

    /** Installs a local history spool for Bash and zsh without replacing user startup files. */
    internal fun installHistoryHooks(rootfs: File) {
        val home = File(rootfs, "root").apply { mkdirs() }
        val bashHook = File(home, ".bashrc.d/termx-history.sh").apply {
            parentFile?.mkdirs()
            if (!exists()) writeText(
                """# TermX private command history. Leading-space and TermX commands are skipped.
__termx_b64() { printf '%s' "${'$'}1" | base64 | tr -d '\n'; }
__termx_log() {
    local ec=${'$'}? cmd
    cmd=${'$'}(HISTTIMEFORMAT= history 1 | sed -e 's/^[ ]*[0-9]*[ ]*//')
    [ -z "${'$'}cmd" ] && return ${'$'}ec
    case "${'$'}cmd" in " "*|__termx_*) return ${'$'}ec ;; esac
    local dir="${'$'}HOME/.local/state/termx"
    mkdir -p "${'$'}dir"
    [ -f "${'$'}dir/history.tsv" ] && [ ${'$'}(wc -c < "${'$'}dir/history.tsv") -ge 5242880 ] && : > "${'$'}dir/history.tsv"
    printf '%s\t%s\t%s\t%s\n' "${'$'}(date +%s)" "${'$'}ec" "${'$'}(__termx_b64 "${'$'}PWD")" "${'$'}(__termx_b64 "${'$'}cmd")" >> "${'$'}dir/history.tsv"
    return ${'$'}ec
}
case ";${'$'}{PROMPT_COMMAND:-};" in *";__termx_log;"*) ;; *) PROMPT_COMMAND="__termx_log${'$'}{PROMPT_COMMAND:+;${'$'}PROMPT_COMMAND}" ;; esac
""".trimIndent() + "\n")
        }
        val bashrc = File(home, ".bashrc")
        if (bashrc.exists() && !bashrc.readText().contains(".bashrc.d/termx-history.sh")) {
            bashrc.appendText("\n# TermX command history hook\n[ -r ~/.bashrc.d/termx-history.sh ] && . ~/.bashrc.d/termx-history.sh\n")
        }
        val zshHook = File(home, ".zshrc.d/termx-history.zsh").apply {
            parentFile?.mkdirs()
            if (!exists()) writeText(
                """# TermX private command history. Leading-space and TermX commands are skipped.
__termx_b64() { printf '%s' "${'$'}1" | base64 | tr -d '\n' }
__termx_precmd() {
  local ec=${'$'}? cmd
  cmd=${'$'}(fc -ln -1 2>/dev/null)
  [[ -z "${'$'}cmd" || "${'$'}cmd" == " "* || "${'$'}cmd" == __termx_* ]] && return ${'$'}ec
  local dir="${'$'}HOME/.local/state/termx"
  mkdir -p "${'$'}dir"
  [[ -f "${'$'}dir/history.tsv" && ${'$'}(wc -c < "${'$'}dir/history.tsv") -ge 5242880 ]] && : > "${'$'}dir/history.tsv"
  printf '%s\t%s\t%s\t%s\n' "${'$'}(date +%s)" "${'$'}ec" "${'$'}(__termx_b64 "${'$'}PWD")" "${'$'}(__termx_b64 "${'$'}cmd")" >> "${'$'}dir/history.tsv"
  return ${'$'}ec
}
(( ${'$'}precmd_functions[(Ie)__termx_precmd] )) || precmd_functions+=(__termx_precmd)
""".trimIndent() + "\n")
        }
        val zshrc = File(home, ".zshrc")
        if (!zshrc.exists()) {
            zshrc.writeText("# TermX command history hook\n[ -r ~/.zshrc.d/termx-history.zsh ] && . ~/.zshrc.d/termx-history.zsh\n")
        } else if (!zshrc.readText().contains(".zshrc.d/termx-history.zsh")) {
            zshrc.appendText("\n# TermX command history hook\n[ -r ~/.zshrc.d/termx-history.zsh ] && . ~/.zshrc.d/termx-history.zsh\n")
        }
    }

    /**
     * Gives every distro the same TermX prompt, with the host part naming the distro that is
     * actually running (`root@wolfi`, `root@alpine`, ...) instead of a bare `localhost`.
     *
     * Wolfi previously got this by accident: it ships no .bashrc, so TermX's defaults were the
     * only ones present. Distro images that ship their own .bashrc (Alpine, Debian, Void) kept
     * their stock prompt. The snippet below is sourced from the end of the shell rc files, so it
     * applies to all of them, and it reads $TERMX_DISTRO at prompt time so it stays correct even
     * when the same rootfs is reused. Set TERMX_PROMPT=off to keep a stock prompt.
     */
    internal fun installPromptHooks(rootfs: File, distroName: String) {
        val home = File(rootfs, "root").apply { mkdirs() }
        val snippet = File(home, ".termx/prompt.sh").apply {
            parentFile?.mkdirs()
            if (!exists()) writeText(
                """# TermX prompt: names the running distro instead of "localhost".
# Add TERMX_PROMPT=off to your session environment to keep a stock prompt.
if [ "${'$'}{TERMX_PROMPT:-on}" = "off" ]; then
    return 0 2>/dev/null || exit 0
fi
__termx_host="${'$'}{TERMX_DISTRO:-localhost}"
if [ -n "${'$'}ZSH_VERSION" ]; then
    PROMPT='%F{213}🐙 %F{81}%n@'"${'$'}__termx_host"'%f:%F{114}%~%f%F{244}$%f '
else
    PS1='\[\e[38;5;213m\]🐙 \[\e[38;5;81m\]\u@'"${'$'}__termx_host"'\[\e[38;5;244m\]:\[\e[38;5;114m\]\w\[\e[38;5;244m\]\$ \[\e[0m\]'
fi
unset __termx_host
""".trimStart() + "\n",
            )
        }
        val sourceLine = "[ -r ~/.termx/prompt.sh ] && . ~/.termx/prompt.sh"
        val marker = "# TermX prompt"
        // Only adopt an rc file that does not already define a prompt, so a user who wrote their
        // own PS1/PROMPT keeps it; the TermX prompt then covers every distro's stock default.
        fun ensureSourced(rc: File, promptVar: String) {
            if (!rc.exists()) {
                rc.parentFile?.mkdirs()
                rc.writeText("# TermX defaults.\n$marker\n$sourceLine\n")
            } else {
                val text = rc.readText()
                if (!text.contains(".termx/prompt.sh")) {
                    rc.appendText("\n$marker\n$sourceLine\n")
                }
            }
        }
        // Bash: append so the TermX prompt wins over the distro's own PS1 without deleting it.
        ensureSourced(File(home, ".bashrc"), "PS1")
        // Alpine and other busybox images default to /bin/sh, which reads ~/.profile.
        ensureSourced(File(home, ".profile"), "PS1")
        // zsh reads ~/.zshrc; the snippet picks PROMPT itself when $ZSH_VERSION is set.
        ensureSourced(File(home, ".zshrc"), "PROMPT")
    }

    private fun File.readTextOrNull(): String? = runCatching { readText() }.getOrNull()

    /** Snapshot host /proc files so htop/top work under proot on Android. */
    internal fun snapshotProcFiles(tempDir: File): List<Bind> {
        val procDir = File(tempDir, "proc-snapshot").apply { mkdirs() }
        val result = mutableListOf<Bind>()
        val files = listOf("stat", "vmstat", "meminfo", "uptime", "loadavg", "cpuinfo", "version")
        for (name in files) {
            val source = File("/proc", name)
            if (!source.canRead()) continue
            val target = File(procDir, name)
            runCatching { source.copyTo(target, overwrite = true) }
            if (target.exists() && target.length() > 0) {
                result.add(Bind(target.absolutePath, "/proc/$name"))
            }
        }
        return result
    }

    /** Keeps the guest's /etc/hostname in step so `hostname` and `\h` name the distro too. */
    internal fun writeGuestHostname(rootfs: File, distroName: String) {
        val safe = distroName.lowercase().replace(Regex("[^a-z0-9-]"), "-").take(64).ifBlank { "localhost" }
        val hostname = File(rootfs, "etc/hostname")
        if (java.nio.file.Files.isSymbolicLink(hostname.toPath())) return
        hostname.parentFile?.mkdirs()
        if (hostname.readTextOrNull()?.trim() != safe) hostname.writeText("$safe\n")
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
