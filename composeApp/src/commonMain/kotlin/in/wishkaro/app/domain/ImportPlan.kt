package `in`.wishkaro.app.domain

import kotlinx.datetime.LocalDate

/**
 * Which source column means what (FR-IMP-05).
 *
 * Indices into the row, or null for "this file has no such column". Only [name] and [date]
 * are required to produce anything; relation can come from [ImportSettings.defaultRelation]
 * instead, which is what makes a file with no relation column importable in one extra tap
 * rather than 300 failed rows.
 */
data class ColumnMapping(
    val name: Int? = null,
    val date: Int? = null,
    val relation: Int? = null,
    val phone: Int? = null,
    val notes: Int? = null,
) {
    val isUsable: Boolean get() = name != null && date != null
}

/** Everything the user chose on the mapping screen, other than the columns. */
data class ImportSettings(
    val order: DateOrder = DateOrder.DAY_FIRST,
    /** Applied to rows whose relation column is empty, or when there is no relation column. */
    val defaultRelation: String? = null,
    /** True when the first row is column headings rather than a person. */
    val hasHeaderRow: Boolean = true,
)

/**
 * A person already in the app, reduced to what the dedup rule needs.
 *
 * Deliberately not [Person]: building the index from full records would mean holding every
 * trait and event of 400 people in memory for the whole of an import, to answer a question
 * that needs a name and a set of day/month pairs.
 */
data class DedupCandidate(
    val id: String,
    val displayName: String,
    val dayMonths: Set<Int>,
) {
    companion object {
        /** Day and month as one comparable number, so the index key is not a data class. */
        fun key(day: Int, month: Int): Int = month * 100 + day

        fun from(person: Person): DedupCandidate = DedupCandidate(
            id = person.id,
            displayName = person.displayName,
            dayMonths = person.events.mapTo(mutableSetOf()) { key(it.date.day, it.date.month) },
        )
    }
}

/**
 * The dedup rule (FR-IMP-08, FR-PPL-06), turned inside out so it can be asked 600 times.
 *
 * `PeopleRepository.findDuplicates` loads every person and re-tokenises every name on each
 * call, which is right for the create path — one call is one call — and quadratic for an
 * import. 600 rows against 400 people is 240,000 tokenisations and 1,200 table scans.
 *
 * So the tokens are computed ONCE, into a token -> ids map, and each row is a set lookup.
 * The rule itself is unchanged: a shared name token AND, when the row has a date, a shared
 * day/month. Same tokens, same comparison, same answers — see [nameTokens], which both this
 * and the server read from one table of cases.
 *
 * Rows accepted earlier in the same run are added as they go, which is what makes a
 * duplicate WITHIN one file the same code path as a duplicate against the existing database
 * (definition-of-done items 5 and 6, one mechanism rather than two).
 */
class DedupIndex(existing: List<DedupCandidate> = emptyList()) {

    private val byToken = mutableMapOf<String, MutableSet<String>>()
    private val byId = mutableMapOf<String, DedupCandidate>()

    init {
        existing.forEach(::add)
    }

    fun add(candidate: DedupCandidate) {
        byId[candidate.id] = candidate
        for (token in nameTokens(candidate.displayName)) {
            byToken.getOrPut(token) { mutableSetOf() } += candidate.id
        }
    }

    /**
     * Everyone this name-and-date could already be, nearest first by shared tokens.
     *
     * A null [date] matches on the name alone, exactly as `findDuplicates` does — the date
     * narrows the match, it is not a second requirement to have one.
     */
    fun matches(displayName: String, date: EventDate?): List<DedupCandidate> {
        val wanted = nameTokens(displayName)
        if (wanted.isEmpty()) return emptyList()

        val shared = mutableMapOf<String, Int>()
        for (token in wanted) {
            for (id in byToken[token].orEmpty()) shared[id] = (shared[id] ?: 0) + 1
        }
        val wantedDayMonth = date?.let { DedupCandidate.key(it.day, it.month) }

        return shared.entries
            .mapNotNull { (id, count) -> byId[id]?.let { it to count } }
            .filter { (candidate, _) ->
                wantedDayMonth == null || wantedDayMonth in candidate.dayMonths
            }
            .sortedByDescending { it.second }
            .map { it.first }
    }
}

/** What the importer decided about one row of the file. */
sealed interface RowPlan {
    val row: ImportRow

    /** Will be written. [warnings] are things changed to fit, never things dropped silently. */
    data class Ready(
        override val row: ImportRow,
        val name: String,
        val date: EventDate,
        val relation: String,
        val phone: String?,
        val notes: String?,
        val ambiguousDate: Boolean,
        val warnings: List<String> = emptyList(),
    ) : RowPlan

