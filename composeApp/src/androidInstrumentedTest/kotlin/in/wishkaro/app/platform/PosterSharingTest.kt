package `in`.wishkaro.app.platform

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The FileProvider authority actually resolves (FR-POS-01, FR-WSH-05).
 *
 * ## Why this test exists
 *
 * There is **no compile-time link** between the authority string in Kotlin and
 * `android:authorities` in the manifest. A mismatch throws `IllegalArgumentException` at the
 * moment the user taps Share and nowhere earlier — and `shareImage` catches it and returns
 * false, which the screen reports as "couldn't open that app". So the failure is silent,
 * indistinguishable from WhatsApp not being installed, and invisible to every other suite.
 *
 * **It shipped on this branch.** The string was written with an over-escaped interpolation
 * and became a LITERAL `${'$'}{appContext.packageName}.posters`. Every share on the handset
 * failed with "Couldn't find meta-data for provider with authority …". Found by tapping
 * Share on a device and reading logcat, not by any test. `M6_VERIFY.md` §0.4.
 *
 * Instrumented rather than a JVM test because the thing under test IS the manifest: a unit
 * test has no merged manifest and no `ContentProvider` registry, so it could only assert
 * that two strings this file wrote match each other — the tautology that let the bug
 * through.
 */
class PosterSharingTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** A tiny PNG header, generated here — never a real poster, which carries a real face. */
    private fun pngBytes(): ByteArray =
        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(64)

    @Test
    fun theAuthorityResolvesToADeclaredProvider() {
        val authority = posterAuthority(context)
        assertEquals("${context.packageName}.posters", authority)

        // The real assertion: the system can resolve it. This is what failed on the device.
        val info = context.packageManager.resolveContentProvider(authority, 0)
        assertNotNull(
            info,
            "No provider declared for authority '$authority' — the Kotlin string and " +
                "android:authorities have drifted",
        )
        assertTrue(
            info.grantUriPermissions,
            "The provider must grant URI permissions, or the receiving app cannot read it",
        )
        assertTrue(
            !info.exported,
            "The provider must NOT be exported — the per-intent grant is the access path",
        )
    }

    @Test
    fun stagingAPosterReturnsAReadableContentUri() {
        val uri = stagePosterForSharing(context, pngBytes())
        assertNotNull(uri, "Staging returned null — the share reports 'could not open that app'")
        assertEquals("content", uri.scheme)
        assertEquals(posterAuthority(context), uri.authority)

        // Readable through the resolver, which is what the receiving app does.
        val read = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        assertNotNull(read)
        assertEquals(pngBytes().size, read.size)
    }

    @Test
    fun theShareIntentResolvesToSomethingOnThisDevice() {
        // A well-formed intent that resolves to nothing is a Share button that does
        // nothing. Asserted against the real package manager on the real handset.
        val uri = stagePosterForSharing(context, pngBytes())!!
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            putExtra(android.content.Intent.EXTRA_TEXT, "Happy Birthday")
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val targets = context.packageManager.queryIntentActivities(intent, 0)
        assertTrue(
            targets.isNotEmpty(),
            "No activity can receive an image/png ACTION_SEND — the share sheet would be empty",
        )
    }

    /**
     * The real sender, opening a real chooser, on a real device.
     *
     * **What this does and does not prove.** It drives
     * `AndroidMessageSender.shareImage` — the production path, not a re-statement of it —
     * and asserts it reports success, which it can only do once the FileProvider resolved,
     * the bytes were staged and `startActivity` was accepted. What it does NOT cover is the
     * poster studio's button wiring; `PosterPickerFilterTest` covers that the button exists
     * and `M6_VERIFY.md` §2 says which half is which rather than implying one picture
     * showed both.
     */
    @Test
    fun theRealSenderOpensAChooser() {
        val sender = AndroidMessageSender(context)
        val opened = sender.shareImage(pngBytes(), "image/png", "Happy Birthday, Asha Kulkarni")
        assertTrue(opened, "shareImage reported failure — the screen would say 'couldn't open that app'")
    }

    @Test
    fun stagingOverwritesRatherThanAccumulating() {
        // One fixed filename. The alternative fills the cache with every poster a user has
        // ever previewed, and nothing deletes them.
        stagePosterForSharing(context, pngBytes())
        stagePosterForSharing(context, pngBytes())

        val dir = java.io.File(context.cacheDir, "posters")
        assertEquals(1, dir.listFiles()?.size)
    }
}
