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
