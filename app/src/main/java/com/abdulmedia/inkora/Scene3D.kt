package com.abdulmedia.inkora

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/*
 * A small 3D system for drawing references: posable figures, simple objects and imported models,
 * drawn by a software renderer (no OpenGL needed) into a layer.
 *
 * World units are meters, y points up, the floor is y = 0 and a figure faces +z.
 */

// =====================================================================
// math
// =====================================================================

/** 4x4 matrix, row-major, for column vectors: p' = M · p */
class Mat4(val m: FloatArray = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)) {

    operator fun times(o: Mat4): Mat4 {
        val r = FloatArray(16)
        val a = m; val b = o.m
        for (i in 0 until 4) for (j in 0 until 4) {
            r[i * 4 + j] = a[i * 4] * b[j] + a[i * 4 + 1] * b[4 + j] + a[i * 4 + 2] * b[8 + j] + a[i * 4 + 3] * b[12 + j]
        }
        return Mat4(r)
    }

    fun point(x: Float, y: Float, z: Float, out: FloatArray, o: Int = 0) {
        out[o] = m[0] * x + m[1] * y + m[2] * z + m[3]
        out[o + 1] = m[4] * x + m[5] * y + m[6] * z + m[7]
        out[o + 2] = m[8] * x + m[9] * y + m[10] * z + m[11]
    }

    /** the 3x3 matrix that turns normals (inverse transpose of the upper 3x3) */
    fun normalMatrix(): FloatArray {
        val a = m[0]; val b = m[1]; val c = m[2]
        val d = m[4]; val e = m[5]; val f = m[6]
        val g = m[8]; val h = m[9]; val i = m[10]
        val A = e * i - f * h; val B = -(d * i - f * g); val C = d * h - e * g
        val D = -(b * i - c * h); val E = a * i - c * g; val F = -(a * h - b * g)
        val G = b * f - c * e; val H = -(a * f - c * d); val I = a * e - b * d
        val det = a * A + b * B + c * C
        val k = if (abs(det) < 1e-12f) 0f else 1f / det
        // inverse = adjugate / det = cofactorᵀ / det; its transpose = cofactor / det
        return floatArrayOf(A * k, B * k, C * k, D * k, E * k, F * k, G * k, H * k, I * k)
    }

    companion object {
        fun translate(x: Float, y: Float, z: Float) = Mat4(floatArrayOf(1f, 0f, 0f, x, 0f, 1f, 0f, y, 0f, 0f, 1f, z, 0f, 0f, 0f, 1f))
        fun scale(x: Float, y: Float, z: Float) = Mat4(floatArrayOf(x, 0f, 0f, 0f, 0f, y, 0f, 0f, 0f, 0f, z, 0f, 0f, 0f, 0f, 1f))
        private fun rad(d: Float) = d * (PI / 180.0).toFloat()
        fun rotX(deg: Float): Mat4 { val c = cos(rad(deg)); val s = sin(rad(deg)); return Mat4(floatArrayOf(1f, 0f, 0f, 0f, 0f, c, -s, 0f, 0f, s, c, 0f, 0f, 0f, 0f, 1f)) }
        fun rotY(deg: Float): Mat4 { val c = cos(rad(deg)); val s = sin(rad(deg)); return Mat4(floatArrayOf(c, 0f, s, 0f, 0f, 1f, 0f, 0f, -s, 0f, c, 0f, 0f, 0f, 0f, 1f)) }
        fun rotZ(deg: Float): Mat4 { val c = cos(rad(deg)); val s = sin(rad(deg)); return Mat4(floatArrayOf(c, -s, 0f, 0f, s, c, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)) }
        /** turn (y), then tilt (x), then roll (z) as seen from outside: R = Ry · Rx · Rz */
        fun euler(rx: Float, ry: Float, rz: Float) = rotY(ry) * rotX(rx) * rotZ(rz)
    }
}

// =====================================================================
// meshes
// =====================================================================

/** Triangles: [pos] and [nrm] hold x,y,z per vertex, [idx] three vertex numbers per triangle. */
class Mesh(val pos: FloatArray, val nrm: FloatArray, val idx: IntArray) {
    val vertexCount get() = pos.size / 3
    val triangleCount get() = idx.size / 3

    companion object {
        /** joins meshes, each moved by its matrix */
        fun merge(parts: List<Pair<Mesh, Mat4>>): Mesh {
            val vc = parts.sumOf { it.first.vertexCount }
            val ic = parts.sumOf { it.first.idx.size }
            val pos = FloatArray(vc * 3); val nrm = FloatArray(vc * 3); val idx = IntArray(ic)
            var v = 0; var k = 0
            val tmp = FloatArray(3)
            for ((mesh, mat) in parts) {
                val nm = mat.normalMatrix()
                for (i in 0 until mesh.vertexCount) {
                    mat.point(mesh.pos[i * 3], mesh.pos[i * 3 + 1], mesh.pos[i * 3 + 2], tmp)
                    pos[(v + i) * 3] = tmp[0]; pos[(v + i) * 3 + 1] = tmp[1]; pos[(v + i) * 3 + 2] = tmp[2]
                    val nx = mesh.nrm[i * 3]; val ny = mesh.nrm[i * 3 + 1]; val nz = mesh.nrm[i * 3 + 2]
                    var ax = nm[0] * nx + nm[1] * ny + nm[2] * nz
                    var ay = nm[3] * nx + nm[4] * ny + nm[5] * nz
                    var az = nm[6] * nx + nm[7] * ny + nm[8] * nz
                    val l = sqrt(ax * ax + ay * ay + az * az).takeIf { it > 1e-9f } ?: 1f
                    ax /= l; ay /= l; az /= l
                    nrm[(v + i) * 3] = ax; nrm[(v + i) * 3 + 1] = ay; nrm[(v + i) * 3 + 2] = az
                }
                for (j in mesh.idx.indices) idx[k + j] = mesh.idx[j] + v
                v += mesh.vertexCount; k += mesh.idx.size
            }
            return Mesh(pos, nrm, idx)
        }

        /**
         * A mesh from bare triangles: each corner gets the average normal of the triangles around
         * its vertex that turn less than [creaseDeg] away, so round parts look smooth and edges stay sharp.
         */
        fun fromTriangles(p: FloatArray, faces: IntArray, creaseDeg: Float = 50f): Mesh {
            val nf = faces.size / 3
            val fn = FloatArray(nf * 3)
            for (f in 0 until nf) {
                val a = faces[f * 3] * 3; val b = faces[f * 3 + 1] * 3; val c = faces[f * 3 + 2] * 3
                val ux = p[b] - p[a]; val uy = p[b + 1] - p[a + 1]; val uz = p[b + 2] - p[a + 2]
                val vx = p[c] - p[a]; val vy = p[c + 1] - p[a + 1]; val vz = p[c + 2] - p[a + 2]
                val nx = uy * vz - uz * vy; val ny = uz * vx - ux * vz; val nz = ux * vy - uy * vx
                val l = sqrt(nx * nx + ny * ny + nz * nz).takeIf { it > 1e-12f } ?: 1f
                fn[f * 3] = nx / l; fn[f * 3 + 1] = ny / l; fn[f * 3 + 2] = nz / l
            }
            // faces around each vertex
            val vcount = p.size / 3
            val start = IntArray(vcount + 1)
            for (v in faces) start[v + 1]++
            for (i in 0 until vcount) start[i + 1] += start[i]
            val fill = start.copyOf()
            val around = IntArray(faces.size)
            for (k in faces.indices) around[fill[faces[k]]++] = k / 3
            val cosT = cos(creaseDeg * PI / 180.0).toFloat()
            val pos = FloatArray(faces.size * 3); val nrm = FloatArray(faces.size * 3); val idx = IntArray(faces.size)
            for (k in faces.indices) {
                val v = faces[k]; val f = k / 3
                pos[k * 3] = p[v * 3]; pos[k * 3 + 1] = p[v * 3 + 1]; pos[k * 3 + 2] = p[v * 3 + 2]
                var sx = 0f; var sy = 0f; var sz = 0f
                for (j in start[v] until start[v + 1]) {
                    val g = around[j]
                    val d = fn[g * 3] * fn[f * 3] + fn[g * 3 + 1] * fn[f * 3 + 1] + fn[g * 3 + 2] * fn[f * 3 + 2]
                    if (d >= cosT) { sx += fn[g * 3]; sy += fn[g * 3 + 1]; sz += fn[g * 3 + 2] }
                }
                val l = sqrt(sx * sx + sy * sy + sz * sz).takeIf { it > 1e-9f } ?: 1f
                nrm[k * 3] = sx / l; nrm[k * 3 + 1] = sy / l; nrm[k * 3 + 2] = sz / l
                idx[k] = k
            }
            return Mesh(pos, nrm, idx)
        }
    }
}

