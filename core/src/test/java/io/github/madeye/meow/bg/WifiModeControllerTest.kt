package io.github.madeye.meow.bg

import io.github.madeye.meow.api.RouteMode
import io.github.madeye.meow.preference.WifiModeConfig
import io.github.madeye.meow.preference.WifiModeRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class WifiModeControllerTest {
    private val settings = WifiModeConfig(true, listOf(WifiModeRule("Home", "direct")))

    @Test fun `only current physical wifi matches and leaving restores default`() = runBlocking {
        val applied = mutableListOf<RouteMode>()
        val c = WifiModeController({ settings }, { RouteMode.Rule }) { applied += it }
        c.network(true, "Home")
        assertEquals("matched", c.refresh().state)
        assertEquals("Home", c.status.ssid)
        c.network(false, "Home")
        assertEquals("default", c.refresh().state)
        assertNull(c.status.ssid)
        assertEquals(listOf(RouteMode.Direct, RouteMode.Rule), applied)
        c.network(true, null)
        assertEquals("unavailable", c.refresh().state)
        assertEquals(2, applied.size)
    }

    @Test fun `handover during suspended apply does not publish stale match`() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val resume = CompletableDeferred<Unit>()
        val applied = mutableListOf<RouteMode>()
        val c = WifiModeController({ settings }, { RouteMode.Rule }) {
            applied += it
            if (it == RouteMode.Direct) { entered.complete(Unit); resume.await() }
        }
        c.network(true, "Home")
        val job = launch { c.refresh() }
        entered.await()
        c.network(false, null)
        resume.complete(Unit); job.join()
        assertEquals("default", c.status.state)
        assertEquals(listOf(RouteMode.Direct, RouteMode.Rule), applied)
    }

    @Test fun `failed application is visible and retry succeeds`() = runBlocking {
        var fail = true
        val c = WifiModeController({ settings }, { RouteMode.Rule }) { if (fail) error("refused") }
        c.network(true, "Home")
        assertEquals("error", c.refresh().state)
        assertNull(c.status.ssid)
        fail = false
        assertEquals("matched", c.refresh().state)
    }

    @Test fun `disable restores current manual default and stop prevents publication`() = runBlocking {
        var config = settings; var default = RouteMode.Rule
        val applied = mutableListOf<RouteMode>()
        val c = WifiModeController({ config }, { default }) { applied += it }
        c.network(true, "Home"); c.refresh()
        default = RouteMode.Global
        c.refresh()
        assertEquals(listOf(RouteMode.Direct), applied)
        config = settings.copy(enabled = false)
        assertEquals("off", c.refresh().state)
        assertEquals(RouteMode.Global, applied.last())
        c.stop(); c.network(true, "Home"); config = settings; c.refresh()
        assertEquals(2, applied.size)
        assertEquals("off", c.status.state)
    }

    @Test fun `stop during apply never republishes an active match`() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val resume = CompletableDeferred<Unit>()
        val c = WifiModeController({ settings }, { RouteMode.Rule }) { entered.complete(Unit); resume.await() }
        c.network(true, "Home")
        val job = launch { c.refresh() }
        entered.await(); c.stop(); resume.complete(Unit); job.join()
        assertEquals("off", c.status.state)
        assertNull(c.status.ssid)
    }
}
