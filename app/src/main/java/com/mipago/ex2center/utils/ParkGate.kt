package com.mipago.ex2center.utils

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Shared vehicle-state gate for actions that are unsafe while driving
 * (reboot, app install/uninstall, lowering all windows, ...).
 *
 * Fail-safe: on a real head unit, if the gear or speed cannot be read the action is BLOCKED.
 * Only a device without the android.car API at all (plain emulator / phone) is allowed through,
 * because there is no vehicle to protect there.
 *
 * VHAL reads happen on a background thread; results are delivered on the main thread.
 */
object ParkGate {

    sealed class Decision {
        object Allowed : Decision()
        data class Blocked(val reason: String) : Decision()
    }

    private const val STATIONARY_KMH = 1.0f
    private const val CONNECT_WAIT_MS = 3_000L
    private const val HOLDER = "park-gate"

    /** Waits briefly for the shared Car link (e.g. Kho ứng dụng opened right after wake). */
    private fun ensureReady(context: Context): Boolean {
        val car = CarApiHelper.getInstance()
        if (car.isReady) return true
        val latch = CountDownLatch(1)
        car.acquire(context, HOLDER) { latch.countDown() }
        latch.await(CONNECT_WAIT_MS, TimeUnit.MILLISECONDS)
        return car.isReady
    }

    private val worker = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Gear must be P and the car stationary. Blocking call — do not run on the main thread. */
    fun checkParked(context: Context): Decision {
        if (!CarApiHelper.isCarApiPresent) return Decision.Allowed
        val car = CarApiHelper.getInstance()
        if (!ensureReady(context)) return Decision.Blocked("Chưa kết nối được với xe, không xác định được vị trí cần số.")
        val gear = car.readGear() ?: return Decision.Blocked("Không đọc được vị trí cần số.")
        if (gear != CarApiHelper.GEAR_PARK) return Decision.Blocked("Chỉ dùng được khi xe ở số P.")
        val speed = car.readSpeed()
        if (speed != null && speed > STATIONARY_KMH) return Decision.Blocked("Xe đang di chuyển. Hãy dừng hẳn xe.")
        return Decision.Allowed
    }

    /** Speed must be known and below [maxKmh]. Blocking call — do not run on the main thread. */
    fun checkSlowerThan(context: Context, maxKmh: Float): Decision {
        if (!CarApiHelper.isCarApiPresent) return Decision.Allowed
        val car = CarApiHelper.getInstance()
        if (!ensureReady(context)) return Decision.Blocked("Chưa kết nối được với xe, không đọc được tốc độ.")
        val speed = car.readSpeed() ?: return Decision.Blocked("Không đọc được tốc độ xe.")
        if (speed >= maxKmh) return Decision.Blocked("Chỉ dùng được khi xe chạy dưới ${maxKmh.toInt()} km/h.")
        return Decision.Allowed
    }

    /**
     * Evaluates [check] off the main thread, then runs [onAllowed] on the main thread,
     * or shows the block reason as a Toast (and calls [onBlocked]) when not allowed.
     */
    fun runIfAllowed(
        context: Context,
        check: (Context) -> Decision = ::checkParked,
        onBlocked: (String) -> Unit = {},
        onAllowed: () -> Unit
    ) {
        val appContext = context.applicationContext
        worker.execute {
            val decision = try {
                check(appContext)
            } catch (t: Throwable) {
                Decision.Blocked("Không kiểm tra được trạng thái xe: ${t.message}")
            }
            mainHandler.post {
                when (decision) {
                    is Decision.Allowed -> onAllowed()
                    is Decision.Blocked -> {
                        Toast.makeText(appContext, decision.reason, Toast.LENGTH_LONG).show()
                        onBlocked(decision.reason)
                    }
                }
            }
        }
    }
}
