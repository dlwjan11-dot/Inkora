#!/usr/bin/env python3
"""The animated shortcut cards, written once: this makes the data the app (ShortcutData.kt)
and the website (index.html) both play. Space: 100 x 60. Times: 0..1 over 'dur' seconds."""
import json, math, sys, re, os

def K(*a):  # keyframes: t, v, t, v ...
    return [[a[i], a[i + 1]] for i in range(0, len(a), 2)]

def rect(x, y, w, h, c="panel", a=1, r=2, s=0, rot=0): return dict(k="rect", x=x, y=y, w=w, h=h, c=c, a=a, r=r, s=s, rot=rot)
def circ(x, y, r, c="ink", a=1, s=0): return dict(k="circ", x=x, y=y, r=r, c=c, a=a, s=s)
def line(x1, y1, x2, y2, c="ink", a=1, s=1.4): return dict(k="line", x1=x1, y1=y1, x2=x2, y2=y2, c=c, a=a, s=s)
def poly(pts, c="ink", a=1, s=1.6, p=1): return dict(k="poly", pts=pts, c=c, a=a, s=s, p=p)
def finger(x, y, a=1, d=1): return dict(k="finger", x=x, y=y, a=a, d=d)
def pen(x, y, a=1, b=0): return dict(k="pen", x=x, y=y, a=a, b=b)
def txt(t, x, y, z=7, c="ink", a=1): return dict(k="txt", t=t, x=x, y=y, z=z, c=c, a=a)

def paper(w=46, h=34, x=50, y=31, rot=0): return rect(x, y, w, h, "paper", r=1.5, rot=rot)
def tap(t0, t1): return K(0, 0, max(0, t0 - .04), 0, t0, 1, t1, 1, min(1, t1 + .05), 0)
def ripple(x, y, t0, c="accent"): return circ(x, y, K(t0, 3, t0 + .18, 11), c, K(0, 0, t0, 0, t0 + .01, .9, t0 + .18, 0), s=1.2)
def squiggle(x0, y0, x1, y1, n=18, amp=4, ph=0):
    return [[round(x0 + (x1 - x0) * i / n, 2), round(y0 + (y1 - y0) * i / n + amp * math.sin(ph + i * 0.9), 2)] for i in range(n + 1)]
def topbar(): return [rect(50, 4.5, 96, 6, "panel2", r=2)]
def slider(hy): return [rect(14, 30, 2.6, 46, "dim", r=1.3), rect(14, hy, 9, 5, "ink", r=1.6)]
def rows(ys, c="panel2"): return [rect(50, y, 62, 11, c, r=2.5) for y in ys]

G = []  # groups: (title, ar, ckb, [demos])
def group(en, ar, ckb): G.append(dict(en=en, ar=ar, ckb=ckb, demos=[]))
def demo(title, text, els, dur=3.2): G[-1]["demos"].append(dict(title=title, text=text, dur=dur, els=els))

# ---------------- fingers ----------------
group("Fingers on the canvas", "الأصابع على اللوحة", "پەنجەکان لەسەر تابلۆ")
demo(("Zoom, move and turn", "تكبير وتحريك وتدوير", "گەورەکردن و جوڵاندن و سوڕاندن"),
     ("Pinch with two fingers", "اقرص بإصبعين", "بە دوو پەنجە بیگوشە"),
     [rect(50, 31, K(0, 30, .45, 50, .7, 50, 1, 30), K(0, 22, .45, 37, .7, 37, 1, 22), "paper", r=1.5, rot=K(0, 0, .45, 14, .7, 14, 1, 0)),
      finger(K(0, 44, .45, 33, .7, 33, 1, 44), K(0, 34, .45, 44, .7, 44, 1, 34)),
      finger(K(0, 56, .45, 69, .7, 69, 1, 56), K(0, 28, .45, 17, .7, 17, 1, 28))])
demo(("Fit to the screen", "ملء الشاشة", "گونجاندن لەگەڵ شاشە"),
     ("A quick pinch in", "قرصة سريعة للداخل", "گوشینێکی خێرا بۆ ناوەوە"),
     [rect(K(0, 60, .3, 60, .42, 50, .9, 50, 1, 60), K(0, 38, .3, 38, .42, 31, .9, 31, 1, 38), K(0, 70, .3, 70, .42, 46, .9, 46, 1, 70), K(0, 52, .3, 52, .42, 34, .9, 34, 1, 52), "paper", r=1.5, rot=K(0, 16, .3, 16, .42, 0, .9, 0, 1, 16)),
      finger(K(.15, 30, .3, 44), K(.15, 44, .3, 35), a=K(0, 0, .1, 0, .15, 1, .32, 1, .4, 0)),
      finger(K(.15, 70, .3, 56), K(.15, 16, .3, 27), a=K(0, 0, .1, 0, .15, 1, .32, 1, .4, 0))])
