package com.fivelidz.compositecamera.pro

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Cheap translational image alignment for bracket exposure fusion.
 *
 * Pure pixel-domain integer-pixel cross-correlation on downsampled luminance.
 * Handles the typical ±1–5 px drift between bracket frames on a tripod (or even
 * a steady hand), which is what causes ghosting / blur in naive fusion.
 *
 * Does NOT handle rotation, scale, or perspective — for those we'd need full
 * ECC affine (OpenCV) or feature-based SIFT/ORB (also OpenCV). Translation
 * alone catches 80% of bracket-fusion ghosting in practice.
 *
 * Algorithm:
 *   1. Build luminance images of both frames.
 *   2. Downsample to a small working resolution (~256 px longest edge) — speed.
 *   3. Search (dx, dy) ∈ [-radius, +radius] in the downsampled domain, picking the
 *      shift that maximises normalised cross-correlation of luminance.
 *   4. Scale the shift back to the full image's coordinate system.
 *
 * Returns the integer (dx, dy) the second frame should be translated by to align
 * with the first frame.
 */
object Alignment {

    private const val WORK_EDGE = 256
    private const val SEARCH_RADIUS = 12

    /**
     * Compute the translational shift that aligns [other] to [reference].
     * Both inputs are interleaved RGB float arrays of identical size (w*h*3).
     */
    fun translationalShift(
        reference: FloatArray,
        other: FloatArray,
        w: Int,
        h: Int,
    ): Pair<Int, Int> {
        // Scale factor: choose so that max(w,h)/scale ≈ WORK_EDGE.
        val long = max(w, h)
        val scale = max(1, long / WORK_EDGE)
        val sw = w / scale
        val sh = h / scale
        if (sw < 32 || sh < 32) return 0 to 0

        val refL = downsampleLum(reference, w, h, sw, sh, scale)
        val othL = downsampleLum(other, w, h, sw, sh, scale)

        var bestDx = 0; var bestDy = 0; var bestScore = -Float.MAX_VALUE
        for (dy in -SEARCH_RADIUS..SEARCH_RADIUS) {
            for (dx in -SEARCH_RADIUS..SEARCH_RADIUS) {
                val score = correlate(refL, othL, sw, sh, dx, dy)
                if (score > bestScore) { bestScore = score; bestDx = dx; bestDy = dy }
            }
        }
        return (bestDx * scale) to (bestDy * scale)
    }

    /**
     * Shift the interleaved RGB float buffer in place by (dx, dy) pixels.
     * Pixels coming from outside the image are clamped (edge replication).
     */
    fun shiftInPlace(rgb: FloatArray, w: Int, h: Int, dx: Int, dy: Int) {
        if (dx == 0 && dy == 0) return
        val copy = rgb.copyOf()
        var d = 0
        for (y in 0 until h) {
            val sy = (y - dy).coerceIn(0, h - 1)
            for (x in 0 until w) {
                val sx = (x - dx).coerceIn(0, w - 1)
                val si = (sy * w + sx) * 3
                rgb[d]     = copy[si]
                rgb[d + 1] = copy[si + 1]
                rgb[d + 2] = copy[si + 2]
                d += 3
            }
        }
    }

    private fun downsampleLum(
        rgb: FloatArray, srcW: Int, srcH: Int, dstW: Int, dstH: Int, scale: Int,
    ): FloatArray {
        val out = FloatArray(dstW * dstH)
        for (y in 0 until dstH) {
            val sy = (y * scale).coerceAtMost(srcH - 1)
            for (x in 0 until dstW) {
                val sx = (x * scale).coerceAtMost(srcW - 1)
                val si = (sy * srcW + sx) * 3
                val r = rgb[si]; val g = rgb[si + 1]; val b = rgb[si + 2]
                out[y * dstW + x] = 0.2126f * r + 0.7152f * g + 0.0722f * b
            }
        }
        // Normalise to zero-mean unit-variance for proper NCC.
        var sum = 0f
        for (v in out) sum += v
        val mean = sum / out.size
        var sq = 0f
        for (i in out.indices) { val d = out[i] - mean; out[i] = d; sq += d * d }
        val sd = kotlin.math.sqrt(sq / out.size).coerceAtLeast(1e-6f)
        for (i in out.indices) out[i] /= sd
        return out
    }

    /** Normalised cross-correlation between [ref] and shifted [oth]. Higher = better match. */
    private fun correlate(
        ref: FloatArray, oth: FloatArray, w: Int, h: Int, dx: Int, dy: Int,
    ): Float {
        val xStart = max(0, -dx); val xEnd = min(w, w - dx)
        val yStart = max(0, -dy); val yEnd = min(h, h - dy)
        if (xEnd <= xStart || yEnd <= yStart) return -Float.MAX_VALUE

        var sum = 0f
        var count = 0
        for (y in yStart until yEnd) {
            for (x in xStart until xEnd) {
                val a = ref[y * w + x]
                val b = oth[(y + dy) * w + (x + dx)]
                sum += a * b
                count++
            }
        }
        // Normalise by overlap area so larger shifts aren't penalised disproportionately.
        return if (count > 0) sum / count else -Float.MAX_VALUE
    }
}
