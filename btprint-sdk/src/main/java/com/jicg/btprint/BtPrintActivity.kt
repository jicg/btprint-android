package com.jicg.btprint

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * 蓝牙打印界面：扫描、连接蓝牙打印机
 * 已配对设备与附近设备分组展示，整行点击连接，状态可视化（圆点 / 徽标 / 进度条）
 */
class BtPrintActivity : ComponentActivity() {
    private lateinit var bluetoothManager: BluetoothManager
    private var bluetoothAdapter: BluetoothAdapter? = null
    private lateinit var btPrintManager: BtPrintManager
    private lateinit var btDeviceManager: BtDeviceManager

    private lateinit var statusText: TextView
    private lateinit var statusDetail: TextView
    private lateinit var statusDot: View
    private lateinit var titleStatus: TextView
    private lateinit var scanButton: Button
    private lateinit var scanProgress: ProgressBar
    private lateinit var bondedListLayout: LinearLayout
    private lateinit var nearbyListLayout: LinearLayout
    private lateinit var groupBondedTitle: TextView
    private lateinit var groupNearbyTitle: TextView
    private lateinit var emptyView: TextView

    /** 设备地址 -> 设备行视图（去重 + 状态刷新） */
    private val deviceRows = HashMap<String, View>()

    /** 已配对设备的地址集合（扫描时用于区分分组与去重） */
    private val bondedAddresses = HashSet<String>()

    /** 等待开启蓝牙后继续执行的动作 */
    private var pendingScan = false

    /** 正在连接的设备地址 */
    private var connectingAddress: String? = null

    /** 当前已连接的设备地址 */
    private var connectedAddress: String? = null

    /**
     * 权限请求结果回调：全部授权后继续自动连接
     */
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val allGranted = result.values.all { it }
        if (allGranted) {
            updateStatus(
                "准备就绪",
                "点击下方按钮扫描附近的蓝牙打印机",
                R.color.btp_primary,
                "未连接"
            )
            autoConnectLastDevice()
        } else {
            updateStatus(
                "缺少蓝牙权限",
                "无法扫描和连接设备，请在系统设置中授权",
                R.color.btp_error,
                "未连接"
            )
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
            updateStatus(
                "蓝牙已开启",
                "点击下方按钮扫描附近的蓝牙打印机",
                R.color.btp_primary,
                "未连接"
            )
            if (pendingScan) {
                pendingScan = false
                scanDevices()
            } else {
                autoConnectLastDevice()
            }
        } else {
            updateStatus(
                "未开启蓝牙",
                "请在系统设置中开启蓝牙后重试",
                R.color.btp_error,
                "未连接"
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bt_print)

        // 初始化蓝牙管理器
        bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        btPrintManager = BtPrintManager.getInstance(this)
        btDeviceManager = BtDeviceManager.getInstance(this)

        bindViews()
        observeDeviceStates()

        // 无蓝牙模块的设备（部分平板/模拟器）：给出提示并禁用扫描，避免 NPE 崩溃
        if (bluetoothAdapter == null) {
            updateStatus(
                "设备不支持蓝牙",
                "当前设备没有蓝牙模块，无法使用蓝牙打印",
                R.color.btp_error,
                "不可用"
            )
            scanButton.isEnabled = false
            return
        }

        // 检查蓝牙权限
        checkBluetoothPermissions()

        // 自动连接上次设备
        autoConnectLastDevice()
    }

    private fun bindViews() {
        statusText = findViewById(R.id.statusText)
        statusDetail = findViewById(R.id.statusDetail)
        statusDot = findViewById(R.id.statusDot)
        titleStatus = findViewById(R.id.titleStatus)
        scanButton = findViewById(R.id.scanButton)
        scanProgress = findViewById(R.id.scanProgress)
        bondedListLayout = findViewById(R.id.bondedListLayout)
        nearbyListLayout = findViewById(R.id.nearbyListLayout)
        groupBondedTitle = findViewById(R.id.groupBondedTitle)
        groupNearbyTitle = findViewById(R.id.groupNearbyTitle)
        emptyView = findViewById(R.id.emptyView)

        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }

