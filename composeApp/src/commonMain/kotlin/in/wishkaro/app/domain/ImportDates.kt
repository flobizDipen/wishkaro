package `in`.wishkaro.app.domain

import kotlinx.datetime.LocalDate
import kotlinx.datetime.number

/**
 * How to read a date whose first two numbers could be either way round (FR-IMP-06).
 *
 * An EXPLICIT user toggle, never inferred from the data. A file where every row happens to
 * be under 13 is exactly the file where guessing is both possible and wrong — and it is also
 * the common case, because most families' birthdays cluster in no particular month.
 *
 * Defaulted to [DAY_FIRST] for India.
 */
enum class DateOrder { DAY_FIRST, MONTH_FIRST }

/** What reading one cell produced. */
sealed interface DateParse {
    /**
     * @param ambiguous true when the two leading numbers were BOTH 12 or less, so [DateOrder]
     *   decided which was which. The column-mapping preview marks these, because they are the
     *   rows where flipping the toggle changes the answer.
     */
    data class Parsed(val date: EventDate, val ambiguous: Boolean) : DateParse

    /** @param reason wording meant for the user, and for the reason column of the failure CSV. */
    data class Failed(val reason: String) : DateParse
}

/**
 * Reading the dates people actually typed.
 *
 * Everything here is pure and timezone-free, deliberately. A birthday is a day and a month,
 * not an instant; the only place a zone legitimately enters is [BirthdayMath.nextOccurrence],
 * which already has one. `today` is a parameter rather than a call to the system clock for
 * the same reason — M2 §0.0.2 caught a test reading the ambient zone through production code.
 */
object ImportDates {

    /** The lowest year the server will accept (`EventDateSchema`). Below it means "unknown". */
    const val MIN_REAL_YEAR = 1900

    /** The highest the server will accept. A four-digit year past it is an error, not a guess. */
    const val MAX_REAL_YEAR = 2200

    /**
     * Years that mean "there is no year here" rather than a year.
     *
     * 1604 is the vCard / Microsoft convention and 1904 is the classic Mac epoch; Android
     * contacts and several desktop exporters write one or the other when the user gave a day
     * and a month only. 1604 is below [MIN_REAL_YEAR] anyway, so it would be caught in any
     * case; 1904 is not, and reading it literally would tell a few hundred imported contacts
     * that they are turning 122.
     *
     * The cost of being wrong in this direction is that a genuine 1904 birth year becomes
     * unknown — the date still imports, the countdown still works, only the age is missing,
     * and nobody alive was born in 1904. The cost in the other direction is an app full of
     * nonsense ages, which is exactly the kind of confident wrongness FR-EVT-02 exists about.
     */
    private val SENTINEL_YEARS = setOf(1604, 1904)

    private val MONTH_NAMES: Map<String, Int> = buildMap {
        val full = listOf(
            "january", "february", "march", "april", "may", "june",
            "july", "august", "september", "october", "november", "december",
        )
        full.forEachIndexed { index, name ->
            put(name, index + 1)
            put(name.take(3), index + 1)
        }
        // The one abbreviation that is not three letters and is written constantly.
        put("sept", 9)
        // Month names are ENGLISH only in v1. A Hindi or Gujarati month name fails with a
        // reason rather than being half-guessed; FR-IMP-06 lists English formats only, and
        // Hindi UI localisation is a v1.1 item in REQUIREMENTS.md §3.
    }

    private val SEPARATORS = Regex("[\\s./\\-,]+")

