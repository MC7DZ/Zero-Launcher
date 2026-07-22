# Zero Launcher

> **Note**
>
> Zero Launcher is my current flagship project and will continue to receive improvements. Once it reaches the level I have in mind, I'll begin working on **Zero Launcher RUST**—a complete rewrite of the launcher in **Rust** while preserving the same vision and features.
>
> The goal isn't to replace the current launcher overnight, but to explore what can be achieved with a lower-level language and build an even faster, more efficient foundation for the future.
>
> **Why Rust?**
>
> * Better performance and lower memory usage.
> * Faster startup times.
> * Safer memory management without garbage collection.
> * Improved reliability for large projects.
> * Better scalability for future features.
> * Opportunity to build a more modern architecture from the ground up.

A modern Minecraft launcher built entirely with Java (Swing/AWT), designed to be fast, lightweight, and highly customizable. Zero Launcher focuses on delivering a polished experience for managing Minecraft instances, mods, and customization without unnecessary complexity.

## Features

### Multiple Minecraft Instances

* Create, edit, duplicate, and delete instances.
* Use separate game directories for complete isolation.
* Configure Java, RAM, JVM arguments, Java executable, and launch options per instance.
* Repair corrupted instances automatically.
* Clone existing instances with all their settings.
* Backup and restore instances.

### Powerful Mod Management

* Automatically identify installed mods.
* Update mods directly from Modrinth.
* Install missing dependencies automatically.
* Remove duplicate mod files.
* Detect incompatible mods.
* Update all mods with one click.
* Export and import mod presets.
* Browse changelogs before updating.
* Categorize mods automatically.
* Built-in Config Editor for supported configuration files.

### Discover

Browse Modrinth without leaving the launcher.

* Search mods and resource packs.
* Advanced filters.
* Rich project pages with screenshots, descriptions, versions, dependencies, and download history.
* Install directly into any instance.
* Save favorites.

### Accounts

* Offline account support.
* Microsoft account support *(planned)*.
* Quick account switching.

### Extensive Customization

Zero Launcher is designed around customization.

* Fully customizable colors.
* Custom fonts.
* Custom backgrounds.
* Blur, tint, dimming, and scaling options.
* Transparent UI effects.
* Custom title bar.
* Window animations.
* Layout customization.
* Theme import/export.
* Theme sharing.

### Highly Customizable Discord Rich Presence

Your Discord status should be as customizable as the launcher itself.

*   Enable or disable Rich Presence.
*   Set a custom Discord Application ID.
*   Customize every displayed text.
*   Select which information is visible.
*   Show the selected instance name.
*   Show Minecraft version.
*   Show elapsed play time.
*   Display a custom large image and text.
*   Show current launcher tab (e.g., "Browsing Instances").
*   Show game state (e.g., "Launching Game...", "In Main Menu", "In Singleplayer", "In Multiplayer").
*   Show server IP when in multiplayer.

### Performance

* Per-instance RAM allocation.
* Custom JVM arguments.
* Java version management.
* Performance profiles (Low / Balanced / High).
* Automatic RAM recommendations based on your hardware.

### Privacy & Security

* Hide usernames throughout the launcher.
* Redact sensitive paths and tokens from logs.
* Automatically clear sessions when exiting.
* Privacy mode for streaming and screenshots.

### Console

* Colorized live console.
* Search within logs.
* Filter warnings and errors.
* Export logs.
* Copy individual errors with one click.

### Downloads

* Integrated download manager.
* Resume interrupted downloads.
* Parallel downloads.
* Download speed limiter.
* Real-time progress tracking.

### Dawn Client Integration

Install or uninstall Dawn Client directly from the launcher.

### System Tray

Minimize the launcher to the system tray while Minecraft is running.

### Built for the Future

Zero Launcher is designed with long-term development in mind. Every feature is built to be maintainable, responsive, and highly configurable while keeping the interface clean and intuitive.

---

# زيرو لانشر

> **ملاحظة**
>
> زيرو لانشر هو المشروع الأساسي الذي أعمل عليه حالياً، وسيستمر في الحصول على تحديثات وتحسينات بشكل مستمر. وبعد الانتهاء من تطويره بالشكل الذي أطمح إليه، سأبدأ العمل على **Zero Launcher RUST**، وهي إعادة بناء كاملة لنفس المشروع باستخدام لغة **Rust** مع الحفاظ على نفس الفكرة والميزات، ولكن على أساس تقني أحدث وأكثر كفاءة.
>
> الهدف ليس استبدال النسخة الحالية مباشرة، وإنما استكشاف الإمكانيات التي توفرها Rust لبناء لانشر أسرع وأكثر استقراراً وأسهل في التطوير على المدى البعيد.
>
> **لماذا Rust؟**
>
> * أداء أعلى واستهلاك أقل للذاكرة.
> * سرعة أكبر في تشغيل اللانشر.
> * إدارة آمنة للذاكرة دون الحاجة إلى Garbage Collector.
> * استقرار واعتمادية أكبر.
> * بنية أسهل للتوسع وإضافة الميزات مستقبلاً.
> * إعادة تصميم المشروع بهندسة حديثة من الصفر.

