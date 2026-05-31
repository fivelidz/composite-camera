package com.fivelidz.compositecamera.pro

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * Builds the list of (exposureNs, iso, focusDiopters) capture requests for a composite shot.
 *
 *  - Exposure bracket: evenly spaced EV steps centred on the metered exposure.
 *    EV step n means multiply exposure time by 2^n (we hold ISO fixed at the metered ISO,
 *    so each step doubles or halves shutter speed → cleanest brackets).
 *  - Focus bracket: linear sweep in DIOPTERS from near (minFocusDistance) to far (0 = infinity).
 *    Diopters are perceptually linear w.r.t. depth-of-field overlap, so this is the right axis.
 *
 * The full set is the Cartesian product: exposures × focuses, all captured in one burst.
 */
data class CaptureStep(
    val exposureNs: Long,
    val iso: Int,
    val focusDiopters: Float,
    val exposureBucket: Int,   // 0..exposureCount-1
    val focusBucket: Int,      // 0..focusCount-1
)

object BracketPlanner {

    /**
     * @param meteredExposureNs Auto-metered exposure time before the manual sweep starts.
     * @param meteredIso        Auto-metered ISO held fixed across the bracket.
     * @param exposureSteps     Total brackets, e.g. 3 → -2EV / 0 / +2EV ; 5 → -2/-1/0/+1/+2.
     * @param evRangeStops      Half-range. e.g. 2.0 means furthest brackets are ±2 EV from metered.
     * @param focusSteps        Number of focus planes. 3 is a good default for rooms.
     * @param minFocusDiopters  From CameraCaps.minFocusDistanceDiopters (largest = closest).
     * @param nearFocusFraction Fraction of [minFocusDiopters] to use as the closest focus plane.
     *   - 1.0 = use the lens's absolute minimum focus distance (≈ 7-10 cm on the Redmi Note 14 5G).
     *   - 0.5 = closest plane is at twice that distance (≈ 14-20 cm), more comfortable for table-top.
     *   - 0.0 = focus all planes at infinity (essentially disables focus bracketing).
     *   Default 1.0 (full sweep) gives best near-subject coverage; for the "close-up + distance"
     *   preset this maximises depth coverage from very-close to infinity.
     */
    fun plan(
        meteredExposureNs: Long,
        meteredIso: Int,
        exposureSteps: Int = 3,
        evRangeStops: Float = 2f,
        focusSteps: Int = 3,
        minFocusDiopters: Float,
        clampExposureRangeNs: android.util.Range<Long>,
        nearFocusFraction: Float = 1.0f,
    ): List<CaptureStep> {
        val exposures = mutableListOf<Long>()
        if (exposureSteps == 1) {
            exposures += meteredExposureNs
        } else {
            for (i in 0 until exposureSteps) {
                val t = if (exposureSteps == 1) 0f else i.toFloat() / (exposureSteps - 1)  // 0..1
                val ev = -evRangeStops + t * (2 * evRangeStops)                            // -range..+range
                val multiplier = 2.0.pow(ev.toDouble())
                val ns = (meteredExposureNs * multiplier).roundToLong()
                    .coerceIn(clampExposureRangeNs.lower, clampExposureRangeNs.upper)
                exposures += ns
            }
        }

        val focuses = mutableListOf<Float>()
        if (focusSteps == 1) {
            focuses += 0f
        } else {
            // Walk from "far" (0 dpt = infinity) to "near" (nearFocusFraction × minFocusDiopters),
            // inclusive. We deliberately START at infinity because the lens motor settles fastest
            // there, so the first capture isn't delayed by AF mechanics. Spacing is linear in
            // diopters, which is the perceptually-correct axis for depth-of-field overlap.
            val nearMax = (nearFocusFraction.coerceIn(0f, 1f)) * minFocusDiopters
            for (i in 0 until focusSteps) {
                val t = i.toFloat() / (focusSteps - 1)
                focuses += t * nearMax
            }
        }

        val steps = mutableListOf<CaptureStep>()
        // Outer loop = focus (the lens motor is the slowest thing — minimise its movements).
        // Inner loop = exposure.
        for ((fIdx, dpt) in focuses.withIndex()) {
            for ((eIdx, ns) in exposures.withIndex()) {
                steps += CaptureStep(
                    exposureNs = ns,
                    iso = meteredIso,
                    focusDiopters = dpt,
                    exposureBucket = eIdx,
                    focusBucket = fIdx,
                )
            }
        }
        return steps
    }
}
