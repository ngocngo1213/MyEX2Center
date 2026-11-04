package com.mipago.ex2center.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.mipago.ex2center.R
import com.mipago.ex2center.launcher.SplitScreenLauncher
import com.mipago.ex2center.service.CarAutomationService
import com.mipago.ex2center.service.FloatingControlService
import com.mipago.ex2center.utils.CarApiHelper
import com.mipago.ex2center.utils.ParkGate
import com.mipago.ex2center.utils.WifiStatus
import com.mipago.ex2center.utils.PendingWrites
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Main Activity & Dashboard for EX2 Center
 *
 * Driver-Centric Cockpit layout (Cyber Emerald & Obsidian):
 * - Top Status Pill Bar: Digital Clock, Outside Temp, 12V Battery, Automation/CrashGuard/Valet Pill, Wi-Fi info, Quick actions.
 * - Column 1 (32%): Driver Operating HUD (Gear, Speed, Battery), Drive Mode & Regen, 6 Core Shortcuts.
 * - Column 2 (38%): Multitasking & Climate (Hero Split-Screen, Media & Volume, Smart Climate with Quick Cool).
 * - Column 3 (30%): Visual Vehicle Status (TPMS 4 Wheels + 4 Window Positions, Quick Window Actions; trunk removed until a verified property exists).
 */
