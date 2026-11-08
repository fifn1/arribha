package com.abubadr.arribha

/**
 * Hand-written Arabic (clear Modern Standard Arabic, the style of officially localised games)
 * for words and lines that appear in almost every game. Checked before machine translation.
 * Keys are lower case, without end punctuation. "ZZ0", "ZZ1"… stand for button icons.
 */
object Phrasebook {
    private val exact: Map<String, String> = mapOf(
        // menus and buttons
        "continue" to "متابعة", "back" to "رجوع", "confirm" to "تأكيد", "cancel" to "إلغاء",
        "select" to "اختيار", "ok" to "موافق", "yes" to "نعم", "no" to "لا", "skip" to "تخطٍّ",
        "pause" to "إيقاف مؤقت", "paused" to "متوقفة مؤقتًا", "resume" to "استئناف", "resume game" to "استئناف اللعبة",
        "options" to "الخيارات", "settings" to "الإعدادات", "game settings" to "إعدادات اللعبة",
        "new game" to "لعبة جديدة", "load game" to "تحميل لعبة", "save game" to "حفظ اللعبة",
        "continue game" to "متابعة اللعبة", "quit" to "خروج", "quit game" to "الخروج من اللعبة", "exit" to "خروج",
        "main menu" to "القائمة الرئيسية", "main menu - demo" to "القائمة الرئيسية - نسخة تجريبية",
        "restart" to "إعادة البدء", "restart checkpoint" to "البدء من نقطة الحفظ", "retry" to "إعادة المحاولة",
        "try again" to "حاول مرة أخرى", "loading" to "جارٍ التحميل", "saving" to "جارٍ الحفظ",
        "game saved" to "تم حفظ اللعبة", "progress saved" to "تم حفظ التقدّم", "autosave" to "الحفظ التلقائي",
        "checkpoint" to "نقطة حفظ", "checkpoint reached" to "تم الوصول إلى نقطة الحفظ",
        "change selection" to "تغيير الاختيار", "apply" to "تطبيق", "default" to "الافتراضي", "reset" to "إعادة تعيين",
        "demo" to "نسخة تجريبية", "credits" to "شارة النهاية", "tutorial" to "شرح تعليمي",
        "hint" to "تلميح", "tip" to "نصيحة", "warning" to "تحذير", "locked" to "مقفل", "unlocked" to "مفتوح",
        "coming soon" to "قريبًا", "on" to "تشغيل", "off" to "إيقاف",
        // settings
        "difficulty" to "مستوى الصعوبة", "easy" to "سهل", "normal" to "عادي", "hard" to "صعب", "very hard" to "صعب جدًا",
        "audio" to "الصوت", "video" to "الفيديو", "display" to "العرض", "controls" to "التحكم",
        "subtitles" to "الترجمة النصية", "language" to "اللغة", "brightness" to "السطوع", "volume" to "مستوى الصوت",
        "music" to "الموسيقى", "sound effects" to "المؤثرات الصوتية", "accessibility" to "سهولة الوصول",
        "camera" to "الكاميرا", "vibration" to "الاهتزاز", "invert y-axis" to "عكس المحور الرأسي",
        // prompts
        "press any button" to "اضغط أي زر", "press any button to continue" to "اضغط أي زر للمتابعة",
        "press start" to "اضغط زر البدء", "press to start" to "اضغط للبدء", "press start to begin" to "اضغط زر البدء للبدء",
        "interact" to "تفاعل", "talk" to "تحدّث", "open" to "فتح", "close" to "إغلاق", "pick up" to "التقاط",
        "jump" to "قفز", "attack" to "هجوم", "dodge" to "مراوغة", "block" to "صدّ", "sprint" to "ركض سريع",
        "crouch" to "انحناء", "aim" to "تصويب", "shoot" to "إطلاق النار", "reload" to "إعادة التلقيم",
        "climb" to "تسلّق", "swim" to "سباحة", "use" to "استخدام", "equip" to "تجهيز", "unequip" to "إزالة التجهيز",
        "drop" to "إسقاط", "discard" to "تخلّص", "buy" to "شراء", "sell" to "بيع", "upgrade" to "ترقية",
        "do not turn off the system while this icon is displayed" to "لا تُطفئ الجهاز أثناء ظهور هذه الأيقونة",
        // game events
        "game over" to "انتهت اللعبة", "you died" to "لقد مُتّ", "victory" to "انتصار", "defeat" to "هزيمة",
        "you win" to "لقد فزت", "you lose" to "لقد خسرت",
        "mission complete" to "اكتملت المهمة", "mission failed" to "فشلت المهمة",
        "quest complete" to "اكتملت المهمة", "quest completed" to "اكتملت المهمة",
        "new quest" to "مهمة جديدة", "new quest received" to "مهمة جديدة", "quest updated" to "تم تحديث المهمة",
        "new objective" to "هدف جديد", "objective updated" to "تم تحديث الهدف", "objective complete" to "اكتمل الهدف",
        "level up" to "ارتقيت في المستوى", "level complete" to "اكتملت المرحلة", "chapter complete" to "اكتمل الفصل",
        "achievement unlocked" to "تم فتح إنجاز", "trophy unlocked" to "حصلت على كأس",
        "new item" to "غرض جديد", "item acquired" to "حصلت على غرض", "secret found" to "عثرت على سرّ",
        "inventory" to "الحقيبة", "inventory full" to "الحقيبة ممتلئة", "map" to "الخريطة", "journal" to "السجل",
        "quests" to "المهام", "skills" to "المهارات", "equipment" to "العتاد", "upgrades" to "الترقيات",
        "shop" to "المتجر", "health" to "الصحة", "ammo" to "الذخيرة", "collectibles" to "المقتنيات",
        "enemy" to "عدو", "boss" to "زعيم",
        // everyday lines
        "are you sure" to "هل أنت متأكد؟", "are you sure you want to quit" to "هل أنت متأكد أنك تريد الخروج؟",
        "unsaved progress will be lost" to "سيضيع التقدّم غير المحفوظ", "do you want to save" to "هل تريد الحفظ؟",
        "follow me" to "اتبعني", "over here" to "من هنا", "look out" to "انتبه", "watch out" to "احذر",
        "let's go" to "هيا بنا", "hurry" to "أسرع", "hurry up" to "أسرِع", "come on" to "هيّا",
        "thank you" to "شكرًا لك", "thanks" to "شكرًا", "good job" to "أحسنت", "well done" to "أحسنت صنعًا",
        "be careful" to "كن حذرًا", "who are you" to "من أنت؟", "where am i" to "أين أنا؟",
        "what happened" to "ماذا حدث؟", "i don't know" to "لا أعرف", "no way" to "مستحيل",
        "missed me" to "لم تُصبني!", "help me" to "ساعدني", "wait" to "انتظر", "stop" to "توقّف",
        "what was that" to "ما كان ذلك؟", "we're safe here" to "نحن بأمان هنا", "i'm sorry" to "أنا آسف",
        // open-world / crime games (GTA style)
        "wasted" to "لقد قُتلت", "busted" to "تم القبض عليك", "mission passed" to "نجحت المهمة",
        "lose the cops" to "تخلّص من الشرطة", "lose the police" to "تخلّص من الشرطة", "evade the police" to "اهرب من الشرطة",
        "lose your wanted level" to "تخلّص من مستوى المطاردة", "wanted level" to "مستوى المطاردة",
        "get in the car" to "اركب السيارة", "get in a car" to "اركب سيارة", "get in the vehicle" to "اركب المركبة",
        "get back in the car" to "عُد إلى السيارة", "get back in the vehicle" to "عُد إلى المركبة",
        "return to the vehicle" to "عُد إلى المركبة", "get out of the car" to "انزل من السيارة",
        "follow the target" to "اتبع الهدف", "lose the target" to "تخلّص من الملاحقة", "the target is getting away" to "الهدف يهرب",
        "you lost the target" to "أضعت الهدف", "you abandoned the mission" to "تخلّيت عن المهمة",
        "destination" to "الوجهة", "waypoint set" to "تم تحديد الوجهة", "new message" to "رسالة جديدة",
        "new contact" to "جهة اتصال جديدة", "incoming call" to "مكالمة واردة", "answer" to "رد", "hang up" to "إنهاء المكالمة",
        "phone" to "الهاتف", "money" to "المال", "cash" to "النقود", "weapon" to "السلاح", "weapons" to "الأسلحة",
        "vehicle" to "المركبة", "garage" to "المرآب", "safehouse" to "المخبأ", "hospital" to "المستشفى",
        "police station" to "مركز الشرطة", "store" to "المتجر", "bank" to "البنك", "airport" to "المطار",
        "take cover" to "احتمِ", "stay in cover" to "ابقَ مختبئًا", "stay out of sight" to "ابقَ بعيدًا عن الأنظار",
        "you've been spotted" to "لقد كُشف أمرك", "get to cover" to "اذهب إلى غطاء",
    )

