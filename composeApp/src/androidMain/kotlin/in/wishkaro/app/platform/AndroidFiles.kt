package `in`.wishkaro.app.platform

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Turns MainActivity's two file launchers into suspending calls. */
object FilePickerBridge {
    @Volatile
    var opener: (suspend () -> Uri?)? = null

    @Volatile
    var creator: (suspend (String) -> Uri?)? = null

    val pendingOpen = CompletableDeferredHolder<Uri?>()
    val pendingCreate = CompletableDeferredHolder<Uri?>()

    class CompletableDeferredHolder<T> {
        @Volatile
        var deferred: CompletableDeferred<T>? = null

        fun complete(value: T) {
            deferred?.complete(value)
            deferred = null
        }
    }
}

/**
 * ACTION_OPEN_DOCUMENT and ACTION_CREATE_DOCUMENT, through the ActivityResult contracts.
 *
 * OPEN_DOCUMENT rather than GET_CONTENT: it is the scoped-storage-era picker, it gives a
 * stable URI, and it means this app declares no storage permission at all. The bytes are
 * read once, straight into memory, and the URI is not kept — an import is a one-off, and a
 * persistable grant on the user's spreadsheet is a thing to still have access to tomorrow
 * for no reason.
 */
class AndroidFileGateway(private val context: Context) : FileGateway {

    override suspend fun pickSpreadsheet(): PickedFile? {
        val uri = FilePickerBridge.opener?.invoke() ?: return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: return@runCatching null
                PickedFile(displayName(uri), bytes)
            }.getOrNull()
        }
    }

    override suspend fun saveCsv(suggestedName: String, content: String): Boolean {
        val uri = FilePickerBridge.creator?.invoke(suggestedName) ?: return false
        return withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use {
                    // A BOM, so Excel opens the file as UTF-8 rather than guessing. Without
                    // it, a corrected CSV carrying a Devanagari or Gujarati name comes back
                    // as mojibake in the very tool the user was sent to fix it in.
                    it.write("﻿".toByteArray())
                    it.write(content.toByteArray())
                } != null
            }.getOrDefault(false)
        }
    }

    private fun displayName(uri: Uri): String {
        val cursor = context.contentResolver.query(uri, null, null, null, null) ?: return "file"
        return cursor.use {
            val column = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (column >= 0 && it.moveToFirst()) it.getString(column) ?: "file" else "file"
        }
    }
}
