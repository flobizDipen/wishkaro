package `in`.wishkaro.app.ui.importing

import `in`.wishkaro.app.data.repository.PeopleRepository
import `in`.wishkaro.app.domain.ColumnMapping
import `in`.wishkaro.app.domain.ContactChoice
import `in`.wishkaro.app.domain.ContactsImport
import `in`.wishkaro.app.domain.Csv
import `in`.wishkaro.app.domain.DateOrder
import `in`.wishkaro.app.domain.DedupIndex
import `in`.wishkaro.app.domain.EventDate
import `in`.wishkaro.app.domain.ImportPlan
import `in`.wishkaro.app.domain.ImportSettings
import `in`.wishkaro.app.domain.RowPlan
import `in`.wishkaro.app.domain.XlsxWorkbook
import `in`.wishkaro.app.domain.correctedCsv
import `in`.wishkaro.app.domain.planImport
import `in`.wishkaro.app.platform.ContactsGateway
import `in`.wishkaro.app.platform.FileGateway
import `in`.wishkaro.app.platform.PermissionOutcome
import `in`.wishkaro.app.platform.readZipTextEntries
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.time.Clock
import kotlinx.datetime.todayIn

/** Where the contacts half of the flow has got to. */
sealed interface ContactsState {
    data object AskingPermission : ContactsState

    /** Refused, and asking again is still possible. */
    data object Denied : ContactsState

    /**
     * Refused in a way the system will not ask about again — so the screen must offer
     * Settings and must NOT offer to ask again, which would be a button that does nothing.
     */
    data object DeniedPermanently : ContactsState

    data object Reading : ContactsState

    data class Review(val choices: List<ContactChoice>) : ContactsState
}

/** Where the file half has got to. */
sealed interface FileState {
    data object Choosing : FileState

    data class Unreadable(val fileName: String, val reason: String) : FileState

    /** The mapping screen: the user's own columns, and what we think each one is. */
    data class Mapping(
        val fileName: String,
        val rows: List<List<String>>,
        val mapping: ColumnMapping,
        val settings: ImportSettings,
    ) : FileState {
        /**
         * The user's own column names. The first NON-BLANK row, not simply the first —
         * blank lines are kept by the readers now so that positions are line numbers, and a
         * file with a stray blank line above its headings must still map correctly.
         */
        val header: List<String>
            get() = if (settings.hasHeaderRow) {
                rows.firstOrNull { cells -> cells.any { it.isNotBlank() } }.orEmpty()
            } else {
                emptyList()
            }

        /** Column labels for the mapping UI — the user's names, or A/B/C when there are none. */
        val columnLabels: List<String>
            get() {
                val width = rows.maxOfOrNull { it.size } ?: 0
                return (0 until width).map { index ->
                    header.getOrNull(index)?.takeIf { it.isNotBlank() } ?: "Column ${columnLetters(index)}"
                }
            }
    }
}

/** What the result screen shows (FR-IMP-07). */
data class ImportResult(
    val imported: Int,
    val skipped: List<RowPlan.Duplicate>,
    val failed: List<RowPlan.Failed>,
    val warnings: List<Pair<String, String>>,
    /** The header to put on the corrected CSV. Empty for a contacts import, which has none. */
    val header: List<String>,
    val sourceName: String,
)

/**
 * The import flow, kept out of [AppStore] because it is a whole feature with a state machine
 * of its own and AppStore is already the biggest thing in the UI layer.
 *
 * It holds no decisions: everything about what a row MEANS lives in the pure functions in
 * `domain` ([planImport], [ImportDates], [DedupIndex]). This is the part that talks to the
 * platform, keeps what the user has chosen, and writes once at the end.
 */
