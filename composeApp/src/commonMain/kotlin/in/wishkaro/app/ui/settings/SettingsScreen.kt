package `in`.wishkaro.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import `in`.wishkaro.app.domain.QuietHours
import `in`.wishkaro.app.domain.ReminderSettings
import `in`.wishkaro.app.platform.OemVendor
import `in`.wishkaro.app.ui.ReliabilityState
import `in`.wishkaro.app.ui.SelfTestState
import `in`.wishkaro.app.ui.adaptive.AdaptiveDialog
import `in`.wishkaro.app.ui.components.Note
import `in`.wishkaro.app.ui.components.SectionHeader
import `in`.wishkaro.app.ui.components.ToggleRow
import `in`.wishkaro.app.ui.components.WarningNote
import `in`.wishkaro.app.ui.components.WishKaroCard

/**
 * The Settings screen M2 creates (FR-SET-01, FR-SET-02).
 *
 * Ordered by what actually decides whether a reminder arrives, not by what is easiest to
 * build. "Will reminders reach you?" is first, because on the phones this product is for, a
 * perfectly configured ladder behind a battery manager delivers nothing at all.
 */
@Composable
fun SettingsScreen(
    settings: ReminderSettings,
    reliability: ReliabilityState,
    selfTest: SelfTestState,
    onBack: () -> Unit,
    onChange: ((ReminderSettings) -> ReminderSettings) -> Unit,
    onSendTest: () -> Unit,
    onDismissTest: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    onRequestExactAlarms: () -> Unit,
    /** M9. The privacy section is absent rather than disabled when nobody is signed in. */
    privacy: PrivacyState? = null,
    modifier: Modifier = Modifier,
) {
    /**
     * **This screen is not in a `Scaffold`**, which is why the insets are written out here.
     *
     * The app's outer `Scaffold` declares `WindowInsets(0, 0, 0, 0)` on purpose — every screen
     * that has a top bar insets itself, and letting the outer one inset as well counted the
     * status bar twice, which was the band of empty purple above the title from M0 to M4. The
     * cost of that decision is that a screen with NO top bar gets no insets at all, and this
     * is one of seven. Before M7 the Back button sat under the status bar on any phone with a
     * cutout, and the last switch sat under the gesture bar.
     *
     * `windowInsetsPadding` for the horizontal and top sides, so the content starts below the
     * status bar; the bottom goes to `contentPadding` instead, because padding a scrollable's
     * PARENT clips it and stops the list scrolling behind the navigation bar — which is the
     * one mistake the edge-to-edge guidance calls out by name for lists.
     */
    val safe = WindowInsets.safeDrawing
    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(safe.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top))
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(
            bottom = safe.asPaddingValues().calculateBottomPadding() + 32.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBack) { Text("Back") }
                Spacer(Modifier.width(4.dp))
                Text("Settings", style = MaterialTheme.typography.titleLarge)
            }
        }

        // ---- Reliability, first (FR-REM-06, FR-SET-02) --------------------------------
        item { SectionHeader("Will reminders reach you?") }

        item {
            ReliabilityCard(
                reliability = reliability,
                onOpenBatterySettings = onOpenBatterySettings,
                onRequestExactAlarms = onRequestExactAlarms,
            )
        }

        item { SelfTestCard(selfTest, onSendTest, onDismissTest) }

        // ---- The ladder (FR-REM-03) ----------------------------------------------------
        item { SectionHeader("When to remind you") }

        item {
            ToggleRow(
                title = "A week before",
                subtitle = "Time to think about a gift. Never sent for a remembrance date.",
                checked = settings.ladderD7,
                onCheckedChange = { on -> onChange { it.copy(ladderD7 = on) } },
            )
        }
        item {
            ToggleRow(
                title = "The day before",
                subtitle = null,
                checked = settings.ladderD1,
                onCheckedChange = { on -> onChange { it.copy(ladderD1 = on) } },
            )
        }
        item {
            ToggleRow(
                title = "On the day",
                subtitle = null,
                checked = settings.ladderD0,
                onCheckedChange = { on -> onChange { it.copy(ladderD0 = on) } },
            )
        }

        item {
            HourPicker(
                label = "Delivery time",
                hour = settings.reminderHour,
                enabled = !settings.digestEnabled,
                note = if (settings.digestEnabled) {
                    "The daily summary decides the time while it is on."
                } else {
                    "You can set a different time for one person on their own screen."
                },
                onPick = { h -> onChange { it.copy(reminderHour = h) } },
            )
        }

        /*
         * The one place the two clocks can quietly fight each other.
         *
         * Nothing here is broken — FR-REM-09 says a reminder inside the quiet window is
         * deferred to the window's end and not dropped, and that is exactly what happens. But
         * picking a delivery hour that sits inside your OWN quiet window moves morning-of to
         * the following day: a 2 December birthday notified at 07:00 on the 3rd. That is
         * surprising enough that the screen should say it rather than leave it to be
         * discovered on the day.
         */
        if (!settings.digestEnabled) {
            quietHoursClashNote(settings.reminderHour, settings)?.let { warning ->
                item { ClashNote(warning) }
            }
        }

        // ---- Quiet hours (FR-REM-09) ---------------------------------------------------
        item { SectionHeader("Quiet hours") }

        item {
            ToggleRow(
                title = "Stay quiet overnight",
                subtitle = "Nothing arrives inside the window. It waits until the window ends.",
                checked = settings.quietHoursOn,
                onCheckedChange = { on ->
                    onChange {
                        // Both ends together, always. The server rejects a half-set window,
                        // because "quiet from 22:00 until unspecified" would silence the app
                        // for ever while it went on looking like it worked.
                        if (on) it.copy(quietHoursStart = 22, quietHoursEnd = 7)
                        else it.copy(quietHoursStart = null, quietHoursEnd = null)
                    }
                },
            )
        }

        if (settings.quietHoursOn) {
            item {
                HourPicker(
                    label = "Quiet from",
                    hour = settings.quietHoursStart ?: 22,
                    onPick = { h -> onChange { it.copy(quietHoursStart = h) } },
                )
            }
            item {
                HourPicker(
                    label = "Quiet until",
                    hour = settings.quietHoursEnd ?: 7,
                    onPick = { h -> onChange { it.copy(quietHoursEnd = h) } },
                )
            }
        }

        // ---- Digest (FR-REM-08) --------------------------------------------------------
        item { SectionHeader("Daily summary") }

        item {
            ToggleRow(
                title = "One notification a day",
                subtitle = "Instead of every reminder separately — including the week-ahead " +
                    "and day-before ones.",
                checked = settings.digestEnabled,
                onCheckedChange = { on -> onChange { it.copy(digestEnabled = on) } },
            )
        }

        if (settings.digestEnabled) {
            item {
                HourPicker(
                    label = "Summary time",
                    hour = settings.digestHour,
                    onPick = { h -> onChange { it.copy(digestHour = h) } },
                )
            }
            quietHoursClashNote(settings.digestHour, settings)?.let { warning ->
                item { ClashNote(warning) }
            }
        }

        // ---- Privacy and your data (FR-SET-03, FR-SET-04, FR-SET-05) -------------------
        //
        // LAST, and that is deliberate. FR-AUTH-08 wants deletion reachable in three taps from
        // Settings, which this is — Settings, scroll, Delete — and a destructive action placed
        // between two toggles is one somebody reaches by accident.
        if (privacy != null) {
            item { SectionHeader("Your data") }
            item { PrivacySection(privacy) }
        }

        item { Spacer(Modifier.height(32.dp)) }
    }
}

