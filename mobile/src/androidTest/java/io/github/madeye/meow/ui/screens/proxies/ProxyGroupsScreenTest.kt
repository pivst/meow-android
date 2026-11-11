package io.github.madeye.meow.ui.screens.proxies

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import io.github.madeye.meow.bg.BaseService
import io.github.madeye.meow.ui.theme.MeowTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ProxyGroupsScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun staleListKeepsSelectionAndOffersRetry() {
        var retries = 0
        compose.setContent {
            MeowTheme {
                ProxyGroupsScreen(
                    state = ProxyGroupsUiState(state = BaseService.State.Connected, hasProfile = true,
                        loadFailed = true, groups = listOf(ProxyGroupUi("G", "Selector", "A", emptyList()))),
                    contentPadding = PaddingValues(0.dp), onToggleExpanded = {}, onSelectNode = { _, _ -> },
                    onTestGroup = {}, onOpenNode = { _, _ -> }, onRetry = { retries++ },
                )
            }
        }
        compose.onNodeWithText("G").assertIsDisplayed()
        compose.onNodeWithTag("proxy_groups_feedback").assertIsDisplayed()
        compose.onNodeWithText("Retry").performClick()
        assertEquals(1, retries)
    }

    @Test fun pendingGroupCannotSelectAndDoesNotClaimNewSelection() {
        var writes = 0
        compose.setContent {
            MeowTheme {
                ProxyGroupsScreen(
                    state = ProxyGroupsUiState(state = BaseService.State.Connected, hasProfile = true,
                        expandedGroup = "G", pendingGroups = setOf("G"), groups = listOf(
                            ProxyGroupUi("G", "Selector", "A", listOf(ProxyNodeUi("B", "Direct", NodeDelay.Untested, false))))),
                    contentPadding = PaddingValues(0.dp), onToggleExpanded = {}, onSelectNode = { _, _ -> writes++ },
                    onTestGroup = {}, onOpenNode = { _, _ -> },
                )
            }
        }
        compose.onNodeWithText("Selector · A").assertIsDisplayed()
        compose.onNodeWithText("B").assertIsNotEnabled()
        assertEquals(0, writes)
    }
}
