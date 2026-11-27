/**
 * "Is this user premium?" — **one function, and in M6 it always answers no.**
 *
 * `docs/BUILD_PROMPT_M6.md` puts credits, the paywall and Play Billing in M7 and says that
 * where M6 needs to know this it "asks one function that returns false". This is that
 * function, and it exists as a function rather than as a literal `false` at each call site
 * so that M7 is one implementation swapped in rather than a search for every place the
 * question was answered inline.
 *
 * Two things depend on it today:
 *
 *  - **FR-POS-08's attribution.** Free-tier posters carry subtle WishKaro attribution;
 *    Premium removes it. `poster.attributed` defaults to true and is written per poster
 *    rather than derived on read, so a subscription lapsing cannot retroactively rewrite
 *    what somebody already sent.
 *  - **FR-POS-07's AI blend**, which is not in M6 at all and is listed here only so that the
 *    next person looking for the premium gate finds the note rather than inventing a second
 *    one.
 *
 * Deliberately `async`: M7's answer is a database read, and a synchronous signature now
 * would mean changing every call site then.
 */
export async function isPremium(_userId: string): Promise<boolean> {
  return false;
}
