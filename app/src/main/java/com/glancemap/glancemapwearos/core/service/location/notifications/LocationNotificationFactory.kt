package com.glancemap.glancemapwearos.core.service.location.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.wear.ongoing.OngoingActivity
import com.glancemap.glancemapwearos.R
import com.glancemap.glancemapwearos.core.service.location.policy.NavigationRuntimeDemandReason
import com.glancemap.glancemapwearos.presentation.MainActivity
import com.glancemap.glancemapwearos.presentation.navigation.WatchRoutes

internal class LocationNotificationFactory(
    private val service: Service,
    private val channelId: String,
) {
    fun buildNotification(
        isForegroundPinned: Boolean,
        notificationId: Int,
        runtimeReason: String,
    ): Notification {
        ensureNotificationChannel()

        val pendingIntent =
            PendingIntent.getActivity(
                service,
                0,
                Intent(service, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra("route", WatchRoutes.NAVIGATE)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        val builder =
            NotificationCompat
                .Builder(service, channelId)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setContentIntent(pendingIntent)
                .setOnlyAlertOnce(true)

        if (isForegroundPinned) {
            val guidanceActive =
                runtimeReason == NavigationRuntimeDemandReason.GUIDANCE_VISIBLE ||
                    runtimeReason == NavigationRuntimeDemandReason.GUIDANCE_AMBIENT ||
                    runtimeReason == NavigationRuntimeDemandReason.GUIDANCE_BACKGROUND ||
                    runtimeReason == NavigationRuntimeDemandReason.RECORDING_GUIDANCE
            builder
                .setContentTitle(
                    if (guidanceActive) {
                        service.getString(
                            R.string.navigation_turn_by_turn_guidance,
                        )
                    } else {
                        service.getString(R.string.navigation_app_kept_open)
                    },
                ).setContentText(
                    if (guidanceActive) {
                        service.getString(R.string.navigation_guidance_active_tap_to_return)
                    } else {
                        service.getString(R.string.navigation_tap_to_return_to_navigation)
                    },
                ).setCategory(NotificationCompat.CATEGORY_NAVIGATION)
                .setOngoing(true)

            val ongoingActivity =
                OngoingActivity
                    .Builder(service.applicationContext, notificationId, builder)
                    .setStaticIcon(R.drawable.ic_launcher_foreground)
                    .setTouchIntent(pendingIntent)
                    .build()

            ongoingActivity.apply(service.applicationContext)
        } else {
            builder
                .setContentTitle(service.getString(R.string.gpx_location_service))
                .setContentText(service.getString(R.string.navigation_ready))
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setOngoing(false)
        }

        return builder.build()
    }

    fun show(
        notificationId: Int,
        notification: Notification,
    ) {
        runCatching {
            val manager = service.getSystemService(NotificationManager::class.java)
            manager.notify(notificationId, notification)
        }
    }

    fun cancel(notificationId: Int) {
        runCatching {
            val manager = service.getSystemService(NotificationManager::class.java)
            manager.cancel(notificationId)
        }
    }

    private fun ensureNotificationChannel() {
        val manager = service.getSystemService(NotificationManager::class.java)
        val channel =
            NotificationChannel(
                channelId,
                service.resources.getString(R.string.gpx_location_service),
                NotificationManager.IMPORTANCE_LOW,
            )
        manager.createNotificationChannel(channel)
    }
}