/**
 * Everything FR-SET-03, FR-SET-04 and FR-SET-05 put on this screen.
 *
 * A state object rather than nine parameters on `SettingsScreen`, because the reminder half of
 * this screen and the privacy half have nothing to do with each other and threading one
 * through the other is how a screen becomes impossible to change.
 */
data class PrivacyState(
    /** The address the user must type to confirm deletion (FR-AUTH-08). */
    val email: String,
    val exporting: Boolean = false,
    val deleting: Boolean = false,
    /** Shown after an export is saved, or when either action failed. */
    val message: String? = null,
    val onExport: () -> Unit = {},
    val onDeleteAccount: (String) -> Unit = {},
    val onOpenPrivacyPolicy: (() -> Unit)? = null,
    val onOpenTerms: (() -> Unit)? = null,
)

@Composable
private fun PrivacySection(privacy: PrivacyState) {
    var confirming by rememberSaveable { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Note(
            "Export your data as a file you can keep, or delete this account and everything " +
                "in it.",
        )

        OutlinedButton(
            onClick = privacy.onExport,
            enabled = !privacy.exporting && !privacy.deleting,
            modifier = Modifier.fillMaxWidth().testTag("settings_export"),
        ) { Text(if (privacy.exporting) "Preparing your file…" else "Export my data") }

        /**
         * FR-SET-05: reachable in-app. Absent rather than disabled when there is no URL to
         * open — which is where this stands until decision 1 in docs/M9_PLAN.md settles where
         * the pages live. A button that opens nothing is worse than no button: it tells a Play
         * reviewer the requirement is met.
         */
        privacy.onOpenPrivacyPolicy?.let { open ->
            TextButton(onClick = open, modifier = Modifier.fillMaxWidth()) { Text("Privacy policy") }
        }
        privacy.onOpenTerms?.let { open ->
            TextButton(onClick = open, modifier = Modifier.fillMaxWidth()) { Text("Terms") }
        }

        privacy.message?.let { Note(it) }

        TextButton(
            onClick = { confirming = true },
            enabled = !privacy.deleting,
            modifier = Modifier.fillMaxWidth().testTag("settings_delete_account"),
        ) {
            Text(
                "Delete my account",
                color = MaterialTheme.colorScheme.error,
            )
        }
    }

    if (confirming) {
        DeleteAccountDialog(
            email = privacy.email,
            busy = privacy.deleting,
            onDismiss = { confirming = false },
            onConfirm = { typed ->
                confirming = false
                privacy.onDeleteAccount(typed)
            },
        )
    }
}

