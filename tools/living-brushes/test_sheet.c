/* Test sheet for a brush: paints strokes with it and writes a PPM over paper.
   usage: harness brush.myb out.ppm */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include "mypaint-brush.h"
#include "inkora_surface.h"

#define W 900
#define H 600
static InkoraSurface *surf;
static MyPaintBrush *brush;

static void rgb_to_hsv(float r, float g, float b, float *h, float *s, float *v) {
    float mx = fmaxf(r, fmaxf(g, b)), mn = fminf(r, fminf(g, b)), d = mx - mn;
    *v = mx; *s = mx > 0 ? d / mx : 0;
    if (d == 0) { *h = 0; return; }
    float hh = mx == r ? fmodf((g - b) / d, 6) : mx == g ? (b - r) / d + 2 : (r - g) / d + 4;
    hh /= 6; if (hh < 0) hh += 1; *h = hh;
}
static void color(int rgb) {
    float h, s, v;
    rgb_to_hsv(((rgb >> 16) & 255) / 255.f, ((rgb >> 8) & 255) / 255.f, (rgb & 255) / 255.f, &h, &s, &v);
    mypaint_brush_set_base_value(brush, MYPAINT_BRUSH_SETTING_COLOR_H, h);
    mypaint_brush_set_base_value(brush, MYPAINT_BRUSH_SETTING_COLOR_S, s);
    mypaint_brush_set_base_value(brush, MYPAINT_BRUSH_SETTING_COLOR_V, v);
}
/* a stroke along a polyline, 120 events per second; pressure p0 -> p1 (with a hump in the middle) */
static void stroke(const float *pts, int n, float p0, float p1, float speed) {
    MyPaintSurface *s = ink_surface_interface(surf);
    mypaint_brush_reset(brush);
    mypaint_brush_new_stroke(brush);
    mypaint_surface_begin_atomic(s);
    mypaint_brush_stroke_to(brush, s, pts[0], pts[1], 0, 0, 0, 1.0, 1, 0, 0, 0);
    float total = 0; for (int i = 1; i < n; i++) total += hypotf(pts[i*2]-pts[i*2-2], pts[i*2+1]-pts[i*2-1]);
    float done = 0;
    for (int i = 1; i < n; i++) {
        float ax = pts[i*2-2], ay = pts[i*2-1], bx = pts[i*2], by = pts[i*2+1];
        float len = hypotf(bx-ax, by-ay); int k = (int)(len / (speed / 120.f)) + 1;
        for (int j = 1; j <= k; j++) {
            float t = j / (float)k, x = ax + (bx-ax)*t, y = ay + (by-ay)*t;
            float u = (done + len * t) / total;
            float p = p0 + (p1 - p0) * u; p *= 0.75f + 0.25f * sinf(u * 3.14159f);
            mypaint_brush_stroke_to(brush, s, x, y, p, 0, 0, 1.0 / 120, 1, 0, 0, 0);
            if (j % 8 == 0) { MyPaintRectangle rr = {0, 0, 0, 0}; MyPaintRectangles r = {1, &rr}; mypaint_surface_end_atomic(s, &r); mypaint_surface_begin_atomic(s); }
        }
        done += len;
    }
    mypaint_brush_stroke_to(brush, s, pts[n*2-2], pts[n*2-1], 0, 0, 0, 1.0 / 120, 1, 0, 0, 0);
    MyPaintRectangle rr = {0, 0, 0, 0}; MyPaintRectangles r = {1, &rr};
    mypaint_surface_end_atomic(s, &r);
}

int main(int argc, char **argv) {
    if (argc < 3) return 1;
    FILE *f = fopen(argv[1], "rb"); if (!f) { perror(argv[1]); return 1; }
    fseek(f, 0, SEEK_END); long n = ftell(f); fseek(f, 0, SEEK_SET);
    char *json = malloc(n + 1); fread(json, 1, n, f); json[n] = 0; fclose(f);
    surf = ink_surface_new(W, H);
    brush = mypaint_brush_new();
    float scale = 1.f;
    char *under = NULL;
    if (argc > 3) { /* a brush that paints the sheet first; the tested brush then works over it */
        FILE *g = fopen(argv[3], "rb"); fseek(g, 0, SEEK_END); long m = ftell(g); fseek(g, 0, SEEK_SET);
        under = malloc(m + 1); fread(under, 1, m, g); under[m] = 0; fclose(g);
        mypaint_brush_from_defaults(brush); mypaint_brush_from_string(brush, under);
    } else if (mypaint_brush_from_defaults(brush), !mypaint_brush_from_string(brush, json)) { fprintf(stderr, "bad brush\n"); return 1; }
    /* 1: a wavy line, light to hard pressure */
    float wave[2 * 40];
    for (int i = 0; i < 40; i++) { wave[i*2] = 60 + i * 20; wave[i*2+1] = 80 + 35 * sinf(i * 0.35f); }
    color(0x1f3a6b); stroke(wave, 40, 0.1f, 1.0f, 500 * scale);
    /* 2: bands of red, yellow, blue, then a stroke of white and of blue dragged through them */
    int cols[3] = {0xc8323a, 0xf0c419, 0x2a5db0};
    for (int c = 0; c < 3; c++) for (int k = 0; k < 3; k++) {
        float x = 120 + c * 230 + k * 22; float l[4] = {x, 190, x + 30, 380};
        color(cols[c]); stroke(l, 2, 0.8f, 0.9f, 700 * scale);
    }
    float across[6] = {80, 250, 450, 270, 830, 240};
    color(0xffffff); stroke(across, 3, 0.6f, 0.9f, 450 * scale);
    float across2[6] = {80, 330, 450, 345, 830, 320};
    color(0x2a5db0); stroke(across2, 3, 0.6f, 0.9f, 450 * scale);
    /* 3: a hatch, slow and fast */
    for (int k = 0; k < 6; k++) {
        float l[4] = {80 + k * 60, 560, 140 + k * 60, 440};
        color(0x2b2b2b); stroke(l, 2, 0.3f + k * 0.12f, 0.3f + k * 0.12f, (k < 3 ? 250 : 1600) * scale);
    }
    float spiral[2 * 60];
    for (int i = 0; i < 60; i++) { float a = i * 0.32f, r = 8 + i * 1.6f; spiral[i*2] = 640 + r * cosf(a); spiral[i*2+1] = 500 + r * sinf(a) * 0.8f; }
    color(0x2e7d4f); stroke(spiral, 60, 0.3f, 1.0f, 600 * scale);
    if (under) {
        mypaint_brush_from_defaults(brush); mypaint_brush_from_string(brush, json);
        color(0x808080);
        for (int k = 0; k < 5; k++) { float z[8] = {100 + k * 150, 170, 170 + k * 150, 300, 120 + k * 150, 400, 190 + k * 150, 560}; stroke(z, 4, 0.7f, 0.9f, 400); }
        float w2[6] = {60, 90, 450, 60, 850, 100}; stroke(w2, 3, 0.8f, 0.8f, 400);
    }
    /* out: over warm paper */
    unsigned char *px = malloc(W * H * 4);
    ink_surface_store(surf, px, W * 4, 0, 0, W, H);
    FILE *o = fopen(argv[2], "wb"); fprintf(o, "P6 %d %d 255\n", W, H);
    int paper[3] = {247, 244, 236};
    for (int i = 0; i < W * H; i++) {
        int a = px[i*4+3];
        for (int c = 0; c < 3; c++) fputc(px[i*4+c] + paper[c] * (255 - a) / 255, o);
    }
    fclose(o);
    return 0;
}
