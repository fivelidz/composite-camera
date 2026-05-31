package com.fivelidz.compositecamera.pro

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.pow

/**
 * Multi-band Laplacian/Gaussian pyramid utilities, shared between ExposureFusion and FocusStack.
 *
 * These match the same 5-tap [1 4 6 4 1]/16 separable filter used in the seminal Burt & Adelson
 * (1983) paper, then by enfuse / Hugin for blending and by Helicon Focus for focus stacking.
 *
 * Operates on interleaved-RGB linear-light float buffers (3 floats per pixel) for image data,
 * and on single-channel float buffers for weight maps.
 */
object Pyramid {

    private val GAUSS_TAPS = floatArrayOf(1f / 16f, 4f / 16f, 6f / 16f, 4f / 16f, 1f / 16f)

    data class Level(val data: FloatArray, val w: Int, val h: Int)

    /** Choose actual pyramid depth based on image size — don't go smaller than ~64 px. */
    fun effectiveLevels(w: Int, h: Int, requested: Int): Int {
        var levels = 1
        var cw = w; var ch = h
        while (levels < requested && cw > 64 && ch > 64) {
            cw = (cw + 1) / 2
            ch = (ch + 1) / 2
            levels++
        }
        return levels
    }

    // ---- sRGB ⇄ linear ----

    private val srgbToLinearLUT = FloatArray(256) { i ->
        val s = i / 255f
        if (s <= 0.04045f) s / 12.92f else ((s + 0.055f) / 1.055f).pow(2.4f)
    }

    fun bitmapToLinearRGB(bmp: Bitmap): FloatArray {
        val w = bmp.width; val h = bmp.height
        val px = IntArray(w * h); bmp.getPixels(px, 0, w, 0, 0, w, h)
        val out = FloatArray(w * h * 3)
        var p = 0
        for (k in 0 until w * h) {
            val v = px[k]
            out[p++] = srgbToLinearLUT[(v shr 16) and 0xFF]
            out[p++] = srgbToLinearLUT[(v shr 8) and 0xFF]
            out[p++] = srgbToLinearLUT[v and 0xFF]
        }
        return out
    }

