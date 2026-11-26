package `in`.wishkaro.app.platform

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast

/**
 * The four send targets on Android (FR-WSH-05).
 *
 * Every one of them starts another application with the text already in it and then stops.
 * There is no path from here to a sent message, which is FR-WSH-06 expressed as code rather
 * than as a promise.
 */
class AndroidMessageSender(private val appContext: Context) : MessageSender {

    /**
     * WhatsApp.
     *
     * `https://wa.me/<number>?text=…` rather than the `whatsapp://send` scheme: wa.me is the
     * documented, stable entry point, it works for WhatsApp Business as well as the ordinary
     * client, and it degrades to the website if neither is installed — which is why the
     * package check below is explicit rather than left to the intent resolving.
     *
     * The number is stripped to digits with the country code kept, because wa.me rejects
     * anything else. An Indian contact stored as "+91 98765 43210", "098765 43210" or
     * "98765-43210" is three spellings of one number, and only the first is in the shape
     * wa.me wants. `91` is assumed for a bare ten-digit number — this is an India-first app
     * (REQUIREMENTS §1) and a ten-digit number with no country code is an Indian mobile.
     */
    override fun openWhatsApp(phone: String?, text: String): Boolean {
        if (!isInstalled(WHATSAPP) && !isInstalled(WHATSAPP_BUSINESS)) return false

        val number = whatsAppNumber(phone)
        val uri = if (number != null) {
            "https://wa.me/$number?text=${Uri.encode(text)}"
        } else {
            // No number on this person. WhatsApp opens its own picker with the text
            // attached, which is a better answer than refusing: "I know their birthday but
            // not their number" is an ordinary state for a record in this app.
            "https://wa.me/?text=${Uri.encode(text)}"
        }

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
            // Explicit, so the link cannot be grabbed by a browser that has registered
            // wa.me — which would show the user a web page instead of their chat.
            setPackage(if (isInstalled(WHATSAPP)) WHATSAPP else WHATSAPP_BUSINESS)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return start(intent, "WhatsApp")
    }

    /**
     * SMS, via `smsto:` with the body pre-filled.
     *
     * `ACTION_SENDTO` rather than `ACTION_SEND`: SENDTO with an `smsto:` URI is the only
     * form that reliably opens the messaging app on the right conversation, and it cannot be
     * picked up by anything that is not a messaging app.
     */
    override fun openSms(phone: String?, text: String): Boolean {
        val target = phone?.filter { it.isDigit() || it == '+' }.orEmpty()
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$target")).apply {
            putExtra("sms_body", text)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return start(intent, "SMS")
    }

    override fun share(text: String): Boolean {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        // The chooser gets the NEW_TASK flag, not the inner intent: flagging the inner one
        // makes the chosen app open behind the chooser on some OEM builds.
        val chooser = Intent.createChooser(send, null).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return start(chooser, "the share sheet")
    }

    /**
     * Copy, and say so.
     *
     * The toast is suppressed on Android 13+, where the system draws its own clipboard
     * confirmation — two overlapping confirmations for one tap is worse than none. The
     * screen also shows its own snackbar, which is what the user actually reads.
     */
    override fun copyToClipboard(text: String): Boolean {
        val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return false
        clipboard.setPrimaryClip(ClipData.newPlainText("WishKaro", text))
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(appContext, "Copied", Toast.LENGTH_SHORT).show()
        }
        return true
    }

    private fun start(intent: Intent, what: String): Boolean =
        try {
            appContext.startActivity(intent)
            true
        } catch (e: android.content.ActivityNotFoundException) {
            // Nothing on this device can take it. The caller shows the user a line about it
            // rather than the tap appearing to do nothing at all.
            Log.w(TAG, "No activity for $what: ${e.message}")
            false
        }

    private fun isInstalled(packageName: String): Boolean =
        try {
            appContext.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
            false
        }

    private companion object {
        const val TAG = "WishKaroSend"
        const val WHATSAPP = "com.whatsapp"
        const val WHATSAPP_BUSINESS = "com.whatsapp.w4b"
    }
}

/**
 * A phone number in the shape `wa.me` accepts: digits only, country code included, no plus.
 *
 * Null when there is nothing usable, which the caller turns into WhatsApp's own picker
 * rather than an error.
 *
 * Internal and pure so it can be tested without a device — the parsing is the part that is
 * actually wrong in the field, and `openWhatsApp` around it is three lines of Intent.
 */
internal fun whatsAppNumber(phone: String?): String? {
    val raw = phone?.trim().orEmpty()
    if (raw.isEmpty()) return null

    val hadPlus = raw.startsWith("+")
    val digits = raw.filter { it.isDigit() }
    if (digits.isEmpty()) return null

    return when {
        // Already international, however it was punctuated.
        hadPlus -> digits
        // 00 is the other way of writing +, common on numbers typed from a landline era.
        raw.startsWith("00") -> digits.removePrefix("00").ifEmpty { return null }
        // A bare Indian mobile. India-first (REQUIREMENTS §1), and a ten-digit number with
        // no country code is one — the alternative is sending the user to a chat with a
        // number that does not exist.
        digits.length == 10 -> "91$digits"
        // 0-prefixed national form, as Indian numbers are often stored by the dialler.
        digits.length == 11 && digits.startsWith("0") -> "91${digits.drop(1)}"
        // Already carries a country code without the plus.
        digits.length in 11..15 -> digits
        // Too short to be anything. Better to open the picker than a wrong chat.
        else -> null
    }
}
