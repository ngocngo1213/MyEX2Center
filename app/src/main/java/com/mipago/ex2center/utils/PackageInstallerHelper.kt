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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

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

    private val ABI_SPLIT = Regex("""^config\.(arm64_v8a|armeabi_v7a|armeabi|x86_64|x86|mips64|mips)\.apk$""")
    private val io = Executors.newSingleThreadExecutor()

    data class Result(val ok: Boolean, val message: String? = null, val packageName: String? = null)

    /** What a local .apk / .xapk file contains (read without installing). */
    data class PackageMeta(
        val packageName: String,
        val versionCode: Long,
        val versionName: String?,
        val isBundle: Boolean
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

    /** Reads package name/version of an .apk or .xapk. Blocking — call off the main thread. */
    fun inspect(context: Context, file: File): PackageMeta? {
        if (!file.isFile || file.length() <= 0) return null
        return try {
            if (isSplitBundle(file)) inspectBundle(file)
            else context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)?.let {
                PackageMeta(it.packageName, installedVersionCode(it), it.versionName, false)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "inspect ${file.name}: ${t.message}")
            null
        }
    }

    private fun inspectBundle(file: File): PackageMeta? = ZipFile(file).use { zip ->
        val manifest = readBundleManifest(zip) ?: return null
        val pkg = manifest.optString("package_name").takeIf { it.isNotBlank() } ?: return null
        PackageMeta(
            packageName = pkg,
            versionCode = manifest.optString("version_code").toLongOrNull()
                ?: manifest.optLong("version_code", 0L),
            versionName = manifest.optString("version_name").takeIf { it.isNotBlank() },
            isBundle = true
        )
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
            val manifest = readBundleManifest(zip)
            val pkg = manifest?.optString("package_name")?.takeIf { it.isNotBlank() } ?: expectedPackage
                ?: return Result(false, "Gói XAPK thiếu manifest.json (không xác định được ứng dụng).")
            if (expectedPackage != null && pkg != expectedPackage) {
                return Result(false, "Sai ứng dụng: gói chứa $pkg, cần $expectedPackage.", pkg)
            }
            val minSdk = manifest?.optString("min_sdk_version")?.toIntOrNull() ?: 0
            if (minSdk > Build.VERSION.SDK_INT) {
                return Result(false, "Ứng dụng cần Android API $minSdk, đầu xe là API ${Build.VERSION.SDK_INT}.", pkg)
            }

            val apks = rootApkEntries(zip)
            if (apks.isEmpty()) return Result(false, "Gói XAPK không chứa file APK nào.", pkg)

            // Pick the best native split for this head unit (Build.SUPPORTED_ABIS is in
            // preference order). Density/language splits are all kept.
            val deviceAbis = Build.SUPPORTED_ABIS.map { it.replace('-', '_') }
            val abiSplits = apks.filter { ABI_SPLIT.matches(it.name) }
            val chosenAbi = deviceAbis.firstOrNull { abi -> abiSplits.any { it.name == "config.$abi.apk" } }
            if (abiSplits.isNotEmpty() && chosenAbi == null) {
                val offered = abiSplits.map { it.name.removePrefix("config.").removeSuffix(".apk").replace('_', '-') }
                return Result(false, abiMessage(offered.toSet()), pkg)
            }
            val selected = apks.filter { !ABI_SPLIT.matches(it.name) || it.name == "config.$chosenAbi.apk" }
            if (selected.any { it.size < 0 }) return Result(false, "Gói XAPK bị hỏng (không đọc được kích thước).", pkg)
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
