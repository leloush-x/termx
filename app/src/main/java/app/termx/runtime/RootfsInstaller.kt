package app.termx.runtime

import android.content.Context
import android.system.Os
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

/** Downloads on an explicit user action, verifies while streaming, extracts, then switches atomically. */
class RootfsInstaller(private val context: Context) {
    fun installedRoot(distroId: String): File = File(File(context.noBackupFilesDir, "distros"), distroId)

    fun install(pkg: RootfsPackage, progress: (Long, Long) -> Unit = { _, _ -> }): File {
        val distroDir = File(context.noBackupFilesDir, "distros").apply { mkdirs() }
        val partial = File(context.cacheDir, "${pkg.distroId}-${pkg.abi}.tar.gz.part")
        val archive = File(context.cacheDir, "${pkg.distroId}-${pkg.abi}.tar.gz")
        downloadAndVerify(pkg, partial, archive, progress)

        val staging = File(distroDir, ".${pkg.distroId}.installing")
        val target = File(distroDir, pkg.distroId)
        val previous = File(distroDir, ".${pkg.distroId}.previous")
        staging.deleteRecursively()
        RootfsExtractor.install(archive, staging)
        File(staging, "root/shared").mkdirs()
        File(staging, "dev").mkdirs()
        File(staging, "proc").mkdirs()
        File(staging, "sys").mkdirs()
        File(staging, ".termx-distro.json").writeText(
            JSONObject()
                .put("id", pkg.distroId)
                .put("version", pkg.version)
                .put("abi", pkg.abi)
                .put("sha256", pkg.sha256)
                .put("installedAt", System.currentTimeMillis())
                .toString(),
        )

        if (!target.exists() && previous.exists()) {
            Os.rename(previous.absolutePath, target.absolutePath)
        }
        previous.deleteRecursively()
        if (target.exists()) {
            Os.rename(target.absolutePath, previous.absolutePath)
        }
        try {
            Os.rename(staging.absolutePath, target.absolutePath)
        } catch (failure: Exception) {
            if (previous.exists() && !target.exists()) Os.rename(previous.absolutePath, target.absolutePath)
            staging.deleteRecursively()
            throw failure
        }
        archive.delete()
        return target
    }

    private fun downloadAndVerify(
        pkg: RootfsPackage,
        partial: File,
        archive: File,
        progress: (Long, Long) -> Unit,
    ) {
        if (archive.isFile && archive.length() == pkg.size && sha256(archive) == pkg.sha256) return
        archive.delete()
        if (partial.length() > pkg.size) partial.delete()

        val digest = MessageDigest.getInstance("SHA-256")
        var offset = partial.length()
        if (offset > 0) FileInputStream(partial).use { it.updateDigest(digest) }
        if (offset == pkg.size) {
            verifyPartial(pkg, partial, digest, archive)
            return
        }

        val connection = (URL(pkg.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            requestMethod = "GET"
            if (offset > 0) setRequestProperty("Range", "bytes=$offset-")
        }
        try {
            val status = connection.responseCode
            require(connection.url.protocol == "https") { "Rootfs download redirected away from HTTPS" }
            if (status == 416 && offset == pkg.size) {
                verifyPartial(pkg, partial, digest, archive)
                return
            }
            require(status == HttpURLConnection.HTTP_OK || status == HttpURLConnection.HTTP_PARTIAL) {
                "Rootfs download failed: HTTP $status"
            }
            val append = offset > 0 && status == HttpURLConnection.HTTP_PARTIAL
            if (append) {
                val contentRange = connection.getHeaderField("Content-Range") ?: ""
                require(contentRange.startsWith("bytes $offset-")) { "Invalid range response" }
            } else {
                offset = 0
                digest.reset()
                partial.delete()
            }

            connection.inputStream.use { input ->
                FileOutputStream(partial, append).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = offset
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= pkg.size) { "Rootfs download exceeded its signed size" }
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                        progress(total, pkg.size)
                    }
                }
            }
            verifyPartial(pkg, partial, digest, archive)
        } finally {
            connection.disconnect()
        }
    }

    private fun verifyPartial(pkg: RootfsPackage, partial: File, digest: MessageDigest, archive: File) {
        val actual = digest.digest().toHex()
        require(partial.length() == pkg.size) { "Rootfs download is incomplete" }
        if (actual != pkg.sha256) {
            partial.delete()
            throw SecurityException("Rootfs SHA-256 mismatch")
        }
        archive.delete()
        Os.rename(partial.absolutePath, archive.absolutePath)
    }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256").let { digest ->
        FileInputStream(file).use { it.updateDigest(digest) }
        digest.digest().toHex()
    }

    private fun InputStream.updateDigest(digest: MessageDigest) {
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = read(buffer)
            if (count < 0) return
            digest.update(buffer, 0, count)
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") {
        "%02x".format(Locale.ROOT, it.toInt() and 0xFF)
    }
}
