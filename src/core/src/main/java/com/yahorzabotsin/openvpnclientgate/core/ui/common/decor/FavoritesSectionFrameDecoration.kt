package com.yahorzabotsin.openvpnclientgate.core.ui.common.decor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
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

    override fun onDrawOver(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        val count = pinnedItemCount()
        if (count <= 0) return

        var top = Float.NaN
        var bottom = Float.NaN
        var left = Float.MAX_VALUE
        var right = Float.MIN_VALUE

        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            val position = parent.getChildAdapterPosition(child)
            if (position < 0 || position >= count) continue
            if (top.isNaN()) top = child.top.toFloat()
            bottom = child.bottom.toFloat()
            left = minOf(left, child.left.toFloat())
            right = maxOf(right, child.right.toFloat())
        }

        if (top.isNaN() || bottom.isNaN() || left >= right) return

        frameRect.set(left + inset, top + inset, right - inset, bottom - inset)
        canvas.drawRoundRect(frameRect, cornerRadius, cornerRadius, paint)
    }
}
