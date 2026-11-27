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

    /**
     * The SYSTEM photo picker (M6). A third launcher rather than a MIME list on [opener],
     * because it is a different contract: `PickVisualMedia` needs **no permission at all**
     * on Android 13+ and returns exactly the one image the user chose, where
     * `ACTION_OPEN_DOCUMENT` with image types would work but go through the documents UI
     * rather than the gallery people expect for a photo.
     */
    @Volatile
    var photoPicker: (suspend () -> Uri?)? = null

    val pendingOpen = CompletableDeferredHolder<Uri?>()
    val pendingCreate = CompletableDeferredHolder<Uri?>()
    val pendingPhoto = CompletableDeferredHolder<Uri?>()
}

/**
 * Where a result waits while the Activity that asked for it is being rebuilt.
 *
 * This is the piece `FilePickerBridge` had and the two permission bridges did not, and the
 * asymmetry cost M4 a locked-shut import flow — see `M4_VERIFY.md` §0.6. A deferred held in
 * a FIELD ON THE ACTIVITY is orphaned the moment that Activity is destroyed: the system
 * hands the result to the NEW Activity, whose own field is null, and the coroutine awaiting
 * the old one waits for ever.
 *
 * Global, so there is exactly one place the result can arrive and exactly one place the
 * caller is waiting. Moved out of FilePickerBridge so all three use it.
 */
class CompletableDeferredHolder<T> {
    @Volatile
    var deferred: CompletableDeferred<T>? = null

    fun complete(value: T) {
        deferred?.complete(value)
        deferred = null
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

    /** FR-SET-03's JSON export. The same picker, and deliberately no BOM — see the interface. */
    override suspend fun saveTextFile(suggestedName: String, content: String): Boolean {
        val uri = FilePickerBridge.creator?.invoke(suggestedName) ?: return false
        return withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use {
                    it.write(content.toByteArray())
                } != null
            }.getOrDefault(false)
        }
    }

    /**
     * One photo, through the system picker (FR-PPL-04, FR-POS-01).
     *
     * The content type comes from the `ContentResolver` rather than from the file name or
     * from the bytes. The upload endpoint accepts exactly three types and sniffing the
     * bytes is what `media.controller.ts` already refuses to do; a resolver type is what
     * the platform itself believes the file is.
     *
     * Falls back to `image/jpeg` when the resolver has no opinion — the server re-encodes
     * everything to JPEG anyway and decodes before it trusts the header, so a wrong guess
     * is a 400 with a message rather than a stored file of the wrong kind.
     */
    override suspend fun pickPhoto(): PickedPhoto? {
        val uri = FilePickerBridge.photoPicker?.invoke() ?: return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: return@runCatching null
                val type = context.contentResolver.getType(uri)
                    ?.takeIf { it in ACCEPTED_IMAGE_TYPES }
                    ?: "image/jpeg"
                PickedPhoto(bytes, type)
            }.getOrNull()
        }
    }

    private fun displayName(uri: Uri): String {
        val cursor = context.contentResolver.query(uri, null, null, null, null) ?: return "file"
        return cursor.use {
            val column = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (column >= 0 && it.moveToFirst()) it.getString(column) ?: "file" else "file"
        }
    }

    private companion object {
        /** Exactly what `configure-app.ts` registers a raw parser for. */
        val ACCEPTED_IMAGE_TYPES = setOf("image/jpeg", "image/png", "image/webp")
    }
}