demo(("Undo", "تراجع", "گەڕانەوە"), ("Tap with two fingers", "اكبس بإصبعين", "بە دوو پەنجە لێبدە"),
     [paper(), poly(squiggle(32, 24, 66, 26), "accent", s=1.8), poly(squiggle(34, 38, 68, 36, ph=2), "red", a=K(0, 1, .42, 1, .5, 0, .9, 0, 1, 1), s=1.8),
      ripple(44, 33, .32), ripple(56, 29, .32), finger(44, 33, a=tap(.3, .42)), finger(56, 29, a=tap(.3, .42))])
demo(("Redo", "إعادة", "دووبارەکردنەوە"), ("Tap with three fingers", "اكبس بتلات أصابع", "بە سێ پەنجە لێبدە"),
     [paper(), poly(squiggle(32, 24, 66, 26), "accent", s=1.8), poly(squiggle(34, 38, 68, 36, ph=2), "red", a=K(0, 0, .42, 0, .5, 1, .9, 1, 1, 0), s=1.8),
      ripple(40, 34, .32), ripple(50, 29, .32), ripple(60, 33, .32),
      finger(40, 34, a=tap(.3, .42)), finger(50, 29, a=tap(.3, .42)), finger(60, 33, a=tap(.3, .42))])
demo(("Pick a color", "التقاط لون", "هەڵگرتنی ڕەنگ"), ("Touch and hold on the artwork", "اكبس مطوّل على الرسمة", "لەسەر کارەکە پەنجە دابنێ و بیگرە"),
     [paper(), circ(41, 31, 8, "gold"), circ(60, 29, 6.5, "red"), circ(88, 9, 5, "dim"), circ(88, 9, 5, "gold", a=K(0, 0, .55, 0, .62, 1, .92, 1, 1, 0)),
      circ(41, 31, K(.25, 4, .55, 11), "ink", a=K(0, 0, .25, 0, .3, .9, .8, .9, .86, 0), s=1.6),
      finger(41, 31, a=K(0, 0, .15, 0, .2, 1, .8, 1, .86, 0))])
demo(("Cut, copy and paste", "قص ونسخ ولصق", "بڕین و لەبەرگرتنەوە و لکاندن"), ("Swipe down with three fingers", "اسحب لتحت بتلات أصابع", "بە سێ پەنجە بەرەو خوار ڕایبکێشە"),
     [paper()] + [finger(x, K(.15, 18, .5, 42), a=K(0, 0, .1, 0, .15, 1, .5, 1, .58, 0)) for x in (40, 50, 60)] +
     [rect(50, 24, 48, 11, "panel2", a=K(0, 0, .5, 0, .6, 1, .92, 1, 1, 0), r=3)] +
     [rect(x, 24, 10, 5, c, a=K(0, 0, .5, 0, .6, 1, .92, 1, 1, 0), r=1.5) for x, c in ((35, "accent"), (50, "dim"), (65, "dim"))])
demo(("Hide the interface", "إخفاء الواجهة", "شاردنەوەی ڕووکار"), ("Tap with four fingers", "اكبس بأربع أصابع", "بە چوار پەنجە لێبدە"),
     [paper(52, 36, 53, 33), rect(50, 4.5, 96, 6, "panel2", a=K(0, 1, .4, 1, .5, 0, .88, 0, 1, 1)), rect(6, 33, 6, 38, "panel2", a=K(0, 1, .4, 1, .5, 0, .88, 0, 1, 1), r=2.5)] +
     [ripple(x, y, .3) for x, y in ((38, 36), (48, 30), (58, 30), (68, 36))] + [finger(x, y, a=tap(.28, .4)) for x, y in ((38, 36), (48, 30), (58, 30), (68, 36))])
