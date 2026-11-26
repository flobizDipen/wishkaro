package `in`.wishkaro.app.platform

/**
 * iOS is compile-only in v1 (D15).
 *
 * Returning false everywhere is the honest stub: the composer already handles "that target
 * could not be opened" by telling the user, because Android has the same case when WhatsApp
 * is not installed. So the iOS build compiles and the screen behaves sensibly rather than
 * silently appearing to work.
 */
class IosMessageSender : MessageSender {
    override fun openWhatsApp(phone: String?, text: String): Boolean = false

    override fun openSms(phone: String?, text: String): Boolean = false

    override fun share(text: String): Boolean = false

    override fun copyToClipboard(text: String): Boolean = false
}