class ImportStore(
    private val people: PeopleRepository,
    private val contacts: ContactsGateway,
    private val files: FileGateway,
    private val scope: CoroutineScope,
    private val today: () -> LocalDate = { Clock.System.todayIn(TimeZone.currentSystemDefault()) },
) {
    private val _contactsState = MutableStateFlow<ContactsState>(ContactsState.AskingPermission)
    val contactsState: StateFlow<ContactsState> = _contactsState.asStateFlow()

    private val _fileState = MutableStateFlow<FileState>(FileState.Choosing)
    val fileState: StateFlow<FileState> = _fileState.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _result = MutableStateFlow<ImportResult?>(null)
    val result: StateFlow<ImportResult?> = _result.asStateFlow()

    private val _savedReport = MutableStateFlow<Boolean?>(null)
    val savedReport: StateFlow<Boolean?> = _savedReport.asStateFlow()

    // -----------------------------------------------------------------------------------
    // Contacts
    // -----------------------------------------------------------------------------------

    /**
     * Called when the contacts screen opens. The rationale is the screen itself, which is
     * already on display before this runs — FR-IMP-01's "a rationale before the system
     * dialog" is a screen the user chose to open, not a second dialog in front of the first.
     */
    fun startContacts() {
        if (contacts.hasPermission()) {
            readContacts()
            return
        }
        _contactsState.value = ContactsState.AskingPermission
    }

    fun askForContactsPermission() {
        if (_busy.value) return
        scope.launch {
            _busy.value = true
            try {
                when (contacts.requestPermission()) {
                    PermissionOutcome.GRANTED -> readContacts()
                    PermissionOutcome.DENIED -> _contactsState.value = ContactsState.Denied
                    PermissionOutcome.DENIED_PERMANENTLY ->
                        _contactsState.value = ContactsState.DeniedPermanently
                }
            } finally {
                // A `finally`, so a thrown or cancelled permission request cannot leave the
                // screen stuck behind a spinner that never clears.
                _busy.value = false
            }
        }
    }

    fun openAppSettings() = contacts.openAppSettings()

    private fun readContacts() {
        scope.launch {
            _contactsState.value = ContactsState.Reading
            val read = contacts.readContacts()
            _contactsState.value = ContactsState.Review(ContactsImport.review(read))
        }
    }

    private fun updateChoices(block: (List<ContactChoice>) -> List<ContactChoice>) {
        val current = _contactsState.value as? ContactsState.Review ?: return
        _contactsState.value = ContactsState.Review(block(current.choices))
    }

    fun toggleContact(id: String) = updateChoices { choices ->
        choices.map {
            if (it.contact.id != id) {
                it
            } else {
                // A row with no date cannot be selected. A person with no event is invisible
                // to every screen this app has, so importing one looks like success and
                // produces nothing — see docs/M3_PLAN.md §4.2.
                if (!it.selected && !it.hasDate) it else it.copy(selected = !it.selected)
            }
        }
    }

    fun setContactDate(id: String, date: EventDate?) = updateChoices { choices ->
        choices.map { if (it.contact.id == id) it.copy(manualDate = date) else it }
    }

    fun setContactRelation(id: String, relation: String?) = updateChoices { choices ->
        choices.map { if (it.contact.id == id) it.copy(relation = relation) else it }
    }

    /**
     * FR-IMP-03's bulk action. Without it the flow is unusable past about ten rows, which is
     * the entire point of importing rather than typing.
     */
    fun setRelationForSelected(relation: String) = updateChoices { choices ->
        choices.map { if (it.selected) it.copy(relation = relation) else it }
    }

    /**
     * Select every row that has a date.
     *
     * A BUTTON, never a default — the engineering rules are explicit that nothing is written
     * without the user choosing it. It does not reach rows with no date, because those
     * cannot be imported and ticking them would promise something that will not happen.
     */
    fun selectAllWithDates() = updateChoices { choices ->
        choices.map { if (it.hasDate) it.copy(selected = true) else it }
    }

    fun selectNone() = updateChoices { choices -> choices.map { it.copy(selected = false) } }

    fun importSelectedContacts() {
        val review = _contactsState.value as? ContactsState.Review ?: return
        runImport(
            rows = ContactsImport.rows(review.choices),
            mapping = ContactsImport.MAPPING,
            settings = ImportSettings(hasHeaderRow = false),
            header = emptyList(),
            sourceName = "contacts",
        )
    }

    // -----------------------------------------------------------------------------------
    // Files
    // -----------------------------------------------------------------------------------

    fun pickFile() {
        if (_busy.value) return
        scope.launch {
            _busy.value = true
            val picked = try {
                files.pickSpreadsheet()
            } finally {
                _busy.value = false
            }
            if (picked == null) return@launch

            if (picked.bytes.size > MAX_FILE_BYTES) {
                _fileState.value = FileState.Unreadable(
                    picked.name,
                    "That file is ${picked.bytes.size / (1024 * 1024)} MB. WishKaro reads files " +
                        "up to ${MAX_FILE_BYTES / (1024 * 1024)} MB on the phone.",
                )
                return@launch
            }

            val rows = when {
                picked.name.endsWith(".xlsx", ignoreCase = true) ->
                    XlsxWorkbook.firstSheetRows(readZipTextEntries(picked.bytes))
                picked.name.endsWith(".xls", ignoreCase = true) -> {
                    _fileState.value = FileState.Unreadable(
                        picked.name,
                        "That is the old Excel format. Open it and save it as .xlsx or .csv first.",
                    )
                    return@launch
                }
                else -> Csv.parse(picked.bytes.decodeToString())
            }

            if (rows.isNullOrEmpty()) {
                _fileState.value = FileState.Unreadable(picked.name, "There are no rows in that file.")
                return@launch
            }
            if (rows.size > MAX_ROWS) {
                _fileState.value = FileState.Unreadable(
                    picked.name,
                    "That file has ${rows.size} rows. WishKaro imports up to $MAX_ROWS at a time.",
                )
                return@launch
            }

            val settings = ImportSettings(order = DateOrder.DAY_FIRST, hasHeaderRow = true)
            _fileState.value = FileState.Mapping(
                fileName = picked.name,
                rows = rows,
                mapping = guessMapping(rows.firstOrNull().orEmpty()),
                settings = settings,
            )
        }
    }

    private fun updateMapping(block: (FileState.Mapping) -> FileState.Mapping) {
        val current = _fileState.value as? FileState.Mapping ?: return
        _fileState.value = block(current)
    }

    fun setColumn(field: ImportField, index: Int?) = updateMapping { state ->
        // A column can only mean one thing. Assigning it to Date when it is already Name
        // clears the old assignment rather than leaving the same column feeding two fields,
        // which would import the person's name as their birthday and fail every row.
        val cleared = state.mapping.clearing(index)
        state.copy(mapping = field.set(cleared, index))
    }

    fun setDateOrder(order: DateOrder) = updateMapping {
        it.copy(settings = it.settings.copy(order = order))
    }

    fun setDefaultRelation(relation: String?) = updateMapping {
        it.copy(settings = it.settings.copy(defaultRelation = relation?.takeIf(String::isNotBlank)))
    }

    /**
     * How many rows the default relation would actually apply to.
     *
     * The dialog used to be told the row COUNT and so offered to "Set for all 200" when it
     * sets nothing on the 180 rows that already carry a relation of their own. Saying a
     * number and then not doing it to that number is the kind of small lie this milestone is
     * meant not to tell.
     */
    fun rowsNeedingADefaultRelation(): Int {
        val state = _fileState.value as? FileState.Mapping ?: return 0
        val relationColumn = state.mapping.relation
        return state.rows
            .drop(if (state.settings.hasHeaderRow) 1 else 0)
            .count { row -> relationColumn?.let { row.getOrNull(it) }?.isNotBlank() != true }
    }

    fun setHasHeaderRow(hasHeader: Boolean) = updateMapping {
        it.copy(settings = it.settings.copy(hasHeaderRow = hasHeader))
    }

    fun restartFilePick() {
        _fileState.value = FileState.Choosing
    }

    fun importFile() {
        val state = _fileState.value as? FileState.Mapping ?: return
        if (!state.mapping.isUsable) return
        runImport(
            rows = state.rows,
            mapping = state.mapping,
            settings = state.settings,
            header = state.header,
            sourceName = state.fileName,
        )
    }

    // -----------------------------------------------------------------------------------
    // The write, and the report
    // -----------------------------------------------------------------------------------

    private fun runImport(
        rows: List<List<String>>,
        mapping: ColumnMapping,
        settings: ImportSettings,
        header: List<String>,
        sourceName: String,
    ) {
        if (_busy.value) return
        scope.launch {
            _busy.value = true
            try {
                // The index is built ONCE from one read of the database, not per row.
                val index = DedupIndex(people.dedupCandidates())
                val plan: ImportPlan = planImport(rows, mapping, settings, index, today())

                // Everything was decided before this line. The write is one transaction over
                // rows that have already passed, so the database cannot end up half-written
                // even though the FILE is partially imported — docs/M3_PLAN.md §5.
                val written = people.importPeople(plan.ready)

                _result.value = ImportResult(
                    imported = written.size,
                    skipped = plan.duplicates,
                    failed = plan.failed,
                    warnings = plan.ready.flatMap { ready -> ready.warnings.map { ready.name to it } },
                    header = header,
                    sourceName = sourceName,
                )
                _savedReport.value = null
            } finally {
                _busy.value = false
            }
        }
    }

    /**
     * FR-IMP-07's downloadable corrected CSV: their columns, their values, plus a reason.
     *
     * A contacts import has no header of its own, so one is supplied — the user still needs
     * a file they can fix, and "Name, Date, Relation, Phone, Notes, why" is what those rows
     * actually are.
     */
    fun saveFailedRows() {
        val result = _result.value ?: return
        if (result.failed.isEmpty() || _busy.value) return
        scope.launch {
            _busy.value = true
            try {
                val header = result.header.ifEmpty { CONTACTS_HEADER }
                val csv = correctedCsv(header, result.failed)
                _savedReport.value = files.saveCsv("wishkaro-rows-to-fix.csv", csv)
            } finally {
                _busy.value = false
            }
        }
    }

    /**
     * Leave the flow, and make sure the next entry starts clean.
     *
     * `_busy` is released here too, and that is not tidiness. The file picker and the
     * permission dialog are awaited through a `CompletableDeferred` that another Activity
     * completes; if that result never arrives, the coroutine stays suspended and `_busy`
     * stays true — and because this store is a singleton on the Application, the flag
     * outlives the Activity. Observed while verifying M3: the picker silently stopped
     * opening, every tap on "Choose a file" did nothing, and only force-stopping the app
     * cleared it. A permanent lock-up with no error anywhere is the worst kind.
     *
     * Releasing it here means leaving and re-entering import always recovers. An orphaned
     * coroutine may still be suspended behind it, but it no longer holds the UI shut.
     */
    fun clearResult() {
        _result.value = null
        _savedReport.value = null
        _fileState.value = FileState.Choosing
        _busy.value = false
    }

    // -----------------------------------------------------------------------------------

    companion object {
        /**
         * The on-device limits, stated out loud because FR-IMP-04's "large files go to the
         * server" is not implemented — docs/M3_PLAN.md §2.3 says so and amends the
         * requirement rather than leaving it half-true. A user with more than 5,000
         * birthdays is not a user this app has; one with a 10 MB spreadsheet has something
         * other than birthdays in it, and an out-of-memory crash would tell them nothing.
         */
        const val MAX_ROWS = 5_000
        const val MAX_FILE_BYTES = 10 * 1024 * 1024

        val CONTACTS_HEADER = listOf("Name", "Date", "Relation", "Phone", "Notes")
    }
}