/** The built-in shapes, all about one meter big, centered on the origin. */
object Meshes {
    val sphere: Mesh by lazy { sphere(20, 32) }
    val box: Mesh by lazy { box() }
    val cylinder: Mesh by lazy { lathe(floatArrayOf(0f, -0.5f, 0.5f, -0.5f, 0.5f, 0.5f, 0f, 0.5f), 40) }
    val cone: Mesh by lazy { lathe(floatArrayOf(0f, -0.5f, 0.5f, -0.5f, 0f, 0.5f), 40) }
    val pyramid: Mesh by lazy { lathe(floatArrayOf(0f, -0.5f, 0.5f, -0.5f, 0f, 0.5f), 4, startDeg = 45f) }
    val torus: Mesh by lazy { torus(0.36f, 0.14f, 40, 20) }
    val plane: Mesh by lazy {
        Mesh(floatArrayOf(-0.5f, 0f, -0.5f, 0.5f, 0f, -0.5f, 0.5f, 0f, 0.5f, -0.5f, 0f, 0.5f),
            floatArrayOf(0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f, 0f), intArrayOf(0, 2, 1, 0, 3, 2))
    }
    val stairs: Mesh by lazy {
        val steps = 5
        Mesh.merge((0 until steps).map { i ->
            val h = (i + 1f) / steps
            box to (Mat4.translate(0f, h / 2 - 0.5f, -0.5f + (i + 0.5f) / steps) * Mat4.scale(1f, h, 1f / steps))
        })
    }
    val arch: Mesh by lazy {
        val parts = ArrayList<Pair<Mesh, Mat4>>()
        parts.add(box to (Mat4.translate(-0.4f, -0.1f, 0f) * Mat4.scale(0.2f, 0.8f, 0.3f)))
        parts.add(box to (Mat4.translate(0.4f, -0.1f, 0f) * Mat4.scale(0.2f, 0.8f, 0.3f)))
        val n = 9
        for (i in 0 until n) {
            val a0 = PI * i / n; val a1 = PI * (i + 1) / n; val am = (a0 + a1) / 2
            val r = 0.4f
            parts.add(box to (Mat4.translate((r * cos(am)).toFloat(), 0.3f + (r * sin(am)).toFloat(), 0f) *
                Mat4.rotZ((am * 180 / PI).toFloat()) * Mat4.scale(0.2f, (2 * r * sin((a1 - a0) / 2)).toFloat() + 0.04f, 0.3f)))
        }
        Mesh.merge(parts)
    }

    private fun sphere(stacks: Int, slices: Int): Mesh {
        val pos = ArrayList<Float>(); val idx = ArrayList<Int>()
        for (i in 0..stacks) {
            val phi = PI * i / stacks
            for (j in 0..slices) {
                val th = 2 * PI * j / slices
                pos.add((0.5 * sin(phi) * sin(th)).toFloat()); pos.add((0.5 * cos(phi)).toFloat()); pos.add((0.5 * sin(phi) * cos(th)).toFloat())
            }
        }
        for (i in 0 until stacks) for (j in 0 until slices) {
            val a = i * (slices + 1) + j; val b = a + slices + 1
            if (i > 0) { idx.add(a); idx.add(b); idx.add(a + 1) }
            if (i < stacks - 1) { idx.add(a + 1); idx.add(b); idx.add(b + 1) }
        }
        val p = pos.toFloatArray()
        return Mesh(p, FloatArray(p.size) { p[it] * 2f }, idx.toIntArray())
    }

    private fun box(): Mesh {
        val pos = ArrayList<Float>(); val nrm = ArrayList<Float>(); val idx = ArrayList<Int>()
        val faces = arrayOf(
            floatArrayOf(1f, 0f, 0f), floatArrayOf(-1f, 0f, 0f), floatArrayOf(0f, 1f, 0f),
            floatArrayOf(0f, -1f, 0f), floatArrayOf(0f, 0f, 1f), floatArrayOf(0f, 0f, -1f))
        for (n in faces) {
            // two axes across the face
            val u = if (abs(n[1]) > 0.5f) floatArrayOf(1f, 0f, 0f) else floatArrayOf(0f, 1f, 0f)
            val v = floatArrayOf(n[1] * u[2] - n[2] * u[1], n[2] * u[0] - n[0] * u[2], n[0] * u[1] - n[1] * u[0])
            val base = pos.size / 3
            for ((su, sv) in listOf(-1f to -1f, 1f to -1f, 1f to 1f, -1f to 1f)) {
                for (k in 0 until 3) pos.add(0.5f * (n[k] + su * u[k] + sv * v[k]))
                for (k in 0 until 3) nrm.add(n[k])
            }
            idx.addAll(listOf(base, base + 1, base + 2, base, base + 2, base + 3))
        }
        return Mesh(pos.toFloatArray(), nrm.toFloatArray(), idx.toIntArray())
    }

    /**
     * Turns a profile (radius, height pairs, bottom to top) around the y axis. Flat caps where the
     * profile touches the axis at the bottom or top.
     */
    private fun lathe(profile: FloatArray, slices: Int, startDeg: Float = 0f): Mesh {
        val pts = profile.size / 2
        val parts = ArrayList<Pair<Mesh, Mat4>>()
        val pos = ArrayList<Float>(); val nrm = ArrayList<Float>(); val idx = ArrayList<Int>()
        val smooth = slices > 8
        for (s in 0 until pts - 1) {
            val r0 = profile[s * 2]; val y0 = profile[s * 2 + 1]
            val r1 = profile[s * 2 + 2]; val y1 = profile[s * 2 + 3]
            if (r0 == 0f && r1 == 0f) continue
            val flat = y0 == y1
            // slope normal of this band, in (radius, height)
            var nr = y1 - y0; var ny = r0 - r1
            val l = sqrt(nr * nr + ny * ny).takeIf { it > 1e-9f } ?: 1f
            nr /= l; ny /= l
            for (j in 0 until slices) {
                val a0 = (startDeg + 360f * j / slices) * PI.toFloat() / 180f
                val a1 = (startDeg + 360f * (j + 1) / slices) * PI.toFloat() / 180f
                val am = (a0 + a1) / 2
                val base = pos.size / 3
                for ((r, y, a) in listOf(Triple(r0, y0, a0), Triple(r0, y0, a1), Triple(r1, y1, a1), Triple(r1, y1, a0))) {
                    pos.add(r * sin(a)); pos.add(y); pos.add(r * cos(a))
                    val na = if (smooth && !flat) a else am
                    nrm.add(nr * sin(na)); nrm.add(ny); nrm.add(nr * cos(na))
                }
                // keep the triangles turning outward
                if (ny < 0 && flat) idx.addAll(listOf(base, base + 2, base + 1, base, base + 3, base + 2))
                else idx.addAll(listOf(base, base + 1, base + 2, base, base + 2, base + 3))
            }
        }
        parts.add(Mesh(pos.toFloatArray(), nrm.toFloatArray(), idx.toIntArray()) to Mat4())
        return Mesh.merge(parts)
    }

