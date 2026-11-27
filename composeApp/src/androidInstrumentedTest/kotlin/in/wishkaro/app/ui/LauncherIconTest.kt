package `in`.wishkaro.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
// raw-color-ok: this test stands in for the SYSTEM, which tints a monochrome icon from the
// user's wallpaper palette. The colours below are what a launcher would supply, not paint
// this app chooses — and a themed icon cannot be rendered through MaterialTheme, because the
// thing being rendered is an Android drawable outside any composition.
import android.graphics.Color // raw-color-ok: the system's tint, not this app's paint
import android.graphics.PorterDuff
import android.graphics.drawable.AdaptiveIconDrawable
import androidx.core.content.res.ResourcesCompat
import androidx.test.platform.app.InstrumentationRegistry
import `in`.wishkaro.app.R
import java.io.File
import kotlin.test.assertTrue
import org.junit.Test

/**
 * The launcher icon's three layers, rendered and written out — M7 item 6.
 *
 * ## Why this exists rather than a photograph of a home screen
 *
 * The obvious evidence for "the monochrome layer works" is a screenshot of the app on a
 * launcher with themed icons turned on. That was attempted on the handset and it does not
 * prove what it looks like it proves:
 *
 *  - `themed_icon: "1"` was set, the launcher was restarted, and its tiles visibly changed to
 *    the themed treatment — so themed mode was genuinely active.
 *  - WishKaro still drew in full colour, **in the app drawer**, because Pixel Launcher applies
 *    themed icons to the **home screen** and not to the drawer or to search results.
 *  - Putting the icon on the home screen means dragging something around the owner's own home
 *    screen with `input swipe`, which is precisely the blind-gesture class of mistake
 *    `MILESTONE_CHECKLIST.md` §E and M4's additions exist to stop. Three of those went into
 *    the wrong app across M2 and M3.
 *
 * So this renders the layers directly, which proves the half that is actually this repository's:
 * that the drawable exists, is declared, and produces a legible silhouette when the system
 * tints it. `M7_VERIFY.md` states plainly that the remaining half — Pixel Launcher choosing to
 * use it on a home screen — is read-verified rather than seen.
 *
 * The strongest line of evidence is not this test but `aapt2 dump xmltree` against the shipped
 * APK, which shows `<monochrome>` present on the `adaptive-icon` with a real drawable behind
 * it. That output is quoted in the verification document.
 */
class LauncherIconTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val outputDir: File by lazy {
        File(context.getExternalFilesDir(null), "icon").apply { mkdirs() }
    }

    private fun write(name: String, bitmap: Bitmap) {
        File(outputDir, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    /** 108dp at 4x, which is what a launcher asks an adaptive icon for on a dense screen. */
    private val size = 432

    private fun render(drawable: android.graphics.drawable.Drawable, tint: Int? = null): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, size, size)
        if (tint != null) drawable.setColorFilter(tint, PorterDuff.Mode.SRC_IN)
        drawable.draw(canvas)
        return bitmap
    }

    @Test
    fun the_adaptive_icon_has_all_three_layers_and_each_one_draws() {
        val icon = ResourcesCompat.getDrawable(context.resources, R.mipmap.ic_launcher, null)
        assertTrue(icon is AdaptiveIconDrawable, "@mipmap/ic_launcher is not an adaptive icon")
        icon as AdaptiveIconDrawable

        write("01-composed", render(icon))
        icon.background?.let { write("02-background", render(it)) }
        icon.foreground?.let { write("03-foreground", render(it)) }

        /**
         * `monochrome` is API 33+. The device this runs on is well past that, and a null here
         * would mean the layer is missing from the drawable — which is the whole item.
         */
        val mono = icon.monochrome
        assertTrue(mono != null, "the adaptive icon has no <monochrome> layer; themed icons will fall back to the full-colour one")

        // Drawn twice, in the two directions a system tint goes: a dark glyph for a light
        // themed palette and a light one for a dark one. Both are written out, because "the
        // silhouette is legible" is a claim about the SHAPE and the shape is what survives
        // losing the colour.
        write("04-monochrome-dark-on-light", render(mono!!.mutate(), Color.parseColor("#2B2823")))
        write("05-monochrome-light-on-dark", render(mono.mutate(), Color.parseColor("#F0EBE3")))

        /**
         * The silhouette must not be a slab.
         *
         * The real failure mode for a monochrome layer is not that it is missing — it is that
         * it is the foreground with every fill set to one colour, which for this icon would be
         * a solid rounded rectangle: page, header and dots all the same. So this counts how
         * much of the drawable's bounding box is actually painted. A calendar outline with
         * eight dots in it covers well under half; a slab covers nearly all of it.
         */
        val painted = render(mono.mutate(), Color.BLACK)
        var opaque = 0
        val pixels = IntArray(size * size)
        painted.getPixels(pixels, 0, size, 0, 0, size, size)
        for (p in pixels) if (Color.alpha(p) > 128) opaque++
        val coverage = opaque.toDouble() / pixels.size
        println("monochrome coverage of the 108x108 box: ${(coverage * 100).toInt()}%")
        assertTrue(
            coverage in 0.05..0.45,
            "the monochrome layer covers ${(coverage * 100).toInt()}% of its box. Below 5% it " +
                "is probably empty; above 45% it is probably a solid slab rather than a " +
                "silhouette with the page and the days still readable in it.",
        )
    }
}
