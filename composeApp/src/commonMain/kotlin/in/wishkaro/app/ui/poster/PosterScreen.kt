package `in`.wishkaro.app.ui.poster

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.decodeToImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import `in`.wishkaro.app.domain.PosterFormat
import `in`.wishkaro.app.domain.PosterTheme
import `in`.wishkaro.app.ui.adaptive.AdaptiveDialog

/**
 * The poster studio (FR-POS-01..13).
 *
 * Three things about this screen are requirements rather than choices:
 *
 *  - **The picker shows the occasion's own family and nothing else** (FR-POS-13). Not
 *    ranked lower — absent. `state.themes` is already filtered by `posterThemesFor(person)`
 *    and this screen does no filtering of its own, because a second filter is a second
 *    chance to get the most dangerous rule in the product wrong.
 *  - **Nothing here sends anything** (FR-WSH-06, unchanged by the payload being a picture).
 *    Both buttons hand the poster to another app and stop.
 *  - **Every generated asset carries "Report this content"** (FR-POS-12, and the Play GenAI
 *    policy box in §12). It is on the preview, where the thing being reported is on screen.
 *
 * Everything a device script has to touch carries a `testTag`, for the reason M4 wrote
 * down: Compose hands `uiautomator` a clickable node with no text and no
 * content-description, so a button without a tag cannot be addressed from the view tree.
 *
 * TBD-5 is open, so the previews below are the theme's own gradient and the display names
 * say "placeholder". When the art direction lands, this screen draws whatever
 * `GET /v1/themes` serves and the swatches become thumbnails.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PosterScreen(
    state: PosterState,
    onBack: () -> Unit,
    onSelectTheme: (String) -> Unit,
    onSelectFormat: (PosterFormat) -> Unit,
    onGenerate: () -> Unit,
    onPickPhoto: () -> Unit,
    onRemovePhoto: () -> Unit,
    onHandOver: (Boolean) -> Unit,
    onReport: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var reporting by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    // The heading is a different heading for a remembrance, not the same
                    // one with a flag — the arrangement FR-WSH-09 established for words.
                    Text(if (state.isSolemn) "Remembrance poster" else "Make a poster")
                },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("poster_back")) {
                        Text("←")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            state.person?.let { person ->
                Text(
                    text = if (state.isSolemn) "For ${person.displayName}" else "For ${person.displayName}",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.testTag("poster_person"),
                )
            }

            PosterPreview(state)

            if (state.stage == PosterStage.READY) {
                HandOverRow(state, onHandOver, onReport = { reporting = true })
            }

            HorizontalDivider()

            PhotoRow(state, onPickPhoto, onRemovePhoto)

            ThemePicker(state, onSelectTheme)

            FormatPicker(state, onSelectFormat)

            Button(
                onClick = onGenerate,
                enabled = state.loaded && !state.working,
                modifier = Modifier.fillMaxWidth().testTag("poster_generate"),
            ) {
                Text(if (state.poster == null) "Make the poster" else "Make it again")
            }

            Text(
                text = "WishKaro never sends anything for you. You'll choose where this goes.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 24.dp),
            )
        }
    }

    if (reporting) {
        ReportDialog(
            onDismiss = { reporting = false },
            onReport = {
                onReport(it)
                reporting = false
            },
        )
    }
}

/**
 * How tall the preview may get.
 *
 * **Found on the handset.** The first version was `fillMaxWidth().aspectRatio(ratio)`, which
 * for STATUS (9:16) makes the preview 1.78x as tall as it is wide — taller than the
 * viewport on a 20:9 phone. Opening the studio showed the title, the person's name and a
 * full screen of empty gradient: the theme picker, the size chips and the "Make the poster"
 * button were all below the fold, and nothing on screen suggested they existed. The
 * instrumented test did not catch it because `assertExists` passes for a node that is
 * composed but off-screen, which is the correct behaviour for that assertion and the wrong
 * thing to rely on here.
 *
 * 380dp leaves the picker and the button visible on a 640dp-tall phone without making the
 * preview too small to judge. `M6_VERIFY.md` §0.5.
 */
