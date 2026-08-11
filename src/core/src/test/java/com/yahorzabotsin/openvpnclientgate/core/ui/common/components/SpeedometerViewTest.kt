package com.yahorzabotsin.openvpnclientgate.core.ui.common.components

import com.yahorzabotsin.openvpnclientgate.core.ui.common.components.SpeedometerView.Companion.ARC_SWEEP_DEGREES
import com.yahorzabotsin.openvpnclientgate.core.ui.common.components.SpeedometerView.Companion.DEFAULT_MAX_MBPS
import com.yahorzabotsin.openvpnclientgate.core.ui.common.components.SpeedometerView.Companion.SCALE_STOPS
import com.yahorzabotsin.openvpnclientgate.core.ui.common.components.SpeedometerView.Companion.formatScaleStop
import com.yahorzabotsin.openvpnclientgate.core.ui.common.components.SpeedometerView.Companion.formatValue
import com.yahorzabotsin.openvpnclientgate.core.ui.common.components.SpeedometerView.Companion.lastActiveStopIndex
import com.yahorzabotsin.openvpnclientgate.core.ui.common.components.SpeedometerView.Companion.resolveMaxMbps
import com.yahorzabotsin.openvpnclientgate.core.ui.common.components.SpeedometerView.Companion.resolveSpeed
import com.yahorzabotsin.openvpnclientgate.core.ui.common.components.SpeedometerView.Companion.sweepForValue
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/**
 * Unit tests for [SpeedometerView]'s pure scale/format helpers. The view itself needs Android
 * resource resolution, so only the companion seams are exercised here.
 */
class SpeedometerViewTest {

    private val tolerance = 0.001f
    private val segmentSweep = ARC_SWEEP_DEGREES / (SCALE_STOPS.size - 1)

    // --------------- sweepForValue: scale stops ---------------

    @Test
    fun `zero fills nothing`() {
        assertEquals(0f, sweepForValue(0f, DEFAULT_MAX_MBPS), tolerance)
    }

    @Test
    fun `maximum fills the whole arc`() {
        assertEquals(ARC_SWEEP_DEGREES, sweepForValue(1000f, DEFAULT_MAX_MBPS), tolerance)
    }

    @Test
    fun `each scale stop lands on its own equal share of the arc`() {
        SCALE_STOPS.forEachIndexed { index, stop ->
            assertEquals(
                "stop $stop",
                segmentSweep * index,
                sweepForValue(stop, DEFAULT_MAX_MBPS),
                tolerance,
            )
        }
    }

    @Test
    fun `scale midpoint of the dial sits at the top of the arc`() {
        // 100 is the 5th of 9 stops, so it must fill exactly half the sweep.
        assertEquals(ARC_SWEEP_DEGREES / 2f, sweepForValue(100f, DEFAULT_MAX_MBPS), tolerance)
    }

    // --------------- sweepForValue: interpolation ---------------

    @Test
    fun `value between two stops interpolates linearly within that segment`() {
        // 75 is halfway between the 50 and 100 stops (segment index 3).
        assertEquals(segmentSweep * 3.5f, sweepForValue(75f, DEFAULT_MAX_MBPS), tolerance)
    }

    @Test
    fun `low speeds still get a large share of the arc`() {
        // 10 Mbps of a 1000 Mbps dial is 1 percent of the range but a quarter of the arc.
        assertEquals(segmentSweep * 2f, sweepForValue(10f, DEFAULT_MAX_MBPS), tolerance)
    }

    // --------------- sweepForValue: clamping ---------------

    @Test
    fun `negative values clamp to the arc start`() {
        assertEquals(0f, sweepForValue(-42f, DEFAULT_MAX_MBPS), tolerance)
    }

    @Test
    fun `values above the maximum clamp to the arc end`() {
        assertEquals(ARC_SWEEP_DEGREES, sweepForValue(9999f, DEFAULT_MAX_MBPS), tolerance)
    }

    // --------------- sweepForValue: custom maximum ---------------

    @Test
    fun `custom maximum rescales the scale proportionally`() {
        // With a 100 Mbps dial the 100-stop scales down to 10, so 10 must sit at half the sweep.
        assertEquals(ARC_SWEEP_DEGREES / 2f, sweepForValue(10f, 100f), tolerance)
    }

    @Test
    fun `invalid maximum falls back to the default dial`() {
        assertEquals(
            sweepForValue(100f, DEFAULT_MAX_MBPS),
            sweepForValue(100f, 0f),
            tolerance,
        )
    }

