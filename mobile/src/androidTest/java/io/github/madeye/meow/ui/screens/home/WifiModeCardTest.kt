package io.github.madeye.meow.ui.screens.home

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.github.madeye.meow.api.RouteMode
import io.github.madeye.meow.bg.BaseService
import io.github.madeye.meow.preference.WifiModeStatus
import io.github.madeye.meow.ui.components.MeowScaffold
import io.github.madeye.meow.ui.nav.TABS
import io.github.madeye.meow.ui.theme.MeowTheme
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class WifiModeCardTest {
    @get:Rule val compose = createComposeRule()

    @Test fun matched_rule_is_visible_manage_opens_and_manual_modes_are_disabled() {
        var managed = false
        var wifi by mutableStateOf(WifiModeStatus("matched", "家庭 Wi-Fi", "direct", "rule"))
        compose.setContent {
            val originalContext = androidx.compose.ui.platform.LocalContext.current
            val configuration = android.content.res.Configuration(androidx.compose.ui.platform.LocalConfiguration.current).apply {
                setLocale(java.util.Locale.SIMPLIFIED_CHINESE)
            }
            val localized = originalContext.createConfigurationContext(configuration)
            CompositionLocalProvider(androidx.compose.ui.platform.LocalContext provides localized,
                androidx.compose.ui.platform.LocalConfiguration provides configuration) {
            MeowTheme(darkTheme = true) {
                MeowScaffold(title = "Meow", bottomBar = {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) { TABS.forEachIndexed { index, tab ->
                        NavigationBarItem(selected = index == 0, onClick = {},
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                selectedTextColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            ),
                            icon = { Icon(tab.icon, null) }, label = { Text(stringResource(tab.label)) })
                    } }
                }) { padding ->
                    HomeScreen(
                        state = HomeUiState(state = BaseService.State.Connected, profileName = "日常配置",
                            hasProfile = true, profileLoaded = true, routeMode = RouteMode.fromWire(wifi.mode.orEmpty()) ?: RouteMode.Rule, wifiMode = wifi),
                        contentPadding = padding, onToggle = {}, onSelectRouteMode = {},
                        onWifiModes = { managed = true }, exitIp = ExitIpUiState.Hidden,
                        exitNat = NatUiState.Unavailable, onRefreshExitIp = {}, onOpenSubscriptions = {}, onRules = {},
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        }
        compose.onNodeWithTag("wifi_mode_status").assertIsDisplayed()
        compose.onNodeWithTag("route_mode_direct").assertIsSelected().assertIsNotEnabled()
        compose.onNodeWithTag("route_mode_rule").assertIsNotEnabled()
        capture("wifi-home-matched.png")
        compose.runOnIdle { wifi = wifi.copy(ssid = "Home-WiFi-123456789012345678901234") }
        compose.waitForIdle()
        capture("wifi-home-long-name.png")
        compose.runOnIdle { wifi = WifiModeStatus("unavailable", mode = "rule") }
        compose.waitForIdle()
        compose.onNodeWithText("家庭 Wi-Fi", substring = true).assertDoesNotExist()
        capture("wifi-home-unavailable.png")
        compose.onNodeWithTag("wifi_mode_status").performClick()
        compose.runOnIdle { assertTrue(managed) }
    }

    @Test fun settings_adds_a_rule_and_validates_duplicate_names() {
        val store = io.github.madeye.meow.preference.WifiModeStore.default
        store.save(io.github.madeye.meow.preference.WifiModeConfig())
        compose.setContent {
            MeowTheme(darkTheme = true) {
                io.github.madeye.meow.ui.screens.settings.WifiModesScreen(onBack = {})
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Add Wi-Fi rule") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Add Wi-Fi rule").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onNodeWithText("Wi-Fi name (SSID)").performTextInput("家庭 Wi-Fi")
        compose.onNodeWithText("Direct").performClick()
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(5_000) { store.load().rules.size == 1 }
        compose.waitForIdle()
        capture("wifi-settings.png")
        compose.onNodeWithText("Add Wi-Fi rule").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Wi-Fi name (SSID)").performTextInput("家庭 Wi-Fi")
        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Edit").performClick()
        compose.onNodeWithText("Wi-Fi name (SSID)").performTextReplacement("Office")
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(5_000) { store.load().rules.singleOrNull()?.ssid == "Office" }
        compose.onNodeWithText("Remove").performClick()
        compose.waitUntil(5_000) { store.load().rules.isEmpty() }
    }

    private fun capture(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.getExternalFilesDir(null), name)
        file.outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