/**
 * FR-AUTH-08: *"irreversible, confirmed by typing"*, and FR-SET-04's "explicit confirmation".
 *
 * Typing the address rather than ticking a box, because the whole point is that the action
 * cannot be taken by muscle memory. The list of what goes is specific — a dialog that says
 * "this cannot be undone" and nothing else leaves the user to imagine the scope, and people
 * imagine it smaller than it is.
 */
@Composable
private fun DeleteAccountDialog(
    email: String,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var typed by rememberSaveable { mutableStateOf("") }
    val matches = typed.trim().equals(email, ignoreCase = true)

    AdaptiveDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Delete your account?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("This cannot be undone. Deleting your account removes:")
                Text(
                    "\u2022  every person and every date you have added\n" +
                        "\u2022  every wish you have recorded and every poster you have made\n" +
                        "\u2022  every photo you have uploaded\n" +
                        "\u2022  anyone you deleted recently and could still have restored\n" +
                        "\u2022  this phone's reminders, immediately",
                    style = MaterialTheme.typography.bodySmall,
                )
                /**
                 * Said plainly, because the policy page has to say the same thing and the two
                 * must not disagree. Dipen's decision 3: the ledger is anonymised, not erased.
                 */
                Text(
                    "We keep a record of what our AI features cost us to run. It holds no " +
                        "names, no messages and nothing you typed, and after this it is not " +
                        "linked to you.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text("Type $email to confirm.")
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    singleLine = true,
                    enabled = !busy,
                    label = { Text("Your email address") },
                    modifier = Modifier.fillMaxWidth().testTag("delete_confirm_field"),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(typed.trim()) },
                // Both: matching the address AND not already running. A second tap while the
                // first request is in flight would send a second deletion for an account the
                // first one is in the middle of removing.
                enabled = matches && !busy,
                modifier = Modifier.testTag("delete_confirm_button"),
            ) {
                Text(
                    if (busy) "Deleting…" else "Delete everything",
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("Keep my account") }
        },
    )
}

