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
 * Draws a filled, rounded-rect card behind the pinned "Favorites" block (section header +
 * pinned rows) at the top of a country/server RecyclerView list. Replaces the
 * stroke-border framing (formerly `FavoritesSectionFrameDecoration`) with a filled
 * colorSurface-variant tone card (no stroke) and internal padding around the block, matching
 * the app's other "info card" surfaces.
 *
 * The card is purely a visual background painted in [onDraw] (drawn *before* item content, so
 * row/header backgrounds paint on top of it): it never touches item backgrounds, click/
 * long-click listeners, or layout, so existing row content, tap navigation, and long-press
 * favorite actions (PopupMenu / TV [FavoriteActionDialog]) are unaffected. It draws nothing
 * when [pinnedItemCount] returns 0 (section hidden, matching the existing show/hide behavior
 * from the Favorites section), and nothing when none of the pinned items are currently laid out (e.g.
 * scrolled out of view).
 *
 * @param pinnedItemCount supplies the number of leading adapter positions (0 until this
 * count, exclusive) that belong to the pinned block. Both [com.yahorzabotsin.openvpnclientgate.core.ui.serverlist.CountryListAdapter]
 * and [com.yahorzabotsin.openvpnclientgate.core.ui.serverlist.ServerPickerAdapter] expose
 * `pinnedSectionItemCount()` for this purpose. The second, non-pinned "All countries"/"All
 * servers" header inserted below the pinned block is never included in this count,
 * so the card never extends past the pinned Favorites rows.
 */
class FavoritesSectionCardDecoration(
    context: Context,
    private val pinnedItemCount: () -> Int
) : RecyclerView.ItemDecoration() {

    private val padding = context.resources.getDimension(R.dimen.favorites_section_card_padding)
    private val cornerRadius = context.resources.getDimension(R.dimen.favorites_section_card_corner_radius)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = MaterialColors.getColor(
            context,
            R.attr.ovpnFavoritesCardBackground,
            ContextCompat.getColor(context, R.color.favorites_card_background)
        )
    }
    private val cardRect = RectF()
    private val cardPath = Path()
    private val radii = FloatArray(8)

    override fun onDraw(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
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
            // Exclude the full-width header (position 0) from horizontal bounds so the card
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
            topChild.top + topChild.translationY - padding
        } else {
            -parent.height.toFloat()
        }
        val b = if (maxPosition == count - 1) {
            bottomChild.bottom + bottomChild.translationY + padding
        } else {
            2f * parent.height
        }
        if (t >= b) return

        val l = left - padding
        val r = right + padding

        val radiusTopLeft = if (minPosition == 0) cornerRadius else 0f
        val radiusTopRight = if (minPosition == 0) cornerRadius else 0f
        val radiusBottomRight = if (maxPosition == count - 1) cornerRadius else 0f
        val radiusBottomLeft = if (maxPosition == count - 1) cornerRadius else 0f

        cardRect.set(l, t, r, b)
        radii[0] = radiusTopLeft
        radii[1] = radiusTopLeft
        radii[2] = radiusTopRight
        radii[3] = radiusTopRight
        radii[4] = radiusBottomRight
        radii[5] = radiusBottomRight
        radii[6] = radiusBottomLeft
        radii[7] = radiusBottomLeft

        cardPath.reset()
        cardPath.addRoundRect(cardRect, radii, Path.Direction.CW)
        canvas.drawPath(cardPath, paint)
    }
}
