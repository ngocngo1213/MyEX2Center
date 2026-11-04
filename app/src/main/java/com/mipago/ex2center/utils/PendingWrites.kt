package com.mipago.ex2center.utils

import android.os.SystemClock

/**
 * Anti-flicker bookkeeping for VHAL controls (UI thread only).
 *
 * When the user commands a value, [mark] records it. While the command is "in flight",
 * [resolve] keeps showing the commanded value even if a poll still returns the old one, so the
 * button does not jump back and forth. As soon as the car reports the commanded value the entry
 * is confirmed and removed. If the car still disagrees after [ttlMs], the entry expires,
 * [resolve] returns the car's real value and reports the rejection once.
 */
class PendingWrites(private val ttlMs: Long = 4_500L) {

    private class Entry(val value: Any, val expiresAt: Long)

    private val entries = HashMap<String, Entry>()

    fun mark(key: String, value: Any) {
        entries[key] = Entry(value, SystemClock.elapsedRealtime() + ttlMs)
    }

    /** Drops an entry immediately (e.g. the write was rejected synchronously). */
    fun clear(key: String) {
        entries.remove(key)
    }

    fun isPending(key: String): Boolean {
        val e = entries[key] ?: return false
        return SystemClock.elapsedRealtime() < e.expiresAt
    }

    /**
     * Returns the value to display for [key] given the car's readback [actual].
     * [onRejected] is called once, with the commanded value, when the car never confirmed it.
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> resolve(key: String, actual: T?, onRejected: (wanted: Any) -> Unit = {}): T? {
        val e = entries[key] ?: return actual
        if (actual != null && actual == e.value) {
            entries.remove(key)
            return actual
        }
        if (SystemClock.elapsedRealtime() < e.expiresAt) {
            return e.value as? T ?: actual
        }
        entries.remove(key)
        onRejected(e.value)
        return actual
    }
}
