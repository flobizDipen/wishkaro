package `in`.wishkaro.app.platform

/**
 * The OEM battery-optimisation helper (FR-REM-06).
 *
 * REQUIREMENTS.md calls this the single highest-impact reliability feature in the app, and it
 * is the reason M2 is not just a cron job. Xiaomi, Oppo, Vivo, Realme and Samsung all ship
 * their own battery manager on top of Android's, and all of them will force-stop a background
 * app they decide is idle. A force-stopped app receives no FCM pushes and keeps no
 * AlarmManager alarms — so both halves of the reminder engine die at once, silently, and the
 * user simply stops being reminded of birthdays.
 */
interface OemBatteryHelper {

    /** The manufacturer this device appears to be, or [OemVendor.OTHER]. */
    fun vendor(): OemVendor

    /** True when the OS says this app is already exempt from battery optimisation. */
    fun isIgnoringBatteryOptimisations(): Boolean

    /**
     * Opens the vendor's own autostart or battery screen, falling back to Android's generic
     * one.
     *
     * Returns what it managed to open, because the instructions on screen have to match where
     * the user has actually landed — telling someone to look for "Autostart" on a screen that
     * says "Battery optimisation" is how a helper becomes noise.
     */
    fun openSettings(): OemHelperOutcome
}

enum class OemVendor(val label: String) {
    XIAOMI("Xiaomi / Redmi / POCO"),
    OPPO("Oppo"),
    VIVO("Vivo"),
    REALME("Realme"),
    SAMSUNG("Samsung"),
    OTHER("your phone"),
}

enum class OemHelperOutcome {
    /** The vendor's own autostart / background-app screen opened. */
    VENDOR_SCREEN,

    /** The vendor intent did not resolve; Android's battery-optimisation screen opened. */
    GENERIC_SCREEN,

    /** Nothing opened. Instructions are all that is left. */
    NOTHING,
}

/**
 * The steps to show for each vendor.
 *
 * Written for someone who does not know what "autostart" means, because that is who this is
 * for. Kept in common code so they are one list rather than scattered through the UI, and so
 * the iOS target compiles without pretending any of it applies.
 */
object OemInstructions {
    fun forVendor(vendor: OemVendor, outcome: OemHelperOutcome): List<String> =
        when {
            outcome == OemHelperOutcome.NOTHING -> listOf(
                "Open your phone's Settings, find WishKaro in the app list, and allow it to " +
                    "run in the background.",
            )
            outcome == OemHelperOutcome.GENERIC_SCREEN -> listOf(
                "Find WishKaro in the list.",
                "Choose \"Don't optimise\" or \"Unrestricted\".",
            )
            vendor == OemVendor.XIAOMI -> listOf(
                "Turn on \"Autostart\" for WishKaro.",
                "Then go back, open Battery saver for WishKaro and choose \"No restrictions\".",
                "In Recents, pull WishKaro down and tap the padlock so clearing apps does not " +
                    "close it.",
            )
            vendor == OemVendor.OPPO || vendor == OemVendor.REALME -> listOf(
                "Turn on \"Allow Auto Startup\" for WishKaro.",
                "Then open Battery for WishKaro and choose \"Allow background running\".",
            )
            vendor == OemVendor.VIVO -> listOf(
                "Turn on \"Auto Start\" for WishKaro.",
                "Then open Battery and set WishKaro to \"High background power consumption\".",
            )
            vendor == OemVendor.SAMSUNG -> listOf(
                "Find WishKaro in the list and turn OFF \"Put app to sleep\".",
                "Make sure WishKaro is not in \"Deep sleeping apps\".",
            )
            else -> listOf(
                "Find WishKaro in the list.",
                "Allow it to run in the background without restrictions.",
            )
        }
}

/** iOS has no vendor battery managers to fight. Keeps the target compiling honestly. */
class NoopOemBatteryHelper : OemBatteryHelper {
    override fun vendor(): OemVendor = OemVendor.OTHER
    override fun isIgnoringBatteryOptimisations(): Boolean = true
    override fun openSettings(): OemHelperOutcome = OemHelperOutcome.NOTHING
}
