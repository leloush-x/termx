package app.termx.customization

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Copies and decodes TTF fonts on a worker thread so font I/O never blocks the UI. */
class FontRepository(context: Context) {
    private val app = context.applicationContext
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "termx-font-loader").apply { isDaemon = true }
    }

    fun import(uri: Uri, onResult: (Result<File>) -> Unit) {
        executor.execute {
            onResult(runCatching {
                val target = File(app.filesDir, "fonts/imported-${System.currentTimeMillis()}.ttf")
                target.parentFile?.mkdirs()
                val input = app.contentResolver.openInputStream(uri) ?: error("font: could not open selected file")
                input.use { source -> target.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var total = 0L
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= MAX_FONT_BYTES) { "font: file exceeds 16 MB" }
                        output.write(buffer, 0, count)
                    }
                } }
                require(Typeface.createFromFile(target).style >= 0) { "font: invalid TrueType file" }
                target
            })
        }
    }

    fun loadImported(file: File, onResult: (Result<Typeface>) -> Unit) {
        executor.execute { onResult(runCatching { Typeface.createFromFile(file) }) }
    }

    fun loadBundled(onResult: (Result<Typeface>) -> Unit) {
        executor.execute {
            onResult(runCatching {
                val target = File(app.cacheDir, "jetbrains-mono.ttf")
                if (!target.exists()) app.assets.open("fonts/jetbrains-mono.ttf").use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                Typeface.createFromFile(target)
            })
        }
    }

    private companion object { const val MAX_FONT_BYTES = 16L * 1024 * 1024 }
}
