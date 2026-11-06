package com.mipago.ex2center.car

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.mipago.ex2center.utils.CarApiHelper
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Groups of car values a screen can ask for. Only the groups someone needs are read from VHAL. */
enum class Signal { DRIVE, BATTERY, ENV, TPMS, WINDOWS, CLIMATE, CONTROLS }

/** Climate state exactly as the car reports it (null = unknown, never assumed). */
data class ClimateState(
    val acOn: Boolean? = null,
    val recircOn: Boolean? = null,
    val defrostFront: Boolean? = null,
    val defrostRear: Boolean? = null,
    val tempC: Int? = null,
    val fan: Int? = null,
    val vent: Int? = null
)

/** Driving aids and comfort toggles as the car reports them. */
data class ControlsState(
    val drive: Int? = null,
    val regen: Int? = null,
    val avas: Int? = null,
    val ambient: Boolean? = null,
    val autoHold: Boolean? = null,
    val wireless: Boolean? = null
)

/** One immutable read of the car. Groups nobody asked for stay null. */
data class CarSnapshot(
    val connected: Boolean = false,
    val speed: Float? = null,
    val gear: Int? = null,
    val speedLimit: CarApiHelper.SpeedLimit? = null,
    val soc: Float? = null,
    val range: Float? = null,
    val outsideTemp: Float? = null,
    val tpms: CarApiHelper.TpmsData? = null,
    val windows: List<Int?>? = null,
    val climate: ClimateState? = null,
    val controls: ControlsState? = null
) {
    /** Groups whose values differ from [other] (all of them when the connection changed). */
    fun changedSince(other: CarSnapshot?): Set<Signal> {
        if (other == null || other.connected != connected) return Signal.values().toSet()
        val out = HashSet<Signal>()
        if (speed != other.speed || gear != other.gear || speedLimit != other.speedLimit) out += Signal.DRIVE
        if (soc != other.soc || range != other.range) out += Signal.BATTERY
        if (outsideTemp != other.outsideTemp) out += Signal.ENV
        if (tpms != other.tpms) out += Signal.TPMS
        if (windows != other.windows) out += Signal.WINDOWS
        if (climate != other.climate) out += Signal.CLIMATE
        if (controls != other.controls) out += Signal.CONTROLS
        return out
    }
}

/**
 * The one place the app reads the car.
 *
 * Every screen (Dashboard, the bottom-bar climate panel, later each dashboard widget) subscribes
 * with the [Signal]s it shows. The hub keeps ONE background thread and ONE VHAL change-callback
 * for the whole process, reads only the union of the subscribed groups, and hands every
 * subscriber the same immutable [CarSnapshot] on the main thread, together with the groups that
 * changed since the previous read. Nothing is read while nobody is subscribed.
 *
 * Threading: subscribe / unsubscribe / refresh are called on the main thread; VHAL reads run on
 * the hub's own thread; listeners are always called on the main thread.
 */
object CarStateHub {

    private const val TAG = "CarStateHub"
    private const val VHAL_HOLDER = "state-hub"
    private const val EVENT_DEBOUNCE_MS = 200L
    private const val DEFAULT_INTERVAL_MS = 3_000L

    fun interface Listener {
        /** Main thread. [changed] = groups that differ from the previous read (all on the first one). */
        fun onCarState(state: CarSnapshot, changed: Set<Signal>)
    }

    class Subscription internal constructor(
        internal val signals: Set<Signal>,
        internal val intervalMs: Long,
        internal val listener: Listener
    ) {
        internal var delivered = false
    }

