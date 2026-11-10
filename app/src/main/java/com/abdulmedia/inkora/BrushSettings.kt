package com.abdulmedia.inkora

import android.content.SharedPreferences
import org.json.JSONObject

/**
 * The Brush Studio's settings beyond the basics: stabilization, taper, shape, grain depth,
 * rendering, wet mix, color dynamics, dynamics and the dual brush. Every value starts where the
 * brush draws as it always did, so the built-in presets look the same until changed.
 */
class BrushDynamics(
    // stroke path / stabilization
    var stabilization: Float = 0f,      // 0..1: averages the last points of the pen
    var motionFilter: Float = 0f,       // 0..1: ignores tiny shakes
    // taper (length in diameters; size = how thin it starts; opacity = how faint it starts)
    var taperLength: Float = 0f,
    var taperSize: Float = 1f,
    var taperOpacity: Float = 0f,
    // shape
    var roundness: Float = 1f,          // 0.1..1: squashes the tip
    var flipX: Boolean = false,         // each dab may be mirrored, at random
    var flipY: Boolean = false,
    // grain
    var grainDepth: Float = 1f,         // 0..1: how much the grain shows
    // rendering
    var blend: LayerBlend = LayerBlend.NORMAL,
    // wet mix
    var wetDilution: Float = 0f,        // 0..1: water in the paint
    var wetCharge: Float = 1f,          // 0..1: how long the paint lasts along the stroke
    var wetPull: Float = 0f,            // 0..1: how much of the colors underneath it picks up
    // color dynamics: each dab
    var hueJitter: Float = 0f,
    var satJitter: Float = 0f,
    var lightJitter: Float = 0f,
    // color dynamics: each stroke
    var strokeHueJitter: Float = 0f,
    var strokeLightJitter: Float = 0f,
    // color with pressure and tilt (-1..1): at light pressure, the color moves this far
    var pressureHue: Float = 0f,
    var pressureSat: Float = 0f,
    var pressureBright: Float = 0f,
    var tiltBright: Float = 0f,
    // dynamics
    var sizeJitter: Float = 0f,
    var opacityJitter: Float = 0f,
    var tiltShading: Boolean = false,
    var pressureCurve: Float = 1f,
    // dual brush: a second shape that cuts into the first
    var dualOn: Boolean = false,
    var dualShape: String = "CHARCOAL",  // a TipShape name, or "CUSTOM" for the brush's own second shape
    var dualSize: Float = 1f,            // relative to the brush
    var dualScatter: Float = 0.3f,
    // physics
    var watercolor: Float = 0f,         // 0..1: the stroke dries like watercolor (dark rims, uneven wash, granulation)
    var bloom: Float = 0f,              // 0..1: on paint that is still wet, the stroke bleeds out
    var speedSize: Float = 0f,          // 0..1: a fast stroke gets thinner, like ink
    var flowNoise: Float = 0f           // 0..1: the paint comes out a little uneven along the stroke
) {
    fun copy() = BrushDynamics().also { it.read(toJson()) }

    val hasColorDynamics get() = hueJitter > 0f || satJitter > 0f || lightJitter > 0f ||
        strokeHueJitter > 0f || strokeLightJitter > 0f ||
        pressureHue != 0f || pressureSat != 0f || pressureBright != 0f || tiltBright != 0f

    val hasWetMix get() = wetDilution > 0f || wetPull > 0f || wetCharge < 0.999f

    fun toJson(): JSONObject = JSONObject().apply {
        put("stabilization", stabilization.toDouble()); put("motionFilter", motionFilter.toDouble())
        put("taperLength", taperLength.toDouble()); put("taperSize", taperSize.toDouble()); put("taperOpacity", taperOpacity.toDouble())
        put("roundness", roundness.toDouble()); put("flipX", flipX); put("flipY", flipY)
        put("grainDepth", grainDepth.toDouble()); put("blend", blend.name)
        put("wetDilution", wetDilution.toDouble()); put("wetCharge", wetCharge.toDouble()); put("wetPull", wetPull.toDouble())
        put("hueJitter", hueJitter.toDouble()); put("satJitter", satJitter.toDouble()); put("lightJitter", lightJitter.toDouble())
        put("strokeHueJitter", strokeHueJitter.toDouble()); put("strokeLightJitter", strokeLightJitter.toDouble())
        put("pressureHue", pressureHue.toDouble()); put("pressureSat", pressureSat.toDouble()); put("pressureBright", pressureBright.toDouble())
        put("tiltBright", tiltBright.toDouble())
        put("sizeJitter", sizeJitter.toDouble()); put("opacityJitter", opacityJitter.toDouble())
        put("tiltShading", tiltShading); put("pressureCurve", pressureCurve.toDouble())
        put("dualOn", dualOn); put("dualShape", dualShape); put("dualSize", dualSize.toDouble()); put("dualScatter", dualScatter.toDouble())
        put("watercolor", watercolor.toDouble()); put("bloom", bloom.toDouble())
        put("speedSize", speedSize.toDouble()); put("flowNoise", flowNoise.toDouble())
    }

    /** Reads what the JSON has; anything missing keeps its value. */
    fun read(j: JSONObject) {
        fun f(k: String, d: Float) = if (j.has(k)) j.optDouble(k, d.toDouble()).toFloat().takeIf { it.isFinite() } ?: d else d
        fun b(k: String, d: Boolean) = if (j.has(k)) j.optBoolean(k, d) else d
        stabilization = f("stabilization", stabilization).coerceIn(0f, 1f)
        motionFilter = f("motionFilter", motionFilter).coerceIn(0f, 1f)
        taperLength = f("taperLength", taperLength).coerceIn(0f, 20f)
        taperSize = f("taperSize", taperSize).coerceIn(0f, 1f)
        taperOpacity = f("taperOpacity", taperOpacity).coerceIn(0f, 1f)
        roundness = f("roundness", roundness).coerceIn(0.05f, 1f)
        flipX = b("flipX", flipX); flipY = b("flipY", flipY)
        grainDepth = f("grainDepth", grainDepth).coerceIn(0f, 1f)
        blend = runCatching { LayerBlend.valueOf(j.optString("blend", blend.name)) }.getOrDefault(blend)
        wetDilution = f("wetDilution", wetDilution).coerceIn(0f, 1f)
        wetCharge = f("wetCharge", wetCharge).coerceIn(0f, 1f)
        wetPull = f("wetPull", wetPull).coerceIn(0f, 1f)
        hueJitter = f("hueJitter", hueJitter).coerceIn(0f, 1f)
        satJitter = f("satJitter", satJitter).coerceIn(0f, 1f)
        lightJitter = f("lightJitter", lightJitter).coerceIn(0f, 1f)
        strokeHueJitter = f("strokeHueJitter", strokeHueJitter).coerceIn(0f, 1f)
        strokeLightJitter = f("strokeLightJitter", strokeLightJitter).coerceIn(0f, 1f)
        pressureHue = f("pressureHue", pressureHue).coerceIn(-1f, 1f)
        pressureSat = f("pressureSat", pressureSat).coerceIn(-1f, 1f)
        pressureBright = f("pressureBright", pressureBright).coerceIn(-1f, 1f)
        tiltBright = f("tiltBright", tiltBright).coerceIn(-1f, 1f)
        sizeJitter = f("sizeJitter", sizeJitter).coerceIn(0f, 1f)
        opacityJitter = f("opacityJitter", opacityJitter).coerceIn(0f, 1f)
        tiltShading = b("tiltShading", tiltShading)
        pressureCurve = f("pressureCurve", pressureCurve).coerceIn(0.3f, 3f)
        dualOn = b("dualOn", dualOn)
        dualShape = j.optString("dualShape", dualShape)
        dualSize = f("dualSize", dualSize).coerceIn(0.1f, 4f)
        dualScatter = f("dualScatter", dualScatter).coerceIn(0f, 2f)
        watercolor = f("watercolor", watercolor).coerceIn(0f, 1f)
        bloom = f("bloom", bloom).coerceIn(0f, 1f)
        speedSize = f("speedSize", speedSize).coerceIn(0f, 1f)
        flowNoise = f("flowNoise", flowNoise).coerceIn(0f, 1f)
    }

    companion object {
        /** What a preset starts with: its old fixed values carried over. */
        fun fromPreset(p: BrushPreset): BrushDynamics = p.dynamics?.copy() ?: BrushDynamics(
            taperLength = p.taper, tiltShading = p.tiltShading, pressureCurve = p.pressureCurve
        ).also { physics(it, p) }

        /**
         * Gives the built-in brushes the behaviour of their real medium: watercolor dries with
         * dark rims and bleeds on wet paint, paint runs out and mixes with what is under it,
         * ink thins when you move fast, dry media come out a little uneven.
         */
        private fun physics(d: BrushDynamics, p: BrushPreset) {
            val wash = p.tip == TipShape.SOFT || p.tip == TipShape.CLOUD || p.tip == TipShape.SPRAY
            when (p.category) {
                "Watercolor", "Water" -> {
                    d.watercolor = if (p.tip == TipShape.STREAK) 0.6f else 0.9f
                    d.bloom = 0.75f
                    // the brush does not run dry by itself (Brush Studio › Wet mix › Charge turns that on)
                    d.wetDilution = 0.35f; d.wetPull = 0.3f; d.wetCharge = 1f
                    d.flowNoise = 0.3f
                    // washes glaze: one over another gets deeper, as transparent paint does
                    if (p.name != "Heavy Gouache") d.blend = LayerBlend.MULTIPLY
                }
                "Painting", "Artistic" -> {
                    d.wetPull = if (wash) 0.2f else 0.4f
                    d.wetCharge = 1f
                    d.wetDilution = 0.06f
                    d.flowNoise = 0.3f
                    d.sizeJitter = maxOf(d.sizeJitter, 0.04f)
                }
                "Sketching", "Drawing", "Charcoals" -> { d.flowNoise = 0.28f; d.opacityJitter = maxOf(d.opacityJitter, 0.06f) }
                "Inking", "Calligraphy" -> { d.speedSize = if (p.tip == TipShape.HARD) 0.35f else 0.2f; d.flowNoise = 0.06f }
                "Airbrushing", "Spraypaints" -> d.flowNoise = 0.15f
                "Touchups" -> d.wetPull = 0.25f
                "Vintage", "Industrial", "Organic", "Materials", "Elements", "Abstract", "Retro", "Luminance" -> d.flowNoise = 0.18f
            }
        }
    }
}