لانشر ماينكرافت حديث مبني بالكامل باستخدام Java (Swing/AWT)، يركز على الأداء، وسهولة الاستخدام، وإدارة الإصدارات والمودات، مع مستوى عالٍ من التخصيص.

## المميزات

### إدارة الإصدارات

* إنشاء وتعديل ونسخ وحذف الإصدارات.
* مجلد مستقل لكل إصدار.
* تخصيص إعدادات Java وRAM وJVM لكل إصدار.
* إصلاح الإصدارات التالفة.
* نسخ إصدار كامل بضغطة واحدة.
* إنشاء نسخ احتياطية واستعادتها.

### إدارة المودات

* التعرف على المودات تلقائياً.
* تحديث المودات مباشرة من Modrinth.
* تثبيت التبعيات تلقائياً.
* إزالة الملفات المكررة.
* اكتشاف المودات غير المتوافقة.
* تحديث جميع المودات دفعة واحدة.
* استيراد وتصدير Presets.
* عرض سجل التغييرات قبل التحديث.
* تصنيف المودات تلقائياً.
* محرر مدمج لملفات Config.

### استكشاف المحتوى

* البحث عن المودات وحزم الموارد.
* فلاتر متقدمة.
* صفحة متكاملة لكل مشروع تحتوي على الصور والوصف والإصدارات والتبعيات.
* تثبيت مباشر داخل الإصدار.
* إضافة المشاريع إلى المفضلة.

### إدارة الحسابات

* دعم حسابات Offline.
* دعم Microsoft مستقبلاً.
* التبديل السريع بين الحسابات.

### تخصيص الواجهة

* تخصيص كامل للألوان.
* خطوط مخصصة.
* صور خلفية.
* تأثيرات Blur وشفافية وتعتيم.
* شريط عنوان مخصص.
* تحريك عناصر الواجهة.
* استيراد وتصدير الثيمات.
* مشاركة الثيمات.

### Discord Rich Presence قابل للتخصيص بالكامل

يمكنك التحكم في كل جزء من حالة Discord.

*   تفعيل أو تعطيل الميزة.
*   تعيين معرف تطبيق Discord مخصص.
*   تخصيص جميع النصوص المعروضة.
*   تحديد المعلومات التي يتم عرضها.
*   عرض اسم الإصدار المحدد.
*   عرض إصدار ماينكرافت.
*   عرض وقت اللعب المنقضي.
*   عرض صورة كبيرة مخصصة ونص.
*   عرض علامة تبويب المشغل الحالية (مثل "تصفح الإصدارات").
*   عرض حالة اللعبة (مثل "تشغيل اللعبة...", "في القائمة الرئيسية", "في اللعب الفردي", "في اللعب المتعدد").
*   عرض عنوان IP للخادم عند اللعب المتعدد.

### الأداء

* تخصيص RAM لكل إصدار.
* إعدادات JVM.
* اختيار نسخة Java.
* أوضاع أداء جاهزة.
* اقتراح أفضل إعدادات RAM تلقائياً.

### الخصوصية

* إخفاء اسم المستخدم.
* إزالة المعلومات الحساسة من السجلات.
* حذف الجلسات عند الإغلاق.
* وضع مخصص للبث والتصوير.

### سجل التشغيل

* سجل ملون مباشر.
* البحث داخل السجل.
* تصفية الأخطاء والتحذيرات.
* تصدير السجل.
* نسخ أي خطأ بضغطة واحدة.

### إدارة التنزيلات

* مدير تنزيلات متكامل.
* استكمال التنزيل بعد الانقطاع.
* تنزيلات متوازية.
* تحديد سرعة التحميل.
* متابعة مباشرة للتقدم.

### تكامل Dawn Client

تثبيت أو إزالة Dawn Client مباشرة من داخل اللانشر.

### دعم System Tray

إبقاء اللانشر يعمل في الخلفية أثناء تشغيل اللعبة.

### مصمم للمستقبل

تم تصميم Zero Launcher ليكون مشروعاً طويل الأمد، مع التركيز على الأداء، وسهولة التوسع، وواجهة نظيفة، وخيارات تخصيص واسعة تجعل كل مستخدم قادراً على تشكيل تجربته بالطريقة التي تناسبه.