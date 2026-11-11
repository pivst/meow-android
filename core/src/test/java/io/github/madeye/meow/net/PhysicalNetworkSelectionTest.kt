package io.github.madeye.meow.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhysicalNetworkSelectionTest {
    @Test fun unrelatedCellularLossDoesNotResetWifi() {
        val state = PhysicalNetworkSelection<String>()
        assertEquals("wifi", state.available("wifi", "wifi")?.network)
        assertNull(state.available("cellular", "wifi"))
        assertNull(state.lost("cellular", "wifi"))
        assertNull(state.available("wifi", "wifi"))
    }

    @Test fun physicalDefaultSwitchesOnceAndOldLossIsIgnored() {
        val state = PhysicalNetworkSelection<String>()
        assertEquals("cellular", state.available("cellular", "cellular")?.network)
        assertEquals("wifi", state.available("wifi", "wifi")?.network)
        assertNull(state.lost("cellular", "wifi"))
        assertNull(state.available("wifi", "wifi"))
    }

    @Test fun vpnDefaultRetainsPhysicalSelectionAndSelectedLossFallsBack() {
        val state = PhysicalNetworkSelection<String>()
        // null means activeNetwork was the VPN, not a physical preference.
        assertEquals("wifi", state.available("wifi", null)?.network)
        assertNull(state.available("cellular", null))
        assertEquals("cellular", state.lost("wifi", null)?.network)
        assertEquals(PhysicalNetworkSelection.Change<String>(null), state.lost("cellular", null))
        assertNull(state.lost("wifi", null))
    }

    @Test fun bestPhysicalWifiReplacesAvailableCellularWhileVpnIsDefault() {
        val state = PhysicalNetworkSelection<String>()
        // API 31+ NOT_VPN best-match callbacks do not depend on activeNetwork.
        assertEquals("cellular", state.bestAvailable("cellular")?.network)
        assertNull(state.capabilitiesChanged("cellular", null))
        assertEquals("wifi", state.bestAvailable("wifi")?.network)
        assertNull(state.bestAvailable("wifi"))
        assertNull(state.lost("cellular", null))
        assertNull(state.capabilitiesChanged("cellular", null))
        assertNull(state.capabilitiesChanged("wifi", null))
        assertEquals(PhysicalNetworkSelection.Change<String>(null), state.lost("wifi", null))
        // A superseded cellular entry is not resurrected. The platform reports
        // a new best if cellular is still suitable.
        assertEquals("cellular", state.bestAvailable("cellular")?.network)
    }

    @Test fun legacyCapabilitiesRecheckPhysicalDefaultWithoutDuplicateReset() {
        val state = PhysicalNetworkSelection<String>()
        assertEquals("cellular", state.available("cellular", null)?.network)
        assertNull(state.available("wifi", null))
        assertEquals("wifi", state.capabilitiesChanged("wifi", "wifi")?.network)
        assertNull(state.capabilitiesChanged("wifi", "wifi"))
        assertNull(state.capabilitiesChanged("unknown", "cellular"))
        assertNull(state.capabilitiesChanged("cellular", null))
    }
    @Test fun legacyCallbackCanSelectAnotherAlreadyAvailablePhysicalDefault() {
        val state = PhysicalNetworkSelection<String>()
        assertEquals("cellular", state.available("cellular", null)?.network)
        assertNull(state.available("wifi", null))
        assertEquals("wifi", state.capabilitiesChanged("cellular", "wifi")?.network)
        assertNull(state.lost("cellular", "wifi"))
    }
}
