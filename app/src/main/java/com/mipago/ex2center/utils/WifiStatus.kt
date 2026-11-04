package com.mipago.ex2center.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import java.net.Inet4Address
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Real Wi-Fi status for the header — never a placeholder SSID or IP. */
object WifiStatus {

    data class Info(val ssidLabel: String, val ip: String)

    fun read(context: Context): Info {
        val app = context.applicationContext
        return try {
            val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val network = cm?.activeNetwork
            val onWifi = network != null &&
                cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true

            val wifiInfo = (app.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.connectionInfo
            val ipInt = wifiInfo?.ipAddress ?: 0
            if (!onWifi && ipInt == 0) return Info("Không có Wi-Fi", "--")

            val ssid = wifiInfo?.ssid?.replace("\"", "")
            val ssidLabel = if (ssid.isNullOrEmpty() || ssid == "<unknown ssid>") "Wi-Fi" else ssid

            val ip = when {
                ipInt != 0 -> InetAddress.getByAddress(
                    ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(ipInt).array()
                ).hostAddress
                network != null -> cm.getLinkProperties(network)?.linkAddresses
                    ?.map { it.address }
                    ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
                    ?.hostAddress ?: "--"
                else -> "--"
            }
            Info(ssidLabel, ip ?: "--")
        } catch (_: Throwable) {
            Info("Không rõ Wi-Fi", "--")
        }
    }
}
