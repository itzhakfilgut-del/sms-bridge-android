package il.smsbridge.app

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
        if (message.data["type"] == "sms_job") BridgeWorker.enqueue(this)
    }
}

class BridgeWorker(ctx: android.content.Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result = try {
        BridgeProcessor.sync(applicationContext)
        Result.success()
    } catch (e: Exception) { Result.retry() }

    companion object {
        fun enqueue(ctx: android.content.Context) {
            WorkManager.getInstance(ctx).enqueueUniqueWork("bridge-sync", ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<BridgeWorker>().build())
        }
        fun schedule(ctx: android.content.Context) {
            val req = PeriodicWorkRequestBuilder<BridgeWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("bridge-periodic", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
