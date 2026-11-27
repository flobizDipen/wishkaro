package `in`.wishkaro.app.ui.onboarding

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import `in`.wishkaro.app.ui.theme.WishKaroMotion
import kotlinx.coroutines.launch

/**
 * `FR-AUTH-01` — *"First launch shows a 3-screen value carousel (Remember · Wish · Gift),
 * skippable."*
 *
 * NOT MET since M0, and nobody noticed until 2026-11-27. First launch went straight to sign-in.
 *
 * ## Where it sits
 *
 * **Before sign-in**, in the same `authState` branch `App.kt` already has, and that is the only
 * placement that makes sense: the point of a value carousel is to be read by somebody deciding
 * whether to hand over a Google account. After sign-in it would be a tour of an app they have
 * already committed to.
 *
 * ## The copy, which is the part with a rule attached
 *
 * `BUILD_PROMPT_M7.md`: *"'Gift' has no feature behind it until M8, and 'Wish' is
 * template-backed from M8 onward. Write carousel copy that is true of the app being shipped.
 * Do not promise AI."*
 *
 * So:
 *
 *  - **Remember** describes the reminder ladder, which is built, shipped and the reason the app
 *    exists (M2, FR-REM-03).
 *  - **Wish** describes the composer and says plainly that nothing is sent for you — which is
 *    FR-WSH-06, is true today, and is a *better* promise than "we write it for you" would have
 *    been even if the model were live.
 *  - **Gift** describes the traits M1 already stores against a person. It does NOT describe a
 *    catalog, because there is not one until M8. "Keep what somebody likes beside their name"
 *    is a thing the app does.
 *
 * Nothing here says "AI", "generated", "smart" or "personalised". v1 is curated; the words on
 * the first screen anybody sees should not be the one place that forgets it.
 *
 * ## State
 *
 * The pager's page is NOT saved and does not need to be: the carousel's whole job is to be
 * dismissed, and a process death part-way through it should leave the user at the start of a
 * three-page introduction rather than restored to page two of something they have no memory of.
 * What must survive is [OnboardingStore]'s flag, which is written to disk with `commit()` at
 * the moment of the skip or the finish. The two are different claims and `M7_VERIFY.md` item 4
 * checks the one that matters.
 */
