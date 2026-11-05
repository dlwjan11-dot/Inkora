/*
 * Inkora's native core, as the Swift side sees it: the MyPaint brush engine (libmypaint,
 * ISC license) painting on a layer's pixels, and the layer compositor.
 * Pixels everywhere: 8-bit premultiplied RGBA (R, G, B, A bytes).
 */
#ifndef INK_ENGINE_H
#define INK_ENGINE_H
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* ---------- brushes ---------- */
typedef struct InkBrush InkBrush;
InkBrush *ink_brush_new(void);
void ink_brush_free(InkBrush *b);
/* settings from a .myb file's text (on top of the engine defaults); 1 = ok */
int ink_brush_load(InkBrush *b, const char *json);
/* a setting's base value by its .myb name ("radius_logarithmic", "smudge", ...) */
void ink_brush_set(InkBrush *b, const char *name, float value);
float ink_brush_get(InkBrush *b, const char *name);
/* paint color: hue, saturation, value 0..1 */
void ink_brush_color(InkBrush *b, float h, float s, float v);

/* ---------- the canvas a stroke paints on ---------- */
typedef struct InkCanvas InkCanvas;
InkCanvas *ink_canvas_new(int width, int height);
void ink_canvas_free(InkCanvas *c);
/* symmetry: type -1 off, 0 vertical, 1 horizontal, 2 both, 3 turned copies, 4 turned + mirrored */
void ink_canvas_symmetry(InkCanvas *c, int type, float cx, float cy, float angle, int lines);
/* a new stroke: the brush forgets the last one, the canvas reads the layer afresh */
void ink_stroke_begin(InkCanvas *c, InkBrush *b);
/*
 * Paints n pen events onto the layer's pixels. events: x, y, pressure, xtilt (-1..1),
 * ytilt (-1..1), seconds since the previous event, for each. out: the changed rectangle
 * (x, y, w, h). Returns 1 when something changed.
 */
int ink_stroke(InkCanvas *c, InkBrush *b, uint8_t *pixels, int stride, const float *events, int n,
               float view_zoom, float view_rotation, int out[4]);
/* the layer as it was before this stroke, for the rectangle (x, y, w, h), into out */
void ink_stroke_original(InkCanvas *c, uint8_t *pixels, int stride, uint8_t *out, int out_stride,
                         int x, int y, int w, int h);
/* the stroke is over */
void ink_stroke_end(InkCanvas *c);

/* ---------- compositing layers ---------- */
enum {
    INK_BLEND_NORMAL = 0, INK_BLEND_MULTIPLY, INK_BLEND_SCREEN, INK_BLEND_OVERLAY,
    INK_BLEND_DARKEN, INK_BLEND_LIGHTEN, INK_BLEND_COLOR_DODGE, INK_BLEND_COLOR_BURN,
    INK_BLEND_HARD_LIGHT, INK_BLEND_SOFT_LIGHT, INK_BLEND_DIFFERENCE, INK_BLEND_ADD,
    INK_BLEND_COUNT
};

typedef struct {
    const uint8_t *pixels; /* the whole layer */
    int stride;
    float opacity;         /* 0..1 */
    int blend;
} InkLayerRef;

/*
 * Draws the layers (bottom first) over the background for the canvas rectangle (x, y, w, h)
 * into dst (w x h). background is 0xRRGGBBAA; alpha 0 leaves the result transparent.
 */
void ink_composite(uint8_t *dst, int dst_stride, int x, int y, int w, int h,
                   const InkLayerRef *layers, int count, uint32_t background);

/* the color at a canvas pixel of the composite (for the eyedropper), 0xRRGGBBAA */
uint32_t ink_pick(const InkLayerRef *layers, int count, uint32_t background, int x, int y);

/* fills the area around (x, y) that has about the same color in src with rgba into dst;
   tolerance 0..1. Returns the number of pixels filled. */
int ink_flood_fill(const uint8_t *src, uint8_t *dst, int width, int height, int stride,
                   int x, int y, uint32_t rgba, float tolerance);

#ifdef __cplusplus
}
#endif
#endif
