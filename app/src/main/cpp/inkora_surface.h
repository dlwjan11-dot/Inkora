/*
 * Inkora: the canvas the MyPaint engine paints on. It works on one layer's pixels
 * (8-bit premultiplied RGBA, as an Android bitmap holds them). Tiles of the layer are read
 * into the engine's 16-bit format the first time a stroke touches them, painted, and written
 * back; the layer as it was before the stroke is kept for undo.
 */
#ifndef INKORA_SURFACE_H
#define INKORA_SURFACE_H
#include <stdint.h>
#include "mypaint-tiled-surface.h"

typedef struct InkoraSurface InkoraSurface;

InkoraSurface *ink_surface_new(int width, int height);
MyPaintSurface *ink_surface_interface(InkoraSurface *self);
void ink_surface_free(InkoraSurface *self);

/* the layer's pixels for the calls that follow (they must stay valid until the next call) */
void ink_surface_attach(InkoraSurface *self, uint8_t *pixels, int stride);
void ink_surface_detach(InkoraSurface *self);

/* writes the painted tiles in a rectangle back to the attached pixels */
void ink_surface_write_back(InkoraSurface *self, int x, int y, int w, int h);

/* the rectangle as it was before this stroke, into out (w x h, its own stride) */
void ink_surface_original(InkoraSurface *self, uint8_t *out, int out_stride, int x, int y, int w, int h);

/* forgets the tiles: the next stroke starts from the layer again */
void ink_surface_reset(InkoraSurface *self);
#endif
