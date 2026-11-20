package com.kineai.local.device

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.PowerManager
import com.kineai.local.models.DeviceBudget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

data class Vitals(
    val totalRamBytes: Long,
    val availRamBytes: Long,
    val lowMemory: Boolean,
    val thermalStatus: Int,
    val batteryPercent: Int,
    val batteryTempC: Float,
    val charging: Boolean,
)

class DeviceMonitor(private val context: Context, private val dataDir: File) {
    private val am = context.getSystemService(ActivityManager::class.java)
    private val pm = context.getSystemService(PowerManager::class.java)
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val _online = MutableStateFlow(isOnline())

    /** True when any network is available. Only model search and download use it. */
    val online: StateFlow<Boolean> = _online.asStateFlow()

    init {
        runCatching {
            cm.registerDefaultNetworkCallback(
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) { _online.value = true }
                    override fun onLost(network: Network) { _online.value = isOnline() }
                },
            )
        }
    }

    private fun isOnline(): Boolean =
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

    fun vitals(): Vitals {
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val b: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = b?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = b?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = b?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        return Vitals(
            mi.totalMem, mi.availMem, mi.lowMemory, pm.currentThermalStatus,
            if (level >= 0) level * 100 / scale else -1,
            (b?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f,
            status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL,
        )
    }

    fun budget(): DeviceBudget = vitals().let { DeviceBudget(it.totalRamBytes, it.availRamBytes, dataDir.usableSpace) }

    companion object {
        fun thermalName(s: Int) =
            when (s) {
                PowerManager.THERMAL_STATUS_NONE -> "normal"
                PowerManager.THERMAL_STATUS_LIGHT -> "light"
                PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
                PowerManager.THERMAL_STATUS_SEVERE -> "severe"
                PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
                PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
                PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
                else -> "unknown"
            }
    }
}
