package com.abdulmedia.inkora

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

enum class TipShape {
    HARD, SOFT, MARKER, PENCIL, CHARCOAL, FLAT, SPRAY,
    SPLATTER, BRISTLE, DRY, SQUARE, LEAF, STAR, CLOUD, HALFTONE, GRASS,
    RING, HATCH, STREAK, FUR, CUSTOM
}

/** Paper texture that shows through a stroke (applied in canvas space). */
enum class GrainType(val label: String) { NONE("None"), PAPER("Paper"), CANVAS("Canvas"), ROUGH("Rough"), CUSTOM("Imported") }

enum class RotationMode(val label: String) { NONE("None"), RANDOM("Random"), FOLLOW("Follow stroke"), FIXED("Fixed angle") }

/** Fixed recipe for a brush. All built-in brushes are original presets. */
class BrushPreset(
    val name: String,
    val category: String,
    val tip: TipShape,
    /** distance between dabs, as a fraction of the diameter */
    val spacing: Float,
    /** size at zero pressure (0..1 of full size) */
    val minSize: Float,
    /** dab opacity at zero pressure (0..1 of flow) */
    val minFlow: Float,
    /** dab opacity at full pressure */
    val flow: Float,
    val streamline: Float,
    val defaultSize: Float,
    val defaultOpacity: Float = 1f,
    val scatter: Float = 0f,
    val rotation: RotationMode = RotationMode.NONE,
    val angle: Float = 0f,
    /** start taper length in diameters, 0 = none */
    val taper: Float = 0f,
    val tiltShading: Boolean = false,
    val pressureCurve: Float = 1f,
    val grain: GrainType = GrainType.NONE,
    val grainScale: Float = 1f,
    /** for imported brushes */
    val id: String = "builtin:$name",
    val customTip: Bitmap? = null,
    val customGrain: Bitmap? = null
) {
    val imported get() = id.startsWith("user:")
}

/** A brush the user is using: preset + the user's own adjustments. */
class Brush(val preset: BrushPreset) {
    var size = preset.defaultSize
    var opacity = preset.defaultOpacity
    var streamline = preset.streamline
    var sizePressure = 1f - preset.minSize   // 0 = pressure doesn't change size
    var flowPressure = 1f - preset.minFlow   // 0 = pressure doesn't change opacity
    var spacing = preset.spacing
    var scatter = preset.scatter
    var flow = preset.flow
    var rotation = preset.rotation
    var angle = preset.angle
    var grainScale = preset.grainScale
    var grainOn = preset.grain != GrainType.NONE

    val minSize get() = 1f - sizePressure
    val minFlow get() = 1f - flowPressure

    fun tip(): Bitmap = preset.customTip ?: Brushes.tip(preset.tip)

    fun grain(): Bitmap? = if (!grainOn) null else preset.customGrain ?: Brushes.grain(preset.grain)

    fun resetSettings() {
        streamline = preset.streamline
        sizePressure = 1f - preset.minSize
        flowPressure = 1f - preset.minFlow
        spacing = preset.spacing
        scatter = preset.scatter
        flow = preset.flow
        rotation = preset.rotation
        angle = preset.angle
        grainScale = preset.grainScale
        grainOn = preset.grain != GrainType.NONE
    }
}

object Brushes {
    const val MIN_SIZE = 1f
    const val MAX_SIZE = 500f

    private fun p(
        name: String, cat: String, tip: TipShape, spacing: Float, minSize: Float, minFlow: Float, flow: Float,
        streamline: Float, size: Float, opacity: Float = 1f, scatter: Float = 0f,
        rotation: RotationMode = RotationMode.NONE, angle: Float = 0f, taper: Float = 0f,
        tilt: Boolean = false, curve: Float = 1f, grain: GrainType = GrainType.NONE, grainScale: Float = 1f
    ) = BrushPreset(name, cat, tip, spacing, minSize, minFlow, flow, streamline, size, opacity, scatter,
        rotation, angle, taper, tilt, curve, grain, grainScale)

    private val categoryOrder = listOf("Inking", "Calligraphy", "Sketching", "Painting", "Watercolor",
        "Airbrushing", "Textures & Elements", "Effects", "Pixel Art")

    val presets: List<BrushPreset> get() = allPresets