    // --------------- lastActiveStopIndex ---------------

    @Test
    fun `an idle dial lights only the zero stop`() {
        // The needle rests exactly on 0, so nothing above it is lit yet.
        assertEquals(0, lastActiveStopIndex(sweepForValue(0f, DEFAULT_MAX_MBPS)))
    }

    @Test
    fun `the smallest reading above zero already lights the next stop`() {
        assertEquals(1, lastActiveStopIndex(sweepForValue(0.4f, DEFAULT_MAX_MBPS)))
    }

    @Test
    fun `a reading between two stops lights the stop it is heading towards`() {
        // 81.74 sits between the 50 and 100 stops, so 100 (index 4) is the last bright one.
        assertEquals(4, lastActiveStopIndex(sweepForValue(81.74f, DEFAULT_MAX_MBPS)))
    }

    @Test
    fun `a reading exactly on a stop lights that stop but not the next`() {
        assertEquals(4, lastActiveStopIndex(sweepForValue(100f, DEFAULT_MAX_MBPS)))
    }

    @Test
    fun `a full dial lights every stop`() {
        assertEquals(
            SCALE_STOPS.size - 1,
            lastActiveStopIndex(sweepForValue(1000f, DEFAULT_MAX_MBPS)),
        )
    }

    @Test
    fun `the last active index never runs past the scale`() {
        assertEquals(SCALE_STOPS.size - 1, lastActiveStopIndex(ARC_SWEEP_DEGREES * 2f))
    }

    // --------------- resolveMaxMbps / resolveSpeed ---------------

    @Test
    fun `resolveMaxMbps keeps a positive maximum`() {
        assertEquals(250f, resolveMaxMbps(250f), tolerance)
    }

    @Test
    fun `resolveMaxMbps rejects non-positive and non-finite maximums`() {
        assertEquals(DEFAULT_MAX_MBPS, resolveMaxMbps(0f), tolerance)
        assertEquals(DEFAULT_MAX_MBPS, resolveMaxMbps(-1f), tolerance)
        assertEquals(DEFAULT_MAX_MBPS, resolveMaxMbps(Float.NaN), tolerance)
        assertEquals(DEFAULT_MAX_MBPS, resolveMaxMbps(Float.POSITIVE_INFINITY), tolerance)
    }

    @Test
    fun `resolveSpeed keeps finite non-negative readings`() {
        assertEquals(81.74f, resolveSpeed(81.74), tolerance)
        assertEquals(0f, resolveSpeed(0.0), tolerance)
    }

    @Test
    fun `resolveSpeed zeroes negative and non-finite readings`() {
        assertEquals(0f, resolveSpeed(-1.0), tolerance)
        assertEquals(0f, resolveSpeed(Double.NaN), tolerance)
        assertEquals(0f, resolveSpeed(Double.POSITIVE_INFINITY), tolerance)
    }

    // --------------- formatValue ---------------

    @Test
    fun `values below 100 keep two decimals`() {
        assertEquals(expected("%.2f", 81.74f), formatValue(81.74f))
        assertEquals(expected("%.2f", 0f), formatValue(0f))
    }

    @Test
    fun `values in the hundreds drop to one decimal`() {
        assertEquals(expected("%.1f", 250.55f), formatValue(250.55f))
    }

    @Test
    fun `values of a thousand and up drop decimals entirely`() {
        assertEquals(expected("%.0f", 1000f), formatValue(1000f))
    }

    // --------------- formatScaleStop ---------------

    @Test
    fun `whole scale stops render as integers`() {
        assertEquals(expected("%d", 0), formatScaleStop(0f))
        assertEquals(expected("%d", 5), formatScaleStop(5f))
        assertEquals(expected("%d", 1000), formatScaleStop(1000f))
    }

    @Test
    fun `scale stops carry no grouping separator`() {
        // "%d" must not turn the top of the scale into "1,000" / "1 000".
        assertEquals(4, formatScaleStop(1000f).length)
    }

    @Test
    fun `fractional stops of a compressed scale keep one decimal`() {
        // A 100 Mbps dial scales the 5-stop down to 0.5.
        assertEquals(expected("%.1f", 0.5f), formatScaleStop(0.5f))
    }

    @Test
    fun `stops of ten and above are rounded to integers`() {
        assertEquals("25", formatScaleStop(25.4f))
    }

    private fun expected(pattern: String, value: Any) =
        String.format(Locale.getDefault(), pattern, value)
}