    /**
     * Matches somebody already here, or somebody earlier in this same file.
     *
     * Surfaced, never merged (FR-PPL-06), and **skipped by default** — which is what makes
     * re-running the same file a no-op without the user having to press anything. A default
     * of "import anyway" would make idempotency depend on a human noticing.
     */
    data class Duplicate(
        override val row: ImportRow,
        val ready: Ready,
        val matchId: String,
        val matchName: String,
        val withinThisFile: Boolean,
    ) : RowPlan

    /** Cannot be written. [reason] is shown to the user and goes in the corrected CSV. */
    data class Failed(override val row: ImportRow, val reason: String) : RowPlan
}

/**
 * One row as it came out of the file, kept whole.
 *
 * [cells] is the original, unmodified values: FR-IMP-07's corrected CSV hands the user back
 * their own columns with a reason appended, so they can fix it in the tool they already use
 * and re-run it. Reconstructing the row from the parsed fields would hand them our
 * interpretation of their file, which is the one thing that is definitely not what they typed.
 */
data class ImportRow(
    /** 1-based line in the source file, header included, so it matches what the user sees. */
    val number: Int,
    val cells: List<String>,
)

/** What a whole run decided, before anything is written. */
data class ImportPlan(
    val ready: List<RowPlan.Ready>,
    val duplicates: List<RowPlan.Duplicate>,
    val failed: List<RowPlan.Failed>,
) {
    val total: Int get() = ready.size + duplicates.size + failed.size
}

/**
 * The server's own limits, mirrored here for ONE reason: a better error.
 *
 * This is not the safety mechanism. The safety mechanism is `sync_blocked` — a row the
 * server refuses by name is set aside with the server's message and stops holding up the
 * outbox (docs/M3_PLAN.md §1.6). Mirroring `sync.schemas.ts` in Kotlin would be a second
 * implementation of validation, which is the duplication this project refuses.
 *
 * What these do is turn "imported, and then three days later your sync quietly stopped
 * mentioning Aarav" into "row 14: the name is longer than 120 characters", at the moment the
 * user is looking at the file and can fix it. If the server's limits change, the worst that
 * happens here is a row rejected early with a clear reason, not a wedged outbox.
 */
private const val MAX_NAME = 120
private const val MAX_RELATION = 60
private const val MAX_PHONE = 40
private const val MAX_NOTES = 2000

/**
 * Decide every row, writing nothing.
 *
 * Deliberately a pure function over a pure index. The whole of the hard part of import —
 * what a cell means, whether a row can be written, whether it is somebody we already have —
 * is decided here, before any transaction opens, which is what makes "all-or-nothing within
 * the transaction, partial with respect to the file" possible: the rows that pass are
 * written together, and the rows that fail were never attempted. See docs/M3_PLAN.md §5.
 */
fun planImport(
    rows: List<List<String>>,
    mapping: ColumnMapping,
    settings: ImportSettings,
    index: DedupIndex,
    today: LocalDate,
): ImportPlan {
    val ready = mutableListOf<RowPlan.Ready>()
    val duplicates = mutableListOf<RowPlan.Duplicate>()
    val failed = mutableListOf<RowPlan.Failed>()

    for ((zeroBased, cells) in importDataRows(rows, settings.hasHeaderRow)) {
        val row = ImportRow(number = zeroBased + 1, cells = cells)
        when (val plan = planRow(row, mapping, settings, index, today)) {
            is RowPlan.Failed -> failed += plan
            is RowPlan.Duplicate -> duplicates += plan
            is RowPlan.Ready -> {
                ready += plan
                // Added to the index BEFORE the next row is considered, so the same aunt on
                // rows 4 and 51 is one aunt. Without this, within-file duplicates would need
                // a second pass with a second rule, and the two would drift.
                index.add(
                    DedupCandidate(
                        id = WITHIN_FILE_PREFIX + row.number,
                        displayName = plan.name,
                        dayMonths = setOf(DedupCandidate.key(plan.date.day, plan.date.month)),
                    ),
                )
            }
        }
    }

    return ImportPlan(ready, duplicates, failed)
}

/**
 * The rows of the file that describe a person, each still carrying its own position.
 *
 * ONE definition, used by the importer and by the mapping screen's preview, so the two
 * cannot disagree about which rows they are talking about or what to call them.
 *
 * Blank lines are skipped HERE rather than by the readers. A spreadsheet with a blank line
 * between households is ordinary, and a blank line is not a person — but dropping it before
 * the numbering made the failure report name the wrong row, which defeats the entire point
 * of FR-IMP-07. The index that comes back is the position in the file, so `index + 1` is the
 * line the user is looking at.
 *
 * The header is found rather than assumed to be first, so a file with a stray blank line
 * above its headings still maps correctly.
 */
