#include <stdlib.h>
#include <string.h>
#include "inkora_surface.h"

typedef struct {
    uint16_t *px;   /* 16-bit premultiplied RGBA, what the engine paints on */
    uint8_t *orig;  /* the same pixels as they were when the stroke first touched them */
} Tile;

struct InkoraSurface {
    MyPaintTiledSurface parent;
    Tile *tiles;
    uint16_t *spare;   /* for tiles outside the canvas: painting there is thrown away */
    int ts, tw, th, width, height;
    uint8_t *src;      /* the layer's pixels while attached */
    int fresh;         /* no pen position given since the last reset */
    int stride;
};

static inline uint16_t up(uint8_t v) { return (uint16_t)((v * (1u << 15) + 127) / 255); }
static inline uint8_t down(uint32_t v) { return (uint8_t)((v * 255 + (1u << 14)) >> 15); }

static Tile *load(InkoraSurface *s, int tx, int ty) {
    Tile *t = &s->tiles[ty * s->tw + tx];
    if (t->px) return t;
    const int ts = s->ts;
    t->px = (uint16_t *)calloc((size_t)ts * ts * 4, sizeof(uint16_t));
    t->orig = (uint8_t *)calloc((size_t)ts * ts * 4, 1);
    if (!t->px || !t->orig) { free(t->px); free(t->orig); t->px = NULL; t->orig = NULL; return NULL; }
    if (s->src) {
        const int x0 = tx * ts, y0 = ty * ts;
        const int w = (x0 + ts > s->width ? s->width - x0 : ts);
        const int h = (y0 + ts > s->height ? s->height - y0 : ts);
        for (int j = 0; j < h; j++) {
            const uint8_t *row = s->src + (size_t)(y0 + j) * s->stride + (size_t)x0 * 4;
            memcpy(t->orig + (size_t)j * ts * 4, row, (size_t)w * 4);
            uint16_t *d = t->px + (size_t)j * ts * 4;
            for (int i = 0; i < w * 4; i++) d[i] = up(row[i]);
        }
    }
    return t;
}

static void req_start(MyPaintTiledSurface *ts, MyPaintTileRequest *r) {
    InkoraSurface *s = (InkoraSurface *)ts;
    Tile *t = NULL;
    if (r->tx >= 0 && r->ty >= 0 && r->tx < s->tw && r->ty < s->th) t = load(s, r->tx, r->ty);
    if (t) r->buffer = t->px;
    else {
        memset(s->spare, 0, (size_t)s->ts * s->ts * 4 * sizeof(uint16_t));
        r->buffer = s->spare;
    }
}

static void req_end(MyPaintTiledSurface *ts, MyPaintTileRequest *r) { (void)ts; (void)r; }

static void destroy(MyPaintSurface *surface) { ink_surface_free((InkoraSurface *)surface); }

InkoraSurface *ink_surface_new(int width, int height) {
    InkoraSurface *s = (InkoraSurface *)calloc(1, sizeof(InkoraSurface));
    if (!s) return NULL;
    mypaint_tiled_surface_init(&s->parent, req_start, req_end);
    s->parent.parent.destroy = destroy;
    s->ts = s->parent.tile_size;
    s->width = width; s->height = height;
    s->tw = (width + s->ts - 1) / s->ts;
    s->th = (height + s->ts - 1) / s->ts;
    s->tiles = (Tile *)calloc((size_t)s->tw * s->th, sizeof(Tile));
    s->spare = (uint16_t *)calloc((size_t)s->ts * s->ts * 4, sizeof(uint16_t));
    if (!s->tiles || !s->spare) { free(s->tiles); free(s->spare); free(s); return NULL; }
    return s;
}

MyPaintSurface *ink_surface_interface(InkoraSurface *self) { return (MyPaintSurface *)self; }

int ink_surface_take_fresh(InkoraSurface *s) {
    int f = s->fresh;
    s->fresh = 0;
    return f;
}

void ink_surface_reset(InkoraSurface *s) {
    s->fresh = 1;
    for (int i = 0; i < s->tw * s->th; i++) {
        free(s->tiles[i].px); free(s->tiles[i].orig);
        s->tiles[i].px = NULL; s->tiles[i].orig = NULL;
    }
}

void ink_surface_free(InkoraSurface *s) {
    if (!s) return;
    ink_surface_reset(s);
    mypaint_tiled_surface_destroy(&s->parent);
    free(s->tiles); free(s->spare); free(s);
}

void ink_surface_attach(InkoraSurface *s, uint8_t *pixels, int stride) { s->src = pixels; s->stride = stride; }
void ink_surface_detach(InkoraSurface *s) { s->src = NULL; }

static int clip(InkoraSurface *s, int *x, int *y, int *w, int *h) {
    if (*x < 0) { *w += *x; *x = 0; }
    if (*y < 0) { *h += *y; *y = 0; }
    if (*x + *w > s->width) *w = s->width - *x;
    if (*y + *h > s->height) *h = s->height - *y;
    return *w > 0 && *h > 0;
}

void ink_surface_write_back(InkoraSurface *s, int x, int y, int w, int h) {
    if (!s->src || !clip(s, &x, &y, &w, &h)) return;
    const int ts = s->ts;
    for (int py = y; py < y + h; py++) {
        uint8_t *row = s->src + (size_t)py * s->stride;
        for (int px = x; px < x + w; px++) {
            const Tile *t = &s->tiles[(py / ts) * s->tw + px / ts];
            if (!t->px) { px = (px / ts) * ts + ts - 1; continue; } /* untouched tile: skip it */
            const uint16_t *p = t->px + ((size_t)(py % ts) * ts + px % ts) * 4;
            uint32_t a = p[3] > (1u << 15) ? (1u << 15) : p[3];
            uint8_t *d = row + (size_t)px * 4;
            for (int c = 0; c < 3; c++) d[c] = down(p[c] > a ? a : p[c]); /* premultiplied: never above alpha */
            d[3] = down(a);
        }
    }
}

void ink_surface_original(InkoraSurface *s, uint8_t *out, int out_stride, int x, int y, int w, int h) {
    const int ox = x, oy = y;
    if (!clip(s, &x, &y, &w, &h)) return;
    const int ts = s->ts;
    for (int py = y; py < y + h; py++) {
        uint8_t *d = out + (size_t)(py - oy) * out_stride + (size_t)(x - ox) * 4;
        for (int px = x; px < x + w; px++, d += 4) {
            const Tile *t = &s->tiles[(py / ts) * s->tw + px / ts];
            const uint8_t *p = t->orig ? t->orig + ((size_t)(py % ts) * ts + px % ts) * 4
                             : (s->src ? s->src + (size_t)py * s->stride + (size_t)px * 4 : NULL);
            if (p) memcpy(d, p, 4); else memset(d, 0, 4);
        }
    }
}
