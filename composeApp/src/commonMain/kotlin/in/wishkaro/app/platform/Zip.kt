package `in`.wishkaro.app.platform

/**
 * Read the XML parts out of a zip container.
 *
 * This is the ENTIRE platform surface of `.xlsx` support, and it is deliberately this small.
 * An `.xlsx` is a zip of XML; opening the zip is the only part that needs a platform, and
 * everything hard about a spreadsheet — shared strings, skipped columns, whether a number is
 * a date — is pure Kotlin in `Xlsx.kt`, tested in `commonTest` against committed fixtures.
 * Putting the parser behind this seam instead would have put the risky half on the side the
 * shared tests cannot reach. See docs/M3_PLAN.md §2.2.
 *
 * Returns the `.xml` and `.rels` entries only, decoded as UTF-8, keyed by their path in the
 * archive. A container this cannot read returns an empty map rather than throwing, so the
 * caller reports "this file could not be read" instead of crashing on a renamed `.zip`.
 */
expect fun readZipTextEntries(bytes: ByteArray): Map<String, String>
