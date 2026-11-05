package com.mipago.ex2center.utils

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/**
 * Module 3: Silent Package Installer Helper
 *
 * Uses system UID privileges (android:sharedUserId="android.uid.system" + INSTALL_PACKAGES)
 * to install silently. Supports:
 *  - single `.apk`
 *  - split bundles `.xapk` / `.apks` (zip of base + config.*.apk, e.g. APKPure), streamed
 *    entry-by-entry straight from the zip into ONE PackageInstaller session (no extraction).
 *
 * Before committing it checks package name, minSdk, native ABI compatibility and free space.
 * A failed session is always abandoned. Installs run one at a time on a background thread.
 */
object PackageInstallerHelper {

    private const val TAG = "PackageInstallerHelper"
    private const val COMMIT_TIMEOUT_S = 180L
    private const val BUFFER = 1 shl 16

    const val PREFS_NAME = "ex2_installer"
    const val KEY_PENDING_SELF_UPDATE = "pending_self_update_version"

    private val ABI_SPLIT = Regex(
        """^(?:split_)?config\.(arm64_v8a|armeabi_v7a|armeabi|x86_64|x86|mips64|mips)\.apk$""",
        RegexOption.IGNORE_CASE
    )
    private val io = Executors.newSingleThreadExecutor()

    private fun abiOfSplit(entryName: String): String? =
        ABI_SPLIT.matchEntire(entryName)?.groupValues?.get(1)?.lowercase()

    data class Result(val ok: Boolean, val message: String? = null, val packageName: String? = null)

    /** What a local .apk / .xapk / .apks file contains (read without installing). */
    data class PackageMeta(
        val packageName: String,
        val versionCode: Long,
        val versionName: String?,
        val isBundle: Boolean,
        val minSdk: Int = 0
    )

    fun getInstalledPackageInfo(context: Context, packageName: String): PackageInfo? {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }

    fun isPackageInstalled(context: Context, packageName: String): Boolean {
        return getInstalledPackageInfo(context, packageName) != null
    }

    fun installedVersionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
        else @Suppress("DEPRECATION") info.versionCode.toLong()

    // ---------------------------------------------------------------------------------------
    // Inspection
    // ---------------------------------------------------------------------------------------

    /** True when [file] is a zip whose root holds .apk entries (xapk/apks), not a plain APK. */
    fun isSplitBundle(file: File): Boolean = try {
        ZipFile(file).use { zip -> rootApkEntries(zip).isNotEmpty() }
    } catch (_: Throwable) {
        false
    }

