package dev.shadowgps.app.nav

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dev.shadowgps.app.MainActivity
import dev.shadowgps.app.R
import kotlinx.coroutines.launch

/**
 * Keeps guidance running when the app is not in the foreground.
 *
 * It does no navigation work itself — it mirrors [NavigationHub] into a notification and
 * holds the process open so location updates keep arriving with the screen off.
 */
class NavigationService : LifecycleService() {

    override fun onCreate() {
        super.onCreate()
        createChannel()

        lifecycleScope.launch {
            NavigationHub.banner.collect { banner ->
                if (banner == null) {
                    stopSelf()
                } else {
                    notificationManager().notify(NOTIFICATION_ID, buildNotification(banner))
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        if (intent?.action == ACTION_STOP) {
            NavigationHub.requestStop()
            NavigationHub.publish(null)
            stopSelf()
            return START_NOT_STICKY
        }

        val banner = NavigationHub.banner.value
            ?: NavigationBanner(getString(R.string.nav_notification_title), "")

        // Android 14 requires the foreground type at the moment the service starts, not
        // only in the manifest. ServiceCompat handles the older levels that ignore it.
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(banner),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
        )

        return START_STICKY
    }

    override fun onDestroy() {
        NavigationHub.publish(null)
        super.onDestroy()
    }

    private fun buildNotification(banner: NavigationBanner): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, NavigationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(banner.instruction)
            .setContentText(banner.detail)
            .setContentIntent(openApp)
            .addAction(0, getString(R.string.nav_stop), stop)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.nav_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.nav_channel_description)
            setShowBadge(false)
        }
        notificationManager().createNotificationChannel(channel)
    }

    private fun notificationManager(): NotificationManager =
        ContextCompat.getSystemService(this, NotificationManager::class.java)!!

    companion object {
        private const val CHANNEL_ID = "navigation"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_STOP = "dev.shadowgps.app.STOP_NAVIGATION"

        fun start(context: Context) {
            val intent = Intent(context, NavigationService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            NavigationHub.publish(null)
            context.stopService(Intent(context, NavigationService::class.java))
        }
    }
}