    fun linearRGBToBitmap(rgb: FloatArray, w: Int, h: Int): Bitmap {
        val px = IntArray(w * h)
        var p = 0
        for (k in 0 until w * h) {
            val r = linearToSrgb(rgb[p++])
            val g = linearToSrgb(rgb[p++])
            val b = linearToSrgb(rgb[p++])
            px[k] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun linearToSrgb(c: Float): Int {
        val s = if (c <= 0.0031308f) c * 12.92f else 1.055f * c.pow(1f / 2.4f) - 0.055f
        return (s * 255f + 0.5f).toInt().coerceIn(0, 255)
    }

    // ---- pyramids ----

    /** Gaussian pyramid of a single-channel float image. Level 0 = finest. */
    fun gaussianPyramid1(src: FloatArray, w: Int, h: Int, levels: Int): Array<Level> {
        val out = arrayOfNulls<Level>(levels)
        var cur = src; var cw = w; var ch = h
        out[0] = Level(cur, cw, ch)
        for (k in 1 until levels) {
            val (next, nw, nh) = downsample1(cur, cw, ch)
            out[k] = Level(next, nw, nh)
            cur = next; cw = nw; ch = nh
        }
        @Suppress("UNCHECKED_CAST")
        return out as Array<Level>
    }

    /** Gaussian pyramid of an interleaved-RGB float image. */
    fun gaussianPyramid3(src: FloatArray, w: Int, h: Int, levels: Int): Array<Level> {
        val out = arrayOfNulls<Level>(levels)
        var cur = src; var cw = w; var ch = h
        out[0] = Level(cur, cw, ch)
        for (k in 1 until levels) {
            val (next, nw, nh) = downsample3(cur, cw, ch)
            out[k] = Level(next, nw, nh)
            cur = next; cw = nw; ch = nh
        }
        @Suppress("UNCHECKED_CAST")
        return out as Array<Level>
    }

    /** Laplacian pyramid of an interleaved-RGB image. Top level is the Gaussian residual. */
    fun laplacianPyramid3(src: FloatArray, w: Int, h: Int, levels: Int): Array<Level> {
        val g = gaussianPyramid3(src, w, h, levels)
        val out = arrayOfNulls<Level>(levels)
        for (k in 0 until levels - 1) {
            val gk = g[k]; val gkp = g[k + 1]
            val up = upsample3(gkp.data, gkp.w, gkp.h, gk.w, gk.h)
            val lap = FloatArray(gk.data.size)
            for (i in gk.data.indices) lap[i] = gk.data[i] - up[i]
            out[k] = Level(lap, gk.w, gk.h)
        }
        out[levels - 1] = g[levels - 1]
        @Suppress("UNCHECKED_CAST")
        return out as Array<Level>
    }

    /** Collapse a Laplacian pyramid (coarsest → finest) back to a full-resolution image. */
    fun collapse3(pyr: Array<Level>): Level {
        var cur = pyr[pyr.size - 1].data
        var cw = pyr[pyr.size - 1].w; var ch = pyr[pyr.size - 1].h
        for (k in pyr.size - 2 downTo 0) {
            val lap = pyr[k]
            val up = upsample3(cur, cw, ch, lap.w, lap.h)
            val out = FloatArray(lap.data.size)
            for (i in lap.data.indices) out[i] = lap.data[i] + up[i]
            cur = out; cw = lap.w; ch = lap.h
        }
        return Level(cur, cw, ch)
    }

    // ---- primitives ----

    private fun downsample1(src: FloatArray, w: Int, h: Int): Triple<FloatArray, Int, Int> {
        val blurred = blur1(src, w, h)
        val nw = (w + 1) / 2; val nh = (h + 1) / 2
        val out = FloatArray(nw * nh)
        for (y in 0 until nh) for (x in 0 until nw) {
            out[y * nw + x] = blurred[(y * 2).coerceAtMost(h - 1) * w + (x * 2).coerceAtMost(w - 1)]
        }
        return Triple(out, nw, nh)
    }

    private fun downsample3(src: FloatArray, w: Int, h: Int): Triple<FloatArray, Int, Int> {
        val blurred = blur3(src, w, h)
        val nw = (w + 1) / 2; val nh = (h + 1) / 2
        val out = FloatArray(nw * nh * 3)
        for (y in 0 until nh) {
            val sy = (y * 2).coerceAtMost(h - 1)
            for (x in 0 until nw) {
                val sx = (x * 2).coerceAtMost(w - 1)
                val si = (sy * w + sx) * 3
                val di = (y * nw + x) * 3
                out[di]     = blurred[si]
                out[di + 1] = blurred[si + 1]
                out[di + 2] = blurred[si + 2]
            }
        }
        return Triple(out, nw, nh)
    }

    fun upsample3(src: FloatArray, sw: Int, sh: Int, dw: Int, dh: Int): FloatArray {
        val expanded = FloatArray(dw * dh * 3)
        for (y in 0 until sh) {
            val dy = y * 2
            if (dy >= dh) break
            for (x in 0 until sw) {
                val dx = x * 2
                if (dx >= dw) break
                val si = (y * sw + x) * 3
                val di = (dy * dw + dx) * 3
                expanded[di]     = src[si]     * 4f
                expanded[di + 1] = src[si + 1] * 4f
                expanded[di + 2] = src[si + 2] * 4f
            }
        }
        return blur3(expanded, dw, dh)
    }

    fun upsample1(src: FloatArray, sw: Int, sh: Int, dw: Int, dh: Int): FloatArray {
        val expanded = FloatArray(dw * dh)
        for (y in 0 until sh) {
            val dy = y * 2
            if (dy >= dh) break
            for (x in 0 until sw) {
                val dx = x * 2
                if (dx >= dw) break
                expanded[dy * dw + dx] = src[y * sw + x] * 4f
            }
        }
        return blur1(expanded, dw, dh)
    }

    private fun blur1(src: FloatArray, w: Int, h: Int): FloatArray {
        val tmp = FloatArray(src.size)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                var s = 0f
                for (k in -2..2) {
                    val xi = (x + k).coerceIn(0, w - 1)
                    s += GAUSS_TAPS[k + 2] * src[row + xi]
                }
                tmp[row + x] = s
            }
        }
        val out = FloatArray(src.size)
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0f
            for (k in -2..2) {
                val yi = (y + k).coerceIn(0, h - 1)
                s += GAUSS_TAPS[k + 2] * tmp[yi * w + x]
            }
            out[y * w + x] = s
        }
        return out
    }

    private fun blur3(src: FloatArray, w: Int, h: Int): FloatArray {
        val tmp = FloatArray(src.size)
        for (y in 0 until h) {
            val rowBase = y * w * 3
            for (x in 0 until w) {
                var sr = 0f; var sg = 0f; var sb = 0f
                for (k in -2..2) {
                    val xi = (x + k).coerceIn(0, w - 1)
                    val ti = rowBase + xi * 3
                    val wt = GAUSS_TAPS[k + 2]
                    sr += wt * src[ti]; sg += wt * src[ti + 1]; sb += wt * src[ti + 2]
                }
                val di = rowBase + x * 3
                tmp[di] = sr; tmp[di + 1] = sg; tmp[di + 2] = sb
            }
        }
        val out = FloatArray(src.size)
        for (y in 0 until h) for (x in 0 until w) {
            var sr = 0f; var sg = 0f; var sb = 0f
            for (k in -2..2) {
                val yi = (y + k).coerceIn(0, h - 1)
                val ti = (yi * w + x) * 3
                val wt = GAUSS_TAPS[k + 2]
                sr += wt * tmp[ti]; sg += wt * tmp[ti + 1]; sb += wt * tmp[ti + 2]
            }
            val di = (y * w + x) * 3
            out[di] = sr; out[di + 1] = sg; out[di + 2] = sb
        }
        return out
    }
}