@Composable
private fun ReliabilityCard(
    reliability: ReliabilityState,
    onOpenBatterySettings: () -> Unit,
    onRequestExactAlarms: () -> Unit,
) {
    WishKaroCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CheckLine("Notifications allowed", reliability.notificationsAllowed)
            CheckLine("Reminders can fire at an exact time", reliability.exactAlarmsAllowed)
            CheckLine("Not restricted by the battery saver", reliability.batteryUnrestricted)

            if (!reliability.exactAlarmsAllowed) {
                Text(
                    "Without exact alarms a reminder can arrive late. Android asks for this " +
                        "one separately.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(onClick = onRequestExactAlarms) { Text("Allow exact alarms") }
            }

            Divider()

            Text(
                if (reliability.vendor == OemVendor.OTHER) {
                    "Phones often close apps in the background to save battery. If that " +
                        "happens, reminders stop arriving."
                } else {
                    "${reliability.vendor.label} phones close background apps to save " +
                        "battery. When that happens, reminders stop arriving and nothing " +
                        "warns you."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(onClick = onOpenBatterySettings) {
                Text("Open battery settings")
            }

            // Shown only after the button has been pressed, because the steps depend on
            // which screen actually opened — the vendor's own, or Android's generic one.
            reliability.instructions.forEachIndexed { index, step ->
                Text("${index + 1}. $step", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun SelfTestCard(
    state: SelfTestState,
    onSendTest: () -> Unit,
    onDismiss: () -> Unit,
) {
    WishKaroCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Check it works", style = MaterialTheme.typography.titleSmall)
            when (state) {
                SelfTestState.Idle -> {
                    Text(
                        "Send yourself a test reminder. It arrives in about two minutes.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(onClick = onSendTest) { Text("Send a test reminder") }
                }

                SelfTestState.Queueing -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.height(18.dp))
                    Text("  Asking the server…", style = MaterialTheme.typography.bodySmall)
                }

                is SelfTestState.Waiting -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.height(18.dp))
                    Text(
                        "  Waiting for it to arrive — keep this screen open.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                is SelfTestState.Delivered -> {
                    Text("Arrived after ${state.secondsTaken} seconds. Delivery works on this phone.")
                    TextButton(onClick = onDismiss) { Text("Done") }
                }

                // The outcome the feature exists for. A Xiaomi owner whose battery manager is
                // eating reminders finds out here, in two minutes, rather than by missing a
                // birthday — and the next thing on screen is the thing that fixes it.
                is SelfTestState.NotArrived -> {
                    Text("It never arrived.", fontWeight = FontWeight.SemiBold)
                    Text(
                        "That usually means this phone is closing WishKaro in the background. " +
                            "Open battery settings above and allow it to run.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = onDismiss) { Text("Dismiss") }
                }

                is SelfTestState.Failed -> {
                    Text("Could not ask the server: ${state.reason}")
                    TextButton(onClick = onDismiss) { Text("Dismiss") }
                }
            }
        }
    }
}

/**
 * Says, in the user's own numbers, what the quiet window will do to the hour they picked.
 *
 * Null when there is nothing to say — no window, or an hour outside it. The same
 * [QuietHours.isQuietHour] the fallback alarm uses, so the screen cannot disagree with the
 * behaviour it is describing.
 */
private fun quietHoursClashNote(hour: Int, settings: ReminderSettings): String? {
    val start = settings.quietHoursStart ?: return null
    val end = settings.quietHoursEnd ?: return null
    if (!QuietHours.isQuietHour(hour, start, end)) return null

    // Before midnight the deferral lands on the NEXT day, which is the part worth spelling
    // out: an on-the-day reminder then arrives the morning after the day itself.
    val sameDay = hour < end
    return if (sameDay) {
        "${formatHour(hour)} is inside your quiet hours, so reminders will arrive at " +
            "${formatHour(end)} instead."
    } else {
        "${formatHour(hour)} is inside your quiet hours, so reminders will arrive at " +
            "${formatHour(end)} the NEXT morning — an on-the-day reminder would reach you " +
            "the day after."
    }
}

@Composable
private fun ClashNote(text: String) {
    WarningNote(text, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun CheckLine(label: String, ok: Boolean) {
    Text(
        text = if (ok) "✓  $label" else "✕  $label",
        style = MaterialTheme.typography.bodyMedium,
        color = if (ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
    )
}

// `SettingRow` is now `components/ToggleRow`, shared with the deceased switch on the edit
// screen and the per-event mute toggle on the person screen. Three switch-and-label rows,
// three different layouts, is what a design system is for.

/**
 * Hours as chips rather than a time picker.
 *
 * Delivery is on the hour by design — the server stores a local HOUR, not a time — so a
 * picker offering minutes would promise a precision the whole schedule does not have.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HourPicker(
    label: String,
    hour: Int,
    onPick: (Int) -> Unit,
    enabled: Boolean = true,
    note: String? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("$label — ${formatHour(hour)}", style = MaterialTheme.typography.bodyLarge)
        note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            HOURS.forEach { h ->
                FilterChip(
                    selected = h == hour,
                    enabled = enabled,
                    onClick = { onPick(h) },
                    label = { Text(formatHour(h)) },
                )
            }
        }
    }
}

private val HOURS = (0..23).toList()

private fun formatHour(hour: Int): String {
    val suffix = if (hour < 12) "am" else "pm"
    val display = when {
        hour == 0 -> 12
        hour > 12 -> hour - 12
        else -> hour
    }
    return "$display$suffix"
}