    /** Mission objectives "Go to the bank": Arabic verb + English object (translated separately). */
    fun objective(en: String): Pair<String, String>? {
        val s = en.trim().trimEnd('.', '!')
        val rules = listOf(
            "^(?:go|get|head|drive) (?:back )?to (?:the )?(.+)$" to "اذهب إلى ",
            "^return to (?:the )?(.+)$" to "عُد إلى ",
            "^follow (?:the )?(.+)$" to "اتبع ",
            "^find (?:the )?(.+)$" to "ابحث عن ",
            "^collect (?:the )?(.+)$" to "اجمع ",
            "^(?:kill|take out|defeat|eliminate) (?:the )?(.+)$" to "اقضِ على ",
            "^escape (?:from )?(?:the )?(.+)$" to "اهرب من ",
            "^protect (?:the )?(.+)$" to "احمِ ",
            "^chase (?:the )?(.+)$" to "طارد ",
            "^steal (?:the |a )?(.+)$" to "اسرق ",
            "^pick up (?:the )?(.+)$" to "أحضر ",
            "^deliver (?:the )?(.+)$" to "سلّم ",
            "^talk to (?:the )?(.+)$" to "تحدّث إلى ",
            "^wait for (?:the )?(.+)$" to "انتظر ",
            "^explore (?:the )?(.+)$" to "استكشف ",
            "^search (?:the )?(.+)$" to "فتّش ",
            "^reach (?:the )?(.+)$" to "اصل إلى ",
            "^survive (?:the )?(.+)$" to "انجُ من ",
        )
        for ((re, ar) in rules) Regex(re, RegexOption.IGNORE_CASE).find(s)?.let { return ar to it.groupValues[1] }
        return null
    }

