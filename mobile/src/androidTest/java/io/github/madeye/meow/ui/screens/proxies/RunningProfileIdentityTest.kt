package io.github.madeye.meow.ui.screens.proxies

import io.github.madeye.meow.aidl.TrafficStats
import io.github.madeye.meow.bg.BaseService
import io.github.madeye.meow.vpn.VpnStateRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test

class RunningProfileIdentityTest {
    @Test fun actualServiceIdentityIsUnknownUntilReportedAndClearedAtStop() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val vpn = VpnStateRepository(scope = scope)
            vpn.stateChanged(BaseService.State.Connected, "same display name", null)
            assertNull(vpn.runningProfileId.value)
            vpn.trafficUpdated(11, TrafficStats())
            assertEquals(11L, vpn.runningProfileId.value)
            vpn.stateChanged(BaseService.State.Stopped, "same display name", null)
            assertNull(vpn.runningProfileId.value)
            vpn.trafficUpdated(11, TrafficStats())
            assertNull(vpn.runningProfileId.value)
            vpn.stateChanged(BaseService.State.Connected, "same display name", null)
            assertNull(vpn.runningProfileId.value)
            vpn.trafficUpdated(12, TrafficStats())
            assertEquals(12L, vpn.runningProfileId.value)
        } finally { scope.cancel() }
    }
}