    /**
     * Parse one cell.
     *
     * Accepts, per FR-IMP-06: `dd/MM/yyyy`, `dd-MM-yy`, `MM/dd/yyyy`, `d MMM yyyy`,
     * `yyyy-MM-dd`, and day-month with no year — plus `d MMMM yyyy`, `yyyy/MM/dd`, `MMM d
     * yyyy`, and `.` or a space as the separator, because those cost nothing once the input
     * is split rather than matched against a list of patterns.
     */
    fun parse(raw: String, order: DateOrder, today: LocalDate): DateParse {
        val text = raw.trim()
        if (text.isEmpty()) return DateParse.Failed("no date")

        val parts = text.split(SEPARATORS).filter { it.isNotBlank() }
        if (parts.size !in 2..3) {
            return DateParse.Failed("could not read \"$text\" as a date")
        }

        val named = parts.indexOfFirst { monthFromName(it) != null }
        return when {
            named >= 0 -> fromNamedMonth(parts, named, text, today)
            // A four-digit number first is ISO, and ISO is never ambiguous.
            parts[0].length == 4 && parts[0].toIntOrNull() != null -> fromIso(parts, text, today)
            else -> fromNumbers(parts, order, text, today)
        }
    }

    /**
     * An Excel date cell, which is a day count rather than a date.
     *
     * Only ever called when the cell's `numFmt` says it is a date — a bare number in a date
     * column is a failed row, not a guess, because `45292` and "45292" are the same bytes and
     * only the format tells them apart.
     *
     * Serial 1 is 1900-01-01, and the system contains a deliberate bug: Excel believes
     * 1900 was a leap year, so serial 60 is a 29 February that never happened and every
     * serial after it is one too high against a real calendar. Serials below 61 are
     * therefore pre-1900 dates, which this app treats as "year unknown" anyway, so the
     * simplest correct rule is to refuse them rather than pretend to resolve them.
     */
    fun fromExcelSerial(serial: Int): DateParse {
        if (serial < 61) {
            return DateParse.Failed("date before 1900-03-01 — Excel's 1900 leap-year bug makes it ambiguous")
        }
        // Serial 61 is 1900-03-01. Counting from there avoids the phantom 29 February
        // entirely instead of adding and subtracting a day around it.
        val date = LocalDate(1900, 3, 1).plusDays(serial - 61)
        if (date.year > MAX_REAL_YEAR) return DateParse.Failed("year ${date.year} is out of range")
        return DateParse.Parsed(EventDate(date.day, date.month.number, date.year), ambiguous = false)
    }

    // -------------------------------------------------------------------------------------

    private fun monthFromName(part: String): Int? =
        MONTH_NAMES[part.lowercase().trimEnd('.')]

    private fun fromNamedMonth(
        parts: List<String>,
        monthIndex: Int,
        text: String,
        today: LocalDate,
    ): DateParse {
        val month = monthFromName(parts[monthIndex])!!
        val rest = parts.filterIndexed { i, _ -> i != monthIndex }
        val numbers = rest.map { it.toIntOrNull() }
        if (numbers.any { it == null }) return DateParse.Failed("could not read \"$text\" as a date")

        // "4 Jan 1990" and "Jan 4 1990" both leave [4, 1990]; the day is the one that could
        // not be a year. A named month means there is nothing left to be ambiguous about.
        val day: Int
        val year: Int?
        when (numbers.size) {
            1 -> {
                day = numbers[0]!!
                year = null
            }
            2 -> {
                val (first, second) = numbers.map { it!! }
                // The larger one is the year unless both are small, in which case the one
                // that is not a possible day is. `4 Jan 65` -> day 4, year 65 -> 1965.
                if (first > 31) {
                    year = resolveYear(rest[0], today)
                    day = second
                } else {
                    day = first
                    year = resolveYear(rest[1], today)
                }
            }
            else -> return DateParse.Failed("could not read \"$text\" as a date")
        }
        return validated(day, month, year, ambiguous = false, text = text)
    }

    private fun fromIso(parts: List<String>, text: String, today: LocalDate): DateParse {
        val numbers = parts.map { it.toIntOrNull() ?: return DateParse.Failed("could not read \"$text\" as a date") }
        if (numbers.size != 3) return DateParse.Failed("could not read \"$text\" as a date")
        val (_, month, day) = numbers
        return validated(day, month, resolveYear(parts[0], today), ambiguous = false, text = text)
    }

