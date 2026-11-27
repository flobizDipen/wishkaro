package `in`.wishkaro.app.ui.wish

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import `in`.wishkaro.app.domain.Sentiment
import `in`.wishkaro.app.domain.WishChannel
import `in`.wishkaro.app.domain.WishLanguage
import `in`.wishkaro.app.domain.WishTone
import `in`.wishkaro.app.ui.adaptive.AdaptiveDialog
import `in`.wishkaro.app.ui.components.DetailTopBar
import `in`.wishkaro.app.ui.components.EmptyState
import `in`.wishkaro.app.ui.components.LoadingState
import `in`.wishkaro.app.ui.components.Note
import `in`.wishkaro.app.ui.theme.SolemnScope

/**
 * The composer (FR-WSH-01..06).
 *
 * Three things about this screen are requirements rather than choices:
 *
 *  - **It is SINGLE-PANE at every width** (FR-VIEW-08). It opts into no two-pane metadata,
 *    which is already how `TwoPaneScene` works — opting in is the thing you do, so not
 *    opting in is the whole implementation.
 *  - **The solemn composer is a different composer** (FR-WSH-09), not this one with a flag.
 *    Different tone list, different heading, different send-button wording, and nothing on
 *    it that congratulates. The strings come from the sentiment, which comes from the
 *    person, never from the event type.
 *  - **Nothing here sends anything** (FR-WSH-06). Every button on the bottom row opens
 *    another app with the text in it. That is why the "Did you send it?" dialog exists.
 *
 * Everything a device script has to touch carries a `testTag`. M4 bought that rule: Compose
 * hands `uiautomator` a clickable node with no text and no content-description, so a button
 * without a tag cannot be addressed from the view tree at all.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WishComposerScreen(
    state: ComposerState,
    onBack: () -> Unit,
    onToneChange: (WishTone) -> Unit,
    onLanguageChange: (WishLanguage) -> Unit,
    onRegenerate: () -> Unit,
    onEdit: (String) -> Unit,
    onSend: (WishChannel) -> Unit,
    onConfirmSent: () -> Unit,
    onDismissConfirm: () -> Unit,
    /** FR-HIS-05. One question after a send, and the screen that answers it. */
    onNudgeAccepted: () -> Unit = {},
    onNudgeDismissed: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    if (state.person == null) {
        if (!state.loaded) {
            LoadingState(modifier = modifier, tag = "composer_loading")
        } else {
            // FR-VIEW-05: never a blank screen that leads nowhere.
            EmptyState(
                title = "This person is no longer here",
                body = "They may have been removed on another device.",
                primaryAction = "Go back" to onBack,
                primaryTag = "composer_back_empty",
                modifier = modifier.fillMaxSize(),
            )
        }
        return
    }

    val person = state.person
    val solemn = state.isSolemn

    // The composer is the screen FR-WSH-09 is most about, so it is the screen where the
    // solemn scope matters most: inside it the occasion chip, the divider rules and every
    // accent on the screen desaturate, while the WORDS come from M5's separate string sets as
    // they already did. Theme and copy answer the same question from two directions.
    SolemnScope(solemn) {
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            DetailTopBar(
                // FR-EVT-04: no "wish" and nothing celebratory on a solemn record.
                title = if (solemn) "Message for ${person.displayName}"
                else "Wish for ${person.displayName}",
                onBack = onBack,
                // Was an IconButton containing the character "←", which a screen reader
                // announces as the Unicode name of a left arrow. A word is a word.
                backTag = "composer_back",
            )
        },
    ) { padding ->
        Column(
            // `imePadding()` BEFORE `verticalScroll`, which is the order the edge-to-edge
            // guidance calls out: applied after the scroll it pads the scrollable's CONTENT
            // rather than its viewport, and the field still ends up under the keyboard.
            //
            // It is needed at all because this app is edge-to-edge. `adjustResize` in the
            // manifest resizes the WINDOW, and a window that has already been told not to fit
            // system windows has nothing left to resize — so from M4's `enableEdgeToEdge()`
            // onward, every scrolling form in this app could put a focused field behind the
            // keyboard. The Scaffold's default `contentWindowInsets` does not carry the IME.
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.event?.let { event ->
                Text(
                    text = if (solemn) event.title else "${event.title} · ${state.occurrenceYear}",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.testTag("composer_occasion"),
                )
            }

            /**
             * FR-HIS-04. "Last year you sent…", in the composer as well as on the person
             * screen. Read from SQLite, so it is there with the radio off.
             */
            state.lastYear?.let { previous ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth().testTag("composer_last_year"),
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "In ${previous.occurrenceYear} you sent",
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Text(previous.messageText, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            ToneRow(
                tones = state.tones,
                selected = state.tone,
                enabled = !state.drafting,
                onSelect = onToneChange,
            )

            LanguageRow(
                selected = state.language,
                enabled = !state.drafting,
                onSelect = onLanguageChange,
            )

            OutlinedTextField(
                value = state.text,
                onValueChange = onEdit,
                label = { Text(if (solemn) "Your message" else "Your wish") },
                minLines = 4,
                modifier = Modifier.fillMaxWidth().testTag("composer_text"),
            )

            ProvenanceLine(state)

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedButton(
                    onClick = onRegenerate,
                    enabled = !state.drafting,
                    modifier = Modifier.testTag("composer_regenerate"),
                ) {
                    Text(if (state.problem != null) "Try again" else "Regenerate")
                }
                if (state.drafting) {
                    CircularProgressIndicator(Modifier.testTag("composer_drafting"))
                }
            }

            HorizontalDivider()

            /**
             * The four send targets (FR-WSH-05), WhatsApp first.
             *
             * Every one of them hands the text to another application. None of them sends
             * anything, and there is deliberately no fifth button that would.
             */
            Text(
                if (solemn) "Send it with" else "Send your wish with",
                style = MaterialTheme.typography.labelLarge,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Button(
                    onClick = { onSend(WishChannel.WHATSAPP) },
                    enabled = state.canSend,
                    modifier = Modifier.testTag("composer_send_whatsapp"),
                ) { Text("WhatsApp") }
                FilledTonalButton(
                    onClick = { onSend(WishChannel.SMS) },
                    enabled = state.canSend,
                    modifier = Modifier.testTag("composer_send_sms"),
                ) { Text("SMS") }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
            ) {
                OutlinedButton(
                    onClick = { onSend(WishChannel.SHARE) },
                    enabled = state.canSend,
                    modifier = Modifier.testTag("composer_send_share"),
                ) { Text("Share") }
                OutlinedButton(
                    onClick = { onSend(WishChannel.COPY) },
                    enabled = state.canSend,
                    modifier = Modifier.testTag("composer_send_copy"),
                ) { Text("Copy") }
            }
        }
    }

    }

    if (state.askedToConfirm) {
        DidYouSendIt(solemn = solemn, onYes = onConfirmSent, onDismiss = onDismissConfirm)
    }

    /**
     * FR-HIS-05. Exactly one, after the send is recorded, and never on a solemn record —
     * `WishStore` decides that, not this screen, so the rule holds for every caller.
     *
     * Shown after the confirm dialog rather than beside it: two dialogs at once is a stack,
     * and the first question ("did you send it?") is the one the app actually needs answered.
     */
    state.nudge?.let { field ->
        AdaptiveDialog(
            onDismissRequest = onNudgeDismissed,
            title = { Text("One quick thing") },
            text = { Text("Want to add ${'$'}{field.prompt} for ${'$'}{person.displayName}? It makes next year's message better.") },
            confirmButton = { TextButton(onClick = onNudgeAccepted) { Text("Add it") } },
            dismissButton = { TextButton(onClick = onNudgeDismissed) { Text("Not now") } },
        )
    }
}