private val PREVIEW_MAX_HEIGHT = 380.dp

@Composable
private fun PosterPreview(state: PosterState) {
    val theme = state.selectedTheme
    val ratio = state.format.width.toFloat() / state.format.height.toFloat()

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
    // Fit INSIDE both bounds rather than letting one win: a 9:16 poster is height-bound and
    // a 1.91:1 one is width-bound, and picking either rule alone overflows the other case.
    val height = minOf(this@BoxWithConstraints.maxWidth / ratio, PREVIEW_MAX_HEIGHT)

    Box(
        modifier = Modifier
            .align(Alignment.Center)
            .height(height)
            .width(height * ratio)
            .background(
                // The theme's own gradient while there is nothing to show, so the preview
                // is the shape and the palette of what is coming rather than a grey box.
                Brush.linearGradient(
                    listOf(
                        parseHex(theme?.backgroundFrom) ?: MaterialTheme.colorScheme.surfaceVariant,
                        parseHex(theme?.backgroundTo) ?: MaterialTheme.colorScheme.surface,
                    ),
                ),
                RoundedCornerShape(12.dp),
            )
            .testTag("poster_preview"),
        contentAlignment = Alignment.Center,
    ) {
        val bytes = state.posterBytes
        when {
            bytes != null -> {
                val image: ImageBitmap? = remember(bytes) {
                    runCatching { bytes.decodeToImageBitmap() }.getOrNull()
                }
                if (image != null) {
                    Image(
                        bitmap = image,
                        // Null rather than a description of the poster: the only honest
                        // description would name the person, and a screen reader reading
                        // somebody's remembrance poster aloud as alt text is not something
                        // to invent here. The heading above already says whose it is.
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().testTag("poster_image"),
                    )
                } else {
                    Text("Couldn't show the poster", textAlign = TextAlign.Center)
                }
            }

            state.working -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(modifier = Modifier.testTag("poster_progress"))
                Text(
                    "Making it…",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            state.problem != null -> Text(
                text = when (state.problem) {
                    // Deliberately vague for BLOCKED. The screen where somebody is making a
                    // remembrance poster is the worst place in this app to explain that a
                    // classifier objected.
                    PosterProblem.BLOCKED -> "We couldn't make this one. Try another theme."
                    PosterProblem.OFFLINE -> "You're offline. Posters need a connection."
                    PosterProblem.UNAVAILABLE -> "We couldn't make this one just now."
                },
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(24.dp).testTag("poster_problem"),
            )

            else -> Text(
                text = theme?.displayName ?: "Pick a theme",
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(24.dp),
            )
        }
    }
    }
}

@Composable
private fun HandOverRow(
    state: PosterState,
    onHandOver: (Boolean) -> Unit,
    onReport: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { onHandOver(true) },
                enabled = state.posterBytes != null,
                modifier = Modifier.weight(1f).testTag("poster_whatsapp"),
            ) { Text("WhatsApp") }

            OutlinedButton(
                onClick = { onHandOver(false) },
                enabled = state.posterBytes != null,
                modifier = Modifier.weight(1f).testTag("poster_share"),
            ) { Text("Share") }
        }

        if (state.handedOver) {
            Text(
                "Handed over. Press send there.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("poster_handed_over"),
            )
        }

        // FR-POS-12 and §12's Play GenAI box. On the asset, where the thing being
        // reported is on screen.
        TextButton(
            onClick = onReport,
            enabled = !state.reported,
            modifier = Modifier.testTag("poster_report"),
        ) {
            Text(if (state.reported) "Reported — thank you" else "Report this content")
        }
    }
}

