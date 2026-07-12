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

    override fun onDrawOver(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        val count = pinnedItemCount()
        if (count <= 0) return

        var minTop = Float.MAX_VALUE
        var maxBottom = Float.MIN_VALUE
        var left = Float.MAX_VALUE
        var right = Float.MIN_VALUE
        var minPosition = Int.MAX_VALUE
        var maxPosition = Int.MIN_VALUE

        // Find children within the pinned range and track min/max positions explicitly
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            val position = parent.getChildAdapterPosition(child)
            if (position < 0 || position >= count) continue

            minPosition = minOf(minPosition, position)
            maxPosition = maxOf(maxPosition, position)

            // Account for child view translations (animation/scroll)
            val childTop = child.top + child.translationY
            val childBottom = child.bottom + child.translationY
            val childLeft = child.left + child.translationX
            val childRight = child.right + child.translationX

            minTop = minOf(minTop, childTop)
            maxBottom = maxOf(maxBottom, childBottom)
            left = minOf(left, childLeft)
            right = maxOf(right, childRight)
        }

        if (minTop >= maxBottom || left >= right || minPosition == Int.MAX_VALUE) return

        val l = left + inset
        val t = minTop + inset
        val r = right - inset
        val b = maxBottom - inset

        // Handle off-screen clipping: only round corners at actual section boundaries
        // If a boundary is scrolled off-screen, use 0 radius for that corner
        val radiusTopLeft = if (minPosition == 0) cornerRadius else 0f
        val radiusTopRight = if (minPosition == 0) cornerRadius else 0f
        val radiusBottomRight = if (maxPosition == count - 1) cornerRadius else 0f
        val radiusBottomLeft = if (maxPosition == count - 1) cornerRadius else 0f

        // Use Path to support per-corner radii
        framePath.reset()
        framePath.addRoundRect(RectF(l, t, r, b), floatArrayOf(
            radiusTopLeft, radiusTopLeft,
            radiusTopRight, radiusTopRight,
            radiusBottomRight, radiusBottomRight,
            radiusBottomLeft, radiusBottomLeft
        ), Path.Direction.CW)

        canvas.drawPath(framePath, paint)
    }
}
