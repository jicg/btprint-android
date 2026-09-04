package com.jicg.btprint

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.jicg.btprint.transport.ConnectionTarget
import com.jicg.btprint.transport.TcpTransport
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * WiFi 打印界面：手动输入 IP 连接，或扫描局域网内开放打印端口（9100/4001）的设备
 * 与 BtPrintActivity 对称，作为 WiFi 网络打印机的连接配置页
 */
class WifiPrintActivity : ComponentActivity() {
    private lateinit var btPrintManager: BtPrintManager
    private lateinit var statusText: TextView
    private lateinit var statusDetail: TextView
    private lateinit var statusDot: View
    private lateinit var titleStatus: TextView
    private lateinit var hostEdit: EditText
    private lateinit var portEdit: EditText
    private lateinit var connectButton: Button
    private lateinit var disconnectButton: Button
    private lateinit var scanButton: Button
    private lateinit var scanProgress: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var foundListLayout: LinearLayout
    private lateinit var groupFoundTitle: TextView
    private lateinit var emptyView: TextView

    /** "host:port" -> 结果行视图（去重 + 状态刷新） */
    private val foundRows = LinkedHashMap<String, View>()

    /** 进行中的扫描任务（Activity 销毁时取消） */
    private var scanJob: Job? = null

    /** 当前已连接的网络打印机标识（host:port），蓝牙连接或断开时为 null */
    private var connectedKey: String? = null