    private fun torus(R: Float, r: Float, seg: Int, ring: Int): Mesh {
        val pos = ArrayList<Float>(); val nrm = ArrayList<Float>(); val idx = ArrayList<Int>()
        for (i in 0..seg) {
            val u = 2 * PI * i / seg
            for (j in 0..ring) {
                val v = 2 * PI * j / ring
                val cx = cos(v); val cy = sin(v)
                pos.add(((R + r * cx) * sin(u)).toFloat()); pos.add((r * cy).toFloat()); pos.add(((R + r * cx) * cos(u)).toFloat())
                nrm.add((cx * sin(u)).toFloat()); nrm.add(cy.toFloat()); nrm.add((cx * cos(u)).toFloat())
            }
        }
        for (i in 0 until seg) for (j in 0 until ring) {
            val a = i * (ring + 1) + j; val b = a + ring + 1
            idx.addAll(listOf(a, b, a + 1, a + 1, b, b + 1))
        }
        return Mesh(pos.toFloatArray(), nrm.toFloatArray(), idx.toIntArray())
    }
}

// =====================================================================
// the posable figure
// =====================================================================

class Joint(val name: String, val label: String, val parent: Int, val ox: Float, val oy: Float, val oz: Float)

/** an egg-shaped body part fixed to a joint */
class BodyPart(val joint: Int, val cx: Float, val cy: Float, val cz: Float, val rx: Float, val ry: Float, val rz: Float, val ball: Boolean = false)

object Mannequin {
    val joints: List<Joint>
    val parts: List<BodyPart>
    private val byName = HashMap<String, Int>()

    init {
        val j = ArrayList<Joint>()
        fun add(name: String, label: String, parent: String?, x: Float, y: Float, z: Float) {
            byName[name] = j.size
            j.add(Joint(name, label, parent?.let { byName.getValue(it) } ?: -1, x, y, z))
        }
        add("hips", "Hips", null, 0f, 0.98f, 0f)
        add("spine", "Waist", "hips", 0f, 0.08f, 0f)
        add("chest", "Chest", "spine", 0f, 0.20f, 0f)
        add("neck", "Neck", "chest", 0f, 0.25f, 0f)
        add("head", "Head", "neck", 0f, 0.09f, 0f)
        for ((s, side) in listOf(1f to "L", -1f to "R")) {
            val n = if (side == "L") "Left" else "Right"
            add("shoulder$side", "$n shoulder", "chest", 0.17f * s, 0.20f, 0f)
            add("elbow$side", "$n elbow", "shoulder$side", 0f, -0.29f, 0f)
            add("wrist$side", "$n hand", "elbow$side", 0f, -0.25f, 0f)
            add("hip$side", "$n leg", "hips", 0.095f * s, -0.06f, 0f)
            add("knee$side", "$n knee", "hip$side", 0f, -0.43f, 0f)
            add("ankle$side", "$n foot", "knee$side", 0f, -0.42f, 0f)
        }
        joints = j
        val p = ArrayList<BodyPart>()
        fun part(joint: String, cx: Float, cy: Float, cz: Float, rx: Float, ry: Float, rz: Float, ball: Boolean = false) =
            p.add(BodyPart(byName.getValue(joint), cx, cy, cz, rx, ry, rz, ball))
        part("hips", 0f, -0.02f, 0f, 0.165f, 0.11f, 0.105f)
        part("spine", 0f, 0.10f, 0f, 0.135f, 0.12f, 0.095f)
        part("chest", 0f, 0.11f, 0f, 0.185f, 0.165f, 0.115f)
        part("neck", 0f, 0.04f, 0f, 0.05f, 0.07f, 0.05f)
        part("head", 0f, 0.10f, 0.01f, 0.09f, 0.115f, 0.105f)
        part("head", 0f, 0.085f, 0.105f, 0.018f, 0.028f, 0.025f) // nose: shows where the face looks
        for (side in listOf("L", "R")) {
            part("shoulder$side", 0f, 0f, 0f, 0.06f, 0.06f, 0.06f, true)
            part("shoulder$side", 0f, -0.145f, 0f, 0.048f, 0.15f, 0.048f)
            part("elbow$side", 0f, 0f, 0f, 0.04f, 0.04f, 0.04f, true)
            part("elbow$side", 0f, -0.125f, 0f, 0.04f, 0.13f, 0.04f)
            part("wrist$side", 0f, -0.085f, 0.005f, 0.022f, 0.085f, 0.045f)
            part("hip$side", 0f, 0f, 0f, 0.075f, 0.075f, 0.075f, true)
            part("hip$side", 0f, -0.215f, 0f, 0.075f, 0.23f, 0.075f)
            part("knee$side", 0f, 0f, 0f, 0.055f, 0.055f, 0.055f, true)
            part("knee$side", 0f, -0.21f, 0f, 0.055f, 0.22f, 0.055f)
            part("ankle$side", 0f, -0.035f, 0.05f, 0.045f, 0.035f, 0.12f)
        }
        parts = p
    }

    fun index(name: String) = byName[name] ?: -1

    /** the joint on the other side of the body (or the same one) */
    fun mirrorOf(i: Int): Int {
        val n = joints[i].name
        return when {
            n.endsWith("L") -> index(n.dropLast(1) + "R")
            n.endsWith("R") -> index(n.dropLast(1) + "L")
            else -> i
        }
    }

    /** world matrix of every joint for [pose], under [model] */
    fun jointMatrices(model: Mat4, pose: Pose): Array<Mat4> {
        val out = arrayOfNulls<Mat4>(joints.size)
        for ((i, j) in joints.withIndex()) {
            val r = pose.rot[i]
            val oy = if (i == 0) j.oy - pose.drop else j.oy
            val local = Mat4.translate(j.ox, oy, j.oz) * Mat4.euler(r[0], r[1], r[2])
            out[i] = if (j.parent < 0) model * local else out[j.parent]!! * local
        }
        @Suppress("UNCHECKED_CAST")
        return out as Array<Mat4>
    }
}

/** Joint angles of a figure (degrees: bend = x, turn = y, side = z) and how far the hips drop. */
class Pose(val rot: Array<FloatArray> = Array(Mannequin.joints.size) { FloatArray(3) }, var drop: Float = 0f) {
    fun copy() = Pose(Array(rot.size) { rot[it].copyOf() }, drop)

    fun set(other: Pose) {
        for (i in rot.indices) for (k in 0 until 3) rot[i][k] = other.rot[i][k]
        drop = other.drop
    }

    /** copies one side of the body to the other, mirrored */
    fun mirror(fromLeft: Boolean) {
        for ((i, j) in Mannequin.joints.withIndex()) {
            if (!j.name.endsWith(if (fromLeft) "L" else "R")) continue
            val o = Mannequin.mirrorOf(i)
            rot[o][0] = rot[i][0]; rot[o][1] = -rot[i][1]; rot[o][2] = -rot[i][2]
        }
    }

    fun toJson() = JSONObject().apply {
        put("drop", drop.toDouble())
        val o = JSONObject()
        for ((i, j) in Mannequin.joints.withIndex()) {
            val r = rot[i]
            if (r[0] != 0f || r[1] != 0f || r[2] != 0f) o.put(j.name, JSONArray().apply { put(r[0].toDouble()); put(r[1].toDouble()); put(r[2].toDouble()) })
        }
        put("joints", o)
    }

