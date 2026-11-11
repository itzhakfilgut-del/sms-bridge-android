# SMS Bridge — גרסת פיתוח משולבת

**לא לייצור. אין לשנות או לדרוס את ה-Worker הפעיל `whatsapp-sms-bridge`.**

## מה כלול
- אפליקציית Android ב-Kotlin: חיבור בקוד חד-פעמי, בחירת SIM, הרשאת SEND_SMS, מתג הפעלה, Firebase Cloud Messaging, WorkManager לגיבוי בסנכרון תקופתי (15 דקות), קבלת משימות, claim חד-פעמי, ושליחת multipart עם תוצאה נפרדת לכל חלק.
- Worker נפרד לניסוי (`staging-worker/`) עם D1: רישום מכשירים, קודי חיבור, תור משימות, מניעת חלוקה חוזרת של משימה, דיווח מצב, FCM ונתיבי ניהול. **אין חיבור ל-WhatsApp או ל-SMS4FREE** בחבילה. כך ניתן לבדוק בלי לשנות ייצור.
- GitHub Actions: בניית `app-debug.apk` אוטומטית בענף `android-development` (או ידנית Actions > Build Android APK > Run workflow). אין צורך ב-Android Studio מקומי.

## איך מפיקים APK
1. פתח מאגר GitHub **נפרד ופרטי** (כדי לא להפעיל אוטומטית את ה-Worker הקיים). העלה את תוכן התיקייה לשורש המאגר.
2. צור ענף `android-development` (או הפעל את ה-Workflow ידנית בענף ברירת המחדל).
3. Actions > Build Android APK > Run workflow. לאחר הצלחה, תחת Artifacts מורידים `SMSBridge-debug-apk` ופותחים את ה-ZIP שבתוכו נמצא `app-debug.apk`.
4. ללא Firebase, ניתן לנסות לבנות APK ולחבר שרת, אבל לא יתקבלו התראות FCM; סנכרון תקופתי בלבד. כדי להפעיל FCM: פתח פרויקט Firebase עם Android package `il.smsbridge.app`, הפק `google-services.json`, והכנס את תוכנו ב-base64 לתוך Secret בשם `FIREBASE_JSON_BASE64` של מאגר GitHub הפרטי. אין להעלות מפתחות שירות של Firebase ל-GitHub. שרת הניסוי ידרוש בנפרד את `GOOGLE_SERVICE_ACCOUNT_JSON` כ-Secret.
5. אין כאן APK חתום להפצה ציבורית או מוכן להפקה. ה-Workflow מייצר APK מסוג debug לבדיקה בלבד.

## הפעלת שרת הניסוי (לא במערכת הפעילה)
1. צור Worker חדש בשם `sms-bridge-staging` ומסד D1 חדש `sms-bridge-staging` ב-Cloudflare.
2. הרץ את `staging-worker/schema.sql` על מסד הניסוי, וקבע binding `DB` ב-Worker הנפרד.
3. קבע `ADMIN_TOKEN` כמחרוזת סודית ארוכה ואקראית, ו-`GOOGLE_SERVICE_ACCOUNT_JSON` בתור Secret עם מפתח השירות של Firebase (נדרש רק להתראות מיידיות).
4. פרוס **רק** `staging-worker/index.js` ל-Worker הניסוי.
5. בקש `POST /admin/pair-code` עם `Authorization: Bearer ADMIN_TOKEN` וגוף JSON לדוגמה `{"sender":"972501234567"}`. קבל `code` בן 24 תווי hex התקף לעשר דקות.
6. באפליקציה הזן URL HTTPS של Worker הניסוי ואת הקוד. בחר SIM, אשר הרשאת SMS והפעל את מתג השליחה.
7. רק עם **מספר ניסוי שבבעלותך**, בצע `POST /admin/job` עם אותו header וגוף `{"sender":"972501234567","phone":"972501234567","text":"SMS Bridge test"}`. ה-Worker ינסה לשלוח FCM; האפליקציה תמשוך ותבצע את המשימה.
8. בדוק `GET /admin/jobs` כדי לראות סטטוס. `MODEM_ACCEPTED` אינו הוכחה למסירה לטלפון הנמען.

## מגבלות ונושאים שצריך לבדוק לפני שימוש אמיתי
- **לא בוצעה קומפילציה במכונת Android SDK**, ולא הרצה על מכשיר. הבנייה ב-GitHub Actions היא מבחן הקומפילציה הראשון. ייתכן צורך בתיקונים.
- בחירת SIM תלויה בדגם ובגרסת Android. אפליקציה זו מיועדת לאנדרואיד בלבד; אייפון אינו תומך בשליחה אוטומטית רגילה מה-SIM.
- FCM עשוי להתעכב, ו-WorkManager אינו רציף; לא מובטחת שליחה מיידית בכל מכשיר.
- משימה שנלקחה (`CLAIMED`) אינה נשלחת שוב אוטומטית גם אם המכשיר נפל. כדי להימנע מכפילויות, משימות לא ודאיות דורשות בירור ידני. **אין במסלול זה fallback ל-SMS4FREE**, והוא נשאר ב-Worker הפעיל עד שנאשר אינטגרציה אחרת.
- אין עדיין שיוך מאומת בין `sender` לבין הודעות Green API. קוד ההתאמה נוצר ידנית בשרת הניסוי בלבד. לפני אינטגרציה נדרש אימות זהות שולח מול Webhook אמיתי.
- `admin/job` מיועד לבדיקת נמען אחד בכל פעם. אל תשלח ממנו לקבוצות או נמענים אמתיים לפני בדיקה מקיפה.
- ביטול חיבור דרך האפליקציה מסיר אסימון מקומי, לא מבטל את הטוקן בשרת. בגרסה הסופית נוסיף ביטול בצד שרת לפי בקשה.
- אין retry אוטומטי להודעות לא ודאיות; אין מסלול חיוב ל-SMS4FREE בשרת הניסוי.

## ממשק API
`POST /admin/pair-code`, `POST /device/pair`, `POST /device/heartbeat`, `GET /device/jobs`, `POST /device/claim`, `POST /device/dispatched`, `POST /device/result`, `POST /device/part-result`, `POST /admin/job`, `GET /admin/jobs`.
