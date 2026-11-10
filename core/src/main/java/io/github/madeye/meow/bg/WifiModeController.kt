package io.github.madeye.meow.bg

import io.github.madeye.meow.api.RouteMode
import io.github.madeye.meow.preference.WifiModeConfig
import io.github.madeye.meow.preference.WifiModeStatus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One session's serialized policy application. Failed changes never publish an active match. */
internal class WifiModeController(
    private val config: () -> WifiModeConfig,
    private val fallback: () -> RouteMode,
    private val apply: suspend (RouteMode) -> Unit,
) {
    private val lock = Mutex()
    @Volatile private var network = Pair<Boolean, String?>(false, null)
    @Volatile private var stopped = false
    @Volatile var status = WifiModeStatus()
        private set
    private var applied: RouteMode? = null

    @Synchronized fun network(wifi: Boolean, ssid: String?) {
        val next = wifi to ssid
        if (!stopped && network != next && status.state != "off") status = WifiModeStatus("changing")
        network = next
    }
    @Synchronized fun stop() { stopped = true; status = WifiModeStatus() }

    suspend fun refresh(): WifiModeStatus = lock.withLock {
        while (!stopped) {
            val settings = config()
            if (!settings.enabled && applied == null) {
                status = WifiModeStatus()
                break
            }
            val snapshot = network
            val (wifi, ssid) = snapshot
            val match = settings.match(wifi, ssid)
            val default = fallback()
            val desired = match?.let { RouteMode.fromWire(it.mode) } ?: default
            val next = when {
                !settings.enabled -> WifiModeStatus(mode = desired.wire)
                match != null -> WifiModeStatus("matched", match.ssid, desired.wire, default.wire)
                wifi && ssid == null -> WifiModeStatus("unavailable", mode = desired.wire)
                else -> WifiModeStatus("default", mode = desired.wire)
            }
            var failed = false
            try {
                if (desired != applied) { apply(desired); applied = desired }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (_: Exception) { failed = true }
            // Publish atomically with stop/network invalidation. A queued result
            // can never republish a matched SSID after stop cleared the status.
            val done = synchronized(this) {
                when {
                    stopped -> true
                    snapshot != network || settings != config() || default != fallback() -> false
                    else -> {
                        status = if (failed) WifiModeStatus("error") else next
                        if (!failed && !settings.enabled) applied = null
                        true
                    }
                }
            }
            if (done) break
        }
        status
    }
}
