package com.mipago.ex2center.utils

import android.car.Car
import android.car.hardware.property.CarPropertyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.BatteryManager
import android.os.IBinder
import android.os.SystemClock
import android.util.Log

/**
 * Helper class for interacting with Geely EX2 VHAL (Vehicle Hardware Abstraction Layer)
 * and hidden CarAudioManager via reflection.
 */
class CarApiHelper private constructor() {

    companion object {
        const val TAG = "CarApiHelper"
        private const val CONNECT_TIMEOUT_MS = 10_000L

        // Powertrain & Driving properties
        const val PROP_GEAR = 289408001
        const val GEAR_NEUTRAL = 1
        const val GEAR_REVERSE = 2
        const val GEAR_PARK = 4
        const val GEAR_DRIVE = 8

        const val PROP_SPEED = 291504647 // PERF_VEHICLE_SPEED, km/h, float
        const val PROP_SOC = 557885165 // High-voltage battery SoC (%)
        const val PROP_ESTIMATED_RANGE = 289407752 // Estimated remaining range (km)

        // Speed limit candidates, READ-ONLY, in fallback order (taken from the dashcam overlay in
        // reference/drive_assist/modehelper/.../DashRecorder.java). NOT in field-catalog.md yet:
        // unverified, so only plausible values are shown and the sign falls back to "--".
        const val PROP_SPEED_LIMIT_CAMERA = 0x2140B029 // 557887529 — ADAS camera sign recognition
        const val PROP_SPEED_LIMIT_NAVI = 0x2140303A   // 557854778 — built-in navigation limit
        const val PROP_SPEED_LIMIT_NAVSPEED = 0x2140A405 // 557884421 — navigation speed value
        private const val SPEED_LIMIT_MIN = 5
        private const val SPEED_LIMIT_MAX = 200

        const val PROP_DRIVE = 570491136
        val DRIVE_AREAS = intArrayOf(0, 1, 16777216)
        const val DRIVE_ECO = 570491137
        const val DRIVE_COMFORT = 570491138
        const val DRIVE_SPORT = 570491139

        const val PROP_REGEN = 537003264
        val REGEN_AREAS = intArrayOf(0, 1)
        const val REGEN_LOW = 537003265
        const val REGEN_MID = 537003266
        const val REGEN_HIGH = 537003267

        // Climate & HVAC properties
        const val PROP_HVAC_POWER = 354419984
        const val PROP_HVAC_AC_ON = 354419973
        const val PROP_HVAC_TEMP_SETPOINT = 0x15600503 // 358614275
        const val PROP_HVAC_FAN_SPEED = 356517120
        const val PROP_HVAC_FAN_SPEED_ALIAS = 557846559
        const val PROP_HVAC_AMBIENT_TEMP = 557884279
        const val PROP_HVAC_AIR_DIRECTION = 557846560 // 1=FACE, 2=FLOOR, 3=FACE|FLOOR, 4=DEFROST
        const val PROP_HVAC_RECIRC = 354419976
        const val PROP_HVAC_DEFROST_FRONT = 354419975
        const val PROP_HVAC_DEFROST_REAR = 354419988
        // NOTE: HVAC_AUTO_ON (354419978) is a NOOP on IHU629G (accepts writes, changes nothing —
        // reference/drive_assist/docs/DATA-CATALOG.md). Intentionally not exposed.

        val HVAC_AREAS = intArrayOf(75, 0, 1)
        val HVAC_TEMP_AREAS = intArrayOf(31, 44, 1, 4, 0)

        // Windows — verified on the car (reference/drive_assist/docs/field-catalog.md §8):
        // WINDOW_POS 0x13400BC0, int, 0 = fully closed, 100 = fully open.
        const val PROP_WINDOW_POS = 322964416
        val WINDOW_AREAS = intArrayOf(16, 64, 256, 1024) // FL, FR, RL, RR

        // Trunk/tailgate: no verified property yet. DOOR_POS (373295872) is READ-ONLY and only
        // covers areas 1/4/16/64, so it cannot open the trunk. Feature removed until probed.

        // ADAS & Safety — only AEB is verified (field-catalog.md §6).
        // HDC / ESC / Auto Hold IDs were guesses sitting inside the real ADAS block
        // (between AEB 557858874 and FCW 557858878) and were removed: writing a boolean to an
        // unknown ADAS property is unsafe. Re-add only after a directed snapshot diff on the car.
        const val PROP_AEB = 557858874 // Autonomous Emergency Braking (boolean)

        // Cabin ambient lighting master switch — verified (field-catalog.md §7): boolean, area 0.
        const val PROP_AMBIENT_LIGHT_ON = 557885013

        // Front trunk (frunk): NO verified property in reference/ yet — intentionally absent.

        // TPMS — AOSP TIRE_PRESSURE 0x17600309 (WHEEL | FLOAT, kPa). Not yet confirmed on EX2;
        // the previous id 0x15400309 had SEAT|INT32 type bits and could never be read as float.
        const val PROP_TIRE_PRESSURE = 392168201
        val TIRE_AREAS = intArrayOf(1, 2, 4, 8) // FL, FR, RL, RR (VehicleAreaWheel)

        /** True when the platform ships android.car.Car (a real head unit / AAOS image). */
        val isCarApiPresent: Boolean by lazy {
            try {
                Class.forName("android.car.Car")
                true
            } catch (_: Throwable) {
                false
            }
        }

        @Volatile
        private var instance: CarApiHelper? = null

        fun getInstance(): CarApiHelper {
            return instance ?: synchronized(this) {
                instance ?: CarApiHelper().also { instance = it }
            }
        }
    }

