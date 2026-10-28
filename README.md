# CAMPRO 📷

كاميرا احترافية لهاتف Samsung Galaxy A35 — فلاتر سينمائية + فلاتر Kodak، وأعلى جودة تصوير ممكنة.

## المميزات
- **أعلى جودة**: CameraX بوضع MAXIMIZE_QUALITY + أعلى دقة متاحة + JPEG 100%
- **9 فلاتر سينمائية** (Teal & Orange, Blockbuster, Noir Cine, Moody Blue, Golden Hour, Matte Film, Cyber Neon, Desert Dust, Thriller Green)
- **9 فلاتر Kodak-inspired** (Gold 200, Portra 400, Ektar 100, ColorPlus 200, Ultramax 400, Vision3 500T, Kodachrome, Tri-X 400 B&W, Funsaver Fade)
- معاينة حية للفلتر + نفس الفلتر على الصورة المحفوظة (WYSIWYG)
- شريط شدة الفلتر (Intensity) + Vignette + حبيبات فيلم (Grain)
- زووم بالقرصة، تركيز باللمس، فلاش (Off/Auto/On)، شبكة، كاميرا أمامية، زر الصوت للتصوير
- الصور تُحفظ في `Pictures/CAMPRO`

## بناء الـ APK على GitHub
1. أنشئ Repository جديد على GitHub.
2. ارفع **محتويات** هذا المجلد كلها (بما فيها المجلد المخفي `.github`).
3. افتح تبويب **Actions** → سيبدأ **Build CAMPRO APK** تلقائيًا (أو شغّله يدويًا بـ Run workflow).
4. بعد انتهائه (٣–٥ دقائق) افتح التشغيلة → **Artifacts** → حمّل `CAMPRO-apk` → فك الضغط → `CAMPRO.apk`.
5. انسخ الـ APK للموبايل وثبّته (فعّل "التثبيت من مصادر غير معروفة").

## ملاحظات
- التطبيق مقفول على A35 (كود الموديل `SM-A356*`). لتشغيله على أي هاتف غيّر `STRICT_A35_ONLY` إلى `false` في `MainActivity.kt`.
- الحد الأدنى Android 14 (API 34).