    private val allPresets: List<BrushPreset> = listOf(
        // ---- Inking (15 Brushes) ----
        p("Technical Pen", "Inking", TipShape.HARD, 0.05f, 0.75f, 1f, 1f, 0.35f, 6f),
        p("Studio Ink", "Inking", TipShape.HARD, 0.04f, 0.12f, 1f, 1f, 0.55f, 16f, taper = 5f, curve = 1.3f),
        p("Monoline", "Inking", TipShape.HARD, 0.05f, 1f, 1f, 1f, 0.3f, 10f),
        p("Gel Pen", "Inking", TipShape.MARKER, 0.04f, 0.65f, 0.9f, 1f, 0.4f, 8f),
        p("Brush Pen", "Inking", TipShape.HARD, 0.03f, 0.05f, 1f, 1f, 0.6f, 24f, taper = 8f, curve = 1.6f),
        p("Dry Ink", "Inking", TipShape.HARD, 0.05f, 0.3f, 1f, 1f, 0.4f, 14f, grain = GrainType.ROUGH, grainScale = 0.8f),
        p("Fineliner 0.1", "Inking", TipShape.HARD, 0.05f, 0.9f, 1f, 1f, 0.35f, 2f),
        p("Fineliner 0.3", "Inking", TipShape.HARD, 0.05f, 0.9f, 1f, 1f, 0.35f, 4f),
        p("Fineliner 0.5", "Inking", TipShape.HARD, 0.05f, 0.9f, 1f, 1f, 0.35f, 6f),
        p("Dip Pen", "Inking", TipShape.HARD, 0.03f, 0.05f, 1f, 1f, 0.5f, 12f, taper = 6f, curve = 2f),
        p("Sumi Ink", "Inking", TipShape.DRY, 0.04f, 0.1f, 0.8f, 1f, 0.5f, 20f, grain = GrainType.PAPER, grainScale = 1.2f),
        p("Manga Ink", "Inking", TipShape.HARD, 0.03f, 0.08f, 1f, 1f, 0.65f, 18f, taper = 7f, curve = 1.8f),
        p("Ballpoint Pen", "Inking", TipShape.HARD, 0.06f, 0.85f, 0.5f, 0.85f, 0.3f, 3f, grain = GrainType.ROUGH, grainScale = 0.4f),
        p("Bleed Ink", "Inking", TipShape.SOFT, 0.05f, 0.4f, 0.8f, 1f, 0.3f, 12f, grain = GrainType.PAPER, grainScale = 1f),
        p("Rollerball Pen", "Inking", TipShape.MARKER, 0.04f, 0.8f, 0.95f, 1f, 0.35f, 5f),

        // ---- Calligraphy (12 Brushes) ----
        p("Calligraphy Flat 45°", "Calligraphy", TipShape.FLAT, 0.025f, 0.4f, 1f, 1f, 0.5f, 26f, rotation = RotationMode.FIXED, angle = 45f),
        p("Qalam (Arabic)", "Calligraphy", TipShape.FLAT, 0.02f, 0.55f, 1f, 1f, 0.55f, 30f, rotation = RotationMode.FIXED, angle = 70f),
        p("Chisel Marker", "Calligraphy", TipShape.FLAT, 0.03f, 0.8f, 0.9f, 1f, 0.4f, 28f, opacity = 0.85f, rotation = RotationMode.FIXED, angle = 30f),
        p("Brush Script", "Calligraphy", TipShape.HARD, 0.025f, 0.06f, 1f, 1f, 0.6f, 28f, taper = 10f, curve = 1.8f),
        p("Pointed Nib", "Calligraphy", TipShape.HARD, 0.025f, 0.03f, 1f, 1f, 0.6f, 18f, taper = 4f, curve = 2.2f),
        p("Copperplate Script", "Calligraphy", TipShape.HARD, 0.02f, 0.02f, 1f, 1f, 0.65f, 20f, taper = 6f, curve = 2.4f),
        p("Gothic Nib", "Calligraphy", TipShape.FLAT, 0.025f, 0.5f, 1f, 1f, 0.5f, 32f, rotation = RotationMode.FIXED, angle = 40f),
        p("Flat Ribbon", "Calligraphy", TipShape.FLAT, 0.02f, 0.6f, 0.9f, 1f, 0.45f, 36f, rotation = RotationMode.FIXED, angle = 60f),
        p("Kanji Brush", "Calligraphy", TipShape.HARD, 0.03f, 0.04f, 0.9f, 1f, 0.55f, 34f, taper = 8f),
        p("Calligraphy Bold", "Calligraphy", TipShape.FLAT, 0.02f, 0.4f, 1f, 1f, 0.5f, 40f, rotation = RotationMode.FIXED, angle = 45f),
        p("Monoline Script", "Calligraphy", TipShape.HARD, 0.04f, 1f, 1f, 1f, 0.5f, 14f),
        p("Broad Edge", "Calligraphy", TipShape.FLAT, 0.025f, 0.6f, 1f, 1f, 0.4f, 24f, rotation = RotationMode.FIXED, angle = 15f),

        // ---- Sketching (18 Brushes) ----
        p("2H Pencil", "Sketching", TipShape.PENCIL, 0.12f, 0.7f, 0.1f, 0.45f, 0.15f, 3f, rotation = RotationMode.RANDOM, tilt = true),
        p("HB Pencil", "Sketching", TipShape.PENCIL, 0.12f, 0.6f, 0.12f, 0.6f, 0.15f, 5f, rotation = RotationMode.RANDOM, tilt = true),
        p("2B Pencil", "Sketching", TipShape.PENCIL, 0.11f, 0.55f, 0.15f, 0.7f, 0.15f, 8f, rotation = RotationMode.RANDOM, tilt = true),
        p("4B Pencil", "Sketching", TipShape.PENCIL, 0.1f, 0.5f, 0.12f, 0.75f, 0.15f, 10f, rotation = RotationMode.RANDOM, tilt = true),
        p("6B Pencil", "Sketching", TipShape.PENCIL, 0.1f, 0.5f, 0.1f, 0.75f, 0.15f, 12f, rotation = RotationMode.RANDOM, tilt = true),
        p("Mechanical 0.5mm", "Sketching", TipShape.PENCIL, 0.1f, 0.9f, 0.25f, 0.7f, 0.2f, 2.5f, rotation = RotationMode.RANDOM),
        p("Paper Pencil", "Sketching", TipShape.SOFT, 0.08f, 0.6f, 0.15f, 0.9f, 0.15f, 9f, tilt = true, grain = GrainType.PAPER, grainScale = 0.7f),
        p("Charcoal Soft", "Sketching", TipShape.CHARCOAL, 0.08f, 0.6f, 0.2f, 0.65f, 0.1f, 32f, rotation = RotationMode.RANDOM, tilt = true),
        p("Charcoal Compressed", "Sketching", TipShape.CHARCOAL, 0.07f, 0.7f, 0.3f, 0.85f, 0.1f, 28f, rotation = RotationMode.RANDOM, tilt = true),
        p("Charcoal Willow", "Sketching", TipShape.CHARCOAL, 0.09f, 0.5f, 0.15f, 0.55f, 0.1f, 40f, rotation = RotationMode.RANDOM, tilt = true),
        p("Soft Pastel", "Sketching", TipShape.CHARCOAL, 0.06f, 0.7f, 0.3f, 0.8f, 0.1f, 40f, rotation = RotationMode.RANDOM, tilt = true, grain = GrainType.PAPER, grainScale = 1.3f),
        p("Hard Pastel", "Sketching", TipShape.CHARCOAL, 0.06f, 0.8f, 0.4f, 0.9f, 0.1f, 24f, rotation = RotationMode.RANDOM, grain = GrainType.PAPER, grainScale = 1f),
        p("Chalk", "Sketching", TipShape.MARKER, 0.06f, 0.8f, 0.4f, 1f, 0.1f, 28f, grain = GrainType.ROUGH, grainScale = 1.4f),
        p("Blackboard Chalk", "Sketching", TipShape.MARKER, 0.06f, 0.85f, 0.5f, 1f, 0.1f, 26f, grain = GrainType.ROUGH, grainScale = 2.2f),
        p("Graphite Block", "Sketching", TipShape.CHARCOAL, 0.06f, 0.8f, 0.15f, 0.4f, 0.1f, 60f, rotation = RotationMode.RANDOM, tilt = true, grain = GrainType.PAPER, grainScale = 1f),
        p("Crayon", "Sketching", TipShape.PENCIL, 0.07f, 0.8f, 0.4f, 0.9f, 0.1f, 18f, rotation = RotationMode.RANDOM, grain = GrainType.ROUGH, grainScale = 0.9f),
        p("Conte Crayon", "Sketching", TipShape.CHARCOAL, 0.07f, 0.75f, 0.35f, 0.85f, 0.1f, 22f, rotation = RotationMode.RANDOM, grain = GrainType.PAPER, grainScale = 1.1f),
        p("Colored Pencil", "Sketching", TipShape.PENCIL, 0.09f, 0.7f, 0.3f, 0.8f, 0.15f, 12f, rotation = RotationMode.RANDOM, tilt = true),

        // ---- Painting (18 Brushes) ----
        p("Round Oil", "Painting", TipShape.HARD, 0.04f, 0.3f, 0.85f, 0.95f, 0.3f, 40f),
        p("Flat Oil", "Painting", TipShape.FLAT, 0.03f, 0.6f, 0.9f, 1f, 0.35f, 40f, rotation = RotationMode.FOLLOW),
        p("Oil Bristle", "Painting", TipShape.BRISTLE, 0.03f, 0.7f, 0.6f, 0.9f, 0.3f, 50f, rotation = RotationMode.FOLLOW),
        p("Dry Oil Brush", "Painting", TipShape.DRY, 0.03f, 0.7f, 0.4f, 0.85f, 0.25f, 60f, rotation = RotationMode.FOLLOW),
        p("Acrylic Thick", "Painting", TipShape.HARD, 0.04f, 0.5f, 0.85f, 1f, 0.3f, 45f, grain = GrainType.CANVAS, grainScale = 1f),
        p("Gouache Opaque", "Painting", TipShape.MARKER, 0.04f, 0.7f, 0.9f, 1f, 0.3f, 50f, grain = GrainType.PAPER, grainScale = 2f),
        p("Detail Oil", "Painting", TipShape.HARD, 0.04f, 0.2f, 0.9f, 1f, 0.35f, 6f, taper = 3f),
        p("Wet Round", "Painting", TipShape.SOFT, 0.05f, 0.5f, 0.3f, 0.5f, 0.3f, 40f),
        p("Palette Knife", "Painting", TipShape.FLAT, 0.025f, 0.85f, 1f, 1f, 0.3f, 50f, rotation = RotationMode.FOLLOW, grain = GrainType.CANVAS, grainScale = 1.2f),
        p("Impasto Heavy", "Painting", TipShape.BRISTLE, 0.025f, 0.8f, 0.8f, 1f, 0.3f, 45f, rotation = RotationMode.FOLLOW, grain = GrainType.CANVAS, grainScale = 0.9f),
        p("Fan Brush", "Painting", TipShape.BRISTLE, 0.05f, 0.7f, 0.5f, 0.8f, 0.25f, 55f, rotation = RotationMode.FOLLOW),
        p("Scumbler Oil", "Painting", TipShape.DRY, 0.04f, 0.8f, 0.3f, 0.7f, 0.2f, 50f, grain = GrainType.CANVAS, grainScale = 1.5f),
        p("Filbert Oil", "Painting", TipShape.SOFT, 0.035f, 0.5f, 0.8f, 0.95f, 0.3f, 42f, rotation = RotationMode.FOLLOW),
        p("Old Master Glaze", "Painting", TipShape.SOFT, 0.05f, 0.9f, 0.15f, 0.35f, 0.2f, 60f, opacity = 0.5f),
        p("Alla Prima Oil", "Painting", TipShape.HARD, 0.03f, 0.4f, 0.9f, 1f, 0.35f, 48f),
        p("Canvas Builder", "Painting", TipShape.DRY, 0.03f, 0.85f, 0.6f, 0.9f, 0.2f, 70f, grain = GrainType.CANVAS, grainScale = 1.8f),
        p("Wet Blending Oil", "Painting", TipShape.SOFT, 0.04f, 0.6f, 0.2f, 0.45f, 0.25f, 45f),
        p("Glaze Oil", "Painting", TipShape.MARKER, 0.05f, 0.9f, 0.2f, 0.4f, 0.2f, 50f, opacity = 0.45f),

        // ---- Watercolor (14 Brushes) ----
        p("Watercolor Wash", "Watercolor", TipShape.SOFT, 0.05f, 0.8f, 0.2f, 0.35f, 0.3f, 80f, opacity = 0.6f, grain = GrainType.PAPER, grainScale = 1.6f),
        p("Watercolor Round", "Watercolor", TipShape.SOFT, 0.05f, 0.6f, 0.25f, 0.3f, 0.3f, 50f, opacity = 0.65f, grain = GrainType.PAPER, grainScale = 1.4f),
        p("Wet Wash", "Watercolor", TipShape.SOFT, 0.06f, 0.9f, 0.08f, 0.12f, 0.2f, 180f, opacity = 0.5f, grain = GrainType.PAPER, grainScale = 2f),
        p("Ink Wash", "Watercolor", TipShape.CLOUD, 0.15f, 0.7f, 0.2f, 0.25f, 0.2f, 120f, opacity = 0.7f, rotation = RotationMode.RANDOM),
        p("Water Splash", "Watercolor", TipShape.SPLATTER, 0.7f, 0.6f, 0.5f, 0.9f, 0f, 70f, opacity = 0.6f, scatter = 0.8f, rotation = RotationMode.RANDOM, grain = GrainType.PAPER, grainScale = 1.2f),
        p("Salted Watercolor", "Watercolor", TipShape.SPRAY, 0.3f, 0.7f, 0.3f, 0.7f, 0.1f, 60f, opacity = 0.6f, scatter = 0.4f, grain = GrainType.PAPER, grainScale = 1.8f),
        p("Water Color Edge", "Watercolor", TipShape.RING, 0.08f, 0.8f, 0.3f, 0.6f, 0.2f, 45f, opacity = 0.6f),
        p("Dry Watercolor", "Watercolor", TipShape.DRY, 0.05f, 0.7f, 0.4f, 0.75f, 0.2f, 50f, opacity = 0.7f, grain = GrainType.PAPER, grainScale = 1.3f),
        p("Bleeding Watercolor", "Watercolor", TipShape.CLOUD, 0.12f, 0.8f, 0.15f, 0.3f, 0.2f, 90f, opacity = 0.5f, rotation = RotationMode.RANDOM),
        p("Wet-on-Wet", "Watercolor", TipShape.SOFT, 0.06f, 0.85f, 0.1f, 0.25f, 0.2f, 100f, opacity = 0.55f, grain = GrainType.PAPER, grainScale = 1.5f),
        p("Granulating Water", "Watercolor", TipShape.SPRAY, 0.2f, 0.75f, 0.2f, 0.5f, 0.15f, 75f, opacity = 0.6f, grain = GrainType.ROUGH, grainScale = 1.7f),
        p("Mop Brush", "Watercolor", TipShape.SOFT, 0.06f, 0.8f, 0.2f, 0.4f, 0.25f, 110f, opacity = 0.55f, grain = GrainType.PAPER, grainScale = 1.8f),
        p("Pigment Flow", "Watercolor", TipShape.SOFT, 0.05f, 0.7f, 0.3f, 0.6f, 0.3f, 60f, opacity = 0.65f),
        p("Heavy Gouache", "Watercolor", TipShape.MARKER, 0.04f, 0.6f, 0.8f, 1f, 0.35f, 45f, opacity = 0.85f, grain = GrainType.PAPER, grainScale = 1.2f),

        // ---- Airbrushing (12 Brushes) ----
        p("Soft Airbrush", "Airbrushing", TipShape.SOFT, 0.08f, 1f, 0.05f, 0.14f, 0.1f, 140f),
        p("Hard Airbrush", "Airbrushing", TipShape.MARKER, 0.06f, 1f, 0.05f, 0.25f, 0.1f, 60f),
        p("Medium Airbrush", "Airbrushing", TipShape.SOFT, 0.07f, 1f, 0.08f, 0.25f, 0.1f, 70f),
        p("Fine Spray", "Airbrushing", TipShape.SPRAY, 0.25f, 0.8f, 0.3f, 0.8f, 0.05f, 30f, scatter = 0.1f, rotation = RotationMode.RANDOM),
        p("Spray Paint", "Airbrushing", TipShape.SPRAY, 0.18f, 0.8f, 0.3f, 0.9f, 0.05f, 90f, scatter = 0.15f, rotation = RotationMode.RANDOM),
        p("Splatter Heavy", "Airbrushing", TipShape.SPLATTER, 0.6f, 0.6f, 0.6f, 1f, 0f, 70f, scatter = 0.6f, rotation = RotationMode.RANDOM),
        p("Grainy Airbrush", "Airbrushing", TipShape.SPRAY, 0.15f, 0.9f, 0.2f, 0.5f, 0.05f, 80f, rotation = RotationMode.RANDOM, grain = GrainType.ROUGH, grainScale = 0.8f),
        p("Soft Smudger", "Airbrushing", TipShape.SOFT, 0.08f, 0.8f, 0.1f, 0.3f, 0.2f, 90f, opacity = 0.5f),
        p("Velvet Airbrush", "Airbrushing", TipShape.SOFT, 0.07f, 0.95f, 0.1f, 0.22f, 0.1f, 100f, grain = GrainType.PAPER, grainScale = 0.6f),
        p("Stipple Airbrush", "Airbrushing", TipShape.SPRAY, 0.5f, 0.7f, 0.5f, 0.9f, 0.05f, 40f, scatter = 0.5f, rotation = RotationMode.RANDOM),
        p("Glow Airbrush", "Airbrushing", TipShape.SOFT, 0.08f, 1f, 0.05f, 0.18f, 0.2f, 60f),
        p("Diffused Airbrush", "Airbrushing", TipShape.SOFT, 0.09f, 0.95f, 0.06f, 0.15f, 0.1f, 120f),

        // ---- Textures & Elements (16 Brushes) ----
        p("Clouds Fluffy", "Textures & Elements", TipShape.CLOUD, 0.25f, 0.6f, 0.2f, 0.45f, 0.2f, 160f, scatter = 0.3f, rotation = RotationMode.RANDOM),
        p("Cumulus", "Textures & Elements", TipShape.CLOUD, 0.2f, 0.7f, 0.3f, 0.6f, 0.2f, 140f, scatter = 0.25f, rotation = RotationMode.RANDOM),
        p("Foliage Dense", "Textures & Elements", TipShape.LEAF, 0.45f, 0.5f, 0.6f, 1f, 0.1f, 60f, scatter = 0.5f, rotation = RotationMode.RANDOM),
        p("Palm Leaves", "Textures & Elements", TipShape.LEAF, 0.6f, 0.6f, 0.7f, 1f, 0.1f, 75f, scatter = 0.6f, rotation = RotationMode.RANDOM),
        p("Pine Needles", "Textures & Elements", TipShape.STREAK, 0.3f, 0.7f, 0.7f, 1f, 0.1f, 45f, scatter = 0.4f, rotation = RotationMode.RANDOM),
        p("Wild Grass", "Textures & Elements", TipShape.GRASS, 0.2f, 0.6f, 0.7f, 1f, 0.1f, 60f, scatter = 0.2f),
        p("Sparkles & Stars", "Textures & Elements", TipShape.STAR, 1.2f, 0.4f, 0.5f, 1f, 0f, 40f, scatter = 1.2f, rotation = RotationMode.RANDOM),
        p("Bokeh Bubbles", "Textures & Elements", TipShape.RING, 1.5f, 0.3f, 0.5f, 0.9f, 0f, 50f, scatter = 1.4f, rotation = RotationMode.RANDOM),
        p("Halftone 30%", "Textures & Elements", TipShape.HALFTONE, 0.3f, 1f, 1f, 1f, 0.2f, 60f),
        p("Halftone 50%", "Textures & Elements", TipShape.HALFTONE, 0.3f, 1f, 1f, 1f, 0.2f, 90f),
        p("Noise Texture", "Textures & Elements", TipShape.SPRAY, 0.25f, 1f, 0.4f, 0.6f, 0.05f, 120f, scatter = 0.3f, rotation = RotationMode.RANDOM, grain = GrainType.ROUGH, grainScale = 0.6f),
        p("Rocks & Stone", "Textures & Elements", TipShape.CHARCOAL, 0.5f, 0.6f, 0.8f, 0.9f, 0f, 50f, scatter = 0.5f, rotation = RotationMode.RANDOM),
        p("Tree Bark", "Textures & Elements", TipShape.DRY, 0.08f, 0.7f, 0.6f, 0.9f, 0.1f, 45f, rotation = RotationMode.FOLLOW, grain = GrainType.ROUGH, grainScale = 1.5f),
        p("Animal Fur", "Textures & Elements", TipShape.FUR, 0.08f, 0.6f, 0.6f, 0.8f, 0.2f, 34f, rotation = RotationMode.FOLLOW, angle = -90f),
        p("Fish Scales", "Textures & Elements", TipShape.RING, 0.4f, 0.8f, 0.8f, 1f, 0.1f, 36f, scatter = 0.1f),
        p("Wood Grain", "Textures & Elements", TipShape.STREAK, 0.1f, 0.8f, 0.5f, 0.85f, 0.2f, 55f, rotation = RotationMode.FOLLOW, grain = GrainType.PAPER, grainScale = 1.2f),

        // ---- Effects (9 Brushes) ----
        p("Rain Streaks", "Effects", TipShape.STREAK, 1.5f, 0.6f, 0.4f, 0.7f, 0f, 80f, scatter = 2f, rotation = RotationMode.FIXED, angle = 15f),
        p("Snowflakes", "Effects", TipShape.SOFT, 2.5f, 0.3f, 0.6f, 1f, 0f, 14f, scatter = 3f),
        p("Starry Night", "Effects", TipShape.STAR, 3f, 0.3f, 0.4f, 1f, 0f, 16f, scatter = 3f, rotation = RotationMode.RANDOM),
        p("Magic Glow", "Effects", TipShape.SOFT, 0.08f, 1f, 0.05f, 0.18f, 0.2f, 60f),
        p("Hatching Cross", "Effects", TipShape.HATCH, 0.8f, 0.8f, 0.6f, 1f, 0.3f, 40f, rotation = RotationMode.FIXED, angle = 45f),
        p("Hatching Parallel", "Effects", TipShape.HATCH, 0.8f, 0.8f, 0.6f, 1f, 0.3f, 40f, rotation = RotationMode.FIXED, angle = 0f),
        p("Stipple Shading", "Effects", TipShape.SPRAY, 0.9f, 0.7f, 0.6f, 1f, 0f, 30f, scatter = 0.4f, rotation = RotationMode.RANDOM),
        p("Glitter Gold", "Effects", TipShape.STAR, 1.5f, 0.3f, 0.6f, 1f, 0f, 28f, scatter = 1.8f, rotation = RotationMode.RANDOM),
        p("Smoke Tendrils", "Effects", TipShape.CLOUD, 0.2f, 0.7f, 0.15f, 0.4f, 0.15f, 110f, scatter = 0.3f, rotation = RotationMode.RANDOM),

        // ---- Pixel Art (6 Brushes) ----
        p("Pixel 1px", "Pixel Art", TipShape.SQUARE, 0.5f, 1f, 1f, 1f, 0f, 1f),
        p("Pixel 2px", "Pixel Art", TipShape.SQUARE, 0.35f, 1f, 1f, 1f, 0f, 2f),
        p("Pixel 4px", "Pixel Art", TipShape.SQUARE, 0.25f, 1f, 1f, 1f, 0f, 4f),
        p("Pixel 8px", "Pixel Art", TipShape.SQUARE, 0.25f, 1f, 1f, 1f, 0f, 8f),
        p("Pixel Dither Light", "Pixel Art", TipShape.HALFTONE, 0.3f, 1f, 1f, 1f, 0f, 16f),
        p("Pixel Dither Heavy", "Pixel Art", TipShape.HALFTONE, 0.25f, 1f, 1f, 1f, 0f, 32f)
    ).sortedBy { categoryOrder.indexOf(it.category).let { i -> if (i < 0) 99 else i } }

