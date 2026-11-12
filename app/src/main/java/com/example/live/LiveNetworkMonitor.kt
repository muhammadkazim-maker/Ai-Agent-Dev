package com.example.live

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Abstraction for lifecycle-safe Android connectivity monitoring and pre-handshake validation.
 *
 * Enforces Phase 03 offline detection contract:
 * - Active network MUST have both [NetworkCapabilities.NET_CAPABILITY_INTERNET] and
 *   [NetworkCapabilities.NET_CAPABILITY_VALIDATED] before any Firebase AI Logic or App Check call.
 * - Notifies listeners immediately when validated internet access is lost or restored.
 */
interface LiveNetworkMonitor {
    /**
     * Returns `true` if and only if an active network currently has both
     * [NetworkCapabilities.NET_CAPABILITY_INTERNET] and [NetworkCapabilities.NET_CAPABILITY_VALIDATED].
     */
    fun isInternetValidated(): Boolean

    /**
     * Registers connectivity monitoring while the diagnostics screen or Live session is active.
     * Safe and idempotent if called multiple times.
     */
    fun startMonitoring(onValidatedChanged: (Boolean) -> Unit)

    /**
     * Unregisters connectivity monitoring safely when no longer needed.
     * Safe and idempotent if called multiple times.
     */
    fun stopMonitoring()
}

/**
 * Deterministic [LiveNetworkMonitor] used in JVM/Robolectric tests and as a fallback when no
 * Android [Context] is supplied. Supports programmatic toggling of validated internet state.
 */
class ControllableLiveNetworkMonitor(
    initialValidated: Boolean = true
) : LiveNetworkMonitor {
    private val validated = AtomicBoolean(initialValidated)

    @Volatile
    private var listener: ((Boolean) -> Unit)? = null

    var isMonitoringActive: Boolean = false
        private set

    var registrationCount: Int = 0
        private set

    var unregistrationCount: Int = 0
        private set

    override fun isInternetValidated(): Boolean = validated.get()

    @Synchronized
    override fun startMonitoring(onValidatedChanged: (Boolean) -> Unit) {
        listener = onValidatedChanged
        if (!isMonitoringActive) {
            isMonitoringActive = true
            registrationCount++
        }
        onValidatedChanged(validated.get())
    }

    @Synchronized
    override fun stopMonitoring() {
        if (isMonitoringActive) {
            isMonitoringActive = false
            unregistrationCount++
        }
        listener = null
    }

    /**
     * Updates the simulated validated internet state and immediately notifies any active listener.
     */
    fun setInternetValidated(isValidated: Boolean) {
        validated.set(isValidated)
        listener?.invoke(isValidated)
    }
}

/**
 * Production Android [LiveNetworkMonitor] backed by [ConnectivityManager.NetworkCallback].
 *
 * Checks both [NetworkCapabilities.NET_CAPABILITY_INTERNET] and
 * [NetworkCapabilities.NET_CAPABILITY_VALIDATED] on the active network and tracks real-time
 * capability transitions (`onAvailable`, `onCapabilitiesChanged`, `onLost`, `onUnavailable`).
 */
class AndroidLiveNetworkMonitor(
    context: Context
) : LiveNetworkMonitor {
    private val appContext = context.applicationContext
    private val connectivityManager: ConnectivityManager? =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val lock = Any()
    private var registeredCallback: ConnectivityManager.NetworkCallback? = null
    private var activeListener: ((Boolean) -> Unit)? = null

    override fun isInternetValidated(): Boolean {
        val cm = connectivityManager ?: return false
        return try {
            val activeNetwork = cm.activeNetwork ?: return false
            val capabilities = cm.getNetworkCapabilities(activeNetwork) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } catch (_: Throwable) {
            false
        }
    }

    override fun startMonitoring(onValidatedChanged: (Boolean) -> Unit) {
        val cm = connectivityManager
        synchronized(lock) {
            activeListener = onValidatedChanged
            if (cm == null) {
                onValidatedChanged(false)
                return
            }
            if (registeredCallback != null) {
                onValidatedChanged(isInternetValidated())
                return
            }

            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    notifyCurrentState()
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities
                ) {
                    val hasValidInternet =
                        networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                            networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    // Also check active network state so non-default networks don't mask loss
                    val effectiveValidated = hasValidInternet && isInternetValidated()
                    val listenerSnapshot = synchronized(lock) { activeListener }
                    listenerSnapshot?.invoke(effectiveValidated)
                }

                override fun onLost(network: Network) {
                    notifyCurrentState()
                }

                override fun onUnavailable() {
                    val listenerSnapshot = synchronized(lock) { activeListener }
                    listenerSnapshot?.invoke(false)
                }
            }

            try {
                cm.registerDefaultNetworkCallback(callback)
                registeredCallback = callback
            } catch (_: Throwable) {
                registeredCallback = null
            }
        }
        onValidatedChanged(isInternetValidated())
    }

    override fun stopMonitoring() {
        val cm = connectivityManager
        val callbackToUnregister: ConnectivityManager.NetworkCallback?
        synchronized(lock) {
            callbackToUnregister = registeredCallback
            registeredCallback = null
            activeListener = null
        }
        if (cm != null && callbackToUnregister != null) {
            try {
                cm.unregisterNetworkCallback(callbackToUnregister)
            } catch (_: Throwable) {
                // Ignore if already unregistered by OS
            }
        }
    }

    private fun notifyCurrentState() {
        val validatedNow = isInternetValidated()
        val listenerSnapshot = synchronized(lock) { activeListener }
        listenerSnapshot?.invoke(validatedNow)
    }
}
