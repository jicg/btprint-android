package com.jicg.apptest.btprint

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 蓝牙设备管理器
 */
class BtDeviceManager private constructor(private val context: Context) {
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private val prefs: SharedPreferences = context.getSharedPreferences("bt_device_prefs", Context.MODE_PRIVATE)
    private val KEY_LAST_DEVICE_ADDRESS = "last_device_address"
    
    private val _discoveredDevices = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<BluetoothDevice>> = _discoveredDevices
    
    private val _connectedDevice = MutableStateFlow<BluetoothDevice?>(null)
    val connectedDevice: StateFlow<BluetoothDevice?> = _connectedDevice
    
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
     * 开始扫描设备
     */
    fun startDiscovery() {
        try {
            bluetoothAdapter?.startDiscovery()
        } catch (e: Exception) {
            Log.e(TAG, "开始扫描设备失败", e)
        }
    }
    
    /**
     * 停止扫描设备
     */
    fun cancelDiscovery() {
        try {
            bluetoothAdapter?.cancelDiscovery()
        } catch (e: Exception) {
            Log.e(TAG, "停止扫描设备失败", e)
        }
    }
    
    /**
     * 添加发现的设备
     * @param device 蓝牙设备
     */
    fun addDiscoveredDevice(device: BluetoothDevice) {
        val currentList = _discoveredDevices.value.toMutableList()
        if (!currentList.any { it.address == device.address }) {
            currentList.add(device)
            _discoveredDevices.value = currentList
        }
    }
    
    /**
     * 设置已连接的设备
     * @param device 蓝牙设备
     */
    fun setConnectedDevice(device: BluetoothDevice?) {
        _connectedDevice.value = device
        if (device != null) {
            // 保存最后连接的设备地址
            prefs.edit().putString(KEY_LAST_DEVICE_ADDRESS, device.address).apply()
        }
    }
    
    /**
     * 保存上次连接的设备
     * @param device 蓝牙设备
     */
    fun saveLastConnectedDevice(device: BluetoothDevice) {
        prefs.edit().putString(KEY_LAST_DEVICE_ADDRESS, device.address).apply()
    }
    
    /**
     * 获取上次连接的设备地址
     * @return 设备地址
     */
    fun getLastConnectedDeviceAddress(): String? {
        return prefs.getString(KEY_LAST_DEVICE_ADDRESS, null)
    }
    
    /**
     * 根据地址获取已配对设备
     * @param address 设备地址
     * @return 蓝牙设备
     */
    fun getPairedDevice(address: String): BluetoothDevice? {
        return bluetoothAdapter?.bondedDevices?.find { it.address == address }
    }
    
    /**
     * 检查蓝牙是否启用
     * @return 是否启用
     */
    fun isBluetoothEnabled(): Boolean {
        return bluetoothAdapter?.isEnabled == true
    }
    
    /**
     * 清除已发现的设备列表
     */
    fun clearDiscoveredDevices() {
        _discoveredDevices.value = emptyList()
    }
} 