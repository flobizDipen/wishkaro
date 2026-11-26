package `in`.wishkaro.app.domain

/**
 * Turning the three XML parts of an `.xlsx` into rows of strings.
 *
 * THE SPLIT, and why it is where it is. An `.xlsx` is a zip of XML. Opening the zip needs a
 * platform; everything after it is where the difficulty actually lives — shared-string
 * indirection, inline strings, skipped columns, and the question of whether `45295` is a
 * date or the number forty-five thousand. So the platform half is thirty lines in
 * `androidMain` that read three entries, and ALL of this is pure and tested in `commonTest`
 * against committed fixtures. See docs/M3_PLAN.md §2.2.
 *
 * The output is deliberately `List<List<String>>` — exactly what the CSV reader produces —
 * so that column mapping, date interpretation and dedup have one input shape and are written
 * once. The server never learns what a relation is because the server is not involved.
 */
object Xlsx {

    /**
     * `xl/sharedStrings.xml`: the string table almost every text cell points into.
     *
     * A `<si>` can be one `<t>`, or a run of `<r><t>` fragments when part of the cell was
     * formatted differently — a name where the surname is bold is one string in two pieces,
     * and concatenating the pieces is the only way to get the name back.
     */
    fun sharedStrings(xml: String): List<String> {
        val strings = mutableListOf<String>()
        val cursor = XmlCursor(xml)
        var current: StringBuilder? = null
        while (true) {
            val tag = cursor.next() ?: break
            when {
                tag.name == "si" && !tag.isEnd -> current = StringBuilder()
                tag.name == "si" && tag.isEnd -> {
                    strings += (current ?: StringBuilder()).toString()
                    current = null
                }
                tag.name == "t" && !tag.isEnd && !tag.selfClosing -> current?.append(cursor.text())
            }
        }
        return strings
    }

    /**
     * `xl/styles.xml`, reduced to the one question that matters: is this cell a date?
     *
     * A date in a spreadsheet is a number with a format on it. There is no other difference
     * — `45295` formatted `dd/mm/yyyy` is 4 January 2024 and `45295` formatted `General` is
     * forty-five thousand, and nothing in the cell tells them apart. The format is reached
     * through two indirections: the cell's `s` indexes `cellXfs`, and the `xf` there carries
     * a `numFmtId`, which is either one of Excel's built-ins or a custom format declared in
     * `numFmts`.
     */
    fun dateStyles(xml: String): Set<Int> {
        val customDateFormats = mutableSetOf<Int>()
        val cellXfs = mutableListOf<Int>()
        var inCellXfs = false

        val cursor = XmlCursor(xml)
        while (true) {
            val tag = cursor.next() ?: break
            when {
                tag.name == "numFmt" && !tag.isEnd -> {
                    val id = tag.attributes["numFmtId"]?.toIntOrNull()
                    val code = tag.attributes["formatCode"]
                    if (id != null && code != null && looksLikeADate(code)) customDateFormats += id
                }
                tag.name == "cellXfs" -> inCellXfs = !tag.isEnd
                tag.name == "xf" && !tag.isEnd && inCellXfs ->
                    cellXfs += tag.attributes["numFmtId"]?.toIntOrNull() ?: 0
            }
        }

        return cellXfs.withIndex()
            .filter { (_, numFmtId) -> numFmtId in BUILT_IN_DATE_FORMATS || numFmtId in customDateFormats }
            .mapTo(mutableSetOf()) { it.index }
    }

    /**
     * `xl/worksheets/sheet1.xml` into rows.
     *
     * [dateStyleIndices] comes from [dateStyles]; a numeric cell carrying one of those styles
     * is rendered as `yyyy-MM-dd`, which [ImportDates] then reads unambiguously. Everything
     * else is handed over as the text it is, because deciding what a string MEANS is the
     * client's job and doing it here would be a second place where dates are interpreted.
     */
    fun rows(xml: String, sharedStrings: List<String>, dateStyleIndices: Set<Int>): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        var column = 0
        var cellType: String? = null
        var cellStyle: Int? = null
        var value: String? = null
        var inInlineString = false

