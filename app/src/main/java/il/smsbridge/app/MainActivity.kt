package il.smsbridge.app

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.telephony.SubscriptionManager
import android.view.Gravity
import android.view.View
import android.widget.*
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import org.json.JSONObject
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var base: EditText
    private lateinit var code: EditText
    private lateinit var simPicker: Spinner
    private lateinit var toggle: Switch
    private var sims: List<Int> = emptyList()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(34, 40, 34, 30); layoutDirection = View.LAYOUT_DIRECTION_RTL }
        fun title(s: String, size: Float = 18f) = TextView(this).apply { text=s; textSize=size; setPadding(0,12,0,12) }
        root.addView(title("SMS Bridge", 27f))
        root.addView(title("חיבור הטלפון לשליחה אוטומטית — גרסת ניסוי", 15f))
        status=title("לא מחובר")
        root.addView(status)
        base=EditText(this).apply { hint="https://staging-worker.example.workers.dev"; setSingleLine(); setText(BridgeStore.base(this@MainActivity)); textDirection=View.TEXT_DIRECTION_LTR }
        root.addView(base)
        code=EditText(this).apply { hint="קוד חיבור חד־פעמי"; setSingleLine() }
        root.addView(code)
        root.addView(Button(this).apply { text="חבר מכשיר"; setOnClickListener { pair() } })
        root.addView(title("כרטיס SIM לשליחה"))
        simPicker=Spinner(this)
        root.addView(simPicker)
        root.addView(Button(this).apply { text="רענן כרטיסי SIM"; setOnClickListener { loadSims() } })
        root.addView(Button(this).apply { text="אשר הרשאת SMS"; setOnClickListener { requestSms() } })
        toggle=Switch(this).apply { text="אפשר שליחה אוטומטית"; isChecked=BridgeStore.enabled(this@MainActivity); setOnCheckedChangeListener { _, enabled ->
            if (enabled && (BridgeStore.token(this@MainActivity).isBlank() || checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED || BridgeStore.sim(this@MainActivity) == SubscriptionManager.INVALID_SUBSCRIPTION_ID)) {
                isChecked=false; show("נדרש חיבור, SIM והרשאת SMS לפני הפעלה")
            } else { BridgeStore.setEnabled(this@MainActivity,enabled); BridgeWorker.enqueue(this@MainActivity); show(if (enabled) "שליחה אוטומטית מופעלת" else "שליחה אוטומטית כבויה") }
        } }
        root.addView(toggle)
        root.addView(Button(this).apply { text="בדוק חיבור ומשימות"; setOnClickListener { BridgeWorker.enqueue(this@MainActivity); show("בוצעה בקשה לסנכרון") } })
        root.addView(Button(this).apply { text="נתק מכשיר מקומית"; setOnClickListener { toggle.isChecked=false; BridgeStore.clear(this@MainActivity); show("הפרטים המקומיים נמחקו. לביטול הרשאה בצד השרת פנה למנהל.") } })
        root.addView(title("במקרה של חוסר ודאות לגבי שליחה, המערכת אינה שולחת SMS גיבוי אוטומטי. מסירה בפועל לנמען אינה מובטחת.",14f))
        setContentView(ScrollView(this).apply { addView(root) })
        loadSims()
        BridgeWorker.schedule(this)
        if (BridgeStore.sender(this).isNotBlank()) show("מחובר: ${BridgeStore.sender(this)}")
    }
    private fun show(s:String) { runOnUiThread { status.text=s } }
    private fun requestSms() { if(checkSelfPermission(Manifest.permission.SEND_SMS)!=PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.SEND_SMS),1) else show("הרשאת SMS מאושרת") }
    private fun loadSims() {
        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.READ_PHONE_STATE), 2)
            return
        }
        val mgr=getSystemService(SubscriptionManager::class.java)
        val available=try { mgr.activeSubscriptionInfoList ?: emptyList() } catch (_:SecurityException) { emptyList() }
        sims=available.map { it.subscriptionId }
        val labels=available.map { "SIM ${it.simSlotIndex+1} — ${it.carrierName}" }
        simPicker.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item, if(labels.isEmpty()) listOf("אין SIM זמין") else labels)
        val existing=sims.indexOf(BridgeStore.sim(this)); if(existing>=0) simPicker.setSelection(existing)
        simPicker.onItemSelectedListener=object:android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent:android.widget.AdapterView<*>?) {}
            override fun onItemSelected(parent:android.widget.AdapterView<*>?,view:View?,position:Int,id:Long) {
                if(position in sims.indices) BridgeStore.setSim(this@MainActivity,sims[position])
            }
        }
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 2 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) loadSims()
    }
    private fun pair() {
        val url=base.text.toString().trimEnd('/')
        val pairingCode=code.text.toString().trim()
        if (!url.startsWith("https://") || pairingCode.length<6) { show("הזן כתובת HTTPS וקוד חיבור תקין");return }
        show("מחבר...")
        thread {
            try {
                val response=BridgeApi.post(url,"/device/pair",JSONObject().put("code",pairingCode))
                val token=response.getString("token")
                val sender=response.getString("sender")
                BridgeStore.savePair(this,url,token,sender)
                show("מחובר: $sender (שליחה כבויה עד אישור)")
                if(FirebaseApp.getApps(this).isNotEmpty()) FirebaseMessaging.getInstance().token.addOnSuccessListener { t ->
                    BridgeStore.setFcmToken(this,t);BridgeWorker.enqueue(this)
                }
                BridgeWorker.enqueue(this)
            } catch(e:Exception) {show("החיבור נכשל: ${e.message}")}
        }
    }
}
