package il.smsbridge.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.Build
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import androidx.work.*
import java.util.concurrent.TimeUnit

class BridgeMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        BridgeStore.setFcmToken(this, token)
        BridgeWorker.enqueue(this)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        if (message.data["type"] == "sms_job") {
            showJobNotification()
            BridgeWorker.enqueue(this)
        }
    }

    // High-priority FCM data messages should produce a user-visible notification.
    private fun showJobNotification() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = getSystemService(NotificationManager::class.java)
        val channelId = "sms-bridge-jobs"
        manager.createNotificationChannel(NotificationChannel(
            channelId, "הודעות SMS ממתינות", NotificationManager.IMPORTANCE_LOW))
        val notification = Notification.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle("SMS Bridge")
            .setContentText("התקבלה בקשה לשליחת SMS")
            .setAutoCancel(true)
            .build()
        manager.notify(101, notification)
    }
}

class BridgeWorker(ctx: android.content.Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result = try {
        if (BridgeProcessor.sync(applicationContext)) Result.success() else Result.retry()
    } catch (_: Exception) {
        Result.retry()
    }

    companion object {
        fun enqueue(ctx: android.content.Context) {
            val connected = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED).build()
            val req = OneTimeWorkRequestBuilder<BridgeWorker>()
                .setConstraints(connected)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            // Do not use KEEP: a previous retrying job must not block a fresh push.
            // Multiple jobs are safe: processor is synchronized and D1 claim is atomic.
            WorkManager.getInstance(ctx).enqueue(req)
        }

        fun schedule(ctx: android.content.Context) {
            val req = PeriodicWorkRequestBuilder<BridgeWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                "bridge-periodic", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