        val cursor = XmlCursor(xml)
        while (true) {
            val tag = cursor.next() ?: break
            when {
                tag.name == "row" && !tag.isEnd -> {
                    row = mutableListOf()
                    column = 0
                }

                tag.name == "row" && tag.isEnd -> rows += row.toList()

                tag.name == "c" && !tag.isEnd -> {
                    // `r="C7"` is the cell's address. Empty cells are simply ABSENT from the
                    // file, so without this a row whose Relation column is blank would shift
                    // every later value one column to the left — and the mapping screen
                    // would then be mapping the wrong things with no sign anything was wrong.
                    val target = tag.attributes["r"]?.let { columnIndex(it) }
                    if (target != null) {
                        while (column < target) {
                            row.add("")
                            column += 1
                        }
                    }
                    cellType = tag.attributes["t"]
                    cellStyle = tag.attributes["s"]?.toIntOrNull()
                    value = null
                    inInlineString = false
                    if (tag.selfClosing) {
                        row.add("")
                        column += 1
                    }
                }

                tag.name == "is" -> inInlineString = !tag.isEnd

                tag.name == "t" && !tag.isEnd && !tag.selfClosing && inInlineString ->
                    value = (value ?: "") + cursor.text()

                tag.name == "v" && !tag.isEnd && !tag.selfClosing -> value = cursor.text()

                tag.name == "c" && tag.isEnd -> {
                    row.add(renderCell(value, cellType, cellStyle, sharedStrings, dateStyleIndices))
                    column += 1
                }
            }
        }

        return rows.filterNot { cells -> cells.all { it.isBlank() } }
    }

    // ---------------------------------------------------------------------------------

    /**
     * Excel's built-in date and time formats.
     *
     * 14-22 are the date and time formats; 45-47 are the elapsed-time ones. The gap matters:
     * 23-44 are currency and accounting, and treating one of those as a date would turn a
     * budget column into 1970-ish nonsense.
     */
    private val BUILT_IN_DATE_FORMATS = (14..22).toSet() + setOf(45, 46, 47)

    /**
     * Whether a custom format code describes a date.
     *
     * The letters are read outside quoted literals and outside `[...]` sections, because
     * `"day"` and `[Red]` are not format letters — a currency format of `"d"#,##0` would
     * otherwise look like a date to anything doing a plain `contains`.
     */
    internal fun looksLikeADate(code: String): Boolean {
        var quoted = false
        var bracketed = false
        var i = 0
        while (i < code.length) {
            val c = code[i]
            when {
                quoted -> if (c == '"') quoted = false
                bracketed -> if (c == ']') bracketed = false
                c == '"' -> quoted = true
                c == '[' -> bracketed = true
                c == '\\' -> i += 1 // an escaped literal character
                c in "yYdDmMhHsS" -> return true
            }
            i += 1
        }
        return false
    }

    /** `C7` -> 2. Letters only; the row number is ignored. */
    internal fun columnIndex(reference: String): Int? {
        var index = 0
        var seen = false
        for (c in reference) {
            if (c in 'A'..'Z' || c in 'a'..'z') {
                index = index * 26 + (c.uppercaseChar() - 'A' + 1)
                seen = true
            } else if (seen) {
                break
            }
        }
        return if (seen) index - 1 else null
    }

    private fun renderCell(
        value: String?,
        type: String?,
        style: Int?,
        sharedStrings: List<String>,
        dateStyleIndices: Set<Int>,
    ): String {
        if (value == null) return ""
        return when (type) {
            // The common case: the cell holds an index into the shared string table.
            "s" -> value.toIntOrNull()?.let { sharedStrings.getOrNull(it) } ?: ""
            // Already text: an inline string, or a formula's cached string result.
            "inlineStr", "str" -> value
            "b" -> if (value == "1") "TRUE" else "FALSE"
            // An error cell (#N/A, #REF!) is handed over as written, so the row fails later
            // with a reason the user can see rather than silently becoming empty.
            "e" -> value
            // Some writers emit a real ISO date with t="d" instead of a serial number.
            "d" -> value
            else -> renderNumber(value, style, dateStyleIndices)
        }
    }

    private fun renderNumber(value: String, style: Int?, dateStyleIndices: Set<Int>): String {
        val isDate = style != null && style in dateStyleIndices
        if (isDate) {
            // A date cell can carry a time as a fraction; the whole part is the day, and a
            // birthday has no use for the rest.
            val serial = value.toDoubleOrNull()?.toInt()
            if (serial != null) {
                val parsed = ImportDates.fromExcelSerial(serial)
                if (parsed is DateParse.Parsed) {
                    val date = parsed.date
                    return buildString {
                        append(date.year?.toString()?.padStart(4, '0') ?: "0000")
                        append('-')
                        append(date.month.toString().padStart(2, '0'))
                        append('-')
                        append(date.day.toString().padStart(2, '0'))
                    }
                }
            }
        }
        // A plain number. `1990` must not come back as `1990.0`, because it is almost
        // certainly a year somebody typed into a column of its own.
        val asDouble = value.toDoubleOrNull() ?: return value
        return if (asDouble == asDouble.toLong().toDouble()) asDouble.toLong().toString() else value
    }
}

