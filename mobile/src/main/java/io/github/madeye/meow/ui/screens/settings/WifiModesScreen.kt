package io.github.madeye.meow.ui.screens.settings

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.madeye.meow.AppGraph
import io.github.madeye.meow.R
import io.github.madeye.meow.api.RouteMode
import io.github.madeye.meow.bg.WifiIdentity
import io.github.madeye.meow.preference.WifiModeConfig
import io.github.madeye.meow.preference.WifiModeRule
import io.github.madeye.meow.preference.WifiModeStore
import io.github.madeye.meow.ui.components.MeowScaffold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun WifiModesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var config by remember { mutableStateOf<WifiModeConfig?>(null) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var permitted by remember { mutableStateOf(WifiIdentity.permitted(context)) }
    var disclosure by rememberSaveable { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var original by rememberSaveable { mutableStateOf<String?>(null) }
    var name by rememberSaveable { mutableStateOf("") }
    var mode by rememberSaveable { mutableStateOf("rule") }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(Unit) { config = withContext(Dispatchers.IO) { WifiModeStore.default.load() } }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permitted = WifiIdentity.permitted(context)
                scope.launch { withContext(Dispatchers.IO) { AppGraph.vpn.wifiModeStatus(refresh = true) } }
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    fun save(next: WifiModeConfig) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    WifiModeStore.default.save(next)
                    AppGraph.vpn.wifiModeStatus(refresh = true)
                }
                config = next
                failed = false
            } catch (_: java.io.IOException) { failed = true
            } finally { busy = false }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permitted = WifiIdentity.permitted(context)
    }
    MeowScaffold(
        title = stringResource(R.string.wifi_modes_title),
        navigationIcon = { IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.wifi_back))
        } },
    ) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.wifi_modes_description))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.wifi_modes_enable), modifier = Modifier.weight(1f))
                Switch(checked = config?.enabled == true, enabled = config != null && !busy,
                    onCheckedChange = { enabled ->
                        if (enabled && !permitted) disclosure = true
                        else config?.let { save(it.copy(enabled = enabled)) }
                    })
            }
            if (config?.enabled == true && !permitted) {
                Text(stringResource(R.string.wifi_permission_help))
                TextButton(onClick = { disclosure = true }) { Text(stringResource(R.string.wifi_permissions)) }
                TextButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                }) { Text(stringResource(R.string.wifi_app_settings)) }
                TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }) {
                    Text(stringResource(R.string.wifi_location_settings))
                }
            }
            if (failed) Text(stringResource(R.string.wifi_save_failed), color = MaterialTheme.colorScheme.error)
            config?.rules?.forEach { rule ->
                Column(Modifier.fillMaxWidth()) {
                    HorizontalDivider()
                    Column(Modifier.padding(vertical = 12.dp)) {
                        Text(rule.ssid, style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(modeLabel(rule.mode)))
                        Row {
                            TextButton(enabled = !busy, onClick = {
                                original = rule.ssid; name = rule.ssid; mode = rule.mode; editing = true
                            }) { Text(stringResource(R.string.wifi_edit)) }
                            TextButton(enabled = !busy, onClick = {
                                config?.let { save(it.copy(rules = it.rules.filterNot { item -> item.ssid == rule.ssid })) }
                            }) { Text(stringResource(R.string.wifi_remove)) }
                        }
                    }
                }
            }
            Button(enabled = config != null && !busy && (config?.rules?.size ?: 64) < 64, onClick = {
                original = null; name = ""; mode = "rule"; editing = true
            }) { Text(stringResource(R.string.wifi_add)) }
        }
    }
    if (disclosure) AlertDialog(
        onDismissRequest = { disclosure = false },
        title = { Text(stringResource(R.string.wifi_permissions)) },
        text = { Text(stringResource(R.string.wifi_permission_disclosure)) },
        confirmButton = { TextButton(onClick = {
            disclosure = false
            config?.let { save(it.copy(enabled = true)) }
            permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }) { Text(stringResource(R.string.wifi_continue)) } },
        dismissButton = { TextButton(onClick = { disclosure = false }) { Text(stringResource(R.string.wifi_cancel)) } },
    )
    if (editing) {
        val rule = WifiModeRule(name, mode)
        val valid = rule.valid() && config?.rules?.none { it.ssid == name && it.ssid != original } == true
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text(stringResource(R.string.wifi_add)) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true,
                    label = { Text(stringResource(R.string.wifi_name)) },
                    isError = name.isNotEmpty() && !valid,
                    supportingText = { Text(stringResource(R.string.wifi_name_help)) })
                RouteMode.entries.forEach { entry ->
                    TextButton(onClick = { mode = entry.wire }) {
                        RadioButton(selected = mode == entry.wire, onClick = null)
                        Text(stringResource(modeLabel(entry.wire)))
                    }
                }
            } },
            confirmButton = { TextButton(enabled = valid && !busy, onClick = {
                config?.let { save(it.copy(rules = it.rules.filterNot { item -> item.ssid == original } + rule)) }
                editing = false
            }) { Text(stringResource(R.string.wifi_save)) } },
            dismissButton = { TextButton(onClick = { editing = false }) { Text(stringResource(R.string.wifi_cancel)) } },
        )
    }
}

private fun modeLabel(mode: String): Int = when (mode) {
    "direct" -> R.string.route_mode_direct
    "global" -> R.string.route_mode_global
    else -> R.string.route_mode_rule
}
