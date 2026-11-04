package com.abdulmedia.inkora

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.min

/**
 * Time-lapse: a small picture of the artwork is saved after every change,
 * and the pictures are turned into an MP4 video on request.
 */
object TimeLapse {

    private val writer = Executors.newSingleThreadExecutor()

    fun dir(ctx: Context, id: String) = File(File(ctx.filesDir, "timelapse"), id)

    fun frameCount(ctx: Context, id: String) = dir(ctx, id).list()?.count { it.endsWith(".jpg") } ?: 0

    fun clear(ctx: Context, id: String) {
        dir(ctx, id).deleteRecursively()
    }

    /** video size: fits in 1920x1080 (or 1080x1920), even numbers */
    fun frameSize(w: Int, h: Int): Pair<Int, Int> {
        val (bw, bh) = if (h > w) 1080 to 1920 else 1920 to 1080
        val s = min(1f, min(bw / w.toFloat(), bh / h.toFloat()))
        val fw = ((w * s).toInt() / 2 * 2).coerceAtLeast(2)
        val fh = ((h * s).toInt() / 2 * 2).coerceAtLeast(2)
        return fw to fh
    }

    /** Captures one frame (main thread: quick scaled drawing; the JPEG is written in the background). */
    fun capture(ctx: Context, doc: Document) {
        val id = doc.galleryId ?: return
        val (fw, fh) = frameSize(doc.width, doc.height)
        val frame = Bitmap.createBitmap(fw, fh, Bitmap.Config.ARGB_8888)
        val c = Canvas(frame)
        c.drawColor(0xFFFFFFFF.toInt())
        c.scale(fw / doc.width.toFloat(), fh / doc.height.toFloat())
        doc.draw(c, true)
        val app = ctx.applicationContext
        writer.execute {
            runCatching {
                val d = dir(app, id).apply { mkdirs() }
                val n = (d.list()?.size ?: 0) + 1
                File(d, String.format(Locale.US, "%06d.jpg", n)).outputStream().use {
                    frame.compress(Bitmap.CompressFormat.JPEG, 85, it)
                }
            }
            frame.recycle()
        }
    }

    /**
     * Encodes all frames into an H.264 MP4 in Movies/Inkora. Runs on a background thread.
     * [progress] gets 0..1. Returns the video's Uri, or null.
     */
    fun export(ctx: Context, id: String, title: String, progress: (Float) -> Unit): Uri? {
        val frames = dir(ctx, id).listFiles { f -> f.name.endsWith(".jpg") }?.sortedBy { it.name } ?: return null
        if (frames.isEmpty()) return null
        val first = BitmapFactory.decodeFile(frames[0].absolutePath) ?: return null
        val w = first.width / 2 * 2
        val h = first.height / 2 * 2
        first.recycle()

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "${title.replace(Regex("[^A-Za-z0-9 _-]"), "").ifBlank { "Inkora" }}_timelapse_$stamp.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Inkora")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val resolver = ctx.contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        val pfd = resolver.openFileDescriptor(uri, "rw") ?: return null

        val fps = 30
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, (w * h * 5).coerceAtMost(16_000_000))
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
        }
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var ok = false
        try {
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = codec.createInputSurface()
            codec.start()
            muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val state = MuxState()
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            val dst = RectF(0f, 0f, w.toFloat(), h.toFloat())
            // the finished picture stays on screen for 2.5 seconds
            val holdFrames = (fps * 2.5f).toInt()
            val total = frames.size + holdFrames
            var last: Bitmap? = null
            for (i in 0 until total) {
                val bmp = if (i < frames.size) {
                    last?.recycle()
                    BitmapFactory.decodeFile(frames[i].absolutePath).also { last = it }
                } else last
                val canvas = surface.lockHardwareCanvas()
                canvas.drawColor(0xFFFFFFFF.toInt())
                if (bmp != null) canvas.drawBitmap(bmp, null, dst, paint)
                surface.unlockCanvasAndPost(canvas)
                drain(codec, muxer, state, false, fps)
                if (i % 10 == 0) progress(i / total.toFloat())
            }
            last?.recycle()
            codec.signalEndOfInputStream()
            drain(codec, muxer, state, true, fps)
            surface.release()
            ok = state.started
        } catch (t: Throwable) {
            ok = false
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { pfd.close() }
        }
        if (!ok) {
            resolver.delete(uri, null, null)
            return null
        }
        values.clear()
        values.put(MediaStore.Video.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        progress(1f)
        return uri
    }

    private class MuxState {
        var track = -1
        var started = false
        var frameIndex = 0L
    }

    private fun drain(codec: MediaCodec, muxer: MediaMuxer, st: MuxState, end: Boolean, fps: Int) {
        val info = MediaCodec.BufferInfo()
        var idleTries = 0
        while (true) {
            val idx = codec.dequeueOutputBuffer(info, 10_000)
            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!end) {
                        // give the encoder a moment, but don't wait forever
                        if (++idleTries > 2) return
                    } else if (++idleTries > 300) return
                }
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    st.track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    st.started = true
                }
                idx >= 0 -> {
                    idleTries = 0
                    val buf = codec.getOutputBuffer(idx)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && st.started && buf != null) {
                        // our own clock: one picture per 1/fps second
                        info.presentationTimeUs = st.frameIndex * 1_000_000L / fps
                        st.frameIndex++
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        muxer.writeSampleData(st.track, buf, info)
                    }
                    codec.releaseOutputBuffer(idx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }
}
