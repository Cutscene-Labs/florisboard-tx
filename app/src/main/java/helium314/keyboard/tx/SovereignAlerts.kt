// SPDX-License-Identifier: GPL-3.0-only
// SovereignBoard: a swipe-away phone alert when the keyboard's AI stops answering (his ask, 19 Nov 2026:
// "ping me when the balance is low somehow? an alert that i can swipe away").
package helium314.keyboard.tx

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import helium314.keyboard.latin.utils.prefs

object SovereignAlerts {
    private const val CHANNEL = "sovereign_alerts"
    private const val ID_NO_CREDIT = 4101
    private const val PREF_LAST_NO_CREDIT = "sovereign_alert_no_credit_at"
    /** Once in this many hours is enough: every dictation would otherwise ring it again. */
    private const val REPEAT_HOURS = 6L
    const val BILLING_URL = "https://console.anthropic.com/settings/billing"

    /** The server said Claude refused for want of credit: tell him once, with a tap to the billing page. */
    fun outOfCredit(context: Context) {
        val app = context.applicationContext
        val p = app.prefs()
        val now = System.currentTimeMillis()
        if (now - p.getLong(PREF_LAST_NO_CREDIT, 0L) < REPEAT_HOURS * 3_600_000L) return
        p.edit { putLong(PREF_LAST_NO_CREDIT, now) }
        val title = "The keyboard's AI is out of credit"
        val body = "Dictation still types what it hears, but nothing gets cleaned up. Tap to top up the Anthropic account."
        if (!canNotify(app)) {
            Handler(Looper.getMainLooper()).post { Toast.makeText(app, "$title. $body", Toast.LENGTH_LONG).show() }
            return
        }
        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "SovereignBoard alerts", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "When the keyboard's AI stops answering"
            }
        )
        val open = PendingIntent.getActivity(
            app, 0,
            Intent(Intent.ACTION_VIEW, Uri.parse(BILLING_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(app, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setAutoCancel(true) // swipe it away, or tap it, and it is gone
            .build()
        nm.notify(ID_NO_CREDIT, n)
    }

    /** Android 13+ needs the notification permission; the SovereignBoard settings screen asks for it. */
    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
}
