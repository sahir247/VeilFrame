package com.veilframe.app.runtime

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import java.util.concurrent.Executors

/**
 * ThermalGovernor — F11: thermal & battery awareness for heavy compute.
 *
 * Root problem this fixes: sustained all-core inference soaks the SoC; Android
 * then throttles EVERYTHING (including the system compositor), which is why the
 * whole phone felt laggy during and after upscaling. With an explicit governor:
 *  - MODERATE+  → efficiency profile (workers=1, smaller chunks) before the OS
 *                 has to throttle us,
 *  - CRITICAL+  → heavy jobs abort cooperatively with a typed, honest error,
 *  - battery <20% unplugged → efficiency profile.
 *
 * Registered by heavy workspaces (AI Upscaler now; CV lanes in Phase B).
 * Pre-API-29 devices have no thermal callback: status stays NONE and the
 * battery heuristic still applies.
 */
object ThermalGovernor {

    /** Raw PowerManager.THERMAL_STATUS_* value (0 = NONE on API 29+). */
    @Volatile
    var status: Int = 0
        private set

    private var powerManager: PowerManager? = null
    private var listener: Any? = null
    private var registrations = 0
    private val executor by lazy {
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "vf-thermal").apply { isDaemon = true }
        }
    }

    @Synchronized
    fun register(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        registrations++
        if (listener != null) return
        val pm = context.applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        val l = PowerManager.OnThermalStatusChangedListener { newStatus ->
            status = newStatus
        }
        runCatching { pm.addThermalStatusListener(executor, l) }
            .onSuccess {
                powerManager = pm
                listener = l
                status = runCatching { pm.currentThermalStatus }.getOrDefault(0)
            }
    }

    @Synchronized
    fun unregister() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        registrations = (registrations - 1).coerceAtLeast(0)
        if (registrations > 0) return // another workspace still needs the listener
        val pm = powerManager
        val l = listener
        if (pm != null && l != null) {
            runCatching {
                pm.removeThermalStatusListener(l as PowerManager.OnThermalStatusChangedListener)
            }
        }
        powerManager = null
        listener = null
    }

    /** MODERATE or worse → efficiency mode (workers 1, smaller chunks). */
    val isThrottled: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            status >= PowerManager.THERMAL_STATUS_MODERATE

    /** CRITICAL or worse → abort heavy jobs cooperatively (typed ThermalShutdownException). */
    val isCritical: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            status >= PowerManager.THERMAL_STATUS_CRITICAL

    /** True when running on battery below 20% — heavy jobs drop to efficiency profile. */
    fun batteryConstrained(context: Context): Boolean {
        return try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return false
            val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            !bm.isCharging && level in 1..19
        } catch (_: Throwable) {
            false
        }
    }

    /** Human-readable status for diagnostics panels and honest status lines. */
    fun statusName(): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return "unsupported (API<29)"
        return when (status) {
            PowerManager.THERMAL_STATUS_NONE -> "none"
            PowerManager.THERMAL_STATUS_LIGHT -> "light"
            PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
            PowerManager.THERMAL_STATUS_SEVERE -> "severe"
            PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
            else -> "unknown($status)"
        }
    }
}
