/**
 * The name-token rule, in one place on this side of the wire.
 *
 * It is implemented twice — here and in Kotlin, in `NameTokens.kt` — because import dedup
 * has to work on the phone with the radio off, and create-time dedup happens on the server.
 * Two implementations of one rule is a thing this project otherwise refuses, so the price of
 * the exception is that BOTH are held to one shared table of cases:
 * `docs/fixtures/name-tokens.json`, read by this module's spec and by the Kotlin one.
 *
 * WHAT THE RULE IS. Lower-cased tokens of two or more characters, split on anything that is
 * not a letter, a number, or a COMBINING MARK. Word order and punctuation do not matter:
 * "Jansari Jaydatt" and "Jaydatt Jansari" are the same human and no sensible edit distance
 * says so, while "Aarav" and "Aarti" are different humans and a loose edit distance says
 * they are not.
 *
 * WHY `\p{M}` IS IN THERE, which is the part that is easy to get wrong and was wrong here
 * until M3. Devanagari, Gujarati, Tamil, Bengali and the rest build words out of combining
 * marks — the virama, the vowel signs. They are Unicode category M, not L. A class of
 * letters and numbers alone therefore treats every one of them as a SEPARATOR and shatters
 * an Indic name into fragments.
 *
 * This file's own previous rule was worse again: `[^a-z0-9ऀ-ॿ]` is ASCII plus the Devanagari
 * block and nothing else, so a Gujarati, Tamil, Telugu, Bengali, Kannada, Malayalam, Punjabi
 * or Odia name produced an EMPTY token set — and `findDuplicates` returns [] on an empty
 * set, so it silently reported "no duplicates" for every such person. In an India-first app.
 * See docs/M3_PLAN.md §3.
 *
 * KNOWN LIMIT, kept deliberately: "José" and "Jose" do not match. Matching them means
 * stripping diacritics, and stripping diacritics means stripping `\p{M}` — which is exactly
 * what breaks every Indic name. It is in the shared table as a case, so it stays a decision
 * rather than becoming a surprise.
 */
const SEPARATORS = /[^\p{L}\p{N}\p{M}]+/u;

/** The minimum token length. One-letter initials and stray digits are not evidence. */
const MIN_TOKEN_LENGTH = 2;

export function nameTokens(name: string): Set<string> {
  return new Set(
    name
      .toLowerCase()
      .split(SEPARATORS)
      .filter((token) => token.length >= MIN_TOKEN_LENGTH),
  );
}

/** True when the two names share a whole token — a surname, or a first name. */
export function tokensOverlap(a: Set<string>, b: Set<string>): boolean {
  for (const token of a) if (b.has(token)) return true;
  return false;
}