class MainActivity : Activity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val WINDOW_OPEN_MAX_KMH = 30f
        private const val VHAL_HOLDER = "dashboard"

        private const val TEMP_MIN = 17
        private const val TEMP_MAX = 32
        private const val FAN_MIN = 1
        private const val FAN_MAX = 8
        private const val SLIDER_WRITE_DEBOUNCE_MS = 250L
        private const val EVENT_REFRESH_DEBOUNCE_MS = 200L
        private const val POST_WRITE_REFRESH_MS = 1200L

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

        private val WATCH_PROPS = intArrayOf(
            CarApiHelper.PROP_GEAR,
            CarApiHelper.PROP_HVAC_POWER,
            CarApiHelper.PROP_HVAC_AC_ON,
            CarApiHelper.PROP_HVAC_TEMP_SETPOINT,
            CarApiHelper.PROP_HVAC_FAN_SPEED,
            CarApiHelper.PROP_HVAC_AIR_DIRECTION,
            CarApiHelper.PROP_HVAC_RECIRC,
            CarApiHelper.PROP_HVAC_DEFROST_FRONT,
            CarApiHelper.PROP_HVAC_DEFROST_REAR,
            CarApiHelper.PROP_DRIVE,
            CarApiHelper.PROP_REGEN,
            CarApiHelper.PROP_WINDOW_POS
        )
    }

    /** Control state exactly as the car reports it (null = unknown, never assumed). */
    private data class ControlSnapshot(
        val acOn: Boolean? = null,
        val recircOn: Boolean? = null,
        val defrostFront: Boolean? = null,
        val defrostRear: Boolean? = null,
        val tempC: Int? = null,
        val fan: Int? = null,
        val vent: Int? = null,
        val drive: Int? = null,
        val regen: Int? = null,
        val avas: Int? = null
    )

    private data class MediaInfo(val title: String?, val isPlaying: Boolean)

    private val carHelper = CarApiHelper.getInstance()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val telemetryExecutor = Executors.newSingleThreadScheduledExecutor()
    private val climateExecutor = Executors.newSingleThreadExecutor()
    private var telemetryFuture: ScheduledFuture<*>? = null

    private lateinit var prefs: SharedPreferences
    private lateinit var audioManager: AudioManager

    // Top Header Views
    private lateinit var tvDigitalClock: TextView
    private lateinit var tvOutsideTemp: TextView
    private lateinit var tvBattery12v: TextView
    private lateinit var layoutAutomationPill: LinearLayout
    private lateinit var ivAutomationIcon: ImageView
    private lateinit var tvAutomationStatus: TextView
    private lateinit var tvWifiSsid: TextView
    private lateinit var tvWifiIp: TextView
    private lateinit var btnOpenCatalogHeader: ImageView
    private lateinit var btnNightBlackout: ImageView
    private lateinit var btnSettings: ImageView

    // Column 1: Driver Zone
    private lateinit var tvGearP: TextView
    private lateinit var tvGearR: TextView
    private lateinit var tvGearN: TextView
    private lateinit var tvGearD: TextView
    private lateinit var tvSpeedValue: TextView
    private lateinit var tvEstimatedRange: TextView
    private lateinit var tvBatterySoc: TextView
    private lateinit var pbBatterySoc: ProgressBar

    private lateinit var btnDriveEco: TextView
    private lateinit var btnDriveComfort: TextView
    private lateinit var btnDriveSport: TextView
    private lateinit var btnRegenLow: TextView
    private lateinit var btnRegenMid: TextView
    private lateinit var btnRegenHigh: TextView

    private lateinit var btnAvasToggle: LinearLayout
    private lateinit var tvAvasLabel: TextView
    private lateinit var btnCamera360: LinearLayout
    private lateinit var btnValetMode: LinearLayout
    private lateinit var tvValetLabel: TextView

    // Column 2: Multitasking & Climate
    private lateinit var btnSplitScreenHero: LinearLayout
    private lateinit var tvMediaTitle: TextView
    private lateinit var btnMediaPrev: ImageView
    private lateinit var btnMediaPlayPause: ImageView
    private lateinit var btnMediaNext: ImageView
    private lateinit var sbVolume: SeekBar
    private lateinit var tvVolumeValue: TextView

    private lateinit var btnAcCompressor: TextView
    private lateinit var btnRecirc: TextView
    private lateinit var btnQuickCool: LinearLayout

    private lateinit var tvClimateTargetTemp: TextView
    private lateinit var btnTempMinus: Button
    private lateinit var sbTempGradient: SeekBar
    private lateinit var btnTempPlus: Button

    private lateinit var tvClimateFanSpeed: TextView
    private lateinit var btnFanMinus: Button
    private lateinit var sbFanSpeed: SeekBar
    private lateinit var btnFanPlus: Button

    private lateinit var btnVentFace: LinearLayout
    private lateinit var btnVentFloor: LinearLayout
    private lateinit var btnDefrostFront: LinearLayout
    private lateinit var btnDefrostRear: LinearLayout

    // Column 3: TPMS & Windows
    private lateinit var tvTpmsFlKpa: TextView
    private lateinit var tvTpmsFlTemp: TextView
    private lateinit var tvTpmsFrKpa: TextView
    private lateinit var tvTpmsFrTemp: TextView
    private lateinit var tvTpmsRlKpa: TextView
    private lateinit var tvTpmsRlTemp: TextView
    private lateinit var tvTpmsRrKpa: TextView
    private lateinit var tvTpmsRrTemp: TextView
    private lateinit var tvTpmsUpdated: TextView

    private lateinit var tvWindowFl: TextView
    private lateinit var tvWindowFr: TextView
    private lateinit var tvWindowRl: TextView
    private lateinit var tvWindowRr: TextView

    private lateinit var btnWindowsClose: TextView
    private lateinit var btnWindowsVent: TextView
    private lateinit var btnWindowsOpen: TextView
    private lateinit var btnSpeedLimitSign: LinearLayout
    private lateinit var tvSpeedLimitValue: TextView

    // Control state: what the car reported last, and what is displayed (car state + in-flight
    // commands from PendingWrites). Never seeded with assumed values.
    private var lastControls = ControlSnapshot()
    private var shown = ControlSnapshot()
    private val pending = PendingWrites()

    private var isTrackingTempSlider = false
    private var isTrackingFanSlider = false
    @Volatile private var watchToken: Any? = null

    // Debounce runnables for sliders
    private var tempDebounceRunnable: Runnable? = null
    private var fanDebounceRunnable: Runnable? = null

    /** VHAL change event → one coalesced refresh. */
    private val eventRefresh = Runnable { scheduleTelemetryRefresh(0L) }

    private val clockRunnable = object : Runnable {
        override fun run() {
            updateDigitalClock()
            mainHandler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences(CarAutomationService.PREFS_NAME, Context.MODE_PRIVATE)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        initViews()
        setupWifiInfo()
        setupListeners()
        startCoreServices()

        // Shared connection: releasing it here never cuts off CarAutomationService/overlay.
        carHelper.acquire(this, VHAL_HOLDER) { ready ->
            if (ready && !telemetryExecutor.isShutdown) {
                try {
                    telemetryExecutor.execute { pollTelemetryBackground() }
                } catch (_: Throwable) {}
            }
        }
    }

    override fun onResume() {
        super.onResume()
        mainHandler.post(clockRunnable)
        setupWifiInfo()
        updateAutomationPillUI()

        // Start 3-second background polling via ScheduledExecutorService (no main thread blocking)
        telemetryFuture?.cancel(false)
        telemetryFuture = telemetryExecutor.scheduleWithFixedDelay(
            { pollTelemetryBackground() },
            0L,
            3000L,
            TimeUnit.MILLISECONDS
        )
        try {
            telemetryExecutor.execute { ensureWatch() }
        } catch (_: Throwable) {}
    }

    override fun onPause() {
        mainHandler.removeCallbacks(clockRunnable)
        mainHandler.removeCallbacks(eventRefresh)
        val token = watchToken
        watchToken = null
        if (token != null) {
            try {
                telemetryExecutor.execute { carHelper.unwatch(token) }
            } catch (_: Throwable) {}
        }
        telemetryFuture?.cancel(false)
        telemetryFuture = null
        super.onPause()
    }

    override fun onDestroy() {
        telemetryExecutor.shutdownNow()
        climateExecutor.shutdownNow()
        carHelper.release(VHAL_HOLDER)
        super.onDestroy()
    }

    private fun initViews() {
        // Top Header
        tvDigitalClock = findViewById(R.id.tvDigitalClock)
        tvOutsideTemp = findViewById(R.id.tvOutsideTemp)
        tvBattery12v = findViewById(R.id.tvBattery12v)
        layoutAutomationPill = findViewById(R.id.layoutAutomationPill)
        ivAutomationIcon = findViewById(R.id.ivAutomationIcon)
        tvAutomationStatus = findViewById(R.id.tvAutomationStatus)
        tvWifiSsid = findViewById(R.id.tvWifiSsid)
        tvWifiIp = findViewById(R.id.tvWifiIp)
        btnOpenCatalogHeader = findViewById(R.id.btnOpenCatalogHeader)
        btnNightBlackout = findViewById(R.id.btnNightBlackout)
        btnSettings = findViewById(R.id.btnSettings)

        // Column 1: Driver Zone
        tvGearP = findViewById(R.id.tvGearP)
        tvGearR = findViewById(R.id.tvGearR)
        tvGearN = findViewById(R.id.tvGearN)
        tvGearD = findViewById(R.id.tvGearD)
        tvSpeedValue = findViewById(R.id.tvSpeedValue)
        tvEstimatedRange = findViewById(R.id.tvEstimatedRange)
        tvBatterySoc = findViewById(R.id.tvBatterySoc)
        pbBatterySoc = findViewById(R.id.pbBatterySoc)

        btnDriveEco = findViewById(R.id.btnDriveEco)
        btnDriveComfort = findViewById(R.id.btnDriveComfort)
        btnDriveSport = findViewById(R.id.btnDriveSport)
        btnRegenLow = findViewById(R.id.btnRegenLow)
        btnRegenMid = findViewById(R.id.btnRegenMid)
        btnRegenHigh = findViewById(R.id.btnRegenHigh)

        btnAvasToggle = findViewById(R.id.btnAvasToggle)
        tvAvasLabel = findViewById(R.id.tvAvasLabel)
        btnCamera360 = findViewById(R.id.btnCamera360)
        btnValetMode = findViewById(R.id.btnValetMode)
        tvValetLabel = findViewById(R.id.tvValetLabel)

        // Column 2: Multitasking & Climate
        btnSplitScreenHero = findViewById(R.id.btnSplitScreenHero)
        tvMediaTitle = findViewById(R.id.tvMediaTitle)
        btnMediaPrev = findViewById(R.id.btnMediaPrev)
        btnMediaPlayPause = findViewById(R.id.btnMediaPlayPause)
        btnMediaNext = findViewById(R.id.btnMediaNext)
        sbVolume = findViewById(R.id.sbVolume)
        tvVolumeValue = findViewById(R.id.tvVolumeValue)

        btnAcCompressor = findViewById(R.id.btnAcCompressor)
        btnRecirc = findViewById(R.id.btnRecirc)
        btnQuickCool = findViewById(R.id.btnQuickCool)

        tvClimateTargetTemp = findViewById(R.id.tvClimateTargetTemp)
        btnTempMinus = findViewById(R.id.btnTempMinus)
        sbTempGradient = findViewById(R.id.sbTempGradient)
        btnTempPlus = findViewById(R.id.btnTempPlus)

        tvClimateFanSpeed = findViewById(R.id.tvClimateFanSpeed)
        btnFanMinus = findViewById(R.id.btnFanMinus)
        sbFanSpeed = findViewById(R.id.sbFanSpeed)
        btnFanPlus = findViewById(R.id.btnFanPlus)

        btnVentFace = findViewById(R.id.btnVentFace)
        btnVentFloor = findViewById(R.id.btnVentFloor)
        btnDefrostFront = findViewById(R.id.btnDefrostFront)
        btnDefrostRear = findViewById(R.id.btnDefrostRear)

        // Column 3: TPMS & Windows
        tvTpmsFlKpa = findViewById(R.id.tvTpmsFlKpa)
        tvTpmsFlTemp = findViewById(R.id.tvTpmsFlTemp)
        tvTpmsFrKpa = findViewById(R.id.tvTpmsFrKpa)
        tvTpmsFrTemp = findViewById(R.id.tvTpmsFrTemp)
        tvTpmsRlKpa = findViewById(R.id.tvTpmsRlKpa)
        tvTpmsRlTemp = findViewById(R.id.tvTpmsRlTemp)
        tvTpmsRrKpa = findViewById(R.id.tvTpmsRrKpa)
        tvTpmsRrTemp = findViewById(R.id.tvTpmsRrTemp)
        tvTpmsUpdated = findViewById(R.id.tvTpmsUpdated)

        tvWindowFl = findViewById(R.id.tvWindowFl)
        tvWindowFr = findViewById(R.id.tvWindowFr)
        tvWindowRl = findViewById(R.id.tvWindowRl)
        tvWindowRr = findViewById(R.id.tvWindowRr)

        btnWindowsClose = findViewById(R.id.btnWindowsClose)
        btnWindowsVent = findViewById(R.id.btnWindowsVent)
        btnWindowsOpen = findViewById(R.id.btnWindowsOpen)
        btnSpeedLimitSign = findViewById(R.id.btnSpeedLimitSign)
        tvSpeedLimitValue = findViewById(R.id.tvSpeedLimitValue)
        // No verified source for the road speed limit yet — never show a made-up number.
        tvSpeedLimitValue.text = "--"

        setupVolumeControl()
        updateAutomationPillUI()
        updateWindowPositionUI(arrayOfNulls(4))
    }

    private fun updateDigitalClock() {
        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
        val timeStr = sdf.format(Date())
        tvDigitalClock.text = timeStr
    }

    private fun setupWifiInfo() {
        val wifi = WifiStatus.read(this)
        tvWifiSsid.text = wifi.ssidLabel
        tvWifiIp.text = wifi.ip
    }

    private fun setupVolumeControl() {
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        sbVolume.max = maxVolume
        sbVolume.progress = currentVolume
        tvVolumeValue.text = currentVolume.toString()

        sbVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, progress, 0)
                    tvVolumeValue.text = progress.toString()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    /**
     * Polls VHAL properties in a background executor thread to avoid UI Binder blocking.
     * Posts immutable data to UI thread for rendering with fallback display.
     */
    private fun pollTelemetryBackground() {
        val media = readMediaInfo()
        if (!carHelper.isReady) {
            watchToken = null // the connection that owned the callback is gone
            carHelper.connect(this) // re-establish after suspend/wake; cheap no-op if in progress
            runOnUiThread {
                applyFallbackTelemetry()
                applyMediaInfo(media)
            }
            return
        }
        ensureWatch()

        try {
            val speed = carHelper.readSpeed()
            val gear = carHelper.readGear()
            val soc = carHelper.readBatterySoc()
            val range = carHelper.readEstimatedRange()
            val outsideTemp = carHelper.readOutsideTemperature()
            val v12 = carHelper.read12vBatteryVoltage(this)
            val tpms = carHelper.readTpmsData()
            val winPos = carHelper.readWindowPositions()
            val controls = readControlSnapshot()

            runOnUiThread {
                lastControls = controls
                renderControls()
                applyMediaInfo(media)
                applyTelemetryData(
                    speed = speed,
                    gear = gear,
                    soc = soc,
                    range = range,
                    outsideTemp = outsideTemp,
                    v12 = v12,
                    tpms = tpms,
                    winPos = winPos
                )
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Error polling background telemetry: ${t.message}")
            runOnUiThread {
                applyFallbackTelemetry()
                applyMediaInfo(media)
            }
        }
    }

    private fun applyTelemetryData(
        speed: Float?,
        gear: Int?,
        soc: Float?,
        range: Float?,
        outsideTemp: Float?,
        v12: Float?,
        tpms: CarApiHelper.TpmsData,
        winPos: Array<Int?>
    ) {
        // Speed & Gear
        tvSpeedValue.text = if (speed != null) speed.toInt().toString() else "--"
        updateGearUI(gear ?: -1)

        // Battery SoC & Estimated Range
        if (soc != null) {
            tvBatterySoc.text = "${soc.toInt()}%"
            pbBatterySoc.progress = soc.toInt()
        } else {
            tvBatterySoc.text = "--%"
            pbBatterySoc.progress = 0
        }

        tvEstimatedRange.text = if (range != null) "${range.toInt()} km" else "-- km"

        // Outside Temp & 12V Battery
        tvOutsideTemp.text = if (outsideTemp != null) "${outsideTemp.toInt()}°C" else "--°C"
        if (v12 != null) {
            tvBattery12v.text = String.format(Locale.US, "%.1f V • Ắc quy", v12)
            if (v12 < 12.0f) {
                tvBattery12v.setTextColor(Color.parseColor("#EF4444")) // Red alert
            } else {
                tvBattery12v.setTextColor(Color.parseColor("#10B981")) // Emerald normal
            }
        } else {
            tvBattery12v.text = "-- V • Ắc quy"
            tvBattery12v.setTextColor(Color.parseColor("#94A3B8"))
        }

        // TPMS
        tvTpmsFlKpa.text = if (tpms.flKpa != null) "${tpms.flKpa} kPa" else "-- kPa"
        tvTpmsFlTemp.text = if (tpms.flTemp != null) "${tpms.flTemp}°C" else "--°C"
        tvTpmsFrKpa.text = if (tpms.frKpa != null) "${tpms.frKpa} kPa" else "-- kPa"
        tvTpmsFrTemp.text = if (tpms.frTemp != null) "${tpms.frTemp}°C" else "--°C"
        tvTpmsRlKpa.text = if (tpms.rlKpa != null) "${tpms.rlKpa} kPa" else "-- kPa"
        tvTpmsRlTemp.text = if (tpms.rlTemp != null) "${tpms.rlTemp}°C" else "--°C"
        tvTpmsRrKpa.text = if (tpms.rrKpa != null) "${tpms.rrKpa} kPa" else "-- kPa"
        tvTpmsRrTemp.text = if (tpms.rrTemp != null) "${tpms.rrTemp}°C" else "--°C"

        setTpmsVisible(tpms.flKpa != null || tpms.frKpa != null || tpms.rlKpa != null || tpms.rrKpa != null)
        if (tpms.isValid) {
            val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
            tvTpmsUpdated.text = "Cập nhật ${sdf.format(Date())}"
        } else {
            tvTpmsUpdated.text = "Xe không cung cấp dữ liệu áp suất lốp"
        }

        // Window Positions (real VHAL readback only)
        updateWindowPositionUI(winPos)
    }

    private fun applyFallbackTelemetry() {
        tvSpeedValue.text = "--"
        updateGearUI(-1)
        tvBatterySoc.text = "--%"
        pbBatterySoc.progress = 0
        tvEstimatedRange.text = "-- km"
        tvOutsideTemp.text = "--°C"
        tvBattery12v.text = "-- V • Ắc quy"
        tvBattery12v.setTextColor(Color.parseColor("#94A3B8"))

        tvTpmsFlKpa.text = "-- kPa"
        tvTpmsFlTemp.text = "--°C"
        tvTpmsFrKpa.text = "-- kPa"
        tvTpmsFrTemp.text = "--°C"
        tvTpmsRlKpa.text = "-- kPa"
        tvTpmsRlTemp.text = "--°C"
        tvTpmsRrKpa.text = "-- kPa"
        tvTpmsRrTemp.text = "--°C"
        tvTpmsUpdated.text = "Chưa kết nối xe"
        setTpmsVisible(false)
        updateWindowPositionUI(arrayOfNulls(4))
        lastControls = ControlSnapshot()
        renderControls()
    }

    /** TPMS chips are hidden (not shown as permanent "--") when the car provides no tire data. */
    private fun setTpmsVisible(visible: Boolean) {
        val state = if (visible) View.VISIBLE else View.INVISIBLE // keep the car image centred
        listOf(tvTpmsFlKpa, tvTpmsFrKpa, tvTpmsRlKpa, tvTpmsRrKpa).forEach { (it.parent as? View)?.visibility = state }
    }

    /** Runs on [telemetryExecutor]. */
    private fun readControlSnapshot(): ControlSnapshot = ControlSnapshot(
        acOn = carHelper.isAcCompressorOn(),
        recircOn = carHelper.readRecirculation(),
        defrostFront = carHelper.readDefrostFront(),
        defrostRear = carHelper.readDefrostRear(),
        tempC = carHelper.readTargetTemperature()?.let { Math.round(it) },
        fan = carHelper.readFanSpeed(),
        vent = carHelper.readAirflowDirection(),
        drive = carHelper.readDriveMode(),
        regen = carHelper.readRegenMode(),
        avas = carHelper.getAvasMode()
    )

    /** Runs on [telemetryExecutor]. Registers VHAL push events once per connection. */
    private fun ensureWatch() {
        if (watchToken != null || !carHelper.isReady || isFinishing) return
        watchToken = carHelper.watch(WATCH_PROPS) {
            mainHandler.removeCallbacks(eventRefresh)
            mainHandler.postDelayed(eventRefresh, EVENT_REFRESH_DEBOUNCE_MS)
        }
    }

    /** Runs on [telemetryExecutor]. Real now-playing info; null title when unknown. */
    private fun readMediaInfo(): MediaInfo {
        var playing = try { audioManager.isMusicActive } catch (_: Throwable) { false }
        var title: String? = null
        try {
            // Needs MEDIA_CONTENT_CONTROL (granted to the system uid on the head unit).
            val msm = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val controllers = msm.getActiveSessions(null)
            val active = controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
                ?: controllers.firstOrNull()
            if (active != null) {
                val appLabel = try {
                    packageManager.getApplicationLabel(
                        packageManager.getApplicationInfo(active.packageName, 0)
                    ).toString()
                } catch (_: Throwable) {
                    active.packageName
                }
                val track = active.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
                title = if (!track.isNullOrBlank()) "$track • $appLabel" else appLabel
                playing = active.playbackState?.state == PlaybackState.STATE_PLAYING
            }
        } catch (_: Throwable) {
            // No permission (e.g. emulator, not system uid) — keep isMusicActive only.
        }
        return MediaInfo(title, playing)
    }

    private fun applyMediaInfo(info: MediaInfo) {
        tvMediaTitle.text = when {
            info.title != null -> "🎵 ${info.title}"
            info.isPlaying -> "🎵 Đang phát"
            else -> "🎵 Chưa có nội dung đang phát"
        }
        btnMediaPlayPause.setImageResource(if (info.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
    }

    // -------------------------------------------------------------
    // CONTROL STATE RENDERING (car state + in-flight commands)
    // -------------------------------------------------------------
    private fun rejectToast(label: String): (Any) -> Unit = {
        Toast.makeText(this, "Xe không nhận lệnh: $label", Toast.LENGTH_SHORT).show()
    }

    private fun renderControls() {
        val c = lastControls
        shown = ControlSnapshot(
            acOn = pending.resolve(K_AC, c.acOn, rejectToast("A/C")),
            recircOn = pending.resolve(K_RECIRC, c.recircOn, rejectToast("Gió trong")),
            defrostFront = pending.resolve(K_DEFROST_FRONT, c.defrostFront, rejectToast("Sấy kính trước")),
            defrostRear = pending.resolve(K_DEFROST_REAR, c.defrostRear, rejectToast("Sấy kính sau")),
            tempC = pending.resolve(K_TEMP, c.tempC, rejectToast("Nhiệt độ")),
            fan = pending.resolve(K_FAN, c.fan, rejectToast("Quạt gió")),
            vent = pending.resolve(K_VENT, c.vent, rejectToast("Hướng gió")),
            drive = pending.resolve(K_DRIVE, c.drive, rejectToast("Chế độ lái")),
            regen = pending.resolve(K_REGEN, c.regen, rejectToast("Phanh tái sinh")),
            avas = pending.resolve(K_AVAS, c.avas, rejectToast("AVAS"))
        )
        val s = shown

        renderToggle(btnAcCompressor, s.acOn)
        renderToggle(btnRecirc, s.recircOn)
        renderCompound(btnDefrostFront, s.defrostFront)
        renderCompound(btnDefrostRear, s.defrostRear)

        tvClimateTargetTemp.text = s.tempC?.let { "$it°C" } ?: "--°C"
        if (!isTrackingTempSlider && s.tempC != null) {
            sbTempGradient.progress = (s.tempC - TEMP_MIN).coerceIn(0, TEMP_MAX - TEMP_MIN)
        }
        tvClimateFanSpeed.text = when (val f = s.fan) {
            null -> "Mức --"
            0 -> "Tắt"
            else -> "Mức $f"
        }
        if (!isTrackingFanSlider && s.fan != null) sbFanSpeed.progress = s.fan.coerceIn(0, FAN_MAX)

        // Airflow bitmask: 1=FACE, 2=FLOOR, 3=FACE|FLOOR, 4=DEFROST, 6=DEFROST|FLOOR
        val vent = s.vent
        renderCompound(btnVentFace, vent?.let { it and 1 != 0 })
        renderCompound(btnVentFloor, vent?.let { it and 2 != 0 })

        renderChoice(btnDriveEco, s.drive, CarApiHelper.DRIVE_ECO)
        renderChoice(btnDriveComfort, s.drive, CarApiHelper.DRIVE_COMFORT)
        renderChoice(btnDriveSport, s.drive, CarApiHelper.DRIVE_SPORT)
        renderChoice(btnRegenLow, s.regen, CarApiHelper.REGEN_LOW)
        renderChoice(btnRegenMid, s.regen, CarApiHelper.REGEN_MID)
        renderChoice(btnRegenHigh, s.regen, CarApiHelper.REGEN_HIGH)

        val muted = s.avas?.let { it == 0 }
        btnAvasToggle.setBackgroundResource(if (muted == true) R.drawable.bg_btn_active else R.drawable.bg_btn_inactive)
        btnAvasToggle.alpha = if (muted == null) 0.6f else 1f
        tvAvasLabel.setTextColor(if (muted == true) Color.WHITE else Color.parseColor("#94A3B8"))
        tvAvasLabel.text = when (muted) {
            null -> "AVAS: --"
            true -> "AVAS: Đã tắt âm"
            false -> "AVAS: Đang bật"
        }

        // "Sending" state: a control whose command is still awaiting the car's confirmation
        // is shown slightly dimmed, so the driver can tell requested from confirmed.
        fun sending(view: View, key: String) {
            if (pending.isPending(key)) view.alpha = 0.7f
        }
        sending(btnAcCompressor, K_AC)
        sending(btnRecirc, K_RECIRC)
        sending(btnDefrostFront, K_DEFROST_FRONT)
        sending(btnDefrostRear, K_DEFROST_REAR)
        sending(btnVentFace, K_VENT)
        sending(btnVentFloor, K_VENT)
        sending(btnAvasToggle, K_AVAS)
        listOf(btnDriveEco, btnDriveComfort, btnDriveSport).forEach { sending(it, K_DRIVE) }
        listOf(btnRegenLow, btnRegenMid, btnRegenHigh).forEach { sending(it, K_REGEN) }
        tvClimateTargetTemp.alpha = if (pending.isPending(K_TEMP)) 0.7f else 1f
        tvClimateFanSpeed.alpha = if (pending.isPending(K_FAN)) 0.7f else 1f
    }

    /** null = unknown → neutral + dimmed, so the driver never sees a guessed state. */
    private fun renderToggle(view: TextView, active: Boolean?) {
        view.setBackgroundResource(if (active == true) R.drawable.bg_btn_active else R.drawable.bg_btn_inactive)
        view.setTextColor(if (active == true) Color.WHITE else Color.parseColor("#94A3B8"))
        view.alpha = if (active == null) 0.6f else 1f
    }

    private fun renderCompound(view: LinearLayout, active: Boolean?) {
        updateCompoundButtonStyle(view, active == true)
        view.alpha = if (active == null) 0.6f else 1f
    }

    private fun renderChoice(view: TextView, current: Int?, value: Int) {
        renderToggle(view, current?.let { it == value })
        if (current != null) view.alpha = 1f
    }

    /**
     * Shows [wanted] immediately (tracked by [pending] so polls don't flicker it back),
     * writes on [climateExecutor], and reconciles with the car shortly after.
     */
    private fun commandControl(key: String, wanted: Any, label: String, write: () -> Boolean) {
        pending.mark(key, wanted)
        renderControls()
        climateExecutor.execute {
            val accepted = try { write() } catch (_: Throwable) { false }
            if (!accepted) {
                runOnUiThread {
                    pending.clear(key)
                    renderControls()
                    rejectToast(label)(wanted)
                }
            }
            scheduleTelemetryRefresh(POST_WRITE_REFRESH_MS)
        }
    }

    private fun updateGearUI(gear: Int) {
        val activeBg = R.drawable.bg_gear_active
        val inactiveBg = Color.TRANSPARENT

        tvGearP.setBackgroundResource(if (gear == CarApiHelper.GEAR_PARK) activeBg else inactiveBg)
        tvGearP.setTextColor(if (gear == CarApiHelper.GEAR_PARK) Color.WHITE else Color.parseColor("#94A3B8"))

        tvGearR.setBackgroundResource(if (gear == CarApiHelper.GEAR_REVERSE) activeBg else inactiveBg)
        tvGearR.setTextColor(if (gear == CarApiHelper.GEAR_REVERSE) Color.WHITE else Color.parseColor("#94A3B8"))

        tvGearN.setBackgroundResource(if (gear == CarApiHelper.GEAR_NEUTRAL) activeBg else inactiveBg)
        tvGearN.setTextColor(if (gear == CarApiHelper.GEAR_NEUTRAL) Color.WHITE else Color.parseColor("#94A3B8"))

        tvGearD.setBackgroundResource(if (gear == CarApiHelper.GEAR_DRIVE) activeBg else inactiveBg)
        tvGearD.setTextColor(if (gear == CarApiHelper.GEAR_DRIVE) Color.WHITE else Color.parseColor("#94A3B8"))
    }

    private fun updateAutomationPillUI() {
        val isGuardHalted = prefs.getBoolean(CarAutomationService.KEY_GUARD_HALTED, false)
        val isValet = prefs.getBoolean(CarAutomationService.KEY_VALET_MODE, false)

        if (isGuardHalted) {
            layoutAutomationPill.setBackgroundResource(R.drawable.bg_btn_active_amber)
            tvAutomationStatus.text = "⚠️ Crash Guard: Đã ngắt (Bấm để reset)"
            tvAutomationStatus.setTextColor(Color.WHITE)
            ivAutomationIcon.setColorFilter(Color.WHITE)
            btnValetMode.setBackgroundResource(R.drawable.bg_btn_inactive)
            tvValetLabel.setTextColor(Color.parseColor("#94A3B8"))
        } else if (isValet) {
            layoutAutomationPill.setBackgroundResource(R.drawable.bg_pill_valet)
            tvAutomationStatus.text = "Chế độ Bảo dưỡng: Đang bật"
            tvAutomationStatus.setTextColor(Color.parseColor("#FBBF24"))
            ivAutomationIcon.setColorFilter(Color.parseColor("#FBBF24"))
            btnValetMode.setBackgroundResource(R.drawable.bg_btn_active_amber)
            tvValetLabel.setTextColor(Color.WHITE)
        } else {
            layoutAutomationPill.setBackgroundResource(R.drawable.bg_pill_automation)
            tvAutomationStatus.text = "Tự động hóa: Đang bật"
            tvAutomationStatus.setTextColor(Color.parseColor("#34D399"))
            ivAutomationIcon.setColorFilter(Color.parseColor("#34D399"))
            btnValetMode.setBackgroundResource(R.drawable.bg_btn_inactive)
            tvValetLabel.setTextColor(Color.parseColor("#94A3B8"))
        }
    }

    private fun updateWindowPositionUI(pos: Array<Int?>) {
        val views = arrayOf(tvWindowFl, tvWindowFr, tvWindowRl, tvWindowRr)
        for (i in views.indices) {
            val percent = pos.getOrNull(i)
            when {
                percent == null -> {
                    views[i].text = "Kính: --"
                    views[i].setTextColor(Color.parseColor("#64748B"))
                }
                percent == 0 -> {
                    views[i].text = "Kính: 0%"
                    views[i].setTextColor(Color.parseColor("#10B981"))
                }
                else -> {
                    views[i].text = "Kính: $percent%"
                    views[i].setTextColor(Color.parseColor("#F59E0B"))
                }
            }
        }
    }

    /** Re-reads telemetry shortly after a write so the UI shows the car's real state. */
    private fun scheduleTelemetryRefresh(delayMs: Long = 1500L) {
        try {
            telemetryExecutor.schedule({ pollTelemetryBackground() }, delayMs, TimeUnit.MILLISECONDS)
        } catch (_: Throwable) {}
    }

    private fun setupListeners() {
        // ---------------------------------------------------------
        // Top Header Actions
        // ---------------------------------------------------------
        btnOpenCatalogHeader.setOnClickListener {
            startActivity(Intent(this, AppCatalogActivity::class.java))
        }

        btnNightBlackout.setOnClickListener {
            // Blackout is owned by FloatingControlService (one overlay owner, survives this
            // Activity, and auto-dismisses when the car shifts into R).
            startService(Intent(this, FloatingControlService::class.java).apply {
                action = FloatingControlService.ACTION_TRIGGER_BLACKOUT
            })
        }

        btnSettings.setOnClickListener {
            showSettingsDialog()
        }

        layoutAutomationPill.setOnClickListener {
            val isGuardHalted = prefs.getBoolean(CarAutomationService.KEY_GUARD_HALTED, false)
            if (isGuardHalted) {
                showCrashGuardResetDialog()
            } else {
                toggleValetMode()
            }
        }

        // ---------------------------------------------------------
        // Column 1: Driver Zone Actions
        // ---------------------------------------------------------
        btnDriveEco.setOnClickListener { selectDriveMode(CarApiHelper.DRIVE_ECO) }
        btnDriveComfort.setOnClickListener { selectDriveMode(CarApiHelper.DRIVE_COMFORT) }
        btnDriveSport.setOnClickListener { selectDriveMode(CarApiHelper.DRIVE_SPORT) }

        btnRegenLow.setOnClickListener { selectRegenMode(CarApiHelper.REGEN_LOW) }
        btnRegenMid.setOnClickListener { selectRegenMode(CarApiHelper.REGEN_MID) }
        btnRegenHigh.setOnClickListener { selectRegenMode(CarApiHelper.REGEN_HIGH) }

        btnAvasToggle.setOnClickListener {
            // The button expresses the wanted AVAS state; the automation service then holds it.
            val mute = shown.avas != 0
            prefs.edit().putBoolean(CarAutomationService.KEY_AUTO_AVAS, mute).apply()
            commandControl(K_AVAS, if (mute) 0 else 1, "AVAS") { carHelper.setAvasMode(if (mute) 0 else 1) }
        }

        btnCamera360.setOnClickListener {
            val pkg360 = "com.flyme.auto.camera"
            val launchIntent = packageManager.getLaunchIntentForPackage(pkg360)
            if (launchIntent != null) {
                startActivity(launchIntent)
            } else {
                Toast.makeText(this, "Không tìm thấy ứng dụng Camera 360 ($pkg360) trên xe.", Toast.LENGTH_SHORT).show()
            }
        }

        btnValetMode.setOnClickListener {
            toggleValetMode()
        }

        // ---------------------------------------------------------
        // Column 2: Multitasking & Climate Actions
        // ---------------------------------------------------------
        btnSplitScreenHero.setOnClickListener {
            SplitScreenLauncher.launchSplitScreen(this)
        }

        // Media Controls (icon/title follow the real playback state on the next refresh)
        btnMediaPlayPause.setOnClickListener {
            dispatchMediaKeyEvent(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            scheduleTelemetryRefresh(600L)
        }
        btnMediaPrev.setOnClickListener {
            dispatchMediaKeyEvent(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            scheduleTelemetryRefresh(800L)
        }
        btnMediaNext.setOnClickListener {
            dispatchMediaKeyEvent(KeyEvent.KEYCODE_MEDIA_NEXT)
            scheduleTelemetryRefresh(800L)
        }

        // Climate Controls — every button starts from the car's real state.
        btnAcCompressor.setOnClickListener {
            val target = shown.acOn != true
            commandControl(K_AC, target, "A/C") { carHelper.setAcCompressorOn(target) }
        }

        btnRecirc.setOnClickListener {
            val target = shown.recircOn != true
            commandControl(K_RECIRC, target, "Gió trong") { carHelper.setRecirculation(target) }
        }

        btnQuickCool.setOnClickListener {
            Toast.makeText(this, "⚡ Đang kích hoạt Làm mát nhanh (A/C, 17°C, Gió 7)...", Toast.LENGTH_SHORT).show()
            pending.mark(K_AC, true)
            pending.mark(K_TEMP, TEMP_MIN)
            pending.mark(K_FAN, 7)
            pending.mark(K_VENT, 1)
            pending.mark(K_RECIRC, true)
            renderControls()
            carHelper.triggerQuickCool {
                scheduleTelemetryRefresh(POST_WRITE_REFRESH_MS)
            }
        }

        btnTempMinus.setOnClickListener { nudgeTemperature(-1) }
        btnTempPlus.setOnClickListener { nudgeTemperature(+1) }
        sbTempGradient.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val target = TEMP_MIN + progress
                tvClimateTargetTemp.text = "$target°C"
                tempDebounceRunnable?.let { mainHandler.removeCallbacks(it) }
                val runnable = Runnable { setTemperature(target) }
                tempDebounceRunnable = runnable
                mainHandler.postDelayed(runnable, SLIDER_WRITE_DEBOUNCE_MS)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) { isTrackingTempSlider = true }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isTrackingTempSlider = false
                tempDebounceRunnable?.let { mainHandler.removeCallbacks(it) }
                setTemperature(TEMP_MIN + (seekBar?.progress ?: 0))
            }
        })

        btnFanMinus.setOnClickListener { nudgeFan(-1) }
        btnFanPlus.setOnClickListener { nudgeFan(+1) }
        sbFanSpeed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val target = progress.coerceIn(FAN_MIN, FAN_MAX)
                tvClimateFanSpeed.text = "Mức $target"
                fanDebounceRunnable?.let { mainHandler.removeCallbacks(it) }
                val runnable = Runnable { setFan(target) }
                fanDebounceRunnable = runnable
                mainHandler.postDelayed(runnable, SLIDER_WRITE_DEBOUNCE_MS)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) { isTrackingFanSlider = true }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isTrackingFanSlider = false
                fanDebounceRunnable?.let { mainHandler.removeCallbacks(it) }
                setFan((seekBar?.progress ?: FAN_MIN).coerceIn(FAN_MIN, FAN_MAX))
            }
        })

        // Vents
        btnVentFace.setOnClickListener { selectVent(1) }
        btnVentFloor.setOnClickListener { selectVent(2) }

        btnDefrostFront.setOnClickListener {
            val target = shown.defrostFront != true
            commandControl(K_DEFROST_FRONT, target, "Sấy kính trước") { carHelper.setDefrostFront(target) }
        }

        btnDefrostRear.setOnClickListener {
            val target = shown.defrostRear != true
            commandControl(K_DEFROST_REAR, target, "Sấy kính sau") { carHelper.setDefrostRear(target) }
        }

        // ---------------------------------------------------------
        // Column 3: TPMS & Windows Actions
        // ---------------------------------------------------------
        btnWindowsClose.setOnClickListener {
            sendWindowCommand(0, "Đã gửi lệnh đóng kín 4 kính")
        }

        btnWindowsVent.setOnClickListener {
            sendWindowCommand(15, "Đã gửi lệnh hé 4 kính (15%) để thoát nhiệt")
        }

        // Lowering every pane fully is only allowed at low speed (mis-tap on the highway).
        btnWindowsOpen.setOnClickListener {
            ParkGate.runIfAllowed(this, check = { ctx -> ParkGate.checkSlowerThan(ctx, WINDOW_OPEN_MAX_KMH) }) {
                sendWindowCommand(100, "Đã gửi lệnh hạ hết 4 kính")
            }
        }

        btnSpeedLimitSign.setOnClickListener {
            val vietmapIntent = packageManager.getLaunchIntentForPackage("vn.vietmap.live")
            if (vietmapIntent != null) {
                startActivity(vietmapIntent)
            } else {
                Toast.makeText(this, "Mở Vietmap Live từ Kho ứng dụng.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun sendWindowCommand(percent: Int, sentMessage: String) {
        climateExecutor.execute {
            val ok = carHelper.setAllWindowsPosition(percent)
            runOnUiThread {
                Toast.makeText(
                    this,
                    if (ok) sentMessage else "Xe không nhận lệnh kính.",
                    Toast.LENGTH_SHORT
                ).show()
            }
            // UI is never updated optimistically: show what the glass really reports.
            scheduleTelemetryRefresh(1500L)
            scheduleTelemetryRefresh(5000L)
        }
    }

    private fun showCrashGuardResetDialog() {
        AlertDialog.Builder(this)
            .setTitle("Cảnh báo Crash Guard")
            .setMessage("Hệ thống tự động hóa đã tự động ngắt bảo vệ do phát hiện lỗi liên tiếp để tránh treo hệ thống.\n\nBạn có muốn đặt lại (Reset) để tiếp tục chạy tự động hóa?")
            .setPositiveButton("Đặt lại (Reset)") { _, _ ->
                val resetIntent = Intent(this, CarAutomationService::class.java).apply {
                    action = CarAutomationService.ACTION_RESET_GUARD
                }
                startService(resetIntent)
                prefs.edit().putBoolean(CarAutomationService.KEY_GUARD_HALTED, false).apply()
                updateAutomationPillUI()
                Toast.makeText(this, "Đã đặt lại Crash Guard. Tự động hóa tiếp tục hoạt động.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun toggleValetMode() {
        val current = prefs.getBoolean(CarAutomationService.KEY_VALET_MODE, false)
        val nextState = !current
        prefs.edit().putBoolean(CarAutomationService.KEY_VALET_MODE, nextState).apply()
        updateAutomationPillUI()

        if (nextState) {
            Toast.makeText(this, "Chế độ Bảo dưỡng: ĐÃ BẬT. Tạm dừng mọi tự động hóa VHAL.", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "Chế độ Bảo dưỡng: ĐÃ TẮT. Tự động hóa đã hoạt động trở lại.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun nudgeTemperature(delta: Int) {
        val current = shown.tempC
        if (current == null) {
            Toast.makeText(this, "Chưa đọc được nhiệt độ cài đặt từ xe.", Toast.LENGTH_SHORT).show()
            return
        }
        setTemperature((current + delta).coerceIn(TEMP_MIN, TEMP_MAX))
    }

    private fun setTemperature(target: Int) {
        if (target == shown.tempC && !pending.isPending(K_TEMP)) return
        commandControl(K_TEMP, target, "Nhiệt độ") { carHelper.writeTargetTemperature(target.toFloat()) }
    }

    private fun nudgeFan(delta: Int) {
        val current = shown.fan
        if (current == null) {
            Toast.makeText(this, "Chưa đọc được mức quạt từ xe.", Toast.LENGTH_SHORT).show()
            return
        }
        setFan((current + delta).coerceIn(FAN_MIN, FAN_MAX))
    }

    private fun setFan(target: Int) {
        if (target == shown.fan && !pending.isPending(K_FAN)) return
        commandControl(K_FAN, target, "Quạt gió") { carHelper.writeFanSpeed(target) }
    }

    private fun selectVent(mask: Int) {
        commandControl(K_VENT, mask, "Hướng gió") { carHelper.setAirflowDirection(mask) }
    }

    private fun updateCompoundButtonStyle(button: LinearLayout, isActive: Boolean) {
        button.setBackgroundResource(if (isActive) R.drawable.bg_btn_active else R.drawable.bg_btn_inactive)
        for (i in 0 until button.childCount) {
            val child = button.getChildAt(i)
            if (child is TextView) {
                child.setTextColor(if (isActive) Color.WHITE else Color.parseColor("#94A3B8"))
            }
        }
    }

    private fun selectDriveMode(mode: Int) {
        prefs.edit().putInt(CarAutomationService.KEY_PREFERRED_DRIVE_MODE, mode).apply()
        commandControl(K_DRIVE, mode, "Chế độ lái") { carHelper.writeDriveMode(mode) }
    }

    private fun selectRegenMode(mode: Int) {
        prefs.edit().putInt(CarAutomationService.KEY_PREFERRED_REGEN_MODE, mode).apply()
        commandControl(K_REGEN, mode, "Phanh tái sinh") { carHelper.writeRegenMode(mode) }
    }

    private fun dispatchMediaKeyEvent(keyCode: Int) {
        try {
            val eventDown = KeyEvent(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(), KeyEvent.ACTION_DOWN, keyCode, 0)
            val eventUp = KeyEvent(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, keyCode, 0)
            audioManager.dispatchMediaKeyEvent(eventDown)
            audioManager.dispatchMediaKeyEvent(eventUp)
        } catch (_: Throwable) {
            val intent = Intent(Intent.ACTION_MEDIA_BUTTON)
            intent.putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            sendOrderedBroadcast(intent, null)
        }
    }

    private fun showSettingsDialog() {
        val isValet = prefs.getBoolean(CarAutomationService.KEY_VALET_MODE, false)
        val options = arrayOf(
            "Tự động tắt âm AVAS khi vào số D: ${if (prefs.getBoolean(CarAutomationService.KEY_AUTO_AVAS, true)) "BẬT" else "TẮT"}",
            "Chế độ Bảo dưỡng / Rửa xe (Valet): ${if (isValet) "ĐANG BẬT" else "TẮT"}",
            "Mở Kho ứng dụng Wi-Fi",
            "Bật/Tắt Nút nổi điều khiển nhanh (Overlay)"
        )

        AlertDialog.Builder(this)
            .setTitle("Cài đặt hệ thống EX2 Center")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        val current = prefs.getBoolean(CarAutomationService.KEY_AUTO_AVAS, true)
                        prefs.edit().putBoolean(CarAutomationService.KEY_AUTO_AVAS, !current).apply()
                        Toast.makeText(this, "Đã lưu cài đặt tự động AVAS.", Toast.LENGTH_SHORT).show()
                    }
                    1 -> {
                        toggleValetMode()
                    }
                    2 -> {
                        startActivity(Intent(this, AppCatalogActivity::class.java))
                    }
                    3 -> {
                        val current = prefs.getBoolean("key_floating_bubble_enabled", true)
                        prefs.edit().putBoolean("key_floating_bubble_enabled", !current).apply()
                        if (!current) {
                            val overlayIntent = Intent(this, FloatingControlService::class.java)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                startForegroundService(overlayIntent)
                            } else {
                                startService(overlayIntent)
                            }
                        } else {
                            stopService(Intent(this, FloatingControlService::class.java))
                        }
                    }
                }
            }
            .setNegativeButton("Đóng", null)
            .show()
    }

    private fun startCoreServices() {
        val autoServiceIntent = Intent(this, CarAutomationService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(autoServiceIntent)
        } else {
            startService(autoServiceIntent)
        }
    }
}
