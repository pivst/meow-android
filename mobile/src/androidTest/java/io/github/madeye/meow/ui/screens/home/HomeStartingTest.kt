package io.github.madeye.meow.ui.screens.home

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.madeye.meow.bg.BaseService
import io.github.madeye.meow.ui.theme.MeowTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class HomeStartingTest {
    @get:Rule val compose = createComposeRule()

    @Test fun connectingOffersRealCancelWithoutRequestingReconnect() {
        val toggles = mutableListOf<Boolean>()
        compose.setContent {
            MeowTheme { StatusCard(HomeUiState(state = BaseService.State.Connecting, hasProfile = true), toggles::add) }
        }
        compose.onNodeWithTag("vpn_cancel").assertIsEnabled().performClick()
        assertEquals(listOf(false), toggles)
    }

    @Test fun stoppingCannotStartAnotherConnection() {
        compose.setContent {
            MeowTheme { StatusCard(HomeUiState(state = BaseService.State.Stopping, hasProfile = true), {}) }
        }
        compose.onNodeWithTag("vpn_switch").assertDoesNotExist()
        compose.onNodeWithTag("vpn_cancel").assertDoesNotExist()
    }
}