    // =========================================================
    // tips
    // =========================================================

    private val tipCache = HashMap<TipShape, Bitmap>()
    private val grainCache = HashMap<GrainType, Bitmap>()

    fun tip(shape: TipShape): Bitmap = tipCache.getOrPut(shape) {
        runCatching { makeTip(shape) }.getOrElse { makeTip(TipShape.HARD) }
    }

    fun grain(type: GrainType): Bitmap? =
        if (type == GrainType.NONE || type == GrainType.CUSTOM) null else grainCache.getOrPut(type) { makeGrain(type) }

    private const val N = 128

    /** Builds an ALPHA_8 bitmap from one byte per pixel. */
    fun alphaBitmap(a: ByteArray, w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        if (bmp.rowBytes == w) {
            bmp.copyPixelsFromBuffer(ByteBuffer.wrap(a))
        } else {
            val padded = ByteArray(bmp.rowBytes * h)
            for (y in 0 until h) System.arraycopy(a, y * w, padded, y * bmp.rowBytes, w)
            bmp.copyPixelsFromBuffer(ByteBuffer.wrap(padded))
        }
        return bmp
    }

    private fun fromField(f: FloatArray, w: Int = N, h: Int = N): Bitmap {
        val a = ByteArray(w * h)
        for (i in f.indices) a[i] = (f[i] * 255).toInt().coerceIn(0, 255).toByte()
        return alphaBitmap(a, w, h)
    }

