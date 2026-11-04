package com.abdulmedia.inkora

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes brushes in Procreate's file format: a .brush is a zip with Brush.archive (the settings,
 * as an NSKeyedArchiver plist), Shape.png and Grain.png (white paints, black doesn't), and a
 * thumbnail; a .brushset holds several, listed in brushset.plist. Inkora reads them back with all
 * of their settings.
 */
object BrushExport {

    /** Procreate's names for a brush's settings (BrushImport reads the same ones). */
    fun fields(b: Brush): Map<String, Any?> {
        val d = b.dyn
        val sliderFactor = 0.15f + 0.85f * 0.25f // the size slider halfway, as BrushImport reads it
        val grainC = ((b.grainScale - 0.3f) / 3f).coerceIn(0f, 1f)
        return linkedMapOf(
            "name" to b.preset.name,
            "maxSize" to (b.size / 262f / sliderFactor).toDouble(),
            "minSize" to 0.0,
            "paintSize" to 0.5,
            "paintOpacity" to b.opacity.toDouble(),
            "plotSpacing" to b.spacing.toDouble(),
            "plotJitter" to b.scatter.toDouble(),
            "plotSmoothing" to b.streamline.toDouble(),
            "plotMovingAverageStabilization" to d.stabilization.toDouble(),
            "plotFFTSmoothingAmount" to d.motionFilter.toDouble(),
            "dynamicsPressureSize" to b.sizePressure.toDouble(),
            "dynamicsPressureOpacity" to b.flowPressure.toDouble(),
            "dynamicsGlazedFlow" to b.flow.toDouble(),
            "shapeRotation" to (if (b.rotation == RotationMode.FOLLOW) 1.0 else 0.0),
            "shapeRandomise" to (b.rotation == RotationMode.RANDOM),
            "shapeAngle" to Math.toRadians(b.angle.toDouble()),
            "shapeRoundness" to d.roundness.toDouble(),
            "shapeFlipXJitter" to d.flipX,
            "shapeFlipYJitter" to d.flipY,
            "pencilTaperStartLength" to (d.taperLength / 8f).toDouble(),
            "taperStartLength" to (d.taperLength / 8f).toDouble(),
            "pencilTaperSize" to d.taperSize.toDouble(),
            "pencilTaperOpacity" to d.taperOpacity.toDouble(),
            "textureScale" to (16f * grainC * grainC).toDouble(),
            "grainDepth" to d.grainDepth.toDouble(),
            "dynamicsJitterHue" to d.hueJitter.toDouble(),
            "dynamicsJitterSaturation" to d.satJitter.toDouble(),
            "dynamicsJitterLightness" to d.lightJitter.toDouble(),
            "dynamicsJitterStrokeHue" to d.strokeHueJitter.toDouble(),
            "dynamicsJitterStrokeLightness" to d.strokeLightJitter.toDouble(),
            "dynamicsPressureHue" to d.pressureHue.toDouble(),
            "dynamicsPressureSaturation" to d.pressureSat.toDouble(),
            "dynamicsPressureBrightness" to d.pressureBright.toDouble(),
            "dynamicsTiltBrightness" to d.tiltBright.toDouble(),
            "dynamicsMix" to d.wetDilution.toDouble(),
            "dynamicsLoad" to d.wetCharge.toDouble(),
            "dynamicsWetAccumulation" to d.wetPull.toDouble(),
            "dynamicsJitterSize" to d.sizeJitter.toDouble(),
            "dynamicsJitterOpacity" to d.opacityJitter.toDouble(),
            "inkoraBlend" to d.blend.name,
            "inkoraTiltShading" to d.tiltShading,
            "inkoraPressureCurve" to d.pressureCurve.toDouble(),
            "inkoraDualSize" to d.dualSize.toDouble(),
            "inkoraDualScatter" to d.dualScatter.toDouble(),
            "bundledShapePath" to null,
            "bundledGrainPath" to null,
            "shapeInverted" to false,
            "textureInverted" to false
        )
    }

    /** an alpha image as Procreate keeps shapes and grains: white where paint goes, on black */
    private fun png(alpha: Bitmap): ByteArray {
        val out = Bitmap.createBitmap(alpha.width, alpha.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.BLACK)
        c.drawBitmap(alpha, 0f, 0f, Paint().apply { color = Color.WHITE })
        val bos = ByteArrayOutputStream()
        out.compress(Bitmap.CompressFormat.PNG, 100, bos)
        out.recycle()
        return bos.toByteArray()
    }

    private fun plainPng(b: Bitmap): ByteArray {
        val bos = ByteArrayOutputStream()
        b.compress(Bitmap.CompressFormat.PNG, 100, bos)
        return bos.toByteArray()
    }

    private fun ZipOutputStream.put(name: String, data: ByteArray) {
        putNextEntry(ZipEntry(name))
        write(data)
        closeEntry()
    }

    private fun writeBrush(zip: ZipOutputStream, prefix: String, b: Brush) {
        zip.put(prefix + "Brush.archive", BPlist.keyedArchive("SilicaBrush", fields(b)))
        zip.put(prefix + "Shape.png", png(b.tip()))
        b.grain()?.let { zip.put(prefix + "Grain.png", png(it)) }
        runCatching { zip.put(prefix + "QuickLook/Thumbnail.png", plainPng(BrushPanel.render(b))) }
        // the dual brush's second shape, as Procreate keeps a dual brush's second brush
        b.dualTip()?.let { dual ->
            val sub = linkedMapOf<String, Any?>("name" to "${b.preset.name} (dual)", "plotSpacing" to 0.1, "bundledShapePath" to null)
            zip.put(prefix + "Sub01/Brush.archive", BPlist.keyedArchive("SilicaBrush", sub))
            zip.put(prefix + "Sub01/Shape.png", png(dual))
        }
    }

    /** one brush as a .brush file */
    fun writeBrush(b: Brush, out: OutputStream) {
        ZipOutputStream(out).use { zip -> writeBrush(zip, "", b) }
    }

    /** several brushes as a .brushset named [name] */
    fun writeSet(name: String, brushes: List<Brush>, out: OutputStream) {
        ZipOutputStream(out).use { zip ->
            val ids = brushes.map { UUID.randomUUID().toString().uppercase() }
            zip.put("brushset.plist", BPlist.write(mapOf("name" to name, "brushes" to ids)))
            brushes.forEachIndexed { i, b -> writeBrush(zip, ids[i] + "/", b) }
        }
    }

    /** a file name for [name] */
    fun fileName(name: String, ext: String) =
        name.replace(Regex("[\\\\/:*?\"<>|]"), " ").trim().ifEmpty { "Brush" } + ext
}
