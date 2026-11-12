package com.abdulmedia.inkora

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.HardwareBuffer
import android.media.Image
import android.media.ImageReader
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.opengl.GLUtils
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The GPU brush engine: the stroke being drawn is painted by the graphics chip, each dab once.
 *
 * The brush engine still works out every dab (where, how big, turned, which color, how strong);
 * it draws them on [dabCanvas], which only writes them down. A thread of its own hands them to
 * OpenGL, which stamps the brush shape into a texture the size of the artwork that stays on the
 * chip for the whole stroke. Once a frame that texture is copied to a buffer the screen can show
 * directly ([shown], a hardware bitmap): nothing goes through main memory while the pen moves.
 *
 * When the stroke ends, only the part it touched is read back ([readInto]) for the usual commit.
 * Anything that fails turns the engine off ([ok] = false); the caller then paints the old way.
 */
class GlStroke(private val width: Int, private val height: Int, private val onFrame: () -> Unit) {

    @Volatile var ok = false
        private set

    /** the stroke as the screen should show it now (null: nothing yet) */
    @Volatile var shown: Bitmap? = null
        private set

    // ---------------------------------------------------------------- dabs, written down

    /** one dab: its brush shape, the four corners (x, y), and its color (premultiplied r, g, b, a) */
    private class Dab(val tip: Bitmap, val q: FloatArray, val r: Float, val g: Float, val b: Float, val a: Float)

    private val pending = ArrayList<Dab>()
    private val lock = Any()
    private val corners = FloatArray(8)

    /** the brush engine draws here; a dab is a brush shape drawn with a matrix and a paint */
    val dabCanvas: Canvas = object : Canvas() {
        override fun drawBitmap(bitmap: Bitmap, matrix: Matrix, paint: Paint?) {
            val c = paint?.color ?: 0xFF000000.toInt()
            val a = ((c ushr 24) and 0xFF) / 255f
            if (a <= 0f) return
            val w = bitmap.width.toFloat(); val h = bitmap.height.toFloat()
            corners[0] = 0f; corners[1] = 0f; corners[2] = w; corners[3] = 0f
            corners[4] = w; corners[5] = h; corners[6] = 0f; corners[7] = h
            matrix.mapPoints(corners)
            val d = Dab(bitmap, corners.copyOf(),
                ((c shr 16) and 0xFF) / 255f * a, ((c shr 8) and 0xFF) / 255f * a, (c and 0xFF) / 255f * a, a)
            synchronized(lock) { pending.add(d) }
            requestFrame()
        }
    }

    // ---------------------------------------------------------------- the thread

    private val thread = HandlerThread("inkora-gl").apply { start() }
    private val gl = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var framePosted = false

    private fun requestFrame() {
        if (framePosted || !ok) return
        framePosted = true
        gl.post { framePosted = false; runCatching { renderFrame() }.onFailure { fail() } }
    }

    private fun fail() {
        ok = false
        main.post { shown = null; onFrame() }
    }

