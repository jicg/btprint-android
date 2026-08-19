package com.jicg.btprint

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
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

    private lateinit var statusText: TextView
    private lateinit var scanButton: Button
    private lateinit var deviceListLayout: LinearLayout

    companion object {
        private const val REQUEST_BLUETOOTH_PERMISSIONS = 1
        private const val REQUEST_ENABLE_BT = 2
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 初始化蓝牙管理器
        bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        btPrintManager = BtPrintManager.getInstance(this)

        // 创建界面
        createUI()

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
                if (checkBluetoothEnabled()) {
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

    private fun checkBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val permissions = arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )

            val notGrantedPermissions = permissions.filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }

            if (notGrantedPermissions.isNotEmpty()) {
                ActivityCompat.requestPermissions(
                    this,
                    notGrantedPermissions.toTypedArray(),
                    REQUEST_BLUETOOTH_PERMISSIONS
                )
            }
        }
    }

    private fun checkBluetoothEnabled(): Boolean {
        if (!bluetoothAdapter.isEnabled) {
            val enableBtIntent = android.content.Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT)
            return false
        }
        return true
    }

    private fun scanDevices() {
        statusText.text = "正在扫描设备..."
        scanButton.isEnabled = false

        // 清空设备列表
        deviceListLayout.removeAllViews()

        // 获取已配对设备
        val pairedDevices = bluetoothAdapter.bondedDevices

        // 显示已配对设备
        pairedDevices.forEach { device ->
            addDeviceToLayout(device)
        }

        scanButton.isEnabled = true
        statusText.text = "扫描完成"
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

        lifecycleScope.launch {
            val connected = btPrintManager.connect(device)
            if (connected) {
                statusText.text = "已连接到 ${device.name ?: "设备"}"
                Toast.makeText(this@BtPrintActivity, "连接成功", Toast.LENGTH_SHORT).show()
                // 连接成功后自动返回
//                finish()
            } else {
                statusText.text = "连接失败"
                Toast.makeText(this@BtPrintActivity, "连接失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun autoConnectLastDevice() {
        lifecycleScope.launch {
            if (btPrintManager.autoConnectLastDevice()) {
                statusText.text = "已自动连接上次的打印机"
                Toast.makeText(this@BtPrintActivity, "已自动连接上次的打印机", Toast.LENGTH_SHORT).show()
                // 连接成功后自动返回
//                finish()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}