demo(("Clear the layer", "مسح الطبقة", "پاککردنەوەی چین"), ("Rub three fingers back and forth", "افرك بتلات أصابع يمين ويسار", "بە سێ پەنجە بیسڕەوە بۆ ئەملا و ئەولا"),
     [paper(), poly(squiggle(32, 24, 66, 26), "accent", a=K(0, 1, .6, 1, .72, 0, .92, 0, 1, 1), s=1.8), poly(squiggle(34, 38, 68, 36, ph=2), "red", a=K(0, 1, .6, 1, .72, 0, .92, 0, 1, 1), s=1.8)] +
     [finger(K(.15, x - 9, .27, x + 9, .39, x - 9, .51, x + 9, .63, x - 9), y, a=K(0, 0, .1, 0, .15, 1, .63, 1, .7, 0)) for x, y in ((40, 34), (50, 29), (60, 33))])

# ---------------- pen ----------------
group("The S Pen", "قلم S Pen", "قەڵەمی S Pen")
demo(("Brush ⇄ eraser", "فرشاة ⇄ ممحاة", "فڵچە ⇄ سڕەر"), ("Click the S Pen button", "اكبس زر القلم", "لە دوگمەی قەڵەمەکە بدە"),
     topbar() + [paper(46, 30, 46, 34), line(68, 6.5, 72, 2.5, "ink", s=1.6), rect(84, 4.5, 6, 3.6, "red", r=1),
      rect(K(.4, 70, .5, 84, .9, 84, 1, 70), 4.5, 10, 5.4, "accent", s=0.9, r=1.6),
      ripple(52, 26, .3), pen(46, 36, b=K(0, 0, .28, 0, .3, 1, .4, 1, .42, 0))])
demo(("Quick menu", "القائمة السريعة", "لیستی خێرا"), ("Double-click or hold the S Pen button", "اكبس زر القلم مرتين أو مطوّل", "دوو جار لە دوگمەی قەڵەم بدە یان بیگرە"),
     [paper(), circ(46, 36, K(.42, 2, .56, 15), "gold", a=K(0, 0, .42, 0, .5, .95, .9, .95, 1, 0), s=5),
      circ(46, 36, K(.42, 1, .56, 9), "accent", a=K(0, 0, .42, 0, .5, .95, .9, .95, 1, 0), s=2),
      pen(46, 36, b=K(0, 0, .18, 0, .2, 1, .26, 1, .28, 0, .32, 0, .34, 1, .4, 1, .42, 0))])
demo(("Quick menu without the button", "القائمة السريعة بدون الزر", "لیستی خێرا بەبێ دوگمە"), ("Keep the pen still on the canvas", "خلّي القلم ثابت على اللوحة", "قەڵەمەکە بە جێگیری لەسەر تابلۆ بهێڵەوە"),
     [paper(), circ(46, 36, K(.2, 2, .5, 6), "ink", a=K(0, 0, .2, 0, .25, .7, .5, .7, .55, 0), s=1),
      circ(46, 36, K(.52, 2, .66, 15), "gold", a=K(0, 0, .52, 0, .6, .95, .9, .95, 1, 0), s=5),
      circ(46, 36, K(.52, 1, .66, 9), "accent", a=K(0, 0, .52, 0, .6, .95, .9, .95, 1, 0), s=2),
      pen(K(0, 60, .18, 46), K(0, 24, .18, 36), a=K(0, 0, .06, 1))])
demo(("Erase without switching", "امسح بدون ما تبدّل", "سڕینەوە بەبێ گۆڕین"), ("Draw with the S Pen button held", "ارسم وزر القلم مكبوس", "بە دوگمەی گیراوی قەڵەمەوە بکێشە"),
     [paper(), poly(squiggle(30, 31, 70, 31, amp=5), "accent", s=2), poly([[34, 31], [66, 31]], "paper", s=12, p=K(.15, 0, .7, 1), a=K(0, 1, .9, 1, 1, 0)),
      pen(K(.15, 30, .7, 70), 31, b=1, a=K(0, 0, .1, 1, .8, 1, .9, 0))])