        scanButton.setOnClickListener {
            if (!btDeviceManager.hasBluetoothPermission()) {
                checkBluetoothPermissions()
            } else {
                scanDevices()
            }
        }
    }

    /**
     * 订阅扫描结果、扫描状态与连接状态
     */
    private fun observeDeviceStates() {
        // 新发现的设备追加到"附近设备"分组（去重，跳过已配对）
        lifecycleScope.launch {
            btDeviceManager.discoveredDevices.collect { devices ->
                devices.forEach { device ->
                    if (device.address !in deviceRows) {
                        addDeviceRow(nearbyListLayout, device)
                    }
                }
            }
        }

        // 扫描状态：按钮可用性 + 进度条 + 文案
        lifecycleScope.launch {
            btDeviceManager.isScanning.collect { scanning ->
                scanButton.isEnabled = !scanning
                scanButton.text = if (scanning) "扫描中..." else "扫描设备"
                scanProgress.visibility = if (scanning) View.VISIBLE else View.GONE
                if (!scanning) {
                    updateEmptyVisibility()
                }
            }
        }

        // 连接状态：标题栏圆点 + 设备行高亮（不覆盖主状态文本，避免与扫描/连接提示冲突）
        lifecycleScope.launch {
            btPrintManager.connectedDevice.collect { device ->
                btDeviceManager.setConnectedDevice(device)
                connectedAddress = device?.address
                connectingAddress = null

                val connected = device != null
                statusDot.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(
                        this@BtPrintActivity,
                        if (connected) R.color.btp_success else R.color.btp_text_secondary
                    )
                )
                titleStatus.text = if (connected) "已连接" else "未连接"
                refreshDeviceRows()
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
        if (bluetoothAdapter?.isEnabled != true) {
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

        updateStatus(
            "正在扫描设备...",
            "请确保打印机已开机并靠近手机",
            R.color.btp_primary,
            "未连接"
        )
        scanButton.isEnabled = false

        // 清空并重建两组设备列表
        bondedListLayout.removeAllViews()
        nearbyListLayout.removeAllViews()
        deviceRows.clear()
        bondedAddresses.clear()

        // 已配对设备展示到"已配对"分组
        btDeviceManager.getBondedDevices().forEach { device ->
            bondedAddresses.add(device.address)
            addDeviceRow(bondedListLayout, device)
        }

        // 启动真实扫描
        if (!btDeviceManager.startScan()) {
            updateStatus(
                "扫描启动失败",
                "请检查蓝牙与权限设置",
                R.color.btp_error,
                "未连接"
            )
            scanButton.isEnabled = true
        }
    }

    /**
     * 添加一行设备卡片；地址已在其他分组时跳过（按分组去重）
     */
    private fun addDeviceRow(container: LinearLayout, device: BluetoothDevice) {
        if (device.address in deviceRows) return

        val row = layoutInflater.inflate(R.layout.item_device, container, false)
        row.findViewById<TextView>(R.id.deviceName).text =
            device.name?.takeIf { it.isNotBlank() } ?: "未知设备"
        row.findViewById<TextView>(R.id.deviceAddress).text = device.address

        row.setOnClickListener { connectToDevice(device) }

        container.addView(row)
        deviceRows[device.address] = row
        updateEmptyVisibility()
    }

    /**
     * 刷新所有设备行的连接状态（已连接高亮 / 连接中 / 可连接）
     */
    private fun refreshDeviceRows() {
        deviceRows.forEach { (address, row) ->
            val badge = row.findViewById<TextView>(R.id.deviceState)
            when (address) {
                connectedAddress -> {
                    row.setBackgroundResource(R.drawable.bg_device_item_connected)
                    badge.setBackgroundResource(R.drawable.bg_badge_green)
                    badge.text = "已连接"
                }
                connectingAddress -> {
                    row.setBackgroundResource(R.drawable.bg_device_item)
                    badge.setBackgroundResource(R.drawable.bg_badge_gray)
                    badge.text = "连接中..."
                }
                else -> {
                    row.setBackgroundResource(R.drawable.bg_device_item)
                    badge.setBackgroundResource(R.drawable.bg_badge_primary)
                    badge.text = "连接"
                }
            }
        }
    }

    private fun connectToDevice(device: BluetoothDevice) {
        val address = device.address
        if (address == connectedAddress) {
            Toast.makeText(this, "已连接该打印机", Toast.LENGTH_SHORT).show()
            return
        }
        if (address == connectingAddress) return

        val name = device.name?.takeIf { it.isNotBlank() } ?: "设备"
        updateStatus(
            "正在连接 $name...",
            device.address,
            R.color.btp_warning,
            "连接中"
        )
        connectingAddress = address
        refreshDeviceRows()

        // 发现过程会减慢 RFCOMM 连接，连接前先停止扫描
        btDeviceManager.stopScan()

        lifecycleScope.launch {
            val connected = btPrintManager.connect(device)
            if (connected) {
                updateStatus(
                    "已连接：$name",
                    device.address,
                    R.color.btp_success,
                    "已连接"
                )
                Toast.makeText(this@BtPrintActivity, "连接成功", Toast.LENGTH_SHORT).show()
            } else {
                connectingAddress = null
                updateStatus(
                    "连接失败",
                    "请确认打印机已开机且在范围内",
                    R.color.btp_error,
                    "未连接"
                )
                Toast.makeText(this@BtPrintActivity, "连接失败", Toast.LENGTH_SHORT).show()
                refreshDeviceRows()
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
                updateStatus(
                    "已自动连接上次的打印机",
                    btPrintManager.connectedDevice.value?.address ?: "",
                    R.color.btp_success,
                    "已连接"
                )
                Toast.makeText(this@BtPrintActivity, "已自动连接上次的打印机", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 更新状态卡片文案与标题栏圆点/状态
     */
    private fun updateStatus(main: String, detail: String, dotColorRes: Int, title: String) {
        statusText.text = main
        statusDetail.text = detail
        statusDot.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, dotColorRes)
        )
        titleStatus.text = title
    }

    /**
     * 根据两组设备数量控制分组标题与空状态提示的显隐
     */
    private fun updateEmptyVisibility() {
        val hasBonded = bondedListLayout.childCount > 0
        val hasNearby = nearbyListLayout.childCount > 0
        groupBondedTitle.visibility = if (hasBonded) View.VISIBLE else View.GONE
        groupNearbyTitle.visibility = if (hasNearby) View.VISIBLE else View.GONE
        emptyView.visibility = if (hasBonded || hasNearby) View.GONE else View.VISIBLE
    }

    override fun onDestroy() {
        btDeviceManager.stopScan()
        super.onDestroy()
    }
}
