package `in`.wishkaro.app.domain

/**
 * Reading and writing CSV, to RFC 4180 plus the things real exports do anyway.
 *
 * Written by hand rather than taken from a library because Kotlin Multiplatform has no CSV
 * reader worth the dependency, and because the awkward parts here are not the grammar — they
 * are the byte-order mark Excel writes, the semicolons a European locale writes, and the
 * newline inside a quoted address field. Those are each one line, and each one is a row the
 * user would otherwise have to go and fix by hand.
 */
object Csv {

    private const val BOM = '﻿'

    /** Candidates for a file that does not use commas, commonest first. */
    private val DELIMITERS = listOf(',', ';', '\t', '|')

    /**
     * Split a whole file into rows of cells.
     *
     * [delimiter] null means sniff it from the header line. Sniffing the SEPARATOR is not
     * the same kind of guess as inferring dd/MM from the data: a delimiter is a property of
     * the file's encoding that is visible in one line and wrong in an obvious way, while a
     * date order is a property of the author's intent and wrong in a way nobody notices.
     * The mapping screen shows the resulting columns before anything is applied either way.
     */
    fun parse(text: String, delimiter: Char? = null): List<List<String>> {
        val body = text.removePrefix(BOM.toString())
        if (body.isBlank()) return emptyList()
        val separator = delimiter ?: sniffDelimiter(body)

        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var index = 0

        fun endCell() {
            row.add(cell.toString())
            cell.clear()
        }

        fun endRow() {
            endCell()
            rows.add(row.toList())
            row = mutableListOf()
        }

        while (index < body.length) {
            val char = body[index]
            when {
                quoted && char == '"' ->
                    // A doubled quote inside a quoted field is one literal quote — the only
                    // escape RFC 4180 has.
                    if (index + 1 < body.length && body[index + 1] == '"') {
                        cell.append('"')
                        index += 1
                    } else {
                        quoted = false
                    }

                quoted -> cell.append(char)

                char == '"' && cell.isEmpty() -> quoted = true

                // A quote that opens mid-cell is not an opening quote, it is an apostrophe
                // somebody typed. `Rajesh "Raj" Kumar` keeps its quotes rather than becoming
                // a parse error.
                char == '"' -> cell.append(char)

                char == separator -> endCell()

                char == '\r' -> {
                    endRow()
                    if (index + 1 < body.length && body[index + 1] == '\n') index += 1
                }

                char == '\n' -> endRow()

                else -> cell.append(char)
            }
            index += 1
        }

        // A file that does not end in a newline still has a last row; one that does must not
        // gain an empty one.
        if (cell.isNotEmpty() || row.isNotEmpty()) endRow()

        return rows.filterNot { cells -> cells.all { it.isBlank() } }
    }

    /**
     * Which separator the first non-empty line uses.
     *
     * Counted outside quotes, so a comma inside `"Mumbai, Maharashtra"` does not vote for
     * the comma. Ties go to the earlier candidate, which puts the comma first.
     */
    fun sniffDelimiter(text: String): Char {
        val firstLine = text.lineSequence().firstOrNull { it.isNotBlank() } ?: return ','
        var best = ','
        var bestCount = 0
        for (candidate in DELIMITERS) {
            val count = countOutsideQuotes(firstLine, candidate)
            if (count > bestCount) {
                best = candidate
                bestCount = count
            }
        }
        return best
    }

    private fun countOutsideQuotes(line: String, char: Char): Int {
        var quoted = false
        var count = 0
        for (c in line) {
            when {
                c == '"' -> quoted = !quoted
                c == char && !quoted -> count += 1
            }
        }
        return count
    }

    /**
     * Write rows back out, quoting only what has to be quoted.
     *
     * This is the other half of FR-IMP-07: the failures come back as a CSV the user opens in
     * the tool they already use, fixes, and re-runs. A writer that mangled a field with a
     * comma in it would make that round trip lossy, which would make the whole report a
     * worse version of the problem it is reporting.
     *
     * CRLF, because the file's most likely destination is Excel on Windows.
     */
    fun write(rows: List<List<String>>): String =
        rows.joinToString("\r\n") { row -> row.joinToString(",") { escape(it) } }

    private fun escape(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }
}
