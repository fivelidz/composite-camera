package com.fivelidz.compositecamera.pro

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Estimate and undo the scale + translation change between two focus-bracket frames
 * due to **focus breathing** (the lens slightly magnifies as it focuses closer).
 *
 * Algorithm (simple but effective):
 *   1. Downsample both luminance images to a small working resolution.
 *   2. Search a small grid of (dx, dy, scale) candidates around 1.0 scale.
 *   3. Pick the triple that maximises normalised cross-correlation between
 *      luminance(ref) and bilinearly-resampled luminance(other) at that
 *      candidate (dx, dy, scale).
 *   4. Scale up the (dx, dy) back to full image coords.
 *
 * Scale search is constrained to ±2% — phone focus breathing is small
 * (typically 0.5-1.5%). Searching wider would tank performance and risk false
 * scale matches on textured backgrounds.
 *
 * For DSLR macro lenses the breathing range can be much larger (~5-10%) so
 * for a future generalisation we'd widen this search. For phones, ±2% is plenty.
 */
object FocusBreathing {

    private const val WORK_EDGE = 256
    private const val SEARCH_RADIUS_PX = 6
    private val SCALE_CANDIDATES = floatArrayOf(0.985f, 0.99f, 0.995f, 1.0f, 1.005f, 1.01f, 1.015f)

    // Minimum correlation gain (over the no-correction baseline) required before we
    // actually APPLY a non-trivial correction. Stops us from chasing noise when the
    // scene is mostly flat / featureless.
    private const val MIN_CORRELATION_GAIN = 0.02f

    data class Correction(val dx: Int, val dy: Int, val scale: Float)

    fun estimate(ref: FloatArray, other: FloatArray, w: Int, h: Int): Correction {
        val long = max(w, h)
        val s = max(1, long / WORK_EDGE)
        val sw = w / s; val sh = h / s
        if (sw < 32 || sh < 32) return Correction(0, 0, 1f)

        val refL = downsampleLum(ref, w, h, sw, sh, s, normalise = true)
        val othL = downsampleLum(other, w, h, sw, sh, s, normalise = true)

        // Baseline: no correction (dx=0, dy=0, scale=1).
        val baseline = correlate(refL, othL, sw, sh, 0, 0)

        var bestDx = 0; var bestDy = 0; var bestScale = 1f
        var bestScore = baseline

        for (scale in SCALE_CANDIDATES) {
            val resampled = if (abs(scale - 1f) < 0.001f) othL
                else resampleScaledAroundCentre(othL, sw, sh, scale)

            for (dy in -SEARCH_RADIUS_PX..SEARCH_RADIUS_PX) {
                for (dx in -SEARCH_RADIUS_PX..SEARCH_RADIUS_PX) {
                    val score = correlate(refL, resampled, sw, sh, dx, dy)
                    if (score > bestScore) {
                        bestScore = score; bestDx = dx; bestDy = dy; bestScale = scale
                    }
                }
            }
        }

        // Only return a non-trivial correction if it materially beats the no-correction baseline.
        // Otherwise we're amplifying noise — every adjacent focus plane would get a different
        // spurious correction, compounding into visible softening.
        return if (bestScore - baseline < MIN_CORRELATION_GAIN) {
            Correction(0, 0, 1f)
        } else {
            Correction(bestDx * s, bestDy * s, bestScale)
        }
    }

    /**
     * Apply (translation, scale-around-centre) correction to an interleaved-RGB float buffer.
     * Bilinear sampling. Pixels outside the source area clamp to the edge.
     */
    fun applyInPlace(rgb: FloatArray, w: Int, h: Int, dx: Int, dy: Int, scale: Float) {
        if (dx == 0 && dy == 0 && abs(scale - 1f) < 1e-4f) return
        val src = rgb.copyOf()
        val cx = w / 2f; val cy = h / 2f

        var di = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                // Map output pixel back to source coordinate.
                val sxF = cx + (x - cx - dx) / scale
                val syF = cy + (y - cy - dy) / scale

                val sxC = sxF.coerceIn(0f, w - 1.0001f)
                val syC = syF.coerceIn(0f, h - 1.0001f)
                val x0 = sxC.toInt(); val y0 = syC.toInt()
                val x1 = min(x0 + 1, w - 1); val y1 = min(y0 + 1, h - 1)
                val tx = sxC - x0; val ty = syC - y0

                val i00 = (y0 * w + x0) * 3
                val i01 = (y0 * w + x1) * 3
                val i10 = (y1 * w + x0) * 3
                val i11 = (y1 * w + x1) * 3

                for (c in 0..2) {
                    val v00 = src[i00 + c]; val v01 = src[i01 + c]
                    val v10 = src[i10 + c]; val v11 = src[i11 + c]
                    val v0 = v00 * (1f - tx) + v01 * tx
                    val v1 = v10 * (1f - tx) + v11 * tx
                    rgb[di++] = v0 * (1f - ty) + v1 * ty
                }
            }
        }
    }

    private fun downsampleLum(
        rgb: FloatArray, srcW: Int, srcH: Int,
        dstW: Int, dstH: Int, scale: Int, normalise: Boolean,
    ): FloatArray {
        val out = FloatArray(dstW * dstH)
        for (y in 0 until dstH) {
            val sy = (y * scale).coerceAtMost(srcH - 1)
            for (x in 0 until dstW) {
                val sx = (x * scale).coerceAtMost(srcW - 1)
                val si = (sy * srcW + sx) * 3
                out[y * dstW + x] = 0.2126f * rgb[si] + 0.7152f * rgb[si + 1] + 0.0722f * rgb[si + 2]
            }
        }
        if (normalise) {
            var sum = 0f
            for (v in out) sum += v
            val mean = sum / out.size
            var sq = 0f
            for (i in out.indices) { val d = out[i] - mean; out[i] = d; sq += d * d }
            val sd = sqrt(sq / out.size).coerceAtLeast(1e-6f)
            for (i in out.indices) out[i] /= sd
        }
        return out
    }

    /** Resample a single-channel image by [scale] around the centre. Bilinear. */
    private fun resampleScaledAroundCentre(src: FloatArray, w: Int, h: Int, scale: Float): FloatArray {
        val out = FloatArray(src.size)
        val cx = w / 2f; val cy = h / 2f
        for (y in 0 until h) {
            for (x in 0 until w) {
                val sxF = (cx + (x - cx) / scale).coerceIn(0f, w - 1.0001f)
                val syF = (cy + (y - cy) / scale).coerceIn(0f, h - 1.0001f)
                val x0 = sxF.toInt(); val y0 = syF.toInt()
                val x1 = min(x0 + 1, w - 1); val y1 = min(y0 + 1, h - 1)
                val tx = sxF - x0; val ty = syF - y0
                val v00 = src[y0 * w + x0]; val v01 = src[y0 * w + x1]
                val v10 = src[y1 * w + x0]; val v11 = src[y1 * w + x1]
                val v0 = v00 * (1f - tx) + v01 * tx
                val v1 = v10 * (1f - tx) + v11 * tx
                out[y * w + x] = v0 * (1f - ty) + v1 * ty
            }
        }
        return out
    }

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
                sum += ref[y * w + x] * oth[(y + dy) * w + (x + dx)]
                count++
            }
        }
        return if (count > 0) sum / count else -Float.MAX_VALUE
    }
}