_c = [[round(50 + (13 + 1.5 * math.sin(i * 0.6)) * math.cos(-math.pi / 2 + i * 2 * math.pi / 28), 2), round(31 + (12 + 1.3 * math.cos(i * 0.75)) * math.sin(-math.pi / 2 + i * 2 * math.pi / 28), 2)] for i in range(29)]
_px = []; _py = []
for i in range(0, 29, 4): _px += [.08 + .42 * i / 28, _c[i][0]]; _py += [.08 + .42 * i / 28, _c[i][1]]
demo(("Clean shapes", "أشكال نظيفة", "شێوەی پاک"), ("Draw a shape and keep the pen down", "ارسم شكل وخلّي القلم نازل", "شێوەیەک بکێشە و قەڵەمەکە هەڵمەگرە"),
     [paper(), poly(_c, "accent", s=1.7, p=K(.08, 0, .5, 1), a=K(0, 1, .68, 1, .74, 0)), circ(50, 31, 13, "accent", a=K(0, 0, .68, 0, .74, 1, .93, 1, 1, 0), s=1.7),
      circ(_c[28][0], _c[28][1], K(.5, 1, .68, 5), "ink", a=K(0, 0, .5, 0, .52, .7, .68, .7, .72, 0), s=0.9),
      pen(K(*_px), K(*_py), a=K(0, 0, .06, 1, .82, 1, .9, 0))])

# ---------------- sliders ----------------
group("Size and opacity sliders", "شريطي الحجم والشفافية", "هێڵەکانی قەبارە و ڕوونی")
demo(("Fine control", "تحكم دقيق", "کۆنترۆڵی ورد"), ("Slide away from the slider while you drag", "ابعد عن الشريط وإنت عم تسحب", "لە کاتی ڕاکێشاندا لە هێڵەکە دوور بکەوەرەوە"),
     slider(K(0, 44, .3, 18, .5, 18, .9, 24, 1, 44)) + [line(14, K(.4, 18, .5, 18, .9, 24), K(.4, 14, .5, 62), K(.4, 18, .5, 18, .9, 46), "dim", a=K(0, 0, .4, 0, .5, .8, .9, .8, .95, 0), s=0.7),
      circ(40, 14, K(0, 2.5, .3, 6, .5, 6, .9, 7, 1, 2.5), "accent", a=.9), finger(K(0, 14, .38, 14, .5, 62), K(0, 44, .3, 18, .5, 18, .9, 46), a=K(0, 1, .9, 1, .96, 0))], dur=4)
demo(("Leave a mark", "حط علامة", "نیشانە دابنێ"), ("Double-tap the handle; tap the mark to come back", "اكبس المسكة مرتين؛ اكبس العلامة لترجع", "دوو جار لە دەسکەکە بدە؛ لە نیشانەکە بدە بۆ گەڕانەوە"),
     [rect(14, 30, 2.6, 46, "dim", r=1.3), rect(14, 20, 12, 2, "accent", a=K(0, 0, .26, 0, .3, 1), r=1), rect(14, K(0, 20, .38, 20, .58, 42, .78, 42, .84, 20), 9, 5, "ink", r=1.6),
      ripple(14, 20, .1), ripple(14, 20, .22), ripple(14, 20, .74),
      finger(18, K(0, 21, .38, 21, .58, 43, .64, 43, .7, 21), a=K(0, 0, .06, 0, .08, 1, .14, 1, .17, 0, .2, 1, .26, 1, .3, 0, .36, 0, .38, 1, .58, 1, .62, 0, .7, 0, .72, 1, .8, 1, .86, 0)),
      circ(45, 30, K(0, 5, .38, 5, .58, 11, .78, 11, .84, 5), "accent", a=.9)], dur=4.4)
demo(("An exact number", "رقم دقيق", "ژمارەی ورد"), ("Hold the slider's handle", "اكبس مطوّل على المسكة", "دەسکی هێڵەکە بگرە"),
     slider(26) + [circ(14, 26, K(.15, 3, .5, 9), "ink", a=K(0, 0, .15, 0, .2, .8, .5, .8, .55, 0), s=1),
      finger(18, 27, a=K(0, 0, .1, 0, .15, 1, .55, 1, .62, 0)),
      rect(58, 30, 44, 18, "panel2", a=K(0, 0, .5, 0, .6, 1, .92, 1, 1, 0), r=3), rect(58, 34, 30, 0.8, "accent", a=K(0, 0, .5, 0, .6, 1, .92, 1, 1, 0), r=0),
      txt("24 px", 58, 32, 8, "ink", a=K(0, 0, .5, 0, .6, 1, .92, 1, 1, 0))])