/** The five things a column can be (FR-IMP-05). */
enum class ImportField(val label: String) {
    NAME("Name"),
    DATE("Date"),
    RELATION("Relation"),
    PHONE("Phone"),
    NOTES("Notes"),
    ;

    fun get(mapping: ColumnMapping): Int? = when (this) {
        NAME -> mapping.name
        DATE -> mapping.date
        RELATION -> mapping.relation
        PHONE -> mapping.phone
        NOTES -> mapping.notes
    }

    fun set(mapping: ColumnMapping, index: Int?): ColumnMapping = when (this) {
        NAME -> mapping.copy(name = index)
        DATE -> mapping.copy(date = index)
        RELATION -> mapping.copy(relation = index)
        PHONE -> mapping.copy(phone = index)
        NOTES -> mapping.copy(notes = index)
    }
}

/** Drop [index] from whichever field currently holds it, so one column means one thing. */
internal fun ColumnMapping.clearing(index: Int?): ColumnMapping {
    if (index == null) return this
    return copy(
        name = name.takeIf { it != index },
        date = date.takeIf { it != index },
        relation = relation.takeIf { it != index },
        phone = phone.takeIf { it != index },
        notes = notes.takeIf { it != index },
    )
}

/**
 * Spreadsheet column letters for a file with no header row: A..Z, then AA, AB, ...
 *
 * `'A' + index` looked right and ran off the end of the alphabet at column 27, labelling
 * them "Column [", "Column \\", "Column ]". `Xlsx.columnIndex` already reads this base-26
 * scheme in the other direction.
 */
