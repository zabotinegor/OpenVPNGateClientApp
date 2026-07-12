package com.yahorzabotsin.openvpnclientgate.core.ui.common.decor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.yahorzabotsin.openvpnclientgate.core.R

/**
 * Draws a single rounded-rect border/frame around the pinned "Favorites" block (section
 * header + pinned rows) at the top of a country/server RecyclerView list (SUB-06).
 *
 * The frame is purely a visual overlay drawn in [onDrawOver]: it never touches item
 * backgrounds, click/long-click listeners, or layout, so existing row content, tap
 * navigation, and long-press favorite actions (PopupMenu / TV [FavoriteActionDialog]) are
 * unaffected. It draws nothing when [pinnedItemCount] returns 0 (section hidden, matching
 * the existing show/hide behavior from SUB-02/SUB-03), and nothing when none of the pinned
 * items are currently laid out (e.g. scrolled out of view).
 *
 * @param pinnedItemCount supplies the number of leading adapter positions (0 until this
 * count, exclusive) that belong to the pinned block. Both [com.yahorzabotsin.openvpnclientgate.core.ui.serverlist.CountryListAdapter]
 * and [com.yahorzabotsin.openvpnclientgate.core.ui.serverlist.ServerPickerAdapter] expose
 * `pinnedSectionItemCount()` for this purpose.
 */
class FavoritesSectionFrameDecoration(
    context: Context,
    private val pinnedItemCount: () -> Int
) : RecyclerView.ItemDecoration() {

    private val inset = context.resources.getDimension(R.dimen.favorites_section_frame_inset)
    private val cornerRadius = context.resources.getDimension(R.dimen.favorites_section_frame_corner_radius)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.resources.getDimension(R.dimen.favorites_section_frame_stroke_width)
        color = MaterialColors.getColor(
            context,
            com.google.android.material.R.attr.colorSecondary,
            ContextCompat.getColor(context, R.color.theme_color_secondary)
        )
    }
    private val frameRect = RectF()
    private val framePath = Path()
    private val radii = FloatArray(8)

    override fun onDrawOver(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        val count = pinnedItemCount()
        if (count <= 0) return

        var minPosition = Int.MAX_VALUE
        var maxPosition = Int.MIN_VALUE
        var topChild: android.view.View? = null
        var bottomChild: android.view.View? = null
        var left = Float.MAX_VALUE
        var right = Float.NEGATIVE_INFINITY

        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            val position = parent.getChildAdapterPosition(child)
            if (position < 0 || position >= count) continue

            if (position < minPosition) {
                minPosition = position
                topChild = child
            }
            if (position > maxPosition) {
                maxPosition = position
                bottomChild = child
            }
            // Exclude the full-width header (position 0) from horizontal bounds so the frame
            // hugs the narrower row cards instead of stretching edge-to-edge.
            if (position > 0) {
                val childLeft = child.left + child.translationX
                val childRight = child.right + child.translationX
                left = minOf(left, childLeft)
                right = maxOf(right, childRight)
            }
        }

        if (topChild == null || bottomChild == null || left >= right) return

        // Extend top/bottom edges beyond the RecyclerView's own bounds (naturally clipped by
        // the canvas) whenever the true section boundary is scrolled off-screen, instead of
        // drawing a false closing edge at whatever child happens to be first/last visible.
        val t = if (minPosition == 0) {
            topChild.top + topChild.translationY + inset
        } else {
            -parent.height.toFloat()
        }
        val b = if (maxPosition == count - 1) {
            bottomChild.bottom + bottomChild.translationY - inset
        } else {
            2f * parent.height
        }
        if (t >= b) return

        val l = left + inset
        val r = right - inset

        val radiusTopLeft = if (minPosition == 0) cornerRadius else 0f
        val radiusTopRight = if (minPosition == 0) cornerRadius else 0f
        val radiusBottomRight = if (maxPosition == count - 1) cornerRadius else 0f
        val radiusBottomLeft = if (maxPosition == count - 1) cornerRadius else 0f

        frameRect.set(l, t, r, b)
        radii[0] = radiusTopLeft
        radii[1] = radiusTopLeft
        radii[2] = radiusTopRight
        radii[3] = radiusTopRight
        radii[4] = radiusBottomRight
        radii[5] = radiusBottomRight
        radii[6] = radiusBottomLeft
        radii[7] = radiusBottomLeft

        framePath.reset()
        framePath.addRoundRect(frameRect, radii, Path.Direction.CW)
        canvas.drawPath(framePath, paint)
    }
}
