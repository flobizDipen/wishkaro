package `in`.wishkaro.app.platform

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * The whole Android half of reading a spreadsheet: thirty lines over `java.util.zip`.
 *
 * `ZipInputStream` rather than `ZipFile` because the bytes come from a content:// URI and
 * never exist as a file on disk — copying a 10 MB import to the cache directory just to open
 * it would leave the user's spreadsheet lying around after the import, which is the opposite
 * of what M3's privacy rules ask for.
 */
actual fun readZipTextEntries(bytes: ByteArray): Map<String, String> {
    val entries = mutableMapOf<String, String>()
    return runCatching {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                if (!entry.isDirectory && (name.endsWith(".xml") || name.endsWith(".rels"))) {
                    entries[name] = zip.readBytes().decodeToString()
                }
                zip.closeEntry()
            }
        }
        entries.toMap()
    }.getOrDefault(emptyMap())
}
