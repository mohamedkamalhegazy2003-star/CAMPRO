# Camera-Patcher مع Samsung Galaxy A35 (اختياري، يحتاج روت)

المشروع: https://github.com/TBM13/Camera-Patcher

## ما هو وماذا يفعل؟
مجموعة سكربتات Python تعدّل **مكتبة الكاميرا الخاصة بالنظام** (HAL) في هواتف سامسونج بمعالج Exynos، لتغيير:
- **Hardware Level** (مثلاً من LIMITED إلى FULL).
- **القدرات (Capabilities)** التي تعرضها الكاميرا للتطبيقات، وأهمها **RAW**، وهو المطلوب لتصوير DNG ولتطبيق GCam.

الأداة بتعدّل ملف في نظام الهاتف (partition الـ vendor)، **مش جزء من تطبيق**. عشان كده CAMPRO ما يقدرش يطبّقها بنفسه، لكنه:
1. **يفحص** الهاتف ويعرض لك الحالة (اضغط على كلمة **CAMPRO** أعلى الشاشة).
2. **يفعّل وضع RAW (DNG)** تلقائيًا في شريط الأوضاع بمجرد ما الهاتف يعرض قدرة RAW بعد التعديل.

## الملف المناسب للـ A35
الـ A35 معالجه Exynos 1380 (S5E8835)، وهو نفس معالج Galaxy A54 اللي مكتوب في README المشروع إنه تم اختباره عليه (Android 16). ملف المكتبة المتوقع:

```
/vendor/lib64/hw/camera.s5e8835.so
```
(README المشروع بيقول: اسم الملف بيختلف حسب الجهاز، فدوّر على ما يشبه `camera.s5e8835.so`.)

> الـ A35 نفسه غير مذكور في جدول الاختبارات، فالنتيجة غير مضمونة.

## الأوامر (من README المشروع)
يتطلب Python 3.11 أو أحدث، وتحميل المستودع كله (مش ملف واحد):

```bash
git clone https://github.com/TBM13/Camera-Patcher
cd Camera-Patcher
pip install -r requirements.txt

# تفعيل RAW على كل الكاميرات
python3 ./patch_s5e.py camera.s5e8835.so --enable-cap RAW

# أو رفع الـ Hardware Level
python3 ./patch_s5e.py camera.s5e8835.so --hardware-level FULL
```
القدرات المتاحة لـ `--enable-cap` حسب README: ManualSensor_ReadSensorSettings, ManualPostProcessing, BurstCapture, RAW, YUVReprocessing, PrivateReprocessing, MotionTracking, LogicalMultiCamera, SecureImageData, SystemCamera, OfflineProcessing, ControlZoom, LensCal, StreamUseCase, DynamicRangeTenBit, ColorSpaceProfiles (بعضها لا يعمل على الإصدارات القديمة).

لإخراج الملف كوحدة Magisk يضيف README هذه الخيارات معًا: `--lib-name camera.s5e8835.so --model "Galaxy A35" --android-version <إصدار أندرويد هاتفك> --version 1`.

(السكربت في README مكتوب `patch_lib.py` في الأمثلة، لكن اسم الملف الفعلي `patch_s5e.py`. اتبع الأمثلة الموجودة في README نفسه فهو المرجع.)

## تحذيرات مهمة
- README المشروع: "تفعيل شيء لا يعني أنه سيعمل كما هو متوقع، وعليك اختبار كل شيء".
- التعديل يلزمه **صلاحيات روت** (مثلًا Magisk) وفتح البوتلودر، وده بيفقد الضمان وممكن يعطّل Knox وبعض تطبيقات البنوك والدفع. وخطأ في الملف ممكن يعطّل الكاميرا أو يسبب bootloop. احتفظ بنسخة من الملف الأصلي وبطريقة للتراجع (مثلًا إزالة وحدة Magisk من وضع الأمان).
- لو السكربت فشل في تعديل ملفك، المشروع يطلب فتح Issue مع موديل الهاتف والملف.

## بعد التعديل
1. ثبّت CAMPRO (أو افتحه).
2. اضغط على **CAMPRO** أعلى الشاشة لتشوف تقرير القدرات: هتلاقي `RAW (DNG): AVAILABLE`.
3. هيظهر وضع **RAW** في شريط الأوضاع، وبيحفظ ملفات `.dng` في `Pictures/CAMPRO`.
   (فلاتر CAMPRO ما بتتطبقش على ملفات RAW، لأنها لازم تفضل خام.)
