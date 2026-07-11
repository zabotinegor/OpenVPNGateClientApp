package com.yahorzabotsin.openvpnclientgate.core.ui.serverlist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression test for DEF-sub05-serverlist-header-misscroll-on-open.
 *
 * With >=1 favorite country persisted, the pinned "Favorites" section header occupies
 * adapter position 0 and ServerListViewModel emits FocusFirstItem(1) to skip it. On a
 * touch (non-TV) device the Activity must NOT act on that effect: calling
 * scrollToPosition(1) on open hides the section header, making the pinned favorite row
 * look like a caption-less duplicate of its regular-list twin. The list must stay at its
 * natural top position (no scroll at all) so the header stays visible. TV devices (D-pad)
 * keep the existing scroll-then-focus behavior.
 *
 * Exercises [ServerListActivity.applyFocusFirstItem] — the production handler of the
 * FocusFirstItem effect, extracted as a testable seam (mirrors
 * CountryServersActivity.applyFocusFirstItem, the DEF-sub03-header-misscroll-on-open fix).
 * The full themed Activity cannot be launched here because core unit tests run Robolectric
 * in legacy resources mode, which cannot resolve AppCompat/Material library theme resources.
 */
class ServerListActivityFocusTest {

    @Test
    fun `touch device - FocusFirstItem does not call scrollToPosition so pinned header stays visible`() {
        val scrollCalls = mutableListOf<Int>()
        val focusCalls = mutableListOf<Int>()

        // ViewModel emits FocusFirstItem(1) when a SectionHeader occupies position 0
        // (>=1 favorite country persisted).
        ServerListActivity.applyFocusFirstItem(
            isTvDevice = false,
            position = 1,
            scrollToPosition = { scrollCalls.add(it) },
            focusWhenReady = { focusCalls.add(it) }
        )

        assertTrue(
            "scrollToPosition must NOT be called on touch devices: it would hide the " +
                "pinned Favorites header at position 0 on open " +
                "(DEF-sub05-serverlist-header-misscroll-on-open)",
            scrollCalls.isEmpty()
        )
        assertTrue(
            "No item focus request must be made on touch devices",
            focusCalls.isEmpty()
        )
    }

    @Test
    fun `tv device - FocusFirstItem scrolls to the target row and then requests focus`() {
        val calls = mutableListOf<String>()

        ServerListActivity.applyFocusFirstItem(
            isTvDevice = true,
            position = 1,
            scrollToPosition = { calls.add("scroll:$it") },
            focusWhenReady = { calls.add("focus:$it") }
        )

        // Scroll must happen before the focus attempt: findViewHolderForAdapterPosition
        // returns null for positions RecyclerView hasn't bound yet.
        assertEquals(listOf("scroll:1", "focus:1"), calls)
    }
}
