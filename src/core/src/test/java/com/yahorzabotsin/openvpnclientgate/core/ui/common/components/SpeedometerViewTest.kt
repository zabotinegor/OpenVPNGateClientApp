package com.yahorzabotsin.openvpnclientgate.core.ui.common.components

import com.yahorzabotsin.openvpnclientgate.core.ui.common.components.SpeedometerView.Companion.ARC_SWEEP_DEGREES
import com.yahorzabotsin.openvpnclientgate.core.ui.common.components.SpeedometerView.Companion.DEFAULT_MAX_MBPS
import com.yahorzabotsin.openvpnclientgate.core.ui.common.components.SpeedometerView.Companion.FACE_LOWER_EXTENT_RATIO
import com.yahorzabotsin.openvpnclientgate.core.ui.common.components.SpeedometerView.Companion.GLOW_EXTENT_RATIO
import com.yahorzabotsin.openvpnclientgate.core.ui.common.components.SpeedometerView.Companion.SCALE_STOPS
import com.yahorzabotsin.openvpnclientgate.core.ui.common.components.SpeedometerView.Companion.VERTICAL_EXTENT_RATIO
import com.yahorzabotsin.openvpnclientgate.core.ui.common.components.SpeedometerView.Companion.computeGeometry
import com.yahorzabotsin.openvpnclientgate.core.ui.common.components.SpeedometerView.Companion.formatScaleStop
import com.yahorzabotsin.openvpnclientgate.core.ui.common.components.SpeedometerView.Companion.formatValue
import com.yahorzabotsin.openvpnclientgate.core.ui.common.components.SpeedometerView.Companion.labelHaloRadius
import com.yahorzabotsin.openvpnclientgate.core.ui.common.components.SpeedometerView.Companion.lastActiveStopIndex
import com.yahorzabotsin.openvpnclientgate.core.ui.common.components.SpeedometerView.Companion.resolveMaxMbps
import com.yahorzabotsin.openvpnclientgate.core.ui.common.components.SpeedometerView.Companion.resolveSpeed
import com.yahorzabotsin.openvpnclientgate.core.ui.common.components.SpeedometerView.Companion.shouldShowNeedle
import com.yahorzabotsin.openvpnclientgate.core.ui.common.components.SpeedometerView.Companion.shouldUpdateConnected
import com.yahorzabotsin.openvpnclientgate.core.ui.common.components.SpeedometerView.Companion.sweepForValue
import com.yahorzabotsin.openvpnclientgate.vpn.ConnectionState
import kotlin.math.hypot
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale

/**
 * Unit tests for [SpeedometerView]'s pure scale/format helpers. The view itself needs Android
 * resource resolution, so only the companion seams are exercised here.
 */
class SpeedometerViewTest {

    private val tolerance = 0.001f
    private val segmentSweep = ARC_SWEEP_DEGREES / (SCALE_STOPS.size - 1)

    private lateinit var originalDefaultLocale: Locale

    @Before
    fun saveDefaultLocale() {
        originalDefaultLocale = Locale.getDefault()
    }

    @After
    fun restoreDefaultLocale() {
        Locale.setDefault(originalDefaultLocale)
    }

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

    // --------------- locale independence (regression guard) ---------------

    // formatValue/formatScaleStop must always use a period, never a locale-dependent decimal
    // separator (e.g. a comma in ru-RU/pl-PL/de-DE), even when the JVM/device default locale
    // uses one. Pinning Locale.setDefault() here - unlike expected(), which mirrors
    // Locale.US and would drift together with a reverted implementation - is what makes this
    // test actually fail if the Locale.US fix in formatValue/formatScaleStop is ever reverted
    // back to Locale.getDefault().
    @Test
    fun `formatValue keeps a period decimal separator under a comma-decimal default locale`() {
        Locale.setDefault(Locale.forLanguageTag("ru-RU"))

        val formatted = formatValue(81.74f)

        assertEquals(expected("%.2f", 81.74f), formatted)
        assertFalse("expected a period, not a locale comma: $formatted", formatted.contains(','))
    }

