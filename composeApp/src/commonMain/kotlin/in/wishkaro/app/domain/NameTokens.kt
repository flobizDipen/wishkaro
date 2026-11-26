package `in`.wishkaro.app.domain

/**
 * The name-token rule, in one place on this side of the wire.
 *
 * It is implemented twice — here and in TypeScript, in `server/src/modules/people/
 * name-tokens.ts` — because import dedup has to work on the phone with the radio off, and
 * create-time dedup happens on the server. Two implementations of one rule is a thing this
 * project otherwise refuses, so the price of the exception is that BOTH are held to one
 * shared table of cases: `docs/fixtures/name-tokens.json`, read by this rule's test and by
 * the TypeScript one.
 *
 * WHAT THE RULE IS. Lower-cased tokens of two or more characters, split on anything that is
 * not a letter, a number, or a COMBINING MARK. Word order and punctuation do not matter:
 * "Jansari Jaydatt" and "Jaydatt Jansari" are the same human and no sensible edit distance
 * says so, while "Aarav" and "Aarti" are different humans and a loose edit distance says
 * they are not.
 *
 * WHY `\p{M}` IS IN THERE, which is the part that is easy to get wrong and was wrong here
 * until M3. Devanagari, Gujarati, Tamil, Bengali and the rest build words out of combining
 * marks — the virama, the vowel signs. They are Unicode category M, not L. The rule used to
 * be `[^\p{L}\p{N}]+`, which treats every one of them as a SEPARATOR: `जयदत्त जांसरी`
 * tokenised to `जयदत` and `सर`, fragments rather than names. See docs/M3_PLAN.md §3.
 *
 * KNOWN LIMIT, kept deliberately: "José" and "Jose" do not match. Matching them means
 * stripping diacritics, and stripping diacritics means stripping `\p{M}` — which is exactly
 * what breaks every Indic name. It is in the shared table as a case, so it stays a decision
 * rather than becoming a surprise.
 */
private val SEPARATORS = Regex("[^\\p{L}\\p{N}\\p{M}]+")

/** The minimum token length. One-letter initials and stray digits are not evidence. */
private const val MIN_TOKEN_LENGTH = 2

fun nameTokens(name: String): Set<String> =
    name.lowercase()
        .split(SEPARATORS)
        .filter { it.length >= MIN_TOKEN_LENGTH }
        .toSet()

/** True when the two names share a whole token — a surname, or a first name. */
fun tokensOverlap(a: Set<String>, b: Set<String>): Boolean = a.any { it in b }
