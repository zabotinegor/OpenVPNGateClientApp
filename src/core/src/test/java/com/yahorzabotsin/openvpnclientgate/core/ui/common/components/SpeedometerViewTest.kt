package com.yahorzabotsin.openvpnclientgate.core.ui.common.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for US-21: Speedometer gauge redesign.
 *
 * SpeedometerView itself cannot be constructed in this module's JVM unit tests: core unit tests
 * run Robolectric without Android resource resolution, so any `context.getColor(R.color.*)` /
 * `@ColorRes` lookup throws `Resources$NotFoundException` (a pre-existing, documented constraint
 * - see the "resolveThemedTitleColor" note in FavoriteActionDialogTest.kt). SpeedometerView's
 * constructor resolves several `R.color.speedometer_*` values, so it hits that constraint too.
 *
 * Following the same seam-extraction pattern already used by
 * `ConnectionControlsView.resolveFocusTarget` / `FavoriteActionDialog.resolvePresentation`, the
 * pure decision logic behind AC4/AC5 was pulled into `internal` companion functions on
 * SpeedometerView (formatMegabits, resolveMaxMbps, resolveSpeedTarget, shouldAnimateTo) that need
 * no Context/resources and are exercised directly here - no Robolectric runner required.
 *
 * What is NOT covered by these tests (gradient shader wiring, real ValueAnimator reuse on a live
 * View, resolved accent/arc colors, crash-free rendering at TV's fixed 400dp size) cannot be
 * unit-tested under this module's current Robolectric setup; per the story's Definition of Done
 * those are covered by manual QA against test scenarios T1-T7.
 */
class SpeedometerViewTest {

    // ---- AC5: formatMegabits formatting must be unchanged (T8) ----

    @Test
    fun formatMegabits_matchesExistingScaleFormatting_forT8Values() {
        assertEquals("0.00", SpeedometerView.formatMegabits(0f))
        assertEquals("9.99", SpeedometerView.formatMegabits(9.99f))
        assertEquals("10.0", SpeedometerView.formatMegabits(10f))
        // 99.99 rounds up to 100.0 at one-decimal precision - pre-existing behavior, locked in.
        assertEquals("100.0", SpeedometerView.formatMegabits(99.99f))
        assertEquals("100", SpeedometerView.formatMegabits(100f))
    }

    @Test
    fun formatMegabits_negativeValuesClampToZeroFloor() {
        assertEquals("0.00", SpeedometerView.formatMegabits(-5f))
    }

    @Test
    fun formatMegabits_midRangeValues() {
        assertEquals("45.5", SpeedometerView.formatMegabits(45.5f))
        assertEquals("80.2", SpeedometerView.formatMegabits(80.2f))
    }

    // ---- AC5: setMaxMbps behavior must be unchanged ----

    @Test
    fun resolveMaxMbps_acceptsPositiveValue() {
        assertEquals(50f, SpeedometerView.resolveMaxMbps(50f), 0.0001f)
    }

    @Test
    fun resolveMaxMbps_nonPositiveValueFallsBackTo1000() {
        // Fix-cycle (literal reference replica): default max scale raised from 100 to 1000 Mb/s
        // to mirror the speedtest.net reference's tick set.
        assertEquals(1000f, SpeedometerView.resolveMaxMbps(0f), 0.0001f)
        assertEquals(1000f, SpeedometerView.resolveMaxMbps(-10f), 0.0001f)
    }

    // ---- setSpeedMbps: input validation preserved ----

    @Test
    fun resolveSpeedTarget_validPositiveValuePassesThrough() {
        assertEquals(20f, SpeedometerView.resolveSpeedTarget(20.0), 0.0001f)
        assertEquals(80.2f, SpeedometerView.resolveSpeedTarget(80.2), 0.0001f)
    }

    @Test
    fun resolveSpeedTarget_negativeValueClampsToZero() {
        assertEquals(0f, SpeedometerView.resolveSpeedTarget(-5.0), 0.0001f)
    }

    @Test
    fun resolveSpeedTarget_nanClampsToZero() {
        assertEquals(0f, SpeedometerView.resolveSpeedTarget(Double.NaN), 0.0001f)
    }

    @Test
    fun resolveSpeedTarget_infiniteClampsToZero() {
        assertEquals(0f, SpeedometerView.resolveSpeedTarget(Double.POSITIVE_INFINITY), 0.0001f)
        assertEquals(0f, SpeedometerView.resolveSpeedTarget(Double.NEGATIVE_INFINITY), 0.0001f)
    }

    // ---- AC4: animation should-restart decision (T7 - no stacking on rapid duplicate updates) ----

    @Test
    fun shouldAnimateTo_trueWhenTargetMeaningfullyChanges() {
        assertTrue(SpeedometerView.shouldAnimateTo(45.5f, 0f))
        assertTrue(SpeedometerView.shouldAnimateTo(80.2f, 45.5f))
        assertTrue(SpeedometerView.shouldAnimateTo(10f, 80f))
    }

    @Test
    fun shouldAnimateTo_falseForSameOrNegligiblyDifferentValue() {
        assertFalse(SpeedometerView.shouldAnimateTo(45f, 45f))
        // Within the existing 0.01 no-op threshold - must not restart/stack an animation.
        assertFalse(SpeedometerView.shouldAnimateTo(45.004f, 45f))
    }

    @Test
    fun shouldAnimateTo_trueJustOutsideThreshold() {
        assertTrue(SpeedometerView.shouldAnimateTo(45.02f, 45f))
    }

    // ---- Risk mitigation: bounded animation duration (300-500ms) ----

    @Test
    fun speedAnimationDuration_isWithinRiskMitigationBounds() {
        assertTrue(
            "Animation duration must be within the 300-500ms risk-mitigation bound",
            SpeedometerView.SPEED_ANIMATION_DURATION_MS in 300L..500L
        )
    }

    // ---- Fix-cycle: angleForValue - shared angle math for tick placement, the needle indicator
    // and the gradient progress-arc sweep (extracted from the former drawTicksAndLabels-local
    // `angleFor` closure) ----
    //
    // Fix-cycle (literal reference replica): the mapping is now a two-segment piecewise curve,
    // not a single linear ratio. Values from 0 to 10% of maxMbps (the "low tier" - 0-100 Mb/s at
    // the default 1000 Mb/s scale, where real-world speeds live) map onto the first
    // ANGLE_LOW_TIER_SWEEP_FRACTION (45%) of the arc sweep; values above that breakpoint (the
    // "high tier" - 100-1000 Mb/s by default) map onto the remaining 55%. This makes typical
    // speeds visually fill much more of the arc than a strict linear 0-1000 mapping would.

    @Test
    fun angleForValue_atZero_returnsArcStartAngle() {
        assertEquals(
            SpeedometerView.ARC_START_ANGLE,
            SpeedometerView.angleForValue(0f, 1000f),
            0.0001f
        )
    }

    @Test
    fun angleForValue_atMax_returnsArcStartPlusSweep() {
        assertEquals(
            SpeedometerView.ARC_START_ANGLE + SpeedometerView.ARC_SWEEP_DEGREES,
            SpeedometerView.angleForValue(1000f, 1000f),
            0.0001f
        )
    }

    @Test
    fun angleForValue_atLowTierBreakpoint_returnsLowTierSweepFraction() {
        // 100 Mb/s is exactly the low/high tier breakpoint (10% of the default 1000 Mb/s max) -
        // it must land exactly at ANGLE_LOW_TIER_SWEEP_FRACTION (45%) through the sweep.
        assertEquals(
            SpeedometerView.ARC_START_ANGLE +
                SpeedometerView.ARC_SWEEP_DEGREES * SpeedometerView.ANGLE_LOW_TIER_SWEEP_FRACTION,
            SpeedometerView.angleForValue(100f, 1000f),
            0.01f
        )
    }

    @Test
    fun angleForValue_belowBreakpoint_usesLowTierFraction() {
        // 50 Mb/s is halfway through the low tier (0-100), so it must land at half of the low
        // tier's 45% sweep share - 22.5% through the total sweep.
        val expectedRatio = 0.5f * SpeedometerView.ANGLE_LOW_TIER_SWEEP_FRACTION
        assertEquals(
            SpeedometerView.ARC_START_ANGLE + SpeedometerView.ARC_SWEEP_DEGREES * expectedRatio,
            SpeedometerView.angleForValue(50f, 1000f),
            0.01f
        )
    }

    @Test
    fun angleForValue_aboveBreakpoint_usesRemainingHighTierFraction() {
        // 500 Mb/s is 400 of the remaining 900 Mb/s (100..1000) high-tier span into the high tier
        // (~44.4%), so it must land at 45% + 44.4%*55% (~69.4%) through the total sweep.
        val highTierRatio = (500f - 100f) / (1000f - 100f)
        val expectedRatio = SpeedometerView.ANGLE_LOW_TIER_SWEEP_FRACTION +
            highTierRatio * (1f - SpeedometerView.ANGLE_LOW_TIER_SWEEP_FRACTION)
        assertEquals(
            SpeedometerView.ARC_START_ANGLE + SpeedometerView.ARC_SWEEP_DEGREES * expectedRatio,
            SpeedometerView.angleForValue(500f, 1000f),
            0.01f
        )
    }

    @Test
    fun angleForValue_scalesWithNonDefaultMax() {
        // The breakpoint is a fraction (10%) of maxMbps, not a fixed 100 Mb/s literal - 50 out of
        // a 500 max is the same low-tier-breakpoint ratio as 100 out of the default 1000 max.
        assertEquals(
            SpeedometerView.angleForValue(100f, 1000f),
            SpeedometerView.angleForValue(50f, 500f),
            0.01f
        )
    }

    @Test
    fun angleForValue_clampsAboveMaxToArcEndAngle() {
        assertEquals(
            SpeedometerView.ARC_START_ANGLE + SpeedometerView.ARC_SWEEP_DEGREES,
            SpeedometerView.angleForValue(1500f, 1000f),
            0.0001f
        )
    }

    @Test
    fun angleForValue_clampsNegativeValueToArcStartAngle() {
        assertEquals(
            SpeedometerView.ARC_START_ANGLE,
            SpeedometerView.angleForValue(-10f, 1000f),
            0.0001f
        )
    }

    @Test
    fun angleForValue_nonPositiveMaxFallsBackToDefault1000Scale() {
        // maxMbps<=0 resolves through the same 1000 Mb/s default as resolveMaxMbps, so a
        // breakpoint-scale value still produces the same angle as the explicit-1000-max case
        // instead of dividing by zero/NaN.
        assertEquals(
            SpeedometerView.angleForValue(100f, 1000f),
            SpeedometerView.angleForValue(100f, 0f),
            0.0001f
        )
    }

    // ---- Fix-cycle (literal reference replica): TICK_VALUES - fixed tick set matching the
    // reference gauge exactly, replacing the previous evenly-spaced 0/25/50/75/100 series ----

    @Test
    fun tickValues_matchesReferenceGaugeExactly() {
        assertEquals(
            listOf(0f, 5f, 10f, 50f, 100f, 250f, 500f, 750f, 1000f),
            SpeedometerView.TICK_VALUES.toList()
        )
    }

    // ---- Fix-cycle (true semicircle REDO): the arc must be a true top-half semicircle - both
    // endpoints level with the circle's vertical center - not dipping below it as the prior
    // 175/190deg pair did. ----

    @Test
    fun arcGeometry_isExactly180DegreeSemicircle() {
        assertEquals(180f, SpeedometerView.ARC_START_ANGLE, 0.0001f)
        assertEquals(180f, SpeedometerView.ARC_SWEEP_DEGREES, 0.0001f)
    }

    @Test
    fun arcGeometry_coversExactlyHalfTheCircle() {
        // A true semicircle's drawn arc and its undrawn gap must be equal halves of the circle.
        val gap = 360f - SpeedometerView.ARC_SWEEP_DEGREES
        assertEquals(SpeedometerView.ARC_SWEEP_DEGREES, gap, 0.0001f)
    }

    @Test
    fun arcGeometry_endpointsAreLevelWithVerticalCenter() {
        // Android drawArc: 0deg = 3 o'clock/East, clockwise. An endpoint is level with the
        // circle's vertical center exactly when sin(angle) == 0 (i.e. angle is 180 or 360/0deg).
        val startSin = kotlin.math.sin(Math.toRadians(SpeedometerView.ARC_START_ANGLE.toDouble()))
        val endAngle = SpeedometerView.ARC_START_ANGLE + SpeedometerView.ARC_SWEEP_DEGREES
        val endSin = kotlin.math.sin(Math.toRadians(endAngle.toDouble()))
        assertEquals(0.0, startSin, 0.0001)
        assertEquals(0.0, endSin, 0.0001)
    }

    @Test
    fun arcGeometry_apexIsAtTopOfCircle() {
        // The arc's midpoint (start + sweep/2) must point straight up (canvas angle 270deg =
        // 12 o'clock/North), so the semicircle bulges upward, not downward.
        val arcCenterAngle = SpeedometerView.ARC_START_ANGLE + SpeedometerView.ARC_SWEEP_DEGREES / 2f
        assertEquals(270f, arcCenterAngle, 0.0001f)
    }

    // ---- Fix-cycle (true semicircle REDO): VALUE_LOWER_AREA_CENTER_RATIO positions the value
    // text within the newly-opened lower half (between centerY and the bottom of the view) ----

    @Test
    fun valueLowerAreaCenterRatio_isWithinTheOpenLowerHalf() {
        assertTrue(
            "Ratio must be strictly between 0 (centerY) and 1 (bottom of view) to sit within " +
                "the open lower half",
            SpeedometerView.VALUE_LOWER_AREA_CENTER_RATIO > 0f &&
                SpeedometerView.VALUE_LOWER_AREA_CENTER_RATIO < 1f
        )
    }

    // ---- Fix-cycle (spacing REDO): tickStartRadius/tickEndRadius/labelRadius - radial
    // breathing-room math extracted from drawTicksAndLabels ----

    @Test
    fun tickStartRadius_isInsideTheArcTrack() {
        // Must be smaller than the raw track radius (i.e. inset from the arc), so ticks never
        // draw on top of/outside the arc stroke.
        val radius = 100f
        val arcWidth = 10f
        assertTrue(SpeedometerView.tickStartRadius(radius, arcWidth) < radius)
    }

    @Test
    fun tickEndRadius_isSmallerThanTickStartRadius() {
        val radius = 100f
        val arcWidth = 10f
        val start = SpeedometerView.tickStartRadius(radius, arcWidth)
        val end = SpeedometerView.tickEndRadius(radius, arcWidth)
        assertTrue("Tick end radius must sit further inward than tick start radius", end < start)
    }

    @Test
    fun labelRadius_hasClearGapFromTickEndRadius() {
        val radius = 100f
        val arcWidth = 10f
        val end = SpeedometerView.tickEndRadius(radius, arcWidth)
        val label = SpeedometerView.labelRadius(radius, arcWidth)
        // Fix-cycle requirement: labels must have breathing room from the tick marks, not sit
        // immediately adjacent to them.
        assertTrue("Label radius must be measurably further inward than the tick end", end - label >= arcWidth * 0.5f)
    }

    @Test
    fun tickAndLabelRadii_scaleProportionallyWithArcWidth() {
        val radius = 100f
        val gapAtSmallWidth = SpeedometerView.tickStartRadius(radius, 5f) -
            SpeedometerView.labelRadius(radius, 5f)
        val gapAtLargeWidth = SpeedometerView.tickStartRadius(radius, 20f) -
            SpeedometerView.labelRadius(radius, 20f)
        assertTrue(
            "Arc-to-label spacing must grow with arcWidth, not stay a fixed pixel amount",
            gapAtLargeWidth > gapAtSmallWidth
        )
    }

    // ---- Fix-cycle (needle REDO): computeNeedlePoints - tapered wedge polygon geometry,
    // wide at the outer edge and narrowing to a point near the track's inner edge ----

    @Test
    fun computeNeedlePoints_tipSitsCloserToCenterThanBase() {
        val radius = 100f
        val arcWidth = 10f
        val points = SpeedometerView.computeNeedlePoints(
            cx = 0f, cy = 0f, radius = radius, arcWidth = arcWidth, angleDegrees = 0f
        )
        // At angle 0 (pointing along +x), the tip's x must be smaller than the base's x - the
        // wedge narrows toward the center (smaller radius) and widens toward the outer edge.
        assertTrue(points.tipX < points.baseLeftX)
        assertTrue(points.tipX < points.baseRightX)
    }

    @Test
    fun computeNeedlePoints_baseIsWiderThanASinglePoint() {
        val points = SpeedometerView.computeNeedlePoints(
            cx = 0f, cy = 0f, radius = 100f, arcWidth = 10f, angleDegrees = 0f
        )
        // Base-left and base-right must be distinct points (the wedge has real width at its
        // outer edge), unlike the prior uniform-width line which had no perpendicular flare.
        assertTrue(
            "Base-left and base-right must differ - the needle must have width, not be a single line",
            kotlin.math.abs(points.baseLeftY - points.baseRightY) > 0.01f
        )
    }

    @Test
    fun computeNeedlePoints_baseExtendsPastTheArcOuterEdge() {
        val radius = 100f
        val arcWidth = 10f
        val arcOuterEdge = radius + arcWidth / 2f
        val points = SpeedometerView.computeNeedlePoints(
            cx = 0f, cy = 0f, radius = radius, arcWidth = arcWidth, angleDegrees = 0f
        )
        val baseCenterX = (points.baseLeftX + points.baseRightX) / 2f
        assertTrue(
            "Needle base must extend just past the arc's outer edge",
            baseCenterX > arcOuterEdge
        )
    }

    @Test
    fun computeNeedlePoints_tipDoesNotOriginateAtExactCenter() {
        val radius = 100f
        val arcWidth = 10f
        val points = SpeedometerView.computeNeedlePoints(
            cx = 0f, cy = 0f, radius = radius, arcWidth = arcWidth, angleDegrees = 0f
        )
        // The tip must sit partway out toward the track's inner radius (radius - arcWidth/2),
        // not at the exact center of the view (distance 0 from cx/cy).
        assertTrue(points.tipX > 0f)
    }

    @Test
    fun computeNeedlePoints_scalesWithArcWidth() {
        val radius = 100f
        val smallWedge = SpeedometerView.computeNeedlePoints(
            cx = 0f, cy = 0f, radius = radius, arcWidth = 5f, angleDegrees = 0f
        )
        val largeWedge = SpeedometerView.computeNeedlePoints(
            cx = 0f, cy = 0f, radius = radius, arcWidth = 20f, angleDegrees = 0f
        )
        val smallHalfWidth = kotlin.math.abs(smallWedge.baseLeftY - smallWedge.baseRightY) / 2f
        val largeHalfWidth = kotlin.math.abs(largeWedge.baseLeftY - largeWedge.baseRightY) / 2f
        assertTrue(
            "Needle base width must scale with arcWidth, not stay a fixed pixel amount",
            largeHalfWidth > smallHalfWidth
        )
    }
}
