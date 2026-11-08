package app.termx.diagnostics

import java.util.concurrent.TimeUnit

/** Shizuku UserService implementation. It can touch only the two documented phantom settings. */
class PhantomSettingsUserService : IPhantomSettingsService.Stub() {
    override fun destroy() {
        // Shizuku's UserService contract uses this transaction when unbinding.
        // This dedicated process performs no persistent work and can exit safely.
        kotlin.system.exitProcess(0)
    }

    override fun readGlobal(key: String): String = runSettings("get", key, null)

    override fun writeGlobal(key: String, value: String): String = runSettings("put", key, value)

    override fun deleteGlobal(key: String): String = runSettings("delete", key, null)

    private fun runSettings(operation: String, key: String, value: String?): String {
        require(key in ALLOWED_KEYS) { "setting is not allowed" }
        require(operation == "get" || operation == "put" || operation == "delete") { "operation is not allowed" }
        if (operation == "put") require(isAllowedWrite(key, value)) { "invalid value for setting" }
        val args = buildList {
            add("/system/bin/settings")
            add(operation)
            add("global")
            add(key)
            if (value != null) add(value)
        }
        val process = ProcessBuilder(args).redirectErrorStream(true).start()
        if (!process.waitFor(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            error("settings command timed out")
        }
        val output = process.inputStream.bufferedReader().use { it.readText().trim().take(MAX_OUTPUT) }
        check(process.exitValue() == 0) { output.ifBlank { "settings command failed" } }
        return if (operation == "get" && output == "null") UNSET else output
    }

    companion object {
        const val UNSET = "@termx-unset@"
        private const val TIMEOUT_MILLIS = 2_000L
        private const val MAX_OUTPUT = 256
        const val MONITOR_KEY = "settings_enable_monitor_phantom_procs"
        const val PROCESS_LIMIT_KEY = "max_phantom_processes"
        private val ALLOWED_KEYS = setOf(MONITOR_KEY, PROCESS_LIMIT_KEY)

        internal fun isAllowedWrite(key: String, value: String?): Boolean {
            if (value.isNullOrBlank() || value.length > 64 || key !in ALLOWED_KEYS) return false
            return when (key) {
                MONITOR_KEY -> value in setOf("true", "false", "1", "0")
                PROCESS_LIMIT_KEY -> value.toLongOrNull()?.let { it in 1L..Int.MAX_VALUE.toLong() } == true
                else -> false
            }
        }
    }
}