/** Keeps each brush's studio settings between runs, per tool (a brush can be set differently as an eraser). */
object BrushSettings {

    fun toJson(b: Brush): JSONObject = JSONObject().apply {
        put("streamline", b.streamline.toDouble())
        put("sizePressure", b.sizePressure.toDouble())
        put("flowPressure", b.flowPressure.toDouble())
        put("spacing", b.spacing.toDouble())
        put("scatter", b.scatter.toDouble())
        put("flow", b.flow.toDouble())
        put("rotation", b.rotation.name)
        put("angle", b.angle.toDouble())
        put("grainScale", b.grainScale.toDouble())
        put("grainOn", b.grainOn)
        put("dyn", b.dyn.toJson())
    }

    fun apply(b: Brush, j: JSONObject) {
        fun f(k: String, d: Float) = if (j.has(k)) j.optDouble(k, d.toDouble()).toFloat().takeIf { it.isFinite() } ?: d else d
        b.streamline = f("streamline", b.streamline).coerceIn(0f, 1f)
        b.sizePressure = f("sizePressure", b.sizePressure).coerceIn(0f, 1f)
        b.flowPressure = f("flowPressure", b.flowPressure).coerceIn(0f, 1f)
        b.spacing = f("spacing", b.spacing).coerceIn(0.01f, 2f)
        b.scatter = f("scatter", b.scatter).coerceIn(0f, 2f)
        b.flow = f("flow", b.flow).coerceIn(0.02f, 1f)
        b.rotation = runCatching { RotationMode.valueOf(j.optString("rotation", b.rotation.name)) }.getOrDefault(b.rotation)
        b.angle = f("angle", b.angle)
        b.grainScale = f("grainScale", b.grainScale).coerceIn(0.1f, 8f)
        if (j.has("grainOn")) b.grainOn = j.optBoolean("grainOn", b.grainOn) && b.preset.grain != GrainType.NONE
        j.optJSONObject("dyn")?.let { b.dyn.read(it) }
    }

    private fun key(tool: Tool, b: Brush) = "bs_${tool.name}_${b.preset.id}"

    fun save(prefs: SharedPreferences, tool: Tool, b: Brush) {
        prefs.edit().putString(key(tool, b), toJson(b).toString()).apply()
    }

    fun forget(prefs: SharedPreferences, tool: Tool, b: Brush) {
        prefs.edit().remove(key(tool, b)).apply()
    }

    fun load(prefs: SharedPreferences, tool: Tool, b: Brush) {
        val s = prefs.getString(key(tool, b), null) ?: return
        runCatching { apply(b, JSONObject(s)) }
    }
}