    companion object {
        fun fromJson(o: JSONObject?): Pose {
            val p = Pose()
            if (o == null) return p
            p.drop = o.optDouble("drop", 0.0).toFloat()
            val js = o.optJSONObject("joints") ?: return p
            for (k in js.keys()) {
                val i = Mannequin.index(k)
                val a = js.optJSONArray(k) ?: continue
                if (i >= 0 && a.length() == 3) for (c in 0 until 3) p.rot[i][c] = a.optDouble(c, 0.0).toFloat()
            }
            return p
        }

        private fun of(drop: Float, vararg v: Pair<String, FloatArray>): Pose {
            val p = Pose(drop = drop)
            for ((n, r) in v) { val i = Mannequin.index(n); if (i >= 0) r.copyInto(p.rot[i]) }
            return p
        }

        private fun r(x: Float, y: Float = 0f, z: Float = 0f) = floatArrayOf(x, y, z)

        /** ready-made poses (degrees). Arms: side + = out for the left arm; bend − = forward. */
        val PRESETS: List<Pair<String, Pose>> by lazy {
            listOf(
                "Standing" to of(0f, "shoulderL" to r(0f, 0f, 8f), "shoulderR" to r(0f, 0f, -8f),
                    "elbowL" to r(-10f), "elbowR" to r(-10f)),
                "Relaxed" to of(0.01f, "hips" to r(0f, 0f, 4f), "hipL" to r(0f, 0f, -4f), "hipR" to r(-6f, 0f, 2f), "kneeR" to r(12f),
                    "chest" to r(0f, 0f, -4f), "head" to r(0f, 0f, 4f),
                    "shoulderL" to r(0f, 0f, 10f), "shoulderR" to r(0f, 0f, -6f), "elbowL" to r(-15f), "elbowR" to r(-8f)),
                "T-pose" to of(0f, "shoulderL" to r(0f, 0f, 90f), "shoulderR" to r(0f, 0f, -90f)),
                "Walking" to of(0.02f, "hipL" to r(-25f), "kneeL" to r(10f), "ankleL" to r(10f), "hipR" to r(20f), "kneeR" to r(25f),
                    "ankleR" to r(-5f), "shoulderL" to r(20f, 0f, 6f), "shoulderR" to r(-25f, 0f, -6f),
                    "elbowL" to r(-15f), "elbowR" to r(-30f), "chest" to r(0f, 6f, 0f)),
                "Running" to of(0.06f, "spine" to r(12f), "hipL" to r(-60f), "kneeL" to r(70f), "ankleL" to r(-10f),
                    "hipR" to r(25f), "kneeR" to r(90f), "shoulderL" to r(45f, 0f, 10f), "elbowL" to r(-90f),
                    "shoulderR" to r(-50f, 0f, -10f), "elbowR" to r(-90f), "head" to r(-8f), "chest" to r(0f, 10f, 0f)),
                "Jumping" to of(-0.25f, "hipL" to r(-40f), "kneeL" to r(80f), "ankleL" to r(30f), "hipR" to r(-20f), "kneeR" to r(60f),
                    "ankleR" to r(30f), "shoulderL" to r(0f, 0f, 155f), "shoulderR" to r(0f, 0f, -155f), "elbowL" to r(-15f), "elbowR" to r(-15f),
                    "head" to r(-15f)),
                "Sitting" to of(0.43f, "hipL" to r(-90f, 0f, -4f), "kneeL" to r(90f), "hipR" to r(-90f, 0f, 4f), "kneeR" to r(90f),
                    "shoulderL" to r(-30f, 0f, 8f), "shoulderR" to r(-30f, 0f, -8f), "elbowL" to r(-50f), "elbowR" to r(-50f)),
                "Kneeling" to of(0.435f, "kneeL" to r(90f), "kneeR" to r(90f), "ankleL" to r(30f), "ankleR" to r(30f),
                    "shoulderL" to r(0f, 0f, 10f), "shoulderR" to r(0f, 0f, -10f), "elbowL" to r(-20f), "elbowR" to r(-20f)),
                "Crouching" to of(0.63f, "hipL" to r(-110f, 0f, -12f), "kneeL" to r(140f), "ankleL" to r(-30f),
                    "hipR" to r(-110f, 0f, 12f), "kneeR" to r(140f), "ankleR" to r(-30f), "spine" to r(30f), "chest" to r(15f), "head" to r(-30f),
                    "shoulderL" to r(-40f, 0f, 10f), "shoulderR" to r(-40f, 0f, -10f), "elbowL" to r(-60f), "elbowR" to r(-60f)),
                "Reaching up" to of(0f, "shoulderR" to r(0f, 0f, -170f), "elbowR" to r(-5f), "shoulderL" to r(0f, 0f, 10f),
                    "chest" to r(0f, 0f, 6f), "head" to r(-25f), "hipL" to r(0f, 0f, 3f), "ankleR" to r(20f)),
                "Waving" to of(0f, "shoulderR" to r(0f, 0f, -95f), "elbowR" to r(0f, 0f, -70f), "shoulderL" to r(0f, 0f, 8f),
                    "elbowL" to r(-10f), "head" to r(0f, -10f, -6f)),
                "Thinking" to of(0f, "shoulderR" to r(-35f, 0f, -12f), "elbowR" to r(-140f, 20f, 0f), "wristR" to r(-20f),
                    "shoulderL" to r(-35f, 0f, -20f), "elbowL" to r(-85f, -40f, 0f), "head" to r(12f, 0f, 8f), "hipR" to r(0f, 0f, 3f), "kneeR" to r(8f)),
                "Fighting" to of(0.06f, "hips" to r(0f, 25f, 0f), "hipL" to r(-25f, -20f, 10f), "kneeL" to r(30f), "hipR" to r(15f, -20f, -10f), "kneeR" to r(25f),
                    "shoulderL" to r(-55f, 0f, 20f), "elbowL" to r(-110f), "shoulderR" to r(-45f, 0f, -25f), "elbowR" to r(-120f),
                    "chest" to r(8f), "head" to r(0f, -25f, 0f)),
                "Lying down" to of(0.87f, "hips" to r(-90f), "head" to r(20f), "shoulderL" to r(0f, 0f, 15f), "shoulderR" to r(0f, 0f, -15f))
            )
        }
    }
}

// =====================================================================
// the scene
// =====================================================================

enum class Shape3D(val label: String) {
    FIGURE("Figure"), CUBE("Cube"), SPHERE("Sphere"), CYLINDER("Cylinder"), CONE("Cone"), PYRAMID("Pyramid"),
    TORUS("Ring"), STAIRS("Stairs"), ARCH("Arch"), PLANE("Wall / floor"), MODEL("3D model")
}

enum class Look3D(val label: String) { SHADED("Shaded"), TOON("Toon"), LINES("Lines") }

class Obj3D(val kind: Shape3D) {
    var x = 0f; var y = 0f; var z = 0f
    var rx = 0f; var ry = 0f; var rz = 0f
    var size = 1f
    var sx = 1f; var sy = 1f; var sz = 1f
    var color = if (kind == Shape3D.FIGURE) 0xFFD8C8B4.toInt() else 0xFFD9D6D0.toInt()
    /** figure: joint angles and body width */
    var pose: Pose? = if (kind == Shape3D.FIGURE) Pose.PRESETS[0].second.copy() else null
    var build = 1f
    /** imported model */
    var mesh: Mesh? = null
    var name = kind.label

