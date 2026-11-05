/* Inkora: layer compositing with blend modes, the eyedropper and flood fill. */
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include "ink_engine.h"

static inline float blendf(int mode, float cb, float cs) {
    switch (mode) {
        case INK_BLEND_MULTIPLY: return cb * cs;
        case INK_BLEND_SCREEN: return cb + cs - cb * cs;
        case INK_BLEND_OVERLAY: return cb <= 0.5f ? 2.f * cb * cs : 1.f - 2.f * (1.f - cb) * (1.f - cs);
        case INK_BLEND_DARKEN: return cb < cs ? cb : cs;
        case INK_BLEND_LIGHTEN: return cb > cs ? cb : cs;
        case INK_BLEND_COLOR_DODGE:
            if (cb <= 0.f) return 0.f;
            if (cs >= 1.f) return 1.f;
            { float v = cb / (1.f - cs); return v > 1.f ? 1.f : v; }
        case INK_BLEND_COLOR_BURN:
            if (cb >= 1.f) return 1.f;
            if (cs <= 0.f) return 0.f;
            { float v = 1.f - (1.f - cb) / cs; return v < 0.f ? 0.f : v; }
        case INK_BLEND_HARD_LIGHT: return cs <= 0.5f ? 2.f * cb * cs : 1.f - 2.f * (1.f - cb) * (1.f - cs);
        case INK_BLEND_SOFT_LIGHT: {
            if (cs <= 0.5f) return cb - (1.f - 2.f * cs) * cb * (1.f - cb);
            float d = cb <= 0.25f ? ((16.f * cb - 12.f) * cb + 4.f) * cb : sqrtf(cb);
            return cb + (2.f * cs - 1.f) * (d - cb);
        }
        case INK_BLEND_DIFFERENCE: return fabsf(cb - cs);
        case INK_BLEND_ADD: { float v = cb + cs; return v > 1.f ? 1.f : v; }
        default: return cs;
    }
}

/* one source pixel (premultiplied 0..1, alpha already times opacity) over a destination one */
static inline void over(float *d, const float *s, int mode) {
    const float as = s[3], ab = d[3];
    if (as <= 0.f) return;
    if (mode == INK_BLEND_NORMAL || ab <= 0.f) {
        for (int c = 0; c < 4; c++) d[c] = s[c] + d[c] * (1.f - as);
        return;
    }
    if (mode == INK_BLEND_ADD) {
        /* add: light adds up (premultiplied), alpha as normal */
        for (int c = 0; c < 3; c++) { float v = d[c] + s[c]; d[c] = v > 1.f ? 1.f : v; }
        d[3] = as + ab * (1.f - as);
        return;
    }
    float r[4];
    for (int c = 0; c < 3; c++) {
        const float cs = s[c] / as, cb = d[c] / ab;
        r[c] = s[c] * (1.f - ab) + d[c] * (1.f - as) + as * ab * blendf(mode, cb, cs);
    }
    r[3] = as + ab * (1.f - as);
    for (int c = 0; c < 4; c++) d[c] = r[c] < 0.f ? 0.f : (r[c] > 1.f ? 1.f : r[c]);
}

void ink_composite(uint8_t *dst, int dst_stride, int x, int y, int w, int h,
                   const InkLayerRef *layers, int count, uint32_t bg) {
    const float inv = 1.f / 255.f;
    float bgp[4] = {0, 0, 0, 0};
    const float ba = (bg & 255) * inv;
    bgp[0] = ((bg >> 24) & 255) * inv * ba;
    bgp[1] = ((bg >> 16) & 255) * inv * ba;
    bgp[2] = ((bg >> 8) & 255) * inv * ba;
    bgp[3] = ba;
    float *row = (float *)malloc(sizeof(float) * 4 * (size_t)w);
    if (!row) return;
    for (int j = 0; j < h; j++) {
        for (int i = 0; i < w; i++) memcpy(row + i * 4, bgp, sizeof(bgp));
        for (int l = 0; l < count; l++) {
            const InkLayerRef *L = &layers[l];
            if (L->opacity <= 0.f || !L->pixels) continue;
            const uint8_t *src = L->pixels + (size_t)(y + j) * L->stride + (size_t)x * 4;
            const float k = L->opacity * inv;
            for (int i = 0; i < w; i++) {
                const uint8_t *p = src + i * 4;
                if (p[3] == 0) continue;
                float s[4] = {p[0] * k, p[1] * k, p[2] * k, p[3] * k};
                over(row + i * 4, s, L->blend);
            }
        }
        uint8_t *d = dst + (size_t)j * dst_stride;
        for (int i = 0; i < w * 4; i++) {
            float v = row[i] * 255.f + 0.5f;
            d[i] = (uint8_t)(v > 255.f ? 255.f : v);
        }
    }
    free(row);
}

