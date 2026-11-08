# ترجمان — Tercüman

**العب ألعاب البلايستيشن على تلفزيون أندرويد مع ترجمة عربية فورية.**
تطبيق مجاني لتلفزيونات Android TV / Google TV: يبث اللعبة من PS5/PS4 داخل شبكة المنزل (1080p، 60 إطارًا، HEVC/HDR) ويقرأ نصوص اللعبة الإنجليزية ويعرضها بالعربية الفصحى مكانها.

## المميزات
- بث سلس من البلايستيشن (محرك Chiaki) بدون أي خادم وسيط.
- ترجمة فورية فوق النص الإنجليزي بأسلوب الترجمة السينمائية، مع أيقونات أزرار اليد بشكلها.
- مكتبة ترجمات احترافية مشتركة تُبنى تلقائيًا ([fifn1/arribha-packs](https://github.com/fifn1/arribha-packs)).
- تسجيل دخول PlayStation من التلفزيون أو بالباركود من الجوال.
- [سياسة الخصوصية](https://arribha-intake.d-fifn1.workers.dev/privacy)

## محتوى المستودع
| المجلد | الوصف |
|---|---|
| `player/` | تطبيق أندرويد TV (Kotlin) |
| `cloud/intake/` | نقطة الاستقبال والترجمة (Cloudflare Worker + D1 + Workers AI) |
| `tools/` | أداة تحويل حزم الترجمة |

## البناء
- Android Studio / Gradle 9.8 / AGP 9.4، JDK 17+.
- `cd player && gradle assembleRelease` (التوقيع من `signing/keystore.properties`، غير منشور).

## الترخيص
AGPL-3.0 (انظر `LICENSE`). يستخدم التطبيق محرك **Chiaki 2.2.0** (AGPL-3.0 مع استثناء OpenSSL):
الملفات `player/app/src/main/java/com/metallic/chiaki/lib/Chiaki.kt` ومكتبة `libchiaki-jni.so` مأخوذة من إصدار F-Droid
لـ Chiaki، وكوده المصدري متاح هنا: https://f-droid.org/repo/com.metallic.chiaki_12_src.tar.gz
و https://git.sr.ht/~thestr4ng3r/chiaki

PlayStation وPS5 علامات تجارية لشركة Sony Interactive Entertainment. هذا المشروع غير تابع لـ Sony.

---
**Tercüman** (formerly Arribha) — free Android TV app: PlayStation Remote Play (Chiaki engine) with live Arabic and Turkish translation of in-game text. AGPL-3.0.
