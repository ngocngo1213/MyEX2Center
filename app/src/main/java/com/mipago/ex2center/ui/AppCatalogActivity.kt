package com.mipago.ex2center.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mipago.ex2center.R
import com.mipago.ex2center.model.AppInfo
import com.mipago.ex2center.repository.AppRepository
import com.mipago.ex2center.utils.LocalApkFinder
import com.mipago.ex2center.utils.ParkGate
import com.mipago.ex2center.utils.WifiStatus
import com.mipago.ex2center.utils.PackageInstallerHelper
import java.io.File
import java.io.FileOutputStream
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Module 3: App Catalog Activity (Kho ứng dụng)
 *
 * Implements a 4x2 Bento Grid of applications matching the reference design.
 * Features Wi-Fi status display, remote catalog fetching, APK download,
 * silent installation, and uninstallation.
 */
class AppCatalogActivity : Activity() {

    private lateinit var appRepo: AppRepository
    private lateinit var rvAppCatalog: RecyclerView
    private lateinit var progressBarLoading: ProgressBar
    private lateinit var tvEmptyState: TextView
    private lateinit var tvCatalogWifiSsid: TextView
    private lateinit var tvCatalogWifiIp: TextView
    private lateinit var tvAvailableStorage: TextView
    private lateinit var btnRefreshCatalog: ImageView
    private lateinit var btnBackToDashboard: LinearLayout
    private lateinit var btnInstallFromStorage: LinearLayout

    private val REQUEST_CODE_PICK_APK = 1001