    /** Every property whose change event should trigger an immediate re-read. */
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
        CarApiHelper.PROP_WINDOW_POS,
        CarApiHelper.PROP_AMBIENT_LIGHT_ON,
        CarApiHelper.PROP_AUTO_HOLD,
        CarApiHelper.PROP_WIRELESS_CHARGING
    )

    private val car = CarApiHelper.getInstance()
    private val main = Handler(Looper.getMainLooper())
    private val subs = CopyOnWriteArrayList<Subscription>()
    private val exec = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "car-state-hub") }

    private var appContext: Context? = null
    private var pollFuture: ScheduledFuture<*>? = null     // main thread
    private var pollIntervalMs = 0L                         // main thread
    private var eventFuture: ScheduledFuture<*>? = null     // hub thread
    private var watchToken: Any? = null                     // hub thread
    @Volatile private var last: CarSnapshot? = null

    /** Last read (null before the first one). Any thread. */
    val latest: CarSnapshot? get() = last

    fun subscribe(context: Context, signals: Set<Signal>, intervalMs: Long = DEFAULT_INTERVAL_MS, listener: Listener): Subscription {
        val sub = Subscription(signals, intervalMs, listener)
        val first = subs.isEmpty()
        subs += sub
        if (first) {
            val app = context.applicationContext
            appContext = app
            car.acquire(app, VHAL_HOLDER) { ready -> if (ready) refresh() }
        }
        reschedule()
        // A fresh read for the newcomer; it also gets the last one right away so it never shows "--" for 3 s.
        last?.let { snap -> main.post { deliver(sub, snap, Signal.values().toSet()) } }
        refresh()
        return sub
    }

    fun unsubscribe(sub: Subscription?) {
        if (sub == null || !subs.remove(sub)) return
        if (subs.isEmpty()) {
            pollFuture?.cancel(false)
            pollFuture = null
            pollIntervalMs = 0L
            execute {
                eventFuture?.cancel(false)
                eventFuture = null
                watchToken?.let { car.unwatch(it) }
                watchToken = null
            }
            car.release(VHAL_HOLDER)
            last = null
        } else {
            reschedule()
        }
    }

    /** Re-read soon, e.g. after a write so the screen shows what the car really did. Any thread. */
    fun refresh(delayMs: Long = 0L) {
        if (subs.isEmpty()) return
        try {
            exec.schedule({ poll() }, delayMs, TimeUnit.MILLISECONDS)
        } catch (_: Throwable) {}
    }

    // -------------------------------------------------------------------------------------------

    /** Polls at the shortest interval any subscriber asked for. Main thread. */
    private fun reschedule() {
        val interval = subs.minOfOrNull { it.intervalMs } ?: return
        if (interval == pollIntervalMs && pollFuture != null) return
        pollFuture?.cancel(false)
        pollIntervalMs = interval
        pollFuture = try {
            exec.scheduleWithFixedDelay({ poll() }, interval, interval, TimeUnit.MILLISECONDS)
        } catch (_: Throwable) { null }
    }

    private fun execute(block: () -> Unit) {
        try { exec.execute(block) } catch (_: Throwable) {}
    }

    /** Hub thread. */
    private fun poll() {
        val wanted = HashSet<Signal>()
        subs.forEach { wanted += it.signals }
        if (wanted.isEmpty()) return

        val next = if (!car.isReady) {
            watchToken = null // the connection that owned the callback is gone
            appContext?.let { car.connect(it) } // re-establish after suspend / wake
            CarSnapshot(connected = false)
        } else {
            ensureWatch()
            try {
                read(wanted)
            } catch (t: Throwable) {
                Log.w(TAG, "Read failed: ${t.message}")
                CarSnapshot(connected = false)
            }
        }
        val changed = next.changedSince(last)
        last = next
        main.post { subs.forEach { deliver(it, next, changed) } }
    }

    private fun deliver(sub: Subscription, snap: CarSnapshot, changed: Set<Signal>) {
        if (!subs.contains(sub)) return
        val forSub = if (!sub.delivered) sub.signals else changed.intersect(sub.signals)
        sub.delivered = true
        try {
            sub.listener.onCarState(snap, forSub)
        } catch (t: Throwable) {
            Log.w(TAG, "Listener failed: ${t.message}")
        }
    }

    /** Hub thread. Reads only the requested groups. */
    private fun read(w: Set<Signal>): CarSnapshot {
        val drive = Signal.DRIVE in w
        return CarSnapshot(
            connected = true,
            speed = if (drive) car.readSpeed() else null,
            gear = if (drive) car.readGear() else null,
            speedLimit = if (drive) car.readSpeedLimit() else null,
            soc = if (Signal.BATTERY in w) car.readBatterySoc() else null,
            range = if (Signal.BATTERY in w) car.readEstimatedRange() else null,
            outsideTemp = if (Signal.ENV in w) car.readOutsideTemperature() else null,
            tpms = if (Signal.TPMS in w) car.readTpmsData() else null,
            windows = if (Signal.WINDOWS in w) car.readWindowPositions().toList() else null,
            climate = if (Signal.CLIMATE in w) ClimateState(
                acOn = car.isAcCompressorOn(),
                recircOn = car.readRecirculation(),
                defrostFront = car.readDefrostFront(),
                defrostRear = car.readDefrostRear(),
                tempC = car.readTargetTemperature()?.let { Math.round(it) },
                fan = car.readFanSpeed(),
                vent = car.readAirflowDirection()
            ) else null,
            controls = if (Signal.CONTROLS in w) ControlsState(
                drive = car.readDriveMode(),
                regen = car.readRegenMode(),
                avas = car.getAvasMode(),
                ambient = car.readAmbientLight(),
                autoHold = car.readAutoHold(),
                wireless = car.readWirelessCharging()
            ) else null
        )
    }

    /** Hub thread. One change callback per connection; events coalesce into one re-read. */
    private fun ensureWatch() {
        if (watchToken != null) return
        watchToken = car.watch(WATCH_PROPS) {
            execute {
                eventFuture?.cancel(false)
                eventFuture = try {
                    exec.schedule({ poll() }, EVENT_DEBOUNCE_MS, TimeUnit.MILLISECONDS)
                } catch (_: Throwable) { null }
            }
        }
    }
}

/**
 * Car writes, one at a time on their own thread (a slow VHAL write never blocks the screen or
 * the reads), followed by a re-read so every subscriber sees what the car really did.
 */
object CarCommands {
    const val CONFIRM_DELAY_MS = 1_200L

    private val main = Handler(Looper.getMainLooper())
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "car-commands") }

    /**
     * Runs [write] off the main thread. [onResult] (main thread) gets whether the car accepted it.
     * [refreshAfterMs] lists the re-reads to schedule afterwards (none = no re-read).
     */
    fun run(
        write: () -> Boolean,
        refreshAfterMs: LongArray = longArrayOf(CONFIRM_DELAY_MS),
        onResult: ((Boolean) -> Unit)? = null
    ) {
        try {
            exec.execute {
                val ok = try { write() } catch (_: Throwable) { false }
                onResult?.let { cb -> main.post { cb(ok) } }
                refreshAfterMs.forEach { CarStateHub.refresh(it) }
            }
        } catch (_: Throwable) {
            onResult?.let { cb -> main.post { cb(false) } }
        }
    }
}
