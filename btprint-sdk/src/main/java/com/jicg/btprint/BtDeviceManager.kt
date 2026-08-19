package com.jicg.btprint

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 蓝牙设备管理器
 */
@SuppressLint("MissingPermission")
class BtDeviceManager private constructor(private val context: Context) {
    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()

    private val _discoveredDevices = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<BluetoothDevice>> = _discoveredDevices.asStateFlow()

    private val _connectedDevice = MutableStateFlow<BluetoothDevice?>(null)
    val connectedDevice: StateFlow<BluetoothDevice?> = _connectedDevice.asStateFlow()

    companion object {
        private const val TAG = "BtDeviceManager"
        private const val PREF_NAME = "bt_device_prefs"
        private const val KEY_LAST_DEVICE_ADDRESS = "last_device_address"
        private const val REQUEST_CODE_BLUETOOTH = 1001
        private var instance: BtDeviceManager? = null

        @Synchronized
        fun getInstance(context: Context): BtDeviceManager {
            if (instance == null) {
                instance = BtDeviceManager(context.applicationContext)
            }
            return instance!!
        }
    }

    /**
     * 检查蓝牙权限
     */
    fun hasBluetoothPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) ==
                    PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                    PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * 蓝牙是否开启
     */
    fun isBluetoothEnabled(): Boolean {
        return bluetoothAdapter?.isEnabled == true
    }

    /**
     * 获取已配对设备
     */
    fun getBondedDevices(): List<BluetoothDevice> {
        return try {
            bluetoothAdapter?.bondedDevices?.toList() ?: emptyList()
        } catch (e: SecurityException) {
            Log.e(TAG, "获取配对设备失败: 缺少蓝牙权限", e)
            emptyList()
        }
    }

    /**
     * 获取最后一次连接的设备
     */
    fun getLastConnectedDevice(): BluetoothDevice? {
        val lastAddress = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LAST_DEVICE_ADDRESS, null) ?: return null
        return try {
            bluetoothAdapter?.getRemoteDevice(lastAddress)
        } catch (e: Exception) {
            Log.e(TAG, "获取最后连接设备失败", e)
            null
        }
    }

    /**
     * 设置当前连接的设备
     */
    fun setConnectedDevice(device: BluetoothDevice?) {
        _connectedDevice.value = device
    }

    /**
     * 记住设备地址
     */
    fun rememberDevice(address: String) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_DEVICE_ADDRESS, address)
            .apply()
    }
}
