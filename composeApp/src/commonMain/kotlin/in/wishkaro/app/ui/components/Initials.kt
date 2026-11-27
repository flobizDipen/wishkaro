package `in`.wishkaro.app.ui.components

import `in`.wishkaro.app.domain.nameTokens

/**
 * The letters an avatar falls back to when there is no picture — which, in this app, is always.
 *
 * ## Why this is not `name.take(1)`
 *
 * It is built on [nameTokens], the rule this project already has, rather than on a second one.
 * That rule exists because **this is an India-first app**: it splits on anything that is not a
 * letter, a number **or a combining mark**, specifically so that Devanagari, Gujarati, Tamil and
 * Bengali names survive. `docs/M3_PLAN.md` §3 records what the naive version did — `जयदत्त जांसरी`
 * tokenised to `जयदत` and `सर`, fragments rather than names, because the virama and the vowel
 * signs are Unicode category M and the old separator class treated every one of them as a split.
 *
 * Taking the first CHARACTER of a Devanagari name makes the same mistake one level down: the
 * first character of `ज्योति` is `ज`, and the cluster a reader sees is `ज्यो`. So this takes the
 * first **grapheme-ish run** — a base letter plus whatever combining marks follow it — which is
 * what somebody would recognise as "their initial".
 *
 * ## Two letters or one
 *
 * Two when the name has two tokens, one when it has one. Never three: three initials in a 40dp
 * circle at 14sp is a grey smudge, and the avatar's job is to make a list scannable rather than
 * to encode the name.
 *
 * ## Why not uppercase
 *
 * [nameTokens] lower-cases, because matching is what it is for. Display wants the name's own
 * case, so this reads the ORIGINAL string and uses the tokens only to decide where the words
 * are. Uppercasing is left to the caller — and is not done at all for scripts that have no
 * case, where `uppercase()` is a no-op anyway.
 */
fun initialsOf(displayName: String): String {
    val words = displayName
        .split(' ', '\t', '\n', ' ')
        .map { it.trim() }
        .filter { word -> word.isNotEmpty() && nameTokens(word).isNotEmpty() }

    if (words.isEmpty()) return ""

    val first = leadingCluster(words.first())
    if (words.size == 1) return first

    val last = leadingCluster(words.last())
    return first + last
}

/**
 * The first base character of a word plus any combining marks hanging off it.
 *
 * `Char.isLetter()` is false for a combining mark, so the loop is written on the mark side:
 * take one character, then keep taking while what follows is a mark. Kotlin/Common has no
 * `Character.getType`, so the test is "not a letter, not a digit, not whitespace, not
 * punctuation" — which over the inputs this sees (a name that already passed `nameTokens`) is
 * the combining marks and nothing else.
 */
private fun leadingCluster(word: String): String {
    if (word.isEmpty()) return ""
    var end = 1
    while (end < word.length && isCombining(word[end])) end++
    return word.substring(0, end)
}

private fun isCombining(c: Char): Boolean =
    !c.isLetter() && !c.isDigit() && !c.isWhitespace() && !isAsciiPunctuation(c)

private fun isAsciiPunctuation(c: Char): Boolean = c.code < 128 && !c.isLetterOrDigit()
