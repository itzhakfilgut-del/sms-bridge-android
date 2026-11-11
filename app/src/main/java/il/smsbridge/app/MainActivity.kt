package il.smsbridge.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.telephony.SubscriptionManager
import android.view.Gravity
import android.view.View
import android.widget.*
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import kotlin.concurrent.thread
import org.json.JSONObject

/** Simple first-run setup. No server URL or manual SIM selection in the UI. */
class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var details: TextView
    private lateinit var senderInput: EditText
    private lateinit var connectButton: Button
    private var connecting = false

    private val ink = Color.rgb(19, 36, 57)
    private val muted = Color.rgb(101, 117, 137)
    private val accent = Color.rgb(29, 115, 108)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render()
        BridgeWorker.schedule(this)
        requestSmsIfNeeded()
        refresh()
        if (BridgeStore.token(this).isNotBlank()) syncFirebaseAndJobs()
    }

    private fun pill(bg: Int, radius: Float = 24f) = GradientDrawable().apply {
        setColor(bg)
        cornerRadius = radius * resources.displayMetrics.density
    }
    private fun text(value: String, size: Float, color: Int = ink, bold: Boolean = false) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            gravity = Gravity.RIGHT
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun render() {
        val bg = Color.rgb(246, 248, 250)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(22), dp(32), dp(22), dp(24))
            setBackgroundColor(bg)
        }
        val top = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
        }
        val heading = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        heading.addView(text("SMS Bridge", 29f, ink, true))
        heading.addView(text("הטלפון שלך מחובר. ההודעות בדרך.", 13f, muted))
        top.addView(heading, LinearLayout.LayoutParams(0, -2, 1f))
        val settings = TextView(this).apply {
            text = "⚙"
            textSize = 24f
            gravity = Gravity.CENTER
            contentDescription = "הגדרות"
            background = pill(Color.WHITE)
            setOnClickListener { showSettings() }
        }
        top.addView(settings, LinearLayout.LayoutParams(dp(48), dp(48)))
        root.addView(top)

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = pill(Color.WHITE, 22f)
            setPadding(dp(22), dp(28), dp(22), dp(28))
        }
        val cardLp = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(26) }
        status = text("בודק חיבור...", 23f, ink, true)
        details = text("", 14f, muted)
        val statusLp = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) }
        card.addView(status, statusLp)
        card.addView(details)
        root.addView(card, cardLp)

        val setup = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        setup.addView(text("חיבור ראשוני", 18f, ink, true))
        val prompt = text("מזינים את מספר ה־WhatsApp שממנו שולחים הודעות בקבוצה. עושים זאת פעם אחת בלבד.", 14f, muted)
        setup.addView(prompt, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        senderInput = EditText(this).apply {
            hint = "מספר WhatsApp, לדוגמה 0501234567"
            inputType = android.text.InputType.TYPE_CLASS_PHONE
            textDirection = View.TEXT_DIRECTION_LTR
            setSingleLine(true)
            setPadding(dp(14), dp(16), dp(14), dp(16))
            background = pill(Color.WHITE, 14f)
        }
        setup.addView(senderInput, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) })
        connectButton = Button(this).apply {
            text = "חבר את הטלפון"
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = pill(accent, 14f)
            setOnClickListener { register() }
        }
        setup.addView(connectButton, LinearLayout.LayoutParams(-1, dp(54)).apply { topMargin = dp(14) })
        setup.tag = "setup"
        root.addView(setup, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(26) })
        root.addView(text("שליחה אוטומטית מופעלת לאחר החיבור ואישור ההרשאה. אין צורך לפתוח את האפליקציה לכל הודעה.", 13f, muted),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(24) })
        setContentView(ScrollView(this).apply { fillViewport = true; addView(root) })
        refresh()
    }

    private fun refresh() {
        if (!::status.isInitialized) return
        val linked = BridgeStore.token(this).isNotBlank()
        val permitted = checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
        val hasSim = SubscriptionManager.getDefaultSmsSubscriptionId() != SubscriptionManager.INVALID_SUBSCRIPTION_ID
        if (linked && permitted && hasSim) {
            BridgeStore.setEnabled(this, true)
            status.text = "● מוכן לשליחת SMS"
            status.setTextColor(accent)
            details.text = "מחובר למספר ${BridgeStore.sender(this)}\nשליחה אוטומטית פעילה"
        } else if (linked) {
            BridgeStore.setEnabled(this, false)
            status.text = "נדרשת השלמת הגדרה"
            status.setTextColor(ink)
            details.text = if (!permitted) "נדרשת הרשאת SMS" else "צריך להגדיר SIM ברירת מחדל להודעות במכשיר"
        } else {
            status.text = "הטלפון עדיין לא מחובר"
            status.setTextColor(ink)
            details.text = "החיבור הראשוני קצר ופשוט"
        }
        val container = (findViewById<View>(android.R.id.content) as? android.view.ViewGroup)
        val setup = findByTag(container, "setup")
        setup?.visibility = if (linked) View.GONE else View.VISIBLE
    }

    private fun findByTag(group: android.view.ViewGroup?, tag: String): View? {
        if (group == null) return null
        for (i in 0 until group.childCount) {
            val v = group.getChildAt(i)
            if (v.tag == tag) return v
            if (v is android.view.ViewGroup) findByTag(v, tag)?.let { return it }
        }
        return null
    }

    private fun requestSmsIfNeeded() {
        if (checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            AlertDialog.Builder(this).setTitle("הרשאת שליחת הודעות")
                .setMessage("כדי שהמערכת תוכל לשלוח מסרונים מהטלפון, Android יבקש ממך לאשר הרשאת SMS פעם אחת.")
                .setPositiveButton("המשך") { _, _ -> requestPermissions(arrayOf(Manifest.permission.SEND_SMS), 100) }
                .setNegativeButton("לא עכשיו", null).show()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) {
            refresh()
            if (BridgeStore.enabled(this)) syncFirebaseAndJobs()
        }
    }

    private fun normalizedSender(raw: String): String? {
        val digits = raw.filter { it.isDigit() }
        val n = when {
            digits.startsWith("9720") -> "972" + digits.drop(4)
            digits.startsWith("0") -> "972" + digits.drop(1)
            else -> digits
        }
        return if (n.matches(Regex("972[0-9]{8,10}"))) n else null
    }

    private fun register() {
        if (connecting) return
        val sender = normalizedSender(senderInput.text.toString())
        if (sender == null) {
            Toast.makeText(this, "יש להזין מספר טלפון ישראלי תקין", Toast.LENGTH_LONG).show()
            return
        }
        val base = BuildConfig.BRIDGE_SERVER_URL.trimEnd('/')
        if (!base.startsWith("https://") || base.contains("YOUR_") || base.contains("example")) {
            status.text = "כתובת שרת הניסוי טרם הוגדרה בבנייה"
            return
        }
        connecting = true
        connectButton.isEnabled = false
        status.text = "מחבר את הטלפון..."
        thread {
            try {
                val data = BridgeApi.post(base, "/device/register", JSONObject().put("sender", sender))
                BridgeStore.savePair(this, base, data.getString("token"), data.getString("sender"))
                runOnUiThread {
                    connecting = false
                    connectButton.isEnabled = true
                    refresh()
                    if (checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) requestSmsIfNeeded()
                }
                syncFirebaseAndJobs()
            } catch (e: Exception) {
                runOnUiThread {
                    connecting = false
                    connectButton.isEnabled = true
                    status.text = "לא הצלחנו להתחבר לשרת"
                    details.text = "בדוק את החיבור לאינטרנט וההגדרות"
                }
            }
        }
    }

    private fun syncFirebaseAndJobs() {
        if (FirebaseApp.getApps(this).isNotEmpty()) {
            FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
                BridgeStore.setFcmToken(this, token)
                BridgeWorker.enqueue(this)
            }
        }
        BridgeWorker.enqueue(this)
    }

    private fun showSettings() {
        val items = arrayOf("בדיקת חיבור", "ניתוק מכשיר")
        AlertDialog.Builder(this).setTitle("הגדרות")
            .setItems(items) { _, which ->
                if (which == 0) { syncFirebaseAndJobs(); Toast.makeText(this, "נשלחה בקשת בדיקת חיבור", Toast.LENGTH_SHORT).show() }
                else AlertDialog.Builder(this).setTitle("ניתוק המכשיר?")
                    .setMessage("השליחה האוטומטית מהמכשיר תופסק. לביטול הרשאה בשרת יש לפנות למנהל.")
                    .setNegativeButton("ביטול", null)
                    .setPositiveButton("נתק") { _, _ ->
                        BridgeStore.setEnabled(this, false)
                        BridgeStore.clear(this)
                        render()
                    }.show()
            }.setNegativeButton("סגור", null).show()
    }
}