    private val appList = mutableListOf<AppInfo>()
    private lateinit var adapter: CatalogRecyclerAdapter
    private val iconCache = mutableMapOf<String, Bitmap>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_catalog)

        appRepo = AppRepository(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE), 100)
            }
        }

        initViews()
        setupWifiInfo()
        setupRecyclerView()
        loadCatalog()
    }

    private fun initViews() {
        rvAppCatalog = findViewById(R.id.rvAppCatalog)
        progressBarLoading = findViewById(R.id.progressBarLoading)
        tvEmptyState = findViewById(R.id.tvEmptyState)
        tvCatalogWifiSsid = findViewById(R.id.tvCatalogWifiSsid)
        tvCatalogWifiIp = findViewById(R.id.tvCatalogWifiIp)
        tvAvailableStorage = findViewById(R.id.tvAvailableStorage)
        btnRefreshCatalog = findViewById(R.id.btnRefreshCatalog)
        btnBackToDashboard = findViewById(R.id.btnBackToDashboard)
        btnInstallFromStorage = findViewById(R.id.btnInstallFromStorage)

        updateStorageInfo()

        btnBackToDashboard.setOnClickListener {
            finish()
        }

        btnRefreshCatalog.setOnClickListener {
            loadCatalog()
        }

        btnInstallFromStorage.setOnClickListener {
            showStorageInstallOptions()
        }
    }

    private fun updateStorageInfo() {
        try {
            val stat = StatFs(Environment.getDataDirectory().path)
            val freeBytes = stat.availableBytes
            val freeGb = freeBytes / (1024.0 * 1024.0 * 1024.0)
            tvAvailableStorage.text = String.format(java.util.Locale.US, "Bộ nhớ trống: %.1f GB", freeGb)
        } catch (t: Throwable) {
            tvAvailableStorage.text = "Bộ nhớ trống: --"
        }
    }

    private fun setupWifiInfo() {
        val wifi = WifiStatus.read(this)
        tvCatalogWifiSsid.text = wifi.ssidLabel
        tvCatalogWifiIp.text = wifi.ip
    }

    private fun setupRecyclerView() {
        adapter = CatalogRecyclerAdapter(appList)
        rvAppCatalog.layoutManager = GridLayoutManager(this, 3)
        rvAppCatalog.adapter = adapter
    }

    private fun loadCatalog() {
        updateStorageInfo()
        progressBarLoading.visibility = View.VISIBLE
        tvEmptyState.visibility = View.GONE

        appRepo.fetchCatalog { apps, error ->
            progressBarLoading.visibility = View.GONE
            if (apps != null && apps.isNotEmpty()) {
                appList.clear()
                appList.addAll(apps)
                adapter.notifyDataSetChanged()
                tvEmptyState.visibility = View.GONE
            } else {
                tvEmptyState.visibility = View.VISIBLE
                tvEmptyState.text = error ?: "Không có ứng dụng nào trong danh mục."
            }
        }
    }

    private fun getKeywordsForApp(app: AppInfo): List<String> {
        return when (app.packageName) {
            "vn.vietmap.live" -> listOf("vietmap", "live")
            "org.smarttube.stable" -> listOf("smarttube")
            "com.aaex.carbrowser" -> listOf("browser", "aaex")
            else -> listOf(app.name.lowercase())
        }
    }

    /** Installing replaces running apps (e.g. navigation) — only allowed in Park. */
    private fun handleInstall(app: AppInfo) {
        ParkGate.runIfAllowed(this) { startInstall(app) }
    }

    private fun startInstall(app: AppInfo) {
        // Step 1: Check if an APK already exists locally on USB or Downloads
        val keywords = getKeywordsForApp(app)
        val localApk = LocalApkFinder.findApkForPackage(this, app.packageName, keywords)
        if (localApk != null && localApk.exists()) {
            Toast.makeText(this, "Đã tìm thấy ${localApk.name} trên thiết bị/USB. Đang cài đặt...", Toast.LENGTH_SHORT).show()
            if (app.isSelf) Toast.makeText(this, "MyEX2Center sẽ tự khởi động lại sau khi cập nhật.", Toast.LENGTH_LONG).show()
            installLocalApkFile(localApk, app.packageName, app)
            return
        }

        // Not published for network download (e.g. Vietmap .xapk): install from USB / device.
        if (!app.isPublished) {
            Toast.makeText(this, "${app.name} chưa có bản tải qua mạng. Hãy chọn file .apk/.xapk từ USB hoặc máy.", Toast.LENGTH_LONG).show()
            showStorageInstallOptions()
            return
        }

        // Step 2: Download from remote apkUrl
        app.status = AppInfo.InstallStatus.DOWNLOADING
        app.downloadProgress = 0
        adapter.notifyDataSetChanged()

        appRepo.downloadApk(
            app = app,
            onProgress = { progress ->
                app.downloadProgress = progress
                adapter.notifyDataSetChanged()
            },
            onComplete = { apkFile, error ->
                if (apkFile != null && apkFile.exists()) {
                    // The download can take minutes: re-check the gear right before installing.
                    ParkGate.runIfAllowed(this, onBlocked = {
                        if (apkFile.exists()) apkFile.delete()
                        app.status = AppInfo.InstallStatus.NOT_INSTALLED
                        adapter.notifyDataSetChanged()
                    }) {
                        app.status = AppInfo.InstallStatus.INSTALLING
                        adapter.notifyDataSetChanged()

                        PackageInstallerHelper.installSilently(this, apkFile, app.packageName) { success, msg, installedPkg ->
                            runOnUiThread {
                                if (apkFile.exists()) apkFile.delete()
                                if (success) {
                                    if (!installedPkg.isNullOrEmpty() && installedPkg != app.packageName) {
                                        val idx = appList.indexOf(app)
                                        if (idx != -1) {
                                            val updated = app.copy(packageName = installedPkg)
                                            updated.status = AppInfo.InstallStatus.INSTALLED
                                            appList[idx] = updated
                                        }
                                    } else {
                                        app.status = AppInfo.InstallStatus.INSTALLED
                                    }
                                    Toast.makeText(this, "Đã cài đặt thành công: ${app.name}", Toast.LENGTH_LONG).show()
                                } else {
                                    app.status = AppInfo.InstallStatus.NOT_INSTALLED
                                    Toast.makeText(this, "Cài đặt thất bại: ${msg ?: "Lỗi"}", Toast.LENGTH_LONG).show()
                                }
                                adapter.notifyDataSetChanged()
                            }
                        }
                    }
                } else {
                    app.status = AppInfo.InstallStatus.NOT_INSTALLED
                    adapter.notifyDataSetChanged()

                    // Step 3: Show smart fallback dialog on download failure
                    showDownloadErrorDialog(app, error)
                }
            }
        )
    }

    private fun showDownloadErrorDialog(app: AppInfo, error: String?) {
        val targetBrowserUrl = if (app.browserUrl.isNotEmpty()) {
            app.browserUrl
        } else {
            "https://apkpure.com/search?q=${app.packageName}"
        }

        AlertDialog.Builder(this)
            .setTitle("Không thể tải ${app.name}")
            .setMessage("Không thể tải trực tiếp file APK qua mạng:\n${error ?: "Lỗi kết nối"}\n\nBạn có thể mở Trình duyệt để tải file APK hoặc cắm USB chứa file để cài đặt trực tiếp.")
            .setPositiveButton("🌐 Mở Trình duyệt tải") { _, _ ->
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(targetBrowserUrl))
                    startActivity(intent)
                } catch (t: Throwable) {
                    Toast.makeText(this, "Không thể mở trình duyệt: ${t.message}", Toast.LENGTH_SHORT).show()
                }
            }
            .setNeutralButton("📁 Chọn từ USB / Máy") { _, _ ->
                showStorageInstallOptions()
            }
            .setNegativeButton("Đóng", null)
            .show()
    }

    private fun showStorageInstallOptions() {
        val localApks = LocalApkFinder.scanAllApks(this)
        if (localApks.isNotEmpty()) {
            val items = localApks.map { f ->
                val sizeMb = f.length() / (1024.0 * 1024.0)
                "${f.name} (%.1f MB)".format(java.util.Locale.US, sizeMb)
            }.toMutableList()
            items.add("📁 Chọn tệp APK / XAPK khác...")

            AlertDialog.Builder(this)
                .setTitle("Chọn file APK / XAPK trên USB / Thiết bị")
                .setItems(items.toTypedArray()) { _, which ->
                    if (which < localApks.size) {
                        val chosenFile = localApks[which]
                        installLocalApkFile(chosenFile, null, null)
                    } else {
                        openFilePicker()
                    }
                }
                .setNegativeButton("Hủy", null)
                .show()
        } else {
            openFilePicker()
        }
    }

    private fun openFilePicker() {
        try {
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "*/*"
                addCategory(Intent.CATEGORY_OPENABLE)
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                    "application/vnd.android.package-archive",
                    "application/zip",
                    "application/xapk-package-archive",
                    "application/octet-stream",
                    "*/*"
                ))
            }
            startActivityForResult(intent, REQUEST_CODE_PICK_APK)
        } catch (t: Throwable) {
            Toast.makeText(this, "Không thể mở bộ chọn tệp: ${t.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_PICK_APK && resultCode == Activity.RESULT_OK) {
            data?.data?.let { uri ->
                handlePickedApkUri(uri)
            }
        }
    }

    private fun handlePickedApkUri(uri: Uri) {
        Toast.makeText(this, "Đang đọc tệp đã chọn...", Toast.LENGTH_SHORT).show()
        Thread {
            val temp = File(cacheDir, "picked_manual_install.tmp")
            try {
                if (temp.exists()) temp.delete()
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(temp).use { output -> input.copyTo(output) }
                }

                // Accepts a plain .apk or a split bundle (.xapk / .apks).
                val meta = PackageInstallerHelper.inspect(this, temp)
                if (meta == null) {
                    temp.delete()
                    runOnUiThread {
                        Toast.makeText(this, "Tệp đã chọn không phải APK/XAPK hợp lệ.", Toast.LENGTH_LONG).show()
                    }
                    return@Thread
                }
                val ext = if (meta.isBundle) "xapk" else "apk"
                val staged = File(cacheDir, "picked_${meta.packageName}.$ext")
                if (staged.exists()) staged.delete()
                if (!temp.renameTo(staged)) {
                    temp.delete()
                    runOnUiThread { Toast.makeText(this, "Không thể chuẩn bị tệp cài đặt.", Toast.LENGTH_LONG).show() }
                    return@Thread
                }
                runOnUiThread {
                    installLocalApkFile(staged, meta.packageName, null, deleteAfter = true)
                }
            } catch (t: Throwable) {
                temp.delete()
                runOnUiThread {
                    Toast.makeText(this, "Lỗi đọc file: ${t.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    /** [deleteAfter]: true only for our own staged copies — never delete the user's USB file. */
    private fun installLocalApkFile(
        apkFile: File,
        targetPkg: String?,
        matchingApp: AppInfo?,
        deleteAfter: Boolean = false
    ) {
        ParkGate.runIfAllowed(
            this,
            onBlocked = { if (deleteAfter) apkFile.delete() }
        ) { doInstallLocalApkFile(apkFile, targetPkg, matchingApp, deleteAfter) }
    }

    private fun doInstallLocalApkFile(apkFile: File, targetPkg: String?, matchingApp: AppInfo?, deleteAfter: Boolean) {
        if (matchingApp != null) {
            matchingApp.status = AppInfo.InstallStatus.INSTALLING
            adapter.notifyDataSetChanged()
        }

        Toast.makeText(this, "Đang cài đặt: ${apkFile.name}...", Toast.LENGTH_SHORT).show()

        PackageInstallerHelper.installSilently(this, apkFile, targetPkg ?: "") { success, msg, _ ->
            if (deleteAfter) apkFile.delete()
            runOnUiThread {
                if (success) {
                    Toast.makeText(this, "Cài đặt thành công: ${apkFile.name}!", Toast.LENGTH_LONG).show()
                    loadCatalog()
                } else {
                    if (matchingApp != null) {
                        matchingApp.status = AppInfo.InstallStatus.NOT_INSTALLED
                        adapter.notifyDataSetChanged()
                    }
                    Toast.makeText(this, "Cài đặt thất bại: ${msg ?: "Lỗi không xác định"}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun handleUninstall(app: AppInfo) {
        AlertDialog.Builder(this)
            .setTitle("Xác nhận gỡ cài đặt")
            .setMessage("Bạn có chắc chắn muốn gỡ ứng dụng ${app.name} khỏi hệ thống không?")
            .setPositiveButton("Gỡ cài đặt") { _, _ ->
                ParkGate.runIfAllowed(this) {
                    PackageInstallerHelper.uninstallSilently(this, app.packageName) { success, _ ->
                        runOnUiThread {
                            if (success) {
                                app.status = AppInfo.InstallStatus.NOT_INSTALLED
                                Toast.makeText(this, "Đã gỡ cài đặt ${app.name}", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(this, "Không thể gỡ cài đặt ứng dụng.", Toast.LENGTH_SHORT).show()
                            }
                            adapter.notifyDataSetChanged()
                        }
                    }
                }
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    inner class CatalogRecyclerAdapter(private val items: List<AppInfo>) :
        RecyclerView.Adapter<CatalogRecyclerAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val ivIcon: ImageView = view.findViewById(R.id.ivAppIcon)
            val tvBadge: TextView = view.findViewById(R.id.tvAppBadge)
            val tvName: TextView = view.findViewById(R.id.tvAppName)
            val tvSub: TextView = view.findViewById(R.id.tvAppSub)
            val tvDesc: TextView = view.findViewById(R.id.tvAppDesc)
            val pbProgress: ProgressBar = view.findViewById(R.id.pbAppProgress)
            val layoutInstall: LinearLayout = view.findViewById(R.id.layoutInstallBtn)
            val tvInstallText: TextView = view.findViewById(R.id.tvInstallBtnText)
            val layoutInstalled: LinearLayout = view.findViewById(R.id.layoutInstalledActions)
            val btnOpen: LinearLayout = view.findViewById(R.id.btnOpenApp)
            val btnDelete: ImageView = view.findViewById(R.id.btnDeleteApp)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app_card, parent, false)
            return ViewHolder(view)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val app = items[position]

            holder.tvName.text = app.name
            holder.tvSub.text = "v${app.versionName} • ${if (app.sizeMb > 0) "${app.sizeMb} MB" else "-- MB"}"
            holder.tvDesc.text = app.description

            // Priority 1, 2, 3 Icon Loading
            loadAppIcon(app, holder.ivIcon)

            // Status Badge & Action layout configuration
            when (app.status) {
                AppInfo.InstallStatus.INSTALLED -> {
                    holder.tvBadge.text = "Đã cài"
                    holder.tvBadge.setTextColor(Color.parseColor("#4ADE80"))
                    holder.tvBadge.setBackgroundResource(R.drawable.bg_badge_installed)

                    holder.pbProgress.visibility = View.GONE
                    holder.layoutInstall.visibility = View.GONE
                    holder.layoutInstalled.visibility = View.VISIBLE

                    holder.btnOpen.setOnClickListener {
                        val launchIntent = packageManager.getLaunchIntentForPackage(app.packageName)
                        if (launchIntent != null) {
                            startActivity(launchIntent)
                        } else {
                            Toast.makeText(this@AppCatalogActivity, "Không tìm thấy Activity khởi chạy.", Toast.LENGTH_SHORT).show()
                        }
                    }

                    // MyEX2Center cannot remove itself from its own store.
                    holder.btnDelete.visibility = if (app.isSelf) View.GONE else View.VISIBLE
                    holder.btnDelete.setOnClickListener {
                        if (!app.isSelf) handleUninstall(app)
                    }
                }
                AppInfo.InstallStatus.UPDATE_AVAILABLE -> {
                    holder.tvBadge.text = "Có cập nhật"
                    holder.tvBadge.setTextColor(Color.parseColor("#FBBF24"))
                    holder.tvBadge.setBackgroundResource(R.drawable.bg_badge_update)

                    holder.pbProgress.visibility = View.GONE
                    holder.layoutInstall.visibility = View.VISIBLE
                    holder.layoutInstalled.visibility = View.GONE
                    holder.tvInstallText.text = when {
                        !app.isPublished -> "Cập nhật từ USB / Máy"
                        app.isSelf -> "Cập nhật hệ thống"
                        else -> "Cập nhật"
                    }

                    holder.layoutInstall.setOnClickListener {
                        handleInstall(app)
                    }
                }
                AppInfo.InstallStatus.NOT_INSTALLED -> {
                    holder.tvBadge.text = "Chưa cài"
                    holder.tvBadge.setTextColor(Color.parseColor("#94A3B8"))
                    holder.tvBadge.setBackgroundResource(R.drawable.bg_badge_not_installed)

                    holder.pbProgress.visibility = View.GONE
                    holder.layoutInstall.visibility = View.VISIBLE
                    holder.layoutInstalled.visibility = View.GONE
                    holder.tvInstallText.text = if (app.isPublished) "Cài đặt" else "Cài từ USB / Máy"

                    holder.layoutInstall.setOnClickListener {
                        handleInstall(app)
                    }
                }
                AppInfo.InstallStatus.DOWNLOADING -> {
                    holder.tvBadge.text = "${app.downloadProgress}%"
                    holder.tvBadge.setTextColor(Color.parseColor("#38BDF8"))
                    holder.tvBadge.setBackgroundResource(R.drawable.bg_badge_not_installed)

                    holder.pbProgress.visibility = View.VISIBLE
                    holder.pbProgress.progress = app.downloadProgress
                    holder.layoutInstall.visibility = View.VISIBLE
                    holder.layoutInstalled.visibility = View.GONE
                    holder.layoutInstall.setOnClickListener(null) // busy: no second download
                    holder.tvInstallText.text = "Đang tải (${app.downloadProgress}%)"
                }
                AppInfo.InstallStatus.INSTALLING -> {
                    holder.tvBadge.text = "Đang cài..."
                    holder.tvBadge.setTextColor(Color.parseColor("#4ADE80"))
                    holder.tvBadge.setBackgroundResource(R.drawable.bg_badge_installed)

                    holder.pbProgress.visibility = View.GONE
                    holder.layoutInstall.visibility = View.VISIBLE
                    holder.layoutInstalled.visibility = View.GONE
                    holder.layoutInstall.setOnClickListener(null)
                    holder.tvInstallText.text = "Đang cài đặt..."
                }
            }
        }

        /**
         * 3-tier Icon Loading Hierarchy:
         * 1. If installed, query system PackageManager for real application icon
         * 2. If not installed, load from res/drawable using iconResName defined in apps.json
         * 3. If remote iconUrl is available, fetch asynchronously and cache; fallback to package mapping
         */
        private fun loadAppIcon(app: AppInfo, imageView: ImageView) {
            // Priority 1: System installed package icon
            if (app.status == AppInfo.InstallStatus.INSTALLED || app.status == AppInfo.InstallStatus.UPDATE_AVAILABLE) {
                try {
                    val sysIcon = packageManager.getApplicationIcon(app.packageName)
                    imageView.setImageDrawable(sysIcon)
                    return
                } catch (ignored: Throwable) {}
            }

            // Priority 2: Local resource declared in apps.json (e.g. ic_app_vietmap)
            if (app.iconResName.isNotEmpty()) {
                val resId = resources.getIdentifier(app.iconResName, "drawable", packageName)
                if (resId != 0) {
                    imageView.setImageResource(resId)
                    return
                }
            }

            // Priority 2b: Fallback by known package name mapping
            val knownRes = when (app.packageName) {
                "com.mipago.ex2center", "com.geely.ex2center", "com.geely.ex2" -> R.drawable.img_my_ex2
                "vn.vietmap.live" -> R.drawable.ic_app_vietmap
                "org.smarttube.stable", "com.teamsmart.videomanager.tv", "com.liskovsoft.smartyoutubetv2.tv" -> R.drawable.ic_app_smarttube
                "com.aaex.carbrowser", "com.aaexbrowser", "mark.via.gp" -> R.drawable.ic_app_browser
                else -> 0
            }
            if (knownRes != 0) {
                imageView.setImageResource(knownRes)
                return
            }

            // Priority 3: Remote iconUrl with memory cache
            if (app.iconUrl.isNotEmpty()) {
                val cached = iconCache[app.iconUrl]
                if (cached != null) {
                    imageView.setImageBitmap(cached)
                    return
                }

                imageView.setImageResource(R.drawable.ic_store)
                Thread {
                    try {
                        val conn = java.net.URL(app.iconUrl).openConnection() as java.net.HttpURLConnection
                        conn.connectTimeout = 4000
                        conn.readTimeout = 4000
                        if (conn.responseCode == 200) {
                            val bmp = BitmapFactory.decodeStream(conn.inputStream)
                            if (bmp != null) {
                                synchronized(iconCache) {
                                    iconCache[app.iconUrl] = bmp
                                }
                                runOnUiThread {
                                    imageView.setImageBitmap(bmp)
                                }
                            }
                        }
                    } catch (ignored: Throwable) {}
                }.start()
            } else {
                imageView.setImageResource(R.drawable.ic_store)
            }
        }
    }
}
