# Zero Launcher

<p align="center">
  <img src="assets/banner.png" alt="Zero Launcher Banner">
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Platform-Windows%20%7C%20Linux-2ea44f?style=for-the-badge" alt="Platform">
  <img src="https://img.shields.io/badge/Built%20With-Java%20(Swing%2FAWT)-f89820?style=for-the-badge" alt="Java">
  <img src="https://img.shields.io/badge/Status-In%20Development-8b5cf6?style=for-the-badge" alt="Status">
  <img src="https://img.shields.io/badge/Future-Rust-ce422b?style=for-the-badge" alt="Rust">
</p>

<p align="center">
A modern, lightweight, and highly customizable Minecraft launcher built entirely with Java (Swing/AWT), designed for both Windows and Linux.
</p>

---

> [!NOTE]
>
> **Zero Launcher** is my primary project and will continue to receive updates until it reaches the level of quality I have in mind.
>
> Once that milestone is reached, development will begin on **Zero Launcher RUST**—a complete rewrite of the launcher using **Rust** while preserving the same philosophy, features, and user experience.
>
> The goal isn't simply to switch programming languages. It's to build an even faster, more reliable, and more maintainable launcher on a modern foundation while keeping everything users already enjoy about Zero Launcher.

## Why Rust?

- Better performance and lower memory usage.
- Faster startup times.
- Memory safety without garbage collection.
- Improved reliability and stability.
- Better multithreading support.
- Easier long-term maintenance.
- A cleaner architecture for future development.
- Greater scalability as the project grows.

---

# Features

## Multiple Minecraft Instances

- Create, edit, duplicate, and delete instances.
- Separate game directories for complete isolation.
- Configure Java, RAM, JVM arguments, Java executable, and launch options per instance.
- Clone existing instances.
- Backup and restore instances.
- Automatically repair corrupted installations.

---

## Powerful Mod Management

- Automatically identify installed mods.
- Update mods directly from Modrinth.
- Install missing dependencies automatically.
- Remove duplicate mod files.
- Detect incompatible mods.
- Update all mods with one click.
- Browse changelogs before updating.
- Categorize mods automatically.
- Built-in Config Editor.
- Export and import mod presets.
- Verify installed mod integrity.

---

## Discover

Browse Modrinth without leaving the launcher.

- Search mods and resource packs.
- Advanced filters.
- Rich project pages with screenshots, descriptions, versions, dependencies, and changelogs.
- One-click installation.
- Favorite projects.
- Recently viewed history.

---

## Accounts

- Offline account support.
- Microsoft account support *(planned)*.
- Quick account switching.
- Custom player avatars.

---

## Extensive Customization

Zero Launcher is built around customization.

- Fully customizable colors.
- Theme import and export.
- Theme sharing.
- Custom fonts.
- Custom backgrounds.
- Blur, tint, dimming, and scaling options.
- Transparent interface effects.
- Custom title bar.
- Window animations.
- Adjustable layouts.
- Custom launcher logo.
- Custom startup animation.
- Adjustable corner radius.
- Compact mode.

---

## Highly Customizable Discord Rich Presence

Customize nearly every part of your Discord Rich Presence.

- Enable or disable Rich Presence.
- Use your own Discord Application ID.
- Customize every displayed text.
- Customize buttons and URLs.
- Show the selected instance.
- Show Minecraft version.
- Show the mod loader.
- Show Java version.
- Show allocated RAM.
- Show play time.
- Display custom images and tooltips.
- Show the current launcher page.
- Show launcher activity.
- Display download progress.
- Detect Singleplayer or Multiplayer automatically.
- Optionally display the connected server IP.
- Privacy mode.
- Multiple Rich Presence profiles.
- Automatic profile switching.

---

## Performance

- Per-instance RAM allocation.
- Custom JVM arguments.
- Java version management.
- Automatic Java detection.
- Performance profiles.
- Automatic RAM recommendations.
- Faster launch optimizations.

---

## Privacy & Security

- Hide usernames throughout the launcher.
- Redact sensitive paths.
- Redact Minecraft session tokens.
- Automatically clear account sessions.
- Streamer mode.
- Screenshot privacy mode.

---

## Console

- Colorized live console.
- Search within logs.
- Warning and error filters.
- Export logs.
- Copy individual errors.
- Timestamps.
- Auto-scroll toggle.

---

## Downloads

- Integrated download manager.
- Parallel downloads.
- Resume interrupted downloads.
- Download speed limiter.
- Live progress tracking.
- Download history.
- Retry failed downloads.

---

## Dawn Client Integration

Install or uninstall Dawn Client directly from any compatible instance.

---

## System Tray

Keep the launcher running in the background while Minecraft is open.

- Minimize to the system tray.
- Instant restore.
- Optional notifications.
- Close-to-tray behavior.

---

## Built for the Future

Zero Launcher is designed as a long-term project with a strong focus on performance, customization, and user experience.

Every feature is built with maintainability, flexibility, and responsiveness in mind, ensuring that the launcher continues to evolve without sacrificing simplicity.

---

# زيرو لانشر

<p align="center">
لانشر ماينكرافت حديث وخفيف وقابل للتخصيص بشكل كبير، مبني بالكامل باستخدام Java (Swing/AWT)، ومصمم للعمل على **Windows** و **Linux**.
</p>

---

