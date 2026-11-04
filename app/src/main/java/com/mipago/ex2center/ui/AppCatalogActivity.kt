package com.mipago.ex2center.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.util.LruCache
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
import com.mipago.ex2center.utils.PackageInstallerHelper
import com.mipago.ex2center.utils.ParkGate
import com.mipago.ex2center.utils.WifiStatus
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

/**
 * Module 3: App Catalog Activity (Kho ứng dụng)
 *
 * - Catalog from [AppRepository] (remote, bundled fallback), 3-column grid.
 * - Downloads are resumable and cancellable (the install button becomes "Hủy"), verified by
 *   SHA-256, then installed silently; everything stops/cleans up when the screen is destroyed.
 * - Progress updates rebind only the affected card (payload), never the whole grid.
 * - Icons: installed app icon / bundled drawable / remote URL, cached and tag-checked so a
 *   recycled ViewHolder never shows another app's icon.
 * - Storage scans (USB / Download) parse package files and always run off the main thread.
 */
class AppCatalogActivity : Activity() {

    companion object {
        private const val REQUEST_CODE_PICK_APK = 1001
        private const val PAYLOAD_PROGRESS = "progress"

        /** Process-wide remote icon cache (~4 MB). */
        private val remoteIconCache = object : LruCache<String, Bitmap>(4 * 1024 * 1024) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
        }
        private val iconExecutor = Executors.newFixedThreadPool(2)
    }

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

    private val appList = mutableListOf<AppInfo>()
    private lateinit var adapter: CatalogRecyclerAdapter

    /** Running downloads by package — cancelled when the screen goes away. */
    private val activeDownloads = HashMap<String, AppRepository.DownloadHandle>()
    /** Installed-app icons, rebuilt on every catalog load (an update may change the icon). */
    private val installedIconCache = HashMap<String, Drawable>()
    private val remoteIconsInFlight = HashSet<String>()
    /** Package-file parsing (USB scans, picked files) — off the main thread, one at a time. */
    private val scanExecutor = Executors.newSingleThreadExecutor()

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.mipago.ex2center.utils.ThemeManager.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_catalog)
        com.mipago.ex2center.utils.ThemeManager.applyWindow(this)

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

    override fun onDestroy() {
        // Stop transfers we own; partial files are kept so the next attempt resumes.
        activeDownloads.values.forEach { it.cancel() }
        activeDownloads.clear()
        scanExecutor.shutdownNow()
        super.onDestroy()
    }

    /** True once the screen is closing: async callbacks must not touch views any more. */
    private val gone: Boolean get() = isFinishing || isDestroyed

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

        btnBackToDashboard.setOnClickListener { finish() }
        btnRefreshCatalog.setOnClickListener {
            if (activeDownloads.isNotEmpty()) {
                toast("Đang tải ứng dụng — hãy đợi tải xong hoặc hủy trước khi làm mới.")
            } else {
                loadCatalog()
            }
        }
        btnInstallFromStorage.setOnClickListener { showStorageInstallOptions() }
    }

    private fun toast(msg: String, long: Boolean = false) {
        if (gone) return
        Toast.makeText(this, msg, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }

    private fun updateStorageInfo() {
        try {
            val freeGb = StatFs(Environment.getDataDirectory().path).availableBytes / (1024.0 * 1024.0 * 1024.0)
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
        rvAppCatalog.layoutManager = GridLayoutManager(this, 2) // 2 large cards per row: easier to read and hit in the car
        rvAppCatalog.adapter = adapter
        // Progress changes are applied in place; skip the default change cross-fade.
        (rvAppCatalog.itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)?.supportsChangeAnimations = false
    }

    private fun loadCatalog() {
        updateStorageInfo()
        progressBarLoading.visibility = View.VISIBLE
        tvEmptyState.visibility = View.GONE

        appRepo.fetchCatalog { apps, error ->
            if (gone) return@fetchCatalog
            progressBarLoading.visibility = View.GONE
            if (apps != null && apps.isNotEmpty()) {
                installedIconCache.clear()
                appList.clear()
                appList.addAll(apps)
                adapter.notifyDataSetChanged()
                tvEmptyState.visibility = View.GONE
                appRepo.cleanupDownloads(apps)
            } else {
                tvEmptyState.visibility = View.VISIBLE
                tvEmptyState.text = error ?: "Không có ứng dụng nào trong danh mục."
            }
        }
    }

    private fun indexOf(app: AppInfo) = appList.indexOfFirst { it.packageName == app.packageName }

    private fun refreshCard(app: AppInfo, payload: Any? = null) {
        if (gone) return
        val i = indexOf(app)
        if (i >= 0) adapter.notifyItemChanged(i, payload)
    }

    private fun setStatus(app: AppInfo, status: AppInfo.InstallStatus) {
        app.status = status
        refreshCard(app)
    }

    /** Status the card should return to after a failed / cancelled attempt. */
    private fun restingStatus(app: AppInfo): AppInfo.InstallStatus {
        val installed = PackageInstallerHelper.getInstalledPackageInfo(this, app.packageName)
            ?: return AppInfo.InstallStatus.NOT_INSTALLED
        return if (app.versionCode > PackageInstallerHelper.installedVersionCode(installed))
            AppInfo.InstallStatus.UPDATE_AVAILABLE else AppInfo.InstallStatus.INSTALLED
    }

    // ---------------------------------------------------------------------------------------
    // INSTALL FLOW
    // ---------------------------------------------------------------------------------------

    /** Installing replaces running apps (e.g. navigation) — only allowed in Park. */
    private fun handleInstall(app: AppInfo) {
        if (activeDownloads.containsKey(app.packageName)) return
        ParkGate.runIfAllowed(this) { startInstall(app) }
    }

    private fun startInstall(app: AppInfo) {
        if (gone) return
        // Step 1: an exact package match already on USB / Downloads? (parsing is off-thread)
        app.status = AppInfo.InstallStatus.INSTALLING
        refreshCard(app)
        scanExecutor.execute {
            val local = try { LocalApkFinder.findApkForPackage(this, app.packageName) } catch (_: Throwable) { null }
            runOnUiThread {
                if (gone) return@runOnUiThread
                when {
                    local != null -> {
                        toast("Đã tìm thấy ${local.name} trên thiết bị/USB. Đang cài đặt...")
                        if (app.isSelf) toast("MyEX2Center sẽ tự khởi động lại sau khi cập nhật.", long = true)
                        installLocalApkFile(local, app.packageName, app)
                    }
                    !app.isPublished -> {
                        // Not published for network download (e.g. Vietmap .xapk).
                        setStatus(app, restingStatus(app))
                        toast("${app.name} chưa có bản tải qua mạng. Hãy chọn file .apk/.xapk từ USB hoặc máy.", long = true)
                        showStorageInstallOptions()
                    }
                    else -> startDownload(app)
                }
            }
        }
    }

    private fun startDownload(app: AppInfo) {
        app.status = AppInfo.InstallStatus.DOWNLOADING
        app.downloadProgress = 0
        refreshCard(app)

        val handle = appRepo.downloadApk(
            app = app,
            onProgress = { progress ->
                if (!gone) {
                    app.downloadProgress = progress
                    refreshCard(app, PAYLOAD_PROGRESS)
                }
            },
            onComplete = { file, error ->
                activeDownloads.remove(app.packageName)
                if (gone) return@downloadApk
                when {
                    file != null -> installDownloaded(app, file)
                    error == AppRepository.ERROR_CANCELLED -> {
                        setStatus(app, restingStatus(app))
                        toast("Đã hủy tải ${app.name}. Lần sau sẽ tải tiếp phần còn lại.")
                    }
                    else -> {
                        setStatus(app, restingStatus(app))
                        showDownloadErrorDialog(app, error)
                    }
                }
            }
        )
        activeDownloads[app.packageName] = handle
    }

    private fun installDownloaded(app: AppInfo, file: File) {
        // The download can take minutes: re-check the gear right before installing.
        ParkGate.runIfAllowed(this, onBlocked = {
            file.delete()
            setStatus(app, restingStatus(app))
        }) {
            setStatus(app, AppInfo.InstallStatus.INSTALLING)
            if (app.isSelf) toast("MyEX2Center sẽ tự khởi động lại sau khi cập nhật.", long = true)
            PackageInstallerHelper.install(this, file, app.packageName) { result ->
                file.delete()
                runOnUiThread {
                    if (gone) return@runOnUiThread
                    if (result.ok) {
                        toast("Đã cài đặt thành công: ${app.name}", long = true)
                        loadCatalog()
                    } else {
                        setStatus(app, restingStatus(app))
                        toast("Cài đặt thất bại: ${result.message ?: "Lỗi"}", long = true)
                    }
                }
            }
        }
    }

    private fun cancelDownload(app: AppInfo) {
        activeDownloads[app.packageName]?.cancel()
    }

    private fun showDownloadErrorDialog(app: AppInfo, error: String?) {
        if (gone) return
        val builder = AlertDialog.Builder(this)
            .setTitle("Không thể tải ${app.name}")
            .setMessage("${error ?: "Lỗi kết nối"}\n\nBạn có thể thử lại, hoặc cắm USB chứa file .apk/.xapk để cài trực tiếp.")
            .setPositiveButton("Thử lại") { _, _ -> handleInstall(app) }
            .setNeutralButton("Chọn từ USB / Máy") { _, _ -> showStorageInstallOptions() }
            .setNegativeButton("Đóng", null)
        builder.show()
    }

    // ---------------------------------------------------------------------------------------
    // LOCAL / USB INSTALL
    // ---------------------------------------------------------------------------------------

    private fun showStorageInstallOptions() {
        toast("Đang tìm file .apk / .xapk trên máy và USB...")
        scanExecutor.execute {
            val localApks = try { LocalApkFinder.scanAllApks(this) } catch (_: Throwable) { emptyList() }
            runOnUiThread {
                if (gone) return@runOnUiThread
                if (localApks.isEmpty()) {
                    openFilePicker()
                    return@runOnUiThread
                }
                val items = localApks.map { f ->
                    "${f.name} (%.1f MB)".format(java.util.Locale.US, f.length() / (1024.0 * 1024.0))
                }.toMutableList()
                items.add("Chọn tệp APK / XAPK khác...")
                AlertDialog.Builder(this)
                    .setTitle("Chọn file APK / XAPK trên USB / Thiết bị")
                    .setItems(items.toTypedArray()) { _, which ->
                        if (which < localApks.size) installLocalApkFile(localApks[which], null, null)
                        else openFilePicker()
                    }
                    .setNegativeButton("Hủy", null)
                    .show()
            }
        }
    }

    private fun openFilePicker() {
        try {
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "*/*"
                addCategory(Intent.CATEGORY_OPENABLE)
                putExtra(
                    Intent.EXTRA_MIME_TYPES, arrayOf(
                        "application/vnd.android.package-archive",
                        "application/zip",
                        "application/xapk-package-archive",
                        "application/octet-stream",
                        "*/*"
                    )
                )
            }
            startActivityForResult(intent, REQUEST_CODE_PICK_APK)
        } catch (t: Throwable) {
            toast("Không thể mở bộ chọn tệp: ${t.message}")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_PICK_APK && resultCode == RESULT_OK) {
            data?.data?.let { handlePickedApkUri(it) }
        }
    }

    private fun handlePickedApkUri(uri: Uri) {
        toast("Đang đọc tệp đã chọn...")
        scanExecutor.execute {
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
                    runOnUiThread { toast("Tệp đã chọn không phải APK/XAPK hợp lệ.", long = true) }
                    return@execute
                }
                val staged = File(cacheDir, "picked_${meta.packageName}.${if (meta.isBundle) "xapk" else "apk"}")
                if (staged.exists()) staged.delete()
                if (!temp.renameTo(staged)) {
                    temp.delete()
                    runOnUiThread { toast("Không thể chuẩn bị tệp cài đặt.", long = true) }
                    return@execute
                }
                runOnUiThread {
                    if (gone) staged.delete() else installLocalApkFile(staged, meta.packageName, null, deleteAfter = true)
                }
            } catch (t: Throwable) {
                temp.delete()
                runOnUiThread { toast("Lỗi đọc file: ${t.message}", long = true) }
            }
        }
    }

    /** [deleteAfter]: true only for our own staged copies — never delete the user's USB file. */
    private fun installLocalApkFile(
        apkFile: File,
        targetPkg: String?,
        matchingApp: AppInfo?,
        deleteAfter: Boolean = false
    ) {
        ParkGate.runIfAllowed(this, onBlocked = {
            if (deleteAfter) apkFile.delete()
            matchingApp?.let { setStatus(it, restingStatus(it)) }
        }) {
            matchingApp?.let { setStatus(it, AppInfo.InstallStatus.INSTALLING) }
            toast("Đang cài đặt: ${apkFile.name}...")
            PackageInstallerHelper.install(this, apkFile, targetPkg) { result ->
                if (deleteAfter) apkFile.delete()
                runOnUiThread {
                    if (gone) return@runOnUiThread
                    if (result.ok) {
                        toast("Cài đặt thành công: ${apkFile.name}!", long = true)
                        loadCatalog()
                    } else {
                        matchingApp?.let { setStatus(it, restingStatus(it)) }
                        toast("Cài đặt thất bại: ${result.message ?: "Lỗi không xác định"}", long = true)
                    }
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
                    PackageInstallerHelper.uninstallSilently(this, app.packageName) { success, msg ->
                        runOnUiThread {
                            if (gone) return@runOnUiThread
                            if (success) {
                                installedIconCache.remove(app.packageName)
                                setStatus(app, AppInfo.InstallStatus.NOT_INSTALLED)
                                toast("Đã gỡ cài đặt ${app.name}")
                            } else {
                                toast("Không thể gỡ cài đặt ứng dụng${msg?.let { ": $it" } ?: "."}")
                            }
                        }
                    }
                }
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    // ---------------------------------------------------------------------------------------
    // ADAPTER
    // ---------------------------------------------------------------------------------------

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

        override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
            if (payloads.isNotEmpty() && payloads.all { it == PAYLOAD_PROGRESS }) {
                bindProgress(holder, items[position]) // cheap path: no icon / text rebinding
            } else {
                onBindViewHolder(holder, position)
            }
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val app = items[position]

            holder.tvName.text = app.name
            holder.tvSub.text = "v${app.versionName} • ${if (app.sizeMb > 0) "${app.sizeMb} MB" else "-- MB"}"
            holder.tvDesc.text = app.description
            loadAppIcon(app, holder.ivIcon)

            // Reset listeners every bind: a recycled holder must never keep another state's action.
            holder.layoutInstall.setOnClickListener(null)
            holder.btnOpen.setOnClickListener(null)
            holder.btnDelete.setOnClickListener(null)

            when (app.status) {
                AppInfo.InstallStatus.INSTALLED -> {
                    badge(holder, "Đã cài", R.color.text_emerald, R.drawable.bg_badge_installed)
                    holder.pbProgress.visibility = View.GONE
                    holder.layoutInstall.visibility = View.GONE
                    holder.layoutInstalled.visibility = View.VISIBLE

                    holder.btnOpen.setOnClickListener {
                        val launchIntent = packageManager.getLaunchIntentForPackage(app.packageName)
                        if (launchIntent != null) startActivity(launchIntent)
                        else toast("Không tìm thấy Activity khởi chạy.")
                    }
                    // MyEX2Center cannot remove itself from its own store.
                    holder.btnDelete.visibility = if (app.isSelf) View.GONE else View.VISIBLE
                    if (!app.isSelf) holder.btnDelete.setOnClickListener { handleUninstall(app) }
                }
                AppInfo.InstallStatus.UPDATE_AVAILABLE -> {
                    badge(holder, "Có cập nhật", R.color.text_amber, R.drawable.bg_badge_update)
                    showInstallButton(holder)
                    holder.tvInstallText.text = when {
                        !app.isPublished -> "Cập nhật từ USB / Máy"
                        app.isSelf -> "Cập nhật hệ thống"
                        else -> "Cập nhật"
                    }
                    holder.layoutInstall.setOnClickListener { handleInstall(app) }
                }
                AppInfo.InstallStatus.NOT_INSTALLED -> {
                    badge(holder, "Chưa cài", R.color.text_secondary, R.drawable.bg_badge_not_installed)
                    showInstallButton(holder)
                    holder.tvInstallText.text = if (app.isPublished) "Cài đặt" else "Cài từ USB / Máy"
                    holder.layoutInstall.setOnClickListener { handleInstall(app) }
                }
                AppInfo.InstallStatus.DOWNLOADING -> {
                    showInstallButton(holder)
                    holder.pbProgress.visibility = View.VISIBLE
                    // While downloading the button cancels (the partial is kept for resume).
                    holder.layoutInstall.setOnClickListener { cancelDownload(app) }
                    bindProgress(holder, app)
                }
                AppInfo.InstallStatus.INSTALLING -> {
                    badge(holder, "Đang cài...", R.color.text_emerald, R.drawable.bg_badge_installed)
                    showInstallButton(holder)
                    holder.tvInstallText.text = "Đang xử lý..."
                }
            }
        }

        private fun bindProgress(holder: ViewHolder, app: AppInfo) {
            if (app.status != AppInfo.InstallStatus.DOWNLOADING) return
            badge(holder, "${app.downloadProgress}%", R.color.accent_primary, R.drawable.bg_badge_not_installed)
            holder.pbProgress.progress = app.downloadProgress
            holder.tvInstallText.text = "Hủy tải (${app.downloadProgress}%)"
        }

        private fun badge(holder: ViewHolder, text: String, colorRes: Int, bg: Int) {
            holder.tvBadge.text = text
            holder.tvBadge.setTextColor(getColor(colorRes))
            holder.tvBadge.setBackgroundResource(bg)
        }

        private fun showInstallButton(holder: ViewHolder) {
            holder.pbProgress.visibility = View.GONE
            holder.layoutInstall.visibility = View.VISIBLE
            holder.layoutInstalled.visibility = View.GONE
        }

        /**
         * Icon priority: installed app icon (cached) → bundled drawable → remote iconUrl.
         * The ImageView is tagged with the app so a late remote result for a recycled
         * holder is dropped instead of showing the wrong app's icon.
         */
        private fun loadAppIcon(app: AppInfo, imageView: ImageView) {
            imageView.tag = app.packageName

            if (app.status != AppInfo.InstallStatus.NOT_INSTALLED) {
                val cached = installedIconCache[app.packageName] ?: try {
                    packageManager.getApplicationIcon(app.packageName).also { installedIconCache[app.packageName] = it }
                } catch (_: Throwable) {
                    null
                }
                if (cached != null) {
                    imageView.setImageDrawable(cached)
                    return
                }
            }

            val resId = bundledIconRes(app)
            if (resId != 0) {
                imageView.setImageResource(resId)
                return
            }

            if (app.iconUrl.startsWith("https://")) {
                val cachedBmp = remoteIconCache.get(app.iconUrl)
                if (cachedBmp != null) {
                    imageView.setImageBitmap(cachedBmp)
                    return
                }
                imageView.setImageResource(R.drawable.ic_app_placeholder)
                if (!remoteIconsInFlight.add(app.iconUrl)) return // already being fetched
                val url = app.iconUrl
                iconExecutor.execute {
                    val bmp = try {
                        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                        conn.connectTimeout = 4000
                        conn.readTimeout = 4000
                        try {
                            if (conn.responseCode == 200) conn.inputStream.use { BitmapFactory.decodeStream(it) } else null
                        } finally {
                            conn.disconnect()
                        }
                    } catch (_: Throwable) {
                        null
                    }
                    if (bmp != null) remoteIconCache.put(url, bmp)
                    runOnUiThread {
                        remoteIconsInFlight.remove(url)
                        if (bmp != null && !gone && imageView.tag == app.packageName) imageView.setImageBitmap(bmp)
                    }
                }
            } else {
                imageView.setImageResource(R.drawable.ic_app_placeholder)
            }
        }

        private fun bundledIconRes(app: AppInfo): Int {
            if (app.iconResName.isNotEmpty()) {
                val resId = resources.getIdentifier(app.iconResName, "drawable", packageName)
                if (resId != 0) return resId
            }
            return when (app.packageName) {
                "com.mipago.ex2center" -> R.drawable.img_my_ex2
                "vn.vietmap.live" -> R.drawable.ic_app_vietmap
                "org.smarttube.stable", "com.teamsmart.videomanager.tv", "com.liskovsoft.smartyoutubetv2.tv" -> R.drawable.ic_app_smarttube
                "com.aaex.carbrowser", "com.aaexbrowser", "mark.via.gp" -> R.drawable.ic_app_browser
                else -> 0
            }
        }
    }
}
