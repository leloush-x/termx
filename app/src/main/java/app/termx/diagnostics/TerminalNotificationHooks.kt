package app.termx.diagnostics

import android.content.Context
import java.io.File

/** Installs an opt-in prompt hook only in TermX-owned distro rootfs homes. Call on Dispatchers.IO. */
internal object TerminalNotificationHooks {
    private const val BASH_LINE = "[ -r ~/.bashrc.d/termx-notifications.sh ] && . ~/.bashrc.d/termx-notifications.sh"
    private const val ZSH_LINE = "[ -r ~/.zshrc.d/termx-notifications.zsh ] && . ~/.zshrc.d/termx-notifications.zsh"

    fun setEnabled(context: Context, enabled: Boolean) {
        val distros = File(context.noBackupFilesDir, "distros")
        if (!distros.isDirectory) return
        val base = distros.canonicalFile
        base.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }?.forEach { distro ->
            val rootfs = distro.canonicalFile
            require(rootfs.parentFile == base) { "Distro path escaped TermX storage" }
            val home = File(rootfs, "root/root")
            if (enabled) {
                home.mkdirs()
                installHook(File(home, ".bashrc.d/termx-notifications.sh"), BASH_HOOK)
                installHook(File(home, ".zshrc.d/termx-notifications.zsh"), ZSH_HOOK)
                addSourceLine(File(home, ".bashrc"), BASH_LINE)
                addSourceLine(File(home, ".bash_profile"), BASH_LINE)
                addSourceLine(File(home, ".zshrc"), ZSH_LINE)
            } else {
                removeSourceLine(File(home, ".bashrc"), BASH_LINE)
                removeSourceLine(File(home, ".bash_profile"), BASH_LINE)
                removeSourceLine(File(home, ".zshrc"), ZSH_LINE)
            }
        }
    }

    private fun installHook(file: File, content: String) {
        file.parentFile?.mkdirs()
        if (!file.exists()) file.writeText(content)
    }

    private fun addSourceLine(file: File, source: String) {
        val old = if (file.isFile) file.readText() else ""
        if (old.lineSequence().any { it.trim() == source }) return
        file.parentFile?.mkdirs()
        file.appendText("\n# TermX optional terminal notifications\n$source\n")
    }

    private fun removeSourceLine(file: File, source: String) {
        if (!file.isFile) return
        val lines = file.readLines()
        val result = lines.filterNot { it.trim() == source || it.trim() == "# TermX optional terminal notifications" }
        if (result.size != lines.size) file.writeText(result.joinToString("\n").trimEnd() + "\n")
    }

    private val BASH_HOOK = """# Optional TermX shell integration. Measures time between interactive prompts.
__termx_notify_last=${'$'}SECONDS
__termx_notify_ready=0
__termx_notify_prompt() {
    local ec=${'$'}? now=${'$'}SECONDS elapsed=0
    if (( __termx_notify_ready )); then elapsed=${'$'}((now - __termx_notify_last)); fi
    __termx_notify_last=${'$'}now
    if (( __termx_notify_ready )); then
        printf '\033]133;D;%s\007' "${'$'}ec"
        if (( elapsed >= 10 )); then printf '\033]9;Command finished after %ss\007' "${'$'}elapsed"; fi
    else
        __termx_notify_ready=1
    fi
}
case ";${'$'}{PROMPT_COMMAND:-};" in *";__termx_notify_prompt;"*) ;; *) PROMPT_COMMAND="__termx_notify_prompt${'$'}{PROMPT_COMMAND:+;${'$'}PROMPT_COMMAND}" ;; esac
""".trimIndent() + "\n"

    private val ZSH_HOOK = """# Optional TermX shell integration. Measures time between interactive prompts.
typeset -g __termx_notify_last=${'$'}SECONDS __termx_notify_ready=0
__termx_notify_prompt() {
  local ec=${'$'}? now=${'$'}SECONDS elapsed=0
  (( __termx_notify_ready )) && elapsed=${'$'}((now - __termx_notify_last))
  __termx_notify_last=${'$'}now
  if (( __termx_notify_ready )); then
    printf '\033]133;D;%s\007' "${'$'}ec"
    (( elapsed >= 10 )) && printf '\033]9;Command finished after %ss\007' "${'$'}elapsed"
  else
    __termx_notify_ready=1
  fi
}
(( ${'$'}{precmd_functions[(Ie)__termx_notify_prompt]} )) || precmd_functions+=(__termx_notify_prompt)
""".trimIndent() + "\n"
}
