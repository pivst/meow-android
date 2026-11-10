package io.github.madeye.meow.bg

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat

object WifiIdentity {
    fun permitted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
            (Build.VERSION.SDK_INT < 29 || ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED) &&
            LocationManagerCompat.isLocationEnabled(context.getSystemService(LocationManager::class.java))

    /** Called only with capabilities of the selected physical network. Never retain an old SSID. */
    @android.annotation.SuppressLint("MissingPermission") // permitted() checks runtime access above.
    @Suppress("DEPRECATION")
    fun name(context: Context, capabilities: NetworkCapabilities?): String? {
        if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true || !permitted(context)) return null
        return try {
            val raw = if (Build.VERSION.SDK_INT >= 31) (capabilities.transportInfo as? WifiInfo)?.ssid
                else context.applicationContext.getSystemService(WifiManager::class.java).connectionInfo?.ssid
            raw?.takeUnless { it == WifiManager.UNKNOWN_SSID }?.removeSurrounding("\"")?.takeIf { it.isNotEmpty() }
        } catch (_: SecurityException) { null }
    }
}
