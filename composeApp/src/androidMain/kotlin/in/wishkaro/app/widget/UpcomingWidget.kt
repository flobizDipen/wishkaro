package `in`.wishkaro.app.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import `in`.wishkaro.app.MainActivity
import `in`.wishkaro.app.data.repository.UpcomingRepository
import `in`.wishkaro.app.domain.UpcomingBucket
import `in`.wishkaro.app.domain.UpcomingEntry
import `in`.wishkaro.app.ui.theme.WishKaroGlanceTheme
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * FR-WID-01, FR-WID-02, FR-VIEW-10 — the home-screen widget.
 *
 * Glance is Android-only and androidx-only, so this file is `androidMain` by definition.
 * The line it does NOT cross is the data: it resolves [UpcomingRepository], which lives in
 * `commonMain`, and renders [UpcomingEntry]. It never touches `LocalStore`, never touches
 * `AppStore`, and does not contain a single day of date arithmetic. That is the whole point
 * of `docs/M4_PLAN.md` §3 — the widget and the list answer "what is coming up" with the same
 * query, because two answers to that question is the duplication this project has refused
 * four times now.
 *
 * Not `AppStore` in particular, and deliberately: that is a Koin singleton on the
 * Application holding UI state, and a widget update runs in a process whose UI has usually
 * never been composed. M3 was bitten twice by UI state outliving the Activity; a widget is
 * the case where there is no Activity at all.
 */
class UpcomingWidget : GlanceAppWidget(), KoinComponent {

    private val upcoming: UpcomingRepository by inject()

    /**
     * FR-VIEW-10: sizes for a tablet home screen as well as a phone's.
     *
     * Responsive rather than Exact, so the launcher picks the nearest of these and Glance
     * does not re-render on every pixel of a resize drag.
     */
    override val sizeMode = SizeMode.Responsive(
        setOf(SMALL, MEDIUM, TALL, LARGE),
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // Read ONCE, before provideContent. A widget update is a broadcast — it wakes, reads,
        // draws and is gone — so there is nothing to subscribe to and nobody to be live for.
        // NO limit. The layout truncates to what fits, and it needs the real total to say
        // how many it left out: capping the query at MAX_ROWS made "+N more" count the
        // truncation rather than the remainder, so thirty events in the next week on a 4x2
        // widget read "+7 more" when twenty-nine were hidden. The horizon is a week, so the
        // list this reads is small whatever the account size.
        val entries = upcoming.upcomingNow(withinDays = HORIZON_DAYS)

        provideContent {
            // WishKaro's own colours, light and dark, rather than `GlanceTheme()`'s default —
            // which on Android 12+ is DYNAMIC COLOUR, so the one piece of this app that lives
            // permanently on somebody's home screen was painted from their wallpaper. See
            // `ui/theme/WidgetTheme.kt`.
            WishKaroGlanceTheme {
                WidgetBody(entries)
            }
        }
    }

    companion object {
        /**
         * With SizeMode.Responsive, `LocalSize.current` is the BUCKET the launcher picked,
         * not the widget's real size. That is why these four heights matter: the row count
         * is computed from them, so a bucket nobody can reach is a layout nobody will see.
         *
         * The first version declared only 110dp and 250dp, and dragging the widget to its
         * full height on a Pixel 9a still reported 110dp — so it stayed at one row and
         * "+7 more" however large the user made it. [TALL] is the one that fixes that.
         */

        /** 2x2 on a phone. The count and whoever is first. */
        val SMALL = DpSize(140.dp, 110.dp)

        /** 4x2: the usual home-screen row. */
        val MEDIUM = DpSize(250.dp, 110.dp)

        /** 4x3-4, which is as tall as a phone's grid goes. */
        val TALL = DpSize(250.dp, 200.dp)

        /** What a tablet home screen has room for (FR-VIEW-10). */
        val LARGE = DpSize(300.dp, 300.dp)

        /**
         * A week, because the widget answers "whose birthday is it?" and not "what does my
         * year look like" — the app has three views for the latter. A home-screen widget
         * showing something eleven months away is showing noise.
         */
        const val HORIZON_DAYS = 7

        /**
         * The most rows any size draws. A display cap, NOT a query cap — see [provideGlance]
         * for why the difference matters to the "+N more" line.
         */
        const val MAX_ROWS = 8
    }
}

