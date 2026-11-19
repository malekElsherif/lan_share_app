# LAN Drop (أندرويد)

تطبيق خفيف من غير مكتبات خارجية. أول جهاز يفتحه بيبقى السيرفر، والباقي بيتصل بيه تلقائيًا.
متوافق مع `lan_drop.py` على اللابتوب (نفس الواجهة ونفس الاكتشاف).

## بناء الـ APK بدون Android Studio (GitHub Actions)
1. اعمل Repository جديد على GitHub وارفع محتوى الفولدر ده (بما فيه `.github`).
2. افتح تبويب **Actions** ← **Build APK** ← **Run workflow** (أو استنى بعد أول push).
3. لما يخلص (حوالي 3-5 دقايق) افتح الـ run ونزّل **LanDrop-apk** من Artifacts.
4. فك الـ zip وثبّت `app-debug.apk` على التليفون (فعّل "Install unknown apps").

## أو بـ Android Studio
افتح الفولدر ← Build ← Build APK(s).

## ملاحظات
- أندرويد هيطلب إذن الإشعارات (لازم عشان السيرفر يفضل شغّال في الخلفية).
- عطّل Battery Optimization للتطبيق لو الشركة المصنّعة بتقتل التطبيقات (Xiaomi/Oppo/Samsung).
- الملفات المرفوعة بتتخزّن في مساحة التطبيق، والتحميل من الواجهة بيحفظها في Downloads/LanDrop.
# lan_share_app