    private fun key(s: String) = s.lowercase().trim().trimEnd('.', '!', '?', ':', '…').replace(Regex("\\s+"), " ")

    /** Exact hand-written line, or null. Icon tokens are kept: "ZZ0 Continue" -> "ZZ0 متابعة". */
    fun lookup(en: String): String? {
        exact[key(en)]?.let { return keepPunct(en, it) }
        // button hint: "ZZ0 Continue" / "Continue ZZ0"
        Regex("^(ZZ\\d+)\\s+(.+)$").find(en.trim())?.let { m -> exact[key(m.groupValues[2])]?.let { return "${m.groupValues[1]} $it" } }
        Regex("^(.+?)\\s+(ZZ\\d+)$").find(en.trim())?.let { m -> exact[key(m.groupValues[1])]?.let { return "$it ${m.groupValues[2]}" } }
        return null
    }

    /** Lines like "Press ZZ0 to jump": returns the Arabic start and the English rest that still needs translating. */
    fun buttonPrompt(en: String): Pair<String, String?>? {
        val s = en.trim().trimEnd('.', '!')
        Regex("^(?:press|tap)\\s+(ZZ\\d+)(?:\\s+to\\s+(.+))?$", RegexOption.IGNORE_CASE).find(s)?.let { m ->
            return "اضغط ${m.groupValues[1]}" to m.groupValues[2].ifEmpty { null }
        }
        Regex("^hold\\s+(ZZ\\d+)(?:\\s+to\\s+(.+))?$", RegexOption.IGNORE_CASE).find(s)?.let { m ->
            return "اضغط مطولًا على ${m.groupValues[1]}" to m.groupValues[2].ifEmpty { null }
        }
        Regex("^(.+?)\\s+by\\s+pressing\\s+(ZZ\\d+)$", RegexOption.IGNORE_CASE).find(s)?.let { m ->
            return "اضغط ${m.groupValues[2]} لـ" to m.groupValues[1]
        }
        return null
    }

    /** Words that a "to …" phrase can map to directly: "jump" -> "للقفز". */
    val toVerb: Map<String, String> = mapOf(
        "continue" to "للمتابعة", "jump" to "للقفز", "attack" to "للهجوم", "dodge" to "للمراوغة", "skip" to "للتخطي",
        "interact" to "للتفاعل", "talk" to "للتحدث", "open" to "للفتح", "pick up" to "للالتقاط", "climb" to "للتسلق",
        "sprint" to "للركض السريع", "crouch" to "للانحناء", "aim" to "للتصويب", "shoot" to "لإطلاق النار",
        "reload" to "لإعادة التلقيم", "start" to "للبدء", "begin" to "للبدء", "confirm" to "للتأكيد",
        "cancel" to "للإلغاء", "go back" to "للرجوع", "select" to "للاختيار", "use" to "للاستخدام",
        "paint" to "للرسم", "spin" to "للدوران", "spin attack" to "للهجوم الدوّار",
    )

    fun verb(rest: String): String? = toVerb[key(rest)]

    private fun keepPunct(en: String, ar: String): String {
        if (ar.endsWith("؟") || ar.endsWith("!")) return ar
        val t = en.trim()
        return when {
            t.endsWith("?") -> "$ar؟"
            t.endsWith("!") -> "$ar!"
            else -> ar
        }
    }
}