internal fun columnLetters(index: Int): String {
    var n = index
    val out = StringBuilder()
    while (true) {
        out.append('A' + n % 26)
        n = n / 26 - 1
        if (n < 0) break
    }
    return out.reverse().toString()
}

/**
 * A first guess at the mapping, from the header's own words.
 *
 * Only a guess, and visibly one: the mapping screen shows every choice and a five-row
 * preview before anything is applied, so a wrong guess costs one tap. Guessing nothing
 * would cost five taps on every import, including the common case where the columns are
 * called exactly what they are.
 */
internal fun guessMapping(header: List<String>): ColumnMapping {
    fun find(vararg words: String): Int? = header.indexOfFirst { cell ->
        val lower = cell.lowercase()
        words.any { it in lower }
    }.takeIf { it >= 0 }

    // Claimed in order of how much damage a wrong guess does. One column can only mean
    // one thing — the mapping screen enforces that on every later edit, and the guess
    // has to start out obeying it too. `Birthday Notes` matches both the date words and
    // the notes words, and without this it was offered as Date AND Notes at once, which
    // stores every person's raw date string as their note.
    val taken = mutableSetOf<Int>()
    fun claim(vararg words: String): Int? =
        find(*words)?.takeIf { taken.add(it) }

    return ColumnMapping(
        name = claim("name", "person", "who"),
        date = claim("birth", "date", "dob", "anniversar"),
        relation = claim("relation", "how i know", "type", "group"),
        phone = claim("phone", "mobile", "number", "contact"),
        notes = claim("note", "comment", "remark"),
    )
}
