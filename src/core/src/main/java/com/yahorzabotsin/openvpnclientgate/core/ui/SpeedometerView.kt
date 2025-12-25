package com.yahorzabotsin.openvpnclientgate.core.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
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
    private companion object {
        private val TAG = com.yahorzabotsin.openvpnclientgate.core.logging.LogTags.APP + ':' + "SpeedometerView"
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

    // Remember whether caller provided explicit dimensions via XML attrs
    private var arcWidthFromAttrs: Boolean = false
    private var speedTextFromAttrs: Boolean = false
    private var subtitleTextFromAttrs: Boolean = false
    private var maxMbps: Float = 100f
    private var currentMbps: Float = 0f

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

            arcColor = typedArray.getColor(R.styleable.SpeedometerView_arcColor, defaultArc)
            progressColor = typedArray.getColor(R.styleable.SpeedometerView_progressColor, defaultProgress)
            speedTextColor = typedArray.getColor(R.styleable.SpeedometerView_speedTextColor, defaultText)
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
            speedTextSize = (base * 0.20f).coerceAtLeast(24f)
        }
        if (!subtitleTextFromAttrs || subtitleTextSize <= 0f) {
            subtitleTextSize = (base * 0.08f).coerceAtLeast(12f)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val centerX = width / 2f
        val centerY = height / 2f

        val inset = arcWidth / 2f
        arcRect.set(
            paddingLeft + inset,
            paddingTop + inset,
            width - paddingRight - inset,
            height - paddingBottom - inset
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

        // base arc
        paint.color = arcColor
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = arcWidth
        paint.strokeCap = Paint.Cap.ROUND
        canvas.drawArc(arcRect, 150f, 240f, false, paint)

        // ticks + numeric labels (0..100 Mb/s)
        drawTicksAndLabels(canvas)

        // progress arc (fills fully if > max)
        paint.color = progressColor
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        val sweep = (currentMbps / maxMbps).coerceIn(0f, 1f) * 240f
        canvas.drawArc(arcRect, 150f, sweep, false, paint)

        // centered value shown in Mb/s (to match gauge scale 0..100 Mb/s)
        paint.color = speedTextColor
        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = speedTextSize

        val valueStr = formatMegabits(currentMbps.coerceAtLeast(0f))
        canvas.drawText(valueStr, centerX, centerY, paint)

        paint.color = subtitleTextColor
        paint.textSize = subtitleTextSize
        val unitStr = "Mb/s"
        canvas.drawText(unitStr, centerX, centerY + subtitleTextSize * 1.5f, paint)
    }

    fun setSpeedMbps(value: Double) {
        val v = if (value.isFinite() && value >= 0) value.toFloat() else 0f
        if (kotlin.math.abs(v - currentMbps) > 0.01f) {
            currentMbps = v
            invalidate()
        }
    }

    fun setMaxMbps(max: Float) {
        maxMbps = if (max > 0f) max else 100f
        invalidate()
    }

    fun bindTo(lifecycleOwner: LifecycleOwner) {
        lifecycleOwner.launchLogged(TAG) {
            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                ConnectionStateManager.speedMbps.collect { setSpeedMbps(it) }
            }
        }
    }

    private fun formatMegabits(mbps: Float): String {
        val v = mbps.coerceAtLeast(0f)
        return when {
            v >= 100f -> String.format(Locale.US, "%.0f", v)
            v >= 10f -> String.format(Locale.US, "%.1f", v)
            else -> String.format(Locale.US, "%.2f", v)
        }
    }

    private fun drawTicksAndLabels(canvas: Canvas) {
        val cx = arcRect.centerX()
        val cy = arcRect.centerY()
        val radius = arcRect.width() / 2f
        val outer = radius - arcWidth / 2f + 2f
        val majorLen = arcWidth * 0.60f
        val minorLen = arcWidth * 0.35f

        fun angleFor(valueMb: Float): Float {
            val ratio = (valueMb / maxMbps).coerceIn(0f, 1f)
            return 150f + 240f * ratio
        }

        val majorIntervals = 4
        val majorStep = maxMbps / majorIntervals
        val minorStep = majorStep / 2f

        // minor ticks between major ones
        for (i in 1 until majorIntervals * 2) {
            if (i % 2 == 1) {
                val v = i * minorStep
                val a = Math.toRadians(angleFor(v).toDouble())
                val cosA = cos(a).toFloat()
                val sinA = sin(a).toFloat()
                val x1 = cx + cosA * outer
                val y1 = cy + sinA * outer
                val x2 = cx + cosA * (outer - minorLen)
                val y2 = cy + sinA * (outer - minorLen)
                val savedWidth = paint.strokeWidth
                paint.color = arcColor
                paint.alpha = 150
                paint.strokeWidth = arcWidth * 0.08f
                canvas.drawLine(x1, y1, x2, y2, paint)
                paint.strokeWidth = savedWidth
                paint.alpha = 255
            }
        }

        // major ticks with labels
        for (i in 0..majorIntervals) {
            val mv = i * majorStep
            val a = Math.toRadians(angleFor(mv).toDouble())
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

