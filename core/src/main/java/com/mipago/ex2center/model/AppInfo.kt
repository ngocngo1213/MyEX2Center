package com.mipago.ex2center.model

/**
 * Metadata model for an application in the Wi-Fi App Catalog.
 *
 * [type] is "apk" (single APK) or "xapk" (zip of split APKs, e.g. APKPure .xapk).
 * [abis] lists the native ABIs the package ships (empty = no native code / unknown).
 * [apkUrl] may be empty: the app is then "not published" and can only be installed from USB/local.
 * [isSelf] marks MyEX2Center itself (self-update: no uninstall button, process restarts).
 * [batteryWhitelist]: EX2 Store adds the installed app to the battery-optimisation whitelist
 * (navigation apps that must run in the background; see PowerWhitelist).
 * [overlayPermission]: EX2 Store grants "display over other apps" to the installed app (popup
 * video players such as PipePipe; Flyme Auto has no screen for it; see OverlayPermission).
 */
data class AppInfo(
    val name: String,
    val packageName: String,
    val versionCode: Long,
    val versionName: String,
    val sizeMb: Double = 0.0,
    val apkUrl: String,
    val iconUrl: String = "",
    val iconResName: String = "",
    val description: String = "",
    val browserUrl: String = "",
    val sha256: String = "",
    val type: String = TYPE_APK,
    val abis: List<String> = emptyList(),
    val minSdk: Int = 0,
    val requiresPark: Boolean = true,
    val isSelf: Boolean = false,
    val batteryWhitelist: Boolean = false,
    val overlayPermission: Boolean = false
) {
    companion object {
        const val TYPE_APK = "apk"
        const val TYPE_XAPK = "xapk"
        const val TYPE_APKS = "apks"
    }

    enum class InstallStatus {
        NOT_INSTALLED,
        INSTALLED,
        UPDATE_AVAILABLE,
        DOWNLOADING,
        INSTALLING
    }

    val isPublished: Boolean get() = apkUrl.isNotBlank()

    var status: InstallStatus = InstallStatus.NOT_INSTALLED
    var downloadProgress: Int = 0
}
