package com.mipago.ex2center.utils

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.mipago.ex2center.model.AppInfo
import com.mipago.ex2center.repository.AppRepository
import java.io.File

/**
 * "Kho ứng dụng" in MyEX2Center = EX2 Store (com.mipago.ex2store), a separate app that installs
 * and updates MyEX2Center and the other catalog apps.
 *
 * When EX2 Store is not installed yet (e.g. a car updated from MyEX2Center 1.2.0, whose built-in
 * catalog is gone since 1.3.0) this offers to install it:
 *  - download: only from the signature-verified catalog (remote or its verified cache), SHA-256
 *    checked, installed silently, Park only;
 *  - USB: an EX2Store-*.apk on a USB stick / Download folder, matched by package name.
 */
object StoreLauncher {

    private const val TAG = "StoreLauncher"
    const val STORE_PACKAGE = "com.mipago.ex2store"
    private const val RELEASES_URL = "https://github.com/ngocngo1213/MyEX2Center/releases"

    fun isStoreInstalled(activity: Activity): Boolean =
        PackageInstallerHelper.isPackageInstalled(activity, STORE_PACKAGE)

    fun open(activity: Activity) {
        if (launchStore(activity)) return
        AlertDialog.Builder(activity)
            .setTitle("Cài EX2 Store")
            .setMessage(
                "Từ MyEX2Center 1.3.0, việc cài và cập nhật ứng dụng (kể cả MyEX2Center) " +
                    "được chuyển sang ứng dụng riêng EX2 Store. Xe chưa có EX2 Store.\n\n" +
                    "• Tải và cài: cần Wi-Fi, xe ở số P.\n" +
                    "• Cài từ USB: chép file EX2Store-<phiên bản>.apk vào USB rồi cắm vào xe."
            )
            .setPositiveButton("Tải và cài") { _, _ ->
                ParkGate.runIfAllowed(activity) { downloadAndInstall(activity) }
            }
            .setNeutralButton("Cài từ USB") { _, _ ->
                ParkGate.runIfAllowed(activity) { installFromStorage(activity) }
            }
            .setNegativeButton("Để sau", null)
            .show()
    }

    private fun launchStore(activity: Activity): Boolean {
        val launch = activity.packageManager.getLaunchIntentForPackage(STORE_PACKAGE) ?: return false
        return try {
            activity.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (t: Throwable) {
            Log.w(TAG, "Cannot open EX2 Store: ${t.message}")
            false
        }
    }

    // ---------------------------------------------------------------------------------------

    private class Progress(activity: Activity, title: String) {
        val text = TextView(activity).apply { textSize = 18f }
        val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; isIndeterminate = true
        }
        var onCancel: (() -> Unit)? = null
        val dialog: AlertDialog = AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                val pad = (24 * activity.resources.displayMetrics.density).toInt()
                setPadding(pad, pad / 2, pad, 0)
                addView(text); addView(bar)
            })
            .setNegativeButton("Hủy") { _, _ -> onCancel?.invoke() }
            .setCancelable(false)
            .show()

        fun set(message: String, percent: Int? = null) {
            text.text = message
            if (percent == null) bar.isIndeterminate = true
            else { bar.isIndeterminate = false; bar.progress = percent }
        }
    }

    private fun downloadAndInstall(activity: Activity) {
        val progress = Progress(activity, "Cài EX2 Store")
        progress.set("Đang kiểm tra danh sách ứng dụng đã ký…")
        val repo = AppRepository(activity)
        var cancelled = false
        var handle: AppRepository.DownloadHandle? = null
        progress.onCancel = { cancelled = true; handle?.cancel() }
        Thread {
            val result = repo.loadCatalogBlocking(timeoutMs = 10_000)
            val entry: AppInfo? = result
                ?.takeIf { it.source != AppRepository.CatalogSource.BUNDLED }
                ?.apps?.firstOrNull { it.packageName == STORE_PACKAGE && it.isPublished }
            activity.runOnUiThread {
                if (cancelled || activity.isFinishing) return@runOnUiThread
                if (entry == null) {
                    progress.dialog.dismiss()
                    fail(
                        activity,
                        "Chưa lấy được thông tin EX2 Store đã xác minh chữ ký. " +
                            "Kiểm tra Wi-Fi rồi thử lại, hoặc cài từ USB."
                    )
                    return@runOnUiThread
                }
                progress.set("Đang tải EX2 Store ${entry.versionName}…", 0)
                handle = repo.downloadApk(
                    entry,
                    onProgress = { p -> progress.set("Đang tải EX2 Store ${entry.versionName}… $p%", p) },
                    onComplete = { file, error ->
                        if (cancelled || activity.isFinishing) return@downloadApk
                        if (file == null) {
                            progress.dialog.dismiss()
                            fail(activity, error ?: "Tải EX2 Store thất bại.")
                        } else {
                            install(activity, file, progress, deleteAfter = true)
                        }
                    }
                )
            }
        }.start()
    }

    private fun installFromStorage(activity: Activity) {
        val progress = Progress(activity, "Cài EX2 Store từ USB")
        progress.set("Đang tìm file EX2 Store trên USB / thư mục Download…")
        Thread {
            val file = try { LocalApkFinder.findApkForPackage(activity, STORE_PACKAGE) } catch (_: Throwable) { null }
            activity.runOnUiThread {
                if (activity.isFinishing) return@runOnUiThread
                if (file == null) {
                    progress.dialog.dismiss()
                    fail(
                        activity,
                        "Không thấy file EX2 Store trên USB hoặc thư mục Download.\n" +
                            "Tải EX2Store-<phiên bản>.apk từ trang Releases, chép vào USB rồi thử lại."
                    )
                } else {
                    install(activity, file, progress, deleteAfter = false)
                }
            }
        }.start()
    }

    private fun install(activity: Activity, file: File, progress: Progress, deleteAfter: Boolean) {
        progress.set("Đang cài EX2 Store…")
        progress.dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.isEnabled = false
        PackageInstallerHelper.install(activity, file, STORE_PACKAGE) { r ->
            if (deleteAfter) try { file.delete() } catch (_: Throwable) {}
            activity.runOnUiThread {
                progress.dialog.dismiss()
                if (activity.isFinishing) return@runOnUiThread
                if (r.ok) {
                    if (!launchStore(activity)) fail(activity, "Đã cài EX2 Store. Mở EX2 Store từ màn hình ứng dụng.")
                } else {
                    fail(activity, r.message ?: "Cài EX2 Store thất bại.")
                }
            }
        }
    }

    private fun fail(activity: Activity, message: String) {
        AlertDialog.Builder(activity)
            .setTitle("EX2 Store")
            .setMessage(message)
            .setPositiveButton("Đóng", null)
            .setNeutralButton("Trang Releases") { _, _ ->
                try {
                    activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_URL)))
                } catch (_: Throwable) {}
            }
            .show()
    }
}
