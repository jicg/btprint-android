package com.jicg.btprint

import android.content.Context
import android.graphics.Bitmap
import com.jicg.btprint.BtPrintManager.BarcodeType
import com.jicg.btprint.BtPrintManager.Companion.ALIGN_CENTER
import com.jicg.btprint.BtPrintManager.Companion.ALIGN_LEFT
import com.jicg.btprint.BtPrintManager.Companion.ALIGN_RIGHT
import com.jicg.btprint.BtPrintManager.Companion.CHARSET_GBK
import com.jicg.btprint.BtPrintManager.Companion.FONT_SIZE_LARGE
import com.jicg.btprint.BtPrintManager.Companion.FONT_SIZE_NORMAL
import com.jicg.btprint.BtPrintManager.Companion.FONT_SIZE_SMALL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 打印工具类（门面模式）
 * 统一对外提供打印 API
 */
object PrintUtils {
    private lateinit var context: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var initialized = false
    private var debugMode = false

    /**
     * 初始化打印工具
     * 建议在 Application.onCreate() 中调用
     * @param context 应用上下文
     */
    @Synchronized
    fun init(context: Context) {
        this.context = context.applicationContext
        this.initialized = true
    }

    /**
     * 检查是否已初始化
     */
    fun isInitialized(): Boolean {
        return initialized
    }

    /**
     * 设置调试模式
     * @param enabled 是否启用调试模式
     */
    fun setDebugMode(enabled: Boolean) {
        this.debugMode = enabled
    }

    /**
     * 检查是否调试模式
     */
    fun isDebugMode(): Boolean {
        return debugMode
    }

    /**
     * 释放资源
     */
    fun release() {
        scope.cancel()
        initialized = false
        BtPrintManager.getInstance(context).close()
    }

    /**
     * 获取蓝牙打印管理器
     */
    fun getPrintManager(): BtPrintManager {
        check(initialized) { "PrintUtils 未初始化，请先调用 PrintUtils.init(context)" }
        return BtPrintManager.getInstance(context)
    }

    /**
     * 自动连接上次设备
     */
    suspend fun autoConnectLastDevice(): Boolean {
        check(initialized) { "PrintUtils 未初始化，请先调用 PrintUtils.init(context)" }
        return getPrintManager().autoConnectLastDevice()
    }

    /**
     * 检查打印机连接状态
     */
    suspend fun isConnected(): Boolean {
        check(initialized) { "PrintUtils 未初始化，请先调用 PrintUtils.init(context)" }
        return getPrintManager().isConnected()
    }

    /**
     * 断开连接
     */
    fun disconnect() {
        getPrintManager().disconnect()
    }

    /**
     * 打印文本（异步）
     * @param text 文本内容
     * @param fontSize 字体大小
     * @param align 对齐方式
     * @param feedLines 换行数
     */
    fun printText(
        text: String,
        fontSize: Int = FONT_SIZE_NORMAL,
        align: Int = ALIGN_LEFT,
        feedLines: Int = 1
    ): Job {
        return scope.launch {
            try {
                getPrintManager().printText(text, fontSize, align, feedLines)
            } catch (e: Exception) {
                if (debugMode) e.printStackTrace()
            }
        }
    }

    /**
     * 打印文本（挂起）
     */
    suspend fun printTextWait(
        text: String,
        fontSize: Int = FONT_SIZE_NORMAL,
        align: Int = ALIGN_LEFT,
        feedLines: Int = 1
    ) {
        getPrintManager().printText(text, fontSize, align, feedLines)
    }

    /**
     * 打印两列文本
     */
    fun printTwo(text1: String, text2: String): Job {
        return scope.launch {
            try {
                getPrintManager().printTwo(text1, text2)
            } catch (e: Exception) {
                if (debugMode) e.printStackTrace()
            }
        }
    }

    /**
     * 打印两列文本（挂起）
     */
    suspend fun printTwoWait(text1: String, text2: String) {
        getPrintManager().printTwo(text1, text2)
    }

    /**
     * 打印三列文本
     */
    fun printThree(
        text1: String,
        text2: String,
        text3: String,
        fontSize: Int = FONT_SIZE_NORMAL
    ): Job {
        return scope.launch {
            try {
                getPrintManager().printThree(text1, text2, text3, fontSize)
            } catch (e: Exception) {
                if (debugMode) e.printStackTrace()
            }
        }
    }

    /**
     * 打印三列文本（挂起）
     */
    suspend fun printThreeWait(
        text1: String,
        text2: String,
        text3: String,
        fontSize: Int = FONT_SIZE_NORMAL
    ) {
        getPrintManager().printThree(text1, text2, text3, fontSize)
    }

