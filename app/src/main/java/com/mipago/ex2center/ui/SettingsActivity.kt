package com.mipago.ex2center.ui

import android.app.Activity
import android.app.AlertDialog
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
import com.mipago.ex2center.service.CarAutomationService
import com.mipago.ex2center.service.FloatingControlService
import com.mipago.ex2center.utils.CarApiHelper
import com.mipago.ex2center.utils.CarProfile
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
        right.addView(drivingSection())
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
        val sub = text(subtitle ?: "", 16f, R.color.text_secondary).apply {
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
            // Click (not OnCheckedChange): only a real tap may act. Programmatic updates from
            // the car / Wi-Fi state must never write back — a replayed "false" would turn off
            // the head unit's Wi-Fi.
            setOnClickListener { onChange(isChecked) }
            // The whole row is the touch target: the 32dp switch itself takes no touches, so
            // there is no small target inside a big one. (After setOnClickListener, which
            // re-enables clickable.) Row taps call performClick(), which still toggles.
            isClickable = false
            isFocusable = false
        }
        val (rowView, sub) = row(title, subtitle, sw) { sw.performClick() }
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
        names.addView(text("Tên và ảnh hiển thị trên Dashboard", 16f, R.color.text_secondary))
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
        card.addView(text("Chế độ hiển thị", 16f, R.color.text_secondary).apply { setPadding(0, dp(8), 0, dp(8)) })
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
                if (active) setTextColor(0xFFFFFFFF.toInt())
                layoutParams = LinearLayout.LayoutParams(0, dp(64), 1f).apply { if (i > 0) marginStart = dp(12) }
                setOnClickListener { applyTheme(mode) }
            })
        }
        card.addView(seg)
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
        guardSubtitle = actionRow(card, "Crash Guard", "--") { resetGuard() }.second
        return card
    }

    private fun systemSection(): View {
        val card = section("Hệ thống", R.drawable.ic_settings)
        toggleRow(
            card, "Nút nổi điều khiển nhanh", "Điều hòa, Màn hình đêm, Khởi động lại",
            prefs.getBoolean(FloatingControlService.KEY_FLOATING_BUBBLE_ENABLED, true)
        ) { on ->
            prefs.edit().putBoolean(FloatingControlService.KEY_FLOATING_BUBBLE_ENABLED, on).apply()
            val intent = Intent(this, FloatingControlService::class.java)
            if (on) startService(intent) else stopService(intent)
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
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        AlertDialog.Builder(this)
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
            AlertDialog.Builder(this)
                .setTitle("Tắt Wi-Fi?")
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