    // ---------------------------------------------------------------- OpenGL

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var window: EGLSurface = EGL14.EGL_NO_SURFACE
    private var reader: ImageReader? = null
    private var fbo = 0
    private var fboTex = 0
    private var dabProgram = 0
    private var blitProgram = 0
    private val tips = HashMap<Bitmap, Int>()
    private var verts: FloatBuffer = ByteBuffer.allocateDirect(4 * 8 * 6 * 512).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val quad: FloatBuffer = ByteBuffer.allocateDirect(4 * 8).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0)
    }

    /** sets up OpenGL (waits for it); false: this device can't, the old way stays */
    fun start(): Boolean {
        val done = CountDownLatch(1)
        gl.post { ok = runCatching { setUp() }.getOrDefault(false); done.countDown() }
        done.await(2, TimeUnit.SECONDS)
        return ok
    }

    private fun setUp(): Boolean {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val v = IntArray(2)
        if (!EGL14.eglInitialize(display, v, 0, v, 1)) return false
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, 0x40 /* EGL_OPENGL_ES3_BIT */,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT, EGL14.EGL_NONE)
        val configs = arrayOfNulls<EGLConfig>(1); val n = IntArray(1)
        if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, n, 0) || n[0] == 0) return false
        val config = configs[0] ?: return false
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        if (context == EGL14.EGL_NO_CONTEXT) return false
        // the screen's buffer: an image reader whose pictures the screen draws as they are
        val r = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 4,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)
        reader = r
        r.setOnImageAvailableListener({ rd -> runCatching { took(rd) } }, gl)
        window = EGL14.eglCreateWindowSurface(display, config, r.surface, intArrayOf(EGL14.EGL_NONE), 0)
        if (window == EGL14.EGL_NO_SURFACE) return false
        if (!EGL14.eglMakeCurrent(display, window, window, context)) return false
        EGL14.eglSwapInterval(display, 0)

        // the stroke's texture, kept on the chip
        val t = IntArray(1)
        GLES30.glGenTextures(1, t, 0); fboTex = t[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, fboTex)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, width, height, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        val f = IntArray(1)
        GLES30.glGenFramebuffers(1, f, 0); fbo = f[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, fboTex, 0)
        if (GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) != GLES30.GL_FRAMEBUFFER_COMPLETE) return false
        GLES30.glClearColor(0f, 0f, 0f, 0f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

        dabProgram = program(
            """#version 300 es
            in vec2 aPos; in vec2 aUv; in vec4 aCol;
            uniform vec2 uSize;
            out vec2 vUv; out vec4 vCol;
            void main() { vUv = aUv; vCol = aCol; gl_Position = vec4(aPos / uSize * 2.0 - 1.0, 0.0, 1.0); }""",
            """#version 300 es
            precision mediump float;
            in vec2 vUv; in vec4 vCol;
            uniform sampler2D uTip;
            out vec4 o;
            void main() { o = vCol * texture(uTip, vUv).a; }""")
        blitProgram = program(
            """#version 300 es
            in vec2 aPos;
            out vec2 vUv;
            void main() { vUv = vec2((aPos.x + 1.0) * 0.5, 1.0 - (aPos.y + 1.0) * 0.5); gl_Position = vec4(aPos, 0.0, 1.0); }""",
            """#version 300 es
            precision mediump float;
            in vec2 vUv;
            uniform sampler2D uTex;
            out vec4 o;
            void main() { o = texture(uTex, vUv); }""")
        if (dabProgram == 0 || blitProgram == 0) return false
        return GLES30.glGetError() == GLES30.GL_NO_ERROR
    }

    private fun program(vs: String, fs: String): Int {
        fun shader(type: Int, src: String): Int {
            val s = GLES30.glCreateShader(type)
            GLES30.glShaderSource(s, src)
            GLES30.glCompileShader(s)
            val st = IntArray(1); GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, st, 0)
            return if (st[0] == 0) 0 else s
        }
        val v = shader(GLES30.GL_VERTEX_SHADER, vs); val f = shader(GLES30.GL_FRAGMENT_SHADER, fs)
        if (v == 0 || f == 0) return 0
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, v); GLES30.glAttachShader(p, f)
        GLES30.glBindAttribLocation(p, 0, "aPos"); GLES30.glBindAttribLocation(p, 1, "aUv"); GLES30.glBindAttribLocation(p, 2, "aCol")
        GLES30.glLinkProgram(p)
        val st = IntArray(1); GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, st, 0)
        return if (st[0] == 0) 0 else p
    }

    /** a brush shape as a texture (kept while the engine lives) */
    private fun tipTexture(b: Bitmap): Int = tips.getOrPut(b) {
        val t = IntArray(1)
        GLES30.glGenTextures(1, t, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t[0])
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, b, 0)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        t[0]
    }

    /** stamps the dabs written down so far into the stroke's texture */
    private fun drawDabs() {
        val list: List<Dab>
        synchronized(lock) { if (pending.isEmpty()) return; list = ArrayList(pending); pending.clear() }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glViewport(0, 0, width, height)
        GLES30.glUseProgram(dabProgram)
        GLES30.glUniform2f(GLES30.glGetUniformLocation(dabProgram, "uSize"), width.toFloat(), height.toFloat())
        GLES30.glUniform1i(GLES30.glGetUniformLocation(dabProgram, "uTip"), 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glEnable(GLES30.GL_BLEND)
        // the colors are premultiplied: one dab over the others, like painting
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        var i = 0
        while (i < list.size) {
            val tip = list[i].tip
            var j = i
            while (j < list.size && list[j].tip === tip) j++
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tipTexture(tip))
            val count = j - i
            val need = count * 6 * 8
            if (verts.capacity() < need) verts = ByteBuffer.allocateDirect(need * 4 * 2).order(ByteOrder.nativeOrder()).asFloatBuffer()
            verts.clear()
            for (k in i until j) {
                val d = list[k]; val q = d.q
                // two triangles: corners 0 1 2 and 0 2 3, with the shape's corners (0,0) (1,0) (1,1) (0,1)
                fun v(x: Float, y: Float, u: Float, w: Float) { verts.put(x).put(y).put(u).put(w).put(d.r).put(d.g).put(d.b).put(d.a) }
                v(q[0], q[1], 0f, 0f); v(q[2], q[3], 1f, 0f); v(q[4], q[5], 1f, 1f)
                v(q[0], q[1], 0f, 0f); v(q[4], q[5], 1f, 1f); v(q[6], q[7], 0f, 1f)
            }
            verts.flip()
            val stride = 8 * 4
            verts.position(0); GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, stride, verts); GLES30.glEnableVertexAttribArray(0)
            verts.position(2); GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, stride, verts); GLES30.glEnableVertexAttribArray(1)
            verts.position(4); GLES30.glVertexAttribPointer(2, 4, GLES30.GL_FLOAT, false, stride, verts); GLES30.glEnableVertexAttribArray(2)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, count * 6)
            i = j
        }
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    /** a frame: new dabs into the texture, then the texture to the screen's buffer */
    private fun renderFrame() {
        if (!ok) return
        drawDabs()
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, width, height)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glUseProgram(blitProgram)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, fboTex)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(blitProgram, "uTex"), 0)
        GLES30.glDisableVertexAttribArray(1); GLES30.glDisableVertexAttribArray(2)
        quad.position(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, quad)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        EGL14.eglSwapBuffers(display, window)
    }

    // ---------------------------------------------------------------- pictures for the screen

    /** when the stroke was last emptied: pictures from before that are not shown */
    @Volatile private var clearedAt = 0L
    private var current: Image? = null
    private var previous: Image? = null

    /** a new picture is ready: the screen shows it; the one before the last goes back */
    private fun took(rd: ImageReader) {
        val img = rd.acquireLatestImage() ?: return
        if (img.timestamp in 1 until clearedAt) { img.close(); return }
        val hb = img.hardwareBuffer
        val bmp = hb?.let { Bitmap.wrapHardwareBuffer(it, ColorSpace.get(ColorSpace.Named.SRGB)) }
        hb?.close()
        if (bmp == null) { img.close(); return }
        main.post {
            if (!ok) { img.close(); return@post }
            previous?.close()
            previous = current
            current = img
            shown = bmp
            onFrame()
        }
    }

    // ---------------------------------------------------------------- the stroke's start and end

    /** empties the stroke (a new stroke, or after it was committed) */
    fun clear() {
        synchronized(lock) { pending.clear() }
        shown = null
        if (!ok) return
        gl.post {
            runCatching {
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
                GLES30.glClearColor(0f, 0f, 0f, 0f)
                GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
                clearedAt = System.nanoTime()
            }
        }
    }

    /**
     * Copies the part [area] of the stroke into [into] (the artwork's size) for the commit; waits.
     * False: it could not (the caller paints the stroke again the old way).
     */
    fun readInto(into: Bitmap, area: Rect): Boolean {
        if (!ok) return false
        val r = Rect(area)
        if (!r.intersect(0, 0, width, height) || r.isEmpty) return true
        val buf = ByteBuffer.allocateDirect(r.width() * r.height() * 4).order(ByteOrder.nativeOrder())
        var done = false
        val latch = CountDownLatch(1)
        gl.post {
            done = runCatching {
                drawDabs()
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
                GLES30.glPixelStorei(GLES30.GL_PACK_ALIGNMENT, 1)
                GLES30.glReadPixels(r.left, r.top, r.width(), r.height(), GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
                GLES30.glGetError() == GLES30.GL_NO_ERROR
            }.getOrDefault(false)
            latch.countDown()
        }
        if (!latch.await(1500, TimeUnit.MILLISECONDS) || !done) return false
        return runCatching {
            buf.position(0)
            val part = Bitmap.createBitmap(r.width(), r.height(), Bitmap.Config.ARGB_8888)
            part.copyPixelsFromBuffer(buf)
            Canvas(into).drawBitmap(part, r.left.toFloat(), r.top.toFloat(), Paint().apply { blendMode = BlendMode.SRC })
            part.recycle()
            true
        }.getOrDefault(false)
    }

    fun release() {
        ok = false
        shown = null
        gl.post {
            runCatching {
                if (fbo != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
                if (fboTex != 0) GLES30.glDeleteTextures(1, intArrayOf(fboTex), 0)
                for (t in tips.values) GLES30.glDeleteTextures(1, intArrayOf(t), 0)
                tips.clear()
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (window != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, window)
                if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            }
            main.post { current?.close(); previous?.close(); current = null; previous = null }
            runCatching { reader?.close() }
            thread.quitSafely()
        }
    }
}
