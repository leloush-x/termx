package app.termx.session

/** A command is supervised only after the user registers its process name and shell command. */
data class WatchedCommand(val processName: String, val command: String)

object WatchedCommandRegistry {
    fun parse(text: String): Result<List<WatchedCommand>> = runCatching {
        require(text.length <= 4096) { "list is too long" }
        val commands = text.lineSequence().map(String::trim).filter(String::isNotEmpty).map { line ->
            val separator = line.indexOf('=')
            require(separator in 1 until line.lastIndex) { "use process-name=command" }
            val name = line.substring(0, separator).trim()
            val command = line.substring(separator + 1).trim()
            require(name.matches(Regex("[A-Za-z0-9_.-]{1,64}"))) { "invalid process name" }
            require(command.length <= 512 && command.none { it == '\u0000' || it == '\n' || it == '\r' || (it.code < 0x20 && it != '\t') }) {
                "commands must fit one safe line and be at most 512 characters"
            }
            WatchedCommand(name, command)
        }.toList()
        require(commands.map(WatchedCommand::processName).distinct().size == commands.size) { "duplicate process name" }
        commands
    }
}

/** Runtime state for one explicitly registered daemon in one session. */
class WatchedCommandRuntime(
    private val backoff: WatchedCommandBackoff = WatchedCommandBackoff(),
    private val startupGraceMillis: Long = 30_000L,
) {
    var enabled: Boolean = false
        private set
    var gaveUp: Boolean = false
        private set
    private var lastStartedAt: Long? = null
    private var retryAt: Long? = null
    private var processObserved = false

    fun enable(nowMillis: Long): Boolean {
        enabled = true
        gaveUp = false
        retryAt = null
        lastStartedAt = nowMillis
        processObserved = false
        backoff.clear()
        return true
    }

    fun disable() {
        enabled = false
        retryAt = null
        lastStartedAt = null
        processObserved = false
    }

    /** Returns true when the caller should relaunch the registered command now. */
    fun update(processAlive: Boolean, nowMillis: Long): Boolean {
        if (!enabled || gaveUp) return false
        if (processAlive) {
            retryAt = null
            lastStartedAt = null
            processObserved = true
            return false
        }
        val scheduledRetry = retryAt
        if (scheduledRetry != null) {
            if (nowMillis < scheduledRetry) return false
            retryAt = null
            lastStartedAt = nowMillis
            return true
        }
        val started = lastStartedAt
        if (started != null) {
            if (nowMillis - started < startupGraceMillis) return false
        } else if (!processObserved) {
            return false
        }
        processObserved = false
        val delay = backoff.nextDelayMillis(nowMillis)
        if (delay == null) {
            gaveUp = true
            return false
        }
        lastStartedAt = null
        retryAt = nowMillis + delay
        return false
    }
}
