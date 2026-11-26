package `in`.wishkaro.app.platform

/**
 * Hands a prepared message to the user's own messaging app (FR-WSH-05).
 *
 * **Nothing behind this interface sends anything, and that is a correctness requirement
 * rather than an implementation detail.** FR-WSH-06: "Sending is never automatic. The app
 * does not send on the user's behalf; it hands a prepared message to the user's own
 * messaging app for them to press send."
 *
 * So every method here OPENS something with the text already in it. None of them has a
 * delivery result to return, because none of them delivers anything — the boolean says
 * whether the other app could be opened, not whether a message arrived. That is also why
 * FR-WSH-07 has to ask the user afterwards: once the text is handed over, this app has
 * genuinely lost sight of it.
 *
 * The shape is an interface with a class per platform, the same arrangement `FileGateway`
 * and `ContactsGateway` use, rather than `expect`/`actual`. It keeps the iOS target
 * compiling (D15) without putting a stub in common code.
 */
interface MessageSender {
    /**
     * WhatsApp, the primary target (FR-WSH-05).
     *
     * [phone] may be null — plenty of people in this app have a birthday and no number, and
     * that is a first-class case rather than an error. With a number, the chat opens on that
     * person; without one, WhatsApp opens its own contact picker with the text attached.
     * Both are "the user presses send", which is the only behaviour this is allowed to have.
     *
     * Returns false when WhatsApp is not installed, so the screen can say so instead of
     * appearing to do nothing.
     */
    fun openWhatsApp(phone: String?, text: String): Boolean

    /** The SMS app, with the text pre-filled. */
    fun openSms(phone: String?, text: String): Boolean

    /** The system share sheet — "generic share sheet" in FR-WSH-05. */
    fun share(text: String): Boolean

    /** Copy to the clipboard. The one target that opens nothing. */
    fun copyToClipboard(text: String): Boolean
}