demo(("The □ button", "زر □", "دوگمەی □"), ("Tap □, then touch the canvas: pick a color", "اكبس □ وبعدين المس اللوحة: بتلتقط لون", "لە □ بدە، پاشان لە تابلۆ بدە: ڕەنگ هەڵدەگرێت"),
     [paper(46, 34, 56, 31), circ(50, 31, 8, "gold"), circ(68, 29, 6, "red"), rect(12, 30, 8, 8, "dim", s=1.2, r=1.5), rect(12, 30, 8, 8, "accent", a=K(0, 0, .16, 0, .2, .9, .6, .9, .66, 0), r=1.5),
      circ(88, 9, 5, "dim"), circ(88, 9, 5, "gold", a=K(0, 0, .56, 0, .62, 1, .92, 1, 1, 0)), ripple(12, 30, .16), ripple(50, 31, .52),
      finger(K(.24, 14, .46, 51), K(.24, 31, .46, 32), a=K(0, 0, .1, 0, .14, 1, .22, 1, .26, .25, .46, .25, .5, 1, .6, 1, .66, 0), d=K(0, 1, .22, 1, .26, 0, .46, 0, .5, 1))])

# ---------------- color & brushes ----------------
group("Color and brushes", "الألوان والفرش", "ڕەنگ و فڵچەکان")
demo(("Fill an area", "تعبئة منطقة", "پڕکردنەوەی ناوچەیەک"), ("Drag the color circle onto the canvas", "اسحب دائرة اللون على اللوحة", "بازنەی ڕەنگەکە ڕابکێشە سەر تابلۆ"),
     topbar() + [paper(46, 30, 46, 35), circ(46, 35, 10.6, "gold", a=K(0, 0, .52, 0, .6, 1, .9, 1, 1, 0)), circ(46, 35, 11, "ink", s=1.4), circ(90, 4.5, 2.6, "gold"),
      circ(K(.12, 90, .5, 46), K(.12, 4.5, .5, 35), 3, "gold", a=K(0, 0, .1, 0, .12, 1, .5, 1, .54, 0)),
      finger(K(.12, 91, .5, 47), K(.12, 6, .5, 37), a=K(0, 0, .06, 0, .1, 1, .5, 1, .56, 0))])
demo(("The brush library", "مكتبة الفرش", "کتێبخانەی فڵچە"), ("Tap the tool you are using once more", "اكبس الأداة يلي بإيدك مرة تانية", "جارێکی تر لەو ئامرازە بدە کە بەکاریدەهێنیت"),
     topbar() + [rect(70, 4.5, 8, 5, "accent", r=1.5), ripple(70, 5, .2), finger(71, 7, a=tap(.18, .28)),
      rect(62, 34, 52, 44, "panel2", a=K(0, 0, .28, 0, .4, 1, .92, 1, 1, 0), r=3)] +
     [rect(62, y, 44, 9, "dim", a=K(0, 0, .28, 0, .4, .7, .92, .7, 1, 0), r=2) for y in (21, 33, 45)] +
     [poly(squiggle(46, y, 78, y, amp=1.6, ph=y), "ink", a=K(0, 0, .28, 0, .4, 1, .92, 1, 1, 0), s=s) for y, s in ((21, 1), (33, 2), (45, 3))])
demo(("Pin, duplicate, reset", "تثبيت، نسخ، إرجاع", "جێگیرکردن، لەبەرگرتنەوە، گەڕاندنەوە"), ("Slide a brush to the left", "اسحب الفرشاة لليسار", "فڵچەکە بەرەو چەپ ڕابکێشە"),
     [rect(72, 30, 9, 13, "accent", a=K(0, 0, .2, 0, .4, 1, .88, 1, .96, 0), r=2), rect(82, 30, 9, 13, "green", a=K(0, 0, .2, 0, .4, 1, .88, 1, .96, 0), r=2), rect(92, 30, 9, 13, "red", a=K(0, 0, .2, 0, .4, 1, .88, 1, .96, 0), r=2),
      rect(K(0, 50, .2, 50, .5, 22, .88, 22, 1, 50), 30, 84, 15, "panel2", r=3), poly(squiggle(-22, 0, 22, 0, amp=2), "ink", s=2.4, a=0),
      line(K(0, 22, .2, 22, .5, -6, .88, -6, 1, 22), 30, K(0, 78, .2, 78, .5, 50, .88, 50, 1, 78), 30, "ink", s=2.6),
      finger(K(.2, 66, .5, 38), 33, a=K(0, 0, .14, 0, .2, 1, .5, 1, .58, 0))])
