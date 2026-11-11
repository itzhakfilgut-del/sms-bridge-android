package il.smsbridge.app

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import org.json.JSONObject

object BridgeProcessor {
    fun sync(ctx: Context) {
        if (BridgeStore.token(ctx).isBlank()) return
        val fcm = BridgeStore.fcmToken(ctx)
        try { BridgeApi.authPost(ctx, "/device/heartbeat", JSONObject().put("fcmToken", fcm).put("enabled", BridgeStore.enabled(ctx))) }
        catch (_: Exception) { return /* no job sent when heartbeat fails */ }
        if (!BridgeStore.enabled(ctx)) return
        if (ctx.checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) return
        if (!ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_MESSAGING)) return
        val subscription = SubscriptionManager.getDefaultSmsSubscriptionId()
        if (subscription == SubscriptionManager.INVALID_SUBSCRIPTION_ID) return
        val jobs = BridgeApi.get(ctx, "/device/jobs").optJSONArray("jobs") ?: return
        for (i in 0 until jobs.length()) {
            val job = jobs.optJSONObject(i) ?: continue
            val id = job.optString("id")
            if (id.isBlank()) continue
            val phone = job.optString("phone")
            val msg = job.optString("text")
            if (!phone.matches(Regex("\\+?[0-9]{9,15}")) || msg.isBlank()) continue
            // Server atomically leases exactly one recipient at a time before this POST.
            val claim = BridgeApi.authPost(ctx, "/device/claim", JSONObject().put("id", id))
            if (!claim.optBoolean("claimed")) continue
            try {
                val sms = if (Build.VERSION.SDK_INT >= 31) {
                    ctx.getSystemService(SmsManager::class.java).createForSubscriptionId(subscription)
                } else {
                    @Suppress("DEPRECATION")
                    SmsManager.getSmsManagerForSubscriptionId(subscription)
                }
                val parts = sms.divideMessage(msg)
                val intents = ArrayList<PendingIntent>()
                for (part in parts.indices) {
                    val sentIntent = Intent(ctx, SentReceiver::class.java).apply {
                        putExtra("jobId", id)
                        putExtra("part", part)
                        putExtra("total", parts.size)
                        putExtra("token", BridgeStore.token(ctx))
                    }
                    intents.add(PendingIntent.getBroadcast(ctx, (id + ":" + part).hashCode(), sentIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                }
                sms.sendMultipartTextMessage(phone, null, parts, intents, null)
                // Don't report sent here: only SentReceiver receives modem result.
                BridgeApi.authPost(ctx, "/device/dispatched", JSONObject().put("id", id))
            } catch (e: Exception) {
                // A synchronous exception may still be ambiguous. Never auto-fallback here.
                try { BridgeApi.authPost(ctx, "/device/result", JSONObject().put("id", id).put("status", "UNKNOWN")) }
                catch (_: Exception) { }
            }
        }
    }
}

class SentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("jobId") ?: return
        val part = intent.getIntExtra("part", -1)
        val total = intent.getIntExtra("total", 0)
        val status = if (resultCode == Activity.RESULT_OK) "MODEM_ACCEPTED" else "FAILED_UNCERTAIN"
        // Android may terminate the process: keep receiver processing alive until POST finishes.
        val pending = goAsync()
        Thread {
            try {
                BridgeApi.authPost(context, "/device/part-result", JSONObject()
                    .put("id", id).put("part", part).put("total", total).put("status", status))
            } catch (_: Exception) { }
            finally { pending.finish() }
        }.start()
    }
}
