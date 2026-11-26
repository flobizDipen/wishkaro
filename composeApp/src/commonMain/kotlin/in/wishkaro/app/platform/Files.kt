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
}
