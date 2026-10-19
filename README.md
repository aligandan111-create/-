# لجين: تعلّم الإنجليزي الأمريكي (تطبيق أندرويد)

هالمجلد هو مشروع أندرويد كامل وجاهز لـ Google Play. التطبيق عبارة عن صفحة لجين نفسها جوّا WebView، ومعها ثلاث طبقات أصلية:

| الطبقة | الملف | شو بتعمل |
|---|---|---|
| 🎤 الميكروفون | `SpeechBridge.kt` | التعرّف على الكلام من أندرويد (الـ WebView ما فيه هالخاصية). |
| 🔊 النطق | `TtsBridge.kt` | تحويل النص لكلام من أندرويد، بصوت أمريكي وصوت عربي. |

> بالنسخة 1.1.0 شلنا Gemma والمترجم المستمر. الروبوت بيشتغل بدون إنترنت، والتطبيق صار أخف بكتير.

ملف `bridge.js` بيربط هالطبقات بالصفحة، فكود الصفحة ما احتاج يتغيّر.

```
lujain-android/
├─ app/src/main/assets/www/index.html   ← التطبيق نفسه
├─ app/src/main/assets/www/bridge.js    ← الجسر مع أندرويد
├─ app/src/main/java/com/bequeen/lujain/ ← MainActivity + الطبقات الثلاث
├─ .github/workflows/build.yml          ← بناء مجاني على GitHub
└─ store/                               ← الأيقونة، الصور، النصوص، سياسة الخصوصية
```

- **اسم الحزمة:** `com.bequeen.lujain`. بعد أول نشر ما بيتغيّر، فإذا بدك اسم تاني غيّره من هلق بملف `app/build.gradle.kts`.
- **targetSdk 36:** هاد المطلوب حالياً من Google Play للتطبيقات الجديدة.

---

## الخطوة ١: بناء ملف AAB الموقّع

### الطريقة (أ): Android Studio (الأسهل)
1. نزّل [Android Studio](https://developer.android.com/studio) وافتح مجلد `lujain-android`. رح ينزّل Gradle والـ SDK لحاله، ووافق إذا طلب تنزيل Android SDK 36.
2. للتجربة: وصّل موبايلك (بعد ما تفعّل USB debugging) واضغط ▶ Run.
   - **لملف APK بتثبّته مباشرة:** **Build → Generate Signed App Bundle / APK → APK**، واختار مفتاح Be Queen LLC، وبعدين **release**. الملف بيطلع بـ `app/release/app-release.apk`.
   - إذا التطبيق القديم مثبّت بمفتاح تاني، امسحه من الموبايل قبل ما تثبّت الجديد.
3. للنشر: من القائمة **Build → Generate Signed App Bundle / APK → Android App Bundle**:
   - اختار **Create new…** واعمل مفتاح جديد (keystore). اختار كلمة سر قوية.
   - ⚠️ احفظ ملف المفتاح وكلمة السر بمكان آمن. بدونهم ما بتقدر تحدّث التطبيق.
   - اختار **release**، وبيطلعلك الملف `app-release.aab`.

### الطريقة (ب): GitHub (بدون ما تنزّل شي)
1. اعمل مستودع **Private** جديد على GitHub، وارفع محتوى مجلد `lujain-android` كله.
2. بعد كل رفع بيشتغل البناء لحاله. من تبويب **Actions** نزّل `lujain-test-apk`، وثبّته على موبايلك للتجربة.
3. لملف النشر الموقّع، اعمل المفتاح مرة وحدة على أي كمبيوتر فيه Java:
   ```bash
   keytool -genkeypair -v -keystore upload-keystore.jks -alias upload -keyalg RSA -keysize 2048 -validity 10000
   base64 -w0 upload-keystore.jks > keystore.b64
   ```
   (على ماك استعمل `base64 -i upload-keystore.jks -o keystore.b64`)
4. بالمستودع روح على **Settings → Secrets and variables → Actions** وضيف هالأسرار:

   | الاسم | القيمة |
   |---|---|
   | `KEYSTORE_BASE64` | محتوى ملف keystore.b64 |
   | `KEYSTORE_PASSWORD` | كلمة سر المفتاح |
   | `KEY_ALIAS` | `upload` |
   | `KEY_PASSWORD` | كلمة سر المفتاح |

5. شغّل البناء من جديد (Actions → Run workflow)، ونزّل `lujain-play-aab`.

---

## الخطوة ٢: Google Play Console
1. **حساب مطوّر:** من [play.google.com/console](https://play.google.com/console). الرسوم ٢٥ دولار مرة وحدة، وبيطلبوا توثيق الهوية.
2. **Create app:**
   - الاسم: «لجين: تعلّم الإنجليزي الأمريكي»
   - اللغة الافتراضية: العربية
   - النوع: App
   - السعر: Free
3. **Play App Signing:** خلّيه مفعّل (Use Google-generated key). هيك غوغل بتحفظ مفتاح التوقيع النهائي، وإذا ضاع مفتاحك بتقدر تعيده.
4. **صفحة المتجر (Store listing):** كل شي جاهز بمجلد `store/`:
   - النصوص: `listing-ar.md` و`listing-en.md` (ضيف الإنجليزي كترجمة)
   - الأيقونة: `icon-512.png`
   - صورة الغلاف: `feature-graphic-1024x500.png`
   - لقطات الشاشة: مجلد `screenshots/` (٦ صور، 1080×1920)
   - الفئة: Education
5. **سياسة الخصوصية:** عدّل الاسم والبريد بملف `store/privacy-policy.html`، وانشره على رابط عام (موقع Be Queen، أو GitHub Pages، أو أي استضافة)، وحط الرابط بالـ Console.
6. **App content:** الأجوبة جاهزة بملف `store/data-safety-and-content.md`. فيه أمان البيانات، والتصنيف العمري، والجمهور المستهدف، والإعلانات، والأذونات.

---

## الخطوة ٣: الاختبار والنشر
- **الحسابات الشخصية الجديدة** بتطلب غوغل منها **اختبار مغلق (Closed testing)** مع **١٢ مختبِر على الأقل لمدة ١٤ يوم** قبل ما تفتح النشر العام. الأهل والأصحاب بيمشّوها. تأكد من الرقم الحالي بالـ Console لأنه ممكن يتغيّر.
- **حسابات الشركات** (متل Be Queen LLC مع رقم D-U-N-S) عادةً معفاة من هالشرط.
- ارفع `app-release.aab` على **Testing → Closed testing**، وبعد المدة انقله لـ **Production**.
- **كل تحديث:** زيد `versionCode` (١ ← ٢ ← ٣ …) و`versionName` بملف `app/build.gradle.kts`، وابنِ من جديد.

---

## ملاحظات
- **الترجمة:** بتشتغل بـ Google Translate عبر الإنترنت، ومفعّلة من الأساس. إذا Google ما ردّ، بتستعمل MyMemory كبديل. المستخدم بيقدر يسكّرها من ⚙️ الإعدادات.
- **المايك:** التطبيق بيطلب إذن المايك مرة وحدة بأول فتحة بعد التثبيت. وبـ ⚙️ في زر «🎤 افحص» بيفحص كل شي.
- **ترجمة الصور** كانت بتشتغل بـ Claude بس، فهي مخفية جوّا التطبيق.
- **الخط:** إذا ما في إنترنت بيستعمل خط الموبايل العربي. ولتضمين خط Readex Pro، حطه بمجلد `assets/www/` وعدّل رابط الخط بـ `index.html`.
