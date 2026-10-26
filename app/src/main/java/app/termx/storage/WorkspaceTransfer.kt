package app.termx.storage

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption

/** Explicit file copies between user-picked SAF folders and TermX's app-owned shared folder. */
object WorkspaceTransfer {
    fun copyDocumentIntoShared(context: Context, source: Uri, sharedDirectory: File): Int = guarded {
        prepareShared(sharedDirectory)
        val name = displayName(context, source) ?: "imported-file"
        val destination = uniqueFile(sharedDirectory, name)
        context.contentResolver.openInputStream(source)?.use { input ->
            destination.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IOException("The selected file could not be opened")
        1
    }

    fun copyWorkspaceIntoShared(context: Context, treeUri: Uri, sharedDirectory: File): Int = guarded {
        prepareShared(sharedDirectory)
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, rootId)
        var copied = 0
        context.contentResolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE),
            null, null, null,
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (cursor.moveToNext()) {
                if (cursor.getString(mimeColumn) == DocumentsContract.Document.MIME_TYPE_DIR) continue
                val document = DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(idColumn))
                val destination = uniqueFile(sharedDirectory, cursor.getString(nameColumn) ?: "imported-file")
                context.contentResolver.openInputStream(document)?.use { input ->
                    destination.outputStream().use { output -> input.copyTo(output) }
                } ?: throw IOException("A workspace file could not be opened")
                copied++
            }
        } ?: throw IOException("The selected workspace could not be listed")
        copied
    }

    fun copySharedIntoWorkspace(context: Context, sharedDirectory: File, treeUri: Uri): Int = guarded {
        if (!sharedDirectory.isDirectory) return@guarded 0
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val root = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId)
        var copied = 0
        sharedDirectory.listFiles()?.filter(::isRegularFileWithoutFollowingLinks)?.forEach { source ->
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(source.extension.lowercase())
                ?: "application/octet-stream"
            val target = DocumentsContract.createDocument(context.contentResolver, root, mime, source.name)
                ?: throw IOException("A workspace file could not be created")
            context.contentResolver.openOutputStream(target)?.use { output ->
                source.inputStream().use { it.copyTo(output) }
            } ?: throw IOException("A workspace file could not be written")
            copied++
        }
        copied
    }

    fun exportSharedToDownloads(context: Context, sharedDirectory: File): Int = guarded {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            throw IOException("Downloads export through MediaStore requires Android 10 or newer")
        }
        if (!sharedDirectory.isDirectory) return@guarded 0
        var copied = 0
        sharedDirectory.listFiles()?.filter(::isRegularFileWithoutFollowingLinks)?.forEach { source ->
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, source.name)
                put(MediaStore.MediaColumns.MIME_TYPE,
                    MimeTypeMap.getSingleton().getMimeTypeFromExtension(source.extension.lowercase())
                        ?: "application/octet-stream")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/TermX")
            }
            val target = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("A Downloads file could not be created")
            try {
                context.contentResolver.openOutputStream(target)?.use { output ->
                    source.inputStream().use { it.copyTo(output) }
                } ?: throw IOException("A Downloads file could not be written")
            } catch (error: Exception) {
                runCatching { context.contentResolver.delete(target, null, null) }
                throw error
            }
            copied++
        }
        copied
    }

    fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()

    private fun prepareShared(sharedDirectory: File) {
        if ((!sharedDirectory.exists() && !sharedDirectory.mkdirs()) || !sharedDirectory.isDirectory || !sharedDirectory.canWrite()) {
            throw IOException("The app shared folder is not writable")
        }
    }

    private fun uniqueFile(directory: File, requestedName: String): File {
        val safeName = requestedName.substringAfterLast('/').substringAfterLast('\\')
            .takeUnless { it.isBlank() || it == "." || it == ".." } ?: "imported-file"
        var candidate = File(directory, safeName)
        var index = 1
        val stem = safeName.substringBeforeLast('.', safeName)
        val extension = safeName.substringAfterLast('.', "").takeIf { it != safeName }
        while (candidate.exists()) {
            val suffix = if (extension == null) "-$index" else "-$index.$extension"
            candidate = File(directory, "$stem$suffix")
            index++
        }
        return candidate
    }

    private inline fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (error: Exception) {
        val hint = StorageAccess.permissionHint(error)
        if (hint == null) throw error
        throw IOException(hint, error)
    }

    private fun isRegularFileWithoutFollowingLinks(file: File): Boolean =
        Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)
}
