package com.yahorzabotsin.openvpnclientgate.core.ui.common.components

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
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
        internal const val ARC_START_ANGLE = 150f
        internal const val ARC_SWEEP_DEGREES = 240f

        // US-21: single reused ValueAnimator, bounded duration per risk mitigation
        // (avoid excessive invalidate()/CPU cost on frequent StateFlow emissions).
        internal const val SPEED_ANIMATION_DURATION_MS = 350L

        // US-21 fix-cycle (AC3, needle): shared angle-for-value math, extracted from the former
        // drawTicksAndLabels-local `angleFor` closure so both tick placement and the new needle
        // indicator use one source of truth. Pure and state-free, so it is unit-tested directly
        // (no Robolectric/Context needed) following the same seam-extraction pattern as
        // formatMegabits/resolveMaxMbps/resolveSpeedTarget/shouldAnimateTo above.
        internal fun angleForValue(valueMb: Float, maxMbps: Float): Float {
            val safeMax = resolveMaxMbps(maxMbps)
            val ratio = (valueMb / safeMax).coerceIn(0f, 1f)
            return ARC_START_ANGLE + ARC_SWEEP_DEGREES * ratio
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

        // US-21 (AC5): unchanged setMaxMbps fallback - non-positive values reset to the 100 Mb/s default.
        internal fun resolveMaxMbps(max: Float): Float = if (max > 0f) max else 100f

        // Unchanged setSpeedMbps input validation - NaN/negative/infinite collapse to 0.
        internal fun resolveSpeedTarget(value: Double): Float =
            if (value.isFinite() && value >= 0) value.toFloat() else 0f

        // US-21 (AC4): only (re)start the animator when the target actually moved, so duplicate
        // StateFlow emissions of the same value cannot restart/stack an animation.
        internal fun shouldAnimateTo(target: Float, current: Float): Boolean =
            kotlin.math.abs(target - current) > 0.01f

        // US-21 fix-cycle (AC2): center value sits low near the open bottom arc gap (the
        // undrawn segment between ARC_START_ANGLE+ARC_SWEEP_DEGREES and 360+ARC_START_ANGLE,
        // which is centered on straight-down/6-o'clock) instead of the dead center of the
        // circle, matching speedtest.net's layout.
        private const val VALUE_VERTICAL_OFFSET_RATIO = 0.45f
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

    // US-21 fix-cycle: thin gray needle pointing at the current value, distinct from both the
    // now-dimmed base arc track and the gradient progress fill. Not exposed as an XML/theme
    // attr - kept as a plain constant per the story's "avoid new attr/drawable surface" guidance,
    // Color.GRAY reads legibly against both the light and dark app_background tones.
    private val needleColor: Int = Color.GRAY

    // Remember whether caller provided explicit dimensions via XML attrs
    private var arcWidthFromAttrs: Boolean = false
    private var speedTextFromAttrs: Boolean = false
    private var subtitleTextFromAttrs: Boolean = false
    private var maxMbps: Float = 100f

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
            arcWidth = (base * 0.06f).coerceAtLeast(8f)
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
        // portion of the wheel covered by our 240-degree arc (position 0..ARC_SWEEP/360), then
        // rotate the wheel so position 0 lands on ARC_START_ANGLE. The tail stop (back to blue)
        // covers the remaining, undrawn portion of the wheel and is never visible.
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

        // ticks + numeric labels (0..100 Mb/s)
        drawTicksAndLabels(canvas)

        // progress arc: multi-stop gradient (blue -> cyan -> green), fills fully if > max (AC1)
        if (progressShader == null) {
            rebuildProgressShader()
        }
        paint.color = progressColor
        paint.shader = progressShader
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        val sweep = (animatedMbps / maxMbps).coerceIn(0f, 1f) * ARC_SWEEP_DEGREES
        canvas.drawArc(arcRect, ARC_START_ANGLE, sweep, false, paint)
        paint.shader = null

        // US-21 fix-cycle: thin gray needle at the current value's angle, rendered on top of
        // both arcs - additive to the gradient progress fill, not a replacement for it.
        drawNeedle(canvas)

        // US-21 fix-cycle (AC2/AC3): value number moved low, near the open bottom arc gap,
        // instead of sitting dead-center in the circle, and shrunk (see onSizeChanged). The
        // accent color that used to be on the number now marks the "Mb/s" subtitle instead,
        // matching speedtest.net's teal "down-arrow Mbps" treatment; a unicode down-arrow glyph
        // substitutes for a dedicated icon drawable per the story's clarifying answers.
        val radius = arcRect.width() / 2f
        val valueY = centerY + radius * VALUE_VERTICAL_OFFSET_RATIO

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
        val rad = Math.toRadians(angle.toDouble())
        val cosA = cos(rad).toFloat()
        val sinA = sin(rad).toFloat()

        // Starts just inside the arc's inner edge and points outward past the arc band, so the
        // tip reads as a needle poking through the track/fill rather than stopping short of it.
        val innerR = radius - arcWidth * 1.2f
        val outerR = radius + arcWidth * 0.35f
        val x1 = cx + cosA * innerR
        val y1 = cy + sinA * innerR
        val x2 = cx + cosA * outerR
        val y2 = cy + sinA * outerR

        paint.shader = null
        paint.color = needleColor
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = arcWidth * 0.12f
        canvas.drawLine(x1, y1, x2, y2, paint)
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
        val outer = radius - arcWidth / 2f + 2f
        val majorLen = arcWidth * 0.60f

        val majorIntervals = 4
        val majorStep = maxMbps / majorIntervals

        // US-21 fix-cycle: minor ticks removed entirely per the speedtest.net reference - only
        // the major value ticks (0/25/50/75/100) with labels remain. Angle math now goes through
        // the shared angleForValue companion function (also used by drawNeedle) instead of a
        // local closure, so tick placement and the needle stay in sync from one source of truth.
        for (i in 0..majorIntervals) {
            val mv = i * majorStep
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

            val labelR = outer - majorLen - (arcWidth * 0.5f)
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