@androidx.compose.runtime.Composable
private fun WidgetBody(entries: List<UpcomingEntry>) {
    val size = LocalSize.current

    // How many rows FIT, rather than a breakpoint. The first version switched from 2 rows to
    // 6 at a single 250dp threshold, and on a phone's home-screen grid that threshold is
    // effectively unreachable: dragging the widget to its full height on a Pixel 9a still
    // showed two names and "+6 more". A widget the user has made twice as tall should show
    // twice as much, on every device, without anybody guessing at grid sizes.
    val available = size.height - HEADER_HEIGHT - PADDING * 2
    val rows = (available / ROW_HEIGHT).toInt().coerceIn(1, UpcomingWidget.MAX_ROWS)
    val compact = size.width < UpcomingWidget.MEDIUM.width

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.widgetBackground)
            .cornerRadius(16.dp)
            .padding(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Header(entries = entries, compact = compact)
        Spacer(GlanceModifier.height(6.dp))

        if (entries.isEmpty()) {
            // FR-VIEW-05's rule applies here too: never a blank thing on the home screen.
            Text(
                text = "Nothing this week",
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp()),
            )
            return@Column
        }

        entries.take(rows).forEach { entry ->
            EntryRow(entry = entry, compact = compact)
        }

        val hidden = entries.size - rows
        if (hidden > 0) {
            Text(
                text = "+$hidden more",
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp()),
            )
        }
    }
}

/**
 * An intent that opens MainActivity on a `wishkaro://` URL.
 *
 * EXPLICIT — component set, not just a package. An implicit intent inside a widget's
 * PendingIntent resolves perfectly well from `adb am start` and does nothing at all when
 * the launcher sends it, which is a difficult thing to debug from the home screen because
 * there is no error anywhere: the tap simply has no effect.
 */
private fun openIntent(url: String): Intent =
    Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage("in.wishkaro.app")

@androidx.compose.runtime.Composable
private fun Header(entries: List<UpcomingEntry>, compact: Boolean) {
    val today = entries.count { it.bucket == UpcomingBucket.TODAY }
    val title = when {
        today > 0 && compact -> "$today today"
        today > 0 -> if (today == 1) "1 birthday today" else "$today today"
        entries.isEmpty() -> "WishKaro"
        else -> if (compact) "This week" else "Coming up this week"
    }

    // A Column, not a Row, and that is not a style choice. A `clickable` on a Glance `Row`
    // produced a node uiautomator reported as clickable, at the right bounds, that did
    // nothing at all when tapped — while the identical action on a `Column` (the entry rows
    // below) worked throughout. Proved by moving the WORKING url onto the Row and watching
    // it stop working. Glance 1.2.0.
    Column(
        // FR-WID-02: tapping the header opens the app. A plain launch, not a deep link —
        // the user is asking for WishKaro, not for anybody in particular.
        //
        // Two things here were learned from a real home screen rather than from the docs.
        //
        // It is on the HEADER, not on the root Column where it was first written: a
        // clickable on the root of a Glance tree does not become a working click. uiautomator
        // reports the whole widget as clickable, taps land on it, and nothing happens.
        //
        // And it goes through the SAME `wishkaro://` route the entry rows use, rather than
        // `actionStartActivity<MainActivity>()`, which also did nothing when tapped. A host
        // that is not "person" falls through MainActivity.handleDeepLink untouched and the
        // app opens where it normally would — which is what "the header opens the app"
        // means. BUILD_PROMPT_M4 asked the widget to join M2's deep-link path rather than
        // invent one; this is that, for both halves of FR-WID-02.
        modifier = GlanceModifier.fillMaxWidth()
            .clickable(
                actionStartActivity(openIntent("wishkaro://open")),
            ),
    ) {
        Text(
            text = title,
            style = TextStyle(
                color = GlanceTheme.colors.primary,
                fontWeight = FontWeight.Bold,
                fontSize = if (compact) 13.sp() else 15.sp(),
            ),
        )
    }
}

@androidx.compose.runtime.Composable
private fun EntryRow(entry: UpcomingEntry, compact: Boolean) {
    Column(
        modifier = GlanceModifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            // FR-WID-02: tapping an entry deep-links to that person, down the SAME route a
            // notification uses (`wishkaro://person/<id>`, handled in MainActivity since
            // M2). The widget joins that path rather than inventing one.
            .clickable(
                actionStartActivity(
                    openIntent("wishkaro://person/${entry.personId}"),
                ),
            ),
    ) {
        Text(
            text = entry.displayName,
            maxLines = 1,
            style = TextStyle(
                color = GlanceTheme.colors.onSurface,
                fontWeight = FontWeight.Medium,
                fontSize = if (compact) 13.sp() else 14.sp(),
            ),
        )
        Text(
            text = if (compact) entry.countdown else "${entry.title} · ${entry.countdown}",
            maxLines = 1,
            style = TextStyle(
                // Solemn entries are not given the accent colour. A death anniversary drawn
                // in the same bright purple as a birthday is exactly what FR-EVT-05 exists
                // to prevent, and a home screen is the most public place it could happen.
                color = if (entry.isSolemn) {
                    GlanceTheme.colors.onSurfaceVariant
                } else {
                    GlanceTheme.colors.secondary
                },
                fontSize = 11.sp(),
            ),
        )
    }
}

/** Measured from the rendered widget on a Pixel 9a rather than guessed. */
private val HEADER_HEIGHT = 34.dp
private val ROW_HEIGHT = 46.dp
private val PADDING = 12.dp

private fun Int.sp() = androidx.compose.ui.unit.TextUnit(
    this.toFloat(),
    androidx.compose.ui.unit.TextUnitType.Sp,
)
