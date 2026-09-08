package com.yahorzabotsin.openvpnclientgate.core.ui.serverlist

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import kotlin.math.ceil

/**
 * Computes how many servers to request per lazy-loaded page on the country servers screen,
 * derived at runtime from the device's actual screen dimensions and the server
 * row's real measured/laid-out height -- there is no hardcoded item-count constant driving the
 * page size itself. Requests enough rows to fill a few screens ahead so scrolling feels
 * seamless well before the next network fetch is needed, and the result scales naturally with
 * device (a TV's larger screen / different row metrics yields a different page size than a
 * phone's, without any per-form-factor branching).
 *
 * Extracted as a standalone, Robolectric-testable seam (mirrors [com.yahorzabotsin.openvpnclientgate.core.ui.common.utils.TvUtils])
 * so [CountryServersActivity] only has to call [compute] with real layout inputs.
 */
object ServerListPageSizeCalculator {

    /**
     * Screens' worth of rows requested per page. A ratio, not an item count: multiplied by
     * however many rows the *measured* row height actually fits on the *measured* screen
     * height, so the resulting page size still comes entirely from real device/layout
     * measurements, not a fixed number of servers.
     */
    private const val SCREENS_PER_PAGE = 3

    /**
     * Defensive floor only, not the page size in normal operation: guards the degenerate case
     * where row/screen measurement yields zero or a negative height (inflate failure, unusual
     * theming) so a page request can never collapse to 0 items and stall pagination.
     */
    private const val MIN_PAGE_SIZE = 10

    /**
     * @param parent the RecyclerView (or another attached ViewGroup) used only as an inflation/
     * measurement context for the sample row -- never mutated.
     * @param rowLayoutResId layout resource of a single server row (`R.layout.item_server_row`).
     * @param screenHeightPx device screen height in pixels (`resources.displayMetrics.heightPixels`).
     * @param screenWidthPx device screen width in pixels, used to measure the row at a realistic
     * width when [parent] has not yet been laid out (width == 0, e.g. called from `onCreate`).
     */
    fun compute(
        parent: ViewGroup,
        rowLayoutResId: Int,
        screenHeightPx: Int,
        screenWidthPx: Int
    ): Int {
        val rowHeightPx = measureRowHeightPx(parent, rowLayoutResId, screenWidthPx)
        return computeFromMeasurements(rowHeightPx, screenHeightPx)
    }

    /**
     * The pure arithmetic step (real screen height / real measured row height -> page size),
     * split out from [compute] so it can be unit tested directly against known pixel values
     * without needing to inflate a real View: this app's row layout is Material-themed and
     * cannot be inflated outside a themed Activity in core unit tests' legacy Robolectric
     * resources mode (see `ServerListPageSizeCalculatorTest`'s doc for the full rationale).
     * `internal` rather than `private` purely to allow that direct test access.
     */
    internal fun computeFromMeasurements(rowHeightPx: Int, screenHeightPx: Int): Int {
        if (rowHeightPx <= 0 || screenHeightPx <= 0) return MIN_PAGE_SIZE
        val rowsPerScreen = ceil(screenHeightPx.toDouble() / rowHeightPx).toInt().coerceAtLeast(1)
        return (rowsPerScreen * SCREENS_PER_PAGE).coerceAtLeast(MIN_PAGE_SIZE)
    }

    private fun measureRowHeightPx(parent: ViewGroup, rowLayoutResId: Int, screenWidthPx: Int): Int {
        val sample = LayoutInflater.from(parent.context).inflate(rowLayoutResId, parent, false)
        val measureWidth = if (parent.width > 0) parent.width else screenWidthPx
        val widthSpec = View.MeasureSpec.makeMeasureSpec(measureWidth, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        sample.measure(widthSpec, heightSpec)
        return sample.measuredHeight
    }
}
