package com.fivelidz.compositecamera.pro

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Multi-band focus stacking for close-up + distance-in-focus shots.
 *
 * The use case this is designed for: a subject 20-80 cm from the lens (a flower, a
 * product, food) WITH a background you also want sharp (the room, the landscape).
 * At the Redmi Note 14 5G's f/1.8 main camera, a single shot puts the background
 * far out of focus when you focus close. Stacking multiple focus planes fixes that.
 *
 * Pipeline:
 *
 *   1. Decode all source frames to linear-light RGB (consistent with ExposureFusion).
 *   2. Optionally align adjacent frames in (translation, scale) — this catches the
 *      classic "focus breathing" scale-change that makes lazy focus stacks show
 *      double-edges where the focus transition happens.
 *   3. For each frame, compute a **sharpness map**:
 *      Modified Laplacian (Nayar & Nakagawa 1994) summed over a 7×7 window.
 *      Recommended by Pertuz et al. 2013 ("Analysis of focus measure operators
 *      for shape-from-focus") as the most reliable focus measure for stacking.
 *   4. Per-pixel normalise sharpness across frames → weight maps.
 *   5. Build Gaussian pyramids of the weight maps and Laplacian pyramids of the
 *      source images, blend at each pyramid level, collapse → fused image.
 *      Same blending machinery as ExposureFusion — only the weight functions
 *      differ. This gives seam-free transitions across focus boundaries.
 */
object FocusStack {

    private const val TAG = "FocusStack"

    data class Frame(val jpeg: ByteArray, val focusBucket: Int, val focusDiopters: Float)

    interface Reporter {
        fun progress(p: Float, label: String) {}
    }

    /**
     * Stack the given focus-bracket frames into a single all-in-focus image.
     *
     * @param frames        all frames at the same exposure, varying focus
     * @param maxLongEdge   working resolution cap
     * @param pyramidLevels number of Laplacian-pyramid levels (5 is a good default)
     * @param compensateBreathing  if true, detect & undo per-pair scale changes
     */
    fun stack(
        frames: List<Frame>,
        maxLongEdge: Int = 1800,
        pyramidLevels: Int = 5,
        compensateBreathing: Boolean = true,
        reporter: Reporter = object : Reporter {},
    ): Bitmap {
        require(frames.isNotEmpty()) { "Need ≥ 1 frame" }
        if (frames.size == 1) return BitmapFactory.decodeByteArray(
            frames[0].jpeg, 0, frames[0].jpeg.size)

        reporter.progress(0.02f, "Decoding ${frames.size} focus planes")
        val n = frames.size
        val decoded = frames.map { decodeScaled(it.jpeg, maxLongEdge) }
        val w = decoded[0].width
        val h = decoded[0].height
        Log.i(TAG, "Focus stack: ${w}×${h}, n=$n, levels=$pyramidLevels")

        reporter.progress(0.10f, "Linearising")
        val linear = Array(n) { i -> Pyramid.bitmapToLinearRGB(decoded[i]) }
        decoded.forEach { if (!it.isRecycled) it.recycle() }

        // Sort by focus distance (ascending diopters = near→far in our convention, but
        // we always reference frame 0 anyway, so order doesn't really matter for the
        // fusion math — it does matter for breathing detection because the scale change
        // is monotonic with focus distance, so we walk adjacent pairs).
        // We assume `frames` is already in focusBucket order from the caller.

        if (compensateBreathing && n > 1) {
            reporter.progress(0.15f, "Compensating focus breathing")
            // Compare each frame to its predecessor and apply scale + translation correction.
            // This catches the focus-breathing magnification change and small misalignment.
            for (i in 1 until n) {
                val (dx, dy, scale) = FocusBreathing.estimate(linear[i - 1], linear[i], w, h)
                if (abs(dx) + abs(dy) > 0 || abs(scale - 1f) > 0.001f) {
                    Log.i(TAG, "Focus plane $i correction: dx=$dx dy=$dy scale=$scale")
                    FocusBreathing.applyInPlace(linear[i], w, h, dx, dy, scale)
                }
            }
        }

        // Compute per-frame sharpness maps.
        reporter.progress(0.25f, "Measuring sharpness")
        val sharp = Array(n) { i ->
            computeModifiedLaplacianSum(linear[i], w, h, windowRadius = 3)
        }

        // Box-blur the sharpness maps so weights vary smoothly across boundaries.
        for (i in 0 until n) boxBlur(sharp[i], w, h, radius = 6)

        // Sharpen the weight distribution — raising weights to a power makes the
        // sharpest frame dominate strongly (winner-take-most), instead of getting
        // smoothly averaged with marginally less-sharp frames. Without this, the
        // fused output tends to look softer than the best source frame for any region.
        val winnerSharpness = 4.0f
        for (m in sharp) for (k in 0 until w * h) m[k] = m[k].pow(winnerSharpness)

        // Normalise across frames so weights sum to 1 per pixel.
        normalise(sharp, w, h)

        // Build pyramids progressively, freeing source data as we go (memory-tight).
        reporter.progress(0.40f, "Building pyramids")
        val levels = Pyramid.effectiveLevels(w, h, pyramidLevels)
        val srcP = Array<Array<Pyramid.Level>?>(n) { null }
        val wP   = Array<Array<Pyramid.Level>?>(n) { null }
        for (i in 0 until n) {
            srcP[i] = Pyramid.laplacianPyramid3(linear[i], w, h, levels)
            wP[i]   = Pyramid.gaussianPyramid1(sharp[i], w, h, levels)
            linear[i] = FloatArray(0)
            sharp[i]  = FloatArray(0)
            reporter.progress(0.40f + 0.20f * ((i + 1).toFloat() / n),
                "Building pyramid ${i + 1}/$n")
        }
        System.gc()

        reporter.progress(0.65f, "Blending")
        val outPyr = arrayOfNulls<Pyramid.Level>(levels)
        for (k in 0 until levels) {
            val lw = srcP[0]!![k].w; val lh = srcP[0]!![k].h
            val rgb = FloatArray(lw * lh * 3)
            for (i in 0 until n) {
                val src = srcP[i]!![k].data
                val wmap = wP[i]!![k].data
                var p = 0
                for (px in 0 until lw * lh) {
                    val wt = wmap[px]
                    rgb[p]     += wt * src[p];     p++
                    rgb[p]     += wt * src[p];     p++
                    rgb[p]     += wt * src[p];     p++
                }
                srcP[i]!![k] = Pyramid.Level(FloatArray(0), lw, lh)
                wP[i]!![k]   = Pyramid.Level(FloatArray(0), lw, lh)
            }
            outPyr[k] = Pyramid.Level(rgb, lw, lh)
        }

        reporter.progress(0.85f, "Collapsing pyramid")
        @Suppress("UNCHECKED_CAST")
        val fused = Pyramid.collapse3(outPyr as Array<Pyramid.Level>)

        reporter.progress(0.95f, "Encoding")
        val outBmp = Pyramid.linearRGBToBitmap(fused.data, fused.w, fused.h)
        reporter.progress(1f, "Done")
        return outBmp
    }

    // ---- focus measure: modified-Laplacian sum over a window ----

    /**
     * Modified Laplacian (Nayar & Nakagawa, 1994):
     *   ML(x,y) = |2*I(x,y) - I(x-1,y) - I(x+1,y)| + |2*I(x,y) - I(x,y-1) - I(x,y+1)|
     *
     * Then sum over a square window (radius r → (2r+1)² window). This is the
     * recommended focus measure ("SML" — Sum of Modified Laplacian) — it's
     * robust against noise and reliably peaks at the in-focus depth per pixel.
     */
    private fun computeModifiedLaplacianSum(
        rgb: FloatArray, w: Int, h: Int, windowRadius: Int,
    ): FloatArray {
        // 1. Per-pixel luminance.
        val lum = FloatArray(w * h)
        var p = 0
        for (k in 0 until w * h) {
            val r = rgb[p++]; val g = rgb[p++]; val b = rgb[p++]
            lum[k] = 0.2126f * r + 0.7152f * g + 0.0722f * b
        }

        // 2. Per-pixel modified-Laplacian magnitude.
        val ml = FloatArray(w * h)
        for (y in 1 until h - 1) {
            val row = y * w
            for (x in 1 until w - 1) {
                val c = row + x
                val mx = abs(2f * lum[c] - lum[c - 1] - lum[c + 1])
                val my = abs(2f * lum[c] - lum[c - w] - lum[c + w])
                ml[c] = mx + my
            }
        }

        // 3. Sum over a square window (separable box filter pass).
        return windowSum(ml, w, h, windowRadius)
    }

    /** Separable rolling sum over a (2r+1)×(2r+1) window. */
    private fun windowSum(src: FloatArray, w: Int, h: Int, radius: Int): FloatArray {
        val tmp = FloatArray(src.size)
        // Horizontal.
        for (y in 0 until h) {
            val row = y * w
            var sum = 0f
            for (x in -radius..radius) sum += src[row + x.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                tmp[row + x] = sum
                val xAdd = (x + radius + 1).coerceAtMost(w - 1)
                val xSub = (x - radius).coerceAtLeast(0)
                sum += src[row + xAdd] - src[row + xSub]
            }
        }
        // Vertical.
        val out = FloatArray(src.size)
        for (x in 0 until w) {
            var sum = 0f
            for (y in -radius..radius) sum += tmp[(y.coerceIn(0, h - 1)) * w + x]
            for (y in 0 until h) {
                out[y * w + x] = sum
                val yAdd = (y + radius + 1).coerceAtMost(h - 1)
                val ySub = (y - radius).coerceAtLeast(0)
                sum += tmp[yAdd * w + x] - tmp[ySub * w + x]
            }
        }
        return out
    }

    private fun normalise(maps: Array<FloatArray>, w: Int, h: Int) {
        val total = FloatArray(w * h)
        for (m in maps) for (k in 0 until w * h) total[k] += m[k]
        for (k in 0 until w * h) if (total[k] < 1e-12f) total[k] = 1e-12f
        for (m in maps) for (k in 0 until w * h) m[k] /= total[k]
    }

    private fun boxBlur(src: FloatArray, w: Int, h: Int, radius: Int) {
        if (radius < 1) return
        val tmp = FloatArray(src.size)
        val winSize = (radius * 2 + 1).toFloat()
        for (y in 0 until h) {
            var sum = 0f
            val row = y * w
            for (x in -radius..radius) sum += src[row + x.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                tmp[row + x] = sum / winSize
                val xAdd = (x + radius + 1).coerceAtMost(w - 1)
                val xSub = (x - radius).coerceAtLeast(0)
                sum += src[row + xAdd] - src[row + xSub]
            }
        }
        for (x in 0 until w) {
            var sum = 0f
            for (y in -radius..radius) sum += tmp[(y.coerceIn(0, h - 1)) * w + x]
            for (y in 0 until h) {
                src[y * w + x] = sum / winSize
                val yAdd = (y + radius + 1).coerceAtMost(h - 1)
                val ySub = (y - radius).coerceAtLeast(0)
                sum += tmp[yAdd * w + x] - tmp[ySub * w + x]
            }
        }
    }

    private fun decodeScaled(jpeg: ByteArray, maxLongEdge: Int): Bitmap {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts)
        val long = max(opts.outWidth, opts.outHeight)
        var sample = 1
        while (long / (sample * 2) >= maxLongEdge) sample *= 2
        val opts2 = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts2)
            ?: throw RuntimeException("Failed to decode JPEG")
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
