# أجوبة نماذج Google Play Console

## Data safety (أمان البيانات)

| السؤال | الجواب |
|---|---|
| Does your app collect or share any of the required user data types? | **Yes** (بسبب الترجمة الاختيارية عبر الإنترنت) |
| Is all of the user data collected by your app encrypted in transit? | **Yes** (HTTPS) |
| Do you provide a way for users to request that their data is deleted? | **Yes**: كل شي على الجهاز؛ مسح بيانات التطبيق أو حذفه |

**أنواع البيانات:**
- **App activity → Other user-generated content** (النص اللي بيترجمه المستخدم):
  - Collected: **Yes** · Shared: **Yes**، مع Google Translate (وMyMemory كبديل)
  - Optional: **Yes** (المستخدم بيقدر يسكّرها من ⚙️ الإعدادات)
  - Processed ephemerally: **Yes** · Purpose: **App functionality**
- **Audio → Voice or sound recordings**: **No**. التطبيق ما بيجمع صوت، والتحويل لنص بيصير بخدمة النظام بالموبايل.
- كل الأنواع التانية: **No**.

> ملاحظة: إذا قررت تشيل الترجمة عبر الإنترنت نهائياً من التطبيق، بتصير الإجابة الأولى **No** وبيصير النموذج أبسط.

## Content rating (التصنيف العمري، استبيان IARC)
- Category: **Reference, News, or Educational**
- كل أسئلة العنف والجنس واللغة البذيئة والمخدرات والمقامرة: **No**
- User interaction / sharing content between users: **No**
- Shares location: **No** · Digital purchases: **No**
- النتيجة المتوقعة: **Everyone / 3+**

## Target audience and content (الجمهور المستهدف)
- **الخيار ١ (أسهل لأول إصدار):** 13+ (13–15، 16–17، 18+). بتتجنّب متطلبات برنامج العائلات الكاملة.
- **الخيار ٢:** إذا بدك تشمل الأطفال تحت ١٣ (مثلاً 6–12)، لازم تلتزم بـ **Families Policy**: ما في إعلانات، ما في مكتبات تتبع، سياسة خصوصية واضحة، ومحتوى مناسب. التطبيق مبني بهالشكل: ما فيه إعلانات ولا تحليلات ولا حسابات. بس الترجمة عبر الإنترنت بتبعت نص لطرف تالت، ومنصح تخليها مسكّرة. والأحسن تشيلها كلياً بنسخة الأطفال.

## App access (الوصول للتطبيق)
- All functionality is available without special access (ما في تسجيل دخول).

## Ads (الإعلانات)
- **No, my app does not contain ads.**

## Permissions declaration (تصريح الأذونات)
- **RECORD_AUDIO:** للتدريب على النطق والمحادثة والمترجم المباشر. بينطلب بس لما المستخدم يضغط 🎤.
