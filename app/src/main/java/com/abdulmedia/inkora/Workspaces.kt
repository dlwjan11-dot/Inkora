package com.abdulmedia.inkora

import android.content.SharedPreferences
import org.json.JSONObject

/**
 * Workspaces: a ready layout of the tool bar for a way of working. Picking one puts its tools on the
 * tool bar (with the sister tool each place shows), sets where the bar sits and whether the islands
 * fade while you draw. "Mine" is the layout you saved yourself.
 */
object Workspaces {
    class Workspace(
        val key: String,
        val name: String,
        val hint: String,
        val mode: String,
        val order: List<String>,
        /** the sister tool each place shows (place key → tool id) */
        val variants: Map<String, String> = emptyMap(),
        val fade: Boolean = true
    )

    const val MINE = "mine"

    val presets = listOf(
        Workspace("sketching", "Sketching", "Pencils at hand, quick shapes, little else",
            ToolRail.TOP, listOf("brush", "smudge", "eraser", "select", "transform", "help", "eyedropper"),
            mapOf("help" to "quickshape", "select" to "sel_free")),
        Workspace("painting", "Painting", "Fill, adjust, flip the view, symmetry and a reference photo",
            ToolRail.TOP, listOf("brush", "smudge", "eraser", "fill", "eyedropper", "adjust", "view", "help", "windows", "select", "transform"),
            mapOf("view" to "flip", "help" to "symmetry", "windows" to "reference", "fill" to "fill")),
        Workspace("lettering", "Lettering", "Guides, assisted drawing, text and measure",
            ToolRail.TOP, listOf("brush", "eraser", "smudge", "help", "text", "measure", "select", "transform"),
            mapOf("help" to "guides")),
        Workspace("frame", "Frame", "Bars around the canvas, like desktop software; nothing fades",
            ToolRail.DOCK, RailTools.defaults, fade = false)
    )

    fun current(prefs: SharedPreferences): String = prefs.getString("workspace", "") ?: ""

    fun hasMine(prefs: SharedPreferences) = prefs.getString("workspace_mine", null) != null

    /** writes [w]'s layout into the tool bar's settings; the tool bar then loads them again */
    fun apply(prefs: SharedPreferences, w: Workspace) {
        val e = prefs.edit()
        e.putString("rail_mode", w.mode)
        e.putString("rail_order", w.order.joinToString(","))
        e.putBoolean("rail_adjust_added", true)
        for (s in RailTools.slots) e.remove("rail_var_" + s.key)
        for ((k, v) in w.variants) e.putString("rail_var_$k", v)
        e.putBoolean("fade_islands", w.fade)
        e.putString("workspace", w.key)
        e.apply()
    }

    /** the layout as it is now becomes "Mine" */
    fun saveMine(prefs: SharedPreferences) {
        val j = JSONObject()
        j.put("mode", prefs.getString("rail_mode", ToolRail.TOP))
        j.put("order", prefs.getString("rail_order", RailTools.defaults.joinToString(",")))
        j.put("fade", prefs.getBoolean("fade_islands", true))
        val vars = JSONObject()
        for (s in RailTools.slots) prefs.getString("rail_var_" + s.key, null)?.let { vars.put(s.key, it) }
        j.put("vars", vars)
        prefs.edit().putString("workspace_mine", j.toString()).putString("workspace", MINE).apply()
    }

    fun mine(prefs: SharedPreferences): Workspace? {
        val j = runCatching { JSONObject(prefs.getString("workspace_mine", null) ?: return null) }.getOrNull() ?: return null
        val vars = j.optJSONObject("vars")
        val map = HashMap<String, String>()
        vars?.keys()?.forEach { k -> map[k] = vars.getString(k) }
        return Workspace(MINE, "Mine", "The layout you saved",
            j.optString("mode", ToolRail.TOP), j.optString("order", "").split(',').filter { it.isNotBlank() },
            map, j.optBoolean("fade", true))
    }
}
