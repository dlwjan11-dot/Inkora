/*
 * Inkora: the bridge between the app (Kotlin, MyPaintNative) and the MyPaint brush engine.
 */
#include <jni.h>
#include <stdlib.h>
#include <math.h>
#include <android/bitmap.h>
#include "mypaint-brush.h"
#include "mypaint-brush-settings.h"
#include "inkora_surface.h"

#define FN(name) Java_com_abdulmedia_inkora_MyPaintNative_##name
#define BRUSH(h) ((MyPaintBrush *)(intptr_t)(h))
#define SURF(h) ((InkoraSurface *)(intptr_t)(h))

/* ---------- brushes ---------- */

JNIEXPORT jlong JNICALL FN(nBrushNew)(JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    MyPaintBrush *b = mypaint_brush_new();
    if (b) mypaint_brush_from_defaults(b);
    return (jlong)(intptr_t)b;
}

JNIEXPORT void JNICALL FN(nBrushFree)(JNIEnv *env, jobject thiz, jlong b) {
    (void)env; (void)thiz;
    if (b) mypaint_brush_unref(BRUSH(b));
}

/* the brush's settings from a .myb file's text */
JNIEXPORT jboolean JNICALL FN(nBrushLoad)(JNIEnv *env, jobject thiz, jlong b, jstring json) {
    (void)thiz;
    const char *s = (*env)->GetStringUTFChars(env, json, NULL);
    if (!s) return JNI_FALSE;
    mypaint_brush_from_defaults(BRUSH(b));
    gboolean ok = mypaint_brush_from_string(BRUSH(b), s);
    (*env)->ReleaseStringUTFChars(env, json, s);
    return ok ? JNI_TRUE : JNI_FALSE;
}

static int setting_id(JNIEnv *env, jstring name) {
    const char *s = (*env)->GetStringUTFChars(env, name, NULL);
    if (!s) return -1;
    int id = mypaint_brush_setting_from_cname(s);
    (*env)->ReleaseStringUTFChars(env, name, s);
    return id;
}

/* a setting's base value by its name in .myb files ("radius_logarithmic", "smudge", ...) */
JNIEXPORT void JNICALL FN(nBrushSet)(JNIEnv *env, jobject thiz, jlong b, jstring name, jfloat v) {
    (void)thiz;
    int id = setting_id(env, name);
    if (id >= 0) mypaint_brush_set_base_value(BRUSH(b), (MyPaintBrushSetting)id, v);
}

JNIEXPORT jfloat JNICALL FN(nBrushGet)(JNIEnv *env, jobject thiz, jlong b, jstring name) {
    (void)thiz;
    int id = setting_id(env, name);
    return id >= 0 ? mypaint_brush_get_base_value(BRUSH(b), (MyPaintBrushSetting)id) : 0.f;
}

/* the paint color, 0..1 hue / saturation / value */
JNIEXPORT void JNICALL FN(nBrushColor)(JNIEnv *env, jobject thiz, jlong b, jfloat h, jfloat s, jfloat v) {
    (void)env; (void)thiz;
    mypaint_brush_set_base_value(BRUSH(b), MYPAINT_BRUSH_SETTING_COLOR_H, h);
    mypaint_brush_set_base_value(BRUSH(b), MYPAINT_BRUSH_SETTING_COLOR_S, s);
    mypaint_brush_set_base_value(BRUSH(b), MYPAINT_BRUSH_SETTING_COLOR_V, v);
}

/* ---------- surfaces ---------- */

JNIEXPORT jlong JNICALL FN(nSurfaceNew)(JNIEnv *env, jobject thiz, jint w, jint h) {
    (void)env; (void)thiz;
    return (jlong)(intptr_t)ink_surface_new(w, h);
}

JNIEXPORT void JNICALL FN(nSurfaceFree)(JNIEnv *env, jobject thiz, jlong s) {
    (void)env; (void)thiz;
    if (s) mypaint_surface_unref(ink_surface_interface(SURF(s)));
}

/* type: -1 off, 0 vertical, 1 horizontal, 2 both, 3 turned copies, 4 turned + mirrored */
JNIEXPORT void JNICALL FN(nSymmetry)(JNIEnv *env, jobject thiz, jlong s, jint type, jfloat cx, jfloat cy, jfloat angle, jint lines) {
    (void)env; (void)thiz;
    MyPaintTiledSurface *ts = (MyPaintTiledSurface *)SURF(s);
    if (type < 0) mypaint_tiled_surface_set_symmetry_state(ts, FALSE, cx, cy, 0, MYPAINT_SYMMETRY_TYPE_VERTICAL, 2);
    else mypaint_tiled_surface_set_symmetry_state(ts, TRUE, cx, cy, angle, (MyPaintSymmetryType)type, lines < 2 ? 2 : lines);
}

