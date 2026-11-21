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
    // Serialize simultaneous FCM/periodic work inside this app process.
    // D1's atomic /device/claim remains the cross-process duplicate barrier.
    @Synchronized
    fun sync(ctx: Context): Boolean {
        if (BridgeStore.token(ctx).isBlank()) return true

        val prefs = ctx.getSharedPreferences("bridge_sync_diag", Context.MODE_PRIVATE)
        var stage = "HEARTBEAT"
        var seen = 0
        var claimed = 0
        var dispatched = 0

        // Preserve a diagnostic locally if the network is down, and replay it when possible.
        fun sendDiag(event: JSONObject) {
            prefs.edit().putString("pending", event.toString()).apply()
            try {
                BridgeApi.authPost(ctx, "/device/diagnostic", event)
                prefs.edit().remove("pending").apply()
            } catch (_: Exception) {
                // Diagnostic delivery must never interrupt SMS delivery.
            }
        }
        fun report(result: String, detail: String = "") {
            sendDiag(JSONObject()
                .put("at", System.currentTimeMillis())
                .put("stage", stage)
                .put("result", result)
                .put("detail", detail.take(220))
                .put("seen", seen)
                .put("claimed", claimed)
                .put("dispatched", dispatched))
        }

        prefs.getString("pending", null)?.let { raw ->
            try {
                BridgeApi.authPost(ctx, "/device/diagnostic", JSONObject(raw))
                prefs.edit().remove("pending").apply()
            } catch (_: Exception) { }
        }

        try {
            // A successful heartbeat proves connectivity, not SMS capability.
            BridgeApi.authPost(ctx, "/device/heartbeat", JSONObject()
                .put("fcmToken", BridgeStore.fcmToken(ctx))
                .put("enabled", BridgeStore.enabled(ctx)))

            stage = "PRECHECK"
            if (!BridgeStore.enabled(ctx)) {
                report("BLOCKED", "App disabled")
                return true
            }
            if (ctx.checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
                report("BLOCKED", "SEND_SMS permission missing")
                return true
            }
            // Do not block on FEATURE_TELEPHONY_MESSAGING: some working SMS devices
            // (including Android 12) do not advertise this PackageManager feature.
            // Permission + default SIM are checked; SmsManager reports actual send failures.
            val subscription = SubscriptionManager.getDefaultSmsSubscriptionId()
            if (subscription == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                report("BLOCKED", "Default SMS SIM missing")
                return true
            }

            stage = "GET_JOBS"
            val jobs = BridgeApi.get(ctx, "/device/jobs").optJSONArray("jobs")
                ?: throw IllegalStateException("jobs array missing")
            seen = jobs.length()
            if (seen == 0) {
                report("OK", "No pending jobs returned")
                return true
            }

            for (i in 0 until jobs.length()) {
                val job = jobs.optJSONObject(i) ?: continue
                val id = job.optString("id")
                val phone = job.optString("phone")
                val message = job.optString("text")
                if (id.isBlank() || !phone.matches(Regex("\\+?[0-9]{9,15}")) || message.isBlank()) {
                    stage = "VALIDATE_JOB"
                    report("ERROR", "Invalid job payload")
                    continue
                }

                stage = "CLAIM_JOB"
                val claim = BridgeApi.authPost(ctx, "/device/claim", JSONObject().put("id", id))
                if (!claim.optBoolean("claimed")) continue
                claimed++

                // Never automatically requeue a claimed job: modem outcome may be uncertain.
                stage = "SEND_SMS"
                try {
                    val sms = if (Build.VERSION.SDK_INT >= 31) {
                        ctx.getSystemService(SmsManager::class.java).createForSubscriptionId(subscription)
                    } else {
                        @Suppress("DEPRECATION")
                        SmsManager.getSmsManagerForSubscriptionId(subscription)
                    }
                    val parts = sms.divideMessage(message)
                    val intents = ArrayList<PendingIntent>()
                    for (part in parts.indices) {
                        val sentIntent = Intent(ctx, SentReceiver::class.java).apply {
                            putExtra("jobId", id)
                            putExtra("part", part)
                            putExtra("total", parts.size)
                            putExtra("token", BridgeStore.token(ctx))
                        }
                        intents.add(PendingIntent.getBroadcast(ctx,
                            (id + ":" + part).hashCode(), sentIntent,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                    }
                    sms.sendMultipartTextMessage(phone, null, parts, intents, null)
                    dispatched++
                    BridgeApi.authPost(ctx, "/device/dispatched", JSONObject().put("id", id))
                } catch (e: Exception) {
                    try {
                        BridgeApi.authPost(ctx, "/device/result", JSONObject()
                            .put("id", id).put("status", "UNKNOWN"))
                    } catch (_: Exception) { }
                    report("ERROR", "SMS attempt uncertain: ${e.javaClass.simpleName}")
                }
            }
            stage = "SYNC"
            report("OK", "Job dispatch attempted; modem result tracked separately")
            return true
        } catch (e: Exception) {
            report("ERROR", "${e.javaClass.simpleName}: ${e.message ?: "no message"}")
            return false
        }
    }
}

class SentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("jobId") ?: return
        val part = intent.getIntExtra("part", -1)
        val total = intent.getIntExtra("total", 0)
        val status = if (resultCode == Activity.RESULT_OK) "MODEM_ACCEPTED" else "FAILED_UNCERTAIN"
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