fun importDataRows(
    rows: List<List<String>>,
    hasHeaderRow: Boolean,
): List<IndexedValue<List<String>>> {
    val headerIndex = rows.indexOfFirst { cells -> cells.any { it.isNotBlank() } }
    val firstData = if (hasHeaderRow && headerIndex >= 0) headerIndex + 1 else 0
    return rows.withIndex()
        .drop(firstData)
        .filterNot { (_, cells) -> cells.all { it.isBlank() } }
}

/** Marks an index entry that came from this file rather than from the database. */
const val WITHIN_FILE_PREFIX = "row:"

private fun planRow(
    row: ImportRow,
    mapping: ColumnMapping,
    settings: ImportSettings,
    index: DedupIndex,
    today: LocalDate,
): RowPlan {
    fun cell(column: Int?): String = column?.let { row.cells.getOrNull(it) }?.trim().orEmpty()

    val warnings = mutableListOf<String>()

    val name = cell(mapping.name)
    if (name.isEmpty()) return RowPlan.Failed(row, "no name")
    if (name.length > MAX_NAME) {
        return RowPlan.Failed(row, "the name is longer than $MAX_NAME characters")
    }

    val rawDate = cell(mapping.date)
    val date = when (val parsed = ImportDates.parse(rawDate, settings.order, today)) {
        is DateParse.Failed -> return RowPlan.Failed(row, parsed.reason)
        is DateParse.Parsed -> parsed
    }

    // FR-IMP-03: every imported row needs a relation. Stricter than the manual add path,
    // deliberately and in writing — bulk is where unlabelled people pile up, and the mapping
    // screen offers a default so a file with no relation column still imports in one tap.
    // See docs/M3_PLAN.md §4.1.
    val relation = cell(mapping.relation).ifEmpty { settings.defaultRelation.orEmpty() }
    if (relation.isEmpty()) return RowPlan.Failed(row, "no relation")
    val trimmedRelation = if (relation.length > MAX_RELATION) {
        warnings += "the relation was shortened to fit"
        relation.take(MAX_RELATION)
    } else {
        relation
    }

    // Half a phone number is worse than none, so an over-long one is dropped rather than
    // truncated — and said out loud, because a field that vanishes without a word is exactly
    // the silent partial success this milestone is built to avoid.
    val phone = cell(mapping.phone).takeIf { it.isNotEmpty() }?.let {
        if (it.length > MAX_PHONE) {
            warnings += "the phone number was too long to store and was left out"
            null
        } else {
            it
        }
    }

    // A note, on the other hand, is still useful shortened.
    val notes = cell(mapping.notes).takeIf { it.isNotEmpty() }?.let {
        if (it.length > MAX_NOTES) {
            warnings += "the note was shortened to fit"
            it.take(MAX_NOTES)
        } else {
            it
        }
    }

    val ready = RowPlan.Ready(
        row = row,
        name = name,
        date = date.date,
        relation = trimmedRelation,
        phone = phone,
        notes = notes,
        ambiguousDate = date.ambiguous,
        warnings = warnings,
    )

    val match = index.matches(name, date.date).firstOrNull() ?: return ready
    return RowPlan.Duplicate(
        row = row,
        ready = ready,
        matchId = match.id,
        matchName = match.displayName,
        withinThisFile = match.id.startsWith(WITHIN_FILE_PREFIX),
    )
}

/**
 * The failure report as a CSV the user can fix and re-run (FR-IMP-07).
 *
 * Their own header, their own values, plus one extra column. The round trip is the point:
 * they fix the five broken rows in the tool they already have, re-import the corrected file,
 * and the rows that were fine the first time come back as duplicates and change nothing.
 * That is why FR-IMP-07 and FR-IMP-08 are one feature and not two.
 */
fun correctedCsv(header: List<String>, failed: List<RowPlan.Failed>): String {
    // Everything is padded to the WIDEST thing in the file, header included, so the reason
    // is the last column of every line and the header's last name describes it.
    //
    // Padding only the short rows is not enough, and that was the bug: a row with MORE cells
    // than the header — one stray comma is all it takes, and a file like that is exactly
    // what import exists to cope with — pushed its reason past the column the header
    // declared, so the round trip handed the user a file with the explanation in the wrong
    // place. It also bit every headerless file whose column count was not five, because the
    // substituted default header is five names long.
    val width = maxOf(header.size, failed.maxOfOrNull { it.row.cells.size } ?: 0)

    val rows = mutableListOf<List<String>>()
    rows += header.padTo(width) + REASON_COLUMN
    for (failure in failed) {
        rows += failure.row.cells.padTo(width) + failure.reason
    }
    return Csv.write(rows)
}

/** Trailing empty cells up to [width]. Never truncates: the user's own values all survive. */
private fun List<String>.padTo(width: Int): List<String> =
    if (size >= width) this else this + List(width - size) { "" }

const val REASON_COLUMN = "WishKaro: why this row was not imported"
