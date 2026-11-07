package com.mipago.ex2center.ui

import android.app.Activity
import com.mipago.ex2center.utils.AppDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Typeface
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.mipago.ex2center.R
import com.mipago.ex2center.launcher.MediaCompanions
import com.mipago.ex2center.ui.dash.DashLayout
import com.mipago.ex2center.ui.dash.DashPreset
import com.mipago.ex2center.service.CarAutomationService
import com.mipago.ex2center.service.FloatingControlService
import com.mipago.ex2center.utils.CarApiHelper
import com.mipago.ex2center.utils.CarProfile
import com.mipago.ex2center.utils.ParkGate
import com.mipago.ex2center.utils.ThemeManager
import com.mipago.ex2center.utils.UiStyle
import com.mipago.ex2center.utils.WifiStatus
import java.util.concurrent.Executors

/**
 * Cài đặt — grouped cards with toggles, built in code so every row shares one style:
 *  - Hồ sơ xe: name + photo (create / change / reset)
 *  - Giao diện: Theo hệ thống / Sáng / Tối
 *  - Kết nối: Wi-Fi (SSID • IP), Đèn viền nội thất (VHAL, readback-verified)
 *  - Lái xe & Tự động hóa: ghi nhớ chế độ lái / phanh tái sinh, tắt âm AVAS, Valet, Crash Guard
 *  - Hệ thống: nút nổi, Kho ứng dụng, phiên bản
 */
class SettingsActivity : Activity() {

    companion object {
        private const val REQUEST_PICK_PHOTO = 2001
        private const val VHAL_HOLDER = "settings"
        private const val REFRESH_MS = 3_000L
    }

    private val carHelper = CarApiHelper.getInstance()
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences

    private lateinit var ivAvatar: ImageView
    private lateinit var tvCarName: TextView
    private var resetPhotoRow: View? = null
    private lateinit var wifiSwitch: Switch
    private lateinit var wifiSubtitle: TextView
    private lateinit var ambientSwitch: Switch
    private lateinit var ambientSubtitle: TextView
    private lateinit var guardSubtitle: TextView
    private lateinit var safeVolumeSubtitle: TextView
    private val diagSubtitles = mutableListOf<TextView>()
    private lateinit var courtesySwitch: Switch
    private lateinit var courtesySubtitle: TextView
    private lateinit var followMeSubtitle: TextView
    private lateinit var speedLockSubtitle: TextView
    private lateinit var autoCloseSwitch: Switch
    private lateinit var autoCloseSubtitle: TextView
    private lateinit var wiperIdleSwitch: Switch
    private lateinit var wiperIdleSubtitle: TextView

    private val periodicRefresh = object : Runnable {
        override fun run() {
            refreshConnections()
            main.postDelayed(this, REFRESH_MS)
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(ThemeManager.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(CarAutomationService.PREFS_NAME, Context.MODE_PRIVATE)
        carHelper.acquire(this, VHAL_HOLDER) { main.post { refreshConnections() } }
        setContentView(buildContent())
        com.mipago.ex2center.utils.ThemeManager.applyWindow(this)
    }

    override fun onResume() {
        super.onResume()
        bindProfile()
        refreshGuard()
        refreshDiagnostics()
        refreshCourtesy()
        refreshComfortSettings()
        main.post(periodicRefresh)
    }

    override fun onPause() {
        main.removeCallbacks(periodicRefresh)
        super.onPause()
    }

    override fun onDestroy() {
        io.shutdownNow()
        carHelper.release(VHAL_HOLDER)
        super.onDestroy()
    }

    // ---------------------------------------------------------------------------------------
    // Layout
    // ---------------------------------------------------------------------------------------

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()
    private fun color(id: Int) = getColor(id)

    private fun text(value: String, sizeSp: Float, colorRes: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color(colorRes))
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun buildContent(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.bg_primary))
            setPadding(dp(24), dp(16), dp(24), dp(16))
        }