    fun copy(): Obj3D = Obj3D(kind).also {
        it.x = x; it.y = y; it.z = z; it.rx = rx; it.ry = ry; it.rz = rz
        it.size = size; it.sx = sx; it.sy = sy; it.sz = sz; it.color = color
        it.pose = pose?.copy(); it.build = build; it.mesh = mesh; it.name = name
    }

    fun model(): Mat4 = Mat4.translate(x, y, z) * Mat4.euler(rx, ry, rz) * Mat4.scale(size, size, size)

    /** the mesh and matrix of every part; [ids] gets the part number (figure: joint) of each */
    fun parts(ids: MutableList<Int>? = null): List<Pair<Mesh, Mat4>> {
        val base = model()
        val out = ArrayList<Pair<Mesh, Mat4>>()
        when (kind) {
            Shape3D.FIGURE -> {
                val jm = Mannequin.jointMatrices(base, pose ?: Pose())
                for (p in Mannequin.parts) {
                    val g = if (p.ball) 1f else build
                    out.add(Meshes.sphere to (jm[p.joint] * Mat4.translate(p.cx, p.cy, p.cz) * Mat4.scale(p.rx * 2 * g, p.ry * 2, p.rz * 2 * g)))
                    ids?.add(p.joint)
                }
            }
            else -> {
                val mesh = when (kind) {
                    Shape3D.CUBE -> Meshes.box; Shape3D.SPHERE -> Meshes.sphere; Shape3D.CYLINDER -> Meshes.cylinder
                    Shape3D.CONE -> Meshes.cone; Shape3D.PYRAMID -> Meshes.pyramid; Shape3D.TORUS -> Meshes.torus
                    Shape3D.STAIRS -> Meshes.stairs; Shape3D.ARCH -> Meshes.arch; Shape3D.PLANE -> Meshes.plane
                    else -> mesh ?: Meshes.box
                }
                // built-in shapes stand on the floor: their base sits at the object's position
                val lift = when (kind) { Shape3D.PLANE, Shape3D.MODEL -> 0f; Shape3D.TORUS -> 0.14f; else -> 0.5f }
                out.add(mesh to (base * Mat4.scale(sx, sy, sz) * Mat4.translate(0f, lift, 0f)))
                ids?.add(-1)
            }
        }
        return out
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("kind", kind.name); put("name", name)
        put("p", JSONArray().apply { listOf(x, y, z, rx, ry, rz, size, sx, sy, sz).forEach { put(it.toDouble()) } })
        put("color", color)
        pose?.let { put("pose", it.toJson()); put("build", build.toDouble()) }
        mesh?.let { m -> if (kind == Shape3D.MODEL) put("mesh", MeshIO.toJson(m)) }
    }

    companion object {
        fun fromJson(o: JSONObject): Obj3D {
            val kind = runCatching { Shape3D.valueOf(o.getString("kind")) }.getOrDefault(Shape3D.CUBE)
            val ob = Obj3D(kind)
            ob.name = o.optString("name", kind.label)
            o.optJSONArray("p")?.let { a ->
                val v = FloatArray(10) { a.optDouble(it, if (it >= 6) 1.0 else 0.0).toFloat() }
                ob.x = v[0]; ob.y = v[1]; ob.z = v[2]; ob.rx = v[3]; ob.ry = v[4]; ob.rz = v[5]
                ob.size = v[6]; ob.sx = v[7]; ob.sy = v[8]; ob.sz = v[9]
            }
            ob.color = o.optInt("color", ob.color)
            if (kind == Shape3D.FIGURE) { ob.pose = Pose.fromJson(o.optJSONObject("pose")); ob.build = o.optDouble("build", 1.0).toFloat() }
            if (kind == Shape3D.MODEL) ob.mesh = o.optJSONObject("mesh")?.let { MeshIO.fromJson(it) }
            return ob
        }
    }
}

class Camera3D {
    var tx = 0f; var ty = 0.9f; var tz = 0f
    var yaw = 25f; var pitch = 8f; var dist = 4.6f
    /** vertical field of view: low = telephoto (flat), high = wide angle (strong perspective) */
    var fov = 38f

    fun copy() = Camera3D().also { it.tx = tx; it.ty = ty; it.tz = tz; it.yaw = yaw; it.pitch = pitch; it.dist = dist; it.fov = fov }

    /** eye position and right / up / forward axes: 12 floats */
    fun basis(): FloatArray {
        val cy = cos(yaw * PI / 180).toFloat(); val sy = sin(yaw * PI / 180).toFloat()
        val cp = cos(pitch * PI / 180).toFloat(); val sp = sin(pitch * PI / 180).toFloat()
        val ex = tx + dist * sy * cp; val ey = ty + dist * sp; val ez = tz + dist * cy * cp
        val fx = -sy * cp; val fy = -sp; val fz = -cy * cp
        // right = forward × up(0,1,0)
        var rx = -fz; var rz = fx
        val rl = sqrt(rx * rx + rz * rz).takeIf { it > 1e-6f } ?: 1f
        rx /= rl; rz /= rl
        // up = right × forward
        val ux = -rz * fy; val uy = rz * fx - rx * fz; val uz = rx * fy
        return floatArrayOf(ex, ey, ez, rx, 0f, rz, ux, uy, uz, fx, fy, fz)
    }

    /** moves the point looked at across the screen */
    fun pan(dxFrac: Float, dyFrac: Float) {
        val b = basis()
        val k = dist * tan(fov * PI / 360).toFloat() * 2
        tx += (-b[3] * dxFrac + b[6] * dyFrac) * k
        ty += (-b[4] * dxFrac + b[7] * dyFrac) * k
        tz += (-b[5] * dxFrac + b[8] * dyFrac) * k
    }
}

class Scene3D {
    val objects = ArrayList<Obj3D>()
    var camera = Camera3D()
    /** light direction: around (degrees) and height (degrees above the horizon) */
    var lightAz = 40f
    var lightEl = 45f
    /** how bright the shadow side is (0 = black, 1 = no shading) */
    var ambient = 0.32f
    var look = Look3D.SHADED
    var outline = true
    var floor = false
    var floorColor = 0xFFEDEBE6.toInt()

    fun copy(): Scene3D = Scene3D().also { s ->
        objects.forEach { s.objects.add(it.copy()) }
        s.camera = camera.copy(); s.lightAz = lightAz; s.lightEl = lightEl; s.ambient = ambient
        s.look = look; s.outline = outline; s.floor = floor; s.floorColor = floorColor
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("v", 1)
        put("objects", JSONArray().apply { objects.forEach { put(it.toJson()) } })
        val c = camera
        put("camera", JSONArray().apply { listOf(c.tx, c.ty, c.tz, c.yaw, c.pitch, c.dist, c.fov).forEach { put(it.toDouble()) } })
        put("light", JSONArray().apply { put(lightAz.toDouble()); put(lightEl.toDouble()); put(ambient.toDouble()) })
        put("look", look.name); put("outline", outline); put("floor", floor); put("floorColor", floorColor)
    }

    companion object {
        fun fromJson(o: JSONObject): Scene3D {
            val s = Scene3D()
            o.optJSONArray("objects")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { s.objects.add(Obj3D.fromJson(it)) } }
            o.optJSONArray("camera")?.let { a ->
                val c = s.camera
                c.tx = a.optDouble(0, 0.0).toFloat(); c.ty = a.optDouble(1, 0.9).toFloat(); c.tz = a.optDouble(2, 0.0).toFloat()
                c.yaw = a.optDouble(3, 25.0).toFloat(); c.pitch = a.optDouble(4, 8.0).toFloat()
                c.dist = a.optDouble(5, 4.6).toFloat(); c.fov = a.optDouble(6, 38.0).toFloat()
            }
            o.optJSONArray("light")?.let { a ->
                s.lightAz = a.optDouble(0, 40.0).toFloat(); s.lightEl = a.optDouble(1, 45.0).toFloat(); s.ambient = a.optDouble(2, 0.32).toFloat()
            }
            s.look = runCatching { Look3D.valueOf(o.optString("look", "SHADED")) }.getOrDefault(Look3D.SHADED)
            s.outline = o.optBoolean("outline", true)
            s.floor = o.optBoolean("floor", false)
            s.floorColor = o.optInt("floorColor", s.floorColor)
            return s
        }
    }
}

