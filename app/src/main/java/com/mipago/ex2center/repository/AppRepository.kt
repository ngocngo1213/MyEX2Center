package com.mipago.ex2center.repository

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.mipago.ex2center.model.AppInfo
import com.mipago.ex2center.utils.PackageInstallerHelper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Module 3: App Repository
 *
 * Fetches remote app catalog JSON via Wi-Fi with local assets fallback,
 * determines package installation and update status, and downloads APK files.
 */
class AppRepository(private val context: Context) {

    companion object {
        const val TAG = "AppRepository"
        const val PREFS_NAME = "ex2_app_repo"
        const val KEY_CATALOG_URL = "key_catalog_url"

        // Catalog published from this project's own repository (never a third-party repo:
        // the catalog decides what gets installed with system privileges).
        const val GITHUB_REPO = "ngocngo1213/MyEX2Center"
        const val DEFAULT_CATALOG_URL = "https://raw.githubusercontent.com/$GITHUB_REPO/main/apps.json"
        private const val UNSET_OWNER = "__GH_OWNER__"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val mainHandler = Handler(Looper.getMainLooper())

    fun getCatalogUrl(): String {
        val stored = prefs.getString(KEY_CATALOG_URL, null)
        // Ignore the legacy third-party default that older builds may have persisted.
        if (stored.isNullOrBlank() || stored.contains("swimapps/CentralEXAuto")) return DEFAULT_CATALOG_URL
        return stored
    }

    fun setCatalogUrl(url: String) {
        prefs.edit().putString(KEY_CATALOG_URL, url.trim()).apply()
    }

    /**
     * Fetches catalog list from remote URL or local assets fallback.
     */
    fun fetchCatalog(onResult: (List<AppInfo>?, String?) -> Unit) {
        Thread {
            var jsonString: String? = null
            var errorMsg: String? = null

            val targetUrl = getCatalogUrl()
            if (targetUrl.contains(UNSET_OWNER) || !targetUrl.startsWith("https://")) {
                Log.i(TAG, "No remote catalog configured — using bundled assets/apps.json.")
            } else try {
                val url = URL(targetUrl)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 2500
                conn.readTimeout = 2500
                conn.requestMethod = "GET"

                if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                    jsonString = conn.inputStream.bufferedReader().use { it.readText() }
                    Log.i(TAG, "Fetched catalog from remote: $targetUrl")
                } else {
                    Log.w(TAG, "Remote fetch HTTP ${conn.responseCode}, trying local asset fallback.")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Remote fetch error: ${t.message}. Falling back to assets.")
                errorMsg = t.message
            }

            // Fallback to local asset if remote fetch failed or returned empty
            if (jsonString.isNullOrBlank()) {
                try {
                    jsonString = context.assets.open("apps.json").bufferedReader().use { it.readText() }
                    Log.i(TAG, "Loaded catalog from assets/apps.json fallback.")
                } catch (t2: Throwable) {
                    Log.e(TAG, "Failed to load local asset apps.json: ${t2.message}")
                }
            }

            if (jsonString.isNullOrBlank()) {
                mainHandler.post { onResult(null, errorMsg ?: "Không thể tải danh mục ứng dụng.") }
                return@Thread
            }

            val appList = parseCatalog(jsonString)
            mainHandler.post { onResult(appList, null) }
        }.start()
    }

    private fun parseCatalog(json: String): List<AppInfo> {
        val list = mutableListOf<AppInfo>()
        try {
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val pkg = obj.optString("packageName", "")
                if (pkg.isBlank()) continue
                val abisJson = obj.optJSONArray("abis")
                val abis = if (abisJson == null) emptyList() else (0 until abisJson.length()).map { abisJson.optString(it) }
                val app = AppInfo(
                    name = obj.optString("name", "Ứng dụng"),
                    packageName = pkg,
                    versionCode = obj.optLong("versionCode", 1L),
                    versionName = obj.optString("versionName", "1.0"),
                    sizeMb = obj.optDouble("sizeMb", 0.0),
                    apkUrl = obj.optString("apkUrl", "").trim(),
                    iconUrl = obj.optString("iconUrl", ""),
                    iconResName = obj.optString("iconResName", ""),
                    description = obj.optString("description", ""),
                    browserUrl = obj.optString("browserUrl", ""),
                    sha256 = obj.optString("sha256", "").trim(),
                    type = obj.optString("type", AppInfo.TYPE_APK).lowercase(),
                    abis = abis,
                    minSdk = obj.optInt("minSdk", 0),
                    requiresPark = obj.optBoolean("requiresPark", true),
                    isSelf = obj.optBoolean("self", false) || pkg == context.packageName
                )

                // Update installation status
                val installedInfo = PackageInstallerHelper.getInstalledPackageInfo(context, app.packageName)
                if (installedInfo == null) {
                    app.status = AppInfo.InstallStatus.NOT_INSTALLED
                } else {
                    val installedVersion = PackageInstallerHelper.installedVersionCode(installedInfo)

                    if (app.versionCode > installedVersion) {
                        app.status = AppInfo.InstallStatus.UPDATE_AVAILABLE
                    } else {
                        app.status = AppInfo.InstallStatus.INSTALLED
                    }
                }

                list.add(app)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "JSON parse error: ${t.message}", t)
        }
        return list
    }

