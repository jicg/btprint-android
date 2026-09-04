package com.jicg.btprint

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import com.jicg.btprint.transport.BleTransport
import com.jicg.btprint.transport.ConnectionTarget
import com.jicg.btprint.transport.PrintTransport
import com.jicg.btprint.transport.SppTransport
import com.jicg.btprint.transport.TcpTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.Charset
import java.util.concurrent.Executors

/**
 * 蓝牙打印管理器
 */
class BtPrintManager private constructor(private val context: Application) {
    private val writeMutex = Mutex()
    /** 串行化 connect/reconnect，与写锁分离：RFCOMM/BLE 连接可能阻塞 10 秒以上，持写锁会冻结整个打印队列 */
    private val connectMutex = Mutex()
    @Volatile private var transport: PrintTransport? = null
    @Volatile private var connected = false
    @Volatile private var connectedDeviceAddress: String? = null
    private var aclReceiver: BroadcastReceiver? = null
    private val _connectedDevice = MutableStateFlow<BluetoothDevice?>(null)
    /** 当前连接的蓝牙设备（TCP 网络打印机时为 null） */
    val connectedDevice: StateFlow<BluetoothDevice?> = _connectedDevice.asStateFlow()
    private val _connectionTarget = MutableStateFlow<ConnectionTarget?>(null)
    /** 当前连接目标（蓝牙设备或网络地址），断开时为 null */
    val connectionTarget: StateFlow<ConnectionTarget?> = _connectionTarget.asStateFlow()
    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()

    companion object {
        private const val TAG = "BtPrintManager"
        private var instance: BtPrintManager? = null
        private const val PREF_NAME = "bt_printer_pref"
        private const val KEY_LAST_DEVICE_ADDRESS = "last_device_address"
        private const val KEY_LAST_DEVICE_TYPE = "last_device_type"
        private const val KEY_PAPER_WIDTH = "paper_width"

        /** 持久化的连接类型 */
        private const val TYPE_SPP = "spp"
        private const val TYPE_BLE = "ble"
        private const val TYPE_TCP = "tcp"

        // 字体大小常量
        const val FONT_SIZE_SMALL = 0    // 小字体
        const val FONT_SIZE_NORMAL = 1   // 正常字体
        const val FONT_SIZE_LARGE = 2    // 大字体

        const val ALIGN_LEFT: Int = 0x00
        const val ALIGN_CENTER: Int = 0x01
        const val ALIGN_RIGHT: Int = 0x02

        // 切纸模式
        const val CUT_FULL = 0     // 全切
        const val CUT_PARTIAL = 1  // 半切（留连接点）

        const val ESC: Byte = 0x1B
        const val GS: Byte = 0x1D

        val CHARSET_GBK = Charset.forName("GBK")
        val FEED_LINE: Int = 0x0A

        /** 图片打印像素上限（按缩放后的打印尺寸计）：抖动处理峰值内存 ≈ 12 字节/像素，超出拒绝打印 */
        const val MAX_PRINT_PIXELS = 2_000_000

        @Synchronized
        fun getInstance(context: Context): BtPrintManager {
            if (instance == null) {
                instance = BtPrintManager(context.applicationContext as Application)
                instance?.registerConnectionMonitor()
            }
            return instance!!
        }
    }

    /**
     * 纸张宽度规格
     * @param charsPerLine 每行字符数（Font A 标准字号，中文按 2 字符宽计）
     * @param dotsPerLine 每行打印点数（图片打印的最大宽度）
     */
    enum class PaperWidth(val charsPerLine: Int, val dotsPerLine: Int) {
        MM_58(32, 384),   // 58mm 小票机
        MM_80(48, 576);   // 80mm 小票机

        companion object {
            fun fromName(name: String?): PaperWidth =
                entries.firstOrNull { it.name == name } ?: MM_58
        }
    }

    /**
     * 多列排版（printTwo/printThree）超宽处理的全局开关：
     * false（默认）保持旧行为——超宽列掉到下一行；
     * true 启用表格模式——列内折行、多列超长均分空间（见 ColumnLayout）
     * 也可通过 PrintUtils.setColumnWrapEnabled 设置
     */
    @Volatile
    var columnWrapEnabled: Boolean = false

