package io.github.madeye.meow.preference

import io.github.madeye.meow.Core
import io.github.madeye.meow.api.RouteMode
import java.io.File
import java.io.IOException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class WifiModeRule(val ssid: String, val mode: String) {
    fun valid() = ssid.isNotEmpty() && ssid.toByteArray(Charsets.UTF_8).size <= 32 &&
        ssid != "<unknown ssid>" && RouteMode.entries.any { it.wire == mode }
}

@Serializable
data class WifiModeConfig(val enabled: Boolean = false, val rules: List<WifiModeRule> = emptyList()) {
    fun valid() = rules.size <= 64 && rules.all { it.valid() } && rules.map { it.ssid }.distinct().size == rules.size
    fun match(wifi: Boolean, ssid: String?): WifiModeRule? =
        if (enabled && wifi && ssid != null) rules.firstOrNull { it.ssid == ssid } else null
}

/** Atomic, fresh cross-process reads; names are never sent to the controller or logs. */
class WifiModeStore(private val file: File) {
    fun load(): WifiModeConfig = try {
        if (file.length() > 32768) WifiModeConfig() else
            Json.decodeFromString<WifiModeConfig>(file.readText()).takeIf { it.valid() } ?: WifiModeConfig()
    } catch (_: Exception) { WifiModeConfig() }

    fun save(config: WifiModeConfig) {
        require(config.valid())
        val tmp = File.createTempFile("wifi_modes", ".tmp", file.parentFile)
        try {
            tmp.writeText(Json.encodeToString(config))
            if (!tmp.renameTo(file)) throw IOException("Cannot save Wi-Fi modes")
        } finally { tmp.delete() }
    }
    companion object {
        val default by lazy { WifiModeStore(File(Core.deviceStorage.noBackupFilesDir, "wifi_modes.json")) }
    }
}

/** Public UI status contains only the current match, never a history. */
@Serializable
data class WifiModeStatus(
    val state: String = "off",
    val ssid: String? = null,
    val mode: String? = null,
    val defaultMode: String? = null,
)