    /** 正在连接的网络打印机标识 */
    private var connectingKey: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_wifi_print)
        btPrintManager = BtPrintManager.getInstance(this)
        bindViews()
        observeConnectionState()
        autoConnectLastTcp()
    }

    private fun bindViews() {
        statusText = findViewById(R.id.statusText)
        statusDetail = findViewById(R.id.statusDetail)
        statusDot = findViewById(R.id.statusDot)
        titleStatus = findViewById(R.id.titleStatus)
        hostEdit = findViewById(R.id.hostEdit)
        portEdit = findViewById(R.id.portEdit)
        connectButton = findViewById(R.id.connectButton)
        disconnectButton = findViewById(R.id.disconnectButton)
        scanButton = findViewById(R.id.scanButton)
        scanProgress = findViewById(R.id.scanProgress)
        progressText = findViewById(R.id.progressText)
        foundListLayout = findViewById(R.id.foundListLayout)
        groupFoundTitle = findViewById(R.id.groupFoundTitle)
        emptyView = findViewById(R.id.emptyView)

        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }

        connectButton.setOnClickListener {
            val host = hostEdit.text.toString().trim()
            if (!isValidHost(host)) {
                Toast.makeText(this, R.string.wfp_invalid_host, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // 端口留空或非法时回落到默认 9100，并钳制在合法范围内
            val port = (portEdit.text.toString().trim().toIntOrNull() ?: TcpTransport.DEFAULT_PORT)
                .coerceIn(1, 65535)
            connectTo(host, port)
        }

        disconnectButton.setOnClickListener { btPrintManager.disconnect() }

        scanButton.setOnClickListener { startScan() }
    }

    /**
     * 订阅连接目标：网络打印机显示 host:port；蓝牙连接或断开时视为未连接
     */
    private fun observeConnectionState() {
        lifecycleScope.launch {
            btPrintManager.connectionTarget.collect { target ->
                connectedKey = (target as? ConnectionTarget.NetworkTarget)
                    ?.let { "${it.host}:${it.port}" }
                connectingKey = null
                val connected = connectedKey != null
                statusDot.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(
                        this@WifiPrintActivity,
                        if (connected) R.color.btp_success else R.color.btp_text_secondary
                    )
                )
                titleStatus.text = getString(
                    if (connected) R.string.btp_status_connected else R.string.btp_status_disconnected
                )
                disconnectButton.visibility = if (connected) View.VISIBLE else View.GONE
                refreshFoundRows()
            }
        }
    }

    /**
     * 上次连接的是网络打印机时自动重连（按持久化的连接类型判断，
     * 不能看地址里有没有冒号——蓝牙 MAC 地址同样含冒号）
     */
    private fun autoConnectLastTcp() {
        if (!btPrintManager.isLastTargetTcp()) return
        lifecycleScope.launch {
            if (btPrintManager.autoConnectLastDevice()) {
                Toast.makeText(this@WifiPrintActivity, R.string.btp_toast_auto_connected, Toast.LENGTH_SHORT)
                    .show()
            }
        }
    }

    private fun connectTo(host: String, port: Int) {
        val key = "$host:$port"
        if (key == connectedKey) {
            Toast.makeText(this, R.string.btp_toast_already_connected, Toast.LENGTH_SHORT).show()
            return
        }
        if (key == connectingKey) return

        updateStatus(
            getString(R.string.btp_connecting_to, key),
            getString(R.string.wfp_connecting_detail),
            R.color.btp_warning,
            getString(R.string.btp_status_connecting)
        )
        connectingKey = key
        refreshFoundRows()

        lifecycleScope.launch {
            val connected = btPrintManager.connectTcp(host, port)
            if (connected) {
                updateStatus(
                    getString(R.string.btp_connected_to, key),
                    getString(R.string.wfp_status_ready_detail),
                    R.color.btp_success,
                    getString(R.string.btp_status_connected)
                )
                Toast.makeText(this@WifiPrintActivity, R.string.btp_toast_connect_success, Toast.LENGTH_SHORT)
                    .show()
            } else {
                connectingKey = null
                updateStatus(
                    getString(R.string.btp_toast_connect_failed),
                    getString(R.string.wfp_connect_failed_detail),
                    R.color.btp_error,
                    getString(R.string.btp_status_disconnected)
                )
                Toast.makeText(this@WifiPrintActivity, R.string.btp_toast_connect_failed, Toast.LENGTH_SHORT)
                    .show()
                refreshFoundRows()
            }
        }
    }

    private fun startScan() {
        if (scanJob?.isActive == true) return

        foundListLayout.removeAllViews()
        foundRows.clear()
        groupFoundTitle.visibility = View.GONE
        emptyView.visibility = View.GONE

        scanButton.isEnabled = false
        scanButton.text = getString(R.string.wfp_scanning)
        scanProgress.visibility = View.VISIBLE
        progressText.visibility = View.VISIBLE

        scanJob = lifecycleScope.launch {
            val results = LanPrinterScanner.scan(
                onFound = { printer -> runOnUiThread { addFoundRow(printer) } },
                onProgress = { done, total ->
                    runOnUiThread { progressText.text = getString(R.string.wfp_scan_progress, done, total) }
                }
            )
            scanButton.isEnabled = true
            scanButton.text = getString(R.string.wfp_scan)
            scanProgress.visibility = View.GONE
            progressText.visibility = View.GONE
            updateEmptyVisibility()
            updateStatus(
                getString(R.string.wfp_scan_done, results.size),
                getString(R.string.wfp_status_ready_detail),
                if (results.isEmpty()) R.color.btp_warning else R.color.btp_success,
                titleStatus.text.toString()
            )
        }
    }

    /**
     * 扫描每命中一台打印机立即追加一行，无需等扫描结束
     */
    private fun addFoundRow(printer: LanPrinterScanner.FoundPrinter) {
        val key = "${printer.host}:${printer.port}"
        if (key in foundRows) return

        val row = layoutInflater.inflate(R.layout.item_device, foundListLayout, false)
        row.findViewById<TextView>(R.id.deviceName).text = getString(R.string.wfp_printer_name)
        row.findViewById<TextView>(R.id.deviceAddress).text = key
        row.setOnClickListener { connectTo(printer.host, printer.port) }
        foundListLayout.addView(row)
        foundRows[key] = row
        updateEmptyVisibility()
        refreshFoundRows()
    }

    /**
     * 刷新结果行的徽标状态（已连接高亮 / 连接中 / 可连接）
     */
    private fun refreshFoundRows() {
        foundRows.forEach { (key, row) ->
            val badge = row.findViewById<TextView>(R.id.deviceState)
            when (key) {
                connectedKey -> {
                    row.setBackgroundResource(R.drawable.bg_device_item_connected)
                    badge.setBackgroundResource(R.drawable.bg_badge_green)
                    badge.text = getString(R.string.btp_status_connected)
                }

                connectingKey -> {
                    row.setBackgroundResource(R.drawable.bg_device_item)
                    badge.setBackgroundResource(R.drawable.bg_badge_gray)
                    badge.text = getString(R.string.btp_badge_connecting)
                }

                else -> {
                    row.setBackgroundResource(R.drawable.bg_device_item)
                    badge.setBackgroundResource(R.drawable.bg_badge_primary)
                    badge.text = getString(R.string.btp_badge_connect)
                }
            }
        }
    }

    /**
     * 简单 IPv4 校验：四段数字，每段 0-255
     */
    private fun isValidHost(host: String): Boolean {
        val parts = host.split(".")
        if (parts.size != 4) return false
        return parts.all { p ->
            p.isNotEmpty() && p.length <= 3 && p.all { it.isDigit() } && p.toInt() <= 255
        }
    }

    private fun updateStatus(main: String, detail: String, dotColorRes: Int, title: String) {
        statusText.text = main
        statusDetail.text = detail
        statusDot.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, dotColorRes)
        )
        titleStatus.text = title
    }

    private fun updateEmptyVisibility() {
        val hasFound = foundListLayout.childCount > 0
        groupFoundTitle.visibility = if (hasFound) View.VISIBLE else View.GONE
        emptyView.visibility = if (hasFound) View.GONE else View.VISIBLE
    }

    override fun onDestroy() {
        scanJob?.cancel()
        super.onDestroy()
    }
}