// =====================================================================
// model files
// =====================================================================

object MeshIO {
    const val MAX_TRIANGLES = 150_000

    /** Reads a Wavefront .obj or an .stl (text or binary). Throws with a readable message. */
    fun read(bytes: ByteArray, nameHint: String): Mesh {
        val lower = nameHint.lowercase()
        val text = bytes.size >= 5 && String(bytes, 0, 5, Charsets.US_ASCII) == "solid"
        val (p, f) = when {
            lower.endsWith(".stl") || (!lower.endsWith(".obj") && isBinaryStl(bytes)) ->
                if (text && !isBinaryStl(bytes)) readStlText(String(bytes, Charsets.US_ASCII)) else readStlBinary(bytes)
            else -> readObj(String(bytes, Charsets.UTF_8))
        }
        if (f.isEmpty()) throw IllegalArgumentException("No triangles found in this file")
        if (f.size / 3 > MAX_TRIANGLES) throw IllegalArgumentException("This model is too detailed (more than 150,000 triangles)")
        return Mesh.fromTriangles(normalize(p), f)
    }

    private fun isBinaryStl(b: ByteArray): Boolean {
        if (b.size < 84) return false
        val n = (b[80].toInt() and 255) or ((b[81].toInt() and 255) shl 8) or ((b[82].toInt() and 255) shl 16) or ((b[83].toInt() and 255) shl 24)
        return n > 0 && 84L + n.toLong() * 50 == b.size.toLong()
    }

    private fun readObj(s: String): Pair<FloatArray, IntArray> {
        val v = FloatList(); val f = IntList()
        val face = IntList()
        for (raw in s.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("v ")) {
                val t = line.split(Regex("\\s+"))
                if (t.size >= 4) { v.add(t[1].toFloatOrNull() ?: 0f); v.add(t[2].toFloatOrNull() ?: 0f); v.add(t[3].toFloatOrNull() ?: 0f) }
            } else if (line.startsWith("f ")) {
                face.clear()
                val n = v.size / 3
                for (tok in line.split(Regex("\\s+")).drop(1)) {
                    val k = tok.substringBefore('/').toIntOrNull() ?: continue
                    val i = if (k < 0) n + k else k - 1
                    if (i in 0 until n) face.add(i)
                }
                for (i in 1 until face.size - 1) { f.add(face[0]); f.add(face[i]); f.add(face[i + 1]) }
                if (f.size / 3 > MAX_TRIANGLES) throw IllegalArgumentException("This model is too detailed (more than 150,000 triangles)")
            }
        }
        return v.toArray() to f.toArray()
    }

    private fun readStlText(s: String): Pair<FloatArray, IntArray> {
        val v = FloatList()
        for (raw in s.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("vertex")) {
                val t = line.split(Regex("\\s+"))
                if (t.size >= 4) { v.add(t[1].toFloatOrNull() ?: 0f); v.add(t[2].toFloatOrNull() ?: 0f); v.add(t[3].toFloatOrNull() ?: 0f) }
            }
        }
        return weld(v.toArray())
    }

    private fun readStlBinary(b: ByteArray): Pair<FloatArray, IntArray> {
        val n = (b[80].toInt() and 255) or ((b[81].toInt() and 255) shl 8) or ((b[82].toInt() and 255) shl 16) or ((b[83].toInt() and 255) shl 24)
        if (n > MAX_TRIANGLES) throw IllegalArgumentException("This model is too detailed (more than 150,000 triangles)")
        val buf = java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val v = FloatArray(n * 9)
        for (t in 0 until n) {
            val o = 84 + t * 50 + 12
            for (k in 0 until 9) v[t * 9 + k] = buf.getFloat(o + k * 4)
        }
        return weld(v)
    }

    /** joins equal corners of loose triangles so smooth parts get smooth normals */
    private fun weld(v: FloatArray): Pair<FloatArray, IntArray> {
        val map = HashMap<Triple<Float, Float, Float>, Int>()
        val out = FloatList(); val f = IntArray(v.size / 3)
        for (i in 0 until v.size / 3) {
            val key = Triple(v[i * 3], v[i * 3 + 1], v[i * 3 + 2])
            f[i] = map.getOrPut(key) { out.add(key.first); out.add(key.second); out.add(key.third); out.size / 3 - 1 }
        }
        return out.toArray() to f
    }

    /** about 1.6 m tall (a person's height), standing on the floor, centered */
    private fun normalize(p: FloatArray): FloatArray {
        if (p.isEmpty()) return p
        var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var z0 = Float.MAX_VALUE
        var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE; var z1 = -Float.MAX_VALUE
        for (i in 0 until p.size / 3) {
            x0 = min(x0, p[i * 3]); x1 = max(x1, p[i * 3]); y0 = min(y0, p[i * 3 + 1]); y1 = max(y1, p[i * 3 + 1])
            z0 = min(z0, p[i * 3 + 2]); z1 = max(z1, p[i * 3 + 2])
        }
        val big = max(x1 - x0, max(y1 - y0, z1 - z0)).takeIf { it > 1e-9f } ?: 1f
        val k = 1.6f / big
        val cx = (x0 + x1) / 2; val cz = (z0 + z1) / 2
        return FloatArray(p.size) { i ->
            when (i % 3) { 0 -> (p[i] - cx) * k; 1 -> (p[i] - y0) * k; else -> (p[i] - cz) * k }
        }
    }

    // saved inside the project: corner positions and triangles, normals are rebuilt
    fun toJson(m: Mesh): JSONObject {
        // the mesh keeps three corners per triangle; weld them back for a smaller file
        val (p, f) = weld(m.pos)
        val sb = StringBuilder(p.size * 7)
        for (i in p.indices) { if (i > 0) sb.append(','); sb.append(((p[i] * 10000).roundToInt() / 10000f).toString()) }
        val sf = StringBuilder(f.size * 6)
        for (i in f.indices) { if (i > 0) sf.append(','); sf.append(f[i]) }
        return JSONObject().apply { put("p", sb.toString()); put("f", sf.toString()) }
    }

    fun fromJson(o: JSONObject): Mesh? = runCatching {
        val p = o.getString("p").split(',').map { it.toFloat() }.toFloatArray()
        val f = o.getString("f").split(',').map { it.toInt() }.toIntArray()
        Mesh.fromTriangles(p, f)
    }.getOrNull()

    private class FloatList {
        var a = FloatArray(1024); var size = 0
        fun add(v: Float) { if (size == a.size) a = a.copyOf(size * 2); a[size++] = v }
        fun toArray() = a.copyOf(size)
    }

    private class IntList {
        var a = IntArray(1024); var size = 0
        fun add(v: Int) { if (size == a.size) a = a.copyOf(size * 2); a[size++] = v }
        operator fun get(i: Int) = a[i]
        fun clear() { size = 0 }
        fun toArray() = a.copyOf(size)
    }
}

// =====================================================================
// renderer
// =====================================================================

/**
 * Draws a [Scene3D] into ARGB pixels (transparent where there is nothing). Everything is turned
 * into screen triangles once; then the picture is drawn in strips, each [ss]×[ss] supersampled
 * for smooth edges, with a depth buffer and optional outlines.
 */
class Renderer3D(private val scene: Scene3D, val width: Int, val height: Int) {