@Composable
private fun PhotoRow(state: PosterState, onPick: () -> Unit, onRemove: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Photo", style = MaterialTheme.typography.titleSmall)
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = onPick,
                enabled = !state.uploadingPhoto,
                modifier = Modifier.testTag("poster_pick_photo"),
            ) {
                Text(if (state.photoUrl == null) "Add a photo" else "Change photo")
            }
            if (state.photoUrl != null) {
                TextButton(onClick = onRemove, modifier = Modifier.testTag("poster_remove_photo")) {
                    Text("Remove")
                }
            }
            if (state.uploadingPhoto) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
            }
        }
        // FR-POS-06 / OD-11: no photo is a first-class case, not a missing step, and the
        // copy says so rather than nagging.
        Text(
            text = if (state.photoUrl == null) {
                "Optional — posters without a photo are typographic, and still good."
            } else {
                "Your photo goes on unchanged. It is never passed through an AI model."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ThemePicker(state: PosterState, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Theme", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // `state.themes` is ALREADY the occasion's family. No filter here, no sort, no
            // "others" section — FR-POS-13 says filtered out of the picker, and anything
            // this screen added would be a second place for that to be got wrong.
            state.themes.forEach { theme ->
                ThemeSwatch(
                    theme = theme,
                    selected = theme.id == state.selectedTheme?.id,
                    onClick = { onSelect(theme.id) },
                )
            }
        }
    }
}

@Composable
private fun ThemeSwatch(theme: PosterTheme, selected: Boolean, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(onClick = onClick)
            .testTag("poster_theme_${theme.id}"),
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .background(
                    Brush.linearGradient(
                        listOf(
                            parseHex(theme.backgroundFrom) ?: Color.Gray,
                            parseHex(theme.backgroundTo) ?: Color.DarkGray,
                        ),
                    ),
                    RoundedCornerShape(10.dp),
                )
                .border(
                    width = if (selected) 3.dp else 1.dp,
                    color = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outlineVariant
                    },
                    shape = RoundedCornerShape(10.dp),
                ),
        )
        Text(
            // The display names carry "— placeholder" while TBD-5 is open, deliberately,
            // so nobody mistakes a gradient for art direction.
            text = theme.displayName.removeSuffix(" — placeholder"),
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(72.dp).padding(top = 4.dp),
        )
    }
}

@Composable
private fun FormatPicker(state: PosterState, onSelect: (PosterFormat) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Size", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PosterFormat.entries.forEach { format ->
                FilterChip(
                    selected = state.format == format,
                    onClick = { onSelect(format) },
                    label = { Text(format.label) },
                    modifier = Modifier.testTag("poster_format_${format.wire}"),
                )
            }
        }
    }
}

/** FR-POS-12's categories. A fixed list, so a report is a fact rather than an essay. */
private val REPORT_REASONS = listOf(
    "OFFENSIVE" to "Offensive or hurtful",
    "INSENSITIVE" to "Insensitive for this occasion",
    "WRONG_TONE" to "Wrong tone",
    "LOOKS_WRONG" to "Looks wrong or broken",
    "OTHER" to "Something else",
)

@Composable
private fun ReportDialog(onDismiss: () -> Unit, onReport: (String) -> Unit) {
    AdaptiveDialog(
        onDismissRequest = onDismiss,
        title = { Text("Report this poster") },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("poster_report_cancel")) {
                Text("Cancel")
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "This goes to us, not to anyone you've written to.",
                    style = MaterialTheme.typography.bodySmall,
                )
                REPORT_REASONS.forEach { (code, label) ->
                    TextButton(
                        onClick = { onReport(code) },
                        modifier = Modifier.fillMaxWidth().testTag("poster_report_" + code),
                    ) {
                        Text(label, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        },
    )
}

/**
 * `#RRGGBB` to a Color.
 *
 * The manifest stores hex because that is what the shared fixture holds and what the
 * server's SVG needs; Compose needs an ARGB long. Returns null rather than throwing on a
 * malformed value, because a bad hex in a theme should be a grey swatch, not a crash on
 * the person screen.
 */
private fun parseHex(hex: String?): Color? {
    val raw = hex?.removePrefix("#") ?: return null
    if (raw.length != 6) return null
    val value = raw.toLongOrNull(16) ?: return null
    return Color(0xFF000000L or value)
}
