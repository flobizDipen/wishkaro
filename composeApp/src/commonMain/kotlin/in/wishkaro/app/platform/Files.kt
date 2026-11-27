package `in`.wishkaro.app.platform

/** A file the user chose, held in memory. [name] is for display and for error messages. */
data class PickedFile(val name: String, val bytes: ByteArray) {
    // ByteArray has identity equals, which makes the enclosing data class lie. Compared by
    // name and size: enough for the one thing this is used for, which is telling two picks
    // apart in a state flow.
    override fun equals(other: Any?): Boolean =
        this === other || (other is PickedFile && name == other.name && bytes.size == other.bytes.size)

    override fun hashCode(): Int = 31 * name.hashCode() + bytes.size
}

/**
 * Choosing a file to import, and saving the failure report back out (FR-IMP-04, FR-IMP-07).
 *
 * Both directions go through the system picker, deliberately. Reading means the app never
 * needs storage permission and never holds a path it could come back to later; writing means
 * the user says where the corrected CSV goes, so a file full of family birthdays is not left
 * in a cache directory for the next app that asks.
 */
interface FileGateway {
    /** Null when the user backed out, or when the file could not be read. */
    suspend fun pickSpreadsheet(): PickedFile?

    /** True when the file was written. False covers both "cancelled" and "failed". */
    suspend fun saveCsv(suggestedName: String, content: String): Boolean

    /**
     * The same save, **without the UTF-8 BOM** `saveCsv` writes (M9, FR-SET-03).
     *
     * Not a parameter on `saveCsv`, because the two have opposite requirements and a boolean
     * at the call site is a thing people get backwards. A CSV wants the BOM so Excel does not
     * render a Gujarati name as mojibake; a JSON file must NOT have one, because several
     * parsers — `JSON.parse` among them — treat a leading BOM as a syntax error, and an export
     * nobody can open is not an export.
     */
    suspend fun saveTextFile(suggestedName: String, content: String): Boolean

    /**
     * One photo of a person (FR-PPL-04, and FR-POS-01's "the person's unmodified photo").
     *
     * Through the SYSTEM photo picker, which is the whole reason this is a separate method
     * rather than `pickSpreadsheet` with different MIME types. On Android 13+ the photo
     * picker needs **no permission at all** — the user chooses one image and the app
     * receives only that image, where `READ_MEDIA_IMAGES` would hand this app the entire
     * camera roll for the life of the grant. This app reads one contact list under a
     * rationale and otherwise asks for nothing; a gallery permission to put one face on one
     * poster would be the largest ask in the manifest for the smallest feature.
     *
     * [PickedFile.name] carries the MIME type where the picker gave one, because the
     * upload endpoint needs a content type and sniffing it from the bytes is the thing
     * `media.controller.ts` already refuses to do.
     *
     * Null when the user backed out or the bytes could not be read.
     */
    suspend fun pickPhoto(): PickedPhoto?
}

/** A chosen image and the content type to upload it as. */
data class PickedPhoto(val bytes: ByteArray, val mimeType: String) {
    // ByteArray has identity equals, which would make the enclosing data class lie.
    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is PickedPhoto && mimeType == other.mimeType && bytes.size == other.bytes.size)

    override fun hashCode(): Int = 31 * mimeType.hashCode() + bytes.size
}