    /** pick id of a pixel: 0 = nothing, else (object index + 1) * 256 + (part number + 1) */
    companion object {
        fun pickObject(id: Int) = (id shr 8) - 1
        fun pickPart(id: Int) = (id and 255) - 1
        private const val NEAR = 0.05f
    }

    /** highlighted: this object (and, for a figure, this joint) is tinted */
    var highlightObject = -1
    var highlightJoint = -1

    // screen triangles: x, y, 1/z, shade for 3 corners (12 floats), color and pick id
    private var tri = FloatArray(4096)
    private var triColor = IntArray(340)
    private var triId = IntArray(340)
    /** face direction of each triangle (camera space), for lines along sharp edges */
    private var triN = FloatArray(1020)
    private var triCount = 0

    private fun build() {
        triCount = 0
        val b = scene.camera.basis()
        val f = (height / 2f) / tan(scene.camera.fov * PI.toFloat() / 360f)
        val cx = width / 2f; val cy = height / 2f
        val el = scene.lightEl * PI.toFloat() / 180f; val az = scene.lightAz * PI.toFloat() / 180f
        // light direction in world space (toward the light), turned so 0° lights from the camera's right side
        val camYaw = scene.camera.yaw * PI.toFloat() / 180f
        val lx = cos(el) * sin(az + camYaw); val ly = sin(el); val lz = cos(el) * cos(az + camYaw)
        val amb = scene.ambient.coerceIn(0f, 1f)

        val objs = ArrayList<Pair<Pair<Mesh, Mat4>, Pair<Int, Int>>>() // (mesh, matrix), (color, id)
        if (scene.floor) objs.add((Meshes.plane to (Mat4.translate(b[0], 0f, b[2]) * Mat4.scale(60f, 1f, 60f))) to (scene.floorColor to 1))
        for ((oi, o) in scene.objects.withIndex()) {
            val ids = ArrayList<Int>()
            val parts = o.parts(ids)
            for ((k, p) in parts.withIndex()) {
                val joint = ids[k]
                var color = o.color
                if (oi == highlightObject && (highlightJoint < 0 || joint == highlightJoint)) color = mix(color, 0xFF4C8DFF.toInt(), 0.45f)
                objs.add(p to (color to ((oi + 1) * 256 + (joint + 1).coerceIn(0, 255))))
            }
        }

        val wp = FloatArray(3)
        val cam = FloatArray(4 * 3) // up to 4 clipped corners: x, y, z in camera space
        for ((pm, ci) in objs) {
            val (mesh, mat) = pm
            val nm = mat.normalMatrix()
            val twoSided = mesh === Meshes.plane
            val vc = mesh.vertexCount
            val vx = FloatArray(vc); val vy = FloatArray(vc); val vz = FloatArray(vc); val sh = FloatArray(vc)
            for (i in 0 until vc) {
                mat.point(mesh.pos[i * 3], mesh.pos[i * 3 + 1], mesh.pos[i * 3 + 2], wp)
                val dx = wp[0] - b[0]; val dy = wp[1] - b[1]; val dz = wp[2] - b[2]
                vx[i] = dx * b[3] + dy * b[4] + dz * b[5]
                vy[i] = dx * b[6] + dy * b[7] + dz * b[8]
                vz[i] = dx * b[9] + dy * b[10] + dz * b[11]
                val n0 = mesh.nrm[i * 3]; val n1 = mesh.nrm[i * 3 + 1]; val n2 = mesh.nrm[i * 3 + 2]
                var nx = nm[0] * n0 + nm[1] * n1 + nm[2] * n2
                var ny = nm[3] * n0 + nm[4] * n1 + nm[5] * n2
                var nz = nm[6] * n0 + nm[7] * n1 + nm[8] * n2
                val l = sqrt(nx * nx + ny * ny + nz * nz).takeIf { it > 1e-9f } ?: 1f
                nx /= l; ny /= l; nz /= l
                var d = nx * lx + ny * ly + nz * lz
                if (twoSided && nx * dx + ny * dy + nz * dz > 0) d = -d
                sh[i] = amb + (1f - amb) * max(0f, d)
            }
            val (color, id) = ci
            val idx = mesh.idx
            for (t in 0 until idx.size / 3) {
                val a = idx[t * 3]; val bb = idx[t * 3 + 1]; val c = idx[t * 3 + 2]
                val inA = vz[a] >= NEAR; val inB = vz[bb] >= NEAR; val inC = vz[c] >= NEAR
                if (!inA && !inB && !inC) continue
                if (inA && inB && inC) {
                    emit(f, cx, cy, vx[a], vy[a], vz[a], sh[a], vx[bb], vy[bb], vz[bb], sh[bb], vx[c], vy[c], vz[c], sh[c], color, id)
                    continue
                }
                // clip against the near plane
                val ins = intArrayOf(a, bb, c)
                var n = 0
                val sc = FloatArray(4)
                for (k in 0 until 3) {
                    val p = ins[k]; val q = ins[(k + 1) % 3]
                    val pin = vz[p] >= NEAR; val qin = vz[q] >= NEAR
                    if (pin) { cam[n * 3] = vx[p]; cam[n * 3 + 1] = vy[p]; cam[n * 3 + 2] = vz[p]; sc[n] = sh[p]; n++ }
                    if (pin != qin) {
                        val tt = (NEAR - vz[p]) / (vz[q] - vz[p])
                        cam[n * 3] = vx[p] + (vx[q] - vx[p]) * tt; cam[n * 3 + 1] = vy[p] + (vy[q] - vy[p]) * tt; cam[n * 3 + 2] = NEAR
                        sc[n] = sh[p] + (sh[q] - sh[p]) * tt; n++
                    }
                }
                for (k in 1 until n - 1) emit(f, cx, cy, cam[0], cam[1], cam[2], sc[0], cam[k * 3], cam[k * 3 + 1], cam[k * 3 + 2], sc[k],
                    cam[(k + 1) * 3], cam[(k + 1) * 3 + 1], cam[(k + 1) * 3 + 2], sc[k + 1], color, id)
            }
        }
    }

    private fun emit(f: Float, cx: Float, cy: Float,
                     x0: Float, y0: Float, z0: Float, s0: Float, x1: Float, y1: Float, z1: Float, s1: Float,
                     x2: Float, y2: Float, z2: Float, s2: Float, color: Int, id: Int) {
        if (triCount * 12 + 12 > tri.size) tri = tri.copyOf(tri.size * 2)
        if (triCount + 1 > triColor.size) {
            triColor = triColor.copyOf(triColor.size * 2); triId = triId.copyOf(triId.size * 2); triN = triN.copyOf(triN.size * 2)
        }
        val ux = x1 - x0; val uy = y1 - y0; val uz = z1 - z0
        val vx = x2 - x0; val vy = y2 - y0; val vz = z2 - z0
        var nx = uy * vz - uz * vy; var ny = uz * vx - ux * vz; var nz = ux * vy - uy * vx
        val nl = sqrt(nx * nx + ny * ny + nz * nz).takeIf { it > 1e-12f } ?: 1f
        nx /= nl; ny /= nl; nz /= nl
        // the side that faces the camera
        if (nx * x0 + ny * y0 + nz * z0 > 0) { nx = -nx; ny = -ny; nz = -nz }
        triN[triCount * 3] = nx; triN[triCount * 3 + 1] = ny; triN[triCount * 3 + 2] = nz
        val o = triCount * 12
        tri[o] = cx + f * x0 / z0; tri[o + 1] = cy - f * y0 / z0; tri[o + 2] = 1f / z0; tri[o + 3] = s0
        tri[o + 4] = cx + f * x1 / z1; tri[o + 5] = cy - f * y1 / z1; tri[o + 6] = 1f / z1; tri[o + 7] = s1
        tri[o + 8] = cx + f * x2 / z2; tri[o + 9] = cy - f * y2 / z2; tri[o + 10] = 1f / z2; tri[o + 11] = s2
        triColor[triCount] = color; triId[triCount] = id
        triCount++
    }

