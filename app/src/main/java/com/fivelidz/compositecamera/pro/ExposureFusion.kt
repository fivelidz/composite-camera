package com.fivelidz.compositecamera.pro

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Proper Mertens-Kautz-Van Reeth exposure fusion using a multi-band Laplacian pyramid,
 * operating in linear light.
 *
 * This is the algorithm enfuse / Hugin uses and is the SOTA for real-estate-style HDR
 * exposure blending without tonemapping artefacts. Reference:
 *
 *   T. Mertens, J. Kautz, F. Van Reeth.
 *   "Exposure Fusion: A Simple and Practical Alternative to High Dynamic Range Photography."
 *   Computer Graphics Forum, 2009.
 *
 * Pipeline per pixel-level fusion:
 *   1. Decode all bracket JPEGs and apply inverse sRGB gamma → linear-light float buffers
 *   2. Per source: compute weight = wellExposedness^we_pow × saturation^ws_pow × contrast^wc_pow
 *      Default exponents follow enfuse: we=1.0, ws=0.2, wc=0.0 for pure exposure fusion.
 *   3. Normalise weights per pixel across the bracket so they sum to 1.
 *   4. Build a GAUSSIAN pyramid of each weight map.
 *   5. Build a LAPLACIAN pyramid of each linear source image.
 *   6. Blend at every pyramid level: L_out[k] = Σ_i G_w[i][k] * L_src[i][k]
 *   7. Collapse the blended Laplacian pyramid → fused linear image.
 *   8. Apply sRGB gamma → 8-bit ARGB Bitmap.
 *
 * Memory note: we work at a capped longest-edge resolution. A 5-level pyramid of three
 * 2000×1500 RGB float images is ~210 MB peak — fine on modern phones, would OOM cheap
 * tablets. The maxLongEdge parameter caps this.
 */
object ExposureFusion {

    private const val TAG = "ExposureFusion"

    data class Frame(val jpeg: ByteArray, val exposureBucket: Int)

    /** Optional callback for streaming progress + intermediate previews. */
    interface Reporter {
        fun progress(p: Float, label: String) {}
        /** Called once per source image after weight-map compute (debug visualisation). */
        fun weightMap(idx: Int, weightMapDownscaled: Bitmap) {}
    }