/**
 * Which tones are offered, and nothing about why.
 *
 * The list comes from [ComposerState.tones], which comes from the person's sentiment. There
 * is no disabled Funny chip on a solemn occasion — FR-WSH-09 says those options are
 * UNAVAILABLE, and a greyed-out "Funny" on a death anniversary is still the word on screen.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ToneRow(
    tones: List<WishTone>,
    selected: WishTone,
    enabled: Boolean,
    onSelect: (WishTone) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Tone", style = MaterialTheme.typography.labelMedium)
        // FlowRow, not Row. Five chips do not fit across a 1080px phone, and a plain Row
        // does not wrap them — it squeezes the last one until its LABEL wraps instead, so
        // "Poetic" rendered as three stacked letters. Seen on the device, not in a preview.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            tones.forEach { tone ->
                FilterChip(
                    selected = tone == selected,
                    onClick = { onSelect(tone) },
                    enabled = enabled,
                    label = { Text(tone.label) },
                    modifier = Modifier.testTag("composer_tone_${tone.wire}"),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LanguageRow(
    selected: WishLanguage,
    enabled: Boolean,
    onSelect: (WishLanguage) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Language", style = MaterialTheme.typography.labelMedium)
        // Three chips fit today, and Hindi's label is the longest string in the app that is
        // not a name. Wrapping here for the same reason as the tones rather than waiting for
        // a fourth language to find it.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            WishLanguage.entries.forEach { language ->
                FilterChip(
                    selected = language == selected,
                    onClick = { onSelect(language) },
                    enabled = enabled,
                    label = { Text(language.label) },
                    modifier = Modifier.testTag("composer_lang_${language.wire}"),
                )
            }
        }
    }
}

/**
 * Where the words came from, and what to do about it.
 *
 * The user is told when the phone wrote this, so they know the better version is one tap
 * away. Three different failures reach the same place (docs/M5_PLAN.md §4.2) and the line
 * says something true about each — except for a refusal, where it deliberately does not:
 * the screen where somebody is writing about a death is the worst place in this app to
 * explain that a classifier declined.
 */
