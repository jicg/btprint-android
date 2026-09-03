package com.jicg.btprint

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.charset.Charset
import java.util.*

/**
 * 蓝牙打印管理器
 */
class BtPrintManager private constructor(private val context: Application) {
    private val writeMutex = Mutex()
    private var bluetoothSocket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    private var connected = false
    private var connectedDeviceAddress: String? = null
    private var aclReceiver: BroadcastReceiver? = null
    private val _connectedDevice = MutableStateFlow<BluetoothDevice?>(null)
    val connectedDevice: StateFlow<BluetoothDevice?> = _connectedDevice.asStateFlow()
    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()

    companion object {
        private const val TAG = "BtPrintManager"
        private const val SPP_UUID = "00001101-0000-1000-8000-00805F9B34FB"
        private var instance: BtPrintManager? = null
        private const val PREF_NAME = "bt_printer_pref"
        private const val KEY_LAST_DEVICE_ADDRESS = "last_device_address"
        private const val KEY_PAPER_WIDTH = "paper_width"

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
    private fun saveLastDeviceAddress(address: String) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_DEVICE_ADDRESS, address)
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
     * 自动连接上次的设备
     */
    suspend fun autoConnectLastDevice(): Boolean = withContext(Dispatchers.IO) {
        try {
            val lastAddress = getLastDeviceAddress() ?: return@withContext false
            val device = bluetoothAdapter?.getRemoteDevice(lastAddress) ?: return@withContext false
            return@withContext connect(device)
        } catch (e: Exception) {
            Log.e(TAG, "自动连接上次设备失败", e)
            return@withContext false
        }
    }