    data class TpmsData(
        val flKpa: Int? = null, val flTemp: Int? = null,
        val frKpa: Int? = null, val frTemp: Int? = null,
        val rlKpa: Int? = null, val rlTemp: Int? = null,
        val rrKpa: Int? = null, val rrTemp: Int? = null
    ) {
        val isValid: Boolean
            get() = flKpa != null && frKpa != null && rlKpa != null && rrKpa != null
    }

    // -------------------------------------------------------------
    // SHARED CONNECTION LIFECYCLE
    // -------------------------------------------------------------
    // One Car connection per process, shared by Dashboard, CarAutomationService and the
    // overlay. Components register as holders with acquire()/release(); the connection is
    // only closed when the LAST holder releases, so closing the Dashboard never cuts the
    // background service off from VHAL.
    @Volatile private var car: Car? = null
    @Volatile private var cpm: CarPropertyManager? = null
    private var isConnecting = false
    private var connectStartedAt = 0L
    private var generation = 0 // bumps on every new/closed connection; stale callbacks are ignored
    private val pendingCallbacks = mutableListOf<(Boolean) -> Unit>()
    private val holders = HashSet<String>()
    private val lock = Any()

    val isReady: Boolean
        get() {
            val c = car ?: return false
            if (cpm == null) return false
            return try { c.isConnected } catch (_: Throwable) { false }
        }

    /** Registers [owner] as a user of the shared connection and connects if needed. */
    fun acquire(context: Context, owner: String, onReady: (Boolean) -> Unit = {}) {
        synchronized(lock) { holders.add(owner) }
        connect(context, onReady)
    }

    /** Unregisters [owner]; the connection is closed only when no holder remains. */
    fun release(owner: String) {
        val last: Boolean
        synchronized(lock) {
            holders.remove(owner)
            last = holders.isEmpty()
        }
        if (last) {
            Log.i(TAG, "Last holder ($owner) released — closing Car connection.")
            closeConnection()
        } else {
            Log.d(TAG, "Holder $owner released; still in use by ${synchronized(lock) { holders.toList() }}")
        }
    }

