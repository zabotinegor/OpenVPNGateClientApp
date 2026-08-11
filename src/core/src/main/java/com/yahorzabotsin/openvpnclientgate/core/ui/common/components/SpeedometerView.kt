package com.yahorzabotsin.openvpnclientgate.core.ui.common.components

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.yahorzabotsin.openvpnclientgate.core.logging.launchLogged
import kotlin.math.cos
import kotlin.math.sin
import com.yahorzabotsin.openvpnclientgate.vpn.ConnectionStateManager
import java.util.Locale
import com.yahorzabotsin.openvpnclientgate.core.R

class SpeedometerView(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    // Not `private`: this module's core unit tests run Robolectric without Android resource
    // resolution (R.color/@ColorRes lookups throw Resources$NotFoundException here - see
    // FavoriteActionDialogTest's documented constraint), so SpeedometerView itself cannot be
    // constructed in a JVM unit test. Pure, state-free logic is exposed here as `internal`
    // companion functions - the same seam-extraction pattern ConnectionControlsView uses for
    // resolveFocusTarget - so it stays unit-testable without an Android instrumentation harness.
    companion object {
        private val TAG = com.yahorzabotsin.openvpnclientgate.core.logging.LogTags.APP + ':' + "SpeedometerView"

        // US-21: shared arc geometry constants (start angle / sweep) reused by drawing,
        // tick-angle math and the progress gradient rotation - keep these in sync.
        // internal (not private) so angleForValue below is unit-testable against the real
        // constants instead of duplicating them as magic numbers in the test file.
        //
        // US-21 fix-cycle (true semicircle REDO): the prior 175/190deg pair dipped visibly below
        // the circle's vertical center on both ends, reading closer to "circle with a wedge cut
        // out" than the reference's clean top-half semicircle. Android's drawArc convention is
        // 0deg = 3 o'clock/East, clockwise: start=180 (9 o'clock/West) with sweep=180 travels
        // through 270 (12 o'clock/North) to 360/0 (3 o'clock/East), so both endpoints sit exactly
        // level with the circle's vertical center - a true flat horizontal diameter at center
        // height, with the entire bottom half of the view open (see the value/subtitle text
        // repositioning below).
        internal const val ARC_START_ANGLE = 180f
        internal const val ARC_SWEEP_DEGREES = 180f

        // US-21: single reused ValueAnimator, bounded duration per risk mitigation
        // (avoid excessive invalidate()/CPU cost on frequent StateFlow emissions).
        internal const val SPEED_ANIMATION_DURATION_MS = 350L

        // US-21 fix-cycle (literal reference replica): the gauge's scale/tick set now mirrors the
        // speedtest.net reference directly - 0/5/10/50/100/250/500/750/1000 Mb/s ticks against a
        // 1000 Mb/s max (see resolveMaxMbps/TICK_VALUES below) instead of a plain 0-100 scale.
        // The 0-100 sub-range is only 10% of that scale but is where real-world speeds live, so a
        // linear angle mapping would cram nearly every reading into a sliver near the arc start.
        // ANGLE_LOW_TIER_VALUE_RATIO marks that sub-range's boundary as a fraction of safeMax
        // (100/1000 by default, but expressed proportionally so a custom setMaxMbps value still
        // yields a sane low/high split). ANGLE_LOW_TIER_SWEEP_FRACTION is the share of the arc
        // sweep given to that low sub-range - deliberately far larger than its 10% value-share, so
        // typical speeds visually fill much more of the arc, matching why the reference reads
        // "fuller" at modest values than a strict linear mapping would.
        internal const val ANGLE_LOW_TIER_VALUE_RATIO = 0.1f
        internal const val ANGLE_LOW_TIER_SWEEP_FRACTION = 0.45f
        private const val ANGLE_HIGH_TIER_SWEEP_FRACTION = 1f - ANGLE_LOW_TIER_SWEEP_FRACTION

        // US-21 fix-cycle (AC3, needle): shared angle-for-value math, extracted from the former
        // drawTicksAndLabels-local `angleFor` closure so both tick placement and the new needle
        // indicator use one source of truth. Pure and state-free, so it is unit-tested directly
        // (no Robolectric/Context needed) following the same seam-extraction pattern as
        // formatMegabits/resolveMaxMbps/resolveSpeedTarget/shouldAnimateTo above.
        //
        // US-21 fix-cycle (literal reference replica): two-segment piecewise mapping instead of a
        // single linear ratio - clamped values below the breakpoint map onto the first
        // ANGLE_LOW_TIER_SWEEP_FRACTION of the sweep, values above it onto the remaining fraction.
        // Ticks, the needle and the gradient progress-arc sweep all call this same function, so
        // they stay visually aligned with each other by construction.
        internal fun angleForValue(valueMb: Float, maxMbps: Float): Float {
            val safeMax = resolveMaxMbps(maxMbps)
            val clamped = valueMb.coerceIn(0f, safeMax)
            val breakpoint = safeMax * ANGLE_LOW_TIER_VALUE_RATIO
            val ratio = if (clamped <= breakpoint) {
                (clamped / breakpoint) * ANGLE_LOW_TIER_SWEEP_FRACTION
            } else {
                val highTierRatio = (clamped - breakpoint) / (safeMax - breakpoint)
                ANGLE_LOW_TIER_SWEEP_FRACTION + highTierRatio * ANGLE_HIGH_TIER_SWEEP_FRACTION
            }
            return ARC_START_ANGLE + ARC_SWEEP_DEGREES * ratio
        }

        // US-21 fix-cycle (spacing REDO): radial "breathing room" ratios for the arc -> tick ->
        // label chain, all proportional to arcWidth so the gap scales with stroke thickness
        // instead of the previous fixed +2f / 0.5x-arcWidth literals that read as "glued"
        // together at typical view sizes. tickStartRadius/tickEndRadius/labelRadius below are the
        // single source of truth for both drawTicksAndLabels and the unit tests.
        private const val ARC_TO_TICK_GAP_RATIO = 0.35f
        private const val TICK_LENGTH_RATIO = 0.5f
        private const val TICK_TO_LABEL_GAP_RATIO = 0.9f

        // US-21 fix-cycle (needle REDO): tapered wedge geometry ratios, all proportional to
        // arcWidth. The wedge's narrow tip sits near the track's inner edge (radius - 0.5*arcWidth)
        // rather than deep inside the tick/label zone, and its wide base sits just past the arc's
        // outer edge (radius + 0.5*arcWidth) - matching the reference gauge's needle, which is
        // wide near the outer rim and narrows toward the center instead of being a uniform-width
        // line from near-center outward.
        private const val NEEDLE_TIP_RADIUS_RATIO = 0.7f
        private const val NEEDLE_BASE_RADIUS_RATIO = 0.65f
        private const val NEEDLE_BASE_HALF_WIDTH_RATIO = 0.25f
        private const val NEEDLE_ALPHA = 190

        // US-21 fix-cycle (spacing REDO): pure radius math extracted from drawTicksAndLabels,
        // following the same seam-extraction pattern as angleForValue - unit-testable without
        // Canvas/Paint/Context.
        internal fun tickStartRadius(radius: Float, arcWidth: Float): Float =
            radius - arcWidth * (0.5f + ARC_TO_TICK_GAP_RATIO)

        internal fun tickEndRadius(radius: Float, arcWidth: Float): Float =
            tickStartRadius(radius, arcWidth) - arcWidth * TICK_LENGTH_RATIO

        internal fun labelRadius(radius: Float, arcWidth: Float): Float =
            tickEndRadius(radius, arcWidth) - arcWidth * TICK_TO_LABEL_GAP_RATIO

        // US-21 fix-cycle (needle REDO): pure tapered-wedge point geometry, extracted so the
        // shape math has one source of truth and is unit-testable without Canvas/Paint/Path.
        // Returns the three vertices of the needle polygon (tip, base-left, base-right).
        internal data class NeedlePoints(
            val tipX: Float,
            val tipY: Float,
            val baseLeftX: Float,
            val baseLeftY: Float,
            val baseRightX: Float,
            val baseRightY: Float
        )

        internal fun computeNeedlePoints(
            cx: Float,
            cy: Float,
            radius: Float,
            arcWidth: Float,
            angleDegrees: Float
        ): NeedlePoints {
            val rad = Math.toRadians(angleDegrees.toDouble())
            val cosA = cos(rad).toFloat()
            val sinA = sin(rad).toFloat()
            // Perpendicular to the needle direction, used to flare the wide base to either side.
            val perpCos = -sinA
            val perpSin = cosA

            val tipR = radius - arcWidth * NEEDLE_TIP_RADIUS_RATIO
            val baseR = radius + arcWidth * NEEDLE_BASE_RADIUS_RATIO
            val baseHalfWidth = arcWidth * NEEDLE_BASE_HALF_WIDTH_RATIO

            val tipX = cx + cosA * tipR
            val tipY = cy + sinA * tipR
            val baseCenterX = cx + cosA * baseR
            val baseCenterY = cy + sinA * baseR

            return NeedlePoints(
                tipX = tipX,
                tipY = tipY,
                baseLeftX = baseCenterX + perpCos * baseHalfWidth,
                baseLeftY = baseCenterY + perpSin * baseHalfWidth,
                baseRightX = baseCenterX - perpCos * baseHalfWidth,
                baseRightY = baseCenterY - perpSin * baseHalfWidth
            )
        }

        // US-21 (AC5): unchanged formatting - 2 decimals under 10, 1 decimal under 100, none above.
        internal fun formatMegabits(mbps: Float): String {
            val v = mbps.coerceAtLeast(0f)
            return when {
                v >= 100f -> String.format(Locale.US, "%.0f", v)
                v >= 10f -> String.format(Locale.US, "%.1f", v)
                else -> String.format(Locale.US, "%.2f", v)
            }
        }

        // US-21 fix-cycle (literal reference replica): setMaxMbps fallback now resets to a
        // 1000 Mb/s default instead of 100 - the gauge's max scale/tick set now mirrors the
        // speedtest.net reference (which tops out at 1000 Mb/s), not a plain 0-100 scale. This is
        // an explicit product decision from this fix cycle; formatMegabits (the actual measured
        // speed number shown in the center) is untouched by this change.
        internal fun resolveMaxMbps(max: Float): Float = if (max > 0f) max else 1000f

        // US-21 fix-cycle (literal reference replica): fixed tick values matching the reference
        // gauge exactly, replacing the previous evenly-spaced 0/25/50/75/100 set derived from
        // maxMbps/4. Densely packed in the low sub-range (0/5/10/50/100) where real-world speeds
        // live, sparse above it (250/500/750/1000) - angular spacing (not value spacing) is what
        // angleForValue's piecewise mapping compresses/expands, so these stay the literal values.
        internal val TICK_VALUES = floatArrayOf(0f, 5f, 10f, 50f, 100f, 250f, 500f, 750f, 1000f)

        // Unchanged setSpeedMbps input validation - NaN/negative/infinite collapse to 0.
        internal fun resolveSpeedTarget(value: Double): Float =
            if (value.isFinite() && value >= 0) value.toFloat() else 0f

        // US-21 (AC4): only (re)start the animator when the target actually moved, so duplicate
        // StateFlow emissions of the same value cannot restart/stack an animation.
        internal fun shouldAnimateTo(target: Float, current: Float): Boolean =
            kotlin.math.abs(target - current) > 0.01f

        // US-21 fix-cycle (true semicircle REDO): with ARC_START_ANGLE/ARC_SWEEP_DEGREES now a
        // true top-half semicircle (see above), the arc's flat diameter sits exactly at centerY,
        // so the entire bottom half of the view - from centerY down to the bottom of the
        // drawable area - is open space, matching the reference where the value sits below the
        // arc rather than inside its circular interior. VALUE_LOWER_AREA_CENTER_RATIO places the
        // value text roughly centered in that open lower half (slightly above dead-center so the
        // subtitle line drawn below it still has room), replacing the old radius-based
        // `centerY + radius * 0.45` offset that was calibrated for the previous non-semicircle
        // geometry and no longer matches the shape of the open space.
        internal const val VALUE_LOWER_AREA_CENTER_RATIO = 0.42f
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arcRect = RectF()

    private var arcColor: Int
    private var progressColor: Int
    private var speedTextColor: Int
    private var subtitleTextColor: Int
    private var arcWidth: Float
    private var speedTextSize: Float
    private var subtitleTextSize: Float

    // US-21: speedtest.net-style multi-stop gradient (blue -> cyan -> green) for the progress arc.
    private val gradientStartColor: Int = context.getColor(R.color.speedometer_gradient_start)
    private val gradientMidColor: Int = context.getColor(R.color.speedometer_gradient_mid)
    private val gradientEndColor: Int = context.getColor(R.color.speedometer_gradient_end)
    private var progressShader: SweepGradient? = null

    // US-21 fix-cycle (needle REDO): tapered gray wedge pointing at the current value, distinct
    // from both the now-dimmed base arc track and the gradient progress fill. Not exposed as an
    // XML/theme attr - kept as a plain constant per the story's "avoid new attr/drawable surface"
    // guidance. Color.GRAY reads legibly against both the light and dark app_background tones;
    // drawn semi-transparent (NEEDLE_ALPHA) so the soft/muted look matches the reference gauge.
    private val needleColor: Int = Color.GRAY

    // Remember whether caller provided explicit dimensions via XML attrs
    private var arcWidthFromAttrs: Boolean = false
    private var speedTextFromAttrs: Boolean = false
    private var subtitleTextFromAttrs: Boolean = false
    // US-21 fix-cycle (literal reference replica): default max scale raised from 100 to 1000
    // Mb/s to mirror the speedtest.net reference's tick set (see TICK_VALUES/resolveMaxMbps in
    // the companion object) - callers that never invoke setMaxMbps now render against this
    // 1000 Mb/s scale instead of the previous 100 Mb/s one.
    private var maxMbps: Float = 1000f

    // currentMbps is the latest target value from setSpeedMbps/setMaxMbps callers.
    // animatedMbps is the value actually rendered on each frame, eased toward currentMbps
    // by speedAnimator so the arc/number never jump instantly (AC4).
    private var currentMbps: Float = 0f
    private var animatedMbps: Float = 0f

    // US-21: single reused ValueAnimator instance - restarted (not stacked) on every
    // setSpeedMbps call so rapid successive updates cannot pile up overlapping animations.
    private val speedAnimator: ValueAnimator = ValueAnimator().apply {
        duration = SPEED_ANIMATION_DURATION_MS
        interpolator = DecelerateInterpolator()
        addUpdateListener { animator ->
            animatedMbps = animator.animatedValue as Float
            invalidate()
        }
    }

    private fun resolveColorAttr(attrRes: Int, fallback: Int): Int {
        val tv = android.util.TypedValue()
        return if (context.theme.resolveAttribute(attrRes, tv, true)) {
            if (tv.resourceId != 0) context.getColor(tv.resourceId) else tv.data
        } else fallback
    }

    init {
        val typedArray = context.theme.obtainStyledAttributes(
            attrs,
            R.styleable.SpeedometerView,
            0, 0
        )

        try {
            val defaultArc = resolveColorAttr(
                R.attr.ovpnSpeedometerArcColor,
                context.getColor(R.color.speedometer_arc_color)
            )
            val defaultProgress = resolveColorAttr(
                R.attr.ovpnSpeedometerProgressColor,
                context.getColor(R.color.speedometer_progress_color)
            )
            val defaultText = resolveColorAttr(
                R.attr.ovpnSpeedometerTextColor,
                Color.WHITE
            )
            // US-21 (AC3): center speed number uses a distinct accent color instead of the
            // plain gray/white text color; the "Mb/s" subtitle keeps the original text color.
            val defaultValueAccent = resolveColorAttr(
                R.attr.ovpnSpeedometerValueAccentColor,
                context.getColor(R.color.speedometer_value_accent_color)
            )

            arcColor = typedArray.getColor(R.styleable.SpeedometerView_arcColor, defaultArc)
            progressColor = typedArray.getColor(R.styleable.SpeedometerView_progressColor, defaultProgress)
            speedTextColor = typedArray.getColor(R.styleable.SpeedometerView_speedTextColor, defaultValueAccent)
            subtitleTextColor = typedArray.getColor(R.styleable.SpeedometerView_subtitleTextColor, defaultText)

            arcWidthFromAttrs = typedArray.hasValue(R.styleable.SpeedometerView_arcWidth)
            speedTextFromAttrs = typedArray.hasValue(R.styleable.SpeedometerView_speedTextSize)
            subtitleTextFromAttrs = typedArray.hasValue(R.styleable.SpeedometerView_subtitleTextSize)

            // Fallbacks are placeholders; they will be recalculated proportionally in onSizeChanged
            arcWidth = typedArray.getDimension(R.styleable.SpeedometerView_arcWidth, 0f)
            speedTextSize = typedArray.getDimension(R.styleable.SpeedometerView_speedTextSize, 0f)
            subtitleTextSize = typedArray.getDimension(R.styleable.SpeedometerView_subtitleTextSize, 0f)
        } finally {
            typedArray.recycle()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val base = kotlin.math.min(w, h).toFloat()
        // Scale dimensions to view size when not provided explicitly
        if (!arcWidthFromAttrs || arcWidth <= 0f) {
            // US-21 fix-cycle (thinner arc REDO): reduced from 0.06f/8f (~58% of the previous
            // proportional value and floor) - the prior arc read as a thick/chunky band next to
            // the reference's noticeably slimmer track.
            arcWidth = (base * 0.035f).coerceAtLeast(5f)
        }
        if (!speedTextFromAttrs || speedTextSize <= 0f) {
            // US-21 fix-cycle (AC2): shrunk from 0.20f/24f - the reference gauge's center number
            // is noticeably smaller than a value vertically centered in the middle of the circle.
            speedTextSize = (base * 0.13f).coerceAtLeast(18f)
        }
        if (!subtitleTextFromAttrs || subtitleTextSize <= 0f) {
            subtitleTextSize = (base * 0.08f).coerceAtLeast(12f)
        }
        // US-21 (risk mitigation): rebuild the gradient shader here, alongside the existing
        // proportional sizing logic, so it is repositioned on every resize/rotation instead
        // of being stretched/misaligned.
        updateArcRect(w.toFloat(), h.toFloat())
        rebuildProgressShader()
    }

    private fun updateArcRect(w: Float, h: Float) {
        val inset = arcWidth / 2f
        arcRect.set(
            paddingLeft + inset,
            paddingTop + inset,
            w - paddingRight - inset,
            h - paddingBottom - inset
        )

        if (arcRect.width() > arcRect.height()) {
            val diff = arcRect.width() - arcRect.height()
            arcRect.left += diff / 2f
            arcRect.right -= diff / 2f
        } else {
            val diff = arcRect.height() - arcRect.width()
            arcRect.top += diff / 2f
            arcRect.bottom -= diff / 2f
        }
    }

    private fun rebuildProgressShader() {
        if (arcRect.width() <= 0f || arcRect.height() <= 0f) {
            progressShader = null
            return
        }
        val cx = arcRect.centerX()
        val cy = arcRect.centerY()
        // SweepGradient spans the full 360 degrees starting at angle 0 (3 o'clock), clockwise -
        // the same convention Canvas#drawArc uses. Map the blue->cyan->green stops across the
        // portion of the wheel covered by our ARC_SWEEP_DEGREES arc (position 0..ARC_SWEEP/360),
        // then rotate the wheel so position 0 lands on ARC_START_ANGLE. The tail stop (back to
        // blue) covers the remaining, undrawn portion of the wheel and is never visible.
        val midPosition = (ARC_SWEEP_DEGREES / 2f) / 360f
        val endPosition = ARC_SWEEP_DEGREES / 360f
        val shader = SweepGradient(
            cx,
            cy,
            intArrayOf(gradientStartColor, gradientMidColor, gradientEndColor, gradientStartColor),
            floatArrayOf(0f, midPosition, endPosition, 1f)
        )
        shader.setLocalMatrix(Matrix().apply { postRotate(ARC_START_ANGLE, cx, cy) })
        progressShader = shader
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val centerX = width / 2f
        val centerY = height / 2f

        updateArcRect(width.toFloat(), height.toFloat())

        // base arc
        paint.shader = null
        paint.color = arcColor
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = arcWidth
        paint.strokeCap = Paint.Cap.ROUND
        canvas.drawArc(arcRect, ARC_START_ANGLE, ARC_SWEEP_DEGREES, false, paint)

        // ticks + numeric labels (fixed TICK_VALUES set, see companion object)
        drawTicksAndLabels(canvas)

        // progress arc: multi-stop gradient (blue -> cyan -> green), fills fully if > max (AC1)
        if (progressShader == null) {
            rebuildProgressShader()
        }
        paint.color = progressColor
        paint.shader = progressShader
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        // US-21 fix-cycle (literal reference replica): sweep now goes through the shared
        // angleForValue piecewise mapping instead of a plain linear (animatedMbps / maxMbps)
        // ratio, so the progress fill stays visually aligned with the ticks and needle, which
        // already used angleForValue - all three must agree on the same non-linear mapping.
        val sweep = angleForValue(animatedMbps, maxMbps) - ARC_START_ANGLE
        canvas.drawArc(arcRect, ARC_START_ANGLE, sweep, false, paint)
        paint.shader = null

        // US-21 fix-cycle: tapered gray needle wedge at the current value's angle, rendered on
        // top of both arcs - additive to the gradient progress fill, not a replacement for it.
        drawNeedle(canvas)

        // US-21 fix-cycle (true semicircle REDO): value number repositioned into the open lower
        // half created by the now-true-semicircle arc (flat diameter at centerY - see
        // ARC_START_ANGLE/ARC_SWEEP_DEGREES above), roughly centered between centerY and the
        // bottom of the drawable area, instead of the old radius-based `centerY + radius * 0.45`
        // offset that was calibrated for the previous (non-semicircle) arc shape. The accent
        // color that used to sit on the number stays on the "Mb/s" subtitle only, matching
        // speedtest.net's teal "down-arrow Mbps" treatment; a unicode down-arrow glyph substitutes
        // for a dedicated icon drawable per the story's clarifying answers.
        val lowerAreaBottom = height.toFloat() - paddingBottom
        val valueY = centerY + (lowerAreaBottom - centerY) * VALUE_LOWER_AREA_CENTER_RATIO

        paint.color = subtitleTextColor
        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = speedTextSize

        val valueStr = formatMegabits(animatedMbps.coerceAtLeast(0f))
        canvas.drawText(valueStr, centerX, valueY, paint)

        paint.color = speedTextColor
        paint.textSize = subtitleTextSize
        val unitStr = "\u2193 Mb/s"
        canvas.drawText(unitStr, centerX, valueY + subtitleTextSize * 1.5f, paint)
    }

    private fun drawNeedle(canvas: Canvas) {
        val cx = arcRect.centerX()
        val cy = arcRect.centerY()
        val radius = arcRect.width() / 2f
        val angle = angleForValue(animatedMbps, maxMbps)

        // US-21 fix-cycle (needle REDO): tapered wedge polygon instead of a uniform-width line -
        // narrow tip near the track's inner edge, flaring to a wide base just past the outer edge.
        // See computeNeedlePoints for the shared, unit-tested point math.
        val points = computeNeedlePoints(cx, cy, radius, arcWidth, angle)
        val needlePath = Path().apply {
            moveTo(points.tipX, points.tipY)
            lineTo(points.baseLeftX, points.baseLeftY)
            lineTo(points.baseRightX, points.baseRightY)
            close()
        }

        paint.shader = null
        paint.color = needleColor
        paint.alpha = NEEDLE_ALPHA
        paint.style = Paint.Style.FILL
        canvas.drawPath(needlePath, paint)
        paint.alpha = 255
    }

    fun setSpeedMbps(value: Double) {
        val v = resolveSpeedTarget(value)
        if (shouldAnimateTo(v, currentMbps)) {
            currentMbps = v
            // US-21 (AC4): ease from whatever is currently on screen to the new target using a
            // single reused ValueAnimator - cancel+restart rather than queue, so rapid
            // successive updates never stack overlapping animations.
            speedAnimator.cancel()
            speedAnimator.setFloatValues(animatedMbps, v)
            speedAnimator.start()
        }
    }

    override fun onDetachedFromWindow() {
        speedAnimator.cancel()
        super.onDetachedFromWindow()
    }

    fun setMaxMbps(max: Float) {
        maxMbps = resolveMaxMbps(max)
        invalidate()
    }

    fun bindTo(lifecycleOwner: LifecycleOwner) {
        lifecycleOwner.launchLogged(TAG) {
            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                ConnectionStateManager.speedMbps.collect { setSpeedMbps(it) }
            }
        }
    }

    private fun drawTicksAndLabels(canvas: Canvas) {
        val cx = arcRect.centerX()
        val cy = arcRect.centerY()
        val radius = arcRect.width() / 2f
        // US-21 fix-cycle (spacing REDO): radii now go through tickStartRadius/tickEndRadius/
        // labelRadius (proportional ARC_TO_TICK_GAP_RATIO / TICK_TO_LABEL_GAP_RATIO spacing)
        // instead of the previous fixed +2f / 0.5x-arcWidth literals, so the arc, tick marks and
        // number labels have clear, size-proportional breathing room between them.
        val outer = tickStartRadius(radius, arcWidth)
        val majorLen = arcWidth * TICK_LENGTH_RATIO

        // US-21 fix-cycle: minor ticks removed entirely per the speedtest.net reference - only
        // the major value ticks remain. Angle math now goes through the shared angleForValue
        // companion function (also used by drawNeedle) instead of a local closure, so tick
        // placement and the needle stay in sync from one source of truth.
        //
        // US-21 fix-cycle (literal reference replica): tick VALUES are now the fixed TICK_VALUES
        // set (0/5/10/50/100/250/500/750/1000) instead of an evenly-spaced maxMbps/4 series - only
        // their angular positions move (via angleForValue's piecewise mapping), so 0-100 reads
        // densely packed and 250-1000 reads evenly spread, matching the reference.
        for (mv in TICK_VALUES) {
            val a = Math.toRadians(angleForValue(mv, maxMbps).toDouble())
            val cosA = cos(a).toFloat()
            val sinA = sin(a).toFloat()
            val x1 = cx + cosA * outer
            val y1 = cy + sinA * outer
            val x2 = cx + cosA * (outer - majorLen)
            val y2 = cy + sinA * (outer - majorLen)
            val savedWidth = paint.strokeWidth
            paint.color = arcColor
            paint.strokeWidth = arcWidth * 0.12f
            canvas.drawLine(x1, y1, x2, y2, paint)
            paint.strokeWidth = savedWidth

            val labelR = labelRadius(radius, arcWidth)
            val lx = cx + cosA * labelR
            val ly = cy + sinA * labelR
            paint.color = subtitleTextColor
            paint.alpha = 220
            paint.style = Paint.Style.FILL
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = subtitleTextSize * 0.9f
            canvas.drawText(mv.toInt().toString(), lx, ly, paint)
            paint.alpha = 255
        }
    }
}


