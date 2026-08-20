package com.jicg.btprint

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * 蓝牙打印界面
 */
class BtPrintActivity : ComponentActivity() {
    private lateinit var bluetoothManager: BluetoothManager
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var btPrintManager: BtPrintManager
    private lateinit var btDeviceManager: BtDeviceManager

    private lateinit var statusText: TextView
    private lateinit var scanButton: Button
    private lateinit var deviceListLayout: LinearLayout

    /** 已展示到列表的设备地址（用于去重） */
    private val listedAddresses = HashSet<String>()

    /** 等待开启蓝牙后继续执行的动作 */
    private var pendingScan = false

    /**
     * 权限请求结果回调：全部授权后继续自动连接
     */
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val allGranted = result.values.all { it }
        if (allGranted) {
            statusText.text = "准备就绪"
            autoConnectLastDevice()
        } else {
            statusText.text = "缺少蓝牙权限，无法扫描和连接设备"
            Toast.makeText(this, "需要蓝牙权限才能扫描和连接设备", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * 开启蓝牙结果回调：成功则继续原动作
     */
    private val enableBtLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            statusText.text = "蓝牙已开启"
            if (pendingScan) {
                pendingScan = false
                scanDevices()
            } else {
                autoConnectLastDevice()
            }
        } else {
            statusText.text = "未开启蓝牙"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 初始化蓝牙管理器
        bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        btPrintManager = BtPrintManager.getInstance(this)
        btDeviceManager = BtDeviceManager.getInstance(this)

        // 创建界面
        createUI()

        // 订阅扫描结果与连接状态
        observeDeviceStates()

        // 检查蓝牙权限
        checkBluetoothPermissions()

        // 自动连接上次设备
        autoConnectLastDevice()
    }

    private fun createUI() {
        // 创建根布局
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }

        // 状态文本
        statusText = TextView(this).apply {
            text = "准备就绪"
            textSize = 16f
            setPadding(0, 0, 0, 16)
        }
        rootLayout.addView(statusText)

        // 扫描按钮
        scanButton = Button(this).apply {
            text = "扫描设备"
            setOnClickListener {
                if (!btDeviceManager.hasBluetoothPermission()) {
                    checkBluetoothPermissions()
                } else {
                    scanDevices()
                }
            }
        }
        rootLayout.addView(scanButton)

        // 设备列表
        deviceListLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 16, 0, 0)
        }
        rootLayout.addView(deviceListLayout)

        setContentView(rootLayout)
    }

    /**
     * 订阅扫描结果、扫描状态与连接状态
     */
    private fun observeDeviceStates() {
        // 新发现的设备追加展示（去重）
        lifecycleScope.launch {
            btDeviceManager.discoveredDevices.collect { devices ->
                devices.forEach { device ->
                    if (listedAddresses.add(device.address)) {
                        addDeviceToLayout(device)
                    }
                }
            }
        }

        // 扫描状态控制按钮与状态文本
        lifecycleScope.launch {
            btDeviceManager.isScanning.collect { scanning ->
                scanButton.isEnabled = !scanning
                if (!scanning) {
                    statusText.text = "扫描完成"
                }
            }
        }

        // 连接状态同步到 BtDeviceManager（保持 API 一致）
        lifecycleScope.launch {
            btPrintManager.connectedDevice.collect { device ->
                btDeviceManager.setConnectedDevice(device)
            }
        }
    }

    /**
     * 检查蓝牙权限（Android 12+ 用 BLUETOOTH_SCAN/CONNECT，Android 11 及以下用定位权限）
     */
    private fun checkBluetoothPermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        }

        val notGrantedPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (notGrantedPermissions.isNotEmpty()) {
            permissionLauncher.launch(notGrantedPermissions.toTypedArray())
        }
    }

    private fun checkBluetoothEnabled(): Boolean {
        if (!bluetoothAdapter.isEnabled) {
            enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return false
        }
        return true
    }

    private fun scanDevices() {
        if (!checkBluetoothEnabled()) {
            pendingScan = true
            return
        }
        pendingScan = false

        statusText.text = "正在扫描设备..."
        scanButton.isEnabled = false
        listedAddresses.clear()

        // 清空设备列表
        deviceListLayout.removeAllViews()

        // 显示已配对设备
        btDeviceManager.getBondedDevices().forEach { device ->
            listedAddresses.add(device.address)
            addDeviceToLayout(device)
        }

        // 启动真实扫描
        if (!btDeviceManager.startScan()) {
            statusText.text = "扫描启动失败，请检查蓝牙与权限"
            scanButton.isEnabled = true
        }
    }

    private fun addDeviceToLayout(device: BluetoothDevice) {
        val deviceLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 8, 0, 8)
        }

        val deviceInfo = TextView(this).apply {
            text = "${device.name ?: "未知设备"} (${device.address})"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                weight = 1f
            }
        }
        deviceLayout.addView(deviceInfo)

        val connectButton = Button(this).apply {
            text = "连接"
            setOnClickListener {
                connectToDevice(device)
            }
        }
        deviceLayout.addView(connectButton)

        deviceListLayout.addView(deviceLayout)
    }

    private fun connectToDevice(device: BluetoothDevice) {
        statusText.text = "正在连接 ${device.name ?: "设备"}..."

        // 发现过程会减慢 RFCOMM 连接，连接前先停止扫描
        btDeviceManager.stopScan()

        lifecycleScope.launch {
            val connected = btPrintManager.connect(device)
            if (connected) {
                statusText.text = "已连接到 ${device.name ?: "设备"}"
                Toast.makeText(this@BtPrintActivity, "连接成功", Toast.LENGTH_SHORT).show()
            } else {
                statusText.text = "连接失败"
                Toast.makeText(this@BtPrintActivity, "连接失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun autoConnectLastDevice() {
        // 未开启蓝牙则先请求开启
        if (!btDeviceManager.isBluetoothEnabled()) {
            checkBluetoothEnabled()
            return
        }

        lifecycleScope.launch {
            if (btPrintManager.autoConnectLastDevice()) {
                statusText.text = "已自动连接上次的打印机"
                Toast.makeText(this@BtPrintActivity, "已自动连接上次的打印机", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroy() {
        btDeviceManager.stopScan()
        super.onDestroy()
    }
}