    /** Draw with a regular Canvas, then keep only the alpha channel. */
    private fun drawn(block: (Canvas, Paint, Random) -> Unit): Bitmap {
        val argb = Bitmap.createBitmap(N, N, Bitmap.Config.ARGB_8888)
        val c = Canvas(argb)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
        block(c, paint, Random(4242))
        val alpha = argb.extractAlpha()
        argb.recycle()
        return alpha
    }

    private fun makeTip(shape: TipShape): Bitmap {
        val rnd = Random(shape.ordinal * 7919 + 13)
        val c = (N - 1) / 2f
        return when (shape) {
            TipShape.SPRAY -> {
                val f = FloatArray(N * N)
                repeat(260) {
                    val ang = rnd.nextFloat() * 2f * PI.toFloat()
                    val rr = sqrt(rnd.nextFloat()) * (c - 3f)
                    dot(f, c + cos(ang) * rr, c + sin(ang) * rr, 0.8f + rnd.nextFloat() * 1.8f, 0.5f + rnd.nextFloat() * 0.5f)
                }
                fromField(f)
            }
            TipShape.HALFTONE -> {
                val f = FloatArray(N * N)
                val step = 14f
                var y = c % step
                while (y < N) {
                    var x = c % step
                    while (x < N) {
                        val r = sqrt((x - c) * (x - c) + (y - c) * (y - c))
                        if (r < c - 4) dot(f, x, y, 4.5f, 1f)
                        x += step
                    }
                    y += step
                }
                fromField(f)
            }
            TipShape.BRISTLE, TipShape.DRY -> {
                // a row of bristles along X; the brush is turned so the row is across the stroke
                val f = FloatArray(N * N)
                val count = if (shape == TipShape.BRISTLE) 34 else 22
                repeat(count) {
                    val x = 6f + rnd.nextFloat() * (N - 12f)
                    if (shape == TipShape.DRY && rnd.nextFloat() < 0.3f) return@repeat
                    val y = c + (rnd.nextFloat() - 0.5f) * 10f
                    val r = 1.6f + rnd.nextFloat() * (if (shape == TipShape.BRISTLE) 3.2f else 2.2f)
                    dot(f, x, y, r, 0.45f + rnd.nextFloat() * 0.55f)
                }
                fromField(f)
            }
            TipShape.SQUARE -> {
                val f = FloatArray(N * N) { i ->
                    val x = i % N; val y = i / N
                    if (x in 4 until N - 4 && y in 4 until N - 4) 1f else 0f
                }
                fromField(f)
            }
            TipShape.RING -> drawn { cv, paint, _ ->
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 7f
                cv.drawCircle(c, c, c - 8f, paint)
                paint.style = Paint.Style.FILL
                paint.alpha = 40
                cv.drawCircle(c, c, c - 10f, paint)
                paint.alpha = 220
                cv.drawCircle(c - c * 0.38f, c - c * 0.38f, 9f, paint)
            }
            TipShape.HATCH -> drawn { cv, paint, _ ->
                paint.strokeWidth = 3.5f
                paint.strokeCap = Paint.Cap.ROUND
                for (k in -2..2) cv.drawLine(10f, c + k * 22f, N - 10f, c + k * 22f, paint)
            }
            TipShape.STREAK -> drawn { cv, paint, _ ->
                val path = Path()
                path.moveTo(c, 4f)
                path.quadTo(c + 3f, c, c, N - 4f)
                path.quadTo(c - 3f, c, c, 4f)
                path.close()
                cv.drawPath(path, paint)
            }
            TipShape.FUR -> drawn { cv, paint, r ->
                paint.style = Paint.Style.STROKE
                paint.strokeCap = Paint.Cap.ROUND
                repeat(26) {
                    val bx = c + (r.nextFloat() - 0.5f) * 90f
                    val len = 50f + r.nextFloat() * 60f
                    val bend = (r.nextFloat() - 0.5f) * 30f
                    paint.strokeWidth = 1.2f + r.nextFloat() * 1.8f
                    paint.alpha = 140 + r.nextInt(115)
                    val path = Path()
                    path.moveTo(bx, N - 6f)
                    path.quadTo(bx + bend * 0.5f, N - 6f - len / 2, bx + bend, N - 6f - len)
                    cv.drawPath(path, paint)
                }
            }
            TipShape.SPLATTER -> drawn { cv, paint, r ->
                paint.maskFilter = BlurMaskFilter(1.2f, BlurMaskFilter.Blur.NORMAL)
                repeat(5) { cv.drawCircle(c + (r.nextFloat() - 0.5f) * 50f, c + (r.nextFloat() - 0.5f) * 50f, 10f + r.nextFloat() * 18f, paint) }
                repeat(26) {
                    val ang = r.nextFloat() * 6.283f
                    val d = 30f + r.nextFloat() * 30f
                    cv.drawCircle(c + cos(ang) * d, c + sin(ang) * d, 1.5f + r.nextFloat() * 5f, paint)
                }
            }
            TipShape.CLOUD -> drawn { cv, paint, r ->
                paint.maskFilter = BlurMaskFilter(9f, BlurMaskFilter.Blur.NORMAL)
                paint.alpha = 150
                repeat(8) {
                    val ang = r.nextFloat() * 6.283f
                    val d = r.nextFloat() * 26f
                    cv.drawCircle(c + cos(ang) * d, c + sin(ang) * d * 0.7f, 18f + r.nextFloat() * 16f, paint)
                }
            }
            TipShape.LEAF -> drawn { cv, paint, _ ->
                val path = Path()
                path.moveTo(c, 6f)
                path.cubicTo(c + 46f, 34f, c + 40f, 96f, c, N - 6f)
                path.cubicTo(c - 40f, 96f, c - 46f, 34f, c, 6f)
                cv.drawPath(path, paint)
                paint.alpha = 0
                paint.strokeWidth = 2.5f
                paint.style = Paint.Style.STROKE
                paint.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)
                cv.drawLine(c, 16f, c, N - 14f, paint)
            }
            TipShape.GRASS -> drawn { cv, paint, r ->
                paint.style = Paint.Style.FILL
                repeat(11) {
                    val bx = c + (r.nextFloat() - 0.5f) * 80f
                    val top = 10f + r.nextFloat() * 60f
                    val bend = (r.nextFloat() - 0.5f) * 40f
                    val w = 2.5f + r.nextFloat() * 3f
                    val path = Path()
                    path.moveTo(bx - w, N - 4f)
                    path.quadTo(bx - w * 0.5f + bend * 0.4f, (N + top) / 2f, bx + bend, top)
                    path.quadTo(bx + w * 0.5f + bend * 0.4f, (N + top) / 2f, bx + w, N - 4f)
                    path.close()
                    cv.drawPath(path, paint)
                }
            }
            TipShape.STAR -> {
                val f = FloatArray(N * N)
                for (y in 0 until N) for (x in 0 until N) {
                    val dx = abs(x - c) / c
                    val dy = abs(y - c) / c
                    val core = exp(-(dx * dx + dy * dy) * 30f)
                    val sx = (1f - dx).coerceAtLeast(0f).pow(3) * exp(-(dy * 22f).pow(2))
                    val sy = (1f - dy).coerceAtLeast(0f).pow(3) * exp(-(dx * 22f).pow(2))
                    f[y * N + x] = (core + sx + sy).coerceIn(0f, 1f)
                }
                fromField(f)
            }
            else -> {
                val noise = if (shape == TipShape.CHARCOAL) valueNoise(rnd, 10, N, false) else null
                val f = FloatArray(N * N)
                for (y in 0 until N) for (x in 0 until N) {
                    val dx = (x - c) / c
                    var dy = (y - c) / c
                    if (shape == TipShape.FLAT) dy /= 0.22f
                    val r = sqrt(dx * dx + dy * dy)
                    f[y * N + x] = when (shape) {
                        TipShape.HARD -> ((1f - r) * c / 1.3f).coerceIn(0f, 1f)
                        TipShape.FLAT -> ((1f - r) * c * 0.22f / 1.3f).coerceIn(0f, 1f)
                        TipShape.MARKER -> ((1f - r) * c / 5f).coerceIn(0f, 1f)
                        TipShape.SOFT -> {
                            val t = (1f - r).coerceIn(0f, 1f)
                            t * t * (3f - 2f * t)
                        }
                        TipShape.PENCIL -> {
                            val edge = ((1f - r) * c / 3f).coerceIn(0f, 1f)
                            val n = rnd.nextFloat()
                            if (n < 0.38f) 0f else edge * (0.35f + 0.65f * n)
                        }
                        TipShape.CHARCOAL -> {
                            val nv = noise!![y * N + x]
                            val rad = 0.72f + 0.28f * nv
                            val edge = ((rad - r) * c / 3f).coerceIn(0f, 1f)
                            val grain = rnd.nextFloat()
                            edge * (if (grain < 0.25f) 0.15f else 0.55f + 0.45f * grain) * (0.5f + 0.5f * nv)
                        }
                        else -> 0f
                    }
                }
                fromField(f)
            }
        }
    }

    private fun dot(f: FloatArray, px: Float, py: Float, dr: Float, strength: Float, n: Int = N) {
        for (y in (py - dr - 1).toInt()..(py + dr + 1).toInt()) for (x in (px - dr - 1).toInt()..(px + dr + 1).toInt()) {
            if (x !in 0 until n || y !in 0 until n) continue
            val d = sqrt((x - px) * (x - px) + (y - py) * (y - py))
            val v = ((dr + 0.5f - d).coerceIn(0f, 1f)) * strength
            val i = y * n + x
            if (v > f[i]) f[i] = v
        }
    }

    // =========================================================
    // grains (seamless 256px tiles)
    // =========================================================

    private const val G = 256

    private fun makeGrain(type: GrainType): Bitmap {
        val rnd = Random(type.ordinal * 31 + 7)
        val f = FloatArray(G * G)
        when (type) {
            GrainType.PAPER -> {
                val big = valueNoise(rnd, 16, G, true)
                val fine = valueNoise(rnd, 64, G, true)
                for (i in f.indices) {
                    val v = 0.55f * big[i] + 0.45f * fine[i]
                    f[i] = smooth(((v - 0.3f) / 0.45f).coerceIn(0f, 1f)) * 0.85f + 0.15f
                }
            }
            GrainType.CANVAS -> {
                val n = valueNoise(rnd, 32, G, true)
                for (y in 0 until G) for (x in 0 until G) {
                    // woven threads, 16 per tile
                    val wx = abs(sin(x * PI.toFloat() * 16 / G))
                    val wy = abs(sin(y * PI.toFloat() * 16 / G))
                    val over = if (((x * 16 / G) + (y * 16 / G)) % 2 == 0) wx else wy
                    f[y * G + x] = (0.35f + 0.55f * over + 0.25f * (n[y * G + x] - 0.5f)).coerceIn(0f, 1f)
                }
            }
            GrainType.ROUGH -> {
                val n = valueNoise(rnd, 48, G, true)
                for (i in f.indices) {
                    val speck = rnd.nextFloat()
                    f[i] = if (speck < 0.12f) 0f else (0.3f + 0.9f * n[i]).coerceIn(0f, 1f)
                }
            }
            else -> {}
        }
        return fromField(f, G, G)
    }

    private fun smooth(t: Float) = t * t * (3 - 2 * t)

    /** Smooth random field 0..1. With [wrap] it tiles seamlessly. */
    private fun valueNoise(rnd: Random, grid: Int, size: Int, wrap: Boolean): FloatArray {
        val g = FloatArray((grid + 1) * (grid + 1)) { rnd.nextFloat() }
        if (wrap) for (i in 0..grid) {
            g[i * (grid + 1) + grid] = g[i * (grid + 1)]
            g[grid * (grid + 1) + i] = g[i]
        }
        val out = FloatArray(size * size)
        for (y in 0 until size) for (x in 0 until size) {
            val fx = x / size.toFloat() * grid
            val fy = y / size.toFloat() * grid
            val ix = floor(fx).toInt().coerceAtMost(grid - 1)
            val iy = floor(fy).toInt().coerceAtMost(grid - 1)
            val sx = smooth(fx - ix)
            val sy = smooth(fy - iy)
            val v00 = g[iy * (grid + 1) + ix]
            val v10 = g[iy * (grid + 1) + ix + 1]
            val v01 = g[(iy + 1) * (grid + 1) + ix]
            val v11 = g[(iy + 1) * (grid + 1) + ix + 1]
            val top = v00 + (v10 - v00) * sx
            val bot = v01 + (v11 - v01) * sx
            out[y * size + x] = top + (bot - top) * sy
        }
        return out
    }

    /** Slider value (0..1) <-> brush size in px, squared for fine control of small sizes. */
    fun sizeFromSlider(v: Float): Float = MIN_SIZE + (MAX_SIZE - MIN_SIZE) * v * v
    fun sliderFromSize(s: Float): Float = sqrt(((s - MIN_SIZE) / (MAX_SIZE - MIN_SIZE)).coerceIn(0f, 1f))
}