demo(("Same brush, another tool", "نفس الفرشاة بأداة تانية", "هەمان فڵچە بۆ ئامرازێکی تر"), ("Hold Eraser or Smudge", "اكبس مطوّل على الممحاة أو التمويه", "سڕەر یان تێکەڵکەر بگرە"),
     topbar() + [line(68, 6.5, 72, 2.5, "ink", s=1.6), circ(79, 4.5, 2, "ink", s=1), rect(88, 4.5, 6, 3.6, "red", r=1),
      rect(K(0, 70, .5, 70, .62, 88, .92, 88, 1, 70), 4.5, 10, 5.4, "accent", s=0.9, r=1.6),
      circ(88, 5, K(.2, 3, .5, 8), "ink", a=K(0, 0, .2, 0, .24, .8, .5, .8, .55, 0), s=1),
      poly(squiggle(26, 34, 74, 34, amp=4), "accent", s=3.2, a=K(0, 1, .5, 1, .6, .0)), poly(squiggle(26, 34, 74, 34, amp=4), "dim", s=3.2, a=K(0, 0, .5, 0, .6, 1, .92, 1, 1, 0)),
      circ(K(.45, 70, .62, 88), K(.45, 4.5, .53, 12, .62, 4.5), 1.6, "gold", a=K(0, 0, .45, 0, .47, 1, .62, 1, .66, 0)),
      finger(89, 7.5, a=K(0, 0, .14, 0, .2, 1, .6, 1, .68, 0))])

# ---------------- layers ----------------
group("Layers and your toolbar", "الطبقات وشريط أدواتك", "چینەکان و شریتی ئامرازەکانت")
demo(("Pick several layers", "اختيار كذا طبقة", "هەڵبژاردنی چەند چینێک"), ("Slide a layer sideways, or hold it", "اسحب الطبقة لليمين أو اليسار، أو اكبس مطوّل", "چینەکە بەلادا ڕابکێشە، یان بیگرە"),
     [rect(50, 16, 62, 11, "accentsoft", r=2.5), rect(K(0, 50, .2, 50, .38, 57, .5, 50), 30, 62, 11, "panel2", r=2.5), rect(50, 44, 62, 11, "panel2", r=2.5),
      rect(50, 30, 62, 11, "accentsoft", a=K(0, 0, .44, 0, .52, 1, .92, 1, 1, 0), r=2.5), txt("✓", 26, 32.4, 7, "ink", a=K(0, 0, .44, 0, .52, 1, .92, 1, 1, 0)),
      finger(K(.2, 42, .38, 62), 32, a=K(0, 0, .14, 0, .2, 1, .38, 1, .46, 0))])
demo(("Put a layer somewhere else", "تغيير ترتيب الطبقة", "گۆڕینی شوێنی چین"), ("Drag it by the grip at its edge", "اسحبها من المسكة يلي عطرفها", "لە دەسکەکەی لێوارەکەیەوە ڕایبکێشە"),
     [rect(50, K(0, 30, .5, 30, .66, 16, .92, 16, 1, 30), 62, 11, "panel2", r=2.5), rect(50, K(0, 44, .56, 44, .7, 30, .92, 30, 1, 44), 62, 11, "panel2", r=2.5),
      rect(50, 51, 60, 1.1, "accent", a=K(0, 0, .3, 0, .36, 1, .6, 1, .64, 0), r=.5),
      rect(50, K(0, 16, .2, 16, .58, 44, .92, 44, 1, 16), 62, 11, "accentsoft", r=2.5)] +
     [circ(23 + dx, K(0, 16 + dy, .2, 16 + dy, .58, 44 + dy, .92, 44 + dy, 1, 16 + dy), .7, "ink") for dx in (0, 2.4) for dy in (-2, 0, 2)] +
     [finger(25, K(.2, 18, .58, 46), a=K(0, 0, .14, 0, .2, 1, .58, 1, .66, 0))])
