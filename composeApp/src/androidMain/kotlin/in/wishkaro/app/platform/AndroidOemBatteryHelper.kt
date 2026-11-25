package `in`.wishkaro.app.platform

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

/**
 * Deep-links into the vendor's own autostart / background-app screen (FR-REM-06).
 *
 * Every intent in [VENDOR_INTENTS] is undocumented, vendor-private, and liable to vanish in
 * the next OS update — which is not a reason to avoid them (they are the only way to reach
 * these screens) but it is the reason every single one is tried and discarded rather than
 * trusted. The chain is:
 *
 *   1. the vendor's own screen, each candidate checked with `resolveActivity` first;
 *   2. Android's generic battery-optimisation screen;
 *   3. nothing, and the UI falls back to written instructions.
 *
 * A crash here would be a particularly bad one — the user tapped a button called "Make
 * reminders reliable" — so every launch is wrapped, not just guarded. `resolveActivity` can
 * succeed and `startActivity` still throw, because several of these activities are exported
 * in the manifest and then refuse the caller at runtime.
 */
class AndroidOemBatteryHelper(private val context: Context) : OemBatteryHelper {

    override fun vendor(): OemVendor {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val brand = Build.BRAND.lowercase()
        val both = "$manufacturer $brand"
        return when {
            // Redmi and POCO are Xiaomi, and both report their own brand. Matching on the
            // manufacturer alone misses most of the phones this feature exists for.
            listOf("xiaomi", "redmi", "poco").any { it in both } -> OemVendor.XIAOMI
            "realme" in both -> OemVendor.REALME
            listOf("oppo", "oneplus").any { it in both } -> OemVendor.OPPO
            "vivo" in both || "iqoo" in both -> OemVendor.VIVO
            "samsung" in both -> OemVendor.SAMSUNG
            else -> OemVendor.OTHER
        }
    }

    override fun isIgnoringBatteryOptimisations(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val power = context.getSystemService(PowerManager::class.java) ?: return false
        return power.isIgnoringBatteryOptimizations(context.packageName)
    }

    override fun openSettings(): OemHelperOutcome {
        for (component in VENDOR_INTENTS[vendor()].orEmpty()) {
            if (launch(Intent().setComponent(component))) return OemHelperOutcome.VENDOR_SCREEN
        }

        // Android's own screen. Deliberately ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS —
        // the list — rather than ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, which pops a
        // system dialog and is a Play policy violation for an app that is not an alarm clock.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (launch(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))) {
                return OemHelperOutcome.GENERIC_SCREEN
            }
        }

        // Last resort: this app's own settings page, which exists on every Android build.
        val appSettings = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.fromParts("package", context.packageName, null))
        if (launch(appSettings)) return OemHelperOutcome.GENERIC_SCREEN

        return OemHelperOutcome.NOTHING
    }

    private fun launch(intent: Intent): Boolean {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(context.packageManager) == null) return false
        return runCatching { context.startActivity(intent) }
            .onFailure { Log.w(TAG, "Could not open ${intent.component ?: intent.action}", it) }
            .isSuccess
    }

    private companion object {
        const val TAG = "WishKaroOem"

        /**
         * Candidate screens per vendor, best first.
         *
         * Several entries per vendor on purpose: the component names move between OS versions
         * (MIUI 11 to 12 to 14, ColorOS 7 to 13), so a single hard-coded name works on one
         * generation of handset and silently fails on the rest. The list is walked until one
         * resolves.
         */
        val VENDOR_INTENTS: Map<OemVendor, List<ComponentName>> = mapOf(
            OemVendor.XIAOMI to listOf(
                ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity",
                ),
                ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.powercenter.PowerSettings",
                ),
            ),
            OemVendor.OPPO to listOf(
                ComponentName(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.permission.startup.StartupAppListActivity",
                ),
                ComponentName(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.startupapp.StartupAppListActivity",
                ),
                ComponentName(
                    "com.oppo.safe",
                    "com.oppo.safe.permission.startup.StartupAppListActivity",
                ),
            ),
            OemVendor.REALME to listOf(
                ComponentName(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.permission.startup.StartupAppListActivity",
                ),
                ComponentName(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.startupapp.StartupAppListActivity",
                ),
            ),
            OemVendor.VIVO to listOf(
                ComponentName(
                    "com.vivo.permissionmanager",
                    "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
                ),
                ComponentName(
                    "com.iqoo.secure",
                    "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager",
                ),
                ComponentName(
                    "com.iqoo.secure",
                    "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
                ),
            ),
            OemVendor.SAMSUNG to listOf(
                ComponentName(
                    "com.samsung.android.lool",
                    "com.samsung.android.sm.ui.battery.BatteryActivity",
                ),
                ComponentName(
                    "com.samsung.android.lool",
                    "com.samsung.android.sm.battery.ui.BatteryActivity",
                ),
            ),
        )
    }
}
