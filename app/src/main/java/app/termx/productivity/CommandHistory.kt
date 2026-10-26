package app.termx.productivity

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Room
import androidx.room.RoomRawQuery
import androidx.room.RoomDatabase
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit

@Entity(tableName = "command_history")
data class HistoryEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: String = "default",
    val sessionId: String? = null,
    val cwd: String,
    val command: String,
    val startedAt: Long,
    val exitCode: Int,
)

@Dao
interface HistoryDao {
    @Insert
    fun insert(entries: List<HistoryEntry>): List<Long>

    @RawQuery
    fun search(query: RoomRawQuery): List<HistoryEntry>

    @Query("DELETE FROM command_history WHERE startedAt < :cutoff")
    fun deleteOlderThan(cutoff: Long): Int

    @Query("DELETE FROM command_history")
    fun clear(): Int
}

@Database(entities = [HistoryEntry::class], version = 1, exportSchema = false)
abstract class HistoryDatabase : RoomDatabase() {
    abstract fun history(): HistoryDao

    companion object {
        @Volatile private var instance: HistoryDatabase? = null

        fun get(context: Context): HistoryDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context, HistoryDatabase::class.java, "termx-history.db")
                .addCallback(object : Callback() {
                    override fun onCreate(connection: SQLiteConnection) {
                        super.onCreate(connection)
                        createSearchIndex(connection)
                    }

                    override fun onOpen(connection: SQLiteConnection) {
                        super.onOpen(connection)
                        createSearchIndex(connection)
                    }
                })
                .setDriver(BundledSQLiteDriver())
                .build()
                .also { instance = it }
        }

        private fun createSearchIndex(connection: SQLiteConnection) {
            connection.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS command_fts USING fts5(command, cwd, content='command_history', content_rowid='id')")
            connection.execSQL("""CREATE TRIGGER IF NOT EXISTS history_ai AFTER INSERT ON command_history BEGIN
                INSERT INTO command_fts(rowid, command, cwd) VALUES (new.id, new.command, new.cwd);
            END""")
            connection.execSQL("""CREATE TRIGGER IF NOT EXISTS history_ad AFTER DELETE ON command_history BEGIN
                INSERT INTO command_fts(command_fts, rowid, command, cwd) VALUES ('delete', old.id, old.command, old.cwd);
            END""")
        }
    }
}

/** Imports the private, append-only shell spool and consumes it only after the DB commit succeeds. */
class HistoryImporter(private val database: HistoryDatabase) {
    fun import(file: File, profileId: String = "default", redactionPatterns: List<Regex> = DEFAULT_REDACTIONS): Int {
        if (!file.isFile) return 0
        val lines = file.readLines()
        val pending = lines.take(MAX_LINES).mapNotNull { decode(it, profileId) }
            .filterNot { entry -> redactionPatterns.any { it.containsMatchIn(entry.command) } }
        if (pending.isNotEmpty()) database.history().insert(pending)
        val remainder = lines.drop(MAX_LINES)
        file.writeText(if (remainder.isEmpty()) "" else remainder.joinToString("\n", postfix = "\n"))
        database.history().deleteOlderThan(System.currentTimeMillis() - RETENTION_MS)
        return pending.size
    }

    private fun decode(line: String, profileId: String): HistoryEntry? = runCatching {
        val fields = line.split('\t')
        require(fields.size == 4)
        val command = String(Base64.getDecoder().decode(fields[3]), Charsets.UTF_8)
        val cwd = String(Base64.getDecoder().decode(fields[2]), Charsets.UTF_8)
        require(command.isNotBlank() && command.length <= MAX_COMMAND_CHARS && cwd.length <= MAX_CWD_CHARS)
        HistoryEntry(profileId = profileId, cwd = cwd, command = command,
            startedAt = fields[0].toLong(), exitCode = fields[1].toInt())
    }.getOrNull()

    companion object {
        const val MAX_BYTES = 5 * 1024 * 1024L
        private const val MAX_LINES = 100_000
        private const val MAX_COMMAND_CHARS = 16_384
        private const val MAX_CWD_CHARS = 4_096
        private val RETENTION_MS = TimeUnit.DAYS.toMillis(90)
        val DEFAULT_REDACTIONS = listOf(
            Regex("(?i)password\\s*="), Regex("(?i)token\\s*="), Regex("(?i)secret\\s*="),
            Regex("(?i)apikey\\s*="), Regex("(?i)authorization:"),
        )
    }
}

object HistoryRedactions {
    private const val PREFS = "history_privacy"
    private const val KEY_RULES = "redaction_rules"

    fun text(context: Context): String = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(KEY_RULES, null)
        ?: HistoryImporter.DEFAULT_REDACTIONS.joinToString("\n") { it.pattern }

    fun patterns(context: Context): List<Regex> = text(context).lineSequence()
        .map(String::trim).filter(String::isNotEmpty).map { Regex(it, RegexOption.IGNORE_CASE) }.toList()

    fun save(context: Context, rules: String) {
        val parsed = rules.lineSequence().map(String::trim).filter(String::isNotEmpty).map { Regex(it, RegexOption.IGNORE_CASE) }.toList()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_RULES, parsed.joinToString("\n") { it.pattern }).apply()
    }
}

fun searchHistory(database: HistoryDatabase, text: String): List<HistoryEntry> {
    val terms = text.trim().split(Regex("\\s+")).filter(String::isNotBlank)
    if (terms.isEmpty()) return emptyList()
    val match = terms.joinToString(" AND ") { "\"${it.replace("\"", "\"\"")}\"*" }
    val sql = "SELECT h.* FROM command_history h JOIN command_fts f ON h.id=f.rowid WHERE command_fts MATCH ? ORDER BY h.startedAt DESC LIMIT 200"
    return database.history().search(RoomRawQuery(sql) { statement -> statement.bindText(1, match) })
}
