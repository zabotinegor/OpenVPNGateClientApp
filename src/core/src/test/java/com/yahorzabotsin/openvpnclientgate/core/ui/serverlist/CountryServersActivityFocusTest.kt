package com.yahorzabotsin.openvpnclientgate.core.ui.serverlist

import com.yahorzabotsin.openvpnclientgate.core.ui.common.utils.TvUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression test for DEF-sub03-header-misscroll-on-open.
 *
 * With >=1 favorite server in the current country, the pinned "Favorites" section header
 * occupies adapter position 0 and CountryServersViewModel emits FocusFirstItem(1) to skip
 * it. On a touch (non-TV) device the Activity must NOT act on that effect: calling
 * scrollToPosition(1) on open hides the section header, making the pinned favorite row
 * look like a caption-less duplicate of its regular-list twin. The list must stay at its
 * natural top position (no scroll at all) so the header stays visible. TV devices (D-pad)
 * keep the existing scroll-then-focus behavior.
 *
 * Exercises [TvUtils.applyFocusFirstItem] — the shared production handler of the
 * FocusFirstItem effect used by CountryServersActivity (extracted as a testable seam,
 * mirrors the ConnectionControlsView.resolveFocusTarget pattern). The full themed Activity cannot be
 * launched here because core unit tests run Robolectric in legacy resources mode, which
 * cannot resolve AppCompat/Material library theme resources.
 *
 * Re-verified against US-23 (lazy-loaded first page): [applyFocusFirstItem] only needs the
 * `position` the ViewModel computed from whatever items are currently loaded (position 0 or 1
 * depending on whether a pinned Favorites header exists) -- that computation is unchanged by
 * paging, since the first page is always non-empty by the time FocusFirstItem is emitted (see
 * CountryServersViewModelTest's `AC1 -` and `initialize loads servers and emits focus effect...`
 * cases, which now drive that emission through the paged getServersPage flow). This file's
 * coverage of the scroll/focus decoupling itself therefore still applies unchanged.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27])
class CountryServersActivityFocusTest {

    @Test
    fun `touch device - FocusFirstItem does not call scrollToPosition so pinned header stays visible`() {
        val scrollCalls = mutableListOf<Int>()
        val focusCalls = mutableListOf<Int>()

        // ViewModel emits FocusFirstItem(1) when a SectionHeader occupies position 0
        // (>=1 favorite in the current country).
        TvUtils.applyFocusFirstItem(
            isTvDevice = false,
            position = 1,
            scrollToPosition = { scrollCalls.add(it) },
            focusWhenReady = { focusCalls.add(it) }
        )

        assertTrue(
            "scrollToPosition must NOT be called on touch devices: it would hide the " +
                "pinned Favorites header at position 0 on open " +
                "(DEF-sub03-header-misscroll-on-open)",
            scrollCalls.isEmpty()
        )
        assertTrue(
            "No item focus request must be made on touch devices",
            focusCalls.isEmpty()
        )
    }

    @Test
    fun `tv device - FocusFirstItem scrolls to position 0 to keep header visible, then focuses the target row`() {
        val calls = mutableListOf<String>()

        TvUtils.applyFocusFirstItem(
            isTvDevice = true,
            position = 1,
            scrollToPosition = { calls.add("scroll:$it") },
            focusWhenReady = { calls.add("focus:$it") }
        )

        // Regression test for DEF-4-tv-list-misscroll-on-open: scrolling to `position` (1)
        // top-aligns the first row and pushes the pinned Favorites header (position 0) out
        // of view on TV. The list must always scroll to 0 first so the header stays fully
        // visible, while D-pad focus still lands on the target row (1).
        assertEquals(listOf("scroll:0", "focus:1"), calls)
    }

    @Test
    fun `tv device - no favorites header - FocusFirstItem scrolls and focuses position 0`() {
        val calls = mutableListOf<String>()

        // When there is no pinned Favorites header, the ViewModel emits FocusFirstItem(0)
        // and position 0 is already a regular row, so scroll and focus target the same
        // position - this case must remain unaffected by the DEF-4 fix.
        TvUtils.applyFocusFirstItem(
            isTvDevice = true,
            position = 0,
            scrollToPosition = { calls.add("scroll:$it") },
            focusWhenReady = { calls.add("focus:$it") }
        )

        assertEquals(listOf("scroll:0", "focus:0"), calls)
    }
}