    @Test
    fun `formatScaleStop keeps a period decimal separator under a comma-decimal default locale`() {
        Locale.setDefault(Locale.forLanguageTag("ru-RU"))

        val formatted = formatScaleStop(0.5f)

        assertEquals(expected("%.1f", 0.5f), formatted)
        assertFalse("expected a period, not a locale comma: $formatted", formatted.contains(','))
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
        assertEquals("1000", formatScaleStop(1000f))
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

    // formatValue/formatScaleStop are fixed to Locale.US (not the JVM/device default) so the
    // gauge's decimal separator always matches the rest of the screen - mirror that here rather
    // than getDefault(), or this helper silently drifts from the implementation on any machine
    // whose default locale formats numbers differently.
    private fun expected(pattern: String, value: Any) =
        String.format(Locale.US, pattern, value)

    // --------------- labelHaloRadius ---------------

    @Test
    fun `halo radius covers the text's bounding box diagonal plus padding`() {
        // A 10x6 box has an 11.66-long half-diagonal (hypot(10, 6) / 2); the halo must reach at
        // least that far in every direction, plus the requested padding, to fully clear the
        // glyph's corners rather than just its sides.
        val expected = hypot(10f, 6f) / 2f + 2f
        assertEquals(expected, labelHaloRadius(textWidth = 10f, textHeight = 6f, paddingPx = 2f), tolerance)
    }

    @Test
    fun `halo radius grows with wider labels`() {
        // "1000" is wider than "0", so its halo must be larger even though both share the same
        // font-metrics text height - otherwise a wide label's corners would poke out past the
        // circle meant to backstop it.
        val narrow = labelHaloRadius(textWidth = 8f, textHeight = 20f, paddingPx = 4f)
        val wide = labelHaloRadius(textWidth = 40f, textHeight = 20f, paddingPx = 4f)
        assertTrue("wide=$wide should exceed narrow=$narrow", wide > narrow)
    }

    @Test
    fun `halo radius is zero-safe for an empty label`() {
        assertEquals(3f, labelHaloRadius(textWidth = 0f, textHeight = 0f, paddingPx = 3f), tolerance)
    }

    @Test
    fun `halo radius scales linearly with padding alone`() {
        val noPadding = labelHaloRadius(textWidth = 12f, textHeight = 8f, paddingPx = 0f)
        val withPadding = labelHaloRadius(textWidth = 12f, textHeight = 8f, paddingPx = 5f)
        assertEquals(5f, withPadding - noPadding, tolerance)
    }

    // --------------- shouldShowNeedle ---------------
    // Truth table over all six ConnectionState values (the spec in .sdlc/status.json names each
    // one explicitly): only DISCONNECTED hides the needle.

    @Test
    fun `the needle is hidden while disconnected`() {
        assertFalse(shouldShowNeedle(ConnectionState.DISCONNECTED))
    }

    @Test
    fun `the needle is shown while connecting`() {
        assertTrue(shouldShowNeedle(ConnectionState.CONNECTING))
    }

    @Test
    fun `the needle is shown while connected`() {
        assertTrue(shouldShowNeedle(ConnectionState.CONNECTED))
    }

    @Test
    fun `the needle is shown while pausing`() {
        assertTrue(shouldShowNeedle(ConnectionState.PAUSING))
    }

    @Test
    fun `the needle is shown while paused`() {
        assertTrue(shouldShowNeedle(ConnectionState.PAUSED))
    }

    @Test
    fun `the needle is shown while disconnecting`() {
        assertTrue(shouldShowNeedle(ConnectionState.DISCONNECTING))
    }

    // --------------- shouldUpdateConnected ---------------

    @Test
    fun `a connected-state change from disconnected to connected should update`() {
        assertTrue(shouldUpdateConnected(current = false, next = true))
    }

    @Test
    fun `a connected-state change from connected to disconnected should update`() {
        assertTrue(shouldUpdateConnected(current = true, next = false))
    }

    @Test
    fun `a redundant connected-state call should not update`() {
        assertFalse(shouldUpdateConnected(current = true, next = true))
        assertFalse(shouldUpdateConnected(current = false, next = false))
    }

    // --------------- computeGeometry: vertical glow-overflow reservation ---------------
    //
    // Regression coverage for the bug where onSizeChanged split the glow-overflow allowance
    // (GLOW_EXTENT_RATIO - 1) evenly above and below the arc instead of reserving it fully above,
    // clipping ~2.2% of the radius off the top of the widest halo pass whenever height was the
    // limiting dimension. See computeGeometry's KDoc for the full derivation.

    @Test
    fun `when height is the limiting dimension, the full glow overflow is reserved above the arc with no clipping`() {
        val availableWidth = 2000f
        val availableHeight = 400f
        val geometry = computeGeometry(availableWidth, availableHeight, paddingLeft = 0f, paddingTop = 0f)

        // Height must actually be the binding constraint for this case to be meaningful.
        assertTrue(geometry.outerRadius < availableWidth / (2f * GLOW_EXTENT_RATIO) + tolerance)

        val topOfGlow = geometry.centerY - geometry.outerRadius * GLOW_EXTENT_RATIO
        // Before the fix this was negative by (GLOW_EXTENT_RATIO - 1) / 2 * outerRadius - roughly
        // 2.2% of the radius - i.e. the glow clipped above the view's top edge.
        assertEquals(0f, topOfGlow, tolerance)

        val bottomOfContent = geometry.centerY + geometry.outerRadius * (VERTICAL_EXTENT_RATIO - 1f)
        assertEquals(availableHeight, bottomOfContent, tolerance)
    }

    @Test
    fun `when width is the limiting dimension, leftover vertical slack is split evenly above and below the full glow-reserved content`() {
        val availableWidth = 200f
        val availableHeight = 2000f
        val geometry = computeGeometry(availableWidth, availableHeight, paddingLeft = 0f, paddingTop = 0f)

        // Width must actually be the binding constraint for this case to be meaningful.
        val heightLimitedRadius = availableHeight / (VERTICAL_EXTENT_RATIO + (GLOW_EXTENT_RATIO - 1f))
        assertTrue(geometry.outerRadius < heightLimitedRadius - tolerance)

        val topSlack = geometry.centerY - geometry.outerRadius * GLOW_EXTENT_RATIO
        val bottomSlack =
            availableHeight - (geometry.centerY + geometry.outerRadius * (VERTICAL_EXTENT_RATIO - 1f))
        assertEquals(topSlack, bottomSlack, tolerance)
        // No clipping in this direction either - there is slack to spare.
        assertTrue(topSlack >= -tolerance)
    }

    @Test
    fun `when height is the limiting dimension with no bottom padding, the full circular face is reserved with no clipping`() {
        // Regression coverage for the bug where the lower extent reserved only the unit
        // caption's ~0.84 * outerRadius, less than the circular face onDraw actually paints
        // (FACE_LOWER_EXTENT_RATIO = 1 - ARC_WIDTH_RATIO = 0.875 * outerRadius), clipping the
        // bottom of the face into a flat edge whenever height was the limiting dimension and
        // there was no bottom padding.
        val availableWidth = 2000f
        val availableHeight = 400f
        val geometry = computeGeometry(availableWidth, availableHeight, paddingLeft = 0f, paddingTop = 0f)

        // Height must actually be the binding constraint for this case to be meaningful.
        assertTrue(geometry.outerRadius < availableWidth / (2f * GLOW_EXTENT_RATIO) + tolerance)

        val bottomOfFace = geometry.centerY + geometry.outerRadius * FACE_LOWER_EXTENT_RATIO
        // Before the fix this exceeded the view's bottom edge - the face circle painted past
        // the space computeGeometry reserved, clipping into a flat edge.
        assertTrue(bottomOfFace <= availableHeight + tolerance)

        val bottomOfContent = geometry.centerY + geometry.outerRadius * (VERTICAL_EXTENT_RATIO - 1f)
        assertEquals(availableHeight, bottomOfContent, tolerance)
        // The content's reserved lower extent must cover at least the face circle's own extent.
        assertTrue(bottomOfContent >= bottomOfFace - tolerance)
    }

    @Test
    fun `computeGeometry offsets the center by the given padding`() {
        val paddingLeft = 12f
        val paddingTop = 34f
        val geometry = computeGeometry(
            availableWidth = 500f,
            availableHeight = 500f,
            paddingLeft = paddingLeft,
            paddingTop = paddingTop,
        )

        val unpadded = computeGeometry(
            availableWidth = 500f,
            availableHeight = 500f,
            paddingLeft = 0f,
            paddingTop = 0f,
        )

        assertEquals(unpadded.centerX + paddingLeft, geometry.centerX, tolerance)
        assertEquals(unpadded.centerY + paddingTop, geometry.centerY, tolerance)
        assertEquals(unpadded.outerRadius, geometry.outerRadius, tolerance)
    }
}