demo(("A layer's options", "خيارات الطبقة", "هەڵبژاردەکانی چین"), ("Tap the active layer once more", "اكبس الطبقة الفعّالة مرة تانية", "جارێکی تر لە چینە چالاکەکە بدە"),
     [rect(50, 14, 62, 11, "accentsoft", r=2.5), rect(50, K(0, 28, .3, 28, .45, 46), 62, 11, "panel2", r=2.5),
      rect(50, 30, 62, 18, "panel2", a=K(0, 0, .3, 0, .45, 1, .92, 1, 1, 0), r=2.5), rect(44, 26, 40, 1.4, "dim", a=K(0, 0, .3, 0, .45, 1, .92, 1, 1, 0), r=.7), circ(52, 26, 2, "ink", a=K(0, 0, .3, 0, .45, 1, .92, 1, 1, 0))] +
     [rect(x, 34, 12, 5, "dim", a=K(0, 0, .3, 0, .45, 1, .92, 1, 1, 0), r=1.5) for x in (30, 44, 58, 72)] +
     [ripple(52, 14, .2), finger(53, 16, a=tap(.18, .28))])
demo(("Your own toolbar", "شريط أدواتك", "شریتی ئامرازەکانی خۆت"), ("Tap ☆ on a tool; hold the toolbar to arrange it", "اكبس ☆ على الأداة؛ اكبس مطوّل على الشريط لترتّبه", "لە ☆ی ئامرازەکە بدە؛ شریتەکە بگرە بۆ ڕێکخستنی"),
     [rect(40, 30, 44, 30, "panel2", r=3), rect(32, 30, 14, 14, "dim", r=3), txt("☆", 54, 24, 8, "ink", a=K(0, 1, .26, 1, .3, 0, .92, 0, 1, 1)), txt("★", 54, 24, 8, "gold", a=K(0, 0, .26, 0, .3, 1, .92, 1, 1, 0)),
      rect(90, 30, 9, 46, "panel2", r=3), rect(90, 14, 5.5, 5.5, "dim", r=1.5), rect(90, 23, 5.5, 5.5, "dim", r=1.5),
      rect(90, 32, 5.5, 5.5, "accent", a=K(0, 0, .3, 0, .42, 1, .92, 1, 1, 0), r=1.5),
      circ(K(.28, 54, .42, 90), K(.28, 22, .35, 18, .42, 32), 1.4, "gold", a=K(0, 0, .28, 0, .3, 1, .42, 1, .46, 0)),
      ripple(54, 22, .2), finger(55, 25, a=tap(.18, .28))])

# ---------------- more (0.23) ----------------
group("More layer and color gestures", "حركات تانية للطبقات والألوان", "جووڵەی تری چین و ڕەنگ")
demo(("Previous color", "اللون يلي قبل", "ڕەنگی پێشوو"), ("Hold the color circle", "اكبس مطوّل على دائرة اللون", "بازنەی ڕەنگەکە بگرە"),
     topbar() + [circ(90, 4.5, 2.8, "accent", a=K(0, 1, .5, 1, .56, 0, .94, 0, 1, 1)), circ(90, 4.5, 2.8, "gold", a=K(0, 0, .5, 0, .56, 1, .94, 1, 1, 0)),
      circ(90, 5, K(.15, 3, .5, 8), "ink", a=K(0, 0, .15, 0, .2, .8, .5, .8, .55, 0), s=1),
      paper(46, 30, 46, 35), poly(squiggle(30, 30, 62, 32), "accent", s=2.4), poly(squiggle(32, 40, 64, 40, ph=2), "gold", s=2.4, p=K(.6, 0, .9, 1)),
      finger(91, 7.5, a=K(0, 0, .1, 0, .15, 1, .55, 1, .62, 0))])
demo(("One layer alone", "طبقة لحالها", "یەک چین بە تەنها"), ("Hold a layer's checkbox; hold it again for all", "اكبس مطوّل على مربع الطبقة؛ ومرة تانية للكل", "چوارگۆشەی چین بگرە؛ دووبارە بۆ هەمووان"),
     [rect(50, 16, 62, 11, "panel2", r=2.5), rect(50, 30, 62, 11, "accentsoft", r=2.5), rect(50, 44, 62, 11, "panel2", r=2.5)] +
     [rect(74, y, 5, 5, "accent", a=(K(0, 1, .42, 1, .48, 0, .9, 0, 1, 1) if y != 30 else 1), r=1.2) for y in (16, 30, 44)] +
     [rect(74, y, 5, 5, "dim", a=(K(0, 0, .42, 0, .48, 1, .9, 1, 1, 0)), r=1.2, s=0.8) for y in (16, 44)] +
     [circ(74, 30, K(.15, 3, .42, 8), "ink", a=K(0, 0, .15, 0, .2, .8, .42, .8, .46, 0), s=1), finger(76, 32, a=K(0, 0, .1, 0, .15, 1, .44, 1, .5, 0))])