    /** Reads package name/version of an .apk, .xapk or .apks. Blocking — call off the main thread. */
    fun inspect(context: Context, file: File): PackageMeta? {
        if (!file.isFile || file.length() <= 0) return null
        return try {
            if (isSplitBundle(file)) ZipFile(file).use { inspectBundle(it) }
            else context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)?.let {
                PackageMeta(
                    packageName = it.packageName,
                    versionCode = installedVersionCode(it),
                    versionName = it.versionName,
                    isBundle = false,
                    minSdk = it.applicationInfo?.minSdkVersion ?: 0
                )
            }
        } catch (t: Throwable) {
            Log.w(TAG, "inspect ${file.name}: ${t.message}")
            null
        }
    }

    private fun inspectBundle(zip: ZipFile): PackageMeta? {
        readBundleManifest(zip)?.let { manifest ->
            val pkg = manifest.optString("package_name").takeIf { it.isNotBlank() }
            if (pkg != null) {
                return PackageMeta(
                    packageName = pkg,
                    versionCode = manifest.optString("version_code").toLongOrNull()
                        ?: manifest.optLong("version_code", 0L),
                    versionName = manifest.optString("version_name").takeIf { it.isNotBlank() },
                    isBundle = true,
                    minSdk = manifest.optString("min_sdk_version").toIntOrNull()
                        ?: manifest.optInt("min_sdk_version", 0)
                )
            }
        }
        // Standard .apks bundles (Bundletool / SAI / APKCube) have no manifest.json;
        // read AndroidManifest.xml directly from base.apk inside the zip stream.
        val apks = rootApkEntries(zip)
        val baseEntry = apks.firstOrNull { it.name.equals("base.apk", ignoreCase = true) }
            ?: apks.firstOrNull { abiOfSplit(it.name) == null && !it.name.startsWith("split_config.", true) && !it.name.startsWith("config.", true) }
            ?: apks.maxByOrNull { it.size }
            ?: return null
        ZipInputStream(zip.getInputStream(baseEntry)).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                if (entry.name == "AndroidManifest.xml") {
                    return parseBinaryManifest(zis.readBytes())
                }
            }
        }
        return null
    }

    /**
     * Minimal binary AXML parser for `AndroidManifest.xml` inside `base.apk` of `.apks` bundles.
     * Extracts `package`, `versionCode`, `versionName` from `<manifest>` and `minSdkVersion` from `<uses-sdk>`.
     */
    private fun parseBinaryManifest(data: ByteArray): PackageMeta? = try {
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        if (data.size < 36 || buf.getInt(0) != 0x00080003) null
        else {
            val strChunkSize = buf.getInt(12)
            val strCount = buf.getInt(16)
            val flags = buf.getInt(24)
            val strStart = 8 + buf.getInt(28)
            val isUtf8 = (flags and 0x100) != 0
            val strings = Array(strCount) { i ->
                var p = strStart + buf.getInt(36 + i * 4)
                if (isUtf8) {
                    val u16 = data[p].toInt() and 0xFF; p += if ((u16 and 0x80) != 0) 2 else 1
                    var u8 = data[p].toInt() and 0xFF; p += 1
                    if ((u8 and 0x80) != 0) {
                        u8 = ((u8 and 0x7F) shl 8) or (data[p].toInt() and 0xFF); p += 1
                    }
                    String(data, p, u8, Charsets.UTF_8)
                } else {
                    var u16 = buf.getShort(p).toInt() and 0xFFFF; p += 2
                    if ((u16 and 0x8000) != 0) {
                        u16 = ((u16 and 0x7FFF) shl 16) or (buf.getShort(p).toInt() and 0xFFFF); p += 2
                    }
                    String(data, p, u16 * 2, Charsets.UTF_16LE)
                }
            }
            var pkg: String? = null
            var versionCode = 0L
            var versionCodeMajor = 0L
            var versionName: String? = null
            var minSdk = 0
            var pos = 8 + strChunkSize
            while (pos + 8 <= data.size) {
                val ctype = buf.getShort(pos).toInt() and 0xFFFF
                val csize = buf.getInt(pos + 4)
                if (csize <= 0) break
                if (ctype == 0x0102 && pos + 28 <= data.size) { // RES_XML_START_ELEMENT_TYPE
                    val nameIdx = buf.getInt(pos + 20)
                    val attrStart = buf.getShort(pos + 24).toInt() and 0xFFFF
                    val attrSize = buf.getShort(pos + 26).toInt() and 0xFFFF
                    val attrCnt = buf.getShort(pos + 28).toInt() and 0xFFFF
                    val tag = strings.getOrNull(nameIdx) ?: ""
                    if ((tag == "manifest" || tag == "uses-sdk") && attrSize >= 20) {
                        var ap = pos + 16 + attrStart
                        repeat(attrCnt) {
                            if (ap + 20 <= data.size) {
                                val aName = strings.getOrNull(buf.getInt(ap + 4)) ?: ""
                                val rawVal = buf.getInt(ap + 8)
                                val dType = data[ap + 15].toInt() and 0xFF
                                val dVal = buf.getInt(ap + 16)
                                val sVal = if (dType == 3) strings.getOrNull(rawVal) else null
                                when {
                                    tag == "manifest" && aName == "package" -> pkg = sVal
                                    tag == "manifest" && aName == "versionCode" -> versionCode = dVal.toLong() and 0xFFFFFFFFL
                                    tag == "manifest" && aName == "versionCodeMajor" -> versionCodeMajor = dVal.toLong() and 0xFFFFFFFFL
                                    tag == "manifest" && aName == "versionName" -> versionName = sVal
                                    tag == "uses-sdk" && aName == "minSdkVersion" -> minSdk = dVal
                                }
                            }
                            ap += attrSize
                        }
                    }
                }
                pos += csize
            }
            pkg?.takeIf { it.isNotBlank() }?.let {
                PackageMeta(
                    packageName = it,
                    versionCode = (versionCodeMajor shl 32) or versionCode,
                    versionName = versionName,
                    isBundle = true,
                    minSdk = minSdk
                )
            }
        }
    } catch (t: Throwable) {
        Log.w(TAG, "parseBinaryManifest failed: ${t.message}")
        null
    }

    private fun readBundleManifest(zip: ZipFile): JSONObject? = try {
        zip.getEntry("manifest.json")?.let { e ->
            zip.getInputStream(e).bufferedReader().use { JSONObject(it.readText()) }
        }
    } catch (_: Throwable) {
        null
    }

    private fun rootApkEntries(zip: ZipFile): List<ZipEntry> =
        zip.entries().asSequence()
            .filter { !it.isDirectory && !it.name.contains('/') && it.name.endsWith(".apk", ignoreCase = true) }
            .toList()

    // ---------------------------------------------------------------------------------------
    // Install / uninstall
    // ---------------------------------------------------------------------------------------

    /**
     * Installs an .apk or .xapk silently. [expectedPackage] (optional) must match the package
     * inside the file. [onResult] runs on a background thread.
     */
    fun install(context: Context, file: File, expectedPackage: String?, onResult: (Result) -> Unit) {
        val appContext = context.applicationContext
        io.execute {
            val result = try {
                if (!file.isFile || file.length() <= 0) {
                    Result(false, "Tệp cài đặt không tồn tại hoặc rỗng.")
                } else if (isSplitBundle(file)) {
                    installBundle(appContext, file, expectedPackage)
                } else {
                    installApk(appContext, file, expectedPackage)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "install failed: ${t.message}", t)
                Result(false, "Lỗi cài đặt: ${t.message}")
            }
            onResult(result)
        }
    }

    /** Backwards-compatible wrapper used by the catalog UI. */
    fun installSilently(
        context: Context,
        apkFile: File,
        targetPackageName: String,
        onResult: (success: Boolean, errorMessage: String?, installedPkg: String?) -> Unit
    ) {
        install(context, apkFile, targetPackageName.takeIf { it.isNotBlank() }) {
            onResult(it.ok, it.message, it.packageName)
        }
    }

    private fun installApk(context: Context, file: File, expectedPackage: String?): Result {
        val info = context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
            ?: return Result(false, "File APK không hợp lệ hoặc bị hỏng.")
        val pkg = info.packageName
        if (expectedPackage != null && pkg != expectedPackage) {
            return Result(false, "Sai ứng dụng: file chứa $pkg, cần $expectedPackage.", pkg)
        }
        val minSdk = info.applicationInfo?.minSdkVersion ?: 0
        if (minSdk > Build.VERSION.SDK_INT) {
            return Result(false, "Ứng dụng cần Android API $minSdk, đầu xe là API ${Build.VERSION.SDK_INT}.", pkg)
        }
        val libAbis = ZipFile(file).use { zip ->
            zip.entries().asSequence()
                .mapNotNull { e -> e.name.takeIf { it.startsWith("lib/") }?.split('/')?.getOrNull(1) }
                .filter { it.isNotBlank() }
                .toSet()
        }
        if (libAbis.isNotEmpty() && Build.SUPPORTED_ABIS.none { it in libAbis }) {
            return Result(false, abiMessage(libAbis), pkg)
        }
        checkFreeSpace(file.length())?.let { return Result(false, it, pkg) }

        return commit(context, pkg, file.length()) { session ->
            file.inputStream().use { input ->
                session.openWrite("base.apk", 0, file.length()).use { out ->
                    input.copyTo(out, BUFFER)
                    session.fsync(out)
                }
            }
        }
    }

    private fun installBundle(context: Context, file: File, expectedPackage: String?): Result =
        ZipFile(file).use { zip ->
            val meta = inspectBundle(zip)
            val pkg = meta?.packageName ?: expectedPackage
                ?: return Result(false, "Gói XAPK/APKS không hợp lệ (không xác định được ứng dụng).")
            if (expectedPackage != null && pkg != expectedPackage) {
                return Result(false, "Sai ứng dụng: gói chứa $pkg, cần $expectedPackage.", pkg)
            }
            val minSdk = meta?.minSdk ?: 0
            if (minSdk > Build.VERSION.SDK_INT) {
                return Result(false, "Ứng dụng cần Android API $minSdk, đầu xe là API ${Build.VERSION.SDK_INT}.", pkg)
            }

            val apks = rootApkEntries(zip)
            if (apks.isEmpty()) return Result(false, "Gói XAPK/APKS không chứa file APK nào.", pkg)

            // Pick the best native split for this head unit (Build.SUPPORTED_ABIS is in
            // preference order). Supports both config.<abi>.apk (.xapk) and split_config.<abi>.apk (.apks).
            // Density/language splits are all kept.
            val deviceAbis = Build.SUPPORTED_ABIS.map { it.lowercase().replace('-', '_') }
            val abiSplits = apks.mapNotNull { entry -> abiOfSplit(entry.name)?.let { abi -> abi to entry } }
            val chosenAbi = deviceAbis.firstOrNull { devAbi -> abiSplits.any { it.first == devAbi } }
            if (abiSplits.isNotEmpty() && chosenAbi == null) {
                val offered = abiSplits.map { it.first.replace('_', '-') }.toSet()
                return Result(false, abiMessage(offered), pkg)
            }
            val selected = apks.filter { entry ->
                val splitAbi = abiOfSplit(entry.name)
                splitAbi == null || splitAbi == chosenAbi
            }
            if (selected.any { it.size < 0 }) return Result(false, "Gói XAPK/APKS bị hỏng (không đọc được kích thước).", pkg)
            val total = selected.sumOf { it.size }
            checkFreeSpace(total)?.let { return Result(false, it, pkg) }

            Log.i(TAG, "Installing bundle $pkg: ${selected.joinToString { it.name }} (abi=$chosenAbi, ${total / 1_048_576} MB)")
            commit(context, pkg, total) { session ->
                for (entry in selected) {
                    zip.getInputStream(entry).use { input ->
                        session.openWrite(entry.name, 0, entry.size).use { out ->
                            input.copyTo(out, BUFFER)
                            session.fsync(out)
                        }
                    }
                }
            }
        }

    private fun abiMessage(packageAbis: Set<String>) =
        "Không tương thích CPU: gói chỉ hỗ trợ ${packageAbis.joinToString()}, " +
            "đầu xe hỗ trợ ${Build.SUPPORTED_ABIS.joinToString()}."

    /** The installer stages a copy and then dex-optimises: require ~2x the payload free. */
    private fun checkFreeSpace(payloadBytes: Long): String? {
        val free = try {
            StatFs(Environment.getDataDirectory().path).availableBytes
        } catch (_: Throwable) {
            return null
        }
        val needed = payloadBytes * 2
        return if (free < needed) {
            "Không đủ bộ nhớ trống: cần ~${needed / 1_048_576} MB, còn ${free / 1_048_576} MB."
        } else null
    }

    private fun commit(
        context: Context,
        pkg: String,
        totalSize: Long,
        write: (PackageInstaller.Session) -> Unit
    ): Result {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setSize(totalSize)
            setAppPackageName(pkg)
        }
        val sessionId = try {
            installer.createSession(params)
        } catch (t: Throwable) {
            return Result(false, "Không thể tạo phiên cài đặt: ${t.message}", pkg)
        }

        val key = "s:$sessionId"
        val latch = CountDownLatch(1)
        val outcome = AtomicReference(Result(false, "Không có phản hồi từ trình cài đặt.", pkg))
        PackageInstallerStatusReceiver.registerCallback(key) { status, msg, reportedPkg ->
            outcome.set(Result(status == PackageInstaller.STATUS_SUCCESS, describe(status, msg), reportedPkg ?: pkg))
            latch.countDown()
        }

        val session = try {
            installer.openSession(sessionId)
        } catch (t: Throwable) {
            PackageInstallerStatusReceiver.unregisterCallback(key)
            try { installer.abandonSession(sessionId) } catch (_: Throwable) {}
            return Result(false, "Không mở được phiên cài đặt: ${t.message}", pkg)
        }
        try {
            write(session)
            if (pkg == context.packageName) {
                // Self-update: PackageManager kills this process during the install, so the
                // result broadcast is never delivered. BootReceiver (MY_PACKAGE_REPLACED) confirms.
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                    .putLong(KEY_PENDING_SELF_UPDATE, System.currentTimeMillis()).commit()
            }
            Log.i(TAG, "Committing session $sessionId for $pkg")
            session.commit(PackageInstallerStatusReceiver.pendingIntent(context, key, sessionId).intentSender)
        } catch (t: Throwable) {
            Log.e(TAG, "Writing session $sessionId failed: ${t.message}", t)
            PackageInstallerStatusReceiver.unregisterCallback(key)
            try { session.abandon() } catch (_: Throwable) {}
            return Result(false, "Lỗi ghi dữ liệu cài đặt: ${t.message}", pkg)
        } finally {
            try { session.close() } catch (_: Throwable) {}
        }

        return try {
            if (!latch.await(COMMIT_TIMEOUT_S, TimeUnit.SECONDS)) {
                PackageInstallerStatusReceiver.unregisterCallback(key)
                Result(false, "Quá thời gian chờ cài đặt (${COMMIT_TIMEOUT_S}s).", pkg)
            } else {
                outcome.get().also { Log.i(TAG, "Install $pkg -> ok=${it.ok} ${it.message}") }
            }
        } catch (e: InterruptedException) {
            PackageInstallerStatusReceiver.unregisterCallback(key)
            Result(false, "Quá trình cài đặt bị gián đoạn.", pkg)
        }
    }

    private fun describe(status: Int, msg: String?): String? = when (status) {
        PackageInstaller.STATUS_SUCCESS -> null
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "Không tương thích với đầu xe. ${msg.orEmpty()}"
        PackageInstaller.STATUS_FAILURE_CONFLICT -> "Xung đột với bản đã cài (khác chữ ký?). ${msg.orEmpty()}"
        PackageInstaller.STATUS_FAILURE_STORAGE -> "Không đủ bộ nhớ. ${msg.orEmpty()}"
        PackageInstaller.STATUS_FAILURE_INVALID -> "Gói cài đặt không hợp lệ. ${msg.orEmpty()}"
        PackageInstaller.STATUS_FAILURE_ABORTED -> "Đã hủy cài đặt. ${msg.orEmpty()}"
        PackageInstaller.STATUS_FAILURE_BLOCKED -> "Hệ thống chặn cài đặt. ${msg.orEmpty()}"
        else -> "Lỗi cài đặt (mã $status). ${msg.orEmpty()}"
    }?.trim()

    /** Uninstalls silently. [onResult] runs on the main thread (receiver) or a worker thread. */
    fun uninstallSilently(
        context: Context,
        packageName: String,
        onResult: (success: Boolean, errorMessage: String?) -> Unit
    ) {
        val appContext = context.applicationContext
        io.execute {
            val key = "u:$packageName"
            try {
                PackageInstallerStatusReceiver.registerCallback(key) { status, msg, _ ->
                    onResult(status == PackageInstaller.STATUS_SUCCESS, describe(status, msg))
                }
                appContext.packageManager.packageInstaller.uninstall(
                    packageName,
                    PackageInstallerStatusReceiver.pendingIntent(appContext, key, packageName.hashCode()).intentSender
                )
                Log.i(TAG, "Requested uninstall for $packageName")
            } catch (t: Throwable) {
                PackageInstallerStatusReceiver.unregisterCallback(key)
                Log.e(TAG, "Uninstall error: ${t.message}", t)
                onResult(false, t.message)
            }
        }
    }
}
