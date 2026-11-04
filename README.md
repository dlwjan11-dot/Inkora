<p align="center">
  <img src="art/banner.png" alt="Inkora — a whole painting studio, in your pen" width="100%">
</p>

<p align="center">
  <a href="https://dlwjan11-dot.github.io/Inkora/Inkora-0.9.0.apk"><img src="https://img.shields.io/badge/Download_APK-0.9.0-ffc14d?style=for-the-badge&logo=android&logoColor=2a1b00&labelColor=ffc14d&color=ffd27a" alt="Download APK 0.9.0"></a>
  &nbsp;
  <a href="https://dlwjan11-dot.github.io/Inkora/"><img src="https://img.shields.io/badge/Website-EN_%C2%B7_AR_%C2%B7_KU-141a3a?style=for-the-badge&labelColor=1c2350" alt="Website"></a>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Android-10%2B-5fd39a?style=flat-square&logo=android&logoColor=white" alt="Android 10+">
  <img src="https://img.shields.io/badge/size-1_MB-6fb3ff?style=flat-square" alt="1 MB">
  <img src="https://img.shields.io/badge/made_for-S_Pen-ff6b8b?style=flat-square" alt="Made for S Pen">
  <img src="https://img.shields.io/badge/Kotlin-no_libraries-7b5cff?style=flat-square&logo=kotlin&logoColor=white" alt="Kotlin, no libraries">
  <img src="https://img.shields.io/badge/ads-none-aab0d6?style=flat-square" alt="No ads">
</p>

<p align="center">
  <a href="#english"><b>English</b></a> &nbsp;|&nbsp; <a href="#العربية"><b>العربية</b></a> &nbsp;|&nbsp; <a href="#کوردی"><b>کوردی</b></a>
</p>

---

<a id="english"></a>

## Inkora

**Inkora is a free drawing and painting app for Android tablets.** Nothing like Procreate existed on Android, so this is an attempt at one: pressure-sensitive brushes, layers, perspective grids, vector shapes for logos and comics, and a color wheel that opens right under your pen.

### New in 0.8: a pro layout

<p align="center"><img src="art/interface.png" alt="The Inkora interface" width="88%"></p>

| | |
|---|---|
| **1 · Top bar** | Gallery, menu, Creative and Editing on one side. Brush, smudge, eraser, layers and your color on the other. Tap the title to rename the artwork. |
| **2 · Your own toolbar** | Pick up to 14 of 30 tools: selection, transform, fill, text, filters, grids, symmetry, focus mode, export… Add, remove and reorder them. Left-handed? Swap the sides in one tap. |
| **3 · Size and opacity** | Two tall sliders by your other hand, with the eyedropper, undo and redo. |
| **4 · Creative and Editing** | Big visual panels: a preview of every grid and symmetry type, switches for QuickShape and assisted drawing, and adjustments previewed live on your own artwork. Tap ☆ on any card to add it to your toolbar. |

### The pen menu

<table>
<tr>
<td width="46%"><img src="art/pen-menu.png" alt="Pen quick menu"></td>
<td>

Hold (or double-click) the S Pen button and everything is around your pen:

- a **standard color wheel** in the middle: hue ring plus saturation/brightness square, so a color is one move away
- your **saved colors** under it, and **+** to save the current one
- **tools** on top, **recent brushes** at the bottom
- **size** on the left arc, **opacity** on the right arc

Move over an item and release the button, or just tap it. Holding the pen tip still on the canvas opens the same menu.

| Pen | Does |
|---|---|
| Click | brush ⇄ eraser |
| Hold / double-click | quick menu |
| Draw, then pause | QuickShape: clean line, circle, box |

</td>
</tr>
</table>

### Everything a painting needs

<p align="center"><img src="art/numbers.png" alt="67 brushes, 17 blend modes, 10 guides, 30 toolbar tools" width="80%"></p>

- **Paint:** 67 brushes in 9 sets (inking, calligraphy with a Qalam nib for Arabic, sketching, painting, watercolor, airbrush, textures, effects, pixel art). Pressure and tilt, streamline, paper grain, and a Brush Studio to make your own. Import Procreate `.brush` / `.brushset`, zip files, or brushes from a link.
- **Organize:** layers with 17 blend modes, alpha lock, merge, and panels you can pin over the canvas. Freehand, rectangle and ellipse selection with feathering. Transform, flip and rotate.
- **Guide:** square, dot, isometric, triangle, hexagon and polar grids, rule of thirds, and 1, 2 and 3-point perspective. Assisted drawing snaps strokes to the guide. Vertical, horizontal, quadrant and radial symmetry.
- **Make:** vector layers with a Bézier pen, shapes, gradients, boolean operations and SVG export. Text in any language (Arabic and Kurdish included). Adjustments and filters with live preview. Fill tool. Automatic time-lapse video. PNG, JPEG and `.inkora` project files.

### Install

