package com.abdulmedia.inkora

import android.content.Context
import android.content.res.Configuration
import android.view.Menu
import java.util.Locale

/**
 * Interface language: English, Arabic or Sorani Kurdish.
 *
 * The app's texts are written in English in the code; [tr] looks them up here.
 * The shared helpers (Ui.text, Ui.button, HUD messages, menus, dialogs) call it,
 * so most of the interface follows the chosen language automatically.
 * Arabic and Kurdish also turn the layout right-to-left.
 */
object L10n {
    const val EN = "en"
    const val AR = "ar"
    const val CKB = "ckb"

    var lang = EN
        private set

    val isRtl get() = lang != EN

    /** the saved choice, or the device language the first time */
    fun load(ctx: Context): String {
        val p = ctx.getSharedPreferences("inkora", Context.MODE_PRIVATE)
        val saved = p.getString("lang", null)
        lang = saved ?: run {
            val sys = Locale.getDefault().language
            when {
                sys == "ar" -> AR
                sys == "ckb" || sys == "ku" -> CKB
                else -> EN
            }
        }
        return lang
    }

    fun save(ctx: Context, l: String) {
        ctx.getSharedPreferences("inkora", Context.MODE_PRIVATE).edit().putString("lang", l).apply()
        lang = l
    }

    /** a context whose resources use the chosen language (gives right-to-left layouts for ar / ckb) */
    fun wrap(base: Context): Context {
        val l = load(base)
        val locale = when (l) { AR -> Locale("ar"); CKB -> Locale("ckb", "IQ"); else -> Locale.ENGLISH }
        val cfg = Configuration(base.resources.configuration)
        cfg.setLocale(locale)
        cfg.setLayoutDirection(locale)
        return base.createConfigurationContext(cfg)
    }

    fun tr(s: String): String {
        if (lang == EN || s.isEmpty()) return s
        val idx = if (lang == AR) 0 else 1
        table[s]?.let { return it[idx] }
        // "Brush · Studio Ink", "Symmetry: Radial" → translate each part
        if (s.contains(" · ")) return s.split(" · ").joinToString(" · ") { tr(it) }
        val colon = s.indexOf(": ")
        if (colon > 0) {
            val head = table[s.substring(0, colon)]
            if (head != null) return head[idx] + ": " + tr(s.substring(colon + 2))
        }
        // "Opacity 80%", "Size 12 px"
        val m = Regex("^([^\\d]+?) ([\\d].*)$").find(s)
        if (m != null) table[m.groupValues[1]]?.let { return it[idx] + " " + m.groupValues[2] }
        return s
    }

    /** translates every item (and sub-menu) of a popup menu */
    fun menu(m: Menu) {
        if (lang == EN) return
        for (i in 0 until m.size()) {
            val it = m.getItem(i)
            it.title = tr(it.title?.toString() ?: "")
            it.subMenu?.let { sm -> menu(sm) }
        }
    }

    private val table: Map<String, Array<String>> by lazy {
        val m = HashMap<String, Array<String>>(T.size / 3 + 16)
        var i = 0
        while (i + 2 < T.size) { m[T[i]] = arrayOf(T[i + 1], T[i + 2]); i += 3 }
        m
    }