    /**
     * Fuse multiple bracket exposures into one.
     *
     * @param frames        list of exposure-bracket frames, any order.
     * @param maxLongEdge   working resolution cap. Output will be ≤ this size on longest edge.
     * @param pyramidLevels number of pyramid levels (4–6 reasonable; default 5).
     * @param alignFrames   if true, run translational phase correlation against frame 0 first.
     */
    fun fuse(
        frames: List<Frame>,
        maxLongEdge: Int = 2400,
        pyramidLevels: Int = 5,
        alignFrames: Boolean = true,
        weExposure: Float = 1.0f,
        weSaturation: Float = 0.2f,
        weContrast: Float = 0.0f,
        reporter: Reporter = object : Reporter {},
    ): Bitmap {
        reporter.progress(0.02f, "Decoding ${frames.size} frames")

        // 1. Decode + downscale.
        val n = frames.size
        require(n >= 2) { "Need ≥ 2 frames" }
        val decoded = frames.map { decodeScaled(it.jpeg, maxLongEdge) }
        val w = decoded[0].width
        val h = decoded[0].height
        Log.i(TAG, "Working resolution: ${w}x${h}, levels=$pyramidLevels")

        // 2. Convert to linear-light float buffers (3 floats per pixel: R G B in [0,1]).
        reporter.progress(0.08f, "Linearising")
        val linear: Array<FloatArray> = Array(n) { i -> bitmapToLinearRGB(decoded[i]) }
        // We can release the original bitmaps now that we have float copies.
        decoded.forEach { if (!it.isRecycled) it.recycle() }

        // 3. Align frames 1..n-1 against frame 0 via translational phase correlation.
        if (alignFrames && n > 1) {
            reporter.progress(0.12f, "Aligning frames")
            for (i in 1 until n) {
                val (dx, dy) = Alignment.translationalShift(linear[0], linear[i], w, h)
                if (abs(dx) + abs(dy) > 0) {
                    Log.i(TAG, "Frame $i shift: dx=$dx dy=$dy")
                    Alignment.shiftInPlace(linear[i], w, h, dx, dy)
                }
            }
        }

        // 4. Per-frame weight maps.
        reporter.progress(0.18f, "Computing weight maps")
        val weights = Array(n) { i ->
            computeWeightMap(linear[i], w, h, weExposure, weSaturation, weContrast)
        }
        // Normalise across frames.
        normaliseWeights(weights, w, h)

        // 5. Build pyramids per source one at a time (so we can free the linear float buffers
        // as soon as their pyramid is built — saves ~25 MB per source on a 1800-edge image).
        reporter.progress(0.30f, "Building pyramids")
        val levels = effectiveLevels(w, h, pyramidLevels)
        val srcPyramids = Array<Array<Pair<FloatArray, Pair<Int, Int>>>?>(n) { null }
        val wPyramids   = Array<Array<Pair<FloatArray, Pair<Int, Int>>>?>(n) { null }
        for (i in 0 until n) {
            srcPyramids[i] = laplacianPyramidRGB(linear[i], w, h, levels)
            wPyramids[i]   = gaussianPyramid(weights[i], w, h, levels)
            linear[i] = FloatArray(0)   // free full-res linear buffer (kept in pyramid level 0)
            weights[i] = FloatArray(0)
            reporter.progress(0.30f + 0.25f * ((i + 1).toFloat() / n),
                "Building pyramid ${i + 1}/$n")
        }
        System.gc()

        // 6. Blend at each level. We accumulate ONE level at a time and release the per-source
        // data for that level as we go, so peak memory stays roughly = (3 sources × 1 level)
        // rather than (3 sources × all levels).
        reporter.progress(0.60f, "Blending pyramids")
        val outPyramid = Array<Pair<FloatArray, Pair<Int, Int>>?>(levels) { null }
        for (k in 0 until levels) {
            val (lw, lh) = srcPyramids[0]!![k].second
            val rgb = FloatArray(lw * lh * 3)
            for (i in 0 until n) {
                val src = srcPyramids[i]!![k].first
                val wmap = wPyramids[i]!![k].first
                var p = 0
                for (px in 0 until lw * lh) {
                    val wt = wmap[px]
                    rgb[p]     += wt * src[p];     p++
                    rgb[p]     += wt * src[p];     p++
                    rgb[p]     += wt * src[p];     p++
                }
                // Free this level's source/weight data NOW.
                srcPyramids[i]!![k] = FloatArray(0) to (lw to lh)
                wPyramids[i]!![k]   = FloatArray(0) to (lw to lh)
            }
            outPyramid[k] = rgb to (lw to lh)
        }
        @Suppress("UNCHECKED_CAST")
        val outPyramidFinal = outPyramid as Array<Pair<FloatArray, Pair<Int, Int>>>

        // 7. Collapse.
        reporter.progress(0.80f, "Collapsing pyramid")
        val (fusedLinear, _) = collapsePyramidRGB(outPyramidFinal)

        // 8. Encode back to 8-bit ARGB.
        reporter.progress(0.92f, "Encoding")
        val outBmp = linearRGBToBitmap(fusedLinear, w, h)
        reporter.progress(1f, "Done")
        return outBmp
    }

    // ===== Linear <-> sRGB conversion =====

    private val srgbToLinearLUT: FloatArray = FloatArray(256) { i ->
        val s = i / 255f
        if (s <= 0.04045f) s / 12.92f else ((s + 0.055f) / 1.055f).pow(2.4f)
    }

