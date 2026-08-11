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
    fun resolveMaxMbps_nonPositiveValueFallsBackTo100() {
        assertEquals(100f, SpeedometerView.resolveMaxMbps(0f), 0.0001f)
        assertEquals(100f, SpeedometerView.resolveMaxMbps(-10f), 0.0001f)
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

    // ---- Fix-cycle: angleForValue - shared angle math for tick placement and the new needle
    // indicator (extracted from the former drawTicksAndLabels-local `angleFor` closure) ----

    @Test
    fun angleForValue_atZero_returnsArcStartAngle() {
        assertEquals(
            SpeedometerView.ARC_START_ANGLE,
            SpeedometerView.angleForValue(0f, 100f),
            0.0001f
        )
    }

    @Test
    fun angleForValue_atMax_returnsArcStartPlusSweep() {
        assertEquals(
            SpeedometerView.ARC_START_ANGLE + SpeedometerView.ARC_SWEEP_DEGREES,
            SpeedometerView.angleForValue(100f, 100f),
            0.0001f
        )
    }

    @Test
    fun angleForValue_atHalfScale_returnsMidpointAngle() {
        assertEquals(
            SpeedometerView.ARC_START_ANGLE + SpeedometerView.ARC_SWEEP_DEGREES / 2f,
            SpeedometerView.angleForValue(50f, 100f),
            0.0001f
        )
    }

    @Test
    fun angleForValue_scalesWithNonDefaultMax() {
        // 25 out of a 50 max is the same 50% ratio as 50 out of 100 above.
        assertEquals(
            SpeedometerView.ARC_START_ANGLE + SpeedometerView.ARC_SWEEP_DEGREES / 2f,
            SpeedometerView.angleForValue(25f, 50f),
            0.0001f
        )
    }

    @Test
    fun angleForValue_clampsAboveMaxToArcEndAngle() {
        assertEquals(
            SpeedometerView.ARC_START_ANGLE + SpeedometerView.ARC_SWEEP_DEGREES,
            SpeedometerView.angleForValue(150f, 100f),
            0.0001f
        )
    }

    @Test
    fun angleForValue_clampsNegativeValueToArcStartAngle() {
        assertEquals(
            SpeedometerView.ARC_START_ANGLE,
            SpeedometerView.angleForValue(-10f, 100f),
            0.0001f
        )
    }

    @Test
    fun angleForValue_nonPositiveMaxFallsBackToDefaultScale() {
        // maxMbps<=0 resolves through the same 100 Mb/s default as resolveMaxMbps, so a mid-scale
        // value still produces a sane angle instead of dividing by zero/NaN.
        assertEquals(
            SpeedometerView.ARC_START_ANGLE + SpeedometerView.ARC_SWEEP_DEGREES / 2f,
            SpeedometerView.angleForValue(50f, 0f),
            0.0001f
        )
    }

    // ---- Fix-cycle (arc-sweep REDO): the arc must read as closer to a semicircle - a visibly
    // bigger bottom gap than the prior 120deg - while staying symmetric around straight-down. ----

    @Test
    fun arcSweep_isWithinRequestedSemicircleBand() {
        assertTrue(
            "ARC_SWEEP_DEGREES must be within the requested ~180-200deg semicircle-like band",
            SpeedometerView.ARC_SWEEP_DEGREES in 180f..200f
        )
    }

    @Test
    fun arcSweep_bottomGapIsLargerThanPriorFixCycle() {
        val gap = 360f - SpeedometerView.ARC_SWEEP_DEGREES
        // Prior fix-cycle gap was 120deg (360 - 240) - the new gap must be visibly bigger.
        assertTrue("Bottom gap must be larger than the prior 120deg gap", gap > 120f)
    }

    @Test
    fun arcSweep_startAngleKeepsGapCenteredOnStraightDown() {
        // Gap is centered on canvas angle 90deg (straight down/6 o'clock) whenever
        // start + sweep/2 == 270 (mod 360), matching the pre-existing (150, 240) pair's symmetry.
        val arcCenterAngle = SpeedometerView.ARC_START_ANGLE + SpeedometerView.ARC_SWEEP_DEGREES / 2f
        assertEquals(270f, arcCenterAngle, 0.0001f)
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