@Composable
private fun ProvenanceLine(state: ComposerState) {
    /**
     * The templates are ENGLISH ONLY, and a user who picked हिन्दी must be told that.
     *
     * Found on the device: selecting Hindi changed the request and left English text on
     * screen with nothing to explain it. Writing Hindi and Hinglish template sets by hand
     * is not the fix — shipped copy in a language, written by a session and reviewed by
     * nobody, is the exact risk TBD-4 exists to guard against, and these strings reach a
     * real recipient. Saying so is the fix; a model is what produces the other two
     * languages (FR-WSH-03).
     */
    val englishOnly = state.origin == DraftOrigin.TEMPLATE &&
        state.language != WishLanguage.ENGLISH

    val message = when {
        state.drafting -> null
        englishOnly ->
            "We can only write ${state.language.label} online. This one is in English, from " +
                "your phone — tap Try again when you are connected."
        state.problem == DraftProblem.OFFLINE ->
            "No connection — written on your phone. Tap Try again when you're back online."
        state.problem == DraftProblem.RATE_LIMITED ->
            "Written on your phone for now. Give it a few minutes and try again."
        state.problem == DraftProblem.UNAVAILABLE ->
            "We couldn't write this one. Here's a simple message you can change."
        state.origin == DraftOrigin.TEMPLATE ->
            "Written on your phone. Tap Regenerate for something more personal."
        else -> null
    } ?: return

    Note(message, tag = "composer_provenance")
}

/**
 * FR-WSH-07. One tap, dismissible, asked once.
 *
 * The app asks because it genuinely does not know: it handed the text to another application
 * and lost sight of it there. That is FR-WSH-06 working, not a gap.
 *
 * `AdaptiveDialog` with a plain `Text` as its body — M4's crash was a scrolling Column passed
 * as `text` with its own scroll inside, and this is deliberately one short line.
 */
@Composable
private fun DidYouSendIt(solemn: Boolean, onYes: () -> Unit, onDismiss: () -> Unit) {
    AdaptiveDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (solemn) "Did you send it?" else "Did you send your wish?") },
        text = {
            Text(
                "We'll keep it in their history, so next year's message doesn't repeat it.",
                textAlign = TextAlign.Start,
            )
        },
        confirmButton = {
            // Addressed by TEXT, not by tag: testTagsAsResourceId does not reach inside a
            // Dialog, which is its own composition in its own window. M4 §bought that.
            TextButton(onClick = onYes) { Text("Yes, sent") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Not yet") }
        },
    )
}

/** Exposed for the tone-restriction test, which asserts on the offered list, not the chips. */
internal fun offeredTones(sentiment: Sentiment): List<WishTone> =
    `in`.wishkaro.app.domain.tonesFor(sentiment)
