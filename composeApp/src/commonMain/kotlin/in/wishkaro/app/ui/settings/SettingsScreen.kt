package `in`.wishkaro.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import `in`.wishkaro.app.domain.ReminderSettings
import `in`.wishkaro.app.platform.OemVendor
import `in`.wishkaro.app.ui.ReliabilityState
import `in`.wishkaro.app.ui.SelfTestState
import `in`.wishkaro.app.ui.components.SectionHeader

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
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBack) { Text("Back") }
                Spacer(Modifier.height(0.dp))
                Text("Settings", style = MaterialTheme.typography.headlineSmall)
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
            SettingRow(
                title = "A week before",
                subtitle = "Time to think about a gift. Never sent for a remembrance date.",
                checked = settings.ladderD7,
                onCheckedChange = { on -> onChange { it.copy(ladderD7 = on) } },
            )
        }
        item {
            SettingRow(
                title = "The day before",
                subtitle = null,
                checked = settings.ladderD1,
                onCheckedChange = { on -> onChange { it.copy(ladderD1 = on) } },
            )
        }
        item {
            SettingRow(
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

        // ---- Quiet hours (FR-REM-09) ---------------------------------------------------
        item { SectionHeader("Quiet hours") }

        item {
            SettingRow(
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
            SettingRow(
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
        }

        item { Spacer(Modifier.height(32.dp)) }
    }
}

@Composable
private fun ReliabilityCard(
    reliability: ReliabilityState,
    onOpenBatterySettings: () -> Unit,
    onRequestExactAlarms: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors()) {
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
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Check it works", fontWeight = FontWeight.SemiBold)
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

@Composable
private fun CheckLine(label: String, ok: Boolean) {
    Text(
        text = if (ok) "✓  $label" else "✕  $label",
        style = MaterialTheme.typography.bodyMedium,
        color = if (ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
    )
}

@Composable
private fun SettingRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

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
