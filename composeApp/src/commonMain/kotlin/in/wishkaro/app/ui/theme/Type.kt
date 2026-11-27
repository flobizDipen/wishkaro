package `in`.wishkaro.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.Font
import wishkaro.resources.Res
import wishkaro.resources.fraunces_bold
import wishkaro.resources.fraunces_regular
import wishkaro.resources.fraunces_semibold
import wishkaro.resources.inter_medium
import wishkaro.resources.inter_regular
import wishkaro.resources.inter_semibold

/**
 * Fraunces for names, dates and headings. Inter for everything the interface says about itself.
 *
 * The split is the direction's, and the reason it is worth the 1.5 MB: a person's name set in a
 * serif is the one change that stops this reading as a default Material app. The chrome stays in
 * a grammar-less UI sans so that it recedes.
 *
 * **The sizes are not Material's.** `displaySmall` through `titleMedium` are larger than the
 * baseline, because `BUILD_PROMPT_M7.md` says the date is the hero and not the chrome — and
 * `main` drew almost everything at `bodyMedium`. `titleMedium` in particular moves 16 → 18sp:
 * it is what a person's name is drawn in on three screens.
 *
 * Both families are bundled files, not downloadable fonts — see
 * `composeResources/files/licenses/FONTS.md` for the versions, the hashes, the OFL copies, and
 * what happens to Devanagari, which neither family covers.
 */
@Composable
fun frauncesFamily(): FontFamily = FontFamily(
    Font(Res.font.fraunces_regular, FontWeight.Normal),
    Font(Res.font.fraunces_semibold, FontWeight.SemiBold),
    Font(Res.font.fraunces_bold, FontWeight.Bold),
)

@Composable
fun interFamily(): FontFamily = FontFamily(
    Font(Res.font.inter_regular, FontWeight.Normal),
    Font(Res.font.inter_medium, FontWeight.Medium),
    Font(Res.font.inter_semibold, FontWeight.SemiBold),
)

/**
 * Trim the first line's ascent and the last line's descent to what the glyphs need.
 *
 * Without it, a heading in a 64sp line box carries ~10sp of dead space above the capital, which
 * is what makes generous line heights look like a layout mistake rather than like air.
 */
private val Trim = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None,
)

@Composable
fun wishKaroTypography(): Typography {
    val display = frauncesFamily()
    val text = interFamily()

    fun fraunces(
        size: Int,
        lineHeight: Int,
        weight: FontWeight = FontWeight.SemiBold,
        tracking: Float = 0f,
    ) = TextStyle(
        fontFamily = display,
        fontWeight = weight,
        fontSize = size.sp,
        lineHeight = lineHeight.sp,
        letterSpacing = tracking.sp,
        lineHeightStyle = Trim,
    )

    fun inter(
        size: Int,
        lineHeight: Int,
        weight: FontWeight = FontWeight.Normal,
        tracking: Float = 0f,
    ) = TextStyle(
        fontFamily = text,
        fontWeight = weight,
        fontSize = size.sp,
        lineHeight = lineHeight.sp,
        letterSpacing = tracking.sp,
        lineHeightStyle = Trim,
    )

    return Typography(
        // Display — the carousel, and nothing else. Negative tracking because Fraunces sets
        // loose at size and a headline that is airy is a headline that looks unset.
        displayLarge = fraunces(57, 64, tracking = -1.0f),
        displayMedium = fraunces(45, 52, tracking = -0.8f),
        displaySmall = fraunces(36, 44, tracking = -0.5f),

        // Headline — the sign-in wordmark, the screen headings that are not top bars, and
        // the countdown on the person screen.
        headlineLarge = fraunces(32, 40, tracking = -0.4f),
        headlineMedium = fraunces(28, 36, tracking = -0.3f),
        headlineSmall = fraunces(24, 32, tracking = -0.2f),

        // Title — top bars, names, section headers.
        titleLarge = fraunces(22, 28),
        // 18, not Material's 16. This is a person's name on the upcoming list, the people
        // list and the deleted list.
        titleMedium = fraunces(18, 24),
        titleSmall = fraunces(15, 20),

        bodyLarge = inter(16, 24, tracking = 0.1f),
        bodyMedium = inter(14, 20, tracking = 0.15f),
        bodySmall = inter(12, 18, tracking = 0.2f),

        // Buttons and the countdowns in lists. SemiBold because a 14sp label in a warm accent
        // at Regular is the first thing to disappear on a sunlit screen.
        labelLarge = inter(14, 20, weight = FontWeight.SemiBold, tracking = 0.1f),
        labelMedium = inter(12, 16, weight = FontWeight.Medium, tracking = 0.4f),
        labelSmall = inter(11, 16, weight = FontWeight.Medium, tracking = 0.4f),
    )
}
