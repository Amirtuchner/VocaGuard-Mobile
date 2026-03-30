package com.example.vocaguard.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.RemoteViews
import com.example.vocaguard.MainActivity
import com.example.vocaguard.R
import com.example.vocaguard.ui.PermissionsManager

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

            val views = RemoteViews(context.packageName, R.layout.widget_vocaguard)

            views.setTextViewText(
                R.id.widget_status,
                if (allGranted) context.getString(R.string.widget_status_protected)
                else context.getString(R.string.widget_status_setup_required)
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
