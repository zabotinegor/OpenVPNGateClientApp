package com.yahorzabotsin.openvpnclient.core.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.yahorzabotsin.openvpnclient.core.R

class SpeedometerView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arcRect = RectF()

    // Customizable properties
    private var arcColor: Int
    private var progressColor: Int
    private var speedTextColor: Int
    private var subtitleTextColor: Int
    private var arcWidth: Float
    private var speedTextSize: Float
    private var subtitleTextSize: Float

    private fun resolveColorAttr(attrRes: Int, fallback: Int): Int {
        val tv = android.util.TypedValue()
        return if (context.theme.resolveAttribute(attrRes, tv, true)) {
            if (tv.resourceId != 0) context.getColor(tv.resourceId) else tv.data
        } else fallback
    }

    init {
        // Use software layer to avoid rendering artifacts with drawArc
        setLayerType(View.LAYER_TYPE_SOFTWARE, null)

        val typedArray = context.theme.obtainStyledAttributes(
            attrs,
            R.styleable.SpeedometerView,
            0, 0
        )

        try {
            val defaultArc = resolveColorAttr(R.attr.ovpnSpeedometerArcColor, Color.parseColor("#1E3A6E"))
            val defaultProgress = resolveColorAttr(R.attr.ovpnSpeedometerProgressColor, Color.parseColor("#3B7CFF"))
            val defaultText = resolveColorAttr(R.attr.ovpnSpeedometerTextColor, Color.WHITE)

            arcColor = if (typedArray.hasValue(R.styleable.SpeedometerView_arcColor))
                typedArray.getColor(R.styleable.SpeedometerView_arcColor, defaultArc)
            else defaultArc

            progressColor = if (typedArray.hasValue(R.styleable.SpeedometerView_progressColor))
                typedArray.getColor(R.styleable.SpeedometerView_progressColor, defaultProgress)
            else defaultProgress

            speedTextColor = if (typedArray.hasValue(R.styleable.SpeedometerView_speedTextColor))
                typedArray.getColor(R.styleable.SpeedometerView_speedTextColor, defaultText)
            else defaultText

            subtitleTextColor = if (typedArray.hasValue(R.styleable.SpeedometerView_subtitleTextColor))
                typedArray.getColor(R.styleable.SpeedometerView_subtitleTextColor, defaultText)
            else defaultText

            arcWidth = typedArray.getDimension(R.styleable.SpeedometerView_arcWidth, 30f)
            speedTextSize = typedArray.getDimension(R.styleable.SpeedometerView_speedTextSize, 60f)
            subtitleTextSize = typedArray.getDimension(R.styleable.SpeedometerView_subtitleTextSize, 30f)
        } finally {
            typedArray.recycle()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val centerX = width / 2f
        val centerY = height / 2f

        // To prevent clipping, the bounding box of the arc must be inset
        // by at least half the stroke width.
        val inset = arcWidth / 2f
        arcRect.set(paddingLeft + inset, paddingTop + inset, width - paddingRight - inset, height - paddingBottom - inset)

        // The arc should be circular, so we make the bounding box a square
        if (arcRect.width() > arcRect.height()) {
            val diff = arcRect.width() - arcRect.height()
            arcRect.left += diff / 2f
            arcRect.right -= diff / 2f
        } else {
            val diff = arcRect.height() - arcRect.width()
            arcRect.top += diff / 2f
            arcRect.bottom -= diff / 2f
        }

        // Draw the outer arc
        paint.color = arcColor
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = arcWidth
        paint.strokeCap = Paint.Cap.ROUND
        canvas.drawArc(arcRect, 150f, 240f, false, paint)

        // Draw the inner progress arc
        paint.color = progressColor
        paint.strokeCap = Paint.Cap.ROUND
        canvas.drawArc(arcRect, 150f, 100f, false, paint) // Example progress

        // Draw the speed text
        paint.color = speedTextColor
        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = speedTextSize
        canvas.drawText("0.0", centerX, centerY, paint)

        // Draw the subtitle text
        paint.color = subtitleTextColor
        paint.textSize = subtitleTextSize
        canvas.drawText("Mbit", centerX, centerY + subtitleTextSize * 1.5f, paint)
    }
}