    /**
     * 连接蓝牙设备
     * @param device 蓝牙设备
     * @return 是否连接成功
     */
    @SuppressLint("MissingPermission")
    suspend fun connect(device: BluetoothDevice): Boolean = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            try {
                // 扫描会显著拖慢甚至导致 RFCOMM 连接失败，连接前先停止扫描
                try {
                    bluetoothAdapter?.takeIf { it.isDiscovering }?.cancelDiscovery()
                } catch (e: SecurityException) {
                    Log.w(TAG, "取消扫描失败: 缺少蓝牙权限", e)
                }
                // 先关闭旧连接，避免已连接状态下重复 connect 泄漏旧 socket
                //（多数热敏打印机仅支持 1 路 SPP 连接，旧连接不释放会导致新连接失败）
                closeInternal()
                bluetoothSocket = device.createRfcommSocketToServiceRecord(UUID.fromString(SPP_UUID))
                bluetoothSocket?.connect()
                outputStream = bluetoothSocket?.outputStream
                // 保存成功连接的设备地址
                saveLastDeviceAddress(device.address)
                connected = true
                connectedDeviceAddress = device.address
                _connectedDevice.value = device
                true
            } catch (e: IOException) {
                Log.e(TAG, "连接蓝牙设备失败", e)
                closeInternal()
                false
            } catch (e: Exception) {
                Log.e(TAG, "连接蓝牙设备失败", e)
                closeInternal()
                false
            }
        }
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
     */
    suspend fun printText(
        text: String,
        fontSize: Int = FONT_SIZE_NORMAL,
        align: Int = ALIGN_LEFT,
        feedLines: Int = 1
    ) {
        // 参数校验在协程切换之前执行，避免校验异常在 IO 线程抛出
        require(
            align in setOf(
                ALIGN_LEFT,
                ALIGN_CENTER, ALIGN_RIGHT
            )
        )
        {
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

                    // 写入文本（提取GBK编码为常量）
                    write(text.toByteArray(CHARSET_GBK))

//                // 换行（支持多行）
                    repeat(feedLines) {
                        write(0x0A) // 使用换行符
                    }
                }

                // 单次IO写入操作
                write(outputBuffer.toByteArray())

                Log.d(TAG, "打印成功: ${text.take(20)}...") // 截断长文本
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
            // 计算实际字符宽度（考虑中文字符）
            val getCharWidth = { str: String ->
                str.sumOf { if (it.code > 127) 2L else 1L }.toInt()
            }

            // 行宽按纸张与字号换算：大字体为倍宽模式，一行只能容纳一半字符
            val totalWidth = if (fontSize == FONT_SIZE_LARGE) {
                paperWidth.charsPerLine / 2
            } else {
                paperWidth.charsPerLine
            }
            val text1Width = getCharWidth(text1)
            val text2Width = getCharWidth(text2)

            if (text1Width + text2Width <= totalWidth) {
                // 一行放得下：正常两列排版
                // coerceAtLeast(0) 防止文本超宽时负数 repeat 崩溃
                val spaceCount = (totalWidth - text1Width - text2Width).coerceAtLeast(0)
                val spaces = " ".repeat(spaceCount)
                // 打印文本（使用GBK编码）
                write("$text1$spaces$text2".toByteArray(CHARSET_GBK))
            } else {
                // 超宽兜底：text2 换行到下一行，避免截断
                write(text1.toByteArray(CHARSET_GBK))
                write(byteArrayOf(FEED_LINE.toByte()))
                write(text2.toByteArray(CHARSET_GBK))
            }
            // 换行
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

                // 计算实际字符宽度（考虑中文字符）
                val getStringPixLength = { str: String ->
                    str.sumOf { if (it.code > 127) 2L else 1L }.toInt()
                }

                // 打印机参数（行宽按纸张与字号换算：大字体为倍宽模式，一行只能容纳一半字符）
                val WIDTH_PIXEL = if (fontSize == FONT_SIZE_LARGE) {
                    paperWidth.charsPerLine / 2
                } else {
                    paperWidth.charsPerLine
                }  // 打印机总宽度
                val WIDTH_PIXEL_MID = WIDTH_PIXEL / 2  // 中间区域宽度
                val SpaceLength = 1  // 空格宽度

                // 计算各列文本宽度
                val leftLength = getStringPixLength(text1)
                val middleLength = getStringPixLength(text2)
                val rightLength = getStringPixLength(text3)

                // 计算剩余空间
                var remLength = WIDTH_PIXEL - rightLength - leftLength - middleLength
                var result = text1

                // 如果空间不足，左列换行
                if (remLength < 0) {
                    result += "\n"
                    remLength = WIDTH_PIXEL - middleLength - rightLength
                }

                // 计算空格数量（coerceAtLeast 防止超宽时负数 repeat 崩溃）
                val size = (remLength / SpaceLength).coerceAtLeast(0)
                val WIDTH_PIXEL_MID_RIGHT = WIDTH_PIXEL - WIDTH_PIXEL_MID

                // 根据左列长度决定空格分配方式
                if (leftLength < WIDTH_PIXEL_MID && WIDTH_PIXEL_MID_RIGHT > (SpaceLength * 2 + middleLength / 2 + rightLength)) {
                    // 左列较短时的空格分配
                    val leftSize = ((WIDTH_PIXEL_MID - leftLength - middleLength / 2) / SpaceLength).coerceAtLeast(0)
                    val rightSize = (size - leftSize).coerceAtLeast(0)
                    result += " ".repeat(leftSize) + text2 + " ".repeat(rightSize) + text3
                } else {
                    // 常规空格分配（先乘后除：Int 除法 WIDTH_PIXEL_MID / WIDTH_PIXEL 恒为 0）
                    val leftSize = (size * WIDTH_PIXEL_MID / WIDTH_PIXEL).coerceAtLeast(0)
                    val rightSize = (size - leftSize).coerceAtLeast(0)
                    result += " ".repeat(leftSize) + text2 + " ".repeat(rightSize) + text3
                }

                // 打印文本（使用GBK编码）
                write(result.toByteArray(Charset.forName("GBK")))
                // 换行
                write(byteArrayOf(FEED_LINE.toByte()))
                Log.d(TAG, "打印三列文本成功: $text1 | $text2 | $text3")
            } catch (e: Exception) {
                Log.e(TAG, "打印三列文本失败: ${e.message}")
                e.printStackTrace()
                throw e
            }
        }
    }

    /**
     * 打印分割线
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
        } catch (e: IOException) {
            Log.e(TAG, "打印分割线失败", e)
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
                if (outputStream == null) {
                    Log.e(TAG, "蓝牙未连接，无法切纸")
                    return@withContext
                }
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
                if (outputStream == null) {
                    Log.e(TAG, "蓝牙未连接，无法开钱箱")
                    return@withContext
                }
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
     * @param text 二维码内容
     * @param width 宽度 (1-16)
     * @param height 高度 (1-16)
     * @param align 对齐方式
     */
    suspend fun printQrCode(
        text: String,
        width: Int = 4,
        height: Int = 4,
        align: Int = ALIGN_CENTER
    ) = withContext(Dispatchers.IO) {
        try {
            if (outputStream == null) {
                Log.e(TAG, "蓝牙未连接，无法打印二维码")
                return@withContext
            }

            // 设置对齐方式
            write(byteArrayOf(ESC, 0x61, align.toByte()))

            // 转换文本为字节数组
            val data = text.toByteArray(Charsets.US_ASCII)
            val dataLength = data.size

            // 设置二维码参数
            // 设置二维码大小
            write(byteArrayOf(29, 40, 107, 3, 0, 49, 67, width.coerceIn(1, 16).toByte()))
            // 设置二维码高度
            write(byteArrayOf(29, 40, 107, 3, 0, 49, 69, height.coerceIn(1, 16).toByte()))

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

            Log.d(TAG, "打印二维码成功: $text")
        } catch (e: Exception) {
            Log.e(TAG, "打印二维码失败: ${e.message}")
            e.printStackTrace()
            throw e
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
    ) = withContext(Dispatchers.IO) {
        try {
            if (outputStream == null) {
                Log.e(TAG, "蓝牙未连接，无法打印条形码")
                return@withContext
            }

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

            // 换行
//            write(byteArrayOf(27, 100, 1.toByte()))

            Log.d(TAG, "打印条形码成功: $text")
        } catch (e: Exception) {
            Log.e(TAG, "打印条形码失败: ${e.message}")
            e.printStackTrace()
            throw e
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
     * @param width 目标宽度（像素），自动按 8 点向上对齐
     * @param height 保留参数，暂未使用；实际高度始终按图片宽高比推导
     * @param align 对齐方式
     */
    suspend fun printImage(
        bitmap: Bitmap,
        width: Int = 200,
        height: Int = 200,
        align: Int = ALIGN_CENTER.toInt()
    ) = withContext(Dispatchers.IO) {
        require(width > 0 && height > 0) { "打印图片的宽高必须大于 0，当前: ${width}x$height" }
        try {
            if (outputStream == null) {
                Log.e(TAG, "蓝牙未连接，无法打印图片")
                return@withContext
            }

            // 设置对齐方式
            write(byteArrayOf(ESC, 0x61, align.toByte()))

            // 目标宽度：钳制在当前纸宽的可打印点数内，再按 8 点（1 字节）向上取整
            val targetWidthPx = (width.coerceAtMost(paperWidth.dotsPerLine) + 7) / 8 * 8
            // 计算实际高度（保持宽高比）
            val actualHeight = bitmap.height * targetWidthPx / bitmap.width

            // 压缩图片
            val compressedBitmap = ImageUtils.compressBitmap(bitmap, targetWidthPx, actualHeight)
            // 使用抖动算法处理图片
            val buffer = ImageUtils.ditherImage(compressedBitmap)

            // 发送图片数据
            // 设置行间距为0
            write(byteArrayOf(ESC, 0x33, 0))

            // 发送 GS v 0 命令
            // 包头尺寸以实际产出的位图为准：compressBitmap 失败回退原图时也能保持头/数据一致
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

            Log.d(TAG, "打印图片成功")
        } catch (e: Exception) {
            Log.e(TAG, "打印图片失败: ${e.message}")
            e.printStackTrace()
            throw e
        }
    }

    /**
     * 检查蓝牙连接状态
     * 纯内存状态判断，不向打印机写入任何字节
     */
    suspend fun isConnected(): Boolean {
        return bluetoothSocket?.isConnected == true && outputStream != null && connected
    }

    /**
     * 重新连接蓝牙设备
     */
    @SuppressLint("MissingPermission")
    suspend fun reconnect(device: BluetoothDevice) = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            try {
                // 先关闭现有连接
                closeInternal()

                // 创建新的连接
                bluetoothSocket = device.createRfcommSocketToServiceRecord(UUID.fromString(SPP_UUID))
                bluetoothSocket?.connect()
                outputStream = bluetoothSocket?.getOutputStream()

                // 测试连接是否有效（纯状态检查，不写字节）
                if (bluetoothSocket?.isConnected == true && outputStream != null) {
                    connected = true
                    connectedDeviceAddress = device.address
                    _connectedDevice.value = device
                    true
                } else {
                    closeInternal()
                    false
                }
            } catch (e: Exception) {
                Log.e(TAG, "重新连接失败", e)
                closeInternal()
                false
            }
        }
    }

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
        try {
            outputStream?.close()
            bluetoothSocket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "关闭连接失败", e)
        } finally {
            outputStream = null
            bluetoothSocket = null
            connected = false
            connectedDeviceAddress = null
            _connectedDevice.value = null
        }
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
     * 写入数据（Mutex 串行化，保证并发打印时命令不交错）
     */
    private suspend fun write(data: ByteArray) = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            try {
                if (bluetoothSocket?.isConnected != true || outputStream == null || !connected) {
                    throw IOException("打印机未连接")
                }
                outputStream?.write(data)
                outputStream?.flush()
            } catch (e: Exception) {
                Log.e(TAG, "写入数据失败", e)
                closeInternal()
                throw e
            }
        }
    }
}