/**
 * The whole `.xlsx` path, from the archive's entries to rows of strings.
 *
 * Separate from [Xlsx] so that object stays about XML and this stays about the container
 * layout. Pure: the bytes have already been unzipped by the platform.
 */
object XlsxWorkbook {

    /**
     * Rows of the FIRST sheet, or null when the entries are not a spreadsheet at all.
     *
     * "First" is resolved through `workbook.xml` and its relationships rather than by
     * reaching for `sheet1.xml`, because the file names follow creation order and the TABS
     * follow the user's order — a workbook whose first tab is `sheet3.xml` is ordinary, and
     * reading the wrong tab would import somebody's notes page in silence.
     */
    fun firstSheetRows(entries: Map<String, String>): List<List<String>>? {
        val sheetPath = firstSheetPath(entries) ?: return null
        val sheetXml = entries[sheetPath] ?: return null
        val shared = entries["xl/sharedStrings.xml"]?.let { Xlsx.sharedStrings(it) } ?: emptyList()
        val dateStyles = entries["xl/styles.xml"]?.let { Xlsx.dateStyles(it) } ?: emptySet()
        return Xlsx.rows(sheetXml, shared, dateStyles)
    }

    internal fun firstSheetPath(entries: Map<String, String>): String? {
        val workbook = entries["xl/workbook.xml"]
            // No workbook part at all: this is not an xlsx. Fall back only if a sheet is
            // sitting there anyway, which costs nothing and rescues a hand-made file.
            ?: return "xl/worksheets/sheet1.xml".takeIf { it in entries }

        val firstId = firstSheetRelationshipId(workbook)
        val target = firstId?.let { relationshipTarget(entries["xl/_rels/workbook.xml.rels"], it) }
        if (target != null) {
            val normalised = if (target.startsWith("/")) target.removePrefix("/") else "xl/${target.removePrefix("./")}"
            if (normalised in entries) return normalised
        }
        return "xl/worksheets/sheet1.xml".takeIf { it in entries }
            ?: entries.keys.filter { it.startsWith("xl/worksheets/") && it.endsWith(".xml") }.minOrNull()
    }

    private fun firstSheetRelationshipId(workbookXml: String): String? {
        val cursor = XmlCursor(workbookXml)
        while (true) {
            val tag = cursor.next() ?: return null
            if (tag.name == "sheet" && !tag.isEnd) return tag.attributes["id"]
        }
    }

    private fun relationshipTarget(relsXml: String?, id: String): String? {
        if (relsXml == null) return null
        val cursor = XmlCursor(relsXml)
        while (true) {
            val tag = cursor.next() ?: return null
            if (tag.name == "Relationship" && !tag.isEnd && tag.attributes["Id"] == id) {
                return tag.attributes["Target"]
            }
        }
    }
}