    /**
     * 打印分割线
     */
    fun printDivider(char: String = "-", length: Int = 32): Job {
        return scope.launch {
            try {
                getPrintManager().printDivider(char, length)
            } catch (e: Exception) {
                if (debugMode) e.printStackTrace()
            }
        }
    }

    /**
     * 打印分割线（挂起）
     */
    suspend fun printDividerWait(char: String = "-", length: Int = 32) {
        getPrintManager().printDivider(char, length)
    }

    /**
     * 打印二维码
     */
    fun printQrCode(
        text: String,
        width: Int = 4,
        height: Int = 4,
        align: Int = ALIGN_CENTER
    ): Job {
        return scope.launch {
            try {
                getPrintManager().printQrCode(text, width, height, align)
            } catch (e: Exception) {
                if (debugMode) e.printStackTrace()
            }
        }
    }

    /**
     * 打印二维码（挂起）
     */
    suspend fun printQrCodeWait(
        text: String,
        width: Int = 4,
        height: Int = 4,
        align: Int = ALIGN_CENTER
    ) {
        getPrintManager().printQrCode(text, width, height, align)
    }

    /**
     * 打印条形码
     */
    fun printBarCode(
        text: String,
        width: Int = 3,
        height: Int = 162,
        align: Int = ALIGN_CENTER,
        barcodeType: BarcodeType = BarcodeType.CODE128
    ): Job {
        return scope.launch {
            try {
                getPrintManager().printBarCode(text, width, height, align, barcodeType)
            } catch (e: Exception) {
                if (debugMode) e.printStackTrace()
            }
        }
    }

    /**
     * 打印条形码（挂起）
     */
    suspend fun printBarCodeWait(
        text: String,
        width: Int = 3,
        height: Int = 162,
        align: Int = ALIGN_CENTER,
        barcodeType: BarcodeType = BarcodeType.CODE128
    ) {
        getPrintManager().printBarCode(text, width, height, align, barcodeType)
    }

    /**
     * 打印图片
     */
    fun printImage(bitmap: Bitmap, width: Int = 200, height: Int = 200, align: Int = ALIGN_CENTER): Job {
        return scope.launch {
            try {
                getPrintManager().printImage(bitmap, width, height, align)
            } catch (e: Exception) {
                if (debugMode) e.printStackTrace()
            }
        }
    }

    /**
     * 打印图片（挂起）
     */
    suspend fun printImageWait(
        bitmap: Bitmap,
        width: Int = 200,
        height: Int = 200,
        align: Int = ALIGN_CENTER
    ) {
        getPrintManager().printImage(bitmap, width, height, align)
    }

    /**
     * 打印空行
     */
    fun printEmptyLines(lines: Int = 1): Job {
        return scope.launch {
            try {
                getPrintManager().printText("", FONT_SIZE_NORMAL, ALIGN_LEFT, lines)
            } catch (e: Exception) {
                if (debugMode) e.printStackTrace()
            }
        }
    }

    /**
     * 打印空行（挂起）
     */
    suspend fun printEmptyLinesWait(lines: Int = 1) {
        getPrintManager().printText("", FONT_SIZE_NORMAL, ALIGN_LEFT, lines)
    }

    /**
     * 打印标题
     */
    fun printTitle(
        text: String,
        fontSize: Int = FONT_SIZE_LARGE,
        align: Int = ALIGN_CENTER,
        dividerChar: String = "=",
        dividerLength: Int = 32
    ): Job {
        return scope.launch {
            try {
                val manager = getPrintManager()
                manager.printDivider(dividerChar, dividerLength)
                manager.printText(text, fontSize, align, 1)
                manager.printDivider(dividerChar, dividerLength)
            } catch (e: Exception) {
                if (debugMode) e.printStackTrace()
            }
        }
    }

    /**
     * 打印标题（挂起）
     */
    suspend fun printTitleWait(
        text: String,
        fontSize: Int = FONT_SIZE_LARGE,
        align: Int = ALIGN_CENTER,
        dividerChar: String = "=",
        dividerLength: Int = 32
    ) {
        val manager = getPrintManager()
        manager.printDivider(dividerChar, dividerLength)
        manager.printText(text, fontSize, align, 1)
        manager.printDivider(dividerChar, dividerLength)
    }

    /**
     * 编码文本为 GBK 字节
     */
    fun encodeGbk(text: String): ByteArray {
        return text.toByteArray(CHARSET_GBK)
    }

    /**
     * 释放 Job
     */
    fun cancelPrint(job: Job?) {
        job?.cancel()
    }
}
