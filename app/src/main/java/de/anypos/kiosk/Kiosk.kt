package de.anypos.kiosk

import android.app.Activity
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import de.anypos.ui.MainActivity

/** Geräteadministrator-Komponente; aktiv erst nach `dpm set-device-owner` (siehe docs/Kioskmodus.md). */
class KioskAdminReceiver : DeviceAdminReceiver()

/**
 * Kioskmodus:
 *  - Geräteeigentümer: Sperrmodus (Lock Task) ohne Rückfrage, App als dauerhafter Startbildschirm,
 *    Statusleiste/Benachrichtigungen gesperrt
 *  - sonst: Bildschirmfixierung (Android fragt einmal nach Bestätigung, lösbar per Geste)
 * In beiden Fällen werden System- und Navigationsleiste ausgeblendet.
 */
object Kiosk {
    fun admin(context: Context) = ComponentName(context, KioskAdminReceiver::class.java)

    fun isDeviceOwner(context: Context): Boolean =
        context.getSystemService(DevicePolicyManager::class.java).isDeviceOwnerApp(context.packageName)

    fun enable(activity: Activity) {
        val dpm = activity.getSystemService(DevicePolicyManager::class.java)
        if (isDeviceOwner(activity)) {
            val admin = admin(activity)
            // SumUp-App darf im gesperrten Modus für Kartenzahlungen geöffnet werden (Payment Switch)
            dpm.setLockTaskPackages(admin, arrayOf(activity.packageName, de.anypos.payment.SumUpSwitch.SUMUP_PACKAGE))
            dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_NONE)
            dpm.addPersistentPreferredActivity(
                admin,
                IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME); addCategory(Intent.CATEGORY_DEFAULT) },
                ComponentName(activity, MainActivity::class.java),
            )
            dpm.setStatusBarDisabled(admin, true)
        }
        runCatching { activity.startLockTask() }
        immersive(activity, true)
    }

    fun disable(activity: Activity) {
        runCatching { activity.stopLockTask() }
        if (isDeviceOwner(activity)) {
            val dpm = activity.getSystemService(DevicePolicyManager::class.java)
            val admin = admin(activity)
            dpm.clearPackagePersistentPreferredActivities(admin, activity.packageName)
            dpm.setStatusBarDisabled(admin, false)
        }
        immersive(activity, false)
    }

    private fun immersive(activity: Activity, on: Boolean) {
        val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        if (on) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}