    /**
     * Downloads an APK file to the app's cache directory with redirect handling and local file support.
     */
    fun downloadApk(
        app: AppInfo,
        onProgress: (Int) -> Unit,
        onComplete: (File?, String?) -> Unit
    ) {
        Thread {
            val ext = if (app.type == AppInfo.TYPE_XAPK) "xapk" else "apk"
            val destination = File(context.cacheDir, "${app.packageName}.$ext")
            if (destination.exists()) destination.delete()

            try {
                // Catalog entries may only point to HTTPS downloads with a pinned SHA-256.
                // Local paths are refused: a remote catalog must never pick files on the device.
                if (!app.isPublished) {
                    mainHandler.post { onComplete(null, "Ứng dụng này chưa được phát hành để tải qua mạng. Hãy cài từ USB / máy.") }
                    return@Thread
                }
                if (!app.apkUrl.startsWith("https://")) {
                    mainHandler.post { onComplete(null, "Lỗi bảo mật: Chỉ cho phép tải qua HTTPS.") }
                    return@Thread
                }
                if (!app.sha256.matches(Regex("^[0-9a-fA-F]{64}$"))) {
                    mainHandler.post { onComplete(null, "Lỗi bảo mật: Mục này thiếu mã SHA-256 hợp lệ, không thể tải an toàn.") }
                    return@Thread
                }

                // Follow HTTP/HTTPS redirects safely (up to 5 redirects)
                var currentUrl = app.apkUrl
                var conn: HttpURLConnection? = null
                var redirects = 0
                val maxRedirects = 5

                while (redirects < maxRedirects) {
                    val url = URL(currentUrl)
                    conn = (url.openConnection() as HttpURLConnection).apply {
                        instanceFollowRedirects = true
                        connectTimeout = 15000
                        readTimeout = 30000
                        setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Mobile; MyEX2Center)")
                    }
                    conn.connect()

                    val code = conn.responseCode
                    if (code in 300..399) {
                        val location = conn.getHeaderField("Location")
                        if (!location.isNullOrEmpty()) {
                            currentUrl = if (location.startsWith("http")) location else URL(url, location).toString()
                            if (!currentUrl.startsWith("https://")) {
                                conn.disconnect()
                                mainHandler.post { onComplete(null, "Lỗi bảo mật: Máy chủ chuyển hướng sang kết nối không mã hóa.") }
                                return@Thread
                            }
                            conn.disconnect()
                            redirects++
                            continue
                        }
                    }
                    break
                }

                val finalConn = conn
                val responseCode = finalConn?.responseCode ?: -1
                if (finalConn == null || responseCode !in 200..299) {
                    val errMsg = when (responseCode) {
                        404 -> "HTTP 404: Tệp tin APK không tồn tại trên máy chủ tải về."
                        451 -> "HTTP 451: Tệp tin bị giới hạn bản quyền tải xuống trên kho lưu trữ."
                        403 -> "HTTP 403: Máy chủ từ chối quyền truy cập hoặc cần xác minh Captcha."
                        else -> "Máy chủ phản hồi mã lỗi HTTP: $responseCode"
                    }
                    mainHandler.post { onComplete(null, errMsg) }
                    return@Thread
                }

                val totalLength = finalConn.contentLengthLong
                var downloaded = 0L

                finalConn.inputStream.use { input ->
                    FileOutputStream(destination).use { output ->
                        val buffer = ByteArray(32768)
                        var bytesRead: Int
                        var lastProgress = 0

                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            downloaded += bytesRead

                            if (totalLength > 0) {
                                val progress = ((downloaded * 100) / totalLength).toInt()
                                if (progress != lastProgress) {
                                    lastProgress = progress
                                    mainHandler.post { onProgress(progress) }
                                }
                            }
                        }
                        output.flush()
                    }
                }

                // Verify SHA-256 checksum if specified
                if (!verifySha256(destination, app.sha256)) {
                    Log.e(TAG, "SHA-256 mismatch for ${app.name}! Aborting installation.")
                    if (destination.exists()) destination.delete()
                    mainHandler.post { onComplete(null, "Lỗi bảo mật: Xác thực mã băm SHA-256 thất bại. Tệp tin không an toàn.") }
                    return@Thread
                }

                Log.i(TAG, "APK downloaded and verified successfully: ${destination.absolutePath}")
                mainHandler.post { onComplete(destination, null) }

            } catch (t: Throwable) {
                Log.e(TAG, "Download failed: ${t.message}", t)
                if (destination.exists()) destination.delete()
                mainHandler.post { onComplete(null, "Lỗi tải về: ${t.message}") }
            }
        }.start()
    }

    private fun verifySha256(file: File, expectedHash: String): Boolean {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(65536)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
            }
            val calculated = digest.digest().joinToString("") { "%02x".format(it) }
            calculated.equals(expectedHash.trim(), ignoreCase = true)
        } catch (t: Throwable) {
            Log.e(TAG, "SHA-256 calculation error: ${t.message}", t)
            false
        }
    }
}