demo(("Merge layers", "دمج الطبقات", "تێکەڵکردنی چینەکان"), ("Pinch two layers together", "اقرص طبقتين ببعض", "دوو چین پێکەوە بگوشە"),
     [rect(50, K(0, 16, .3, 16, .5, 26, .9, 26, 1, 16), 62, 11, "panel2", r=2.5, a=K(0, 1, .5, 1, .56, 0, .92, 0, 1, 1)),
      rect(50, K(0, 44, .3, 44, .5, 34, .9, 34, 1, 44), 62, 11, "panel2", r=2.5, a=K(0, 1, .5, 1, .56, 0, .92, 0, 1, 1)),
      rect(50, 30, 62, 12, "accentsoft", r=2.5, a=K(0, 0, .5, 0, .58, 1, .92, 1, 1, 0)),
      finger(46, K(.15, 17, .5, 27), a=K(0, 0, .1, 0, .15, 1, .5, 1, .56, 0)), finger(54, K(.15, 45, .5, 35), a=K(0, 0, .1, 0, .15, 1, .5, 1, .56, 0))])
demo(("Layer opacity", "شفافية الطبقة", "ڕوونیی چین"), ("Tap a layer with two fingers, then slide", "اكبس الطبقة بإصبعين، وبعدين اسحب", "بە دوو پەنجە لە چینەکە بدە، پاشان ڕابکێشە"),
     [rect(50, 31, 56, 34, "paper", r=1.5, a=K(0, 0, .3, 0, .36, 1, .94, 1, 1, 0)), circ(50, 31, 9, "red", a=K(0, 0, .3, 0, .36, 1, .9, .25, .94, .25, 1, 0)),
      rect(50, 30, 62, 11, "accentsoft", r=2.5, a=K(0, 1, .3, 1, .36, 0, .96, 0, 1, 1)), ripple(44, 30, .12), ripple(56, 30, .12),
      finger(44, 30, a=tap(.1, .22)), finger(56, 30, a=tap(.1, .22)),
      rect(50, 8, 40, 2, "dim", a=K(0, 0, .34, 0, .38, 1, .94, 1, 1, 0), r=1), rect(K(.38, 70, .9, 36), 8, 3.4, 5, "ink", a=K(0, 0, .34, 0, .38, 1, .94, 1, 1, 0), r=1.4),
      finger(K(.4, 70, .9, 36), 46, a=K(0, 0, .36, 0, .4, 1, .9, 1, .95, 0))], dur=3.8)

# strip the invisible helper
for g in G:
    for d in g["demos"]:
        d["els"] = [e for e in d["els"] if not (e["k"] == "poly" and e.get("a") == 0)]

def app_data():
    return [dict(title=g["en"], demos=[dict(title=d["title"][0], text=d["text"][0], dur=d["dur"], els=d["els"]) for d in g["demos"]]) for g in G]
def web_data():
    return [dict(title=[g["en"], g["ar"], g["ckb"]], demos=[dict(title=list(d["title"]), text=list(d["text"]), dur=d["dur"], els=d["els"]) for d in g["demos"]]) for g in G]
def l10n_rows():
    out = []
    for g in G:
        out.append((g["en"], g["ar"], g["ckb"]))
        for d in g["demos"]: out += [d["title"], d["text"]]
    return out

if __name__ == "__main__":
    what = sys.argv[1]
    if what == "web": print(json.dumps(web_data(), ensure_ascii=False, separators=(",", ":")))
    elif what == "app": print(json.dumps(app_data(), ensure_ascii=False, separators=(",", ":")))
    elif what == "kt":
        s = json.dumps(app_data(), ensure_ascii=False, separators=(",", ":"))
        assert "$" not in s and '"""' not in s
        parts = [s[i:i + 12000] for i in range(0, len(s), 12000)]
        print("package com.abdulmedia.inkora\n")
        print("/** The animated shortcut cards. Made by tools/shortcut-demos/make.py: change them there. */")
        print("object ShortcutData {")
        for i, p in enumerate(parts): print('    private const val P%d = """%s"""' % (i, p))
        print("    val json: String get() = " + " + ".join("P%d" % i for i in range(len(parts))))
        print("}")
    elif what == "l10n":
        for r in l10n_rows(): print('        "%s", "%s", "%s",' % r)