@Composable
fun OnboardingScreen(
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pages = OnboardingPage.entries
    val pagerState = rememberPagerState(pageCount = { pages.size })
    val scope = rememberCoroutineScope()
    val lastPage = pagerState.currentPage == pages.lastIndex

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            // This screen is not in a Scaffold — it is what the app shows instead of one — so
            // it insets itself. Without this the Skip button sits under the status bar on
            // every phone with a cutout, which is every phone this product is for.
            .statusBarsPadding()
            .navigationBarsPadding()
            .testTag("onboarding"),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            // Skip is on every page including the last, where it does the same thing as the
            // primary button. A skip that disappears on the final page is a skip the user has
            // to look for at the moment they most want it.
            TextButton(
                onClick = onDone,
                modifier = Modifier.testTag("onboarding_skip"),
            ) { Text("Skip") }
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f).testTag("onboarding_pager"),
        ) { index ->
            OnboardingPageContent(pages[index])
        }

        PageDots(
            count = pages.size,
            current = pagerState.currentPage,
            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
        )

        Button(
            onClick = {
                if (lastPage) {
                    onDone()
                } else {
                    scope.launch {
                        pagerState.animateScrollToPage(
                            page = pagerState.currentPage + 1,
                            animationSpec = WishKaroMotion.settled(WishKaroMotion.Slow),
                        )
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .testTag("onboarding_next"),
        ) {
            Text(if (lastPage) "Get started" else "Next")
        }
    }
}

@Composable
private fun OnboardingPageContent(page: OnboardingPage) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PageMark(page)
        Spacer(Modifier.height(40.dp))
        Text(
            text = page.heading,
            // displaySmall, in Fraunces: one word, large. This is the only place in the app
            // that uses a display size, which is what makes it feel like an opening rather
            // than like another screen.
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.testTag("onboarding_heading_${page.name.lowercase()}"),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = page.body,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * One drawn mark per page.
 *
 * Drawn rather than imported, for the reason `NavGlyphs.kt` already records: the multiplatform
 * Material icon artifact stopped at Compose 1.7.3 and `materialIconsExtended` is several
 * megabytes of vectors to use three of.
 *
 * They are the same three shapes the app uses elsewhere, which is deliberate — the calendar
 * mark on the first page is the calendar tab's glyph at a larger size, so the introduction is
 * teaching the navigation rather than illustrating it.
 */
@Composable
private fun PageMark(page: OnboardingPage) {
    val accent = MaterialTheme.colorScheme.primary
    val quiet = MaterialTheme.colorScheme.outlineVariant

    Canvas(Modifier.size(96.dp)) {
        val stroke = Stroke(width = 2.5.dp.toPx())
        when (page) {
            // A ring with one day marked: the shape of a date coming round again.
            OnboardingPage.REMEMBER -> {
                drawCircle(quiet, radius = size.minDimension / 2 - 2.dp.toPx(), style = stroke)
                drawCircle(
                    accent,
                    radius = 6.dp.toPx(),
                    center = Offset(size.width * 0.5f, size.height * 0.5f - size.minDimension / 2 + 2.dp.toPx()),
                )
            }
            // A speech shape: three lines, the last one short, with a tail.
            OnboardingPage.WISH -> {
                drawRoundRect(
                    quiet,
                    topLeft = Offset(size.width * 0.08f, size.height * 0.18f),
                    size = androidx.compose.ui.geometry.Size(size.width * 0.84f, size.height * 0.52f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(10.dp.toPx()),
                    style = stroke,
                )
                listOf(0.34f to 0.68f, 0.47f to 0.68f, 0.60f to 0.40f).forEach { (y, width) ->
                    drawLine(
                        accent,
                        Offset(size.width * 0.20f, size.height * y),
                        Offset(size.width * (0.20f + width), size.height * y),
                        strokeWidth = 2.5.dp.toPx(),
                    )
                }
                drawLine(
                    quiet,
                    Offset(size.width * 0.26f, size.height * 0.70f),
                    Offset(size.width * 0.20f, size.height * 0.86f),
                    strokeWidth = 2.5.dp.toPx(),
                )
            }
            // A box with a ribbon. Two lines and a rectangle; a bow at 96dp is a smudge.
            OnboardingPage.GIFT -> {
                drawRoundRect(
                    quiet,
                    topLeft = Offset(size.width * 0.14f, size.height * 0.30f),
                    size = androidx.compose.ui.geometry.Size(size.width * 0.72f, size.height * 0.52f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()),
                    style = stroke,
                )
                drawLine(
                    accent,
                    Offset(size.width * 0.5f, size.height * 0.30f),
                    Offset(size.width * 0.5f, size.height * 0.82f),
                    strokeWidth = 2.5.dp.toPx(),
                )
                drawLine(
                    accent,
                    Offset(size.width * 0.14f, size.height * 0.46f),
                    Offset(size.width * 0.86f, size.height * 0.46f),
                    strokeWidth = 2.5.dp.toPx(),
                )
            }
        }
    }
}

/**
 * Three dots, the current one wider.
 *
 * A width animation rather than a colour one: at 8dp a colour change between the accent and the
 * outline is a difference somebody has to look for, and the whole job of a page indicator is to
 * be read without looking. `WishKaroMotion.Settled` because it is the app's one motion token and
 * this is the only animated thing on the screen.
 */
@Composable
private fun PageDots(count: Int, current: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { index ->
            val selected = index == current
            val width by animateFloatAsState(
                targetValue = if (selected) 24f else 8f,
                animationSpec = WishKaroMotion.settled(),
                label = "dot_width",
            )
            Box(
                Modifier
                    .padding(horizontal = 4.dp)
                    .height(8.dp)
                    .width(width.dp)
                    .clip(CircleShape)
                    .background(
                        if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outlineVariant
                        },
                    ),
            )
        }
    }
}

/**
 * The three pages, and every word on them.
 *
 * In an enum rather than inline in the composable so that the copy is a VALUE — which is what
 * lets `OnboardingCopyTest` hold it to the rule above. M5 bought that shape for the contacts
 * disclosure (`ContactsDisclosure`) for the same reason: *"they are the requirement, and a value
 * can be held to a clause by a test where a string literal inside a composable cannot."*
 */
enum class OnboardingPage(val heading: String, val body: String) {

    /** M2, FR-REM-03. Built, shipped, and the reason this app exists. */
    REMEMBER(
        heading = "Remember",
        body = "Every birthday and anniversary that matters, in one place. WishKaro reminds " +
            "you a week before, the day before, and on the morning itself.",
    ),

    /** M5, FR-WSH-01 and FR-WSH-06. The second sentence is the requirement, not a flourish. */
    WISH(
        heading = "Wish",
        body = "A message ready when the day comes, yours to change before it goes. WishKaro " +
            "never sends anything for you — you choose where it goes and press send yourself.",
    ),

    /**
     * M1's traits, which exist. NOT a gift catalog, which does not until M8.
     *
     * The honest version of this page is the smaller claim. "Gift ideas chosen for them" would
     * be a promise the shipped app cannot keep, on the first screen anybody sees.
     */
    GIFT(
        heading = "Gift",
        body = "Keep what somebody likes, and what they don't, beside their name — so next " +
            "year you are not starting from nothing.",
    ),
}
