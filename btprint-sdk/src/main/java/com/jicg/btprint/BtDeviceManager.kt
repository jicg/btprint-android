package com.jicg.btprint

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _connectedDevice = MutableStateFlow<BluetoothDevice?>(null)
    val connectedDevice: StateFlow<BluetoothDevice?> = _connectedDevice.asStateFlow()

    private var discoveryReceiver: BroadcastReceiver? = null
    private val discoveredAddresses = HashSet<String>()

    companion object {
        private const val TAG = "BtDeviceManager"
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
     * 获取最后一次连接的设备（委托 BtPrintManager 的统一存储，避免两份数据不一致）
     */
    fun getLastConnectedDevice(): BluetoothDevice? {
        val lastAddress = BtPrintManager.getInstance(context).getLastDeviceAddress() ?: return null
        return try {
            bluetoothAdapter?.getRemoteDevice(lastAddress)
        } catch (e: Exception) {
            Log.e(TAG, "获取最后连接设备失败", e)
            null
        }
    }

    /**
     * 设置当前连接的设备（由调用方订阅 BtPrintManager.connectedDevice 后同步）
     */
    fun setConnectedDevice(device: BluetoothDevice?) {
        _connectedDevice.value = device
    }

    /**
     * 开始扫描周围蓝牙设备（真实扫描：startDiscovery）
     * @return 是否成功启动扫描
     */
    fun startScan(): Boolean {
        if (!isBluetoothEnabled()) {
            Log.w(TAG, "蓝牙未开启，无法扫描")
            return false
        }
        if (!hasBluetoothPermission()) {
            Log.w(TAG, "缺少蓝牙权限，无法扫描")
            return false
        }
        val adapter = bluetoothAdapter ?: return false
        if (adapter.isDiscovering) {
            adapter.cancelDiscovery()
        }
        discoveredAddresses.clear()
        _discoveredDevices.value = emptyList()

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        @Suppress("DEPRECATION")
                        val device = intent.getParcelableExtra<BluetoothDevice>(
                            BluetoothDevice.EXTRA_DEVICE
                        ) ?: return
                        val address = try {
                            device.address
                        } catch (e: SecurityException) {
                            return
                        }
                        if (discoveredAddresses.add(address)) {
                            _discoveredDevices.value = _discoveredDevices.value + device
                            Log.i(TAG, "发现新设备: ${device.name ?: "未知设备"} ($address)")
                        }
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                        _isScanning.value = false
                        Log.i(TAG, "扫描结束")
                    }
                }
            }
        }
        discoveryReceiver = receiver
        try {
            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            }
            registerSystemReceiver(receiver, filter)
            if (adapter.startDiscovery()) {
                _isScanning.value = true
                Log.i(TAG, "开始扫描设备")
                return true
            } else {
                Log.e(TAG, "startDiscovery 返回 false")
                stopScan()
                return false
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "启动扫描失败: 缺少蓝牙权限", e)
            stopScan()
            return false
        }
    }

    /**
     * 动态注册系统广播接收器（兼容 Android 13+ 的导出标志要求）
     */
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerSystemReceiver(receiver: BroadcastReceiver, filter: IntentFilter) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(receiver, filter)
        }
    }

    /**
     * 停止扫描并注销广播监听
     */
    fun stopScan() {
        bluetoothAdapter?.takeIf { it.isDiscovering }?.cancelDiscovery()
        discoveryReceiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (e: Exception) {
                Log.e(TAG, "注销扫描监听失败", e)
            }
            discoveryReceiver = null
        }
        _isScanning.value = false
    }
}
