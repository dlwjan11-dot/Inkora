#include <stdlib.h>
#include <string.h>
#include <math.h>
#include "ink_engine.h"
#include "mypaint-brush.h"
#include "mypaint-brush-settings.h"
#include "mypaint-tiled-surface.h"
#include "inkora_surface.h"

struct InkBrush { MyPaintBrush *b; };
struct InkCanvas { InkoraSurface *s; };

InkBrush *ink_brush_new(void) {
    InkBrush *r = (InkBrush *)calloc(1, sizeof(InkBrush));
    if (!r) return NULL;
    r->b = mypaint_brush_new();
    if (!r->b) { free(r); return NULL; }
    mypaint_brush_from_defaults(r->b);
    return r;
}

void ink_brush_free(InkBrush *b) {
    if (!b) return;
    mypaint_brush_unref(b->b);
    free(b);
}

int ink_brush_load(InkBrush *b, const char *json) {
    mypaint_brush_from_defaults(b->b);
    return mypaint_brush_from_string(b->b, json) ? 1 : 0;
}

void ink_brush_set(InkBrush *b, const char *name, float v) {
    int id = mypaint_brush_setting_from_cname(name);
    if (id >= 0) mypaint_brush_set_base_value(b->b, (MyPaintBrushSetting)id, v);
}

float ink_brush_get(InkBrush *b, const char *name) {
    int id = mypaint_brush_setting_from_cname(name);
    return id >= 0 ? mypaint_brush_get_base_value(b->b, (MyPaintBrushSetting)id) : 0.f;
}

void ink_brush_color(InkBrush *b, float h, float s, float v) {
    mypaint_brush_set_base_value(b->b, MYPAINT_BRUSH_SETTING_COLOR_H, h);
    mypaint_brush_set_base_value(b->b, MYPAINT_BRUSH_SETTING_COLOR_S, s);
    mypaint_brush_set_base_value(b->b, MYPAINT_BRUSH_SETTING_COLOR_V, v);
}

InkCanvas *ink_canvas_new(int w, int h) {
    InkCanvas *c = (InkCanvas *)calloc(1, sizeof(InkCanvas));
    if (!c) return NULL;
    c->s = ink_surface_new(w, h);
    if (!c->s) { free(c); return NULL; }
    return c;
}

void ink_canvas_free(InkCanvas *c) {
    if (!c) return;
    mypaint_surface_unref(ink_surface_interface(c->s));
    free(c);
}

void ink_canvas_symmetry(InkCanvas *c, int type, float cx, float cy, float angle, int lines) {
    MyPaintTiledSurface *ts = (MyPaintTiledSurface *)c->s;
    if (type < 0) mypaint_tiled_surface_set_symmetry_state(ts, FALSE, cx, cy, 0, MYPAINT_SYMMETRY_TYPE_VERTICAL, 2);
    else mypaint_tiled_surface_set_symmetry_state(ts, TRUE, cx, cy, angle, (MyPaintSymmetryType)type, lines < 2 ? 2 : lines);
}

void ink_stroke_begin(InkCanvas *c, InkBrush *b) {
    ink_surface_reset(c->s);
    mypaint_brush_reset(b->b);
    mypaint_brush_new_stroke(b->b);
}

int ink_stroke(InkCanvas *c, InkBrush *b, uint8_t *pixels, int stride, const float *ev, int n,
               float view_zoom, float view_rotation, int out[4]) {
    ink_surface_attach(c->s, pixels, stride);
    MyPaintSurface *ms = ink_surface_interface(c->s);
    mypaint_surface_begin_atomic(ms);
    for (int i = 0; i < n; i++) {
        const float *e = ev + i * 6;
        mypaint_brush_stroke_to(b->b, ms, e[0], e[1], e[2], e[3], e[4], e[5] > 0 ? e[5] : 0.001,
                                view_zoom, view_rotation, 0.f, TRUE);
    }
    MyPaintRectangle r = {0, 0, 0, 0};
    MyPaintRectangles roi = {1, &r};
    mypaint_surface_end_atomic(ms, &roi);
    int changed = roi.num_rectangles > 0 && r.width > 0 && r.height > 0;
    if (changed) ink_surface_write_back(c->s, r.x, r.y, r.width, r.height);
    ink_surface_detach(c->s);
    out[0] = r.x; out[1] = r.y; out[2] = r.width; out[3] = r.height;
    return changed;
}

void ink_stroke_original(InkCanvas *c, uint8_t *pixels, int stride, uint8_t *out, int out_stride,
                         int x, int y, int w, int h) {
    (void)w; (void)h;
    ink_surface_attach(c->s, pixels, stride);
    ink_surface_original(c->s, out, out_stride, x, y, w, h);
    ink_surface_detach(c->s);
}

void ink_stroke_end(InkCanvas *c) { ink_surface_reset(c->s); }
