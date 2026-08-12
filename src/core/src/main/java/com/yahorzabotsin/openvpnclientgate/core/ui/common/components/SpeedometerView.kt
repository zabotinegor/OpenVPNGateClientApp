package com.yahorzabotsin.openvpnclientgate.core.ui.common.components

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.CornerPathEffect
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.yahorzabotsin.openvpnclientgate.core.R
import com.yahorzabotsin.openvpnclientgate.core.logging.LogTags
import com.yahorzabotsin.openvpnclientgate.core.logging.launchLogged
import com.yahorzabotsin.openvpnclientgate.vpn.ConnectionStateManager
import java.util.Locale
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Speed dial modelled on the speedtest.net gauge: a 252-degree band that starts at the
 * lower-left, runs over the top and ends at the lower-right; a blue -> cyan -> mint fill up to
 * the current speed; a non-linear 0/5/10/50/100/250/500/750/1000 scale whose labels brighten as
 * the needle passes them; a tapered needle; and the current value with its unit printed in the
 * dial's lower half.
 *
 * All drawing dimensions derive from the dial's outer radius, so the gauge keeps the reference's
 * proportions at any view size and density. Colors come from `values`/`values-night`, so light
 * and dark themes are handled by resource qualifiers rather than by branching here.
 */
class SpeedometerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    // Pure geometry/formatting helpers live in the companion so they stay unit-testable: this
    // module's JVM tests run without Android resource resolution, so SpeedometerView itself
    // cannot be constructed there. Same seam-extraction pattern ConnectionControlsView uses.
    internal companion object {
        private val TAG = LogTags.APP + ':' + "SpeedometerView"

        // drawArc convention: 0 deg = 3 o'clock, positive = clockwise. Starting at 144 deg and
        // sweeping 252 deg runs lower-left -> left -> top -> right -> lower-right, leaving a
        // 108 deg gap at the bottom and putting the scale's midpoint exactly at 12 o'clock.
        const val ARC_START_ANGLE = 144f
        const val ARC_SWEEP_DEGREES = 252f

        /**
         * The reference dial's scale. Each neighbouring pair gets an equal share of the arc, so
         * the everyday 0..100 range covers half the dial while the scale still tops out at 1000.
         */
        val SCALE_STOPS = floatArrayOf(0f, 5f, 10f, 50f, 100f, 250f, 500f, 750f, 1000f)

        const val DEFAULT_MAX_MBPS = 1000f

        private const val ANIMATION_DURATION_MS = 350L

        // Radii and text sizes as fractions of the dial's outer radius, measured off the
        // reference gauge.
        private const val ARC_WIDTH_RATIO = 0.125f
        private const val LABEL_RADIUS_RATIO = 0.61f
        private const val NEEDLE_OUTER_RADIUS_RATIO = 0.66f
        private const val NEEDLE_INNER_RADIUS_RATIO = 0.09f
        private const val NEEDLE_OUTER_HALF_WIDTH_RATIO = 0.030f
        private const val NEEDLE_INNER_HALF_WIDTH_RATIO = 0.013f
        /** Softens the wedge's corners without inflating it into a capsule. */
        private const val NEEDLE_CORNER_RATIO = 0.4f
        private const val VALUE_CENTER_RATIO = 0.50f
        private const val UNIT_CENTER_RATIO = 0.75f
        private const val VALUE_TEXT_SIZE_RATIO = 0.23f
        private const val UNIT_TEXT_SIZE_RATIO = 0.085f
        private const val LABEL_TEXT_SIZE_RATIO = 0.085f

        // The dial spans one outer radius above its center and 0.84 below it (the unit caption
        // sits lower than the arc's endpoints, which only reach 0.588).
        private const val VERTICAL_EXTENT_RATIO = 1.84f

        // Halo passes drawn under the fill, widest and faintest first. Cheaper than a
        // BlurMaskFilter, which would force this view into a software layer. The alphas are
        // theme-dependent (see integers.xml) because a halo that reads as glow on the dark face
        // reads as a wash on the light one.
        private val GLOW_WIDTH_RATIOS = floatArrayOf(1.7f, 1.28f)

        /** Where the mid gradient color lands within the filled part of the arc. */
        private const val GRADIENT_MID_POSITION = 0.45f

        /**
         * Degrees of arc, measured from [ARC_START_ANGLE], that [value] fills. Interpolates
         * linearly inside a [SCALE_STOPS] segment and gives every segment the same angular share,
         * which is what makes the dial non-linear. [maxMbps] rescales the whole scale
         * proportionally, so a smaller maximum keeps the reference's shape.
         */
        fun sweepForValue(value: Float, maxMbps: Float): Float {
            val max = resolveMaxMbps(maxMbps)
            val scale = max / SCALE_STOPS.last()
            val clamped = value.coerceIn(0f, max)
            val segments = SCALE_STOPS.size - 1
            for (index in 0 until segments) {
                val high = SCALE_STOPS[index + 1] * scale
                if (clamped > high) continue
                val low = SCALE_STOPS[index] * scale
                val within = if (high > low) (clamped - low) / (high - low) else 0f
                return ARC_SWEEP_DEGREES * (index + within) / segments
            }
            return ARC_SWEEP_DEGREES
        }

        /**
         * Index of the last scale stop drawn in the bright color for a given [sweep]: every stop
         * the needle has passed, plus the one it is heading towards. The reference dial lights
         * that upcoming stop too - at 81.74 its "100" is bright while "250" is muted - and it
         * keeps the label the needle currently overlaps legible.
         */
        fun lastActiveStopIndex(sweep: Float): Int {
            val segments = SCALE_STOPS.size - 1
            val segmentSweep = ARC_SWEEP_DEGREES / segments
            return kotlin.math.ceil(sweep / segmentSweep).toInt().coerceIn(0, segments)
        }

        fun resolveMaxMbps(maxMbps: Float): Float =
            if (maxMbps.isFinite() && maxMbps > 0f) maxMbps else DEFAULT_MAX_MBPS

        fun resolveSpeed(value: Double): Float =
            if (value.isFinite() && value >= 0.0) value.toFloat() else 0f

        /**
         * Two decimals like the reference, dropping precision as the number gets wider. Formatted
         * with a fixed [Locale.US] rather than the device locale, matching every other numeric
         * readout on this screen (duration, downloaded/uploaded bytes, UTC offset all use
         * Locale.US in ConnectionControlsPresenter/ConnectionControlsUseCase/
         * ServerDisplayFormatter) - a locale-dependent decimal separator here would visibly
         * disagree with its neighbours on the same screen (e.g. "0,00" next to "40.15 MB").
         */
        fun formatValue(value: Float): String {
            val pattern = when {
                value < 100f -> "%.2f"
                value < 1000f -> "%.1f"
                else -> "%.0f"
            }
            return String.format(Locale.US, pattern, value)
        }

        /**
         * Scale labels: integers where possible, one decimal only when the scale is compressed.
         * Fixed [Locale.US] like [formatValue], so the labels and the value readout always agree
         * on digit shapes and decimal separator with each other and with the rest of the screen.
         */
        fun formatScaleStop(value: Float): String {
            val rounded = value.roundToInt()
            return if (value >= 10f || rounded.toFloat() == value) {
                String.format(Locale.US, "%d", rounded)
            } else {
                String.format(Locale.US, "%.1f", value)
            }
        }
    }

    private val trackColor = ContextCompat.getColor(context, R.color.speedometer_track_color)
    private val faceCenterColor =
        ContextCompat.getColor(context, R.color.speedometer_face_center_color)
    private val faceEdgeColor = ContextCompat.getColor(context, R.color.speedometer_face_edge_color)
    private val gradientColors = intArrayOf(
        ContextCompat.getColor(context, R.color.speedometer_gradient_start),
        ContextCompat.getColor(context, R.color.speedometer_gradient_mid),
        ContextCompat.getColor(context, R.color.speedometer_gradient_end),
    )
    private val labelActiveColor =
        ContextCompat.getColor(context, R.color.speedometer_label_active_color)
    private val labelInactiveColor =
        ContextCompat.getColor(context, R.color.speedometer_label_inactive_color)
    private val needleOuterColor =
        ContextCompat.getColor(context, R.color.speedometer_needle_outer_color)
    private val needleInnerColor =
        ContextCompat.getColor(context, R.color.speedometer_needle_inner_color)
    private val glowAlphas = intArrayOf(
        resources.getInteger(R.integer.speedometer_glow_alpha_outer),
        resources.getInteger(R.integer.speedometer_glow_alpha_inner),
    )

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
        color = trackColor
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
    }
    private val facePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        color = ContextCompat.getColor(context, R.color.speedometer_value_color)
    }
    private val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = ContextCompat.getColor(context, R.color.speedometer_unit_color)
    }

    private val arcBounds = RectF()
    private val needlePath = Path()
    private val unitLabel: String = context.getString(R.string.speedometer_unit_mbps)

    private var centerX = 0f
    private var centerY = 0f
    private var outerRadius = 0f
    private var arcWidth = 0f

    /** Sweep the cached [progressPaint] shader was built for; -1 forces a rebuild. */
    private var shaderSweep = -1f

    private var currentMbps: Float = 0f
    private var maxMbps: Float = DEFAULT_MAX_MBPS
    private var animator: ValueAnimator? = null

    init {
        contentDescription = describe(currentMbps)
    }

    fun setSpeedMbps(value: Double) {
        val target = resolveSpeed(value)
        animator?.cancel()
        if (!isAttachedToWindow || outerRadius <= 0f) {
            currentMbps = target
            onSpeedChanged()
            return
        }
        animator = ValueAnimator.ofFloat(currentMbps, target).apply {
            duration = ANIMATION_DURATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                currentMbps = it.animatedValue as Float
                onSpeedChanged()
            }
            start()
        }
    }

    fun setMaxMbps(max: Float) {
        maxMbps = resolveMaxMbps(max)
        shaderSweep = -1f
        invalidate()
    }

    fun bindTo(lifecycleOwner: LifecycleOwner) {
        lifecycleOwner.launchLogged(TAG) {
            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                ConnectionStateManager.speedMbps.collect { setSpeedMbps(it) }
            }
        }
    }

    private fun onSpeedChanged() {
        contentDescription = describe(currentMbps)
        invalidate()
    }

    private fun describe(value: Float) = "${formatValue(value)} $unitLabel"

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val availableWidth = (w - paddingLeft - paddingRight).toFloat()
        val availableHeight = (h - paddingTop - paddingBottom).toFloat()
        outerRadius = min(availableWidth / 2f, availableHeight / VERTICAL_EXTENT_RATIO)
            .coerceAtLeast(0f)
        arcWidth = outerRadius * ARC_WIDTH_RATIO

        centerX = paddingLeft + availableWidth / 2f
        // Center the dial's full vertical extent in whatever height is left over.
        val slack = (availableHeight - outerRadius * VERTICAL_EXTENT_RATIO).coerceAtLeast(0f)
        centerY = paddingTop + slack / 2f + outerRadius

        val bandRadius = outerRadius - arcWidth / 2f
        arcBounds.set(
            centerX - bandRadius,
            centerY - bandRadius,
            centerX + bandRadius,
            centerY + bandRadius,
        )

        trackPaint.strokeWidth = arcWidth
        labelPaint.textSize = outerRadius * LABEL_TEXT_SIZE_RATIO
        valuePaint.textSize = outerRadius * VALUE_TEXT_SIZE_RATIO
        unitPaint.textSize = outerRadius * UNIT_TEXT_SIZE_RATIO
        needlePaint.pathEffect = CornerPathEffect(
            outerRadius * NEEDLE_OUTER_HALF_WIDTH_RATIO * NEEDLE_CORNER_RATIO,
        )
        facePaint.shader = RadialGradient(
            centerX,
            centerY,
            (outerRadius - arcWidth).coerceAtLeast(1f),
            intArrayOf(faceCenterColor, faceCenterColor, faceEdgeColor),
            floatArrayOf(0f, 0.78f, 1f),
            Shader.TileMode.CLAMP,
        )
        shaderSweep = -1f
    }

    override fun onDraw(canvas: Canvas) {
        if (outerRadius <= 0f) return
        val sweep = sweepForValue(currentMbps, maxMbps)

        canvas.drawCircle(centerX, centerY, (outerRadius - arcWidth).coerceAtLeast(1f), facePaint)
        canvas.drawArc(arcBounds, ARC_START_ANGLE, ARC_SWEEP_DEGREES, false, trackPaint)
        drawProgress(canvas, sweep)
        drawNeedle(canvas, sweep)
        // Labels last: at a reading that lands on a scale stop the needle would otherwise cover
        // that stop's label, and the number matters more than the last few pixels of the wedge.
        drawScaleLabels(canvas, sweep)
        drawReadout(canvas)
    }

    private fun drawProgress(canvas: Canvas, sweep: Float) {
        if (sweep <= 0f) return
        if (sweep != shaderSweep) {
            progressPaint.shader = buildProgressShader(sweep)
            shaderSweep = sweep
        }
        for (pass in GLOW_WIDTH_RATIOS.indices) {
            progressPaint.strokeWidth = arcWidth * GLOW_WIDTH_RATIOS[pass]
            progressPaint.alpha = glowAlphas[pass]
            canvas.drawArc(arcBounds, ARC_START_ANGLE, sweep, false, progressPaint)
        }
        progressPaint.strokeWidth = arcWidth
        progressPaint.alpha = 255
        canvas.drawArc(arcBounds, ARC_START_ANGLE, sweep, false, progressPaint)
    }

    /**
     * The gradient is remapped onto the *filled* portion of the arc rather than onto the whole
     * band, so the leading edge always sits on the bright mint end - as it does on the reference
     * dial, where a reading well short of the maximum still shows the full blue-to-mint ramp.
     */
    private fun buildProgressShader(sweep: Float): SweepGradient {
        val filled = (sweep / 360f).coerceIn(0.001f, 1f)
        val colors = intArrayOf(
            gradientColors[0],
            gradientColors[1],
            gradientColors[2],
            gradientColors[2],
        )
        val positions = floatArrayOf(
            0f,
            filled * GRADIENT_MID_POSITION,
            filled,
            1f,
        )
        return SweepGradient(centerX, centerY, colors, positions).apply {
            setLocalMatrix(Matrix().apply { postRotate(ARC_START_ANGLE, centerX, centerY) })
        }
    }

    private fun drawScaleLabels(canvas: Canvas, sweep: Float) {
        val scale = maxMbps / SCALE_STOPS.last()
        val radius = outerRadius * LABEL_RADIUS_RATIO
        val metrics = labelPaint.fontMetrics
        val baselineOffset = -(metrics.ascent + metrics.descent) / 2f
        val segments = SCALE_STOPS.size - 1
        val lastActive = lastActiveStopIndex(sweep)
        for (index in SCALE_STOPS.indices) {
            val stopSweep = ARC_SWEEP_DEGREES * index / segments
            val radians = Math.toRadians((ARC_START_ANGLE + stopSweep).toDouble())
            labelPaint.color = if (index <= lastActive) labelActiveColor else labelInactiveColor
            canvas.drawText(
                formatScaleStop(SCALE_STOPS[index] * scale),
                centerX + radius * cos(radians).toFloat(),
                centerY + radius * sin(radians).toFloat() + baselineOffset,
                labelPaint,
            )
        }
    }

    private fun drawNeedle(canvas: Canvas, sweep: Float) {
        val innerRadius = outerRadius * NEEDLE_INNER_RADIUS_RATIO
        val tipRadius = outerRadius * NEEDLE_OUTER_RADIUS_RATIO
        val innerHalfWidth = outerRadius * NEEDLE_INNER_HALF_WIDTH_RATIO
        val tipHalfWidth = outerRadius * NEEDLE_OUTER_HALF_WIDTH_RATIO

        // Built along the +X axis and rotated into place, so the wedge math stays readable.
        needlePath.rewind()
        needlePath.moveTo(centerX + innerRadius, centerY - innerHalfWidth)
        needlePath.lineTo(centerX + tipRadius, centerY - tipHalfWidth)
        needlePath.lineTo(centerX + tipRadius, centerY + tipHalfWidth)
        needlePath.lineTo(centerX + innerRadius, centerY + innerHalfWidth)
        needlePath.close()
        needlePaint.shader = LinearGradient(
            centerX + innerRadius,
            centerY,
            centerX + tipRadius,
            centerY,
            needleInnerColor,
            needleOuterColor,
            Shader.TileMode.CLAMP,
        )

        canvas.save()
        canvas.rotate(ARC_START_ANGLE + sweep, centerX, centerY)
        canvas.drawPath(needlePath, needlePaint)
        canvas.restore()
    }

    private fun drawReadout(canvas: Canvas) {
        val valueMetrics = valuePaint.fontMetrics
        canvas.drawText(
            formatValue(currentMbps),
            centerX,
            centerY + outerRadius * VALUE_CENTER_RATIO -
                (valueMetrics.ascent + valueMetrics.descent) / 2f,
            valuePaint,
        )
        val unitMetrics = unitPaint.fontMetrics
        canvas.drawText(
            unitLabel,
            centerX,
            centerY + outerRadius * UNIT_CENTER_RATIO -
                (unitMetrics.ascent + unitMetrics.descent) / 2f,
            unitPaint,
        )
    }
}
