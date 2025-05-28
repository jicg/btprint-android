package com.jicg.apptest.btprint

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.charset.Charset
import java.util.*

/**
 * 蓝牙打印管理器
 */
class BtPrintManager private constructor(private val context: Context) {
    private var bluetoothSocket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    private var isConnected = false
    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()

    companion object {
        private const val TAG = "BtPrintManager"
        private const val SPP_UUID = "00001101-0000-1000-8000-00805F9B34FB"
        private var instance: BtPrintManager? = null
        private const val PREF_NAME = "bt_printer_pref"
        private const val KEY_LAST_DEVICE_ADDRESS = "last_device_address"

        // 字体大小常量
        const val FONT_SIZE_SMALL = 0    // 小字体
        const val FONT_SIZE_NORMAL = 1   // 正常字体
        const val FONT_SIZE_LARGE = 2    // 大字体

        @Synchronized
        fun getInstance(context: Context): BtPrintManager {
            if (instance == null) {
                instance = BtPrintManager(context.applicationContext)
            }
            return instance!!
        }
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
    private fun getLastDeviceAddress(): String? {
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
        try {
            bluetoothSocket = device.createRfcommSocketToServiceRecord(UUID.fromString(SPP_UUID))
            bluetoothSocket?.connect()
            outputStream = bluetoothSocket?.outputStream
            // 保存成功连接的设备地址
            saveLastDeviceAddress(device.address)
            isConnected = true
            true
        } catch (e: IOException) {
            Log.e(TAG, "连接蓝牙设备失败", e)
            close()
            false
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
     * 打印文本
     * @param text 文本内容
     * @param fontSize 字体大小 (0:小字体, 1:正常字体, 2:大字体)
     * @param align 对齐方式
     * @param feedLines 换行数
     */
    suspend fun printText(
        text: String,
        fontSize: Int = FONT_SIZE_NORMAL,
        align: Int = PrintCommand.ALIGN_LEFT,
        feedLines: Int = 1
    ) = withContext(Dispatchers.IO) {
        require(
            align in setOf(
                PrintCommand.ALIGN_LEFT,
                PrintCommand.ALIGN_CENTER, PrintCommand.ALIGN_RIGHT
            )
        )
        {
            "Invalid alignment value: $align"
        }
        require(fontSize in setOf(FONT_SIZE_SMALL, FONT_SIZE_NORMAL, FONT_SIZE_LARGE)) {
            "Invalid font size value: $fontSize"
        }

        try {
            // 合并多次写入以提高性能
            val outputBuffer = ByteArrayOutputStream().apply {
                write(byteArrayOf(PrintCommand.ESC, 0x61, align.toByte()))
                // 设置字体大小
                when (fontSize) {
                    FONT_SIZE_SMALL -> {
                        // 小字体
                        write(byteArrayOf(PrintCommand.ESC, 0x21, 0x01)) // 小字体
                        write(byteArrayOf(PrintCommand.ESC, 0x4D, 0))
                    }

                    FONT_SIZE_NORMAL -> {
                        // 正常字体
                        write(byteArrayOf(PrintCommand.ESC, 0x21, 0x00)) // 正常大小
                        write(byteArrayOf(PrintCommand.ESC, 0x4D, 0))
                    }

                    FONT_SIZE_LARGE -> {
                        // 大字体
                        write(byteArrayOf(PrintCommand.ESC, 0x21, 0x10)) // 大字体
                        write(byteArrayOf(PrintCommand.ESC, 0x4D, 0))
                    }
                }

                // 写入文本（提取GBK编码为常量）
                write(text.toByteArray(PrintCommand.CHARSET_GBK))

                // 换行（支持多行）
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

    /**
     * 打印两列文本
     * @param text1 左侧文本
     * @param text2 右侧文本
     * @param align 对齐方式
     */
    suspend fun printTwo(text1: String, text2: String) = withContext(Dispatchers.IO) {
        try {
            // 设置对齐方式
            write(byteArrayOf(PrintCommand.ESC, 0x61, 1.toByte()))
            write(byteArrayOf(PrintCommand.ESC, 0x4D, 0))
            // 计算实际字符宽度（考虑中文字符）
            val getCharWidth = { str: String ->
                str.sumOf { if (it.code > 127) 2L else 1L }.toInt()
            }

            // 计算总宽度（假设打印机每行32个字符）
            val totalWidth = 32
            val text1Width = getCharWidth(text1)
            val text2Width = getCharWidth(text2)

            // 计算需要的空格数
            val spaceCount = totalWidth - text1Width - text2Width
            val spaces = " ".repeat(spaceCount)

            // 打印文本（使用GBK编码）
            write("$text1$spaces$text2".toByteArray(Charset.forName("GBK")))
            // 换行
            write(byteArrayOf(PrintCommand.FEED_LINE.toByte()))
        } catch (e: IOException) {
            Log.e(TAG, "打印两列文本失败", e)
        }
    }

    /**
     * 打印三列文本
     * @param text1 左侧文本
     * @param text2 中间文本
     * @param text3 右侧文本
     * @param fontSize 字体大小
     */
    suspend fun printThree(text1: String, text2: String, text3: String, fontSize: Int) =
        withContext(Dispatchers.IO) {
            try {
                // 设置字体大小
                write(byteArrayOf(PrintCommand.ESC, 0x45, fontSize.toByte()))

                // 计算实际字符宽度（考虑中文字符）
                val getCharWidth = { str: String ->
                    str.sumOf { if (it.code > 127) 2L else 1L }.toInt()
                }

                // 计算每列宽度（假设打印机每行32个字符）
                val totalWidth = 32
                val columnWidth = totalWidth / 3

                // 计算每列文本的实际宽度
                val text1Width = getCharWidth(text1)
                val text2Width = getCharWidth(text2)
                val text3Width = getCharWidth(text3)

                // 计算每列需要的空格数
                val space1 = " ".repeat(columnWidth - text1Width)
                val space2 = " ".repeat(columnWidth - text2Width)

                // 组合文本
                val formattedText = "$text1$space1$text2$space2$text3"

                // 打印文本（使用GBK编码）
                write(formattedText.toByteArray(Charset.forName("GBK")))
                // 换行
                write(byteArrayOf(PrintCommand.FEED_LINE.toByte()))
            } catch (e: IOException) {
                Log.e(TAG, "打印三列文本失败", e)
            }
        }

    /**
     * 打印分割线
     * @param char 分割线字符
     * @param length 长度
     */
    suspend fun printDivider(char: String = "-", length: Int = 32) = withContext(Dispatchers.IO) {
        try {
            // 设置对齐方式
            write(byteArrayOf(PrintCommand.ESC, 0x61, PrintCommand.ALIGN_CENTER.toByte()))

            // 计算实际字符宽度（考虑中文字符）
            val charWidth = if (char[0].code > 127) 2 else 1
            val actualLength = length / charWidth

            // 打印分割线
            write(char.repeat(actualLength).toByteArray(Charset.forName("GBK")))
            // 换行
            write(byteArrayOf(PrintCommand.FEED_LINE.toByte()))
        } catch (e: IOException) {
            Log.e(TAG, "打印分割线失败", e)
        }
    }

    /**
     * 打印二维码
     * @param text 二维码内容
     * @param width 宽度
     * @param align 对齐方式
     */
    suspend fun printQrCode(text: String, width: Int, align: Int) = withContext(Dispatchers.IO) {
        try {
            if (outputStream == null) {
                Log.e(TAG, "蓝牙未连接，无法打印二维码")
                return@withContext
            }
            // 先打印空行，避免顶部出现多余字符
            write(byteArrayOf(PrintCommand.FEED_LINE.toByte()))
            // 清空缓冲区
            write(byteArrayOf(PrintCommand.ESC, 0x40))
            // 设置对齐方式
            write(byteArrayOf(PrintCommand.ESC, 0x61, align.toByte()))

            // 设置二维码大小 (1-16)
            val size = when (width) {
                in 0..100 -> 4
                in 101..200 -> 6
                else -> 8
            }
            write(byteArrayOf(PrintCommand.GS, 0x28, 0x6B, 0x03, 0x00, 0x31, 0x43, size.toByte()))

            // 设置二维码纠错级别 (L:0, M:1, Q:2, H:3)
            write(byteArrayOf(PrintCommand.GS, 0x28, 0x6B, 0x03, 0x00, 0x31, 0x45, 0x03))

            // 设置打印浓度 (0-255)
            write(byteArrayOf(PrintCommand.GS, 0x28, 0x4B, 0x02, 0x00, 0x4D, 0x40))

            // 写入二维码数据
            val data = text.toByteArray()
            val length = data.size + 3
            write(
                byteArrayOf(
                    PrintCommand.GS,
                    0x28,
                    0x6B,
                    (length and 0xFF).toByte(),
                    ((length shr 8) and 0xFF).toByte(),
                    0x31,
                    0x50,
                    0x30
                )
            )
            write(data)

            // 打印二维码
            write(byteArrayOf(PrintCommand.GS, 0x28, 0x6B, 0x03, 0x00, 0x31, 0x51, 0x30))

            // 换行
            write(byteArrayOf(PrintCommand.FEED_LINE.toByte()))
            Log.d(TAG, "打印二维码成功 $text")
        } catch (e: Exception) {
            Log.e(TAG, "打印二维码失败", e)
        }
    }

    /**
     * 打印条形码
     * @param text 条形码内容
     * @param width 宽度 (2-6)
     * @param height 高度 (1-255)
     * @param align 对齐方式
     */
    suspend fun printBarCode(
        text: String,
        width: Int = 3,
        height: Int = 100,
        align: Int = PrintCommand.ALIGN_CENTER.toInt()
    ) = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "开始打印条形码: $text")

            // 初始化打印机
//            write(byteArrayOf(PrintCommand.GS, 0x40))

            // 设置对齐方式
            write(byteArrayOf(PrintCommand.GS, 0x61, align.toByte()))
            Log.d(TAG, "设置对齐方式: $align")

            // 设置条形码高度 (1-255)
            val actualHeight = height.coerceIn(1, 255)
            write(byteArrayOf(PrintCommand.GS, 0x62, actualHeight.toByte()))
            Log.d(TAG, "设置条形码高度: $actualHeight")

            // 设置条形码宽度 (2-6)
            val actualWidth = width.coerceIn(2, 6).toByte()
            write(byteArrayOf(PrintCommand.GS, 0x77, actualWidth))
            Log.d(TAG, "设置条形码宽度: $actualWidth")

            // 设置条形码类型为CODE128
            write(byteArrayOf(PrintCommand.GS, 0x62, 0x02))
            Log.d(TAG, "设置条形码类型为CODE128")


            // 写入条形码数据
            //02 31 32 33 34 35 36 37 38 39 30 31 32 0A
            val data = text.toByteArray(Charsets.US_ASCII)//Charsets.US_ASCII
            write(data)
            Log.d(TAG, "写入条形码数据: ${text}")

            // 换行
            write(byteArrayOf(PrintCommand.FEED_LINE.toByte()))
            Log.d(TAG, "打印条形码完成")

        } catch (e: Exception) {
            Log.e(TAG, "打印条形码失败: ${e.message}")
            e.printStackTrace()
            throw e
        }
    }

    /**
     * 打印图片
     * @param bitmap 图片
     * @param width 宽度
     * @param height 高度
     * @param align 对齐方式
     */
    suspend fun printImage(
        bitmap: Bitmap,
        width: Int = 200,
        height: Int = 200,
        align: Int = PrintCommand.ALIGN_CENTER.toInt()
    ) = withContext(Dispatchers.IO) {
        try {
            if (outputStream == null) {
                Log.e(TAG, "蓝牙未连接，无法打印图片")
                return@withContext
            }

            // 设置对齐方式
            write(byteArrayOf(PrintCommand.ESC, 0x61, align.toByte()))

            // 计算每行字节数（8个点一个字节）
            val bytesPerLine = (width + 7) / 8 * 8
            // 计算实际高度（保持宽高比）
            val actualHeight = bitmap.height * bytesPerLine / bitmap.width

            // 压缩图片
            val compressedBitmap = ImageUtils.compressBitmap(bitmap, bytesPerLine, actualHeight)
            // 使用抖动算法处理图片
            val buffer = ImageUtils.ditherImage(compressedBitmap)

            // 发送图片数据
            // 设置行间距为0
            write(byteArrayOf(PrintCommand.ESC, 0x33, 0))

            // 发送 GS v 0 命令
            val command = byteArrayOf(
                PrintCommand.GS, 0x76, 0x30, 0,  // GS v 0 命令
                (bytesPerLine / 8 % 256).toByte(),  // 宽度低字节
                (bytesPerLine / 8 / 256).toByte(),  // 宽度高字节
                (actualHeight % 256).toByte(),      // 高度低字节
                (actualHeight / 256).toByte()       // 高度高字节
            )
            write(command)

            // 创建缓存
            val cacheSize = 1024 * 8 // 8KB 缓存
            val cache = ByteArrayOutputStream(cacheSize)

            // 写入图片数据
            val totalBytes = buffer.size
            var currentPosition = 0

            while (currentPosition < totalBytes) {
                // 计算当前行可用的字节数
                val remainingBytes = totalBytes - currentPosition
                val bytesToWrite = minOf(bytesPerLine, remainingBytes)
                
                // 复制当前行的数据
                val lineData = buffer.copyOfRange(currentPosition, currentPosition + bytesToWrite)
                cache.write(lineData)

                // 当缓存达到一定大小时写入
                if (cache.size() >= cacheSize) {
                    write(cache.toByteArray())
                    cache.reset()
                }

                currentPosition += bytesToWrite
            }

            // 写入剩余的缓存数据
            if (cache.size() > 0) {
                write(cache.toByteArray())
                cache.reset()
            }

            // 换行
            write(byteArrayOf(PrintCommand.FEED_LINE.toByte()))

            // 恢复行间距
            write(byteArrayOf(PrintCommand.ESC, 0x33, 24))

            Log.d(TAG, "打印图片成功")
        } catch (e: Exception) {
            Log.e(TAG, "打印图片失败: ${e.message}")
            e.printStackTrace()
            throw e
        }
    }

    /**
     * 检查蓝牙连接状态
     */
    fun isConnected(): Boolean {
        return try {
            if (bluetoothSocket?.isConnected == true && outputStream != null) {
                // 尝试写入一个空字节来测试连接是否真的有效
                outputStream?.write(byteArrayOf(0))
                outputStream?.flush()
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "检查连接状态失败", e)
            close()
            false
        }
    }

    /**
     * 重新连接蓝牙设备
     */
    @SuppressLint("MissingPermission")
    suspend fun reconnect(device: BluetoothDevice) = withContext(Dispatchers.IO) {
        try {
            // 先关闭现有连接
            close()

            // 创建新的连接
            bluetoothSocket = device.createRfcommSocketToServiceRecord(UUID.fromString(SPP_UUID))
            bluetoothSocket?.connect()
            outputStream = bluetoothSocket?.getOutputStream()

            // 测试连接是否有效
            if (isConnected()) {
                isConnected = true
                true
            } else {
                close()
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "重新连接失败", e)
            close()
            false
        }
    }

    /**
     * 关闭连接
     */
    fun close() {
        try {
            outputStream?.close()
            bluetoothSocket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "关闭连接失败", e)
        } finally {
            outputStream = null
            bluetoothSocket = null
            isConnected = false
        }
    }

    /**
     * 写入数据
     */
    private suspend fun write(data: ByteArray) = withContext(Dispatchers.IO) {
        try {
            if (!isConnected()) {
                throw IOException("打印机未连接")
            }
            outputStream?.write(data)
            outputStream?.flush()
        } catch (e: Exception) {
            Log.e(TAG, "写入数据失败", e)
            throw e
        }
    }
} 