package il.smsbridge.app

import android.content.Context
import android.telephony.SubscriptionManager

object BridgeStore {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("bridge", Context.MODE_PRIVATE)
    fun base(ctx: Context) = prefs(ctx).getString("base", "") ?: ""
    fun token(ctx: Context) = prefs(ctx).getString("token", "") ?: ""
    fun sender(ctx: Context) = prefs(ctx).getString("sender", "") ?: ""
    fun enabled(ctx: Context) = prefs(ctx).getBoolean("enabled", false)
    fun sim(ctx: Context) = prefs(ctx).getInt("sim", SubscriptionManager.INVALID_SUBSCRIPTION_ID)
    fun savePair(ctx: Context, base: String, token: String, sender: String) = prefs(ctx).edit()
        .putString("base", base).putString("token", token).putString("sender", sender).putBoolean("enabled", false).apply()
    fun setEnabled(ctx: Context, enabled: Boolean) = prefs(ctx).edit().putBoolean("enabled", enabled).apply()
    fun setSim(ctx: Context, subscriptionId: Int) = prefs(ctx).edit().putInt("sim", subscriptionId).apply()
    fun fcmToken(ctx: Context) = prefs(ctx).getString("fcm", "") ?: ""
    fun setFcmToken(ctx: Context, fcm: String) = prefs(ctx).edit().putString("fcm", fcm).apply()
    fun clear(ctx: Context) = prefs(ctx).edit().clear().apply()
}
