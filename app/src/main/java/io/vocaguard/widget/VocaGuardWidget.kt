package io.vocaguard.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.RemoteViews
import io.vocaguard.MainActivity
import io.vocaguard.R
import io.vocaguard.data.TranscriptRepository
import io.vocaguard.ui.PermissionsManager
import kotlinx.coroutines.runBlocking
import java.util.Calendar

/**
 * Home screen widget that shows "Protected" when all required permissions are granted,
 * or "Setup Required" when one or more permissions are missing.
 *
 * The widget refreshes every 30 minutes via [widget_info.xml] (updatePeriodMillis)
 * and immediately whenever it is added or the app updates it.
 */
class VocaGuardWidget : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (widgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, widgetId)
        }
    }

    companion object {
        fun updateWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            widgetId: Int
        ) {
            val allGranted = PermissionsManager.REQUIRED_PERMISSIONS.all { perm ->
                context.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED
            }

            // Count today's scam calls for the subtitle (blocking is acceptable inside a
            // BroadcastReceiver — the system allows up to 10 s for this to complete).
            val todayScams = runBlocking {
                val midnightMs = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis
                TranscriptRepository.getInstance(context).countScamsSince(midnightMs)
            }

            val views = RemoteViews(context.packageName, R.layout.widget_vocaguard)

            views.setTextViewText(
                R.id.widget_status,
                if (allGranted) context.getString(R.string.widget_status_protected)
                else context.getString(R.string.widget_status_setup_required)
            )

            views.setTextViewText(
                R.id.widget_subtitle,
                if (todayScams > 0) "Blocked $todayScams scam${if (todayScams == 1) "" else "s"} today"
                else "VocaGuard"
            )

            // Tapping the widget opens the app
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            val pendingIntent = PendingIntent.getActivity(
                context, 0, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            views.setOnClickPendingIntent(R.id.widget_status, pendingIntent)

            appWidgetManager.updateAppWidget(widgetId, views)
        }
    }
}
