package com.mipago.ex2center.ui

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.mipago.ex2center.R
import com.mipago.ex2center.car.CarCommands
import com.mipago.ex2center.car.CarSnapshot
import com.mipago.ex2center.car.CarStateHub
import com.mipago.ex2center.car.ClimateState
import com.mipago.ex2center.car.ControlsState
import com.mipago.ex2center.car.Signal
import com.mipago.ex2center.launcher.SplitScreenLauncher
import com.mipago.ex2center.service.CarAutomationService
import com.mipago.ex2center.service.FloatingControlService
import com.mipago.ex2center.ui.dash.BentoGridLayout
import com.mipago.ex2center.ui.dash.DashLayout
import com.mipago.ex2center.ui.dash.DashPreset
import com.mipago.ex2center.ui.dash.EditOverlay
import com.mipago.ex2center.ui.dash.Placement
import com.mipago.ex2center.ui.dash.LevelIndicator
import com.mipago.ex2center.ui.dash.TileKind
import com.mipago.ex2center.ui.dash.WidgetType
import com.mipago.ex2center.utils.AppDialog
import com.mipago.ex2center.utils.CarApiHelper
import com.mipago.ex2center.utils.ParkGate
import com.mipago.ex2center.utils.PendingWrites
import com.mipago.ex2center.utils.ThemeManager
import com.mipago.ex2center.utils.WifiStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Dashboard — Bento grid 6×3 filled by the preset chosen in Cài đặt (ui/dash/DashPresets.kt):
 * Lái xe hàng ngày · Đi đêm tối giản · Đỗ xe / Cắm trại.
 *
 * Car values come from [CarStateHub] (one reader for the whole app), writes go through
 * [CarCommands]. A tapped control shows the requested state at once (tracked by [PendingWrites]),
 * then the car's confirmed state; unknown values are shown as "--", never guessed.
 */