    private fun fromNumbers(
        parts: List<String>,
        order: DateOrder,
        text: String,
        today: LocalDate,
    ): DateParse {
        val numbers = parts.map { it.toIntOrNull() ?: return DateParse.Failed("could not read \"$text\" as a date") }
        val first = numbers[0]
        val second = numbers[1]
        val year = parts.getOrNull(2)?.let { resolveYear(it, today) }

        // AMBIGUOUS means both leading numbers could be a month. Only then does the toggle
        // decide anything — a row like 25/12 reads the same way under either setting, and
        // treating it as "resolved by the toggle" would make the preview mark rows that
        // cannot change, which is the opposite of what the preview is for.
        val ambiguous = first in 1..12 && second in 1..12 && first != second

        val day: Int
        val month: Int
        when {
            first > 12 -> {
                // Cannot be a month, so this is day-first whatever the toggle says.
                day = first
                month = second
            }
            second > 12 -> {
                // Cannot be a month either, so this is month-first whatever the toggle says.
                day = second
                month = first
            }
            order == DateOrder.DAY_FIRST -> {
                day = first
                month = second
            }
            else -> {
                day = second
                month = first
            }
        }
        return validated(day, month, year, ambiguous, text)
    }

    /**
     * Two-digit years, and the sentinels that mean "no year at all".
     *
     * THE PIVOT: **a birth or anniversary date cannot be in the future.** `yy` becomes
     * `2000 + yy` when that is at or before the current year, and `1900 + yy` otherwise. In
     * 2026 that gives 65 -> 1965, 10 -> 2010, 26 -> 2026, 27 -> 1927.
     *
     * The reasoning, rather than a fixed pivot year: every date this app stores is something
     * that has already happened. `dd-MM-yy` with `65` is 1965 for a birth year and 2065 is
     * absurd, and a fixed pivot at, say, 50 would get `55` right and `27` wrong the moment
     * the year rolls past 2027. Deriving it from today is the same rule for ever.
     *
     * FOUR-DIGIT YEARS ARE LEFT ALONE. A user who genuinely typed 2027 — an anniversary
     * they are counting towards — gets 2027. The pivot is for recovering information a
     * two-digit year threw away, not for second-guessing one that is written out.
     *
     * BELOW 1900 MEANS UNKNOWN, not invalid. Android's contact birthday field and several
     * exporters write 1604 or 1904 as "there is no year here"; `event.year` is nullable and
     * M1 built every path for it, so the sentinel maps onto the representation that already
     * exists rather than becoming a second one.
     */
    private fun resolveYear(text: String, today: LocalDate): Int? {
        val digits = text.trim()
        val raw = digits.toIntOrNull() ?: return null
        if (raw < 0) return null
        // ONE OR TWO DIGITS is what the pivot is for. "0065" and "65" are the same number
        // and different statements: the first says which century, the second threw it away.
        val resolved = if (digits.length <= 2) {
            val thisCentury = 2000 + raw
            if (thisCentury <= today.year) thisCentury else 1900 + raw
        } else {
            raw
        }
        if (resolved in SENTINEL_YEARS) return null
        return if (resolved < MIN_REAL_YEAR) null else resolved
    }

    private fun validated(
        day: Int,
        month: Int,
        year: Int?,
        ambiguous: Boolean,
        text: String,
    ): DateParse {
        if (month !in 1..12) return DateParse.Failed("\"$text\" has no month between 1 and 12")
        if (day !in 1..31) return DateParse.Failed("\"$text\" has no day between 1 and 31")
        if (year != null && year > MAX_REAL_YEAR) {
            return DateParse.Failed("year $year is out of range")
        }
        // The same rule the server applies (`assertValidSpec`): 31 April and 29 Feb 2023 do
        // not exist. 29 Feb with NO year does — it is a real birthday, and M1's leap-day
        // rule already carries it.
        if (!BirthdayMath.isRealDayMonth(day, month)) {
            return DateParse.Failed("\"$text\" is not a real date")
        }
        if (year != null && day == 29 && month == 2 && !BirthdayMath.isLeapYear(year)) {
            return DateParse.Failed("$year was not a leap year, so 29 February $year did not happen")
        }
        return DateParse.Parsed(EventDate(day, month, year), ambiguous)
    }
}
