package `in`.wishkaro.app.ui.onboarding

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The carousel's copy, held to the rule the build prompt states.
 *
 * `BUILD_PROMPT_M7.md`, on `FR-AUTH-01`: *"A note on honesty: 'Gift' has no feature behind it
 * until M8, and 'Wish' is template-backed from M8 onward. Write carousel copy that is true of
 * the app being shipped. **Do not promise AI.**"*
 *
 * That is a rule about three strings on the first screen anybody sees, and a rule about strings
 * is a rule a test can hold. M5 established the shape with `ContactsDisclosure`: the words live
 * in a value rather than inside a composable *because* they are the requirement.
 *
 * The lexicon below is the one from WishKaro's own re-scope, not a generic list. v1 is curated;
 * v2 is generated. Every word here is one a reader would take as a promise that the app writes
 * something for them, and every one of them would have been fair copy a month ago.
 */
class OnboardingCopyTest {

    /**
     * Words the shipped app cannot stand behind.
     *
     * "personalised" is the subtle one and it is in the list deliberately: the app does
     * personalise — it puts a name and a relation into a template — but the word is read as
     * "a model wrote this for you", which is the claim v1 cannot make.
     */
    private val forbidden = listOf(
        "ai", "a.i.", "artificial intelligence", "generated", "generates", "generate",
        "smart", "intelligent", "personalised", "personalized", "written for you",
        "automatically", "automatic",
    )

    @Test
    fun there_are_exactly_three_pages_named_remember_wish_gift() {
        assertEquals(
            listOf("Remember", "Wish", "Gift"),
            OnboardingPage.entries.map { it.heading },
            "FR-AUTH-01 names three screens and names them in this order.",
        )
    }

    @Test
    fun no_page_promises_anything_v1_does_not_do() {
        for (page in OnboardingPage.entries) {
            val text = "${page.heading} ${page.body}".lowercase()
            for (word in forbidden) {
                // Padded, so that "ai" does not match "said" and "generate" does not match a
                // longer word that happens to contain it. The headings and bodies are
                // sentences, so a space-delimited search is the right granularity.
                val padded = " ${text.replace(Regex("[^a-z. ]"), " ")} "
                assertTrue(
                    !padded.contains(" $word "),
                    "${page.name}'s copy contains \"$word\". v1 is curated — the first screen " +
                        "anybody sees is the worst place to promise what M8 and v2 will do.",
                )
            }
        }
    }

    /**
     * FR-WSH-06 is a promise the app keeps, and the carousel is where it is first made.
     *
     * This is the opposite kind of assertion from the one above: not "do not say X" but "this
     * page must still say the thing that makes it honest". Someone rewriting the Wish page for
     * length would otherwise quietly drop the clause that is the whole reason the page is
     * truthful.
     */
    @Test
    fun the_wish_page_still_says_the_app_sends_nothing() {
        val body = OnboardingPage.WISH.body.lowercase()
        assertTrue(
            body.contains("never sends anything for you"),
            "FR-WSH-06: nothing in this app sends a message. The Wish page says so, and that " +
                "sentence is the reason the page can describe a composer without overclaiming.",
        )
    }

    /**
     * The Gift page describes traits, which exist, rather than a catalog, which does not.
     *
     * Asserted on what it must NOT contain, because the catalog is M8's and the page will want
     * rewriting then — at which point this test should go red and be updated deliberately.
     */
    @Test
    fun the_gift_page_does_not_describe_a_catalog_that_does_not_exist_yet() {
        val body = OnboardingPage.GIFT.body.lowercase()
        for (claim in listOf("suggest", "ideas for", "we'll find", "recommend", "shop", "buy")) {
            assertTrue(
                !body.contains(claim),
                "The Gift page contains \"$claim\". There is no gift catalog until M8; this " +
                    "page describes the likes and dislikes M1 already stores.",
            )
        }
    }
}