        // Top bar
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val back = text("‹  Quay lại", 18f, R.color.text_primary, bold = true).apply {
            setBackgroundResource(R.drawable.bg_btn_inactive)
            gravity = Gravity.CENTER
            setPadding(dp(24), 0, dp(24), 0)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(56))
            setOnClickListener { finish() }
        }
        top.addView(back)
        top.addView(text("Cài đặt", 28f, R.color.text_primary, bold = true).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { marginStart = dp(24) }
        })
        root.addView(top)

        // Two columns of section cards
        val columns = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(16), 0, 0)
        }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        columns.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
        columns.addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) })

        left.addView(profileSection())
        left.addView(appearanceSection())
        left.addView(connectionSection())
        left.addView(comfortSection())
        right.addView(drivingSection())
        right.addView(companionSection())
        right.addView(diagnosticsSection())
        right.addView(systemSection())

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(columns)
        }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    private fun section(title: String, iconRes: Int): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card)
            setPadding(dp(24), dp(16), dp(24), dp(8))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(16) }
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(8))
        }
        header.addView(ImageView(this).apply {
            setImageResource(iconRes)
            setColorFilter(color(R.color.accent_primary)) // section headers carry the accent
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(12) }
        })
        header.addView(text(title, 22f, R.color.text_primary, bold = true))
        card.addView(header)
        return card
    }

    private fun divider() = View(this).apply {
        setBackgroundColor(color(R.color.surface_stroke))
        // 4dp air above and below the hairline: adjacent row targets stay >= 8dp apart.
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
            .apply { topMargin = dp(4); bottomMargin = dp(4) }
    }

    /** Title + subtitle on the left, [trailing] on the right; the whole row is the touch target. */
    private fun row(title: String, subtitle: String?, trailing: View?, onClick: (() -> Unit)?): Pair<LinearLayout, TextView> {
        val rowView = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(80)
            setPadding(0, dp(8), 0, dp(8))
            if (onClick != null) {
                isClickable = true
                isFocusable = true
                setOnClickListener { onClick() }
            }
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(text(title, 20f, R.color.text_primary, bold = true))
        val sub = text(subtitle ?: "", 18f, R.color.text_secondary).apply {
            visibility = if (subtitle == null) View.GONE else View.VISIBLE
            setPadding(0, dp(4), 0, 0)
        }
        texts.addView(sub)
        rowView.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (trailing != null) rowView.addView(trailing)
        return rowView to sub
    }

    private fun toggleRow(
        parent: LinearLayout,
        title: String,
        subtitle: String?,
        checked: Boolean,
        addDivider: Boolean = true,
        onChange: (Boolean) -> Unit
    ): Pair<Switch, TextView> {
        val sw = Switch(this).apply {
            isChecked = checked
            UiStyle.styleSwitch(this)
            isSaveEnabled = false // never restore/replay a stale checked state on recreate
            // The whole row is the touch target: the 32dp switch itself takes no touches, so
            // there is no small target inside a big one.
            isClickable = false
            isFocusable = false
            isSoundEffectsEnabled = false
        }
        // Only a real tap acts (never OnCheckedChange): programmatic updates from the car / Wi-Fi
        // state must never write back. The row toggles the switch directly instead of calling
        // sw.performClick(), which played a second click sound for the same tap.
        val (rowView, sub) = row(title, subtitle, sw) {
            if (sw.isEnabled) {
                sw.toggle()
                onChange(sw.isChecked)
            }
        }
        if (addDivider && parent.childCount > 1) parent.addView(divider())
        parent.addView(rowView)
        return sw to sub
    }

    private fun actionRow(parent: LinearLayout, title: String, subtitle: String?, onClick: () -> Unit): Pair<LinearLayout, TextView> {
        val chevron = text("›", 30f, R.color.text_muted).apply { setPadding(dp(12), 0, dp(4), 0) }
        val pair = row(title, subtitle, chevron, onClick)
        if (parent.childCount > 1) parent.addView(divider())
        parent.addView(pair.first)
        return pair
    }

    // ---------------------------------------------------------------------------------------
    // Sections
    // ---------------------------------------------------------------------------------------

    /** "Chạy cùng Vietmap": which media app runs next to the map. */
    private fun companionSection(): View {
        val card = section("Chạy cùng Vietmap", R.drawable.ic_splitscreen)
        lateinit var appSub: TextView
        fun refresh() {
            appSub.text = MediaCompanions.current(this)?.let { c ->
                c.label + if (c.hasPopupPlayer) " · video nổi trên bản đồ" else if (c.isVideo) " · tiếng phát nền" else " · nhạc nền"
            } ?: "Chưa cài app nào. Tải ${MediaCompanions.namesForMessage} từ EX2 Store"
        }
        appSub = actionRow(card, "Ứng dụng video / nhạc", null) {
            val installed = MediaCompanions.installed(this)
            if (installed.isEmpty()) {
                toast("Chưa cài app video/nhạc nào. Tải từ EX2 Store.")
                return@actionRow
            }
            AppDialog(this)
                .setTitle("Ứng dụng chạy cùng Vietmap")
                .setSingleChoiceItems(installed.map { it.label }.toTypedArray(),
                    installed.indexOf(MediaCompanions.current(this))) { d, which ->
                    MediaCompanions.setCurrent(this, installed[which].pkg)
                    d.dismiss()
                    refresh()
                }
                .setNegativeButton("Hủy", null)
                .show()
        }.second
        card.addView(text("Không chia đôi màn hình: trên xe này chia đôi làm Vietmap bị thoát. App mở ra để bạn chọn nội dung, chạm \"Về bản đồ\" (hoặc nhạc bắt đầu phát) là quay lại bản đồ, tiếng vẫn phát.",
            18f, R.color.text_muted).apply { setPadding(0, dp(8), 0, dp(4)) })
        refresh()
        return card
    }

    private fun profileSection(): View {
        val card = section("Hồ sơ xe", R.drawable.ic_car)

        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(16))
        }
        ivAvatar = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(88), dp(88)).apply { marginEnd = dp(16) }
            setBackgroundResource(R.drawable.bg_btn_inactive)
            setOnClickListener { pickPhoto() }
        }
        head.addView(ivAvatar)
        val names = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        tvCarName = text(CarProfile.DEFAULT_NAME, 26f, R.color.text_primary, bold = true)
        names.addView(tvCarName)
        names.addView(text("Tên và ảnh hiển thị trên Dashboard", 18f, R.color.text_secondary))
        head.addView(names)
        card.addView(head)

        actionRow(card, "Đổi tên xe", "Tối đa ${CarProfile.MAX_NAME_LENGTH} ký tự") { editName() }
        actionRow(card, "Chọn ảnh xe", "Ảnh từ USB / bộ nhớ máy") { pickPhoto() }
        resetPhotoRow = actionRow(card, "Dùng ảnh mặc định", "Xóa ảnh đã chọn") {
            CarProfile.clearPhoto(this)
            bindProfile()
            toast("Đã dùng lại ảnh mặc định")
        }.first
        return card
    }

    private fun appearanceSection(): View {
        val card = section("Giao diện", R.drawable.ic_brightness)
        card.addView(text("Chế độ hiển thị", 18f, R.color.text_secondary).apply { setPadding(0, dp(8), 0, dp(8)) })
        val seg = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(16))
        }
        val current = ThemeManager.getMode(this)
        listOf(ThemeManager.MODE_SYSTEM, ThemeManager.MODE_LIGHT, ThemeManager.MODE_DARK).forEachIndexed { i, mode ->
            val active = mode == current
            seg.addView(text(ThemeManager.label(mode), 18f, if (active) R.color.text_primary else R.color.text_secondary, bold = true).apply {
                gravity = Gravity.CENTER
                setBackgroundResource(if (active) R.drawable.bg_btn_active else R.drawable.bg_btn_inactive)
                if (active) setTextColor(getColor(R.color.text_on_accent))
                layoutParams = LinearLayout.LayoutParams(0, dp(64), 1f).apply { if (i > 0) marginStart = dp(12) }
                setOnClickListener { applyTheme(mode) }
            })
        }
        card.addView(seg)

        // Dashboard layout: one of the ready-made Bento presets (the Dashboard rebuilds on return).
        card.addView(text("Bố cục Dashboard", 18f, R.color.text_secondary).apply { setPadding(0, dp(8), 0, dp(8)) })
        val presetSeg = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(16))
        }
        val currentPreset = DashLayout.currentPreset(this) // null = the driver's own layout
        DashPreset.values().forEachIndexed { i, preset ->
            val active = preset == currentPreset
            presetSeg.addView(text(preset.label, 18f, if (active) R.color.text_primary else R.color.text_secondary, bold = true).apply {
                gravity = Gravity.CENTER
                setBackgroundResource(if (active) R.drawable.bg_btn_active else R.drawable.bg_btn_inactive)
                if (active) setTextColor(getColor(R.color.text_on_accent))
                layoutParams = LinearLayout.LayoutParams(0, dp(64), 1f).apply { if (i > 0) marginStart = dp(12) }
                setOnClickListener {
                    if (preset != DashLayout.currentPreset(this@SettingsActivity)) {
                        DashLayout.applyPreset(this@SettingsActivity, preset)
                        recreate()
                    }
                }
            })
        }
        card.addView(presetSeg)
        if (currentPreset == null) {
            card.addView(text("Đang dùng bố cục bạn tự chỉnh. Chọn một mục ở trên để thay bằng bố cục có sẵn.", 18f, R.color.text_muted)
                .apply { setPadding(0, 0, 0, dp(16)) })
        }
        return card
    }

    private fun connectionSection(): View {
        val card = section("Kết nối", R.drawable.ic_wifi)
        toggleRow(card, "Wi-Fi", "--", false) { on -> setWifi(on) }.let { (sw, sub) ->
            wifiSwitch = sw
            wifiSubtitle = sub
        }
        toggleRow(card, "Đèn viền nội thất", "Đang đọc từ xe...", false) { on -> setAmbient(on) }.let { (sw, sub) ->
            ambientSwitch = sw
            ambientSubtitle = sub
            sw.isEnabled = false
        }
        return card
    }

    private fun drivingSection(): View {
        val card = section("Lái xe & Tự động hóa", R.drawable.ic_steering)
        toggleRow(
            card, "Ghi nhớ chế độ lái", "Khôi phục Tiết kiệm / Thoải mái / Thể thao sau khi tắt và mở xe",
            prefs.getBoolean(CarAutomationService.KEY_AUTO_DRIVE_MODE, true)
        ) { on -> prefs.edit().putBoolean(CarAutomationService.KEY_AUTO_DRIVE_MODE, on).apply() }
        toggleRow(
            card, "Ghi nhớ phanh tái sinh", "Khôi phục mức Thấp / Vừa / Cao sau khi tắt và mở xe",
            prefs.getBoolean(CarAutomationService.KEY_AUTO_REGEN_MODE, true)
        ) { on -> prefs.edit().putBoolean(CarAutomationService.KEY_AUTO_REGEN_MODE, on).apply() }
        toggleRow(
            card, "Tự động tắt âm AVAS", "Giữ tắt âm cảnh báo người đi bộ, kể cả sau khi xe thức dậy",
            prefs.getBoolean(CarAutomationService.KEY_AUTO_AVAS, true)
        ) { on -> prefs.edit().putBoolean(CarAutomationService.KEY_AUTO_AVAS, on).apply() }
        toggleRow(
            card, "Chế độ Bảo dưỡng (Valet)", "Tạm dừng mọi tự động hóa khi gửi xe / rửa xe",
            prefs.getBoolean(CarAutomationService.KEY_VALET_MODE, false)
        ) { on ->
            prefs.edit().putBoolean(CarAutomationService.KEY_VALET_MODE, on).apply()
            toast(if (on) "Chế độ Bảo dưỡng: ĐÃ BẬT" else "Chế độ Bảo dưỡng: ĐÃ TẮT")
        }
        toggleRow(
            card, "Giới hạn âm lượng khi bật xe", safeVolumeText(),
            prefs.getBoolean(CarAutomationService.KEY_SAFE_VOLUME, false)
        ) { on ->
            prefs.edit().putBoolean(CarAutomationService.KEY_SAFE_VOLUME, on).apply()
            safeVolumeSubtitle.text = safeVolumeText()
        }.let { (_, sub) -> safeVolumeSubtitle = sub }
        actionRow(card, "Mức âm lượng tối đa khi bật xe", null) { pickSafeVolume() }
        guardSubtitle = actionRow(card, "Crash Guard", "--") { resetGuard() }.second
        return card
    }

    /** Starts the overlay service with the new preferences (it stops itself when nothing is wanted). */
    private fun syncOverlayService() {
        startService(Intent(this, FloatingControlService::class.java).setAction(FloatingControlService.ACTION_SYNC_PREFS))
    }

    private fun safeVolumePercent() =
        prefs.getInt(CarAutomationService.KEY_SAFE_VOLUME_PERCENT, CarAutomationService.DEFAULT_SAFE_VOLUME_PERCENT)

    private fun safeVolumeText(): String =
        if (prefs.getBoolean(CarAutomationService.KEY_SAFE_VOLUME, false))
            "Nhạc hạ về tối đa ${safeVolumePercent()}% khi bật xe, tránh nhạc quá to"
        else "Đang tắt: giữ nguyên âm lượng lần trước"

    private fun pickSafeVolume() {
        val options = intArrayOf(20, 30, 40, 50, 60)
        val current = options.indexOf(safeVolumePercent()).coerceAtLeast(0)
        AppDialog(this)
            .setTitle("Âm lượng tối đa khi bật xe")
            .setSingleChoiceItems(options.map { "$it%" }.toTypedArray(), current) { dialog, which ->
                prefs.edit().putInt(CarAutomationService.KEY_SAFE_VOLUME_PERCENT, options[which]).apply()
                safeVolumeSubtitle.text = safeVolumeText()
                dialog.dismiss()
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    /** Car comfort settings verified on the IHU629G (same calls as the car's own Settings). */
    private fun comfortSection(): View {
        val card = section("Xe & tiện nghi", R.drawable.ic_car)
        toggleRow(card, "Đèn chào khi mở cửa", "Đang đọc từ xe...", false) { on -> setCourtesy(on) }.let { (sw, sub) ->
            courtesySwitch = sw
            courtesySubtitle = sub
            sw.isEnabled = false
        }
        toggleRow(
            card, "Mở camera 360 khi vào số D", "Từ P sang D lúc xe đứng yên: tự mở camera của xe để nhìn quanh xe",
            prefs.getBoolean(CarAutomationService.KEY_CAMERA_ON_DRIVE, false)
        ) { on -> prefs.edit().putBoolean(CarAutomationService.KEY_CAMERA_ON_DRIVE, on).apply() }
        followMeSubtitle = actionRow(card, "Đèn dẫn đường về nhà", "Đang đọc từ xe...") {
            pickFunction("Đèn dẫn đường về nhà", CarApiHelper.PROP_FOLLOW_ME_HOME, FOLLOW_ME_OPTIONS)
        }.second
        speedLockSubtitle = actionRow(card, "Tự khóa theo tốc độ", "Đang đọc từ xe...") {
            pickFunction("Tự khóa theo tốc độ", CarApiHelper.PROP_SPEED_AUTO_LOCK, SPEED_LOCK_OPTIONS)
        }.second
        toggleRow(card, "Tự động đóng kính khi khóa xe", "Đang đọc từ xe...", false) { on ->
            writeFunction("Tự động đóng kính khi khóa xe", CarApiHelper.PROP_AUTO_CLOSE_WINDOW, if (on) 1 else 0)
        }.let { (sw, sub) -> autoCloseSwitch = sw; autoCloseSubtitle = sub; sw.isEnabled = false }
        toggleRow(card, "Giảm tốc gạt mưa khi đỗ", "Đang đọc từ xe...", false) { on ->
            writeFunction("Giảm tốc gạt mưa khi đỗ", CarApiHelper.PROP_WIPER_IDLE, if (on) 1 else 0)
        }.let { (sw, sub) -> wiperIdleSwitch = sw; wiperIdleSubtitle = sub; sw.isEnabled = false }
        actionRow(card, "Mở cốp trước", "Chỉ khi xe ở số P. Đóng cốp bằng tay") { confirmFrontTrunk() }
        return card
    }

    private val FOLLOW_ME_OPTIONS = listOf(
        "Tắt" to 0,
        "30 giây" to CarApiHelper.FOLLOW_ME_HOME_BASE + 1,
        "60 giây" to CarApiHelper.FOLLOW_ME_HOME_BASE + 2,
        "90 giây" to CarApiHelper.FOLLOW_ME_HOME_BASE + 3
    )
    private val SPEED_LOCK_OPTIONS = listOf(
        "Tắt" to 0,
        "10 km/h" to CarApiHelper.SPEED_AUTO_LOCK_BASE + 1,
        "20 km/h" to CarApiHelper.SPEED_AUTO_LOCK_BASE + 2
    )

    private fun optionLabel(options: List<Pair<String, Int>>, value: Int?): String =
        options.firstOrNull { it.second == value }?.first
            ?: if (value == null) (if (CarApiHelper.isCarApiPresent) "Không đọc được từ xe" else "Chỉ có trên xe") else "--"

    /** Comfort settings read through the car's adapter (function values, see CarApiHelper). */
    private fun refreshComfortSettings() {
        try {
            io.execute {
                val followMe = carHelper.readFunctionInt(CarApiHelper.PROP_FOLLOW_ME_HOME)
                val speedLock = carHelper.readFunctionInt(CarApiHelper.PROP_SPEED_AUTO_LOCK)
                val autoClose = carHelper.readFunctionInt(CarApiHelper.PROP_AUTO_CLOSE_WINDOW)
                val wiperIdle = carHelper.readFunctionInt(CarApiHelper.PROP_WIPER_IDLE)
                main.post {
                    if (isDestroyed) return@post
                    followMeSubtitle.text = optionLabel(FOLLOW_ME_OPTIONS, followMe)
                    speedLockSubtitle.text = optionLabel(SPEED_LOCK_OPTIONS, speedLock)
                    bindFunctionSwitch(autoCloseSwitch, autoCloseSubtitle, autoClose, "Kính tự đóng hết khi khóa xe")
                    bindFunctionSwitch(wiperIdleSwitch, wiperIdleSubtitle, wiperIdle, "Gạt mưa chạy chậm lại khi xe dừng")
                }
            }
        } catch (_: Throwable) {}
    }

    private fun bindFunctionSwitch(sw: Switch, sub: TextView, value: Int?, onText: String) {
        sw.isEnabled = value == 0 || value == 1
        if (value == 0 || value == 1) setChecked(sw, value == 1)
        sub.text = when (value) {
            1 -> "Đang bật: $onText"
            0 -> "Đang tắt"
            null -> if (CarApiHelper.isCarApiPresent) "Không đọc được từ xe" else "Chỉ có trên xe"
            else -> "--"
        }
    }

    private fun pickFunction(title: String, prop: Int, options: List<Pair<String, Int>>) {
        io.execute {
            val current = carHelper.readFunctionInt(prop)
            main.post {
                if (isDestroyed) return@post
                AppDialog(this)
                    .setTitle(title)
                    .setSingleChoiceItems(options.map { it.first }.toTypedArray(),
                        options.indexOfFirst { it.second == current }) { dialog, which ->
                        dialog.dismiss()
                        writeFunction(title, prop, options[which].second)
                    }
                    .setNegativeButton("Hủy", null)
                    .show()
            }
        }
    }

    /** Writes, then reads back: the toast only reports a failure the car itself confirms. */
    private fun writeFunction(label: String, prop: Int, value: Int) {
        io.execute {
            val accepted = carHelper.writeFunctionInt(prop, value)
            Thread.sleep(800L)
            val back = carHelper.readFunctionInt(prop)
            main.post {
                if (isDestroyed) return@post
                if (!accepted || back != value) toast("Xe không nhận lệnh: $label")
                refreshComfortSettings()
            }
        }
    }

    private fun refreshCourtesy() {
        try {
            io.execute {
                val on = carHelper.readCourtesyLight()
                main.post {
                    if (isDestroyed) return@post
                    courtesySwitch.isEnabled = on != null
                    if (on != null) setChecked(courtesySwitch, on)
                    courtesySubtitle.text = when (on) {
                        null -> if (CarApiHelper.isCarApiPresent) "Không đọc được từ xe" else "Chỉ có trên xe"
                        true -> "Đang bật: đèn trong xe sáng khi mở cửa"
                        false -> "Đang tắt"
                    }
                }
            }
        } catch (_: Throwable) {}
    }

    private fun setCourtesy(on: Boolean) {
        courtesySwitch.isEnabled = false
        courtesySubtitle.text = "Đang gửi lệnh..."
        io.execute {
            val accepted = carHelper.setCourtesyLight(on)
            Thread.sleep(800L)
            val back = carHelper.readCourtesyLight()
            main.post {
                if (isDestroyed) return@post
                if (!accepted || back != on) toast("Xe không nhận lệnh: Đèn chào khi mở cửa")
                refreshCourtesy()
            }
        }
    }

    private fun confirmFrontTrunk() {
        AppDialog(this)
            .setTitle("Mở cốp trước?")
            .setMessage("Kiểm tra phía trước xe trống, không có người. Cốp cần đóng lại bằng tay.")
            .setPositiveButton("Mở cốp") { _, _ ->
                ParkGate.runIfAllowed(this) {
                    io.execute {
                        val ok = carHelper.openFrontTrunk()
                        main.post { toast(if (ok) "Đã gửi lệnh mở cốp trước" else "Xe không nhận lệnh: Mở cốp trước") }
                    }
                }
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    /** Read-only brake / ESC fault flags reported by the car (never written). */
    private fun diagnosticsSection(): View {
        val card = section("Chẩn đoán phanh & ESC", R.drawable.ic_auto_hold)
        diagSubtitles.clear()
        for (label in listOf(
            "Chống bó cứng phanh (ABS)", "Cân bằng điện tử (ESP)", "Kiểm soát lực kéo (TCS)",
            "Phân bổ lực phanh (EBD)", "Phanh tay điện tử (EPB)", "Đèn cảnh báo ESC trên đồng hồ"
        )) {
            val (rowView, sub) = row(label, "Đang đọc…", null, null)
            if (card.childCount > 1) card.addView(divider())
            card.addView(rowView)
            diagSubtitles.add(sub)
        }
        card.addView(text("Chỉ đọc. Có lỗi thì xe cũng báo trên đồng hồ: hãy đưa xe tới đại lý kiểm tra.",
            14f, R.color.text_muted).apply { setPadding(0, dp(8), 0, dp(8)) })
        return card
    }

    private fun refreshDiagnostics() {
        try {
            io.execute {
                val flags = carHelper.readBrakeDiagnostics()
                main.post {
                    if (isDestroyed) return@post
                    flags.forEachIndexed { i, f ->
                        val tv = diagSubtitles.getOrNull(i) ?: return@forEachIndexed
                        when {
                            f.code == null -> {
                                tv.text = if (CarApiHelper.isCarApiPresent) "Không đọc được từ xe" else "Chỉ có trên xe"
                                tv.setTextColor(color(R.color.text_secondary))
                            }
                            f.code == 0 -> {
                                tv.text = "Bình thường"
                                tv.setTextColor(color(R.color.accent_primary))
                            }
                            else -> {
                                tv.text = "Xe báo lỗi (mã ${f.code})"
                                tv.setTextColor(color(R.color.accent_danger))
                            }
                        }
                    }
                }
            }
        } catch (_: Throwable) {}
    }

    private fun systemSection(): View {
        val card = section("Hệ thống", R.drawable.ic_settings)
        toggleRow(
            card, "Nút nổi điều khiển nhanh", "Điều hòa, Màn hình đêm, Khởi động lại",
            prefs.getBoolean(FloatingControlService.KEY_FLOATING_BUBBLE_ENABLED, true)
        ) { on ->
            prefs.edit().putBoolean(FloatingControlService.KEY_FLOATING_BUBBLE_ENABLED, on).apply()
            syncOverlayService()
        }
        toggleRow(
            card, "Nhiệt độ & quạt trên thanh dưới", "Chỉnh nhanh nhiệt độ và mức quạt ngay trên thanh dưới của xe",
            prefs.getBoolean(FloatingControlService.KEY_DOCK_CLIMATE_ENABLED, true)
        ) { on ->
            prefs.edit().putBoolean(FloatingControlService.KEY_DOCK_CLIMATE_ENABLED, on).apply()
            syncOverlayService()
        }
        actionRow(card, "Kho ứng dụng", "Cài đặt và cập nhật ứng dụng cho xe") {
            com.mipago.ex2center.utils.StoreLauncher.open(this)
        }
        val version = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Throwable) { "?" }
        actionRow(card, "Phiên bản $version", "github.com/ngocngo1213/MyEX2Center") {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/ngocngo1213/MyEX2Center/releases")))
            } catch (_: Throwable) {
                toast("Không mở được trình duyệt")
            }
        }
        return card
    }

    // ---------------------------------------------------------------------------------------
    // Behaviour
    // ---------------------------------------------------------------------------------------

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun bindProfile() {
        tvCarName.text = CarProfile.getName(this)
        CarProfile.bindPhoto(this, ivAvatar)
        resetPhotoRow?.visibility = if (CarProfile.hasCustomPhoto(this)) View.VISIBLE else View.GONE
    }

    private fun editName() {
        val input = EditText(this).apply {
            setText(CarProfile.getName(this@SettingsActivity))
            setSelection(text.length)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            filters = arrayOf(InputFilter.LengthFilter(CarProfile.MAX_NAME_LENGTH))
            setSingleLine(true)
        }
        val box = LinearLayout(this).apply {
            addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        AppDialog(this)
            .setTitle("Tên xe")
            .setView(box)
            .setPositiveButton("Lưu") { _, _ ->
                CarProfile.setName(this, input.text.toString())
                bindProfile()
            }
            .setNeutralButton("Mặc định") { _, _ ->
                CarProfile.setName(this, "")
                bindProfile()
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun pickPhoto() {
        try {
            startActivityForResult(
                Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "image/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                },
                REQUEST_PICK_PHOTO
            )
        } catch (t: Throwable) {
            toast("Không mở được bộ chọn ảnh: ${t.message}")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode != REQUEST_PICK_PHOTO || resultCode != RESULT_OK || uri == null) return
        io.execute {
            val ok = CarProfile.setPhoto(this, uri)
            main.post {
                if (isDestroyed) return@post
                bindProfile()
                toast(if (ok) "Đã cập nhật ảnh xe" else "Không đọc được ảnh đã chọn")
            }
        }
    }

    private fun applyTheme(mode: Int) {
        if (mode == ThemeManager.getMode(this)) return
        ThemeManager.setMode(this, mode)
        // The overlay lives in a service: restart it so it picks up the new colours.
        if (prefs.getBoolean(FloatingControlService.KEY_FLOATING_BUBBLE_ENABLED, true)) {
            val intent = Intent(this, FloatingControlService::class.java)
            stopService(intent)
            startService(intent)
        }
        recreate()
    }

    private fun refreshGuard() {
        val halted = prefs.getBoolean(CarAutomationService.KEY_GUARD_HALTED, false)
        guardSubtitle.text = if (halted) "Đã ngắt do lỗi liên tiếp — chạm để đặt lại" else "Đang bảo vệ, không có lỗi"
        guardSubtitle.setTextColor(color(if (halted) R.color.text_amber else R.color.text_secondary))
    }

    private fun resetGuard() {
        if (!prefs.getBoolean(CarAutomationService.KEY_GUARD_HALTED, false)) {
            toast("Crash Guard đang hoạt động bình thường")
            return
        }
        startService(Intent(this, CarAutomationService::class.java).apply {
            action = CarAutomationService.ACTION_RESET_GUARD
        })
        prefs.edit().putBoolean(CarAutomationService.KEY_GUARD_HALTED, false).apply()
        refreshGuard()
        toast("Đã đặt lại Crash Guard")
    }

    /** Wi-Fi subtitle "SSID ● IP" (green dot when connected) + ambient light state from VHAL. */
    private fun refreshConnections() {
        if (isDestroyed) return
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val enabled = try { wm?.isWifiEnabled == true } catch (_: Throwable) { false }
        setChecked(wifiSwitch, enabled)
        val info = WifiStatus.read(this)
        wifiSubtitle.text = when {
            !enabled -> "Đang tắt"
            info.ip == "--" -> "Đang bật • chưa kết nối"
            else -> SpannableStringBuilder().apply {
                append(info.ssidLabel)
                append("  ")
                val start = length
                append("●")
                setSpan(ForegroundColorSpan(color(R.color.accent_primary)), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                append("  ")
                append(info.ip)
            }
        }

        try {
            io.execute {
                val ambient = carHelper.readAmbientLight()
                main.post {
                    if (isDestroyed) return@post
                    ambientSwitch.isEnabled = ambient != null
                    if (ambient != null) setChecked(ambientSwitch, ambient)
                    ambientSubtitle.text = when (ambient) {
                        null -> if (CarApiHelper.isCarApiPresent) "Không đọc được từ xe" else "Chỉ có trên xe"
                        true -> "Đang bật"
                        false -> "Đang tắt"
                    }
                }
            }
        } catch (_: Throwable) {}
    }

    /** Reflects the real state; switches act only on click, so this never triggers a write. */
    private fun setChecked(sw: Switch, value: Boolean) {
        if (sw.isChecked != value) sw.isChecked = value
    }

    private fun setWifi(on: Boolean) {
        if (!on) {
            // Turning Wi-Fi off cuts the store, catalog updates and hotspot apps: confirm first.
            AppDialog(this)
                .setTitle("Tắt Wi-Fi?")
                .setDestructive()
                .setMessage("Kho ứng dụng và các ứng dụng cần mạng sẽ mất kết nối.")
                .setPositiveButton("Tắt Wi-Fi") { _, _ -> applyWifi(false) }
                .setNegativeButton("Hủy") { _, _ -> refreshConnections() }
                .setOnCancelListener { refreshConnections() }
                .show()
        } else {
            applyWifi(true)
        }
    }

    private fun applyWifi(on: Boolean) {
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val ok = try {
            @Suppress("DEPRECATION")
            wm?.setWifiEnabled(on) == true
        } catch (t: Throwable) {
            false
        }
        if (!ok) {
            toast("Không thể ${if (on) "bật" else "tắt"} Wi-Fi" + if (Build.VERSION.SDK_INT > 28) " (chỉ hỗ trợ trên đầu xe)" else "")
        }
        main.postDelayed({ refreshConnections() }, 1500L)
    }

    private fun setAmbient(on: Boolean) {
        ambientSwitch.isEnabled = false
        ambientSubtitle.text = "Đang gửi lệnh..."
        io.execute {
            val accepted = carHelper.setAmbientLight(on)
            Thread.sleep(800L)
            val back = carHelper.readAmbientLight()
            main.post {
                if (isDestroyed) return@post
                if (!accepted || back != on) toast("Xe không nhận lệnh: Đèn viền nội thất")
                refreshConnections()
            }
        }
    }
}