    /**
     * Ensures a live connection. Safe to call repeatedly: concurrent calls share one attempt,
     * a hung attempt is abandoned after [CONNECT_TIMEOUT_MS], and a dead Car (binder died while
     * the head unit suspended) is released before a new one is created.
     * [onReady] runs on the main thread (ServiceConnection) or the calling thread.
     */
    fun connect(context: Context, onReady: (Boolean) -> Unit = {}) {
        if (!isCarApiPresent) {
            onReady(false)
            return
        }
        val appContext = context.applicationContext
        val previous: Car?
        val gen: Int
        synchronized(lock) {
            if (isReady) {
                previous = null
                gen = -1
            } else {
                pendingCallbacks.add(onReady)
                val stale = isConnecting &&
                    SystemClock.elapsedRealtime() - connectStartedAt > CONNECT_TIMEOUT_MS
                if (isConnecting && !stale) return
                if (stale) Log.w(TAG, "Previous Car connect attempt timed out — retrying.")
                isConnecting = true
                connectStartedAt = SystemClock.elapsedRealtime()
                generation++
                gen = generation
                previous = car
                car = null
                cpm = null
                supportedPropertyIds = null
            }
        }
        if (gen == -1) {
            onReady(true)
            return
        }
        try { previous?.disconnect() } catch (_: Throwable) {}

        try {
            val serviceConn = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    val mgr = try {
                        synchronized(lock) { car }?.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
                    } catch (t: Throwable) {
                        Log.e(TAG, "Error getting CarPropertyManager: ${t.message}", t)
                        null
                    }
                    synchronized(lock) {
                        if (gen != generation) return // superseded attempt
                        cpm = mgr
                    }
                    Log.i(TAG, "Car service connected (ready=${mgr != null}).")
                    finishConnect(gen, mgr != null)
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    synchronized(lock) {
                        if (gen != generation) return
                        // Unit suspended / binder died: invalidate so isReady() stops lying.
                        cpm = null
                        supportedPropertyIds = null
                    }
                    Log.w(TAG, "Car service disconnected.")
                    finishConnect(gen, false)
                }
            }
            val created = Car.createCar(appContext, serviceConn)
            synchronized(lock) {
                if (gen != generation) {
                    try { created.disconnect() } catch (_: Throwable) {}
                    return
                }
                car = created
            }
            created.connect()
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to create or connect Car instance: ${t.message}", t)
            finishConnect(gen, false)
        }
    }

    /** Drops a connection that looks alive but no longer answers, then reconnects. */
    fun reconnect(context: Context, onReady: (Boolean) -> Unit = {}) {
        Log.w(TAG, "Forcing Car reconnect.")
        closeConnection()
        connect(context, onReady)
    }

    private fun finishConnect(gen: Int, ready: Boolean) {
        val callbacksToNotify: List<(Boolean) -> Unit>
        synchronized(lock) {
            if (gen != generation) return
            isConnecting = false
            callbacksToNotify = ArrayList(pendingCallbacks)
            pendingCallbacks.clear()
        }
        callbacksToNotify.forEach { cb ->
            try { cb(ready) } catch (t: Throwable) { Log.w(TAG, "connect callback error: ${t.message}") }
        }
    }

    private fun closeConnection() {
        val previous: Car?
        synchronized(lock) {
            generation++
            previous = car
            car = null
            cpm = null
            supportedPropertyIds = null
            isConnecting = false
            pendingCallbacks.clear()
        }
        try {
            if (previous?.isConnected == true) previous.disconnect()
        } catch (_: Throwable) {}
    }

    // -------------------------------------------------------------
    // POWERTRAIN & TELEMETRY
    // -------------------------------------------------------------
    fun readGear(): Int? {
        val mgr = cpm ?: return null
        for (area in intArrayOf(0, 1, 16777216)) {
            try {
                return mgr.getIntProperty(PROP_GEAR, area)
            } catch (ignored: Throwable) {}
        }
        return null
    }

    fun readSpeed(): Float? {
        val mgr = cpm ?: return null
        for (area in intArrayOf(0, 1)) {
            try {
                return mgr.getFloatProperty(PROP_SPEED, area)
            } catch (ignored: Throwable) {}
        }
        return null
    }

    data class SpeedLimit(val kmh: Int, val source: String)

    /**
     * Current speed limit: camera sign first, then navigation limit, then navigation speed.
     * A real limit is a small round number; anything outside 5..200 km/h is treated as an enum,
     * sentinel or noise and skipped. Returns null when no source has a plausible value.
     */
    fun readSpeedLimit(): SpeedLimit? {
        val mgr = cpm ?: return null
        val sources = arrayOf(
            PROP_SPEED_LIMIT_CAMERA to "camera",
            PROP_SPEED_LIMIT_NAVI to "navi",
            PROP_SPEED_LIMIT_NAVSPEED to "navspeed"
        )
        for ((prop, name) in sources) {
            val v = try { mgr.getIntProperty(prop, 0) } catch (_: Throwable) { null } ?: continue
            if (v in SPEED_LIMIT_MIN..SPEED_LIMIT_MAX) return SpeedLimit(v, name)
        }
        return null
    }

    fun readBatterySoc(): Float? {
        val mgr = cpm ?: return null
        for (area in intArrayOf(0, 1)) {
            try {
                val soc = mgr.getFloatProperty(PROP_SOC, area)
                if (soc in 0f..100f) return soc
            } catch (ignored: Throwable) {}
        }
        return null
    }

    fun readEstimatedRange(): Float? {
        val mgr = cpm ?: return null
        for (area in intArrayOf(0, 1)) {
            try {
                val range = mgr.getFloatProperty(PROP_ESTIMATED_RANGE, area)
                if (range >= 0f) return range
            } catch (ignored: Throwable) {}
        }
        return null
    }

    fun read12vBatteryVoltage(context: Context): Float? {
        // First try reading through Android BatteryManager / Intent
        try {
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val batteryStatus = context.registerReceiver(null, filter)
            val voltageMv = batteryStatus?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
            if (voltageMv > 1000) {
                return voltageMv / 1000.0f
            }
        } catch (ignored: Throwable) {}
        return null
    }

    fun readTpmsData(): TpmsData {
        val mgr = cpm ?: return TpmsData()
        fun wheel(area: Int): Int? = try {
            val kpa = mgr.getFloatProperty(PROP_TIRE_PRESSURE, area)
            if (kpa in 50f..500f) kpa.toInt() else null
        } catch (_: Throwable) {
            null
        }
        // Tire temperature has no verified property on EX2 — left null (UI shows "--").
        return TpmsData(
            flKpa = wheel(TIRE_AREAS[0]),
            frKpa = wheel(TIRE_AREAS[1]),
            rlKpa = wheel(TIRE_AREAS[2]),
            rrKpa = wheel(TIRE_AREAS[3])
        )
    }

    // -------------------------------------------------------------
    // PROPERTY SUPPORT LOOKUP
    // -------------------------------------------------------------
    @Volatile
    private var supportedPropertyIds: Set<Int>? = null

    fun isPropertySupported(propId: Int): Boolean {
        val mgr = cpm ?: return false
        val supported = supportedPropertyIds ?: try {
            val set = HashSet<Int>()
            val list = mgr.propertyList
            if (list != null) {
                for (config in list) {
                    set.add(config.propertyId)
                }
            }
            supportedPropertyIds = set
            set
        } catch (_: Throwable) {
            null
        }

        if (supported != null && supported.isNotEmpty()) {
            return supported.contains(propId)
        }

        return try {
            mgr.getProperty<Any>(propId, 0) != null
        } catch (_: Throwable) {
            false
        }
    }


    // -------------------------------------------------------------
    // DRIVE MODE (ECO, COMFORT, SPORT)
    // -------------------------------------------------------------
    fun readDriveMode(): Int? {
        val mgr = cpm ?: return null
        for (area in DRIVE_AREAS) {
            try {
                return mgr.getIntProperty(PROP_DRIVE, area)
            } catch (ignored: Throwable) {}
        }
        return null
    }

    fun writeDriveMode(mode: Int): Boolean {
        val mgr = cpm ?: return false
        var success = false
        for (area in DRIVE_AREAS) {
            try {
                mgr.setIntProperty(PROP_DRIVE, area, mode)
                success = true
            } catch (t: Throwable) {
                Log.w(TAG, "writeDriveMode area $area error: ${t.message}")
            }
        }
        return success
    }

    // -------------------------------------------------------------
    // REGENERATION MODE (LOW, MID, HIGH)
    // -------------------------------------------------------------
    fun readRegenMode(): Int? {
        val mgr = cpm ?: return null
        for (area in REGEN_AREAS) {
            try {
                return mgr.getIntProperty(PROP_REGEN, area)
            } catch (ignored: Throwable) {}
        }
        return null
    }

    fun writeRegenMode(mode: Int): Boolean {
        val mgr = cpm ?: return false
        var success = false
        for (area in REGEN_AREAS) {
            try {
                mgr.setIntProperty(PROP_REGEN, area, mode)
                success = true
            } catch (t: Throwable) {
                Log.w(TAG, "writeRegenMode area $area error: ${t.message}")
            }
        }
        return success
    }

    // -------------------------------------------------------------
    // AVAS (Pedestrian Warning Sound) via CarAudioManager reflection
    // -------------------------------------------------------------
    /** ok=false when the manager/method is missing or the call threw. */
    private class AudioCall(val ok: Boolean, val value: Any?)

    private fun invokeAudioMethod(methodName: String, vararg args: Any): AudioCall {
        val carInstance = car ?: return AudioCall(false, null)
        return try {
            val audioMgr = carInstance.getCarManager("audio") ?: return AudioCall(false, null)
            val paramTypes = args.map { arg ->
                when (arg) {
                    is Int -> Int::class.javaPrimitiveType ?: Int::class.java
                    is Boolean -> Boolean::class.javaPrimitiveType ?: Boolean::class.java
                    is Float -> Float::class.javaPrimitiveType ?: Float::class.java
                    else -> arg.javaClass
                }
            }.toTypedArray()

            val method = audioMgr.javaClass.getMethod(methodName, *paramTypes)
            AudioCall(true, method.invoke(audioMgr, *args))
        } catch (t: Throwable) {
            val cause = (t as? java.lang.reflect.InvocationTargetException)?.cause ?: t
            Log.e(TAG, "invokeAudioMethod $methodName failed: $cause")
            AudioCall(false, null)
        }
    }

    fun isAvasSupported(): Boolean {
        return (invokeAudioMethod("isAVASModeSupported").value as? Boolean) ?: false
    }

    /** 0 = muted, >=1 = active tune; null when unreadable. */
    fun getAvasMode(): Int? {
        return invokeAudioMethod("getAVASMode").value as? Int
    }

    /** Returns true only when the car reads back [mode] after the write. */
    fun setAvasMode(mode: Int): Boolean {
        if (!invokeAudioMethod("setAVASMode", mode).ok) return false
        val readBack = getAvasMode()
        val ok = readBack == mode
        if (ok) Log.i(TAG, "setAvasMode($mode) confirmed by readback.")
        else Log.w(TAG, "setAvasMode($mode) not applied (readback=$readBack).")
        return ok
    }

    // -------------------------------------------------------------
    // CLIMATE / HVAC CONTROLS
    // -------------------------------------------------------------
    fun isHvacPowerOn(): Boolean? {
        val mgr = cpm ?: return null
        for (area in HVAC_AREAS) {
            try {
                return mgr.getBooleanProperty(PROP_HVAC_POWER, area)
            } catch (ignored: Throwable) {}
        }
        return null
    }

    fun setHvacPowerOn(on: Boolean): Boolean {
        val mgr = cpm ?: return false
        var ok = false
        for (area in HVAC_AREAS) {
            try {
                mgr.setBooleanProperty(PROP_HVAC_POWER, area, on)
                ok = true
            } catch (ignored: Throwable) {}
        }
        return ok
    }

    fun isAcCompressorOn(): Boolean? {
        val mgr = cpm ?: return null
        for (area in HVAC_AREAS) {
            try {
                return mgr.getBooleanProperty(PROP_HVAC_AC_ON, area)
            } catch (ignored: Throwable) {}
        }
        return null
    }

    fun setAcCompressorOn(on: Boolean): Boolean {
        val mgr = cpm ?: return false
        var ok = false
        for (area in HVAC_AREAS) {
            try {
                mgr.setBooleanProperty(PROP_HVAC_AC_ON, area, on)
                ok = true
            } catch (ignored: Throwable) {}
        }
        return ok
    }

    fun readTargetTemperature(): Float? {
        val mgr = cpm ?: return null
        for (area in HVAC_TEMP_AREAS) {
            try {
                val temp = mgr.getFloatProperty(PROP_HVAC_TEMP_SETPOINT, area)
                if (temp in 15.0f..35.0f) return temp
            } catch (ignored: Throwable) {}
        }
        return null
    }

    fun writeTargetTemperature(tempC: Float): Boolean {
        val mgr = cpm ?: return false
        val clamped = tempC.coerceIn(16.0f, 32.0f)
        var ok = false
        for (area in HVAC_TEMP_AREAS) {
            try {
                mgr.setFloatProperty(PROP_HVAC_TEMP_SETPOINT, area, clamped)
                ok = true
            } catch (ignored: Throwable) {}
        }
        return ok
    }

    fun readFanSpeed(): Int? {
        val mgr = cpm ?: return null
        for (area in HVAC_AREAS) {
            try {
                return mgr.getIntProperty(PROP_HVAC_FAN_SPEED, area)
            } catch (ignored: Throwable) {}
        }
        try {
            return mgr.getIntProperty(PROP_HVAC_FAN_SPEED_ALIAS, 0)
        } catch (ignored: Throwable) {}
        return null
    }

    fun writeFanSpeed(speed: Int): Boolean {
        val mgr = cpm ?: return false
        val clamped = speed.coerceIn(0, 8)
        var ok = false
        for (area in HVAC_AREAS) {
            try {
                mgr.setIntProperty(PROP_HVAC_FAN_SPEED, area, clamped)
                ok = true
            } catch (ignored: Throwable) {}
        }
        try {
            mgr.setIntProperty(PROP_HVAC_FAN_SPEED_ALIAS, 0, clamped)
            ok = true
        } catch (ignored: Throwable) {}
        return ok
    }

    fun readOutsideTemperature(): Float? {
        val mgr = cpm ?: return null
        return try {
            val raw = mgr.getIntProperty(PROP_HVAC_AMBIENT_TEMP, 0)
            val tempC = (raw - 80) / 2.0f
            if (tempC in -50.0f..85.0f) tempC else null
        } catch (ignored: Throwable) {
            null
        }
    }

    fun setAirflowDirection(directionMask: Int): Boolean {
        val mgr = cpm ?: return false
        return try {
            mgr.setIntProperty(PROP_HVAC_AIR_DIRECTION, 0, directionMask)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "setAirflowDirection error: ${t.message}")
            false
        }
    }

    fun setRecirculation(enabled: Boolean): Boolean {
        val mgr = cpm ?: return false
        var ok = false
        for (area in HVAC_AREAS) {
            try {
                mgr.setBooleanProperty(PROP_HVAC_RECIRC, area, enabled)
                ok = true
            } catch (ignored: Throwable) {}
        }
        return ok
    }

    fun setDefrostFront(enabled: Boolean): Boolean {
        val mgr = cpm ?: return false
        var ok = false
        for (area in HVAC_AREAS) {
            try {
                mgr.setBooleanProperty(PROP_HVAC_DEFROST_FRONT, area, enabled)
                ok = true
            } catch (ignored: Throwable) {}
        }
        return ok
    }

    fun setDefrostRear(enabled: Boolean): Boolean {
        val mgr = cpm ?: return false
        return try {
            mgr.setBooleanProperty(PROP_HVAC_DEFROST_REAR, 2, enabled)
            true
        } catch (t: Throwable) {
            false
        }
    }

    fun readRecirculation(): Boolean? = readBooleanFirst(PROP_HVAC_RECIRC, HVAC_AREAS)

    fun readDefrostFront(): Boolean? = readBooleanFirst(PROP_HVAC_DEFROST_FRONT, HVAC_AREAS)

    fun readDefrostRear(): Boolean? = readBooleanFirst(PROP_HVAC_DEFROST_REAR, intArrayOf(2))

    /** Bitmask: 1=FACE, 2=FLOOR, 3=FACE|FLOOR, 4=DEFROST, 6=DEFROST|FLOOR. */
    fun readAirflowDirection(): Int? {
        val mgr = cpm ?: return null
        return try {
            mgr.getIntProperty(PROP_HVAC_AIR_DIRECTION, 0).takeIf { it in 1..7 }
        } catch (_: Throwable) {
            null
        }
    }

    private fun readBooleanFirst(propId: Int, areas: IntArray): Boolean? {
        val mgr = cpm ?: return null
        for (area in areas) {
            try {
                return mgr.getBooleanProperty(propId, area)
            } catch (_: Throwable) {}
        }
        return null
    }

    // -------------------------------------------------------------
    // CHANGE EVENTS (push, complements polling)
    // -------------------------------------------------------------
    /**
     * Registers an ON_CHANGE callback for [propIds]. [onChange] runs on the binder/main thread
     * with the property id that changed — keep it cheap (e.g. schedule a refresh).
     * Returns a token for [unwatch], or null when nothing could be registered.
     * Some Geely properties never emit events, so callers must keep a slow poll as fallback.
     */
    fun watch(propIds: IntArray, onChange: (Int) -> Unit): Any? {
        val mgr = cpm ?: return null
        val callback = object : CarPropertyManager.CarPropertyEventCallback {
            override fun onChangeEvent(value: android.car.hardware.CarPropertyValue<*>?) {
                val id = value?.propertyId ?: return
                try { onChange(id) } catch (_: Throwable) {}
            }

            override fun onErrorEvent(propId: Int, zone: Int) {}
        }
        var registered = 0
        for (id in propIds) {
            try {
                if (mgr.registerCallback(callback, id, CarPropertyManager.SENSOR_RATE_ONCHANGE)) registered++
            } catch (t: Throwable) {
                Log.d(TAG, "watch $id not available: ${t.message}")
            }
        }
        if (registered == 0) return null
        Log.i(TAG, "Watching $registered/${propIds.size} properties for change events.")
        return callback
    }

    fun unwatch(token: Any?) {
        val callback = token as? CarPropertyManager.CarPropertyEventCallback ?: return
        try { cpm?.unregisterCallback(callback) } catch (_: Throwable) {}
    }

    // -------------------------------------------------------------
    // MACRO: QUICK COOL (LÀM MÁT NHANH)
    // -------------------------------------------------------------
    fun triggerQuickCool(onComplete: (Boolean) -> Unit = {}) {
        Thread {
            try {
                Log.i(TAG, "Triggering Quick Cool macro...")
                // 1. Turn on HVAC power & AC compressor
                setHvacPowerOn(true)
                setAcCompressorOn(true)
                Thread.sleep(100)

                // 2. Set min temperature (17.0 C)
                writeTargetTemperature(17.0f)
                Thread.sleep(100)

                // 3. Set fan speed to level 7
                writeFanSpeed(7)
                Thread.sleep(100)

                // 4. Set airflow direction to Face (1)
                setAirflowDirection(1)
                Thread.sleep(100)

                // 5. Internal air recirculation for fastest cooling
                setRecirculation(true)

                Log.i(TAG, "Quick Cool macro executed successfully.")
                onComplete(true)
            } catch (t: Throwable) {
                Log.e(TAG, "Error executing Quick Cool: ${t.message}", t)
                onComplete(false)
            }
        }.start()
    }

    // -------------------------------------------------------------
    // 4 WINDOW POSITIONS (Dashboard presets: 0% ĐÓNG KÍN, 10% HÉ, 50% MỞ NỬA, 100% HẠ HẾT)
    // -------------------------------------------------------------
    /**
     * Reads the real glass position of each pane (FL, FR, RL, RR).
     * An entry is null when that pane could not be read — never a cached/assumed value.
     */
    fun readWindowPositions(): Array<Int?> {
        val result = arrayOfNulls<Int>(WINDOW_AREAS.size)
        val mgr = cpm ?: return result
        for (i in WINDOW_AREAS.indices) {
            try {
                val pos = mgr.getIntProperty(PROP_WINDOW_POS, WINDOW_AREAS[i])
                if (pos in 0..100) result[i] = pos
            } catch (_: Throwable) {}
        }
        return result
    }

    /** Returns true only if at least one pane accepted the command. */
    fun setAllWindowsPosition(percent: Int): Boolean {
        val target = percent.coerceIn(0, 100)
        val mgr = cpm ?: return false
        var ok = false
        for (area in WINDOW_AREAS) {
            try {
                mgr.setIntProperty(PROP_WINDOW_POS, area, target)
                ok = true
            } catch (t: Throwable) {
                Log.w(TAG, "setWindow area $area error: ${t.message}")
            }
        }
        return ok
    }

    fun closeAllWindows(): Boolean = setAllWindowsPosition(0)
    fun ventAllWindows(): Boolean = setAllWindowsPosition(10)
    fun openAllWindows(): Boolean = setAllWindowsPosition(100)

    // -------------------------------------------------------------
    // CABIN AMBIENT LIGHT (ĐÈN VIỀN NỘI THẤT)
    // -------------------------------------------------------------
    fun readAmbientLight(): Boolean? {
        val mgr = cpm ?: return null
        return try { mgr.getBooleanProperty(PROP_AMBIENT_LIGHT_ON, 0) } catch (_: Throwable) { null }
    }

    fun setAmbientLight(on: Boolean): Boolean {
        val mgr = cpm ?: return false
        return try {
            mgr.setBooleanProperty(PROP_AMBIENT_LIGHT_ON, 0, on)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "setAmbientLight error: ${t.message}")
            false
        }
    }

    // -------------------------------------------------------------
    // ADAS & SAFETY
    // -------------------------------------------------------------
    fun isAebEnabled(): Boolean? {
        val mgr = cpm ?: return null
        return try {
            mgr.getBooleanProperty(PROP_AEB, 0)
        } catch (ignored: Throwable) {
            null
        }
    }

    fun setAebEnabled(enabled: Boolean): Boolean {
        val mgr = cpm ?: return false
        return try {
            mgr.setBooleanProperty(PROP_AEB, 0, enabled)
            true
        } catch (t: Throwable) {
            false
        }
    }
}