    /**
     * Renders the whole picture into [out] (width × height, non-premultiplied ARGB).
     * [ids], if given (and [ss] = 1), gets each pixel's pick id.
     */
    fun render(out: IntArray, ss: Int = 1, ids: IntArray? = null, strip: Int = 256) {
        build()
        val lineW = max(1f, min(width, height) / 900f) * scene.let { if (it.look == Look3D.LINES) 1.3f else 1f }
        var y0 = 0
        while (y0 < height) {
            val y1 = min(height, y0 + strip)
            renderStrip(out, y0, y1, ss, if (ss == 1) ids else null, lineW)
            y0 = y1
        }
    }

    private fun renderStrip(out: IntArray, y0: Int, y1: Int, ss: Int, ids: IntArray?, lineW: Float) {
        val margin = (lineW * ss).toInt() + 2
        val bw = width * ss
        val top = y0 * ss - margin
        val bh = (y1 - y0) * ss + margin * 2
        val n = bw * bh
        val depth = FloatArray(n)
        val shade = FloatArray(n)
        val pid = IntArray(n)
        val col = IntArray(n)
        val tix = IntArray(n)
        val s = ss.toFloat()
        for (t in 0 until triCount) {
            val o = t * 12
            val ax = tri[o] * s; val ay = tri[o + 1] * s - top
            val bx = tri[o + 4] * s; val by = tri[o + 5] * s - top
            val cxx = tri[o + 8] * s; val cyy = tri[o + 9] * s - top
            val area = (bx - ax) * (cyy - ay) - (by - ay) * (cxx - ax)
            if (abs(area) < 1e-6f) continue
            val minX = max(0, kotlin.math.floor(min(ax, min(bx, cxx))).toInt())
            val maxX = min(bw - 1, kotlin.math.ceil(max(ax, max(bx, cxx))).toInt())
            val minY = max(0, kotlin.math.floor(min(ay, min(by, cyy))).toInt())
            val maxY = min(bh - 1, kotlin.math.ceil(max(ay, max(by, cyy))).toInt())
            if (minX > maxX || minY > maxY) continue
            val inv = 1f / area
            val za = tri[o + 2]; val zb = tri[o + 6]; val zc = tri[o + 10]
            val sa = tri[o + 3]; val sb = tri[o + 7]; val sc = tri[o + 11]
            val c = triColor[t]; val id = triId[t]
            for (py in minY..maxY) {
                val fy = py + 0.5f
                val row = py * bw
                for (px in minX..maxX) {
                    val fx = px + 0.5f
                    val w0 = ((bx - fx) * (cyy - fy) - (by - fy) * (cxx - fx)) * inv
                    val w1 = ((cxx - fx) * (ay - fy) - (cyy - fy) * (ax - fx)) * inv
                    val w2 = 1f - w0 - w1
                    if (w0 < 0f || w1 < 0f || w2 < 0f) continue
                    val z = w0 * za + w1 * zb + w2 * zc
                    val k = row + px
                    if (z <= depth[k]) continue
                    depth[k] = z
                    // shading follows the depth's perspective
                    shade[k] = (w0 * sa * za + w1 * sb * zb + w2 * sc * zc) / z
                    pid[k] = id; col[k] = c; tix[k] = t
                }
            }
        }
        // colors and outlines
        val look = scene.look
        val amb = scene.ambient
        val r = max(1, (lineW * ss).roundToInt())
        val lineColor = 0xFF26262B.toInt()
        val px = IntArray(n)
        for (yy in 0 until bh) for (xx in 0 until bw) {
            val k = yy * bw + xx
            val id = pid[k]
            if (id == 0 && !(look == Look3D.LINES || scene.outline)) continue
            var edge = false
            if (scene.outline || look == Look3D.LINES) {
                val z = depth[k]
                var d = 1
                while (d <= r && !edge) {
                    if (xx + d < bw && differs(id, z, k, k + d, pid, depth, tix)) edge = true
                    else if (xx - d >= 0 && differs(id, z, k, k - d, pid, depth, tix)) edge = true
                    else if (yy + d < bh && differs(id, z, k, k + d * bw, pid, depth, tix)) edge = true
                    else if (yy - d >= 0 && differs(id, z, k, k - d * bw, pid, depth, tix)) edge = true
                    d++
                }
                // outlines are drawn on the object's side only (the background stays clear)
                if (id == 0) edge = false
            }
            if (id == 0) continue
            px[k] = if (edge) lineColor else when (look) {
                Look3D.SHADED -> tint(col[k], shade[k])
                Look3D.TOON -> tint(col[k], if (shade[k] > amb + (1 - amb) * 0.35f) 1f else amb + (1 - amb) * 0.15f)
                Look3D.LINES -> 0xFFFFFFFF.toInt()
            }
        }
        // down to the final pixels
        val inv = 1f / (ss * ss)
        for (y in y0 until y1) {
            val sy0 = (y - y0) * ss + margin
            for (x in 0 until width) {
                var a = 0f; var rr = 0f; var gg = 0f; var bb = 0f
                for (j in 0 until ss) {
                    val row = (sy0 + j) * bw + x * ss
                    for (i in 0 until ss) {
                        val c = px[row + i]
                        val ca = (c ushr 24) / 255f
                        a += ca; rr += ((c shr 16) and 255) * ca; gg += ((c shr 8) and 255) * ca; bb += (c and 255) * ca
                    }
                }
                val o = y * width + x
                out[o] = if (a <= 0f) 0 else {
                    val al = a * inv
                    ((al * 255f).roundToInt().coerceIn(0, 255) shl 24) or
                        ((rr / a).roundToInt().coerceIn(0, 255) shl 16) or ((gg / a).roundToInt().coerceIn(0, 255) shl 8) or (bb / a).roundToInt().coerceIn(0, 255)
                }
                if (ids != null) ids[o] = pid[sy0 * bw + x]
            }
        }
    }

    /** a line goes between two pixels of different parts, or where the depth jumps */
    private fun differs(id: Int, z: Float, k: Int, k2: Int, pid: IntArray, depth: FloatArray, tix: IntArray): Boolean {
        val id2 = pid[k2]; val z2 = depth[k2]
        if (id != 0 && id == id2) {
            // a sharp fold between two flat faces of one shape (cube edges, cylinder rims)
            val a = tix[k] * 3; val b = tix[k2] * 3
            if (a != b && triN[a] * triN[b] + triN[a + 1] * triN[b + 1] + triN[a + 2] * triN[b + 2] < 0.8f) return true
        }
        if (id != id2) {
            // two parts of one figure meet: draw the line only where one is clearly in front
            if (id shr 8 == id2 shr 8 && id and 255 != 0 && id2 and 255 != 0) return abs(1f / z - 1f / z2) > 0.03f
            return true
        }
        if (id == 0) return false
        val a = 1f / z; val b2 = 1f / z2
        return abs(a - b2) > 0.06f * min(a, b2)
    }

    private fun tint(c: Int, s: Float): Int {
        val k = s.coerceIn(0f, 1.2f)
        val r = (((c shr 16) and 255) * k).roundToInt().coerceIn(0, 255)
        val g = (((c shr 8) and 255) * k).roundToInt().coerceIn(0, 255)
        val b = ((c and 255) * k).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(s: Int) = ((((a shr s) and 255) * (1 - t)) + (((b shr s) and 255) * t)).roundToInt()
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}
