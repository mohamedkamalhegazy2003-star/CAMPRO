# CAMPRO 📷

كاميرا احترافية لهاتف Samsung Galaxy A35 — فلاتر سينمائية + فلاتر Kodak، وأعلى جودة تصوير ممكنة.

## المميزات
- **أعلى جودة**: CameraX بوضع MAXIMIZE_QUALITY + أعلى دقة متاحة + JPEG 100%
- **16 فلتر سينمائي** (Teal & Orange, Bleach Bypass, Day for Night, Vintage 70s, Sci-Fi Cyan ...)
- **14 فلتر Kodak-inspired** (Portra 400, Ektar 100, Kodachrome, Ektachrome, Tri-X, T-Max, Disposable Flash ...)
- **15 فلتر على طراز iPhone** (Vivid / Vivid Warm / Vivid Cool, Dramatic / Warm / Cool, Mono, Silvertone, Noir, Instant, Process, Transfer, Chrome, Fade, Tonal)
- **8 فلاتر Fuji-inspired** (Velvia, Provia, Astia, Classic Chrome, Pro Neg, Eterna, Superia, Acros)
- **HDR+** : 3 صور بتعريضات مختلفة (-2/0/+2 EV) تتدمج في صورة واحدة (مخرجها حتى ~8 ميجابكسل، ويحتاج تثبيت الهاتف)
- **أوضاع جوجل كاميرا** (HDR / Night / Portrait / Face Retouch / Auto) عن طريق CameraX Extensions، وبتظهر فقط لو الهاتف بيدعمها
- مؤقت 3/10 ثواني، وتحكم في التعريض (EV)
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