/* a new stroke: the brush forgets the last one, the surface reads the layer afresh */
JNIEXPORT void JNICALL FN(nBegin)(JNIEnv *env, jobject thiz, jlong s, jlong b) {
    (void)env; (void)thiz;
    ink_surface_reset(SURF(s));
    mypaint_brush_reset(BRUSH(b));
    mypaint_brush_new_stroke(BRUSH(b));
}

/*
 * Paints [n] pen events onto [layer]: ev holds x, y, pressure, xtilt, ytilt, seconds since the
 * last event, for each. out gets the changed rectangle (x, y, w, h). Returns false when nothing changed.
 */
JNIEXPORT jboolean JNICALL FN(nStroke)(JNIEnv *env, jobject thiz, jlong sh, jlong bh, jobject layer,
                                       jfloatArray events, jint n, jfloat viewzoom, jfloat viewrotation, jintArray out) {
    (void)thiz;
    InkoraSurface *s = SURF(sh);
    AndroidBitmapInfo info;
    void *pixels = NULL;
    if (AndroidBitmap_getInfo(env, layer, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return JNI_FALSE;
    if (AndroidBitmap_lockPixels(env, layer, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS || !pixels) return JNI_FALSE;
    ink_surface_attach(s, (uint8_t *)pixels, (int)info.stride);
    jfloat *ev = (*env)->GetFloatArrayElements(env, events, NULL);
    MyPaintSurface *ms = ink_surface_interface(s);
    mypaint_surface_begin_atomic(ms);
    for (int i = 0; i < n; i++) {
        const jfloat *e = ev + i * 6;
        if (i == 0 && ink_surface_take_fresh(s)) {
            /* the stroke starts here: a long pause with no pressure puts the brush exactly at
               the first pen position (its smoothing would otherwise drag it from the corner) */
            mypaint_brush_stroke_to(BRUSH(bh), ms, e[0], e[1], 0.f, e[3], e[4], 10.0, viewzoom, viewrotation, 0.f, TRUE);
        }
        mypaint_brush_stroke_to(BRUSH(bh), ms, e[0], e[1], e[2], e[3], e[4], e[5] > 0 ? e[5] : 0.001,
                                viewzoom, viewrotation, 0.f, TRUE);
    }
    MyPaintRectangle r = {0, 0, 0, 0};
    MyPaintRectangles roi = {1, &r};
    mypaint_surface_end_atomic(ms, &roi);
    (*env)->ReleaseFloatArrayElements(env, events, ev, JNI_ABORT);
    jboolean changed = roi.num_rectangles > 0 && r.width > 0 && r.height > 0;
    if (changed) ink_surface_write_back(s, r.x, r.y, r.width, r.height);
    ink_surface_detach(s);
    AndroidBitmap_unlockPixels(env, layer);
    jint o[4] = {r.x, r.y, r.width, r.height};
    (*env)->SetIntArrayRegion(env, out, 0, 4, o);
    return changed;
}

/* the layer as it was before this stroke, for a w x h rectangle at (x, y), into [dst] (w x h) */
JNIEXPORT jboolean JNICALL FN(nOriginal)(JNIEnv *env, jobject thiz, jlong sh, jobject layer, jobject dst, jint x, jint y) {
    (void)thiz;
    InkoraSurface *s = SURF(sh);
    AndroidBitmapInfo li, di;
    void *lp = NULL, *dp = NULL;
    if (AndroidBitmap_getInfo(env, layer, &li) != ANDROID_BITMAP_RESULT_SUCCESS ||
        AndroidBitmap_getInfo(env, dst, &di) != ANDROID_BITMAP_RESULT_SUCCESS ||
        di.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return JNI_FALSE;
    if (AndroidBitmap_lockPixels(env, layer, &lp) != ANDROID_BITMAP_RESULT_SUCCESS) return JNI_FALSE;
    if (AndroidBitmap_lockPixels(env, dst, &dp) != ANDROID_BITMAP_RESULT_SUCCESS) { AndroidBitmap_unlockPixels(env, layer); return JNI_FALSE; }
    ink_surface_attach(s, (uint8_t *)lp, (int)li.stride);
    ink_surface_original(s, (uint8_t *)dp, (int)di.stride, x, y, (int)di.width, (int)di.height);
    ink_surface_detach(s);
    AndroidBitmap_unlockPixels(env, dst);
    AndroidBitmap_unlockPixels(env, layer);
    return JNI_TRUE;
}

/* the stroke is over: the tiles are let go */
JNIEXPORT void JNICALL FN(nEnd)(JNIEnv *env, jobject thiz, jlong s) {
    (void)env; (void)thiz;
    ink_surface_reset(SURF(s));
}
