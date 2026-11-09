package com.yahorzabotsin.openvpnclientgate.core.updates

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import org.junit.Assert.assertEquals
import org.junit.Test

class ReleaseOnDestroyObserverTest {
    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private fun resumedOwner() = Owner().apply {
        registry.currentState = Lifecycle.State.RESUMED
    }

    @Test
    fun `destroying the owner releases exactly once`() {
        val owner = resumedOwner()
        var releases = 0
        owner.registry.addObserver(ReleaseOnDestroyObserver { releases++ })

        owner.registry.currentState = Lifecycle.State.DESTROYED

        assertEquals(1, releases)
    }

    @Test
    fun `observer removed before destruction never releases`() {
        val owner = resumedOwner()
        var releases = 0
        val observer = ReleaseOnDestroyObserver { releases++ }
        owner.registry.addObserver(observer)
        owner.registry.removeObserver(observer)

        owner.registry.currentState = Lifecycle.State.DESTROYED

        assertEquals(0, releases)
    }

    @Test
    fun `owner leaves no observer behind after destruction`() {
        val owner = resumedOwner()
        owner.registry.addObserver(ReleaseOnDestroyObserver { })

        owner.registry.currentState = Lifecycle.State.DESTROYED

        assertEquals(0, owner.registry.observerCount)
    }
}