    /**
     * 当前纸张宽度（默认 58mm），影响 printTwo/printThree/printDivider 的行宽
     * 与 printImage 的最大打印宽度；设置后持久化，下次启动自动恢复
     */
    var paperWidth: PaperWidth = loadPaperWidth()
        set(value) {
            field = value
            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_PAPER_WIDTH, value.name)
                .apply()
        }

    private fun loadPaperWidth(): PaperWidth {
        val name = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PAPER_WIDTH, null)
        return PaperWidth.fromName(name)
    }


    /**
     * 保存最后连接的设备地址
     */
    private fun saveLastTarget(type: String, address: String) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_DEVICE_ADDRESS, address)
            .putString(KEY_LAST_DEVICE_TYPE, type)
            .apply()
    }

    /**
     * 获取最后连接的设备地址
     */
    fun getLastDeviceAddress(): String? {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LAST_DEVICE_ADDRESS, null)
    }

    /**
     * 自动连接上次的设备（按上次成功时使用的传输类型：SPP / BLE / TCP）
     */
    suspend fun autoConnectLastDevice(): Boolean = withContext(Dispatchers.IO) {
        try {
            val lastAddress = getLastDeviceAddress() ?: return@withContext false
            when (getLastDeviceType()) {
                TYPE_BLE -> {
                    val device = bluetoothAdapter?.getRemoteDevice(lastAddress)
                        ?: return@withContext false
                    connectBle(device)
                }
                TYPE_TCP -> {
                    val sep = lastAddress.lastIndexOf(':')
                    if (sep <= 0) return@withContext false
                    val host = lastAddress.substring(0, sep)
                    val port = lastAddress.substring(sep + 1).toIntOrNull()
                        ?: return@withContext false
                    connectTcp(host, port)
                }
                else -> {
                    val device = bluetoothAdapter?.getRemoteDevice(lastAddress)
                        ?: return@withContext false
                    connect(device)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "自动连接上次设备失败", e)
            return@withContext false
        }
    }

    private fun getLastDeviceType(): String {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LAST_DEVICE_TYPE, TYPE_SPP) ?: TYPE_SPP
    }

    /** 上次成功连接的传输类型是否为网络打印机（TCP），供 WiFi 连接页判断是否自动重连 */
    fun isLastTargetTcp(): Boolean = getLastDeviceType() == TYPE_TCP

    /**
     * 连接蓝牙设备（经典蓝牙 SPP 通道，兼容旧行为）
     * @param device 蓝牙设备
     * @return 是否连接成功
     *
     * 说明：连接期间并发打印会快速失败（IOException("打印机未连接")）而不是阻塞等待，
     * 这是有意设计——避免 RFCOMM 连接耗时冻结整个打印队列。
     */
    suspend fun connect(device: BluetoothDevice): Boolean =
        connectTransport(SppTransport(context, device), TYPE_SPP, device.address)

    /**
     * 连接 BLE（低功耗蓝牙）打印机
     * 自动查找常见透传 characteristic 并按 MTU 分包写入
     */
    suspend fun connectBle(device: BluetoothDevice): Boolean =
        connectTransport(BleTransport(context, device), TYPE_BLE, device.address)

    /**
     * 连接网络打印机（Wi-Fi / 以太网，ESC/POS over TCP）
     * @param host 打印机 IP 或域名
     * @param port 端口，默认 9100
     */
    suspend fun connectTcp(host: String, port: Int = TcpTransport.DEFAULT_PORT): Boolean =
        connectTransport(TcpTransport(host, port), TYPE_TCP, "$host:$port")

    /**
     * 连接超时时间（毫秒，默认 20 秒）。
     * 部分机型 RFCOMM 的 connect() 没有系统超时且协程取消无法打断，超时后传输层被关闭并返回失败，
     * 阻塞中的连接线程由 close() 解除
     */
    @Volatile
    var connectTimeoutMs: Long = 20_000

    /**
     * 专用连接线程：阻塞式 connect 统一在此执行，超时时可连同线程一起放弃
     */
    private val connectDispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "btprint-connect").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    /**
     * 传输层统一连接入口：串行化连接、互斥发布、失败收尾
     */
    private suspend fun connectTransport(
        t: PrintTransport,
        type: String,
        address: String,
    ): Boolean = withContext(Dispatchers.IO) {
        connectMutex.withLock {
            // 尚未发布的传输层：连接中途失败时负责关闭，避免资源泄漏
            var pending: PrintTransport? = t
            try {
                t.onDisconnected = { handleUnexpectedDisconnect() }
                // 先关闭旧连接，避免重复连接泄漏旧通道
                writeMutex.withLock { closeInternal() }
                // 阻塞式 connect 放到专用线程并加超时兜底：超时时 pending.close() 会
                // 让阻塞中的 connect 抛出退出，不会冻结后续所有连接与打印
                val established = withTimeoutOrNull(connectTimeoutMs) {
                    withContext(connectDispatcher) { t.connect() }
                }
                if (established == null) {
                    Log.e(TAG, "连接打印机超时（${connectTimeoutMs}ms）")
                    pending?.close()
                    writeMutex.withLock { closeInternal() }
                    return@withContext false
                }
                // 发布新连接：与写入互斥，避免写入方读到半初始化的通道
                writeMutex.withLock {
                    transport = t
                    connected = true
                    connectedDeviceAddress = address
                    _connectedDevice.value =
                        (t.target as? ConnectionTarget.BluetoothTarget)?.device
                    _connectionTarget.value = t.target
                    pending = null
                }
                saveLastTarget(type, address)
                true
            } catch (e: IOException) {
                Log.e(TAG, "连接打印机失败", e)
                pending?.close()
                writeMutex.withLock { closeInternal() }
                false
            } catch (e: Exception) {
                Log.e(TAG, "连接打印机失败", e)
                pending?.close()
                writeMutex.withLock { closeInternal() }
                false
            }
        }
    }

    /**
     * 传输层主动上报连接断开（如 BLE 链路丢失）：与 close() 同款兜底收尾
     */
    private fun handleUnexpectedDisconnect() {
        Log.i(TAG, "传输层上报连接断开")
        close()
    }

    /**
     * 断开连接
     */
    fun disconnect() {
        close()
    }

    suspend fun initPrinter() {
        write(byteArrayOf(0x1B, 0x40))
    }

    /**
     * 设置字体
     * @param width 宽度 (0-7)
     * @param height 高度 (0-7)
     * @param bold 粗体 (0-1)
     * @param underline 下划线 (0-1)
     * @return 0:成功, 1:宽度参数错误, 2:高度参数错误, 3:粗体参数错误, 4:下划线参数错误
     */
    @Deprecated(
        message = "setFont 的字号/粗体设置会残留在打印机状态里，与 printText 内部的字体命令冲突，打印效果不可预期；" +
            "请改用 printText 的 fontSize / bold 参数（每次打印自带设置与恢复）"
    )
    suspend fun setFont(width: Int, height: Int, bold: Int, underline: Int): Int =
        withContext(Dispatchers.IO) {
            try {
                // 参数检查
                if (width !in 0..7) return@withContext 1
                if (height !in 0..7) return@withContext 2
                if (bold !in 0..1) return@withContext 3
                if (underline !in 0..1) return@withContext 4

                // 设置字体样式（粗体和下划线）
                var style = 0
                style = style or (bold shl 3)
                style = style or (underline shl 7)
                write(byteArrayOf(27, 33, style.toByte()))

                // 设置字体大小（宽度和高度）
                var size = 0
                size = size or (width shl 4)
                size = size or height
                write(byteArrayOf(29, 33, size.toByte()))

                Log.d(
                    TAG,
                    "设置字体成功: width=$width, height=$height, bold=$bold, underline=$underline"
                )
                return@withContext 0
            } catch (e: Exception) {
                Log.e(TAG, "设置字体失败: ${e.message}")
                e.printStackTrace()
                throw e
            }
        }

    /**
     * 打印文本
     * @param text 文本内容
     * @param fontSize 字体大小 (0:小字体, 1:正常字体, 2:大字体)
     * @param align 对齐方式
     * @param feedLines 换行数
     * @param bold 粗体，仅对本次文本生效（任务末尾自动恢复，不影响后续打印）
     */
    suspend fun printText(
        text: String,
        fontSize: Int = FONT_SIZE_NORMAL,
        align: Int = ALIGN_LEFT,
        feedLines: Int = 1,
        bold: Boolean = false
    ) {
        // 参数校验在协程切换之前执行，避免校验异常在 IO 线程抛出
        require(align in setOf(ALIGN_LEFT, ALIGN_CENTER, ALIGN_RIGHT)) {
            "Invalid alignment value: $align"
        }
        require(fontSize in setOf(FONT_SIZE_SMALL, FONT_SIZE_NORMAL, FONT_SIZE_LARGE)) {
            "Invalid font size value: $fontSize"
        }
        require(feedLines >= 0) { "Invalid feed lines value: $feedLines" }

        withContext(Dispatchers.IO) {
            try {
                // 合并多次写入以提高性能
                val outputBuffer = ByteArrayOutputStream().apply {
                    write(byteArrayOf(ESC, 0x61, align.toByte()))
                    // 设置字体大小
                    when (fontSize) {
                        FONT_SIZE_SMALL -> {
                            // 小字体
                            write(byteArrayOf(ESC, 0x21, 0x01)) // 小字体
                            write(byteArrayOf(ESC, 0x4D, 0))
                        }

                        FONT_SIZE_NORMAL -> {
                            // 正常字体
                            write(byteArrayOf(ESC, 0x21, 0x00)) // 正常大小
                            write(byteArrayOf(ESC, 0x4D, 0))
                        }

                        FONT_SIZE_LARGE -> {
                            // 大字体
                            write(byteArrayOf(ESC, 0x21, 0x33)) // 大字体
                            write(byteArrayOf(ESC, 0x4D, 0))
                        }
                    }

                    // 粗体（ESC E），仅对本次文本生效，字号命令在前不受影响
                    if (bold) {
                        write(byteArrayOf(27, 0x45, 0x01))
                    }

                    // 写入文本（提取GBK编码为常量）
                    write(text.toByteArray(CHARSET_GBK))

//                // 换行（支持多行）
                    repeat(feedLines) {
                        write(0x0A) // 使用换行符
                    }

                    // 恢复粗体设置（ESC E 会保持生效直到显式关闭），避免泄漏到后续打印任务
                    if (bold) {
                        write(byteArrayOf(27, 0x45, 0x00))
                    }
                }

                // 单次IO写入操作
                write(outputBuffer.toByteArray())

                Log.d(TAG, "打印文本成功 (${text.length} 字符)")
            } catch (e: Exception) {
                Log.e(TAG, "打印失败 | 错误类型: ${e.javaClass.simpleName} | 详情: ${e.message}")
                throw e // 根据需求决定是否抛出
            }
        }
    }

    /**
     * 打印两列文本
     * @param text1 左侧文本
     * @param text2 右侧文本
     * @param fontSize 字体大小
     */
    suspend fun printTwo(
        text1: String,
        text2: String,
        fontSize: Int = FONT_SIZE_NORMAL
    ): Unit = withContext(Dispatchers.IO) {
        require(fontSize in setOf(FONT_SIZE_SMALL, FONT_SIZE_NORMAL, FONT_SIZE_LARGE)) {
            "Invalid font size value: $fontSize"
        }
        try {
            // 设置字体大小（ESC ! n：与 printThree 一致的字号映射，默认 NORMAL 避免继承残留字号）
            write(byteArrayOf(ESC, 0x21, when (fontSize) {
                FONT_SIZE_SMALL -> 0x01
                FONT_SIZE_NORMAL -> 0x00
                else -> 0x33
            }.toByte()))
            write(byteArrayOf(ESC, 0x4D, 0))
            // 两列排版依赖手动补空格，左对齐语义最准确（居中会让超宽兜底的两行都居中）
            write(byteArrayOf(ESC, 0x61, ALIGN_LEFT.toByte()))
            // 行宽按纸张与字号换算：大字体为倍宽模式，一行只能容纳一半字符
            val totalWidth = if (fontSize == FONT_SIZE_LARGE) {
                paperWidth.charsPerLine / 2
            } else {
                paperWidth.charsPerLine
            }
            val w1 = ColumnLayout.textWidth(text1)
            val w2 = ColumnLayout.textWidth(text2)
            // 默认模式：单行放不下时右列掉到下一行；表格模式（columnWrapEnabled）：列内折行
            val lines = when {
                w1 + w2 <= totalWidth ->
                    listOf(text1 + " ".repeat((totalWidth - w1 - w2).coerceAtLeast(0)) + text2)
                columnWrapEnabled -> ColumnLayout.renderTwo(text1, text2, totalWidth)
                else -> listOf(text1, text2)
            }
            write(lines.joinToString("\n").toByteArray(CHARSET_GBK))
            write(byteArrayOf(FEED_LINE.toByte()))
        } catch (e: Exception) {
            Log.e(TAG, "打印两列文本失败", e)
            throw e
        }
    }

    /**
     * 打印三列文本
     * @param text1 左侧文本
     * @param text2 中间文本
     * @param text3 右侧文本
     * @param fontSize 字体大小
     */
    suspend fun printThree(text1: String, text2: String, text3: String, fontSize: Int) {
        require(fontSize in setOf(FONT_SIZE_SMALL, FONT_SIZE_NORMAL, FONT_SIZE_LARGE)) {
            "Invalid font size value: $fontSize"
        }
        withContext(Dispatchers.IO) {
            try {
                // 设置字体大小（ESC ! n：与 printText 一致的字号映射，替代错误的 ESC E 加粗命令）
                write(byteArrayOf(ESC, 0x21, when (fontSize) {
                    FONT_SIZE_SMALL -> 0x01
                    FONT_SIZE_NORMAL -> 0x00
                    else -> 0x33
                }.toByte()))
                write(byteArrayOf(ESC, 0x4D, 0))
                // 显式声明左对齐：三列排版按整行计算空隙，若继承上一次任务的居中对齐会整体错位
                write(byteArrayOf(ESC, 0x61, ALIGN_LEFT.toByte()))

                // 行宽按纸张与字号换算：大字体为倍宽模式，一行只能容纳一半字符
                val totalWidth = if (fontSize == FONT_SIZE_LARGE) {
                    paperWidth.charsPerLine / 2
                } else {
                    paperWidth.charsPerLine
                }
                val w1 = ColumnLayout.textWidth(text1)
                val w2 = ColumnLayout.textWidth(text2)
                val w3 = ColumnLayout.textWidth(text3)
                // 默认模式：超宽时左列掉到下一行；表格模式（columnWrapEnabled）：列内折行、多列超行均分空间
                val lines = when {
                    w1 + w2 + w3 <= totalWidth || !columnWrapEnabled ->
                        legacyThreeColumnLines(text1, text2, text3, totalWidth)
                    else -> ColumnLayout.renderThree(text1, text2, text3, totalWidth)
                }
                write(lines.joinToString("\n").toByteArray(CHARSET_GBK))
                // 换行
                write(byteArrayOf(FEED_LINE.toByte()))
                Log.d(TAG, "打印三列文本成功")
            } catch (e: Exception) {
                Log.e(TAG, "打印三列文本失败: ${e.message}")
                e.printStackTrace()
                throw e
            }
        }
    }

    /**
     * 三列排版旧版算法（columnWrapEnabled = false 时使用）
     * 单行放不下时左列独占第一行，中右列排在第二行，不截断
     */
    private fun legacyThreeColumnLines(
        text1: String,
        text2: String,
        text3: String,
        totalWidth: Int,
    ): List<String> {
        val leftLength = ColumnLayout.textWidth(text1)
        val middleLength = ColumnLayout.textWidth(text2)
        val rightLength = ColumnLayout.textWidth(text3)

        var remLength = totalWidth - rightLength - leftLength - middleLength

        // 如果空间不足，左列换行
        val leftDropped = remLength < 0
        if (leftDropped) {
            remLength = totalWidth - middleLength - rightLength
        }

        val size = (remLength / 1).coerceAtLeast(0)
        val widthPixelMid = totalWidth / 2
        val widthPixelMidRight = totalWidth - widthPixelMid

        // 两种空格分配策略（左列较短时优先把中列推到行中，否则按剩余空间比例分配）
        val line: String = if (leftLength < widthPixelMid &&
            widthPixelMidRight > (2 + middleLength / 2 + rightLength)
        ) {
            val leftSize = (widthPixelMid - leftLength - middleLength / 2).coerceAtLeast(0)
            val rightSize = (size - leftSize).coerceAtLeast(0)
            " ".repeat(leftSize) + text2 + " ".repeat(rightSize) + text3
        } else {
            // 先乘后除：Int 除法 widthPixelMid / totalWidth 恒为 0
            val leftSize = (size * widthPixelMid / totalWidth).coerceAtLeast(0)
            val rightSize = (size - leftSize).coerceAtLeast(0)
            " ".repeat(leftSize) + text2 + " ".repeat(rightSize) + text3
        }
        return if (leftDropped) listOf(text1, line) else listOf(text1 + line)
    }

    /**
     * 打印标题（分割线 + 标题 + 分割线，三步作为一次调用，不被其他打印任务插入打断）
     * @param text 标题文本
     * @param fontSize 字体大小，默认大字体
     * @param align 对齐方式，默认居中
     * @param dividerChar 分割线字符
     * @param dividerLength 分割线长度（字符数）；传 -1（默认）时自动使用当前纸宽的整行字符数
     */
    suspend fun printTitle(
        text: String,
        fontSize: Int = FONT_SIZE_LARGE,
        align: Int = ALIGN_CENTER,
        dividerChar: String = "=",
        dividerLength: Int = -1
    ) {
        printDivider(dividerChar, dividerLength)
        printText(text, fontSize, align, 1)
        printDivider(dividerChar, dividerLength)
    }

    /**
     * 打印分割线
     * 方法结束后对齐方式恢复为左对齐，避免居中对齐泄漏到后续打印任务
     * @param char 分割线字符
     * @param length 长度（字符数）；传 -1（默认）时自动使用当前纸宽的整行字符数
     */
    suspend fun printDivider(char: String = "-", length: Int = -1) = withContext(Dispatchers.IO) {
        try {
            // 设置对齐方式
            write(byteArrayOf(ESC, 0x61, ALIGN_CENTER.toByte()))

            // 空字符防护：char 为空时回退默认 "-"，避免 char[0] 越界
            val dividerChar = char.ifEmpty { "-" }

            // 计算实际字符宽度（考虑中文字符）
            val charWidth = if (dividerChar[0].code > 127) 2 else 1
            val lineWidth = if (length > 0) length else paperWidth.charsPerLine
            val actualLength = (lineWidth / charWidth).coerceAtLeast(1)

            // 打印分割线
            write(dividerChar.repeat(actualLength).toByteArray(CHARSET_GBK))
            // 换行
            write(byteArrayOf(FEED_LINE.toByte()))
            // 恢复左对齐，防止居中对齐残留到后续任务
            write(byteArrayOf(ESC, 0x61, ALIGN_LEFT.toByte()))
        } catch (e: Exception) {
            Log.e(TAG, "打印分割线失败", e)
            throw e
        }
    }

    /**
     * 切纸（先走纸再切刀；无切刀的机型会忽略切刀命令）
     * @param mode CUT_FULL 全切 / CUT_PARTIAL 半切（留连接点）
     * @param feedLines 切纸前走纸行数，避免最后一段内容被切刀裁到
     */
    suspend fun cutPaper(mode: Int = CUT_PARTIAL, feedLines: Int = 3) =
        withContext(Dispatchers.IO) {
            require(mode == CUT_FULL || mode == CUT_PARTIAL) { "Invalid cut mode: $mode" }
            require(feedLines >= 0) { "Invalid feed lines value: $feedLines" }
            try {
                ensureTransport()
                if (feedLines > 0) {
                    write(ByteArray(feedLines) { FEED_LINE.toByte() })
                }
                // GS V m：m=0 全切，m=1 半切
                write(byteArrayOf(GS, 0x56, mode.toByte()))
                Log.d(TAG, "切纸命令已发送: mode=$mode")
            } catch (e: Exception) {
                Log.e(TAG, "切纸失败: ${e.message}")
                throw e
            }
        }

    /**
     * 打开钱箱（向钱箱接口输出脉冲，仅带钱箱接口的机型有效）
     * @param pin 钱箱引脚：0=2 号引脚，1=5 号引脚
     * @param onTime 脉冲开启时间（单位 2ms，1-255）
     * @param offTime 脉冲关闭时间（单位 2ms，1-255）
     */
    suspend fun openCashDrawer(pin: Int = 0, onTime: Int = 25, offTime: Int = 250) =
        withContext(Dispatchers.IO) {
            require(pin in 0..1) { "Invalid cash drawer pin: $pin" }
            try {
                ensureTransport()
                // ESC p m t1 t2
                write(
                    byteArrayOf(
                        ESC, 0x70, pin.toByte(),
                        onTime.coerceIn(1, 255).toByte(),
                        offTime.coerceIn(1, 255).toByte()
                    )
                )
                Log.d(TAG, "开钱箱命令已发送: pin=$pin")
            } catch (e: Exception) {
                Log.e(TAG, "开钱箱失败: ${e.message}")
                throw e
            }
        }

    /**
     * 打印二维码
     * @param text 二维码内容（中文按 GBK 字节编码，与文本打印一致）
     * @param width 模块尺寸 (1-16)
     * @param height 纠错等级（48=7%, 49=15%, 50=25%, 51=30%）；
     *   为兼容旧签名保留参数名，旧代码传的 1-16 会被钳制为合法值
     * @param align 对齐方式
     */
    suspend fun printQrCode(
        text: String,
        width: Int = 4,
        height: Int = 49,
        align: Int = ALIGN_CENTER
    ) {
        require(align in setOf(ALIGN_LEFT, ALIGN_CENTER, ALIGN_RIGHT)) {
            "Invalid alignment value: $align"
        }
        // GBK 字节编码：中文内容不再被 US_ASCII 静默转成 '?'；
        // 长度校验放在协程切换前，上限来自 pL/pH 的 16 位参数长度
        val data = text.toByteArray(CHARSET_GBK)
        require(data.size <= 65532) { "二维码内容过长（${data.size} 字节，最大 65532）" }
        withContext(Dispatchers.IO) {
            try {
                ensureTransport()

                // 设置对齐方式
                write(byteArrayOf(ESC, 0x61, align.toByte()))

            val dataLength = data.size

            // 设置二维码参数
            // 模块尺寸（fn 0x43，二维码为正方形，无"高度"概念）
            write(byteArrayOf(29, 40, 107, 3, 0, 49, 67, width.coerceIn(1, 16).toByte()))
            // 纠错等级（fn 0x45，合法值仅 48-51）
            write(byteArrayOf(29, 40, 107, 3, 0, 49, 69, height.coerceIn(48, 51).toByte()))

            // 创建二维码数据命令
            // pL/pH = (dataLength + 3) 的小端拆分，必须整体计算再拆分，避免 pL 进位丢失
            val command = ByteArray(dataLength + 8)
            command[0] = 29  // GS
            command[1] = 40  // (
            command[2] = 107 // k
            command[3] = ((dataLength + 3) % 256).toByte()
            command[4] = ((dataLength + 3) / 256).toByte()
            command[5] = 49  // 1
            command[6] = 80  // P
            command[7] = 48  // 0
            System.arraycopy(data, 0, command, 8, dataLength)

            // 写入二维码数据
            write(command)

            // 打印二维码
            write(byteArrayOf(29, 40, 107, 3, 0, 49, 81, 48))

            // 换行
            write(byteArrayOf(27, 100, 1))
            // 恢复左对齐，防止居中对齐残留到后续任务
            write(byteArrayOf(ESC, 0x61, ALIGN_LEFT.toByte()))

            Log.d(TAG, "打印二维码成功 (${data.size} 字节)")
        } catch (e: Exception) {
            Log.e(TAG, "打印二维码失败: ${e.message}")
            e.printStackTrace()
            throw e
        }
        }
    }

    /**
     * 打印条形码
     * @param text 条形码内容
     * @param width 宽度 (2-6)
     * @param height 高度 (1-255)
     * @param align 对齐方式
     * @param barcodeType 条形码类型
     */
    suspend fun printBarCode(
        text: String,
        width: Int = 3,
        height: Int = 162,
        align: Int = ALIGN_CENTER,
        barcodeType: BarcodeType = BarcodeType.CODE128
    ) {
        require(align in setOf(ALIGN_LEFT, ALIGN_CENTER, ALIGN_RIGHT)) {
            "Invalid alignment value: $align"
        }
        // 参数校验在协程切换之前执行，避免校验异常在 IO 线程抛出；
        // 按条码类型校验内容格式，非法内容直接拒绝，避免向打印机发送无效命令
        require(text.isNotEmpty()) { "条码内容不能为空" }
        require(text.length <= 255) { "条码内容过长（最大 255 字符）" }
        when (barcodeType) {
            BarcodeType.EAN13 ->
                require(text.matches(Regex("\\d{13}"))) { "EAN-13 条码必须是 13 位数字" }

            BarcodeType.EAN8 ->
                require(text.matches(Regex("\\d{8}"))) { "EAN-8 条码必须是 8 位数字" }

            BarcodeType.UPC_A ->
                require(text.matches(Regex("\\d{12}"))) { "UPC-A 条码必须是 12 位数字" }

            BarcodeType.UPC_E ->
                require(text.matches(Regex("\\d{8}"))) { "UPC-E 条码必须是 8 位数字" }

            BarcodeType.ITF -> {
                require(text.all { it.isDigit() }) { "ITF 条码只能包含数字" }
                require(text.length % 2 == 0) { "ITF 条码长度必须为偶数" }
            }

            BarcodeType.CODE39 ->
                require(text.matches(Regex("[0-9A-Z $%+\\-./]+"))) {
                    "CODE39 条码只能包含数字、大写字母和空格 \$ % + - . /"
                }

            BarcodeType.CODABAR ->
                require(text.matches(Regex("[0-9A-D $+\\-/:.]+"))) {
                    "CODABAR 条码只能包含数字、A-D 和空格 \$ + - / : ."
                }

            BarcodeType.CODE128, BarcodeType.ONE_CODE93 ->
                require(text.all { it.code in 0..127 }) { "条码内容必须是 ASCII 字符（0-127）" }
        }
        withContext(Dispatchers.IO) {
            try {
                ensureTransport()

            // 设置对齐方式
            write(byteArrayOf(ESC, 0x61, align.toByte()))

            // 转换文本为字节数组（使用ASCII编码）
            val data = text.toByteArray(Charsets.US_ASCII)

            // 创建命令数组
            val command = when (barcodeType) {
                BarcodeType.CODABAR -> {
                    // Codabar 需要额外的起始和结束字符
                    ByteArray(data.size + 16)
                }

                BarcodeType.CODE128, BarcodeType.ONE_CODE93 -> {
                    ByteArray(data.size + 14)
                }

                else -> {
                    ByteArray(data.size + 13)
                }
            }

            // 设置条形码宽度 (2-6)
            command[0] = 29
            command[1] = 0x77
            // CODE39 的宽度需要特殊处理
            command[2] = when (barcodeType) {
                BarcodeType.CODE39 -> 2  // CODE39 固定使用宽度 2
                else -> width.coerceIn(2, 6).toByte()
            }

            // 设置条形码高度 (1-255)
            command[3] = 29
            command[4] = 0x68
            command[5] = height.coerceIn(1, 255).toByte()

            // 设置条形码类型
            command[6] = 29
            command[7] = 0x48
            command[8] = barcodeType.level.toByte()

            // 发送条形码命令
            command[9] = 29
            command[10] = 0x6B

            // 根据条形码类型设置命令
            when (barcodeType) {
                BarcodeType.CODABAR -> {
                    command[11] = 0x47
                    command[12] = (text.length + 2).toByte()
                    command[13] = 0x41  // 起始字符
                    System.arraycopy(data, 0, command, 14, data.size)
                    command[14 + data.size] = 0x41  // 结束字符
                }

                BarcodeType.CODE128, BarcodeType.ONE_CODE93 -> {
                    command[11] = barcodeType.code.toByte()
                    command[12] = text.length.toByte()
                    System.arraycopy(data, 0, command, 13, data.size)
                }

                else -> {
                    command[11] = barcodeType.code.toByte()
                    System.arraycopy(data, 0, command, 12, data.size)
                }
            }

            // 写入命令
            write(command)

            // 换行并恢复左对齐，防止居中对齐残留到后续任务
            write(byteArrayOf(27, 100, 1))
            write(byteArrayOf(ESC, 0x61, ALIGN_LEFT.toByte()))

            Log.d(TAG, "打印条形码成功 (${data.size} 字节)")
        } catch (e: Exception) {
            Log.e(TAG, "打印条形码失败: ${e.message}")
            e.printStackTrace()
            throw e
        }
        }
    }

    /**
     * 条形码类型枚举
     */
    enum class BarcodeType(val code: Int, val level: Int = 0) {
        CODE128(0x49),    // Code 128
        EAN13(0x02),      // EAN-13
        EAN8(0x03),       // EAN-8
        UPC_A(0x00),      // UPC-A
        UPC_E(0x01),      // UPC-E
        CODE39(0x04, 2),  // Code 39 (level=2)
        ITF(0x05),        // ITF
        ONE_CODE93(0x48),    // ONE_CODE93
        CODABAR(0x06, 2); // Codabar (level=2)
    }

    /**
     * 打印图片
     * @param bitmap 图片
     * @param width 目标宽度（像素），自动按 8 点向上对齐；传 0（默认）时按当前纸宽打印
     * @param height 保留参数，暂未使用；实际高度始终按图片宽高比推导
     * @param align 对齐方式
     */
    suspend fun printImage(
        bitmap: Bitmap,
        width: Int = 0,
        height: Int = 200,
        align: Int = ALIGN_CENTER.toInt()
    ) = withContext(Dispatchers.IO) {
        try {
            // 目标宽度：width<=0 时取当前纸宽；钳制在可打印点数内，再按 8 点（1 字节）向上取整
            val requestedWidth = if (width <= 0) paperWidth.dotsPerLine else width
            val targetWidthPx = (requestedWidth.coerceAtMost(paperWidth.dotsPerLine) + 7) / 8 * 8
            // 计算实际高度（保持宽高比）；极端宽图（如横幅长条）除法可能得 0，钳到 1 避免后续压缩参数非法
            val actualHeight = (bitmap.height * targetWidthPx / bitmap.width).coerceAtLeast(1)

            // 打印前像素上限校验：抖动处理峰值内存 ≈ 12 字节/像素，超出直接拒绝（不发送任何字节），
            // 避免低端机 OOM 崩溃；提示调用方压缩图片后重打
            val pixels = targetWidthPx.toLong() * actualHeight
            if (pixels > MAX_PRINT_PIXELS) {
                throw IllegalArgumentException(
                    "图片过大（打印尺寸约 ${targetWidthPx}x$actualHeight，超过 $MAX_PRINT_PIXELS 像素），请压缩后再打印"
                )
            }

            ensureTransport()

            // 设置对齐方式
            write(byteArrayOf(ESC, 0x61, align.toByte()))

            // 压缩图片
            val compressedBitmap = ImageUtils.compressBitmap(bitmap, targetWidthPx, actualHeight)
            // 使用抖动算法处理图片
            val buffer = ImageUtils.ditherImage(compressedBitmap)

            // 发送图片数据
            // 设置行间距为0
            write(byteArrayOf(ESC, 0x33, 0))

            // 发送 GS v 0 命令
            // 包头尺寸以实际产出的位图为准（compressBitmap 失败会整体抛错，不存在尺寸不一致的回退路径）
            val bytesPerLine = (compressedBitmap.width + 7) / 8
            val imageHeight = compressedBitmap.height
            val command = byteArrayOf(
                GS, 0x76, 0x30, 0,  // GS v 0 命令
                (bytesPerLine % 256).toByte(),  // 宽度低字节（单位：字节）
                (bytesPerLine / 256).toByte(),  // 宽度高字节
                (imageHeight % 256).toByte(),   // 高度低字节（单位：点）
                (imageHeight / 256).toByte()    // 高度高字节
            )
            write(command)

            // 分块写入图片数据（8KB 缓存，避免一次性拷贝整个 buffer）
            val cacheSize = 1024 * 8 // 8KB 缓存
            val cache = ByteArrayOutputStream(cacheSize)
            val totalBytes = buffer.size
            var currentPosition = 0

            while (currentPosition < totalBytes) {
                val bytesToWrite = minOf(cacheSize - cache.size(), totalBytes - currentPosition)
                cache.write(buffer, currentPosition, bytesToWrite)
                currentPosition += bytesToWrite

                // 缓存满即写入
                if (cache.size() >= cacheSize) {
                    write(cache.toByteArray())
                    cache.reset()
                }
            }

            // 写入剩余的缓存数据
            if (cache.size() > 0) {
                write(cache.toByteArray())
                cache.reset()
            }

            // 换行
            write(byteArrayOf(FEED_LINE.toByte()))

            // 恢复行间距
            write(byteArrayOf(ESC, 0x33, 24))
            // 恢复左对齐，防止居中对齐残留到后续任务
            write(byteArrayOf(ESC, 0x61, ALIGN_LEFT.toByte()))

            Log.d(TAG, "打印图片成功")
        } catch (oom: OutOfMemoryError) {
            // 像素上限之外的极端内存压力（如整机内存不足）：任务失败而不是崩掉进程
            Log.e(TAG, "打印图片失败: 内存不足", oom)
            throw IOException("打印图片内存不足，请压缩图片后重试")
        } catch (e: Exception) {
            Log.e(TAG, "打印图片失败: ${e.message}")
            e.printStackTrace()
            throw e
        }
    }

    /**
     * 检查打印机连接状态（SPP / BLE / TCP 通用）
     * 纯内存状态判断，不向打印机写入任何字节
     */
    suspend fun isConnected(): Boolean {
        val t = transport ?: return false
        return t.isConnected && connected
    }

    /**
     * 重新连接蓝牙设备（经典 SPP）
     * 与 connect 相同的锁策略：连接期间不持写锁，并发打印快速失败
     */
    suspend fun reconnect(device: BluetoothDevice): Boolean =
        connectTransport(SppTransport(context, device, cancelDiscovery = false), TYPE_SPP, device.address)

    /**
     * 关闭连接（非挂起签名不变，Mutex tryLock 避免阻塞调用方）
     */
    fun close() {
        if (writeMutex.tryLock()) {
            try {
                closeInternal()
            } finally {
                writeMutex.unlock()
            }
        } else {
            // 有写入进行中：直接关闭底层资源，写入方会捕获 IOException 兜底
            closeInternal()
        }
    }

    /**
     * 实际关闭逻辑（调用方需自行保证与 writeMutex 的互斥）
     */
    private fun closeInternal() {
        val t = transport
        transport = null
        try {
            t?.close()
        } catch (e: Exception) {
            Log.e(TAG, "关闭连接失败", e)
        } finally {
            connected = false
            connectedDeviceAddress = null
            _connectedDevice.value = null
            _connectionTarget.value = null
        }
    }

    /**
     * 取出当前传输层并校验可用性，未连接时抛 [IOException]
     */
    private fun ensureTransport(): PrintTransport {
        val t = transport
        if (t == null || !t.isConnected || !connected) {
            throw IOException("打印机未连接")
        }
        return t
    }

    /**
     * 注册 ACL 连接状态广播监听（getInstance 时自动注册）
     * 打印机被系统判定断开（断电/超出范围）时自动清理连接资源，
     * 缩短"UI 显示已连接但实际已断开"的窗口期
     */
    fun registerConnectionMonitor() {
        if (aclReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val action = intent.action ?: return
                if (action != BluetoothDevice.ACTION_ACL_CONNECTED &&
                    action != BluetoothDevice.ACTION_ACL_DISCONNECT_REQUESTED &&
                    action != BluetoothDevice.ACTION_ACL_DISCONNECTED
                ) {
                    return
                }
                @Suppress("DEPRECATION")
                val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                if (action == BluetoothDevice.ACTION_ACL_CONNECTED) {
                    Log.i(TAG, "ACL 已连接: ${device.name ?: "未知设备"}")
                    return
                }
                // Android 12+ 读取设备地址需要 BLUETOOTH_CONNECT 权限
                val address = try {
                    device.address
                } catch (e: SecurityException) {
                    Log.w(TAG, "无 BLUETOOTH_CONNECT 权限，无法读取断开设备地址", e)
                    return
                }
                if (address == connectedDeviceAddress) {
                    Log.i(TAG, "检测到打印机断开: $address")
                    // 与 close() 无锁分支同款兜底：写入中的协程靠 IOException 收尾
                    closeInternal()
                }
            }
        }
        aclReceiver = receiver
        try {
            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECT_REQUESTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            }
            registerSystemReceiver(receiver, filter)
            Log.i(TAG, "ACL 连接状态监听已注册")
        } catch (e: SecurityException) {
            Log.e(TAG, "注册 ACL 监听失败: 缺少蓝牙权限", e)
            aclReceiver = null
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
     * 注销 ACL 广播监听（通常无需手动调用）
     */
    fun unregisterConnectionMonitor() {
        aclReceiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (e: Exception) {
                Log.e(TAG, "注销 ACL 监听失败", e)
            }
            aclReceiver = null
        }
    }

    /**
     * 单次写入超时时间（毫秒，默认 10 秒）。
     * 超时视为连接故障：当前连接被关闭并抛出 [IOException]，避免坏死的 socket 永久冻结打印队列
     */
    @Volatile
    var writeTimeoutMs: Long = 10_000
        // 0/负数会被 withTimeoutOrNull 当作立即超时，这里钳到最小 1ms 防止误配拖垮全部打印
        set(value) {
            field = value.coerceAtLeast(1)
        }

    /**
     * 专用写入线程：阻塞式 socket 写入统一在此线程执行，超时时可连同该线程一起放弃；
     * 连接被关闭后，阻塞中的线程会因 socket 关闭而解除阻塞，随后被线程池回收
     */
    private val writeDispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "btprint-write").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    /**
     * 写入数据（Mutex 串行化，保证并发打印时命令不交错）
     */
    private suspend fun write(data: ByteArray) = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            try {
                // 阻塞式写入放到专用线程执行，withTimeoutOrNull 负责超时兜底：
                // 超时说明连接已坏死但 socket 没有报错，关闭连接让后续任务走重连路径
                val completed = withTimeoutOrNull(writeTimeoutMs) {
                    withContext(writeDispatcher) {
                        ensureTransport().write(data)
                    }
                }
                if (completed == null) {
                    throw IOException("写入超时（${writeTimeoutMs}ms），连接已关闭")
                }
            } catch (e: Exception) {
                Log.e(TAG, "写入数据失败", e)
                closeInternal()
                throw e
            }
        }
    }
}