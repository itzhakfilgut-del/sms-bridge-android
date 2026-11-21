package il.smsbridge.app

import android.content.Context
import android.telephony.SubscriptionManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Private app storage; bearer token is encrypted using a non-exportable Android Keystore key. */
object BridgeStore {
    private const val KEY_ALIAS = "smsbridge-token-v1"
    private const val KEY_ENCRYPTED = "token_secure_v1"
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("bridge", Context.MODE_PRIVATE)
    fun base(ctx: Context) = prefs(ctx).getString("base", "") ?: ""
    fun sender(ctx: Context) = prefs(ctx).getString("sender", "") ?: ""
    fun enabled(ctx: Context) = prefs(ctx).getBoolean("enabled", false)
    fun consent(ctx: Context) = prefs(ctx).getBoolean("sms_consent_explicit", false)
    fun setConsent(ctx: Context, agreed: Boolean) = prefs(ctx).edit().putBoolean("sms_consent_explicit", agreed).apply()
    fun sim(ctx: Context) = prefs(ctx).getInt("sim", SubscriptionManager.INVALID_SUBSCRIPTION_ID)
    fun setEnabled(ctx: Context, enabled: Boolean) = prefs(ctx).edit().putBoolean("enabled", enabled).apply()
    fun setSim(ctx: Context, subscriptionId: Int) = prefs(ctx).edit().putInt("sim", subscriptionId).apply()
    fun fcmToken(ctx: Context) = prefs(ctx).getString("fcm", "") ?: ""
    fun setFcmToken(ctx: Context, fcm: String) = prefs(ctx).edit().putString("fcm", fcm).apply()
    fun clear(ctx: Context) = prefs(ctx).edit().clear().apply()

    private fun getKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build())
        return gen.generateKey()
    }
    private fun protect(raw: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getKey())
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val encoded = Base64.encodeToString(cipher.doFinal(raw.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        return "$iv.$encoded"
    }
    private fun reveal(data: String): String {
        val a = data.split('.', limit=2)
        require(a.size == 2)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getKey(), GCMParameterSpec(128, Base64.decode(a[0], Base64.NO_WRAP)))
        return String(cipher.doFinal(Base64.decode(a[1], Base64.NO_WRAP)), Charsets.UTF_8)
    }
    @Synchronized fun token(ctx: Context): String {
        val p = prefs(ctx)
        val secure = p.getString(KEY_ENCRYPTED, null)
        if (secure != null) return try { reveal(secure) } catch (_: Exception) { "" }
        // One-time migration of previously unencrypted tokens. Fail closed if Keystore is unavailable.
        val legacy = p.getString("token", "") ?: ""
        if (legacy.isBlank()) return ""
        return try {
            val encrypted = protect(legacy)
            if (p.edit().putString(KEY_ENCRYPTED, encrypted).remove("token").commit()) legacy else ""
        } catch (_: Exception) { "" }
    }
    @Synchronized fun savePair(ctx: Context, base: String, token: String, sender: String) {
        val encrypted = protect(token)
        check(prefs(ctx).edit().putString("base", base).putString("sender", sender)
            .putString(KEY_ENCRYPTED, encrypted).remove("token")
            .putBoolean("enabled", true).commit()) { "Could not securely save connection" }
    }
}
