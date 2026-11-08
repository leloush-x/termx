package app.termx.composer

/** Pure helpers for the private composer history and named snippet fields. */
object ComposerHistory {
    const val MAX_ENTRIES = 200

    fun append(entries: List<String>, command: String): List<String> =
        (listOf(command) + entries.filterNot { it == command }).take(MAX_ENTRIES)
}

object SnippetTemplates {
    private val placeholder = Regex("\\{\\{([A-Za-z][A-Za-z0-9_]{0,31})}}")

    fun placeholders(template: String): List<String> =
        placeholder.findAll(template).map { it.groupValues[1] }.distinct().toList()

    fun fill(template: String, values: Map<String, String>): String =
        placeholder.replace(template) { match -> values[match.groupValues[1]].orEmpty() }
}
