package com.yahorzabotsin.openvpnclientgate.core.ui.serverlist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Page size must be derived from the device's real screen dimensions and the
 * server row's measured/laid-out height at runtime -- no hardcoded item-count constant.
 *
 * These tests exercise [ServerListPageSizeCalculator.computeFromMeasurements], the pure
 * arithmetic step of [ServerListPageSizeCalculator.compute] (real row height + real screen
 * height -> page size), against known pixel values -- a plain JUnit test, no Robolectric/View
 * inflation needed. The other half, [ServerListPageSizeCalculator.compute]'s LayoutInflater/
 * View.measure() call against the real Material-themed `item_server_row.xml`, is not exercised
 * here: core unit tests run Robolectric in legacy resources mode, which cannot resolve
 * AppCompat/Material theme attributes outside a themed Activity (see
 * `CountryServersActivityFocusTest`'s doc, and `ServerPickerAdapterTest`'s hand-built views,
 * for the same constraint elsewhere in this module). That measurement wiring is exercised by
 * running the real app (manual QA on phone and TV, per the story's own risk note that row
 * height is `wrap_content` and needs a genuine measurement step).
 */
class ServerListPageSizeCalculatorTest {

    @Test
    fun `a taller screen yields a larger page size for the same row height`() {
        val shortScreen = ServerListPageSizeCalculator.computeFromMeasurements(rowHeightPx = 80, screenHeightPx = 800)
        val tallScreen = ServerListPageSizeCalculator.computeFromMeasurements(rowHeightPx = 80, screenHeightPx = 3200)

        assertTrue(
            "a 4x taller screen must request more servers per page for the same row height",
            tallScreen > shortScreen
        )
    }

    @Test
    fun `a taller row yields a smaller page size for the same screen height`() {
        val shortRow = ServerListPageSizeCalculator.computeFromMeasurements(rowHeightPx = 60, screenHeightPx = 2400)
        val tallRow = ServerListPageSizeCalculator.computeFromMeasurements(rowHeightPx = 240, screenHeightPx = 2400)

        assertTrue(
            "rows that measure 4x taller must fit fewer per page",
            tallRow < shortRow
        )
    }

    @Test
    fun `page size scales to roughly a fixed number of screens worth of rows`() {
        // 2400px screen / 80px row = 30 rows visible on one screen; the computed page should
        // be a small whole-number multiple of that (implementation detail: "a few screens
        // ahead"), not an arbitrary/unrelated constant.
        val result = ServerListPageSizeCalculator.computeFromMeasurements(rowHeightPx = 80, screenHeightPx = 2400)

        assertEquals(0, result % 30)
        assertTrue("must request more than a single screen's worth", result > 30)
    }

    @Test
    fun `zero or negative row height falls back to a safe positive floor`() {
        assertTrue(ServerListPageSizeCalculator.computeFromMeasurements(rowHeightPx = 0, screenHeightPx = 2400) > 0)
        assertTrue(ServerListPageSizeCalculator.computeFromMeasurements(rowHeightPx = -10, screenHeightPx = 2400) > 0)
    }

    @Test
    fun `zero or negative screen height falls back to a safe positive floor`() {
        assertTrue(ServerListPageSizeCalculator.computeFromMeasurements(rowHeightPx = 80, screenHeightPx = 0) > 0)
        assertTrue(ServerListPageSizeCalculator.computeFromMeasurements(rowHeightPx = 80, screenHeightPx = -1) > 0)
    }

    @Test
    fun `result is never zero or negative across a range of plausible phone and TV dimensions`() {
        val rowHeights = listOf(48, 64, 80, 96, 120)
        val screenHeights = listOf(1280, 1920, 2400, 2960, 3840)
        for (row in rowHeights) {
            for (screen in screenHeights) {
                val result = ServerListPageSizeCalculator.computeFromMeasurements(row, screen)
                assertTrue("row=$row screen=$screen must yield a positive page size, was $result", result > 0)
            }
        }
    }
}