uint32_t ink_pick(const InkLayerRef *layers, int count, uint32_t bg, int x, int y) {
    uint8_t px[4];
    ink_composite(px, 4, x, y, 1, 1, layers, count, bg);
    if (px[3] == 0) return 0;
    /* back to straight color */
    uint32_t r = px[0] * 255u / px[3], g = px[1] * 255u / px[3], b = px[2] * 255u / px[3];
    if (r > 255) r = 255; if (g > 255) g = 255; if (b > 255) b = 255;
    return (r << 24) | (g << 16) | (b << 8) | px[3];
}

static inline int similar(const uint8_t *a, const uint8_t *b, int tol) {
    for (int c = 0; c < 4; c++) if (abs((int)a[c] - (int)b[c]) > tol) return 0;
    return 1;
}

int ink_flood_fill(const uint8_t *src, uint8_t *dst, int w, int h, int stride,
                   int x, int y, uint32_t rgba, float tolerance) {
    if (x < 0 || y < 0 || x >= w || y >= h) return 0;
    const int tol = (int)(tolerance * 255.f + 0.5f);
    uint8_t seed[4];
    memcpy(seed, src + (size_t)y * stride + (size_t)x * 4, 4);
    const float a = (rgba & 255) / 255.f;
    const uint8_t fill[4] = {
        (uint8_t)(((rgba >> 24) & 255) * a + 0.5f), (uint8_t)(((rgba >> 16) & 255) * a + 0.5f),
        (uint8_t)(((rgba >> 8) & 255) * a + 0.5f), (uint8_t)(rgba & 255)
    };
    uint8_t *seen = (uint8_t *)calloc((size_t)w * h, 1);
    int *stack = (int *)malloc(sizeof(int) * 2 * 65536);
    int cap = 65536, top = 0, filled = 0;
    if (!seen || !stack) { free(seen); free(stack); return 0; }
    stack[top++] = x; stack[top++] = y;
    while (top > 0) {
        int py = stack[--top], px = stack[--top];
        int lx = px;
        while (lx > 0 && !seen[(size_t)py * w + lx - 1] && similar(src + (size_t)py * stride + (size_t)(lx - 1) * 4, seed, tol)) lx--;
        int rx = px;
        while (rx < w - 1 && !seen[(size_t)py * w + rx + 1] && similar(src + (size_t)py * stride + (size_t)(rx + 1) * 4, seed, tol)) rx++;
        for (int i = lx; i <= rx; i++) {
            if (seen[(size_t)py * w + i]) continue;
            seen[(size_t)py * w + i] = 1;
            memcpy(dst + (size_t)py * stride + (size_t)i * 4, fill, 4);
            filled++;
            for (int dy = -1; dy <= 1; dy += 2) {
                int ny = py + dy;
                if (ny < 0 || ny >= h || seen[(size_t)ny * w + i]) continue;
                if (!similar(src + (size_t)ny * stride + (size_t)i * 4, seed, tol)) continue;
                if (top + 2 > cap * 2) {
                    int *ns = (int *)realloc(stack, sizeof(int) * 4 * (size_t)cap);
                    if (!ns) { free(seen); free(stack); return filled; }
                    stack = ns; cap *= 2;
                }
                stack[top++] = i; stack[top++] = ny;
            }
        }
    }
    free(seen); free(stack);
    return filled;
}
