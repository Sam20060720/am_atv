package dev.amatv.integration

import java.lang.reflect.InvocationTargetException
import org.junit.Assert.*
import org.junit.Test

class NativeContextPreparationTest {
    class Provider
    class ExplicitController {
        var call: Triple<Provider, Int, Boolean>? = null
        var legacyCalls = 0
        fun prepare(provider: Provider, insertion: Int, play: Boolean) { call = Triple(provider, insertion, play) }
        @Suppress("UNUSED_PARAMETER")
        fun prepare(provider: Provider, play: Boolean) { legacyCalls++ }
    }
    class LegacyController {
        var call: Pair<Provider, Boolean>? = null
        fun prepare(provider: Provider, play: Boolean) { call = provider to play }
    }
    class FailingController {
        var legacyCalls = 0
        @Suppress("UNUSED_PARAMETER")
        fun prepare(provider: Provider, insertion: Int, play: Boolean) { error("SDK rejected prepare") }
        @Suppress("UNUSED_PARAMETER")
        fun prepare(provider: Provider, play: Boolean) { legacyCalls++ }
    }

    @Test fun existingExplicitOverloadReplacesAllOldProvidersAndKeepsAudioSilent() {
        val provider = Provider()
        val controller = ExplicitController()
        prepareNewNativeContext(controller, provider)
        assertSame(provider, controller.call?.first)
        assertEquals(5, controller.call?.second)
        assertEquals(false, controller.call?.third)
        assertEquals(0, controller.legacyCalls)
    }

    @Test fun genuinelyOlderControllerCanUseItsOnlySilentPrepareOverload() {
        val provider = Provider()
        val controller = LegacyController()
        prepareNewNativeContext(controller, provider)
        assertSame(provider, controller.call?.first)
        assertEquals(false, controller.call?.second)
    }

    @Test fun failedExplicitPrepareIsNotRetriedThroughLegacyQueueSemantics() {
        val controller = FailingController()
        assertThrows(InvocationTargetException::class.java) { prepareNewNativeContext(controller, Provider()) }
        assertEquals(0, controller.legacyCalls)
    }
}
