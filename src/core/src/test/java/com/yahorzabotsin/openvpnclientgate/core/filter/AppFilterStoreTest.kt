package com.yahorzabotsin.openvpnclientgate.core.filter

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class AppFilterStoreTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("app_filter", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun loadExcludedPackages_filtersBlankEntries() {
        context.getSharedPreferences("app_filter", Context.MODE_PRIVATE)
            .edit()
            .putStringSet("excluded_packages", setOf("pkg.one", "", " ", "pkg.two"))
            .commit()

        val result = AppFilterStore.loadExcludedPackages(context)

        assertEquals(setOf("pkg.one", "pkg.two"), result)
    }

    @Test
    fun saveExcludedPackages_sanitizesAndPersists() {
        AppFilterStore.saveExcludedPackages(context, setOf("a", "", "b", " "))

        val result = AppFilterStore.loadExcludedPackages(context)

        assertEquals(setOf("a", "b"), result)
    }

    @Test
    fun updateExcludedPackages_appliesMutation() {
        AppFilterStore.saveExcludedPackages(context, setOf("one", "two"))

        val updated = AppFilterStore.updateExcludedPackages(context) { set ->
            set.remove("one")
            set.add("three")
        }

        assertEquals(setOf("two", "three"), updated)
        assertEquals(setOf("two", "three"), AppFilterStore.loadExcludedPackages(context))
    }
}
