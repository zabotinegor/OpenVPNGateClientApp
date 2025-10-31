package com.yahorzabotsin.openvpnclient.core

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*

@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
    @Test
    fun useAppContext() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        // In androidTest, packageName may include ".test" — avoid hardcoding base
        assertTrue(appContext.packageName.contains(".core"))
    }
}