> [!NOTE]
>
> **زيرو لانشر** هو المشروع الأساسي الذي أعمل عليه حالياً، وسيستمر في الحصول على تحديثات وتحسينات حتى يصل إلى المستوى الذي أطمح إليه.
>
> بعد ذلك سأبدأ العمل على **Zero Launcher RUST**، وهي إعادة بناء كاملة للانشر باستخدام **Rust** مع الحفاظ على نفس الفكرة والميزات وتجربة الاستخدام.
>
> الهدف ليس مجرد تغيير لغة البرمجة، بل إنشاء نسخة أسرع وأكثر استقراراً وأسهل في التطوير والتوسع مستقبلاً، مع الحفاظ على هوية Zero Launcher.

## لماذا Rust؟

- أداء أعلى واستهلاك أقل للذاكرة.
- تشغيل أسرع.
- إدارة آمنة للذاكرة دون Garbage Collector.
- استقرار واعتمادية أكبر.
- دعم أفضل للعمليات المتوازية.
- سهولة تطوير المشروع على المدى الطويل.
- بنية حديثة وقابلة للتوسع.
- أساس قوي لإضافة المزيد من الميزات مستقبلاً.

---

# المميزات

## إدارة الإصدارات

- إنشاء وتعديل ونسخ وحذف الإصدارات.
- مجلد مستقل لكل إصدار.
- تخصيص Java وRAM وJVM لكل إصدار.
- نسخ إصدار كامل.
- إنشاء واستعادة النسخ الاحتياطية.
- إصلاح الإصدارات التالفة تلقائياً.

---

## إدارة المودات

- التعرف على المودات تلقائياً.
- تحديثها مباشرة من Modrinth.
- تثبيت التبعيات تلقائياً.
- إزالة الملفات المكررة.
- اكتشاف المودات غير المتوافقة.
- تحديث جميع المودات بضغطة واحدة.
- عرض سجل التغييرات.
- تصنيف المودات تلقائياً.
- محرر Config مدمج.
- استيراد وتصدير Presets.
- التحقق من سلامة ملفات المودات.

---

## استكشاف المحتوى

- البحث عن المودات وحزم الموارد.
- فلاتر متقدمة.
- صفحات متكاملة للمشاريع.
- تثبيت مباشر.
- المفضلة.
- سجل آخر المشاريع.

---

## إدارة الحسابات

- دعم حسابات Offline.
- دعم Microsoft مستقبلاً.
- التبديل السريع بين الحسابات.
- صور الحسابات.

---

## تخصيص الواجهة

- تخصيص كامل للألوان.
- استيراد وتصدير الثيمات.
- مشاركة الثيمات.
- خطوط مخصصة.
- صور خلفية.
- تأثيرات Blur والشفافية.
- شريط عنوان مخصص.
- تحريك عناصر الواجهة.
- شعار مخصص.
- أنيميشن بداية التشغيل.
- التحكم في حواف الواجهة.
- وضع Compact.

---

## Discord Rich Presence قابل للتخصيص بالكامل

- تشغيل أو إيقاف الميزة.
- استخدام Discord Application ID مخصص.
- تخصيص جميع النصوص.
- تخصيص الأزرار والروابط.
- عرض اسم الإصدار.
- عرض إصدار ماينكرافت.
- عرض Mod Loader.
- عرض إصدار Java.
- عرض RAM.
- عرض مدة اللعب.
- صور مخصصة.
- عرض الصفحة الحالية داخل اللانشر.
- عرض حالة اللانشر.
- عرض تقدم التنزيلات.
- اكتشاف اللعب الفردي أو الجماعي.
- عرض IP الخادم اختيارياً.
- وضع الخصوصية.
- ملفات إعدادات متعددة.
- التبديل التلقائي بينها.

---

## الأداء

- تخصيص RAM.
- إعدادات JVM.
- إدارة Java.
- اكتشاف Java تلقائياً.
- أوضاع أداء.
- اقتراح أفضل إعدادات RAM.
- تحسين سرعة التشغيل.

---

## الخصوصية والأمان

- إخفاء اسم المستخدم.
- إزالة المعلومات الحساسة من السجلات.
- إخفاء Session Tokens.
- حذف الجلسات عند الإغلاق.
- وضع البث.
- وضع خصوصية للصور.

---

## سجل التشغيل

- سجل ملون مباشر.
- البحث داخل السجل.
- تصفية الأخطاء.
- تصدير السجل.
- نسخ الأخطاء.
- عرض الوقت.
- التحكم في التمرير التلقائي.

---

## إدارة التنزيلات

- مدير تنزيلات متكامل.
- تنزيلات متوازية.
- استكمال التنزيل بعد الانقطاع.
- تحديد سرعة التحميل.
- عرض التقدم مباشرة.
- سجل التنزيلات.
- إعادة محاولة التنزيلات الفاشلة.

---

## تكامل Dawn Client

تثبيت أو إزالة Dawn Client لأي إصدار بضغطة واحدة.

---

## دعم System Tray

تشغيل اللانشر في الخلفية أثناء تشغيل ماينكرافت مع إمكانية تصغيره إلى System Tray.

---

## مصمم للمستقبل

تم تصميم Zero Launcher ليكون مشروعاً طويل الأمد يركز على الأداء، وسهولة التخصيص، وتجربة استخدام حديثة، مع بنية قابلة للتطوير وإضافة المزيد من الميزات مستقبلاً دون التأثير على بساطة الاستخدام.

---

<p align="center">

**Made for the Minecraft community.**

**Built with Java today. Built with Rust tomorrow.**

</p>