    private fun bitmapToLinearRGB(bmp: Bitmap): FloatArray {
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

    private fun linearRGBToBitmap(rgb: FloatArray, w: Int, h: Int): Bitmap {
        val px = IntArray(w * h)
        var p = 0
        for (k in 0 until w * h) {
            val r = linearToSrgb(rgb[p++])
            val g = linearToSrgb(rgb[p++])
            val b = linearToSrgb(rgb[p++])
            px[k] = (0xFF shl 24) or
                ((r.coerceIn(0, 255)) shl 16) or
                ((g.coerceIn(0, 255)) shl 8) or
                 (b.coerceIn(0, 255))
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun linearToSrgb(c: Float): Int {
        val s = if (c <= 0.0031308f) c * 12.92f else 1.055f * c.pow(1f / 2.4f) - 0.055f
        return (s * 255f + 0.5f).toInt()
    }

    // ===== Weight map computation =====

    /** Wellexposedness × saturation^ws × contrast^wc, on linear-light RGB. */
    private fun computeWeightMap(
        rgb: FloatArray,
        w: Int, h: Int,
        weExposure: Float,
        weSaturation: Float,
        weContrast: Float,
    ): FloatArray {
        val n = w * h
        val out = FloatArray(n)
        val sigma = 0.2f
        val twoSigSq = 2f * sigma * sigma

        // First pass: luminance + saturation + per-pixel partial weight (before contrast).
        val lum = FloatArray(n)
        var p = 0
        for (k in 0 until n) {
            val r = rgb[p++]; val g = rgb[p++]; val b = rgb[p++]
            val L = 0.2126f * r + 0.7152f * g + 0.0722f * b
            lum[k] = L
            // Well-exposedness: Gaussian per channel centred at 0.5, multiplied.
            val we = exp(-((r - 0.5f) * (r - 0.5f)) / twoSigSq) *
                     exp(-((g - 0.5f) * (g - 0.5f)) / twoSigSq) *
                     exp(-((b - 0.5f) * (b - 0.5f)) / twoSigSq)
            // Saturation: std of channels.
            val mean = (r + g + b) / 3f
            val sat = sqrt(((r - mean) * (r - mean) + (g - mean) * (g - mean) + (b - mean) * (b - mean)) / 3f)
            // Partial weight (we'll multiply in contrast after computing it).
            var W = 1f
            if (weExposure > 0)   W *= we.pow(weExposure).coerceAtLeast(1e-12f)
            if (weSaturation > 0) W *= (sat + 1e-6f).pow(weSaturation)
            out[k] = W
        }

        // Optional contrast factor via Laplacian-of-luminance.
        if (weContrast > 0f) {
            for (y in 1 until h - 1) {
                val row = y * w
                for (x in 1 until w - 1) {
                    val c = row + x
                    val L = -4f * lum[c] + lum[c - 1] + lum[c + 1] + lum[c - w] + lum[c + w]
                    val contrast = abs(L) + 1e-6f
                    out[c] *= contrast.pow(weContrast)
                }
            }
        }
        return out
    }

    /** Normalise so that for each pixel, Σ_i weights[i][k] = 1. Adds tiny epsilon to avoid 0. */
    private fun normaliseWeights(weights: Array<FloatArray>, w: Int, h: Int) {
        val n = w * h
        val total = FloatArray(n)
        for (i in weights.indices) for (k in 0 until n) total[k] += weights[i][k]
        for (k in 0 until n) if (total[k] < 1e-12f) total[k] = 1e-12f
        for (i in weights.indices) for (k in 0 until n) weights[i][k] /= total[k]
    }

    // ===== Gaussian / Laplacian pyramids =====

    /** Standard 5-tap 1D filter [1 4 6 4 1] / 16. */
    private val GAUSS_TAPS = floatArrayOf(1f / 16f, 4f / 16f, 6f / 16f, 4f / 16f, 1f / 16f)

    /** Decide actual pyramid depth based on image size (don't go smaller than ~32 px). */
    private fun effectiveLevels(w: Int, h: Int, requested: Int): Int {
        var levels = 1
        var cw = w; var ch = h
        while (levels < requested && cw > 64 && ch > 64) {
            cw = (cw + 1) / 2
            ch = (ch + 1) / 2
            levels++
        }
        return levels
    }

    /**
     * Gaussian pyramid of a single-channel float image.
     * Returns list of (data, (w,h)) from finest (level 0 = original) to coarsest.
     */
    private fun gaussianPyramid(
        src: FloatArray, w: Int, h: Int, levels: Int,
    ): Array<Pair<FloatArray, Pair<Int, Int>>> {
        val out = arrayOfNulls<Pair<FloatArray, Pair<Int, Int>>>(levels)
        var current = src
        var cw = w; var ch = h
        out[0] = current to (cw to ch)
        for (k in 1 until levels) {
            val (next, nw, nh) = downsample1ch(current, cw, ch)
            out[k] = next to (nw to nh)
            current = next; cw = nw; ch = nh
        }
        @Suppress("UNCHECKED_CAST")
        return out as Array<Pair<FloatArray, Pair<Int, Int>>>
    }

    /** Same idea but for 3-channel RGB float (interleaved RGBRGB...). */
    private fun gaussianPyramidRGB(
        src: FloatArray, w: Int, h: Int, levels: Int,
    ): Array<Pair<FloatArray, Pair<Int, Int>>> {
        val out = arrayOfNulls<Pair<FloatArray, Pair<Int, Int>>>(levels)
        var current = src; var cw = w; var ch = h
        out[0] = current to (cw to ch)
        for (k in 1 until levels) {
            val (next, nw, nh) = downsample3ch(current, cw, ch)
            out[k] = next to (nw to nh)
            current = next; cw = nw; ch = nh
        }
        @Suppress("UNCHECKED_CAST")
        return out as Array<Pair<FloatArray, Pair<Int, Int>>>
    }

    /**
     * Laplacian pyramid of RGB image: L[k] = G[k] - upsample(G[k+1]), L[last] = G[last] (residual).
     */
    private fun laplacianPyramidRGB(
        src: FloatArray, w: Int, h: Int, levels: Int,
    ): Array<Pair<FloatArray, Pair<Int, Int>>> {
        val gauss = gaussianPyramidRGB(src, w, h, levels)
        val out = arrayOfNulls<Pair<FloatArray, Pair<Int, Int>>>(levels)
        for (k in 0 until levels - 1) {
            val (g_k, dims_k) = gauss[k]
            val (g_kp, dims_kp) = gauss[k + 1]
            val up = upsample3ch(g_kp, dims_kp.first, dims_kp.second, dims_k.first, dims_k.second)
            val lap = FloatArray(g_k.size)
            for (i in g_k.indices) lap[i] = g_k[i] - up[i]
            out[k] = lap to dims_k
        }
        out[levels - 1] = gauss[levels - 1]
        @Suppress("UNCHECKED_CAST")
        return out as Array<Pair<FloatArray, Pair<Int, Int>>>
    }

    /** Collapse a Laplacian pyramid (coarsest → finest) back to a full-resolution image. */
    private fun collapsePyramidRGB(
        pyr: Array<Pair<FloatArray, Pair<Int, Int>>>,
    ): Pair<FloatArray, Pair<Int, Int>> {
        var current = pyr[pyr.size - 1].first
        var (cw, ch) = pyr[pyr.size - 1].second
        for (k in pyr.size - 2 downTo 0) {
            val (lap, dims) = pyr[k]
            val up = upsample3ch(current, cw, ch, dims.first, dims.second)
            val out = FloatArray(lap.size)
            for (i in lap.indices) out[i] = lap[i] + up[i]
            current = out; cw = dims.first; ch = dims.second
        }
        return current to (cw to ch)
    }

    // ----- downsample / upsample primitives -----

    private fun downsample1ch(src: FloatArray, w: Int, h: Int): Triple<FloatArray, Int, Int> {
        // Blur 5-tap separable, then drop every other sample.
        val blurred = blur1ch5tap(src, w, h)
        val nw = (w + 1) / 2; val nh = (h + 1) / 2
        val out = FloatArray(nw * nh)
        for (y in 0 until nh) for (x in 0 until nw) {
            out[y * nw + x] = blurred[(y * 2).coerceAtMost(h - 1) * w + (x * 2).coerceAtMost(w - 1)]
        }
        return Triple(out, nw, nh)
    }

    private fun downsample3ch(src: FloatArray, w: Int, h: Int): Triple<FloatArray, Int, Int> {
        val blurred = blur3ch5tap(src, w, h)
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

    /** Upsample to (dstW, dstH) using zero-fill then 5-tap blur ×4. Used in both Lap and collapse. */
    private fun upsample3ch(
        src: FloatArray, srcW: Int, srcH: Int, dstW: Int, dstH: Int,
    ): FloatArray {
        val expanded = FloatArray(dstW * dstH * 3)
        // Zero-fill: place source samples at even indices, fill multiplied by 4 (so blur with
        // unit-sum kernel preserves brightness).
        for (y in 0 until srcH) {
            val dy = y * 2
            if (dy >= dstH) break
            for (x in 0 until srcW) {
                val dx = x * 2
                if (dx >= dstW) break
                val si = (y * srcW + x) * 3
                val di = (dy * dstW + dx) * 3
                expanded[di]     = src[si]     * 4f
                expanded[di + 1] = src[si + 1] * 4f
                expanded[di + 2] = src[si + 2] * 4f
            }
        }
        return blur3ch5tap(expanded, dstW, dstH)
    }

    private fun blur1ch5tap(src: FloatArray, w: Int, h: Int): FloatArray {
        val tmp = FloatArray(src.size)
        // Horizontal.
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
        // Vertical.
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

    private fun blur3ch5tap(src: FloatArray, w: Int, h: Int): FloatArray {
        val tmp = FloatArray(src.size)
        // Horizontal.
        for (y in 0 until h) {
            val rowBase = y * w * 3
            for (x in 0 until w) {
                var sr = 0f; var sg = 0f; var sb = 0f
                for (k in -2..2) {
                    val xi = (x + k).coerceIn(0, w - 1)
                    val ti = rowBase + xi * 3
                    val wt = GAUSS_TAPS[k + 2]
                    sr += wt * src[ti]
                    sg += wt * src[ti + 1]
                    sb += wt * src[ti + 2]
                }
                val di = rowBase + x * 3
                tmp[di] = sr; tmp[di + 1] = sg; tmp[di + 2] = sb
            }
        }
        // Vertical.
        val out = FloatArray(src.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var sr = 0f; var sg = 0f; var sb = 0f
                for (k in -2..2) {
                    val yi = (y + k).coerceIn(0, h - 1)
                    val ti = (yi * w + x) * 3
                    val wt = GAUSS_TAPS[k + 2]
                    sr += wt * tmp[ti]
                    sg += wt * tmp[ti + 1]
                    sb += wt * tmp[ti + 2]
                }
                val di = (y * w + x) * 3
                out[di] = sr; out[di + 1] = sg; out[di + 2] = sb
            }
        }
        return out
    }

    /**
     * Decode + size to roughly [maxLongEdge] on the longest edge.
     *
     * Two-step:
     *   1. inSampleSize-decode to the smallest size that is STILL ≥ maxLongEdge — this
     *      gives us efficient JPEG-domain downscaling at native resolution
     *   2. Bitmap.createScaledBitmap to exactly maxLongEdge (preserving aspect)
     *
     * This avoids both the original bug (output way smaller than maxLongEdge because the
     * inSampleSize step was too aggressive) AND the opposite bug (decoding the full 4K
     * frame into memory, which OOMs).
     */
    private fun decodeScaled(jpeg: ByteArray, maxLongEdge: Int): Bitmap {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts)
        val long = max(opts.outWidth, opts.outHeight)

        // Find largest inSampleSize that still leaves us ≥ maxLongEdge on the long edge.
        var sample = 1
        while (long / (sample * 2) >= maxLongEdge) sample *= 2

        val opts2 = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts2)
            ?: throw RuntimeException("Failed to decode JPEG")

        // Now scale down to exactly maxLongEdge if still larger.
        val dlong = max(decoded.width, decoded.height)
        if (dlong <= maxLongEdge) return decoded
        val scale = maxLongEdge.toFloat() / dlong
        val nw = (decoded.width * scale).toInt() and 1.inv()
        val nh = (decoded.height * scale).toInt() and 1.inv()
        val scaled = Bitmap.createScaledBitmap(decoded, nw, nh, true)
        if (scaled !== decoded) decoded.recycle()
        return scaled
    }
}
