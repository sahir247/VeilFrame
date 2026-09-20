package com.veilframe.app.upscale.inference.qnn

import ai.onnxruntime.OrtEnvironment
import android.content.Context
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Thread-safe JVM process-wide lease manager for the Qualcomm QNN Plugin EP.
 *
 * Because OrtEnvironment is a JVM singleton, registering or unregistering the QNN Plugin EP
 * library affects all active sessions across activities and concurrent tile workers.
 *
 * Invariant: QnnAccelerationManager.remove() must NEVER blindly unregister while sessions
 * or leases are active.
 *
 * Lifecycle contract:
 * - acquire(): Ensures plugin is registered via QnnAccelerationManager and increments lease count.
 *   Rejects new acquisitions if a removal has been requested.
 * - release(): Decrements lease count. If removal is pending and active lease count hits zero,
 *   triggers deferred unregistration and native cleanup.
 * - remove(): Marks removal requested, rejects future acquisitions, awaits or immediately executes
 *   unregistration and native deletion when active leases reach zero.
 */
object QnnPluginLeaseManager {

    private const val TAG = "VeilFrame.QnnLease"

    private val activeLeases = AtomicInteger(0)
    private val activeSessions = AtomicInteger(0)
    private val removalRequested = AtomicBoolean(false)
    private val lock = Any()

    val currentLeaseCount: Int
        get() = activeLeases.get()

    val currentSessionCount: Int
        get() = activeSessions.get()

    val isRemovalPending: Boolean
        get() = removalRequested.get()

    /**
     * Acquires a lease for QNN execution.
     * Ensures plugin is registered before returning.
     * Throws IllegalStateException if removal has been requested.
     */
    fun acquire(env: OrtEnvironment, context: Context): Result<List<QnnDeviceInfo>> {
        synchronized(lock) {
            if (removalRequested.get()) {
                val err = "Cannot acquire QNN lease: removal has been requested"
                Log.w(TAG, err)
                return Result.failure(IllegalStateException(err))
            }

            val regResult = QnnAccelerationManager.register(env, context)
            if (regResult.isFailure) {
                return regResult
            }

            activeLeases.incrementAndGet()
            Log.d(TAG, "Acquired QNN lease (active leases: ${activeLeases.get()}, active sessions: ${activeSessions.get()})")
            return regResult
        }
    }

    /**
     * Increments active session count when an OrtSession is created under an acquired lease.
     */
    fun incrementSession() {
        activeSessions.incrementAndGet()
    }

    /**
     * Decrements active session count when an OrtSession is closed.
     */
    fun decrementSession(env: OrtEnvironment? = null, context: Context? = null) {
        activeSessions.updateAndGet { (it - 1).coerceAtLeast(0) }
        checkDeferredRemoval(env, context)
    }

    /**
     * Releases an acquired QNN lease.
     */
    fun release(env: OrtEnvironment? = null, context: Context? = null) {
        synchronized(lock) {
            val count = activeLeases.updateAndGet { (it - 1).coerceAtLeast(0) }
            Log.d(TAG, "Released QNN lease (remaining leases: $count, remaining sessions: ${activeSessions.get()})")
            checkDeferredRemoval(env, context)
        }
    }

    /**
     * Marks removal requested, rejects future acquisitions, and if no active leases/sessions,
     * executes unregistration, file deletion, and cache invalidation immediately.
     */
    fun remove(context: Context, env: OrtEnvironment? = null): Boolean {
        synchronized(lock) {
            removalRequested.set(true)
            Log.i(TAG, "QNN removal requested. Active leases: ${activeLeases.get()}, active sessions: ${activeSessions.get()}")

            return if (activeLeases.get() == 0 && activeSessions.get() == 0) {
                executeRemoval(context, env)
            } else {
                Log.i(TAG, "QNN removal deferred until all active leases and sessions complete.")
                false
            }
        }
    }

    private fun checkDeferredRemoval(env: OrtEnvironment?, context: Context?) {
        synchronized(lock) {
            if (removalRequested.get() && activeLeases.get() == 0 && activeSessions.get() == 0 && context != null) {
                Log.i(TAG, "All active QNN leases and sessions finished. Executing deferred removal.")
                executeRemoval(context, env)
            }
        }
    }

    private fun executeRemoval(context: Context, env: OrtEnvironment?): Boolean {
        val deleted = QnnAccelerationManager.executeNativeRemoval(context, env)
        removalRequested.set(false)
        activeLeases.set(0)
        activeSessions.set(0)
        Log.i(TAG, "Completed QNN removal and reset lease state.")
        return deleted
    }

    /**
     * Resets lease state (primarily for unit tests).
     */
    fun resetForTesting() {
        synchronized(lock) {
            activeLeases.set(0)
            activeSessions.set(0)
            removalRequested.set(false)
        }
    }
}