    // English, Arabic, Sorani Kurdish
    private val T = arrayOf(
        // ---- top bar, main tools ----
        "Creative", "إبداع", "داهێنان",
        "Editing", "تعديل", "دەستکاری",
        "Brush", "فرشاة", "فڵچە",
        "Smudge", "تمويه", "تێکەڵکردن",
        "Eraser", "ممحاة", "سڕەرەوە",
        "Layers", "الطبقات", "چینەکان",
        "Eyedropper", "قطّارة", "ڕەنگگر",
        "Undo", "تراجع", "گەڕانەوە",
        "Redo", "إعادة", "دووبارە",
        "SIZE", "الحجم", "قەبارە",
        "OPACITY", "الشفافية", "ناڕوونی",
        "Size", "الحجم", "قەبارە",
        "Opacity", "الشفافية", "ناڕوونی",
        "Color", "اللون", "ڕەنگ",
        "Gallery", "المعرض", "گەلەری",
        "Menu", "القائمة", "مێنیو",
        "Layer", "طبقة", "چین",
        "Done", "تم", "تەواو",
        "Cancel", "إلغاء", "پاشگەزبوونەوە",
        "OK", "حسناً", "باشە",
        "Close", "إغلاق", "داخستن",
        "Apply", "تطبيق", "جێبەجێکردن",
        "Reset", "إعادة ضبط", "ڕێکخستنەوە",
        "Delete", "حذف", "سڕینەوە",
        "Duplicate", "تكرار", "دووبارەکردنەوە",
        "Rename", "إعادة تسمية", "ناوگۆڕین",
        "Copy", "نسخ", "کۆپی",
        "Copied", "تم النسخ", "کۆپی کرا",
        "Share", "مشاركة", "هاوبەشکردن",
        "Download", "تحميل", "داگرتن",
        "Import", "استيراد", "هاوردەکردن",
        "Add", "إضافة", "زیادکردن",
        "Create", "إنشاء", "دروستکردن",
        "Name", "الاسم", "ناو",
        "Draw", "ارسم", "بکێشە",
        "ON", "تشغيل", "کارا",
        "Off", "إيقاف", "ناچالاک",
        "Left", "يسار", "چەپ",
        "Right", "يمين", "ڕاست",
        "Top", "أعلى", "سەرەوە",
        "Bottom", "أسفل", "خوارەوە",
        "Reference", "مرجع", "سەرچاوە",
        "Navigator", "المتصفّح", "ڕێنیشاندەر",
        "Lighting Orb", "كرة الإضاءة", "تۆپی ڕووناکی",
        "Brushes", "الفُرَش", "فڵچەکان",
        "Unpin ✕", "إلغاء التثبيت ✕", "لابردن ✕",

        // ---- toolbar tools ----
        "Freehand select", "تحديد حر", "هەڵبژاردنی ئازاد",
        "Rectangle select", "تحديد مستطيل", "هەڵبژاردنی چوارگۆشە",
        "Ellipse select", "تحديد بيضاوي", "هەڵبژاردنی هێلکەیی",
        "Select all", "تحديد الكل", "هەمووی هەڵبژێرە",
        "Invert selection", "عكس التحديد", "پێچەوانەکردنی هەڵبژاردن",
        "Deselect", "إلغاء التحديد", "لابردنی هەڵبژاردن",
        "Copy & paste", "نسخ ولصق", "کۆپی و دانان",
        "Transform", "تحويل", "گۆڕین",
        "Fill", "تعبئة", "پڕکردنەوە",
        "Text", "نص", "دەق",
        "Insert photo", "إدراج صورة", "دانانی وێنە",
        "Vector layer", "طبقة فيكتور", "چینی ڤێکتەر",
        "Grid & perspective", "شبكة ومنظور", "تۆڕ و پێرسپێکتیڤ",
        "Assisted drawing", "رسم مساعد", "وێنەکێشانی یارمەتیدراو",
        "QuickShape", "شكل سريع", "شێوەی خێرا",
        "Symmetry", "تناظر", "سیمێتری",
        "Adjustments", "تعديلات الألوان", "ڕێکخستنەکان",
        "Fit to screen", "ملاءمة للشاشة", "گونجاندن لەگەڵ شاشە",
        "Flip view", "قلب العرض", "هەڵگەڕاندنەوەی پیشاندان",
        "Reference image", "صورة مرجعية", "وێنەی سەرچاوە",
        "3D Lighting orb", "كرة إضاءة ثلاثية الأبعاد", "تۆپی ڕووناکی سێ ڕەهەندی",
        "Focus mode", "وضع التركيز", "دۆخی سەرنج",
        "Export PNG", "تصدير PNG", "هەناردەکردنی PNG",
        "Time-lapse video", "فيديو تايم لابس", "ڤیدیۆی تایم-لاپس",
        "Draw around an area", "ارسم حول منطقة", "بە دەوری ناوچەیەکدا بکێشە",
        "Drag a box", "اسحب مستطيلاً", "چوارگۆشەیەک ڕابکێشە",
        "Drag an oval", "اسحب شكلاً بيضاوياً", "هێلکەیەک ڕابکێشە",
        "The whole layer", "الطبقة كاملة", "هەموو چینەکە",
        "Swap inside / outside", "بدّل الداخل والخارج", "ناوەوە و دەرەوە بگۆڕە",
        "Remove the selection", "أزل التحديد", "هەڵبژاردنەکە لاببە",
        "Selection to a new layer", "التحديد إلى طبقة جديدة", "هەڵبژاردن بۆ چینێکی نوێ",
        "Move, scale, rotate, flip", "تحريك وتكبير وتدوير وقلب", "جووڵاندن، گەورەکردن، سووڕاندن، هەڵگەڕاندنەوە",
        "Pick a color from the canvas", "خذ لوناً من الرسمة", "ڕەنگێک لە تابلۆکە وەربگرە",
        "Tap areas to fill them", "انقر المناطق لتعبئتها", "کرتە لە ناوچەکان بکە بۆ پڕکردنەوە",
        "Any language, 9 fonts", "أي لغة، 9 خطوط", "هەر زمانێک، ٩ فۆنت",
        "As a new layer", "كطبقة جديدة", "وەک چینێکی نوێ",
        "Pen, shapes, logos", "قلم وأشكال وشعارات", "قەڵەم، شێوە، لۆگۆ",
        "All grid types", "كل أنواع الشبكات", "هەموو جۆرەکانی تۆڕ",
        "Strokes snap to the grid", "الخطوط تلتصق بالشبكة", "هێڵەکان بە تۆڕەکەوە دەلکێن",
        "Hold to make perfect shapes", "توقّف لصنع أشكال مثالية", "ڕابوەستە بۆ شێوەی تەواو",
        "Mirror your strokes", "اعكس خطوطك", "هێڵەکانت ئاوێنە بکە",
        "All adjustments & filters", "كل التعديلات والفلاتر", "هەموو ڕێکخستن و فلتەرەکان",
        "Center the canvas", "توسيط الرسمة", "تابلۆکە بخە ناوەڕاست",
        "Check your drawing mirrored", "افحص رسمتك معكوسة", "وێنەکەت بە پێچەوانە ببینە",
        "Floating reference window", "نافذة مرجع عائمة", "پەنجەرەی سەرچاوەی بزۆک",
        "Floating canvas thumbnail", "صورة مصغّرة عائمة للرسمة", "وێنۆچکەی بزۆکی تابلۆ",
        "3D reference sphere for light and shade", "كرة مرجعية للضوء والظل", "تۆپی سەرچاوە بۆ ڕووناکی و سێبەر",
        "Hide the interface", "إخفاء الواجهة", "شاردنەوەی ڕووکار",
        "Save to Pictures/Inkora", "حفظ في Pictures/Inkora", "پاشەکەوت لە Pictures/Inkora",
        "Send the artwork", "أرسل الرسمة", "کارەکە بنێرە",
        "Export the recording", "صدّر التسجيل", "تۆمارەکە هەناردە بکە",
        "Selection", "التحديد", "هەڵبژاردن",
        "Create", "إنشاء", "دروستکردن",
        "Guides", "الأدلة", "ڕێنماکان",
        "Adjust", "تعديل", "ڕێکخستن",
        "View", "العرض", "پیشاندان",
        "File", "ملف", "فایل",

        // ---- Creative / Editing panel ----
        "Add new things to your artwork", "أضف أشياء جديدة إلى رسمتك", "شتی نوێ زیاد بکە بۆ کارەکەت",
        "Grids and perspective help you draw straight", "الشبكات والمنظور تساعدك على الرسم المستقيم", "تۆڕ و پێرسپێکتیڤ یارمەتیت دەدەن ڕێک بکێشیت",
        "Shapes", "الأشكال", "شێوەکان",
        "Perfect lines, circles and vector shapes", "خطوط ودوائر مثالية وأشكال فيكتور", "هێڵ و بازنەی تەواو و شێوەی ڤێکتەر",
        "Every stroke is mirrored live", "كل خط ينعكس مباشرة", "هەموو هێڵێک ڕاستەوخۆ ئاوێنە دەبێت",
        "See your canvas differently", "شاهد رسمتك بطريقة أخرى", "تابلۆکەت بە شێوەیەکی تر ببینە",
        "Select", "تحديد", "هەڵبژاردن",
        "Pick a part of the layer to work on", "اختر جزءاً من الطبقة للعمل عليه", "بەشێک لە چینەکە هەڵبژێرە بۆ کارکردن",
        "Move, scale, rotate and flip", "تحريك وتكبير وتدوير وقلب", "جووڵاندن، گەورەکردن، سووڕاندن و هەڵگەڕاندنەوە",
        "Color and tone, with a live preview", "اللون والدرجات مع معاينة مباشرة", "ڕەنگ و تۆن، بە پێشبینینی ڕاستەوخۆ",
        "Filters", "الفلاتر", "فلتەرەکان",
        "Blur, sharpen and texture", "تمويه وحدّة وملمس", "لێڵکردن، تیژکردن و ڕووکار",
        "Tip: drag the color circle (top right) onto an area to fill it with ColorDrop.", "نصيحة: اسحب دائرة اللون (في الأعلى) فوق منطقة لتعبئتها.", "ئامۆژگاری: بازنەی ڕەنگ (لە سەرەوە) ڕابکێشە سەر ناوچەیەک بۆ پڕکردنەوەی.",
        "Guide type", "نوع الدليل", "جۆری ڕێنما",
        "Your strokes snap to the guide", "خطوطك تلتصق بالدليل", "هێڵەکانت بە ڕێنماکەوە دەلکێن",
        "Size, color & vanishing points…", "الحجم واللون ونقاط التلاشي…", "قەبارە، ڕەنگ و خاڵەکانی نەمان…",
        "Hold the pen still at the end of a stroke", "أبقِ القلم ثابتاً في نهاية الخط", "قەڵەمەکە لە کۆتایی هێڵەکەدا ڕابگرە",
        "Vector", "فيكتور", "ڤێکتەر",
        "Pen & shapes", "القلم والأشكال", "قەڵەم و شێوەکان",
        "Bézier pen, rectangles, stars…", "قلم Bézier ومستطيلات ونجوم…", "قەڵەمی Bézier، چوارگۆشە، ئەستێرە…",
        "Normal drawing", "رسم عادي", "وێنەکێشانی ئاسایی",
        "Left ⇄ right", "يسار ⇄ يمين", "چەپ ⇄ ڕاست",
        "Top ⇄ bottom", "أعلى ⇄ أسفل", "سەرەوە ⇄ خوارەوە",
        "Four ways", "أربعة اتجاهات", "چوار لا",
        "Mandala, 8 ways", "ماندالا، 8 اتجاهات", "ماندالا، ٨ لا",
        "Mandala, 12 ways", "ماندالا، 12 اتجاهاً", "ماندالا، ١٢ لا",
        "Mandala, 16 ways", "ماندالا، 16 اتجاهاً", "ماندالا، ١٦ لا",
        "Symmetry in your toolbar", "التناظر في شريط أدواتك", "سیمێتری لە شریتی ئامرازەکانت",
        "One tap to turn it on or off", "لمسة واحدة لتشغيله أو إيقافه", "یەک کرتە بۆ کارا یان ناچالاک کردن",
        "Selection mode", "نوع التحديد", "جۆری هەڵبژاردن",
        "Actions", "إجراءات", "کردارەکان",
        "Feather (soft edge)", "تنعيم الحواف", "نەرمکردنی لێوار",
        "Adjustments work on drawing layers. Pick a drawing layer first.", "التعديلات تعمل على طبقات الرسم. اختر طبقة رسم أولاً.", "ڕێکخستنەکان لەسەر چینی وێنەکێشان کار دەکەن. سەرەتا چینێکی وێنەکێشان هەڵبژێرە.",
        "Only the selected area will change.", "ستتغيّر المنطقة المحددة فقط.", "تەنها ناوچە هەڵبژێردراوەکە دەگۆڕێت.",
        "One tap", "لمسة واحدة", "یەک کرتە",
        "Off", "إيقاف", "ناچالاک",
        "Vertical", "عمودي", "ستوونی",
        "Horizontal", "أفقي", "ئاسۆیی",
        "Quadrant", "رباعي", "چوارلایی",
        "Radial (8)", "شعاعي (8)", "تیشکی (٨)",
        "Mandala (12)", "ماندالا (12)", "ماندالا (١٢)",
        "Mandala (16)", "ماندالا (16)", "ماندالا (١٦)",
        "2D Grid", "شبكة مربعات", "تۆڕی چوارگۆشە",
        "Dot Grid", "شبكة نقاط", "تۆڕی خاڵ",
        "Isometric", "أيزومتري", "ئایزۆمەتریک",
        "Triangle Grid", "شبكة مثلثات", "تۆڕی سێگۆشە",
        "Hexagon Grid", "شبكة سداسية", "تۆڕی شەشگۆشە",
        "Polar (circles)", "دوائر قطبية", "بازنەی جەمسەری",
        "Rule of Thirds", "قاعدة الأثلاث", "یاسای سێیەکان",
        "Circle Ruler", "مسطرة دائرية", "ڕاستەی بازنەیی",
        "1-Point persp.", "منظور بنقطة", "پێرسپێکتیڤی ١ خاڵ",
        "2-Point persp.", "منظور بنقطتين", "پێرسپێکتیڤی ٢ خاڵ",
        "3-Point persp.", "منظور بثلاث نقاط", "پێرسپێکتیڤی ٣ خاڵ",
        "1-Point Perspective", "منظور بنقطة", "پێرسپێکتیڤی ١ خاڵ",
        "2-Point Perspective", "منظور بنقطتين", "پێرسپێکتیڤی ٢ خاڵ",
        "3-Point Perspective", "منظور بثلاث نقاط", "پێرسپێکتیڤی ٣ خاڵ",

        // ---- adjustments ----
        "Hue / Saturation / Brightness", "تدرّج اللون / التشبّع / السطوع", "ڕەنگ / تێری / ڕووناکی",
        "Brightness / Contrast", "السطوع / التباين", "ڕووناکی / کۆنتراست",
        "Color Balance", "توازن الألوان", "هاوسەنگی ڕەنگ",
        "Gaussian Blur", "تمويه غاوسي", "لێڵکردنی گاوسی",
        "Sharpen", "زيادة الحدّة", "تیژکردن",
        "Noise", "تحبيب", "خاڵخاڵی",
        "Posterize", "تقليل الألوان", "کەمکردنەوەی ڕەنگ",
        "Invert Colors", "عكس الألوان", "پێچەوانەکردنی ڕەنگ",
        "Black & White", "أبيض وأسود", "ڕەش و سپی",
        "Hue", "تدرّج اللون", "ڕەنگ",
        "Saturation", "التشبّع", "تێری",
        "Brightness", "السطوع", "ڕووناکی",
        "Contrast", "التباين", "کۆنتراست",
        "Radius", "نصف القطر", "نیوەتیرە",
        "Amount", "المقدار", "بڕ",
        "Levels", "المستويات", "ئاستەکان",
        "Working…", "جارٍ العمل…", "کار دەکات…",
        "(selection only)", "(التحديد فقط)", "(تەنها هەڵبژاردن)",

        // ---- toolbar editor ----
        "Customize toolbar", "تخصيص شريط الأدوات", "ڕێکخستنی شریتی ئامراز",
        "Your most used tools, always one tap away", "أدواتك الأكثر استخداماً، دائماً على بعد لمسة", "ئامرازە بەکارهاتووەکانت، هەمیشە بە یەک کرتە",
        "Empty — add tools below", "فارغ — أضف أدوات من الأسفل", "بەتاڵە — ئامراز لە خوارەوە زیاد بکە",
        "Add tools", "أضف أدوات", "ئامراز زیاد بکە",
        "Layout", "التخطيط", "ڕیزبەندی",
        "Left-handed layout", "وضع اليد اليسرى", "دۆخی دەستەچەپ",
        "Sliders on the right, toolbar on the left", "المنزلقات يميناً وشريط الأدوات يساراً", "سلایدەرەکان لە ڕاست، شریت لە چەپ",
        "Reset to default tools", "استعادة الأدوات الافتراضية", "گەڕانەوە بۆ ئامرازە بنەڕەتییەکان",
        "Removed from your toolbar", "أُزيلت من شريط أدواتك", "لە شریتەکەت لابرا",
        "Added to your toolbar", "أُضيفت إلى شريط أدواتك", "بۆ شریتەکەت زیاد کرا",
        "Add to toolbar", "أضف إلى الشريط", "زیادکردن بۆ شریت",

        // ---- settings ----
        "Settings", "الإعدادات", "ڕێکخستنەکان",
        "Settings & Preferences…", "الإعدادات والتفضيلات…", "ڕێکخستن و هەڵبژاردەکان…",
        "Canvas & Project", "اللوحة والمشروع", "تابلۆ و پڕۆژە",
        "Stylus & S Pen", "القلم وS Pen", "قەڵەم و S Pen",
        "Interface & Layout", "الواجهة والتخطيط", "ڕووکار و ڕیزبەندی",
        "Gestures & Touch", "الإيماءات واللمس", "جووڵە و دەستلێدان",
        "About & Hotkeys", "حول التطبيق", "دەربارە",
        "Document & Fill Defaults", "إعدادات المستند والتعبئة", "ڕێکخستنی بەڵگەنامە و پڕکردنەوە",
        "Configure document properties, fill tolerance and time-lapse", "خصائص المستند وحساسية التعبئة والتايم لابس", "تایبەتمەندی بەڵگەنامە، هەستیاری پڕکردنەوە و تایم-لاپس",
        "ColorDrop Fill Sensitivity", "حساسية التعبئة", "هەستیاری پڕکردنەوە",
        "Time-lapse Recording", "تسجيل التايم لابس", "تۆمارکردنی تایم-لاپس",
        "S Pen Pressure Response Curve", "منحنى ضغط القلم", "چەمانەی پەستانی قەڵەم",
        "Select preset pressure curve for soft or firm stroke feel", "اختر إحساساً ناعماً أو قاسياً للضغط", "هەستێکی نەرم یان ڕەق بۆ پەستان هەڵبژێرە",
        "Soft", "ناعم", "نەرم",
        "Normal", "عادي", "ئاسایی",
        "Firm / Hard", "قاسٍ", "ڕەق",
        "Fine-tune pressure", "ضبط دقيق للضغط", "ڕێکخستنی ورد بۆ پەستان",
        "S Pen Button Hold Duration", "مدة الضغط على زر القلم", "ماوەی ڕاگرتنی دوگمەی قەڵەم",
        "Toolbar & Sliders Placement", "مكان شريط الأدوات والمنزلقات", "شوێنی شریت و سلایدەرەکان",
        "Toolbar on any edge, sliders on the left or right", "الشريط على أي حافة، والمنزلقات يميناً أو يساراً", "شریت لە هەر لایەک، سلایدەر لە چەپ یان ڕاست",
        "Toolbar Edge", "حافة شريط الأدوات", "لای شریت",
        "Sliders Edge", "حافة المنزلقات", "لای سلایدەرەکان",
        "Left-Handed Mode", "وضع اليد اليسرى", "دۆخی دەستەچەپ",
        "Language", "اللغة", "زمان",
        "The app restarts in the new language", "سيُعاد تشغيل التطبيق باللغة الجديدة", "ئەپەکە بە زمانە نوێیەکە دەست پێدەکاتەوە",
        "Touch & Multi-Finger Gestures", "اللمس وإيماءات الأصابع", "دەستلێدان و جووڵەی پەنجەکان",
        "Draw with Finger", "الرسم بالإصبع", "وێنەکێشان بە پەنجە",
        "QuickShape (hold at the end of a stroke)", "الشكل السريع (توقّف في نهاية الخط)", "شێوەی خێرا (لە کۆتایی هێڵدا ڕابوەستە)",
        "• 2-Finger Tap: Undo last action", "• نقرة بإصبعين: تراجع", "• کرتە بە دوو پەنجە: گەڕانەوە",
        "• 3-Finger Tap: Redo action", "• نقرة بثلاث أصابع: إعادة", "• کرتە بە سێ پەنجە: دووبارە",
        "• 2-Finger Pinch / Rotate: Zoom and rotate canvas", "• قرص وتدوير بإصبعين: تكبير وتدوير الرسمة", "• گوشین و سووڕاندن بە دوو پەنجە: گەورەکردن و سووڕاندنی تابلۆ",
        "Designed for Samsung Galaxy Tab S11 Ultra & S Pen", "مصمَّم لـ Galaxy Tab S11 Ultra وقلم S Pen", "بۆ Galaxy Tab S11 Ultra و S Pen دیزاین کراوە",
        "S Pen shortcuts:", "اختصارات القلم:", "کورتبڕەکانی قەڵەم:",
        "Show the tips again", "اعرض النصائح مرة أخرى", "ئامۆژگارییەکان دووبارە پیشان بدە",

        // ---- top menu ----
        "New canvas…", "لوحة جديدة…", "تابلۆی نوێ…",
        "Rename artwork…", "إعادة تسمية الرسمة…", "ناوگۆڕینی کارەکە…",
        "Open project (.inkora)…", "فتح مشروع (.inkora)…", "کردنەوەی پڕۆژە (.inkora)…",
        "Save project (.inkora)…", "حفظ المشروع (.inkora)…", "پاشەکەوتی پڕۆژە (.inkora)…",
        "Insert photo as layer…", "إدراج صورة كطبقة…", "دانانی وێنە وەک چین…",
        "Add text…", "إضافة نص…", "زیادکردنی دەق…",
        "Export & Share", "تصدير ومشاركة", "هەناردە و هاوبەشکردن",
        "PNG to Gallery", "PNG إلى المعرض", "PNG بۆ گەلەری",
        "JPEG to Gallery", "JPEG إلى المعرض", "JPEG بۆ گەلەری",
        "SVG (vector)…", "SVG (فيكتور)…", "SVG (ڤێکتەر)…",
        "Share PNG…", "مشاركة PNG…", "هاوبەشکردنی PNG…",
        "Time-lapse", "تايم لابس", "تایم-لاپس",
        "Export video…", "تصدير فيديو…", "هەناردەکردنی ڤیدیۆ…",
        "Recording", "التسجيل", "تۆمارکردن",
        "Import brushes…", "استيراد فُرَش…", "هاوردەکردنی فڵچە…",
        "Pin brush library", "تثبيت مكتبة الفُرَش", "جێگیرکردنی کتێبخانەی فڵچە",
        "Pin layers", "تثبيت الطبقات", "جێگیرکردنی چینەکان",
        "Fit canvas to screen", "ملاءمة الرسمة للشاشة", "گونجاندنی تابلۆ لەگەڵ شاشە",
        "Flip canvas horizontally", "قلب الرسمة أفقياً", "هەڵگەڕاندنەوەی ئاسۆیی تابلۆ",
        "Help & gestures", "المساعدة والإيماءات", "یارمەتی و جووڵەکان",
        "Gestures", "الإيماءات", "جووڵەکان",

        // ---- canvas / dialogs ----
        "New canvas (current artwork will be replaced)", "لوحة جديدة", "تابلۆی نوێ",
        "Custom size…", "حجم مخصص…", "قەبارەی تایبەت…",
        "Custom size (pixels)", "حجم مخصص (بكسل)", "قەبارەی تایبەت (پیکسڵ)",
        "Width", "العرض", "پانی",
        "Height", "الارتفاع", "بەرزی",
        "Size must be 64–8192 px per side and at most 24 megapixels", "يجب أن يكون الحجم بين 64 و8192 بكسل وبحد أقصى 24 ميغابكسل", "قەبارە دەبێت لە نێوان ٦٤ بۆ ٨١٩٢ پیکسڵ بێت و زۆرترین ٢٤ مێگاپیکسڵ",
        "Exporting…", "جارٍ التصدير…", "هەناردە دەکرێت…",
        "Export failed", "فشل التصدير", "هەناردەکردن سەرکەوتوو نەبوو",
        "Saved to Pictures/Inkora", "حُفظت في Pictures/Inkora", "لە Pictures/Inkora پاشەکەوت کرا",
        "Saving…", "جارٍ الحفظ…", "پاشەکەوت دەکرێت…",
        "Project saved", "تم حفظ المشروع", "پڕۆژە پاشەکەوت کرا",
        "Could not save the project", "تعذّر حفظ المشروع", "پاشەکەوتکردنی پڕۆژە سەرکەوتوو نەبوو",
        "This is not an Inkora project file", "هذا ليس ملف مشروع Inkora", "ئەمە فایلی پڕۆژەی Inkora نییە",
        "Project opened", "تم فتح المشروع", "پڕۆژە کرایەوە",
        "Photo opened as a new artwork", "فُتحت الصورة كرسمة جديدة", "وێنەکە وەک کارێکی نوێ کرایەوە",
        "Photo added as a new layer", "أُضيفت الصورة كطبقة جديدة", "وێنەکە وەک چینێکی نوێ زیاد کرا",
        "Could not open that image", "تعذّر فتح الصورة", "کردنەوەی وێنەکە سەرکەوتوو نەبوو",
        "Could not open this artwork", "تعذّر فتح هذه الرسمة", "کردنەوەی ئەم کارە سەرکەوتوو نەبوو",
        "The artwork and its time-lapse will be deleted. This can't be undone.", "ستُحذف الرسمة والتايم لابس الخاص بها. لا يمكن التراجع عن ذلك.", "کارەکە و تایم-لاپسەکەی دەسڕدرێنەوە. ناتوانرێت بگەڕێنرێتەوە.",
        "Nothing recorded yet for this artwork. Draw a bit first!", "لم يُسجَّل شيء بعد. ارسم قليلاً أولاً!", "هێشتا هیچ تۆمار نەکراوە. سەرەتا کەمێک بکێشە!",
        "Could not make the video on this device", "تعذّر إنشاء الفيديو على هذا الجهاز", "دروستکردنی ڤیدیۆ لەسەر ئەم ئامێرە سەرکەوتوو نەبوو",
        "Time-lapse saved", "تم حفظ التايم لابس", "تایم-لاپس پاشەکەوت کرا",
        "Making video…", "جارٍ إنشاء الفيديو…", "ڤیدیۆ دروست دەکرێت…",
        "Add text", "إضافة نص", "زیادکردنی دەق",
        "Type your text (any language)", "اكتب نصك (بأي لغة)", "دەقەکەت بنووسە (بە هەر زمانێک)",
        "Font", "الخط", "فۆنت",
        "Align", "المحاذاة", "ڕیزکردن",
        "Center", "وسط", "ناوەڕاست",
        "Start", "بداية", "سەرەتا",
        "End", "نهاية", "کۆتایی",
        "Photo as a new artwork…", "صورة كرسمة جديدة…", "وێنە وەک کارێکی نوێ…",
        "Inkora project (.inkora)…", "مشروع Inkora ‏(.inkora)…", "پڕۆژەی Inkora (.inkora)…",
        "Inkora closed unexpectedly last time", "أُغلق Inkora بشكل مفاجئ في المرة السابقة", "Inkora جاری پێشوو لەناکاو داخرا",
        "Tap Copy and send this text to the developer so it can be fixed.", "اضغط نسخ وأرسل هذا النص للمطوّر ليتم إصلاحه.", "کرتە لە کۆپی بکە و ئەم دەقە بۆ گەشەپێدەر بنێرە بۆ چاککردنەوە.",
        "Import brushes from a link", "استيراد فُرَش من رابط", "هاوردەکردنی فڵچە لە بەستەرێکەوە",
        "Paste a direct download link. Google Drive and Dropbox share links work too.", "الصق رابط تحميل مباشر. روابط Google Drive وDropbox تعمل أيضاً.", "بەستەرێکی داگرتنی ڕاستەوخۆ دابنێ. بەستەرەکانی Google Drive و Dropbox ـیش کار دەکەن.",
        "That doesn't look like a link", "هذا لا يبدو رابطاً", "ئەمە وەک بەستەر نییە",
        "Downloading…", "جارٍ التحميل…", "دادەگیرێت…",
        "Importing brushes…", "جارٍ استيراد الفُرَش…", "فڵچەکان هاوردە دەکرێن…",
        "Open library", "فتح المكتبة", "کردنەوەی کتێبخانە",
        "This removes the imported brush from Inkora.", "سيؤدي هذا لحذف الفرشاة المستوردة من Inkora.", "ئەمە فڵچە هاوردەکراوەکە لە Inkora لادەبات.",
        "Drag the numbered points", "اسحب النقاط المرقّمة", "خاڵە ژمارەدارەکان ڕابکێشە",
        "Done moving points", "انتهيت من تحريك النقاط", "جووڵاندنی خاڵەکان تەواو بوو",

        // ---- HUD messages ----
        "Focus mode · tap the corner button to bring the tools back", "وضع التركيز · اضغط زر الزاوية لإعادة الأدوات", "دۆخی سەرنج · کرتە لە دوگمەی گۆشە بکە بۆ گەڕانەوەی ئامرازەکان",
        "Nothing selected yet", "لا يوجد تحديد بعد", "هێشتا هیچ هەڵنەبژێردراوە",
        "Select something first", "حدّد شيئاً أولاً", "سەرەتا شتێک هەڵبژێرە",
        "Fill: tap an area to fill it with your color", "التعبئة: انقر منطقة لتعبئتها بلونك", "پڕکردنەوە: کرتە لە ناوچەیەک بکە بۆ پڕکردنەوەی بە ڕەنگەکەت",
        "Fill off", "إيقاف التعبئة", "پڕکردنەوە ناچالاک",
        "Choose a grid first (Grid & perspective)", "اختر شبكة أولاً (شبكة ومنظور)", "سەرەتا تۆڕێک هەڵبژێرە (تۆڕ و پێرسپێکتیڤ)",
        "Free drawing", "رسم حر", "وێنەکێشانی ئازاد",
        "QuickShape on: hold the pen at the end of a stroke", "الشكل السريع يعمل: توقّف بالقلم في نهاية الخط", "شێوەی خێرا کارایە: قەڵەم لە کۆتایی هێڵدا ڕابگرە",
        "QuickShape off", "الشكل السريع متوقف", "شێوەی خێرا ناچالاکە",
        "Feathered", "تم تنعيم الحواف", "لێوار نەرم کرا",
        "Color saved to your palette", "حُفظ اللون في لوحتك", "ڕەنگەکە لە پالێتەکەت پاشەکەوت کرا",
        "This is a vector layer — pick a drawing layer to paint", "هذه طبقة فيكتور — اختر طبقة رسم للتلوين", "ئەمە چینی ڤێکتەرە — چینێکی وێنەکێشان هەڵبژێرە",
        "Tap Done or Cancel first", "اضغط تم أو إلغاء أولاً", "سەرەتا کرتە لە تەواو یان پاشگەزبوونەوە بکە",
        "Color picked from reference", "تم أخذ اللون من المرجع", "ڕەنگ لە سەرچاوەکە وەرگیرا",
        "Pinned · drag it by its top strip", "مثبّتة · اسحبها من شريطها العلوي", "جێگیر کرا · لە شریتی سەرەوەی ڕایبکێشە",
        "Touch the canvas to pick a color", "المس الرسمة لأخذ لون", "دەست لە تابلۆکە بدە بۆ وەرگرتنی ڕەنگ",
        "Pen detected: fingers now zoom & pan", "تم اكتشاف القلم: الأصابع للتكبير والتحريك الآن", "قەڵەم ناسرایەوە: ئێستا پەنجەکان بۆ زووم و جووڵاندنن",
        "This layer is hidden", "هذه الطبقة مخفية", "ئەم چینە شاراوەیە",
        "This layer is empty", "هذه الطبقة فارغة", "ئەم چینە بەتاڵە",
        "Layer limit reached", "وصلت للحد الأقصى من الطبقات", "گەیشتیتە سنووری چینەکان",
        "Layer limit reached for this canvas size", "وصلت للحد الأقصى من الطبقات لهذا الحجم", "گەیشتیتە سنووری چینەکان بۆ ئەم قەبارەیە",
        "Copied to a new layer", "نُسخ إلى طبقة جديدة", "بۆ چینێکی نوێ کۆپی کرا",
        "Finish or cancel the current edit first", "أنهِ التعديل الحالي أو ألغِه أولاً", "سەرەتا دەستکارییەکەی ئێستا تەواو بکە یان هەڵیبوەشێنەوە",
        "No shape recognised — try again a bit more carefully", "لم يُتعرّف على الشكل — حاول بدقة أكبر", "هیچ شێوەیەک نەناسرایەوە — بە وردی زیاتر هەوڵ بدەرەوە",
        "Draw around what you want to select", "ارسم حول ما تريد تحديده", "بە دەوری ئەوەدا بکێشە کە دەتەوێت هەڵیبژێریت",
        "Drag to move · corners to scale · green dot to rotate · two fingers to pinch", "اسحب للتحريك · الزوايا للتكبير · النقطة الخضراء للتدوير · إصبعان للقرص", "ڕابکێشە بۆ جووڵاندن · گۆشەکان بۆ گەورەکردن · خاڵی سەوز بۆ سووڕاندن · دوو پەنجە بۆ گوشین",
        "On a vector layer use the vector Select tool", "على طبقة فيكتور استخدم أداة تحديد الفيكتور", "لەسەر چینی ڤێکتەر ئامرازی هەڵبژاردنی ڤێکتەر بەکاربهێنە",
        "Vector layer: draw with the Pen, shapes or Pencil", "طبقة فيكتور: ارسم بالقلم أو الأشكال أو قلم الرصاص", "چینی ڤێکتەر: بە قەڵەم، شێوە یان قەڵەمڕەساس بکێشە",
        "Vector layer: use the vector tools, or pick a drawing layer", "طبقة فيكتور: استخدم أدوات الفيكتور أو اختر طبقة رسم", "چینی ڤێکتەر: ئامرازەکانی ڤێکتەر بەکاربهێنە یان چینی وێنەکێشان هەڵبژێرە",
        "Nothing to undo", "لا شيء للتراجع عنه", "هیچ شتێک نییە بۆ گەڕانەوە",
        "Nothing to redo", "لا شيء لإعادته", "هیچ شتێک نییە بۆ دووبارەکردنەوە",
        "drawing", "رسم", "وێنەکێشان",
        "new layer", "طبقة جديدة", "چینی نوێ",
        "deleted layer", "حذف طبقة", "سڕینەوەی چین",
        "layer order", "ترتيب الطبقات", "ڕیزبەندی چین",
        "merge", "دمج", "یەکخستن",
        "shape edit", "تعديل شكل", "دەستکاری شێوە",
        "rasterize", "تحويل لرسم", "گۆڕین بۆ وێنە",

        // ---- selection / transform bars ----
        "Freehand", "حر", "ئازاد",
        "Rectangle", "مستطيل", "چوارگۆشە",
        "Ellipse", "بيضاوي", "هێلکەیی",
        "Invert", "عكس", "پێچەوانە",
        "Copy & Paste", "نسخ ولصق", "کۆپی و دانان",
        "Clear", "مسح", "پاککردنەوە",
        "Feather", "تنعيم", "نەرمکردن",
        "Flip ↔", "قلب ↔", "هەڵگەڕاندنەوە ↔",
        "Flip ↕", "قلب ↕", "هەڵگەڕاندنەوە ↕",
        "Rotate 90°", "تدوير 90°", "سووڕاندن ٩٠°",
        "Fit to canvas", "ملاءمة للرسمة", "گونجاندن لەگەڵ تابلۆ",

        // ---- vector bar ----
        "Style…", "النمط…", "شێواز…",
        "Shape options…", "خيارات الشكل…", "هەڵبژاردەکانی شێوە…",
        "Shape options", "خيارات الشكل", "هەڵبژاردەکانی شێوە",
        "Finish path", "إنهاء المسار", "تەواوکردنی ڕێڕەو",
        "Smooth ⇄ Corner", "ناعم ⇄ زاوية", "نەرم ⇄ گۆشە",
        "Delete point", "حذف نقطة", "سڕینەوەی خاڵ",
        "Constrain", "تقييد", "سنووردارکردن",
        "Multi-select", "تحديد متعدد", "هەڵبژاردنی زۆر",
        "Combine ▾", "دمج ▾", "یەکخستن ▾",
        "Arrange ▾", "ترتيب ▾", "ڕیزکردن ▾",
        "Align ▾", "محاذاة ▾", "ڕێکخستن ▾",
        "Style", "النمط", "شێواز",
        "For new shapes", "للأشكال الجديدة", "بۆ شێوە نوێیەکان",
        "Swap", "تبديل", "گۆڕینەوە",
        "Line ends", "أطراف الخط", "کۆتایی هێڵ",
        "Flat", "مسطح", "تەخت",
        "Round", "دائري", "خڕ",
        "Square", "مربع", "چوارگۆشە",
        "Corners", "الزوايا", "گۆشەکان",
        "Sharp", "حاد", "تیژ",
        "Bevel", "مشطوف", "لار",
        "Solid line", "خط متصل", "هێڵی تەواو",
        "Dashed", "متقطع", "پچڕپچڕ",
        "Rectangle corner radius", "تدوير زوايا المستطيل", "خڕی گۆشەی چوارگۆشە",
        "Polygon sides", "أضلاع المضلع", "لاکانی فرەلا",
        "Star points", "رؤوس النجمة", "سەرەکانی ئەستێرە",
        "Drag to draw the shape", "اسحب لرسم الشكل", "ڕابکێشە بۆ کێشانی شێوە",
        "Point added", "أُضيفت نقطة", "خاڵ زیاد کرا",
        "Tap a point first", "انقر نقطة أولاً", "سەرەتا کرتە لە خاڵێک بکە",
        "Nothing selected", "لا يوجد تحديد", "هیچ هەڵنەبژێردراوە",

        // ---- layers panel ----
        "Drawing layer", "طبقة رسم", "چینی وێنەکێشان",
        "Vector layer (pen, shapes, logos)", "طبقة فيكتور (قلم، أشكال، شعارات)", "چینی ڤێکتەر (قەڵەم، شێوە، لۆگۆ)",
        "Background color", "لون الخلفية", "ڕەنگی باکگراوند",
        "Tap to use the current color", "انقر لاستخدام اللون الحالي", "کرتە بکە بۆ بەکارهێنانی ڕەنگی ئێستا",
        "Alpha Lock", "قفل الشفافية", "قوفڵی ڕوونی",
        "Fill with color", "تعبئة باللون", "پڕکردنەوە بە ڕەنگ",
        "Nothing below to merge into", "لا توجد طبقة أسفلها للدمج", "هیچ چینێک لە خوارەوە نییە بۆ یەکخستن",
        "A canvas needs at least one layer", "الرسمة تحتاج طبقة واحدة على الأقل", "تابلۆ لانیکەم یەک چینی پێویستە",
        "Now a drawing layer", "أصبحت طبقة رسم", "ئێستا چینی وێنەکێشانە",
        "Rename layer", "إعادة تسمية الطبقة", "ناوگۆڕینی چین",
        "Clipping Mask", "قناع القص", "ماسکی بڕین",
        "Clipping Mask: ON", "قناع القص: يعمل", "ماسکی بڕین: کارا",
        "Clipping Mask: off", "قناع القص: متوقف", "ماسکی بڕین: ناچالاک",
        "Alpha Lock: ON", "قفل الشفافية: يعمل", "قوفڵی ڕوونی: کارا",
        "Alpha Lock: off", "قفل الشفافية: متوقف", "قوفڵی ڕوونی: ناچالاک",
        "Merge down", "دمج للأسفل", "یەکخستن لەگەڵ خوارەوە",
        "Thickness", "السماكة", "ئەستووری",

        // ---- brush library ----
        "Brush Library", "مكتبة الفُرَش", "کتێبخانەی فڵچە",
        "Erase with…", "امسح بـ…", "بسڕەوە بە…",
        "Smudge with…", "موّه بـ…", "تێکەڵ بکە بە…",
        "Brush Studio", "استوديو الفرشاة", "ستۆدیۆی فڵچە",
        "‹ Library", "‹ المكتبة", "‹ کتێبخانە",
        "Edit brush", "تعديل الفرشاة", "دەستکاری فڵچە",
        "Import file…", "استيراد ملف…", "هاوردەکردنی فایل…",
        "Import from link…", "استيراد من رابط…", "هاوردە لە بەستەر…",
        "Expand all", "فتح الكل", "هەمووی بکەرەوە",
        "Collapse all", "طي الكل", "هەمووی دابخە",
        "StreamLine (smoothing)", "تنعيم الخط", "نەرمکردنی هێڵ",
        "Spacing", "التباعد", "مەودا",
        "Scatter", "التبعثر", "پەرشبوون",
        "Flow", "التدفق", "ڕۆیشتن",
        "Pressure → Size", "الضغط ← الحجم", "پەستان ← قەبارە",
        "Pressure → Opacity", "الضغط ← الشفافية", "پەستان ← ناڕوونی",
        "Tip rotation", "دوران الرأس", "سووڕانی سەر",
        "Angle", "الزاوية", "گۆشە",
        "Texture (grain): ON", "الملمس: يعمل", "ڕووکار: کارا",
        "Texture (grain): off", "الملمس: متوقف", "ڕووکار: ناچالاک",
        "Texture scale", "حجم الملمس", "قەبارەی ڕووکار",
        "Reset brush settings", "استعادة إعدادات الفرشاة", "گەڕاندنەوەی ڕێکخستنی فڵچە",
        "Imported", "مستوردة", "هاوردەکراو",

        // ---- color ----
        "Swatch wheel", "دولاب العيّنات", "چەرخی نموونە",
        "Hex  #", "كود  #", "کۆد  #",
        "Palette", "اللوحة", "پالێت",
        "Recent", "الأخيرة", "دوایینەکان",
        "Harmony", "التناسق", "هاوئاهەنگی",
        "None", "بلا", "هیچ",
        "Complementary", "متكامل", "تەواوکەر",
        "Triadic", "ثلاثي", "سێیانە",
        "Analogous", "متشابه", "هاوشێوە",
        "Split-Comp", "متكامل مقسوم", "تەواوکەری دابەشکراو",
        "Tetradic", "رباعي", "چوارانە",
        "Save color to palette", "احفظ اللون في اللوحة", "ڕەنگەکە لە پالێت پاشەکەوت بکە",

        // ---- guide panel ----
        "Drawing Guide", "دليل الرسم", "ڕێنمای وێنەکێشان",
        "Grids and perspective, saved with this canvas", "شبكات ومنظور، تُحفظ مع هذه الرسمة", "تۆڕ و پێرسپێکتیڤ، لەگەڵ ئەم تابلۆیە پاشەکەوت دەکرێن",
        "Line spacing", "تباعد الخطوط", "مەودای هێڵەکان",
        "Grid size", "حجم الشبكة", "قەبارەی تۆڕ",
        "Guide color", "لون الدليل", "ڕەنگی ڕێنما",
        "Move vanishing points", "تحريك نقاط التلاشي", "جووڵاندنی خاڵەکانی نەمان",

        // ---- reference / orb ----
        "Flip", "قلب", "هەڵگەڕاندنەوە",
        "Fit", "ملاءمة", "گونجاندن",
        "Top-L", "أعلى يسار", "سەرەوەی چەپ",
        "Rim", "حافة", "لێوار",
        "Front", "أمامي", "پێشەوە",

        // ---- home screen ----
        "All", "الكل", "هەموو",
        "My drawings", "رسوماتي", "وێنەکانم",
        "Continue", "متابعة", "بەردەوامبوون",
        "Continue drawing  ›", "تابع الرسم  ›", "بەردەوام بە لە وێنەکێشان  ›",
        "‹ Back to canvas", "‹ العودة للرسمة", "‹ گەڕانەوە بۆ تابلۆ",
        "Import…", "استيراد…", "هاوردەکردن…",
        "＋  New canvas", "＋  لوحة جديدة", "＋  تابلۆی نوێ",
        "Nothing here.", "لا يوجد شيء هنا.", "هیچ شتێک لێرە نییە.",
        "Welcome to Inkora", "أهلاً بك في Inkora", "بەخێربێیت بۆ Inkora",
        "Start a new canvas, or import a photo or project.", "ابدأ لوحة جديدة، أو استورد صورة أو مشروعاً.", "تابلۆیەکی نوێ دەست پێبکە، یان وێنە یان پڕۆژەیەک هاوردە بکە.",
        "IMPORTED", "مستوردة", "هاوردەکراو",
        "DRAWN", "مرسومة", "کێشراو",

        "Floating windows", "نوافذ عائمة", "پەنجەرە بزۆکەکان",
        "Undo: ", "تراجع: ", "گەڕانەوە: ",

        // ---- first-run tips ----
        "Skip", "تخطٍّ", "تێپەڕاندن",
        "Next", "التالي", "دواتر",
        "Start drawing", "ابدأ الرسم", "دەست بە وێنەکێشان بکە",
        "Your pen button does the work", "زر القلم يعمل الشغل عنك", "دوگمەی قەڵەم کارەکە دەکات",
        "Click it to switch brush and eraser. Hold it (or double-click) for the quick menu: color wheel in the middle, tools on top, size and opacity on the sides.",
        "انقره للتبديل بين الفرشاة والممحاة. اضغطه مطوّلاً (أو انقره مرتين) للقائمة السريعة: دولاب الألوان في الوسط، والأدوات في الأعلى، والحجم والشفافية على الجانبين.",
        "کرتەی لێ بکە بۆ گۆڕینی فڵچە و سڕەرەوە. ڕایبگرە (یان دوو کرتە) بۆ مێنیوی خێرا: چەرخی ڕەنگ لە ناوەڕاست، ئامرازەکان لە سەرەوە، قەبارە و ناڕوونی لە لاکان.",
        "Your toolbar, your way", "شريط أدواتك كما تريد", "شریتی ئامرازەکانت بە شێوەی خۆت",
        "The strip on the side holds your favorite tools. Long-press it to add, remove or reorder. In Creative and Editing, tap ☆ on any card to add it.",
        "الشريط على الجانب يحمل أدواتك المفضلة. اضغط عليه مطوّلاً للإضافة أو الحذف أو الترتيب. وفي إبداع وتعديل، انقر ☆ على أي بطاقة لإضافتها.",
        "ئەو شریتەی لە لاوەیە ئامرازە دڵخوازەکانتی تێدایە. بە درێژی دای بگرە بۆ زیادکردن، لابردن یان ڕیزکردن. لە داهێنان و دەستکاری، کرتە لە ☆ بکە بۆ زیادکردن.",
        "Fingers move the canvas", "الأصابع تحرّك الرسمة", "پەنجەکان تابلۆکە دەجووڵێنن",
        "Pinch to zoom and rotate. Tap with two fingers to undo, three to redo. Hold a finger still to pick a color. Draw a line and pause for a perfect shape.",
        "اقرص للتكبير والتدوير. انقر بإصبعين للتراجع وبثلاث للإعادة. أبقِ إصبعك ثابتاً لأخذ لون. ارسم خطاً وتوقّف لتحصل على شكل مثالي.",
        "بیگوشە بۆ زووم و سووڕاندن. بە دوو پەنجە کرتە بکە بۆ گەڕانەوە، بە سێ بۆ دووبارە. پەنجەیەک ڕابگرە بۆ وەرگرتنی ڕەنگ. هێڵێک بکێشە و ڕابوەستە بۆ شێوەیەکی تەواو."
    )
}