1. Download **[Inkora-0.9.0.apk](https://dlwjan11-dot.github.io/Inkora/Inkora-0.9.0.apk)** on your Android device.
2. Open it. If Android asks, allow installs from this source.
3. If Play Protect warns you, tap **Install anyway** — Inkora isn't on Google Play yet.
4. Open Inkora and draw.

Made for big tablets with an S Pen (Galaxy Tab S series). Phones work too, with your finger.
SHA-256: `7480bd8a1838a162b06831f1d60c9951e97c95b0b68ac9f66447608feecb1f27`

### Build it yourself

Open the folder in **Android Studio**, let Gradle sync, then press **Run ▶** with your tablet connected (Developer options → USB debugging on). Plain Kotlin and the Android framework, no external libraries.

```
app/src/main/java/com/abdulmedia/inkora/
  MainActivity.kt   layout, menus, dialogs        CanvasView.kt   drawing, gestures, S Pen
  StrokeEngine.kt   brush dabs, pressure, tilt    Brushes.kt      the 67 brush presets
  QuickMenuView.kt  pen menu                      ColorDisc.kt    standard color wheel
  ToolDock.kt       customizable toolbar          ToolsPanel.kt   Creative / Editing panel
  Vector*.kt        vector layers                 Guide*.kt       grids and perspective
```

What's next: [ROADMAP.md](ROADMAP.md) (comics panels and speech bubbles, liquify, layer masks, PSD, animation).

---

<a id="العربية"></a>

<div dir="rtl">

## Inkora بالعربية

<p align="center"><img src="art/site-ar.png" alt="موقع Inkora بالعربية" width="100%"></p>

**Inkora تطبيق رسم وتلوين مجاني لأجهزة أندرويد اللوحية.** لم يكن على أندرويد تطبيق مثل Procreate، فهذه محاولة لصنعه: فُرَش تستجيب لضغط القلم، وطبقات، وشبكات منظور، وأشكال فيكتور للشعارات والقصص المصوّرة، ودولاب ألوان يفتح تحت قلمك مباشرة.

### الجديد في 0.8: واجهة احترافية
- **الشريط العلوي:** المعرض والقائمة وCreative وEditing في جهة، والفرشاة والتمويه والممحاة والطبقات واللون في الجهة الأخرى.
- **شريط أدواتك الخاص:** اختر حتى 14 أداة من 30، وأضفها واحذفها وغيّر ترتيبها. ووضع لليد اليسرى.
- **Creative وEditing:** لوحات كبيرة مصوّرة، ومعاينة مباشرة للتعديلات على رسمتك، ونجمة ☆ لإضافة أي أداة إلى شريطك.
- **قائمة القلم:** دولاب ألوان عادي في الوسط، وألوانك المحفوظة تحته، والأدوات في الأعلى، وآخر الفُرَش في الأسفل، والحجم يساراً والشفافية يميناً.
- **أداة تعبئة** و**وضع التركيز** الذي يخفي الواجهة.

### المزايا
- 67 فرشاة في 9 مجموعات، منها قلم قصب للخط العربي. استيراد فُرَش Procreate.
- طبقات مع 17 نمط دمج، وتحديد، وتحويل.
- 10 أنواع شبكات ومنظور، ورسم مساعد، وQuickShape، وتناظر.
- طبقات فيكتور، ونص بأي لغة، وفلاتر، وفيديو تايم لابس، وتصدير PNG وJPEG وSVG.

### التثبيت
1. حمّل **[Inkora-0.9.0.apk](https://dlwjan11-dot.github.io/Inkora/Inkora-0.9.0.apk)** على جهازك.
2. افتح الملف، واسمح بالتثبيت من هذا المصدر إذا طُلب منك.
3. إذا ظهر تحذير Play Protect اضغط **التثبيت على أي حال**.
4. افتح Inkora وابدأ الرسم.

</div>

---

<a id="کوردی"></a>

<div dir="rtl">

## Inkora بە کوردی

<p align="center"><img src="art/site-ckb.png" alt="ماڵپەڕی Inkora بە کوردی" width="100%"></p>

**Inkora ئەپێکی بێبەرامبەری وێنەکێشان و ڕەنگکردنە بۆ تابلێتەکانی ئەندرۆید.** فڵچەی هەستیار بە پەستانی قەڵەم، چینەکان، تۆڕی پێرسپێکتیڤ، شێوەی ڤێکتەر بۆ لۆگۆ و چیرۆکی وێنەدار، و چەرخێکی ڕەنگ کە ڕاستەوخۆ لە ژێر قەڵەمەکەتدا دەکرێتەوە.

### نوێ لە 0.8
- **شریتی سەرەوە:** گەلەری، مێنیو، Creative و Editing لە لایەک؛ فڵچە، سڕەرەوە، چینەکان و ڕەنگ لە لایەکەی تر.
- **شریتی ئامرازی خۆت:** تا ١٤ ئامراز لە ٣٠ هەڵبژێرە، زیادیان بکە، لایان ببە و ڕیزبەندییان بگۆڕە. دۆخی دەستەچەپیشی هەیە.
- **Creative و Editing:** پانێڵی گەورەی وێنەدار و پێشبینینی ڕاستەوخۆی ڕێکخستنەکان لەسەر کارەکەت.
- **مێنیوی قەڵەم:** چەرخی ڕەنگی ئاسایی لە ناوەڕاست، ڕەنگە پاشەکەوتکراوەکانت لە ژێریدا، ئامرازەکان لە سەرەوە، فڵچەکان لە خوارەوە، قەبارە لە چەپ و ناڕوونی لە ڕاست.

### دامەزراندن
1. **[Inkora-0.9.0.apk](https://dlwjan11-dot.github.io/Inkora/Inkora-0.9.0.apk)** لەسەر ئامێرەکەت دابگرە.
2. فایلەکە بکەرەوە و ئەگەر پرسیاری کرد، ڕێگە بە دامەزراندن لەم سەرچاوەیە بدە.
3. ئەگەر Play Protect ئاگاداریی پیشان دا، کرتە لە **Install anyway** بکە.
4. Inkora بکەرەوە و دەست بە وێنەکێشان بکە.

</div>

---

<p align="center">
  Made by <a href="https://abdulmedia.com"><b>Abdulmedia</b></a> &nbsp;|&nbsp; <a href="https://dlwjan11-dot.github.io/Inkora/">dlwjan11-dot.github.io/Inkora</a>
</p>
