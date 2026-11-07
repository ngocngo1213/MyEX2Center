package com.mipago.ex2center.repository

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.os.SystemClock
import android.util.Log
import com.mipago.ex2center.model.AppInfo
import com.mipago.ex2center.utils.CatalogVerifier
import com.mipago.ex2center.utils.PackageInstallerHelper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

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

        /** Error value passed to onComplete when the user cancelled (not a failure). */
        const val ERROR_CANCELLED = "__cancelled__"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val mainHandler = Handler(Looper.getMainLooper())

    fun getCatalogUrl(): String {
        val stored = prefs.getString(KEY_CATALOG_URL, null)
        // Ignore the legacy third-party default that older builds may have persisted.
        if (stored.isNullOrBlank() || stored.contains("swimapps/CentralEXAuto")) return DEFAULT_CATALOG_URL
        return stored
    }

    /** Where a loaded catalog came from (only [REMOTE_VERIFIED] / [CACHED] are signed). */
    enum class CatalogSource { REMOTE_VERIFIED, CACHED, BUNDLED }

    data class CatalogResult(val apps: List<AppInfo>, val source: CatalogSource)

    private val cacheFile: File get() = File(context.filesDir, "catalog_verified.json")

    /**
     * Fetches catalog list from remote URL or local assets fallback (callback on main thread).
     */
    fun fetchCatalog(onResult: (List<AppInfo>?, String?) -> Unit) {
        Thread {
            val result = loadCatalogBlocking()
            mainHandler.post {
                if (result == null) onResult(null, "Không thể tải danh mục ứng dụng.")
                else onResult(result.apps, null)
            }
        }.start()
    }

    /**
     * Blocking catalog load (call off the main thread), most trusted source first:
     * 1. remote `apps.json` whose `apps.json.sig` verifies with [CatalogVerifier] (then cached),
     * 2. the last verified remote catalog kept in app storage,
     * 3. the catalog bundled inside this signed APK.
     * An unsigned or tampered remote catalog is never used.
     */
    fun loadCatalogBlocking(timeoutMs: Int = 2500): CatalogResult? {
        val bundled = try {
            parseCatalog(context.assets.open("apps.json").bufferedReader().use { it.readText() })
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to load bundled apps.json: ${t.message}")
            emptyList()
        }
        val targetUrl = getCatalogUrl()
        if (!targetUrl.contains(UNSET_OWNER) && targetUrl.startsWith("https://")) {
            // raw.githubusercontent.com caches each URL for up to 5 minutes (and per CDN edge), so a
            // catalog read right after a release could still be the old one. A query that changes
            // every minute makes the CDN fetch the current file; catalog and signature use the same
            // value so they always come from the same commit.
            val fresh = "t=" + System.currentTimeMillis() / 60_000
            fun withFresh(url: String) = url + (if (url.contains('?')) "&" else "?") + fresh
            val body = httpGetBytes(withFresh(targetUrl), timeoutMs)
            if (body != null) {
                val sig = httpGetBytes(withFresh("$targetUrl.sig"), timeoutMs)?.toString(Charsets.UTF_8)
                if (CatalogVerifier.verify(body, sig)) {
                    val apps = parseCatalog(body.toString(Charsets.UTF_8))
                    if (apps.isNotEmpty()) {
                        try { cacheFile.writeBytes(body) } catch (_: Throwable) {}
                        val merged = mergeWithBundled(apps, bundled)
                        Log.i(TAG, "Remote catalog verified (${apps.size} remote, ${merged.size} total).")
                        return CatalogResult(merged, CatalogSource.REMOTE_VERIFIED)
                    }
                } else {
                    Log.w(TAG, "Remote catalog rejected: missing or invalid signature.")
                }
            }
        }
        if (cacheFile.isFile) {
            val apps = try { parseCatalog(cacheFile.readText()) } catch (_: Throwable) { emptyList() }
            if (apps.isNotEmpty()) return CatalogResult(mergeWithBundled(apps, bundled), CatalogSource.CACHED)
        }
        return if (bundled.isEmpty()) null else CatalogResult(bundled, CatalogSource.BUNDLED)
    }

    /**
     * Combines a verified remote/cached catalog with the APK's bundled `assets/apps.json`:
     * - Apps newly added to the bundled APK appear immediately even before `apps.json` is pushed to GitHub.
     * - The signed remote catalog is authoritative: its entry wins unless the bundled entry is a
     *   NEWER version with a real SHA-256 (the bundled MyEX2Center / EX2 Store entries can never
     *   carry their own hash, so they must not hide the downloadable remote entry).
     * - Brand-new apps published only in the remote catalog are appended.
     */
    private val SHA256_HEX = Regex("^[0-9a-fA-F]{64}$")

    private fun mergeWithBundled(remote: List<AppInfo>, bundled: List<AppInfo>): List<AppInfo> {
        if (bundled.isEmpty()) return remote
        val remoteByPkg = remote.associateBy { it.packageName }
        val result = ArrayList<AppInfo>(maxOf(remote.size, bundled.size))
        val seen = HashSet<String>()
        for (b in bundled) {
            val r = remoteByPkg[b.packageName]
            val bundledWins = r == null ||
                (b.versionCode > r.versionCode && b.sha256.matches(SHA256_HEX))
            // batteryWhitelist / overlayPermission only widen what the Store does for an installed
            // app, so either source may set them (a new build fixes apps before the signed catalog
            // is pushed).
            val chosen = if (bundledWins || r == null) b else {
                val battery = r.batteryWhitelist || b.batteryWhitelist
                val overlay = r.overlayPermission || b.overlayPermission
                if (battery == r.batteryWhitelist && overlay == r.overlayPermission) r
                else r.copy(batteryWhitelist = battery, overlayPermission = overlay).also {
                    it.status = r.status // copy() does not carry the mutable install state
                    it.downloadProgress = r.downloadProgress
                }
            }
            result.add(chosen)
            seen.add(b.packageName)
        }
        for (r in remote) {
            if (seen.add(r.packageName)) result.add(r)
        }
        return result
    }

    /** Small HTTPS GET (catalog + signature); null on any error or non-200. */
    private fun httpGetBytes(url: String, timeoutMs: Int): ByteArray? = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        conn.useCaches = false
        try {
            if (conn.responseCode == HttpURLConnection.HTTP_OK) conn.inputStream.use { it.readBytes() }
            else { Log.w(TAG, "GET $url -> HTTP ${conn.responseCode}"); null }
        } finally {
            conn.disconnect()
        }
    } catch (t: Throwable) {
        Log.w(TAG, "GET $url failed: ${t.message}")
        null
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
                    batteryWhitelist = obj.optBoolean("batteryWhitelist", false),
                    overlayPermission = obj.optBoolean("overlayPermission", false),
                    // "self" = the app showing this catalog (EX2 Store, or MyEX2Center's built-in fallback).
                    isSelf = pkg == context.packageName
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

    // ---------------------------------------------------------------------------------------
    // DOWNLOADS (resumable, cancellable, verified)
    // ---------------------------------------------------------------------------------------

    /** Returned by [downloadApk]; [cancel] stops the transfer and keeps the partial for resume. */
    class DownloadHandle internal constructor() {
        private val cancelled = AtomicBoolean(false)
        @Volatile internal var connection: HttpURLConnection? = null

        val isCancelled: Boolean get() = cancelled.get()

        fun cancel() {
            if (cancelled.compareAndSet(false, true)) {
                // disconnect() unblocks a read that is waiting on the socket.
                try { connection?.disconnect() } catch (_: Throwable) {}
            }
        }
    }

    private class Cancelled : Exception()

    private val downloadDir: File
        get() = File(context.filesDir, "downloads").apply { mkdirs() }

    private fun extFor(app: AppInfo): String = when (app.type) {
        AppInfo.TYPE_XAPK -> "xapk"
        AppInfo.TYPE_APKS -> "apks"
        else -> "apk"
    }

    private fun partFileFor(app: AppInfo): File {
        val ext = extFor(app)
        // Keyed by the expected hash: a partial is only ever resumed into the same exact file.
        return File(downloadDir, "${app.sha256.lowercase().take(16)}.$ext.part")
    }

    /**
     * Deletes leftovers in filesDir/downloads: partials whose hash is no longer in the catalog and
     * finished files older than 10 minutes (an install deletes its own file when it ends).
     */
    fun cleanupDownloads(catalog: List<AppInfo>) {
        val keep = catalog.mapNotNull { a -> a.sha256.lowercase().take(16).takeIf { it.length == 16 } }.toSet()
        val now = System.currentTimeMillis()
        Thread {
            downloadDir.listFiles()?.forEach { f ->
                val stale = if (f.name.endsWith(".part")) f.name.substringBefore('.') !in keep
                else now - f.lastModified() > 10 * 60_000L
                if (stale && f.delete()) Log.i(TAG, "Removed stale download ${f.name}")
            }
            // Legacy location used by older builds.
            context.cacheDir.listFiles()?.filter {
                it.name.endsWith(".apk") || it.name.endsWith(".xapk") || it.name.endsWith(".apks") || it.name.startsWith("picked_")
            }?.forEach { if (now - it.lastModified() > 10 * 60_000L) it.delete() }
        }.start()
    }

    /**
     * Downloads [app] into filesDir/downloads. Resumes a previous partial with an HTTP Range
     * request, checks free space first, verifies SHA-256 and only then hands back the file.
     * [onProgress] / [onComplete] run on the main thread. On cancel, [onComplete] receives
     * (null, [ERROR_CANCELLED]) and the partial is kept so the next attempt resumes.
     */
    fun downloadApk(
        app: AppInfo,
        onProgress: (Int) -> Unit,
        onComplete: (File?, String?) -> Unit
    ): DownloadHandle {
        val handle = DownloadHandle()
        Thread {
            val (file, error) = try {
                doDownload(app, handle) { p -> mainHandler.post { onProgress(p) } }
            } catch (_: Cancelled) {
                Log.i(TAG, "Download of ${app.packageName} cancelled; partial kept for resume.")
                null to ERROR_CANCELLED
            } catch (t: Throwable) {
                if (handle.isCancelled) {
                    null to ERROR_CANCELLED
                } else {
                    Log.e(TAG, "Download failed: ${t.message}", t)
                    null to "Lỗi mạng khi tải: ${t.message ?: t.javaClass.simpleName}. Bấm lại để tải tiếp phần còn lại."
                }
            } finally {
                handle.connection = null
            }
            mainHandler.post { onComplete(file, error) }
        }.start()
        return handle
    }

    private fun doDownload(app: AppInfo, handle: DownloadHandle, progress: (Int) -> Unit): Pair<File?, String?> {
        // Catalog entries may only point to HTTPS downloads with a pinned SHA-256.
        // Local paths are refused: a remote catalog must never pick files on the device.
        if (!app.isPublished) {
            return null to "Ứng dụng này chưa được phát hành để tải qua mạng. Hãy cài từ USB / máy."
        }
        if (!app.apkUrl.startsWith("https://")) return null to "Lỗi bảo mật: Chỉ cho phép tải qua HTTPS."
        if (!app.sha256.matches(SHA256_HEX)) {
            return null to "Lỗi bảo mật: Mục này thiếu mã SHA-256 hợp lệ, không thể tải an toàn."
        }

        val part = partFileFor(app)
        var resumeFrom = if (part.exists()) part.length() else 0L

        val conn = openWithRedirects(app.apkUrl, resumeFrom, handle) ?: return null to "Lỗi bảo mật: Máy chủ chuyển hướng sang kết nối không mã hóa."
        try {
            val code = conn.responseCode
            val total: Long
            when {
                code == 206 -> {
                    total = parseContentRangeTotal(conn.getHeaderField("Content-Range"))
                        ?: (resumeFrom + conn.contentLengthLong)
                    Log.i(TAG, "Resuming ${app.packageName} at ${resumeFrom / 1_048_576} MB")
                }
                code == 416 && resumeFrom > 0 -> {
                    // Partial already complete — verify below.
                    total = resumeFrom
                }
                code in 200..299 -> {
                    resumeFrom = 0L // server ignored Range: start over
                    total = conn.contentLengthLong
                }
                else -> return null to when (code) {
                    404 -> "HTTP 404: Tệp cài đặt không tồn tại trên máy chủ."
                    403 -> "HTTP 403: Máy chủ từ chối truy cập."
                    451 -> "HTTP 451: Tệp bị giới hạn tải xuống."
                    else -> "Máy chủ phản hồi mã lỗi HTTP: $code"
                }
            }

            if (total > 0) {
                val free = try { StatFs(context.filesDir.path).availableBytes } catch (_: Throwable) { Long.MAX_VALUE }
                // remaining download + the installer's own staged copy + a small margin
                val needed = (total - resumeFrom) + total + 50L * 1_048_576
                if (free < needed) {
                    return null to "Không đủ bộ nhớ trống để tải: cần ~${needed / 1_048_576} MB, còn ${free / 1_048_576} MB."
                }
            }

            if (code != 416) {
                conn.inputStream.use { input ->
                    FileOutputStream(part, resumeFrom > 0).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var downloaded = resumeFrom
                        var lastPercent = -1
                        var lastPostAt = 0L
                        while (true) {
                            if (handle.isCancelled) throw Cancelled()
                            val n = input.read(buffer)
                            if (n == -1) break
                            output.write(buffer, 0, n)
                            downloaded += n
                            if (total > 0) {
                                val percent = ((downloaded * 100) / total).toInt().coerceIn(0, 100)
                                val now = SystemClock.elapsedRealtime()
                                if (percent != lastPercent && now - lastPostAt >= 250L) {
                                    lastPercent = percent
                                    lastPostAt = now
                                    progress(percent)
                                }
                            }
                        }
                        output.flush()
                    }
                }
            }
        } finally {
            try { conn.disconnect() } catch (_: Throwable) {}
        }
        if (handle.isCancelled) throw Cancelled()

        if (!verifySha256(part, app.sha256)) {
            Log.e(TAG, "SHA-256 mismatch for ${app.name}; deleting partial.")
            part.delete()
            return null to "Lỗi bảo mật: Mã SHA-256 không khớp, tệp đã bị xóa. Hãy thử tải lại."
        }
        val ext = extFor(app)
        val finalFile = File(downloadDir, "${app.packageName}-${app.sha256.lowercase().take(8)}.$ext")
        if (finalFile.exists()) finalFile.delete()
        if (!part.renameTo(finalFile)) return null to "Không thể hoàn tất tệp tải về."
        Log.i(TAG, "Downloaded and verified ${finalFile.name} (${finalFile.length() / 1_048_576} MB)")
        return finalFile to null
    }

    /** Follows up to 5 redirects manually so that every hop is checked to stay on HTTPS. */
    private fun openWithRedirects(startUrl: String, resumeFrom: Long, handle: DownloadHandle): HttpURLConnection? {
        var current = startUrl
        repeat(6) {
            if (handle.isCancelled) throw Cancelled()
            val url = URL(current)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", "MyEX2Center/${versionName()} (Android ${android.os.Build.VERSION.RELEASE})")
                if (resumeFrom > 0) setRequestProperty("Range", "bytes=$resumeFrom-")
            }
            handle.connection = conn
            val code = conn.responseCode
            if (code !in 300..399) return conn
            val location = conn.getHeaderField("Location")
            conn.disconnect()
            if (location.isNullOrEmpty()) return conn
            current = URL(url, location).toString()
            if (!current.startsWith("https://")) return null
        }
        throw java.io.IOException("Quá nhiều lần chuyển hướng.")
    }

    private fun parseContentRangeTotal(header: String?): Long? =
        header?.substringAfterLast('/', "")?.trim()?.toLongOrNull()

    private fun versionName(): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    } catch (_: Throwable) {
        "?"
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