class MainActivity : Activity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val WINDOW_OPEN_MAX_KMH = 30f
        private const val VHAL_HOLDER = "dashboard"
        private const val POLL_INTERVAL_MS = 3_000L
        private const val POST_WRITE_REFRESH_MS = 1_200L

        private const val TEMP_MIN = 17
        private const val TEMP_MAX = 32
        private const val FAN_MIN = 1
        private const val FAN_MAX = 8

        // Airflow bitmask written to the car: 1 = face, 2 = floor, 3 = face + floor.
        private const val VENT_FACE = 1
        private const val VENT_FLOOR = 2
        private const val VENT_FACE_FLOOR = 3

        // PendingWrites keys
        private const val K_AC = "ac"
        private const val K_RECIRC = "recirc"
        private const val K_DEFROST_FRONT = "defrost_front"
        private const val K_DEFROST_REAR = "defrost_rear"
        private const val K_TEMP = "temp"
        private const val K_FAN = "fan"
        private const val K_VENT = "vent"
        private const val K_DRIVE = "drive"
        private const val K_REGEN = "regen"
        private const val K_AVAS = "avas"
        private const val K_AMBIENT = "ambient"
        private const val K_AUTO_HOLD = "auto_hold"
        private const val K_WIRELESS = "wireless"

        private val SIGNALS = setOf(
            Signal.DRIVE, Signal.BATTERY, Signal.ENV, Signal.WINDOWS, Signal.CLIMATE, Signal.CONTROLS
        )
    }

    /** Controls as displayed: the car's state with in-flight commands applied (null = unknown). */
    private data class Shown(
        val acOn: Boolean? = null,
        val recircOn: Boolean? = null,
        val defrostFront: Boolean? = null,
        val defrostRear: Boolean? = null,
        val tempC: Int? = null,
        val fan: Int? = null,
        val vent: Int? = null,
        val drive: Int? = null,
        val regen: Int? = null,
        val avas: Int? = null,
        val ambient: Boolean? = null,
        val autoHold: Boolean? = null,
        val wireless: Boolean? = null
    )

    private data class MediaInfo(val track: String?, val app: String?, val isPlaying: Boolean)

    private val carHelper = CarApiHelper.getInstance()
    private val mainHandler = Handler(Looper.getMainLooper())
    /** Now-playing info is not VHAL: read on its own thread, alongside each car update. */
    private val mediaExecutor = Executors.newSingleThreadScheduledExecutor()
    private var carSub: CarStateHub.Subscription? = null

    private lateinit var prefs: SharedPreferences
    private lateinit var audioManager: AudioManager
    private var createdThemeMode = -1

    // Colours of the current theme
    private var cText = 0
    private var cMuted = 0
    private var cOnAccent = 0

    // Status strip
    private lateinit var tvDigitalClock: TextView
    private lateinit var tvOutsideTemp: TextView
    private lateinit var gearViews: List<Pair<TextView, Int>>
    private lateinit var tvStatusChip: TextView
    private lateinit var btnWifi: TextView

    // Widget views. The preset decides which widgets exist, so every group is a list (empty when
    // the widget is not on screen; several entries when two widgets show the same value).
    private var layoutItems: List<Placement> = emptyList()
    private var builtRevision = -1
    private lateinit var grid: BentoGridLayout
    private val viewsByKey = HashMap<String, View>()
    private var lastSnap = CarSnapshot(connected = false)

    // Edit mode (only in P)
    private var editing = false
    private val undoStack = ArrayList<List<Placement>>()
    private lateinit var editOverlay: EditOverlay
    private lateinit var normalStrip: View
    private lateinit var editStrip: View
    private lateinit var editTray: View
    private lateinit var trayItems: LinearLayout
    private lateinit var presetChips: LinearLayout
    private lateinit var btnEditLayout: TextView

    // Lái xe / Pin
    private var tvSpeedValue = listOf<TextView>()
    private var btnSpeedLimitSign = listOf<View>()
    private var tvSpeedLimitValue = listOf<TextView>()
    private var tvBatterySoc = listOf<TextView>()
    private var tvEstimatedRange = listOf<TextView>()
    private var pbBatterySoc = listOf<ProgressBar>()

    // Điều hòa
    private var tvClimateTargetTemp = listOf<TextView>()
    private var levelTemp = listOf<LevelIndicator>()
    private var tvClimateFanSpeed = listOf<TextView>()
    private var levelFan = listOf<LevelIndicator>()
    private var btnAc = listOf<TextView>()
    private var btnRecirc = listOf<View>()
    private var btnFresh = listOf<View>()
    private var btnVentFace = listOf<View>()
    private var btnVentFaceFloor = listOf<View>()
    private var btnVentFloor = listOf<View>()
    private var btnQuickCool = listOf<View>()
    private var btnDefrostFront = listOf<View>()
    private var btnDefrostRear = listOf<View>()

    // Xe · Cốp · Kính
    private var windowLabels = listOf<List<TextView>>()
    private var tvWindowHint = listOf<TextView>()
    private var btnFrontTrunk = listOf<TextView>()
    private var windowButtons = listOf<Pair<TextView, Int>>()

    // Nhạc
    private var tvMediaSource = listOf<TextView>()
    private var tvMediaTitle = listOf<TextView>()
    private var btnMediaPlayPause = listOf<ImageView>()
    private var sbVolume = listOf<SeekBar>()
    private var tvVolumeValue = listOf<TextView>()

    // Chế độ lái
    private var driveButtons = listOf<Pair<TextView, Int>>()
    private var regenButtons = listOf<Pair<TextView, Int>>()

    // Phím tắt
    private var btnAvas = listOf<TextView>()
    private var btnAutoHold = listOf<TextView>()
    private var btnAmbient = listOf<TextView>()
    private var btnWireless = listOf<TextView>()
    private var btnValet = listOf<TextView>()

    /** 1×1 tiles of the preset. */
    private class Tile(val kind: TileKind, val root: View, val icon: ImageView, val title: TextView, val state: TextView)
    private var tiles = listOf<Tile>()

    private var lastClimate: ClimateState? = null
    private var lastControls: ControlsState? = null
    private var shown = Shown()
    private val pending = PendingWrites()
    private var lastSpeed: Float? = null
    private var lastGear: Int? = null
    private var lastWindows: List<Int?>? = null
    private var lastSpeedLimitSource: String? = null

    private val clockRunnable = object : Runnable {
        override fun run() {
            tvDigitalClock.setTextIfChanged(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()))
            mainHandler.postDelayed(this, 1000L)
        }
    }

    /** "Đi đêm tối giản" layout: always the dark palette, dimmed a little more (see [applyNightLook]). */
    private var nightLook = false

    private fun isNightLayout(context: Context) = DashLayout.load(context).toSet() == DashPreset.NIGHT.placements.toSet()

    override fun attachBaseContext(newBase: Context) {
        nightLook = isNightLayout(newBase)
        super.attachBaseContext(ThemeManager.wrap(newBase, forceDark = nightLook))
    }

    /**
     * Night look: one uniform 18% dim over the whole Dashboard. It lowers white text, the accent
     * and the car photo together, so contrast ratios between them stay the same while the
     * screen glows less in a dark cabin.
     */
    private fun applyNightLook() {
        findViewById<View>(android.R.id.content).foreground =
            if (nightLook) android.graphics.drawable.ColorDrawable(0x2E000000) else null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createdThemeMode = ThemeManager.getMode(this)
        setContentView(R.layout.activity_main)
        ThemeManager.applyWindow(this)

        prefs = getSharedPreferences(CarAutomationService.PREFS_NAME, Context.MODE_PRIVATE)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        cText = getColor(R.color.dash_text)
        cMuted = getColor(R.color.dash_muted)
        cOnAccent = getColor(R.color.dash_on_accent)

        initViews()
        applyNightLook()
        buildGrid(DashLayout.load(this))
        setupListeners()
        startCoreServices()
        applyDisconnected()

        // Shared connection for the writes; reads go through CarStateHub (which holds its own).
        carHelper.acquire(this, VHAL_HOLDER)
    }

    override fun onResume() {
        super.onResume()
        if (ThemeManager.getMode(this) != createdThemeMode || isNightLayout(this) != nightLook) {
            recreate() // appearance changed in Cài đặt, or the night layout was picked / left
            return
        }
        if (DashLayout.revision(this) != builtRevision) buildGrid(DashLayout.load(this)) // preset changed in Cài đặt
        mainHandler.post(clockRunnable)
        refreshWifi()
        renderStatusChip()
        renderValet()
        syncVolumeFromSystem() // may have changed while another screen was in front
        registerReceiver(volumeReceiver, IntentFilter("android.media.VOLUME_CHANGED_ACTION"))
        carSub = CarStateHub.subscribe(this, SIGNALS, POLL_INTERVAL_MS) { state, _ -> onCarState(state) }
    }

    override fun onPause() {
        if (editing) exitEdit(save = true)
        try { unregisterReceiver(volumeReceiver) } catch (_: Throwable) {}
        mainHandler.removeCallbacks(clockRunnable)
        CarStateHub.unsubscribe(carSub)
        carSub = null
        super.onPause()
    }

    override fun onDestroy() {
        mediaExecutor.shutdownNow()
        carHelper.release(VHAL_HOLDER)
        super.onDestroy()
    }

    private fun initViews() {
        tvDigitalClock = findViewById(R.id.tvDigitalClock)
        tvOutsideTemp = findViewById(R.id.tvOutsideTemp)
        gearViews = listOf(
            findViewById<TextView>(R.id.tvGearP) to CarApiHelper.GEAR_PARK,
            findViewById<TextView>(R.id.tvGearR) to CarApiHelper.GEAR_REVERSE,
            findViewById<TextView>(R.id.tvGearN) to CarApiHelper.GEAR_NEUTRAL,
            findViewById<TextView>(R.id.tvGearD) to CarApiHelper.GEAR_DRIVE
        )
        tvStatusChip = findViewById(R.id.tvStatusChip)
        btnWifi = findViewById(R.id.btnWifi)
        grid = findViewById(R.id.bentoGrid)
        normalStrip = findViewById(R.id.normalStrip)
        editStrip = findViewById(R.id.editStrip)
        editTray = findViewById(R.id.editTray)
        trayItems = findViewById(R.id.trayItems)
        presetChips = findViewById(R.id.presetChips)
        btnEditLayout = findViewById(R.id.btnEditLayout)
        editOverlay = EditOverlay(this).apply {
            grid = this@MainActivity.grid
            visibility = View.GONE
            host = editHost
        }
        findViewById<FrameLayout>(R.id.gridHost).addView(
            editOverlay, 1, // above the grid, below the tray
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )
    }

    /** Places the layout's widgets on the grid, collects their views, wires and renders them. */
    private fun buildGrid(items: List<Placement>) {
        layoutItems = items
        builtRevision = DashLayout.revision(this)
        grid.removeAllViews()
        viewsByKey.clear()
        val inflater = layoutInflater
        val tileList = ArrayList<Tile>()
        for (p in items) {
            val view = inflater.inflate(p.type.layout, grid, false)
            grid.addView(view, BentoGridLayout.LayoutParams(p.col, p.row, p.colSpan, p.rowSpan))
            viewsByKey[p.key] = view
            if (editing) {
                view.scaleX = 0.97f
                view.scaleY = 0.97f
            }
            if (p.type == WidgetType.TILE && p.tile != null) {
                tileList += Tile(
                    p.tile, view,
                    view.findViewById(R.id.tileIcon), view.findViewById(R.id.tileTitle), view.findViewById(R.id.tileState)
                ).also { t ->
                    t.icon.setImageResource(p.tile.icon)
                    t.title.text = p.tile.title
                    t.root.contentDescription = p.tile.title
                }
            }
        }
        tiles = tileList

        tvSpeedValue = all(R.id.tvSpeedValue)
        btnSpeedLimitSign = all(R.id.btnSpeedLimitSign)
        tvSpeedLimitValue = all(R.id.tvSpeedLimitValue)
        tvBatterySoc = all(R.id.tvBatterySoc)
        tvEstimatedRange = all(R.id.tvEstimatedRange)
        pbBatterySoc = all(R.id.pbBatterySoc)

        tvClimateTargetTemp = all(R.id.tvClimateTargetTemp)
        levelTemp = all<LevelIndicator>(R.id.levelTemp).onEach {
            it.mode = LevelIndicator.Mode.TICKS
            it.steps = TEMP_MAX - TEMP_MIN + 1
        }
        tvClimateFanSpeed = all(R.id.tvClimateFanSpeed)
        levelFan = all<LevelIndicator>(R.id.levelFan).onEach {
            it.mode = LevelIndicator.Mode.BARS
            it.steps = FAN_MAX
            it.activeColor = getColor(R.color.dash_cool)
        }
        btnAc = all(R.id.btnAcCompressor)
        btnRecirc = all(R.id.btnRecirc)
        btnFresh = all(R.id.btnFresh)
        btnVentFace = all(R.id.btnVentFace)
        btnVentFaceFloor = all(R.id.btnVentFaceFloor)
        btnVentFloor = all(R.id.btnVentFloor)
        btnQuickCool = all(R.id.btnQuickCool)
        btnDefrostFront = all(R.id.btnDefrostFront)
        btnDefrostRear = all(R.id.btnDefrostRear)

        windowLabels = listOf(all(R.id.tvWindowFl), all(R.id.tvWindowFr), all(R.id.tvWindowRl), all(R.id.tvWindowRr))
        tvWindowHint = all(R.id.tvWindowHint)
        btnFrontTrunk = all(R.id.btnFrontTrunk)
        windowButtons = all<TextView>(R.id.btnWindowsClose).map { it to 0 } +
            all<TextView>(R.id.btnWindowsVent).map { it to 10 } +
            all<TextView>(R.id.btnWindowsHalf).map { it to 50 } +
            all<TextView>(R.id.btnWindowsOpen).map { it to 100 }

        tvMediaSource = all(R.id.tvMediaSource)
        tvMediaTitle = all(R.id.tvMediaTitle)
        btnMediaPlayPause = all(R.id.btnMediaPlayPause)
        sbVolume = all(R.id.sbVolume)
        tvVolumeValue = all(R.id.tvVolumeValue)

        driveButtons = all<TextView>(R.id.btnDriveEco).map { it to CarApiHelper.DRIVE_ECO } +
            all<TextView>(R.id.btnDriveComfort).map { it to CarApiHelper.DRIVE_COMFORT } +
            all<TextView>(R.id.btnDriveSport).map { it to CarApiHelper.DRIVE_SPORT }
        regenButtons = all<TextView>(R.id.btnRegenLow).map { it to CarApiHelper.REGEN_LOW } +
            all<TextView>(R.id.btnRegenMid).map { it to CarApiHelper.REGEN_MID } +
            all<TextView>(R.id.btnRegenHigh).map { it to CarApiHelper.REGEN_HIGH }

        btnAvas = all(R.id.btnAvasToggle)
        btnAutoHold = all(R.id.btnAutoHold)
        btnAmbient = all(R.id.btnAmbientLight)
        btnWireless = all(R.id.btnWirelessCharge)
        btnValet = all(R.id.btnValetMode)

        // Shortcut icons at 32dp (the 24dp glyphs read too small from the driver's seat).
        val icon = (32 * resources.displayMetrics.density).toInt()
        (btnAvas + btnAutoHold + btnAmbient + btnWireless + btnValet + all<TextView>(R.id.btnSplitScreen)).forEach { tv ->
            val d = tv.compoundDrawablesRelative[0] ?: return@forEach
            d.setBounds(0, 0, icon, icon)
            tv.setCompoundDrawablesRelative(d, null, null, null)
        }

        bindWidgetListeners()
        setupVolumeControl()
        renderAll()
        if (editing) {
            renderTray()
            editOverlay.invalidate()
        }
    }

    /** Everything on screen from the last known state (after a rebuild). */
    private fun renderAll() {
        if (lastSnap.connected) onCarState(lastSnap) else applyDisconnected()
        refreshMedia()
        renderValet()
    }

    /** Every view with [id] inside the grid (a value may appear in more than one widget). */
    @Suppress("UNCHECKED_CAST")
    private fun <T : View> all(id: Int): List<T> {
        val out = ArrayList<T>()
        fun walk(v: View) {
            if (v.id == id) out += v as T
            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(grid)
        return out
    }

    // -------------------------------------------------------------------------------------------
    // CAR STATE
    // -------------------------------------------------------------------------------------------

    private fun onCarState(s: CarSnapshot) {
        if (isFinishing || isDestroyed) return
        lastSnap = s
        // Safety: the layout can only be edited in P. Leaving P saves and locks it at once.
        if (editing && s.gear != CarApiHelper.GEAR_PARK) {
            exitEdit(save = true)
            Toast.makeText(this, "Đã lưu và khoá bố cục vì xe rời số P", Toast.LENGTH_SHORT).show()
        }
        renderEditButton(s.gear)
        refreshMedia()
        if (!s.connected) {
            applyDisconnected()
            return
        }
        lastClimate = s.climate
        lastControls = s.controls
        renderControls()
        renderDrive(s)
        tvOutsideTemp.setTextIfChanged(s.outsideTemp?.let { "${it.toInt()}°C ngoài trời" } ?: "--°C ngoài trời")
        lastWindows = s.windows
        renderWindows()
    }

    private fun applyDisconnected() {
        lastClimate = null
        lastControls = null
        renderControls()
        renderDrive(CarSnapshot(connected = false))
        tvOutsideTemp.setTextIfChanged("--°C ngoài trời")
        lastWindows = null
        renderWindows()
    }

    private fun renderDrive(s: CarSnapshot) {
        lastSpeed = s.speed
        lastGear = s.gear
        tvSpeedValue.forEach { it.setTextIfChanged(s.speed?.toInt()?.toString() ?: "--") }
        for ((view, gear) in gearViews) {
            val on = s.gear == gear
            setBackgroundIfChanged(view, if (on) R.drawable.dash_btn_on else 0)
            view.setTextColor(if (on) cOnAccent else cMuted)
        }
        val soc = s.soc?.toInt()
        tvBatterySoc.forEach { it.setTextIfChanged(soc?.let { v -> "$v%" } ?: "--%") }
        pbBatterySoc.forEach { it.progress = soc ?: 0 }
        tvEstimatedRange.forEach { it.setTextIfChanged(s.range?.let { r -> "còn ${r.toInt()} km" } ?: "-- km") }

        // Speed limit: only a real value from the car is shown (sign hidden otherwise).
        val limit = s.speedLimit
        btnSpeedLimitSign.forEach {
            it.visibility = if (limit != null) View.VISIBLE else View.INVISIBLE
            it.contentDescription = limit?.let { l -> "Giới hạn tốc độ ${l.kmh} km/h" } ?: ""
        }
        tvSpeedLimitValue.forEach { it.setTextIfChanged(limit?.kmh?.toString() ?: "") }
        if (limit?.source != lastSpeedLimitSource) {
            Log.i(TAG, "Speed limit source: ${limit?.source ?: "none"} (${limit?.kmh ?: "-"} km/h)")
            lastSpeedLimitSource = limit?.source
        }
    }

    private fun renderWindows() {
        val pos = lastWindows
        windowLabels.forEachIndexed { i, views ->
            views.forEach { it.setTextIfChanged(pos?.getOrNull(i)?.let { p -> "$p%" } ?: "--") }
        }
        // A preset is "current" only when all four panes report it.
        val all = pos?.takeIf { it.size == 4 && it.all { p -> p != null } }?.distinct()?.singleOrNull()
        val fast = !isSlowEnough()
        for ((view, percent) in windowButtons) {
            val locked = percent >= 50 && fast
            renderButton(view, all == percent && !locked)
            setStartIcon(view, if (locked) R.drawable.ic_lock else 0, cMuted)
            view.alpha = if (locked) 0.55f else 1f
        }
        tvWindowHint.forEach { it.setTextIfChanged(if (fast) "50/100% khi dưới 30 km/h" else "") }
        val parked = lastGear == CarApiHelper.GEAR_PARK
        btnFrontTrunk.forEach {
            it.alpha = if (parked) 1f else 0.55f
            setStartIcon(it, if (parked) R.drawable.ic_trunk_front else R.drawable.ic_lock, cText)
        }
        renderTiles()
    }

    /** Fail-closed: unknown speed counts as too fast. */
    private fun isSlowEnough() = (lastSpeed ?: Float.MAX_VALUE) < WINDOW_OPEN_MAX_KMH

    // -------------------------------------------------------------------------------------------
    // CONTROLS (car state + in-flight commands)
    // -------------------------------------------------------------------------------------------

    private fun rejectToast(label: String): (Any) -> Unit = {
        Toast.makeText(this, "Xe không nhận lệnh: $label", Toast.LENGTH_SHORT).show()
    }

    private fun renderControls() {
        val c = lastClimate
        val k = lastControls
        shown = Shown(
            acOn = pending.resolve(K_AC, c?.acOn, rejectToast("A/C")),
            recircOn = pending.resolve(K_RECIRC, c?.recircOn, rejectToast("Gió trong / ngoài")),
            defrostFront = pending.resolve(K_DEFROST_FRONT, c?.defrostFront, rejectToast("Sấy kính trước")),
            defrostRear = pending.resolve(K_DEFROST_REAR, c?.defrostRear, rejectToast("Sấy kính sau")),
            tempC = pending.resolve(K_TEMP, c?.tempC, rejectToast("Nhiệt độ")),
            fan = pending.resolve(K_FAN, c?.fan, rejectToast("Quạt gió")),
            vent = pending.resolve(K_VENT, c?.vent, rejectToast("Hướng gió")),
            drive = pending.resolve(K_DRIVE, k?.drive, rejectToast("Chế độ lái")),
            regen = pending.resolve(K_REGEN, k?.regen, rejectToast("Phanh tái sinh")),
            avas = pending.resolve(K_AVAS, k?.avas, rejectToast("AVAS")),
            ambient = pending.resolve(K_AMBIENT, k?.ambient, rejectToast("Đèn nội thất")),
            autoHold = pending.resolve(K_AUTO_HOLD, k?.autoHold, rejectToast("Auto Hold")),
            wireless = pending.resolve(K_WIRELESS, k?.wireless, rejectToast("Sạc không dây"))
        )
        val s = shown

        val tempText = s.tempC?.let { "$it°C" } ?: "--°C"
        val fanText = when (val f = s.fan) {
            null -> "--"
            0 -> "Tắt"
            else -> f.toString()
        }
        tvClimateTargetTemp.forEach {
            it.setTextIfChanged(tempText)
            it.alpha = if (pending.isPending(K_TEMP)) 0.7f else 1f
        }
        tvClimateFanSpeed.forEach {
            it.setTextIfChanged(fanText)
            it.alpha = if (pending.isPending(K_FAN)) 0.7f else 1f
        }
        levelTemp.forEach { it.value = s.tempC?.let { t -> (t - TEMP_MIN).coerceIn(0, TEMP_MAX - TEMP_MIN) } }
        levelFan.forEach { it.value = s.fan?.coerceIn(0, FAN_MAX) }

        btnAc.forEach { renderButton(it, s.acOn, K_AC) }
        btnRecirc.forEach { renderButton(it, s.recircOn, K_RECIRC) }
        btnFresh.forEach { renderButton(it, s.recircOn?.let { r -> !r }, K_RECIRC) }
        btnVentFace.forEach { renderSegment(it, s.vent?.let { v -> v == VENT_FACE }, K_VENT) }
        btnVentFaceFloor.forEach { renderSegment(it, s.vent?.let { v -> v == VENT_FACE_FLOOR }, K_VENT) }
        btnVentFloor.forEach { renderSegment(it, s.vent?.let { v -> v == VENT_FLOOR }, K_VENT) }
        btnDefrostFront.forEach { renderButton(it, s.defrostFront, K_DEFROST_FRONT) }
        btnDefrostRear.forEach { renderButton(it, s.defrostRear, K_DEFROST_REAR) }
        btnQuickCool.forEach { renderButton(it, false) }

        for ((view, mode) in driveButtons) renderSegment(view, s.drive?.let { it == mode }, K_DRIVE)
        for ((view, mode) in regenButtons) renderSegment(view, s.regen?.let { it == mode }, K_REGEN)

        btnAvas.forEach { renderButton(it, s.avas?.let { a -> a == 0 }, K_AVAS) } // lit = AVAS sound muted
        btnAutoHold.forEach { renderButton(it, s.autoHold, K_AUTO_HOLD) }
        btnAmbient.forEach { renderButton(it, s.ambient, K_AMBIENT) }
        btnWireless.forEach { renderButton(it, s.wireless, K_WIRELESS) }
        renderTiles()
    }

    /** 1×1 tiles: whole card lit when on, a plain word for the state underneath. */
    private fun renderTiles() {
        val s = shown
        for (t in tiles) {
            val (on, stateText, key) = when (t.kind) {
                TileKind.BLACKOUT -> Triple(false, "Chạm để tắt màn hình", null)
                TileKind.AMBIENT -> Triple(s.ambient, onOff(s.ambient), K_AMBIENT)
                TileKind.DEFROST -> Triple(s.defrostFront, onOff(s.defrostFront), K_DEFROST_FRONT)
                TileKind.AUTO_HOLD -> Triple(s.autoHold, onOff(s.autoHold), K_AUTO_HOLD)
                TileKind.AVAS -> Triple(s.avas?.let { it == 0 }, when (s.avas) {
                    null -> "--"
                    0 -> "Đã tắt âm"
                    else -> "Đang phát âm"
                }, K_AVAS)
                TileKind.WIRELESS -> Triple(s.wireless, onOff(s.wireless), K_WIRELESS)
                TileKind.VALET -> prefs.getBoolean(CarAutomationService.KEY_VALET_MODE, false)
                    .let { Triple(it, if (it) "Đang bật" else "Tắt", null) }
                TileKind.FRONT_TRUNK -> (lastGear == CarApiHelper.GEAR_PARK)
                    .let { Triple(false, if (it) "Cần xác nhận" else "Chỉ khi ở số P", null) }
            }
            val fg = if (on == true) cOnAccent else cText
            setBackgroundIfChanged(t.root, if (on == true) R.drawable.dash_btn_on else R.drawable.dash_chip)
            t.icon.imageTintList = ColorStateList.valueOf(fg)
            t.title.setTextColor(fg)
            t.state.setTextColor(if (on == true) cOnAccent else cMuted)
            t.state.setTextIfChanged(stateText)
            t.root.alpha = when {
                key != null && pending.isPending(key) -> 0.7f
                key != null && on == null -> 0.6f
                else -> 1f
            }
        }
    }

    private fun onOff(v: Boolean?) = when (v) {
        null -> "--"
        true -> "Bật"
        false -> "Tắt"
    }

    /**
     * On = accent fill; off = recessed well; unknown (null) = off and dimmed, so a guessed state is
     * never shown. A command still waiting for the car's confirmation is slightly dimmed.
     */
    private fun renderButton(view: View, on: Boolean?, key: String? = null) {
        setBackgroundIfChanged(view, if (on == true) R.drawable.dash_btn_on else R.drawable.dash_btn)
        styleState(view, on, key)
    }

    private fun renderSegment(view: View, on: Boolean?, key: String? = null) {
        setBackgroundIfChanged(view, if (on == true) R.drawable.dash_seg_item_on else R.drawable.dash_seg_item)
        styleState(view, on, key)
    }

    /**
     * setBackgroundResource() inflates a new drawable and may request a layout pass even for the
     * same resource; on every 3 s car update that made one slow frame. Only swap when it changes.
     */
    private fun setBackgroundIfChanged(view: View, res: Int) {
        if (view.getTag(R.id.tag_background) == res) return
        view.setTag(R.id.tag_background, res)
        view.setBackgroundResource(res)
    }

    /** Start icon + tint, only when they change (setCompoundDrawables… always relays the view out). */
    private fun setStartIcon(view: TextView, res: Int, tint: Int) {
        val key = res.toLong() shl 32 or (tint.toLong() and 0xFFFFFFFFL)
        if (view.getTag(R.id.tag_start_icon) == key) return
        view.setTag(R.id.tag_start_icon, key)
        view.setCompoundDrawablesWithIntrinsicBounds(res, 0, 0, 0)
        view.compoundDrawableTintList = ColorStateList.valueOf(tint)
    }

    private fun styleState(view: View, on: Boolean?, key: String?) {
        val fg = if (on == true) cOnAccent else cText
        when (view) {
            is TextView -> if (view.currentTextColor != fg) {
                view.setTextColor(fg)
                view.compoundDrawableTintList = ColorStateList.valueOf(fg)
            }
            is ImageView -> if (view.imageTintList?.defaultColor != fg) view.imageTintList = ColorStateList.valueOf(fg)
        }
        view.alpha = when {
            key != null && pending.isPending(key) -> 0.7f
            on == null -> 0.6f
            else -> 1f
        }
    }

    /**
     * Shows [wanted] immediately (tracked by [pending] so polls don't flicker it back),
     * writes through [CarCommands], and reconciles with the car shortly after.
     */
    private fun commandControl(key: String, wanted: Any, label: String, write: () -> Boolean) {
        pending.mark(key, wanted)
        renderControls()
        CarCommands.run(write, longArrayOf(POST_WRITE_REFRESH_MS)) { accepted ->
            if (!accepted && !isDestroyed) {
                pending.clear(key)
                renderControls()
                rejectToast(label)(wanted)
            }
        }
    }

    // -------------------------------------------------------------------------------------------
    // ACTIONS
    // -------------------------------------------------------------------------------------------

    private fun setupListeners() {
        // Status strip
        btnWifi.setOnClickListener { openWifiSettings() }
        findViewById<View>(R.id.btnNightBlackout).setOnClickListener { triggerBlackout() }
        findViewById<View>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        tvStatusChip.setOnClickListener {
            if (prefs.getBoolean(CarAutomationService.KEY_GUARD_HALTED, false)) showCrashGuardResetDialog()
            else toggleValetMode()
        }
        btnEditLayout.setOnClickListener { enterEdit() }
        findViewById<View>(R.id.btnEditDone).setOnClickListener { exitEdit(save = true) }
        findViewById<View>(R.id.btnEditUndo).setOnClickListener { undo() }
        findViewById<View>(R.id.btnEditAdd).setOnClickListener { showTray(editTray.visibility != View.VISIBLE) }
        findViewById<View>(R.id.btnTrayClose).setOnClickListener { showTray(false) }
        findViewById<View>(R.id.btnEditReset).setOnClickListener { applyEdit(DashPreset.DAILY.placements) }
        buildPresetChips()
    }

    /** Click handlers of the widgets on the grid (re-run after every rebuild). */
    private fun bindWidgetListeners() {

        // Lái xe
        btnSpeedLimitSign.forEach { v ->
            v.setOnClickListener {
                packageManager.getLaunchIntentForPackage("vn.vietmap.live")?.let { startActivity(it) }
            }
        }

        // Điều hòa — every button starts from the car's real state.
        all<View>(R.id.btnTempMinus).forEach { it.setOnClickListener { nudgeTemperature(-1) } }
        all<View>(R.id.btnTempPlus).forEach { it.setOnClickListener { nudgeTemperature(+1) } }
        all<View>(R.id.btnFanMinus).forEach { it.setOnClickListener { nudgeFan(-1) } }
        all<View>(R.id.btnFanPlus).forEach { it.setOnClickListener { nudgeFan(+1) } }
        btnAc.forEach { it.setOnClickListener { toggleAc() } }
        btnRecirc.forEach { it.setOnClickListener { setRecirc(true) } }
        btnFresh.forEach { it.setOnClickListener { setRecirc(false) } }
        btnVentFace.forEach { it.setOnClickListener { selectVent(VENT_FACE) } }
        btnVentFaceFloor.forEach { it.setOnClickListener { selectVent(VENT_FACE_FLOOR) } }
        btnVentFloor.forEach { it.setOnClickListener { selectVent(VENT_FLOOR) } }
        btnQuickCool.forEach { it.setOnClickListener { quickCool() } }
        btnDefrostFront.forEach { it.setOnClickListener { toggleDefrostFront() } }
        btnDefrostRear.forEach {
            it.setOnClickListener {
                val target = shown.defrostRear != true
                commandControl(K_DEFROST_REAR, target, "Sấy kính sau") { carHelper.setDefrostRear(target) }
            }
        }

        // Xe · Cốp · Kính
        for ((view, percent) in windowButtons) {
            view.setOnClickListener {
                if (percent >= 50) {
                    // Opening half or fully is only allowed at low speed (mis-tap on the highway);
                    // the speed must be readable, otherwise the command is blocked (fail-closed).
                    ParkGate.runIfAllowed(this, check = { ctx -> ParkGate.checkSlowerThan(ctx, WINDOW_OPEN_MAX_KMH) }) {
                        sendWindowCommand(percent)
                    }
                } else {
                    sendWindowCommand(percent)
                }
            }
        }
        btnFrontTrunk.forEach { it.setOnClickListener { confirmFrontTrunk() } }

        // Nhạc (title / icon follow the real playback state on the next refresh)
        btnMediaPlayPause.forEach {
            it.setOnClickListener {
                dispatchMediaKeyEvent(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                refreshMedia(600L)
            }
        }
        all<View>(R.id.btnMediaPrev).forEach {
            it.setOnClickListener {
                dispatchMediaKeyEvent(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                refreshMedia(800L)
            }
        }
        all<View>(R.id.btnMediaNext).forEach {
            it.setOnClickListener {
                dispatchMediaKeyEvent(KeyEvent.KEYCODE_MEDIA_NEXT)
                refreshMedia(800L)
            }
        }

        // Chế độ lái
        for ((view, mode) in driveButtons) view.setOnClickListener { selectDriveMode(mode) }
        for ((view, mode) in regenButtons) view.setOnClickListener { selectRegenMode(mode) }

        // Phím tắt
        btnAvas.forEach { it.setOnClickListener { toggleAvas() } }
        btnAutoHold.forEach { it.setOnClickListener { toggleAutoHold() } }
        btnAmbient.forEach { it.setOnClickListener { toggleAmbient() } }
        btnWireless.forEach { it.setOnClickListener { toggleWireless() } }
        all<View>(R.id.btnSplitScreen).forEach { it.setOnClickListener { SplitScreenLauncher.launchSplitScreen(this) } }
        btnValet.forEach { it.setOnClickListener { toggleValetMode() } }

        // Ô 1×1
        for (t in tiles) {
            t.root.setOnClickListener {
                when (t.kind) {
                    TileKind.BLACKOUT -> triggerBlackout()
                    TileKind.AMBIENT -> toggleAmbient()
                    TileKind.DEFROST -> toggleDefrostFront()
                    TileKind.AUTO_HOLD -> toggleAutoHold()
                    TileKind.VALET -> toggleValetMode()
                    TileKind.AVAS -> toggleAvas()
                    TileKind.WIRELESS -> toggleWireless()
                    TileKind.FRONT_TRUNK -> confirmFrontTrunk()
                }
            }
        }
    }

    // -------------------------------------------------------------------------------------------
    // EDIT MODE (only in P)
    // -------------------------------------------------------------------------------------------

    private val editHost = object : EditOverlay.Host {
        override fun items() = layoutItems
        override fun viewFor(p: Placement) = viewsByKey[p.key]
        override fun onRemove(p: Placement) = applyEdit(layoutItems - p)
        override fun onResize(p: Placement) = resizeWidget(p)
        override fun onMove(p: Placement, col: Int, row: Int) = moveWidget(p, col, row)
        override fun onEmptyTap() = showTray(true)
    }

    /** Header button: unlocked only in P; elsewhere it shows a lock and explains why. */
    private fun renderEditButton(gear: Int?) {
        val parked = gear == CarApiHelper.GEAR_PARK
        setStartIcon(btnEditLayout, if (parked) R.drawable.ic_edit else R.drawable.ic_lock, if (parked) cText else cMuted)
        btnEditLayout.setTextColor(if (parked) cText else cMuted)
    }

    private fun enterEdit() {
        if (lastSnap.gear != CarApiHelper.GEAR_PARK) {
            Toast.makeText(this, "Chỉ chỉnh bố cục khi xe ở số P", Toast.LENGTH_SHORT).show()
            return
        }
        editing = true
        undoStack.clear()
        normalStrip.visibility = View.GONE
        editStrip.visibility = View.VISIBLE
        editOverlay.visibility = View.VISIBLE
        for (v in viewsByKey.values) v.animate().scaleX(0.97f).scaleY(0.97f).setDuration(120).start()
        renderPresetChips()
        renderTray()
        editOverlay.invalidate()
    }

    private fun exitEdit(save: Boolean) {
        if (!editing) return
        editing = false
        if (save) {
            DashLayout.save(this, layoutItems)
            builtRevision = DashLayout.revision(this)
            if (isNightLayout(this) != nightLook) {
                mainHandler.post { recreate() } // switch to / from the night look
                return
            }
        }
        normalStrip.visibility = View.VISIBLE
        editStrip.visibility = View.GONE
        editTray.visibility = View.GONE
        editOverlay.visibility = View.GONE
        for (v in viewsByKey.values) v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
    }

    /** Applies a new layout while editing (with undo); invalid layouts are refused. */
    private fun applyEdit(items: List<Placement>) {
        if (!DashLayout.isValid(items)) {
            Toast.makeText(this, "Không đủ chỗ cho thay đổi này", Toast.LENGTH_SHORT).show()
            editOverlay.invalidate()
            return
        }
        if (items.toSet() == layoutItems.toSet()) return
        undoStack += layoutItems
        if (undoStack.size > 30) undoStack.removeAt(0)
        buildGrid(items)
        renderPresetChips()
    }

    private fun undo() {
        val prev = undoStack.removeLastOrNull() ?: return
        buildGrid(prev)
        renderPresetChips()
    }

    /**
     * Drop of [p] at (col, row): free spot → move; the widgets under it fit into the spot [p]
     * leaves → they swap in; otherwise they are pushed to the first free spots; if that is not
     * possible the widget bounces back.
     */
    private fun moveWidget(p: Placement, col: Int, row: Int) {
        val moved = p.copy(col = col, row = row)
        val others = layoutItems - p
        val hit = others.filter { it.overlaps(moved) }
        if (hit.isEmpty()) return applyEdit(others + moved)

        // Swap: shift the covered widgets by the opposite offset, into the spot p left.
        val dc = p.col - col
        val dr = p.row - row
        val shifted = hit.map { it.copy(col = it.col + dc, row = it.row + dr) }
        val swapped = (others - hit) + moved + shifted
        if (DashLayout.isValid(swapped)) return applyEdit(swapped)

        // Push aside: place the covered widgets in the first free spots that remain.
        val result = ArrayList((others - hit) + moved)
        for (h in hit.sortedByDescending { it.colSpan * it.rowSpan }) {
            val spot = DashLayout.firstFree(result, h) ?: return applyEdit(layoutItems + p) // refused → bounce
            result += spot
        }
        applyEdit(result)
    }

    /** "⇲": next size of the same widget, in place if it fits, else in the first free spot. */
    private fun resizeWidget(p: Placement) {
        val variants = p.type.variants
        if (variants.size < 2) return
        val next = variants[(variants.indexOf(p.type) + 1) % variants.size]
        val resized = p.copy(type = next)
        val others = layoutItems - p
        if (DashLayout.fits(resized) && others.none { it.overlaps(resized) }) return applyEdit(others + resized)
        val spot = DashLayout.firstFree(others, resized)
        if (spot == null) {
            Toast.makeText(this, "Không đủ chỗ để đổi sang ${next.sizeLabel}. Ẩn bớt widget rồi thử lại.", Toast.LENGTH_SHORT).show()
            return
        }
        applyEdit(others + spot)
    }

    private fun showTray(show: Boolean) {
        if (show) renderTray()
        editTray.visibility = if (show) View.VISIBLE else View.GONE
    }

    /** Tray: every widget that is not on the grid; tap = add it to the first free spot. */
    private fun renderTray() {
        trayItems.removeAllViews()
        val shown = layoutItems.map { it.key }.toSet()
        val hidden = DashLayout.catalog.filter { it.key !in shown }
        if (hidden.isEmpty()) {
            trayItems.addView(TextView(this).apply {
                setTextAppearance(R.style.DashMuted)
                text = "Tất cả widget đang hiển thị"
            })
            return
        }
        val d = resources.displayMetrics.density
        for (item in hidden) {
            val chip = TextView(this).apply {
                setTextAppearance(R.style.DashText)
                textSize = 20f
                typeface = resources.getFont(R.font.be_vietnam_pro_semibold)
                text = "${item.label}  ·  ${item.type.sizeLabel}"
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding((20 * d).toInt(), 0, (20 * d).toInt(), 0)
                setBackgroundResource(R.drawable.dash_btn)
                stateListAnimator = android.animation.AnimatorInflater.loadStateListAnimator(context, R.animator.dash_press)
                contentDescription = "Thêm ${item.label}"
                setOnClickListener { addWidget(item) }
            }
            trayItems.addView(chip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, (76 * d).toInt()).apply {
                marginEnd = (12 * d).toInt()
            })
        }
    }

    private fun addWidget(item: Placement) {
        val spot = DashLayout.firstFree(layoutItems, item)
        if (spot == null) {
            Toast.makeText(this, "Không còn ô trống ${item.type.sizeLabel}. Ẩn bớt widget (nút −) rồi thêm.", Toast.LENGTH_SHORT).show()
            return
        }
        applyEdit(layoutItems + spot)
        showTray(false)
    }

    private fun buildPresetChips() {
        presetChips.removeAllViews()
        val d = resources.displayMetrics.density
        DashPreset.values().forEachIndexed { i, preset ->
            presetChips.addView(TextView(this).apply {
                tag = preset
                text = preset.label
                textSize = 18f
                typeface = resources.getFont(R.font.be_vietnam_pro_semibold)
                gravity = android.view.Gravity.CENTER
                setPadding((18 * d).toInt(), 0, (18 * d).toInt(), 0)
                setOnClickListener { applyEdit(preset.placements) }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT).apply {
                if (i > 0) marginStart = (5 * d).toInt()
            })
        }
    }

    private fun renderPresetChips() {
        val current = layoutItems.toSet()
        for (i in 0 until presetChips.childCount) {
            val v = presetChips.getChildAt(i) as TextView
            val on = (v.tag as DashPreset).placements.toSet() == current
            v.setBackgroundResource(if (on) R.drawable.dash_seg_item_on else R.drawable.dash_seg_item)
            v.setTextColor(if (on) cOnAccent else cText)
        }
    }

    private fun triggerBlackout() {
        // Blackout is owned by FloatingControlService (one overlay owner, survives this
        // Activity, and auto-dismisses when the car shifts into R).
        startService(Intent(this, FloatingControlService::class.java).apply {
            action = FloatingControlService.ACTION_TRIGGER_BLACKOUT
        })
    }

    private fun toggleAc() {
        val target = shown.acOn != true
        commandControl(K_AC, target, "A/C") { carHelper.setAcCompressorOn(target) }
    }

    private fun toggleDefrostFront() {
        val target = shown.defrostFront != true
        commandControl(K_DEFROST_FRONT, target, "Sấy kính trước") { carHelper.setDefrostFront(target) }
    }

    private fun quickCool() {
        Toast.makeText(this, "Làm mát nhanh: A/C, 17°C, quạt 7, gió trong", Toast.LENGTH_SHORT).show()
        pending.mark(K_AC, true)
        pending.mark(K_TEMP, TEMP_MIN)
        pending.mark(K_FAN, 7)
        pending.mark(K_VENT, VENT_FACE)
        pending.mark(K_RECIRC, true)
        renderControls()
        carHelper.triggerQuickCool { scheduleRefresh(POST_WRITE_REFRESH_MS) }
    }

    private fun toggleAvas() {
        // The button expresses the wanted AVAS state; the automation service then holds it.
        val mute = shown.avas != 0
        prefs.edit().putBoolean(CarAutomationService.KEY_AUTO_AVAS, mute).apply()
        commandControl(K_AVAS, if (mute) 0 else 1, "AVAS") { carHelper.setAvasMode(if (mute) 0 else 1) }
    }

    /** Same switch as the car's own Settings. Unknown state -> the tap asks for ON. */
    private fun toggleAutoHold() {
        val target = shown.autoHold != true
        commandControl(K_AUTO_HOLD, target, "Auto Hold") { carHelper.setAutoHold(target) }
    }

    private fun toggleAmbient() {
        val target = shown.ambient != true
        commandControl(K_AMBIENT, target, "Đèn nội thất") { carHelper.setAmbientLight(target) }
    }

    private fun toggleWireless() {
        val target = shown.wireless != true
        commandControl(K_WIRELESS, target, "Sạc không dây") { carHelper.setWirelessCharging(target) }
    }

    private fun nudgeTemperature(delta: Int) {
        val current = shown.tempC
        if (current == null) {
            Toast.makeText(this, "Chưa đọc được nhiệt độ cài đặt từ xe.", Toast.LENGTH_SHORT).show()
            return
        }
        val target = (current + delta).coerceIn(TEMP_MIN, TEMP_MAX)
        if (target == current) return
        commandControl(K_TEMP, target, "Nhiệt độ") { carHelper.writeTargetTemperature(target.toFloat()) }
    }

    private fun nudgeFan(delta: Int) {
        val current = shown.fan
        if (current == null) {
            Toast.makeText(this, "Chưa đọc được mức quạt từ xe.", Toast.LENGTH_SHORT).show()
            return
        }
        if (current == 0 && delta < 0) return // fan off: "−" must not switch it on
        val target = (current + delta).coerceIn(FAN_MIN, FAN_MAX)
        if (target == current) return
        commandControl(K_FAN, target, "Quạt gió") { carHelper.writeFanSpeed(target) }
    }

    private fun setRecirc(on: Boolean) {
        if (shown.recircOn == on && !pending.isPending(K_RECIRC)) return
        commandControl(K_RECIRC, on, if (on) "Gió trong" else "Gió ngoài") { carHelper.setRecirculation(on) }
    }

    private fun selectVent(mask: Int) {
        if (shown.vent == mask && !pending.isPending(K_VENT)) return
        commandControl(K_VENT, mask, "Hướng gió") { carHelper.setAirflowDirection(mask) }
    }

    private fun selectDriveMode(mode: Int) {
        prefs.edit().putInt(CarAutomationService.KEY_PREFERRED_DRIVE_MODE, mode)
            .putLong(CarAutomationService.KEY_MODE_CHANGED_AT, System.currentTimeMillis()).apply()
        commandControl(K_DRIVE, mode, "Chế độ lái") { carHelper.writeDriveMode(mode) }
    }

    private fun selectRegenMode(mode: Int) {
        prefs.edit().putInt(CarAutomationService.KEY_PREFERRED_REGEN_MODE, mode)
            .putLong(CarAutomationService.KEY_MODE_CHANGED_AT, System.currentTimeMillis()).apply()
        commandControl(K_REGEN, mode, "Phanh tái sinh") { carHelper.writeRegenMode(mode) }
    }

    private fun sendWindowCommand(percent: Int) {
        // Never optimistic: the re-reads show what the glass really reports.
        CarCommands.run({ carHelper.setAllWindowsPosition(percent) }, longArrayOf(1500L, 5000L)) { ok ->
            if (!ok && !isDestroyed) Toast.makeText(this, "Xe không nhận lệnh kính.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmFrontTrunk() {
        ParkGate.runIfAllowed(this) {
            AppDialog(this)
                .setTitle("Mở cốp trước?")
                .setMessage("Kiểm tra phía trước xe trống, không có người. Cốp cần đóng lại bằng tay.")
                .setPositiveButton("Mở cốp") { _, _ ->
                    ParkGate.runIfAllowed(this) {
                        CarCommands.run({ carHelper.openFrontTrunk() }, longArrayOf()) { ok ->
                            if (!isDestroyed) Toast.makeText(
                                this, if (ok) "Đã gửi lệnh mở cốp trước" else "Xe không nhận lệnh: Mở cốp trước",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
                .setNegativeButton("Hủy", null)
                .show()
        }
    }

    /** The car's own Wi-Fi screen (Flyme connectivity settings) — no hidden menu needed. */
    private fun openWifiSettings() {
        val intents = listOf(
            Intent("com.flyme.auto.intent.action.ACTION_WIFI_SETTINGS"),
            Intent(Settings.ACTION_WIFI_SETTINGS)
        )
        for (intent in intents) {
            try {
                startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: Throwable) {}
        }
        Toast.makeText(this, "Không mở được màn hình Wi-Fi của xe.", Toast.LENGTH_SHORT).show()
    }

    private fun refreshWifi() {
        btnWifi.setTextIfChanged(WifiStatus.read(this).ssidLabel)
    }

    // -------------------------------------------------------------------------------------------
    // AUTOMATION STATUS (Crash Guard / Valet)
    // -------------------------------------------------------------------------------------------

    /** Header chip only when something needs the driver's attention. */
    private fun renderStatusChip() {
        val halted = prefs.getBoolean(CarAutomationService.KEY_GUARD_HALTED, false)
        val valet = prefs.getBoolean(CarAutomationService.KEY_VALET_MODE, false)
        when {
            halted -> {
                tvStatusChip.visibility = View.VISIBLE
                tvStatusChip.setTextIfChanged("Crash Guard đã ngắt")
                tvStatusChip.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_warning, 0, 0, 0)
            }
            valet -> {
                tvStatusChip.visibility = View.VISIBLE
                tvStatusChip.setTextIfChanged("Valet đang bật")
                tvStatusChip.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_wrench, 0, 0, 0)
            }
            else -> tvStatusChip.visibility = View.GONE
        }
        tvStatusChip.compoundDrawableTintList = ColorStateList.valueOf(getColor(R.color.dash_accent_text))
    }

    private fun renderValet() {
        val on = prefs.getBoolean(CarAutomationService.KEY_VALET_MODE, false)
        btnValet.forEach { renderButton(it, on) }
        renderTiles()
    }

    private fun showCrashGuardResetDialog() {
        AppDialog(this)
            .setTitle("Cảnh báo Crash Guard")
            .setMessage("Hệ thống tự động hóa đã tự ngắt do phát hiện lỗi liên tiếp, để tránh treo hệ thống.\n\nĐặt lại để tự động hóa chạy tiếp?")
            .setPositiveButton("Đặt lại") { _, _ ->
                startService(Intent(this, CarAutomationService::class.java).apply {
                    action = CarAutomationService.ACTION_RESET_GUARD
                })
                prefs.edit().putBoolean(CarAutomationService.KEY_GUARD_HALTED, false).apply()
                renderStatusChip()
                Toast.makeText(this, "Đã đặt lại Crash Guard.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun toggleValetMode() {
        val next = !prefs.getBoolean(CarAutomationService.KEY_VALET_MODE, false)
        prefs.edit().putBoolean(CarAutomationService.KEY_VALET_MODE, next).apply()
        renderValet()
        renderStatusChip()
        Toast.makeText(
            this,
            if (next) "Valet: ĐÃ BẬT. Tạm dừng mọi tự động hóa." else "Valet: ĐÃ TẮT. Tự động hóa hoạt động trở lại.",
            Toast.LENGTH_SHORT
        ).show()
    }

    // -------------------------------------------------------------------------------------------
    // MEDIA & VOLUME
    // -------------------------------------------------------------------------------------------

    private var isTrackingVolume = false

    /** Volume changed elsewhere (wheel keys, the car's volume bar, another app): follow at once. */
    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val stream = intent?.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1) ?: -1
            if (stream == -1 || stream == AudioManager.STREAM_MUSIC) syncVolumeFromSystem()
        }
    }

    /** Reflects the real media volume on the slider (never while the driver is dragging it). */
    private fun syncVolumeFromSystem() {
        if (isTrackingVolume) return
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        sbVolume.forEach {
            if (it.max != max) it.max = max
            if (it.progress != current) it.progress = current
        }
        tvVolumeValue.forEach { it.setTextIfChanged(current.toString()) }
    }

    private fun setupVolumeControl() {
        syncVolumeFromSystem()
        val listener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, progress, 0)
                    tvVolumeValue.forEach { it.setTextIfChanged(progress.toString()) }
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) { isTrackingVolume = true }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isTrackingVolume = false
                syncVolumeFromSystem()
            }
        }
        sbVolume.forEach { it.setOnSeekBarChangeListener(listener) }
    }

    /** Re-reads the car and now-playing info shortly after an action. */
    private fun scheduleRefresh(delayMs: Long) {
        CarStateHub.refresh(delayMs)
        refreshMedia(delayMs)
    }

    /** Now-playing title / state off the main thread (binder calls), then volume. */
    private fun refreshMedia(delayMs: Long = 0L) {
        try {
            mediaExecutor.schedule({
                val media = readMediaInfo()
                runOnUiThread { if (!isDestroyed) applyMedia(media) }
            }, delayMs, TimeUnit.MILLISECONDS)
        } catch (_: Throwable) {}
    }

    /** Runs on [mediaExecutor]. Real now-playing info; nulls when unknown. */
    private fun readMediaInfo(): MediaInfo {
        var playing = try { audioManager.isMusicActive } catch (_: Throwable) { false }
        var track: String? = null
        var app: String? = null
        try {
            // Needs MEDIA_CONTENT_CONTROL (granted to the system uid on the head unit).
            val msm = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val controllers = msm.getActiveSessions(null)
            val active = controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
                ?: controllers.firstOrNull()
            if (active != null) {
                app = try {
                    packageManager.getApplicationLabel(packageManager.getApplicationInfo(active.packageName, 0)).toString()
                } catch (_: Throwable) {
                    active.packageName
                }
                track = active.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }
                playing = active.playbackState?.state == PlaybackState.STATE_PLAYING
            }
        } catch (_: Throwable) {
            // No permission (e.g. emulator, not system uid) — keep isMusicActive only.
        }
        return MediaInfo(track, app, playing)
    }

    private fun applyMedia(info: MediaInfo) {
        val source = info.app?.let { "· $it" } ?: ""
        val title = when {
            info.track != null -> info.track
            info.isPlaying -> "Đang phát"
            else -> "Chưa có nội dung đang phát"
        }
        tvMediaSource.forEach { it.setTextIfChanged(source) }
        tvMediaTitle.forEach {
            it.setTextIfChanged(title)
            it.setTextColor(if (info.track != null || info.isPlaying) cText else cMuted)
        }
        val icon = if (info.isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        btnMediaPlayPause.forEach {
            if (it.tag != icon) {
                it.setImageResource(icon)
                it.tag = icon
            }
        }
        syncVolumeFromSystem()
    }

    private fun dispatchMediaKeyEvent(keyCode: Int) {
        try {
            val now = SystemClock.uptimeMillis()
            audioManager.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
            audioManager.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
        } catch (_: Throwable) {
            val intent = Intent(Intent.ACTION_MEDIA_BUTTON)
            intent.putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            sendOrderedBroadcast(intent, null)
        }
    }

    private fun startCoreServices() {
        startForegroundService(Intent(this, CarAutomationService::class.java))
        // Floating bubble and/or the bottom-bar climate panel (both hosted by FloatingControlService).
        if (FloatingControlService.isWanted(this)) startService(Intent(this, FloatingControlService::class.java))
    }
}
