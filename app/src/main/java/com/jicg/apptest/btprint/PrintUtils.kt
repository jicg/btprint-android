package com.jicg.apptest.btprint

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 打印工具类
 */
object PrintUtils {
    private var btPrintManager: BtPrintManager? = null

    /**
     * 初始化打印管理器
     * @param context 上下文
     */
    fun init(context: Context) {
        if (btPrintManager == null) {
            btPrintManager = BtPrintManager.getInstance(context)
        }
    }

    /**
     * 打印文本
     * @param text 文本内容
     * @param fontSize 字体大小
     * @param align 对齐方式
     */
    fun printText(
        text: String,
        fontSize: Int = PrintCommand.FONT_NORMAL.toInt(),
        align: Int = PrintCommand.ALIGN_LEFT.toInt()
    ) {
        CoroutineScope(Dispatchers.Main).launch {
            btPrintManager?.printText(text, fontSize, align)
        }
    }

    /**
     * 打印两列文本
     * @param text1 左侧文本
     * @param text2 右侧文本
     * @param align 对齐方式
     */
    fun printTwo(text1: String, text2: String) {
        CoroutineScope(Dispatchers.Main).launch {
            btPrintManager?.printTwo(text1, text2)
        }
    }

    /**
     * 打印三列文本
     * @param text1 左侧文本
     * @param text2 中间文本
     * @param text3 右侧文本
     * @param fontSize 字体大小
     */
    fun printThree(
        text1: String,
        text2: String,
        text3: String,
        fontSize: Int = PrintCommand.FONT_NORMAL.toInt()
    ) {
        CoroutineScope(Dispatchers.Main).launch {
            btPrintManager?.printThree(text1, text2, text3, fontSize)
        }
    }

    /**
     * 打印二维码
     * @param text 二维码内容
     * @param width 宽度 (1-3)
     * @param align 对齐方式
     */
    fun printQrCode(text: String, width: Int = 2, align: Int = PrintCommand.ALIGN_CENTER.toInt()) {
        CoroutineScope(Dispatchers.Main).launch {
            btPrintManager?.printQrCode(text, width, align)
        }
    }

    /**
     * 打印条形码
     * @param text 条形码内容
     * @param width 宽度 (1-3)
     * @param height 高度 (1-255)
     * @param align 对齐方式
     */
    fun printBarCode(
        text: String,
        width: Int = 3,
        height: Int = 80,
        align: Int = PrintCommand.ALIGN_CENTER.toInt()
    ) {
        CoroutineScope(Dispatchers.Main).launch {
            btPrintManager?.printBarCode(text, width, height, align)
        }
    }

    /**
     * 打印图片
     * @param bitmap 图片
     * @param width 宽度
     * @param height 高度
     * @param align 对齐方式
     */
    fun printImage(
        bitmap: Bitmap,
        width: Int = 200,
        height: Int = 200,
        align: Int = PrintCommand.ALIGN_CENTER.toInt()
    ) {
        CoroutineScope(Dispatchers.Main).launch {
            btPrintManager?.printImage(bitmap, width, height, align)
        }
    }

    /**
     * 打印空行
     * @param lines 行数
     */
    fun printEmptyLines(lines: Int = 1) {
        CoroutineScope(Dispatchers.Main).launch {
            repeat(lines) {
                btPrintManager?.printText(
                    "",
                    PrintCommand.FONT_NORMAL.toInt(),
                    PrintCommand.ALIGN_CENTER.toInt()
                )
            }
        }
    }

    /**
     * 打印分割线
     * @param char 分割线字符
     * @param length 长度
     */
    fun printDivider(char: String = "-", length: Int = 32) {
        CoroutineScope(Dispatchers.Main).launch {
            btPrintManager?.printText(
                char.repeat(length),
                PrintCommand.FONT_NORMAL.toInt(),
                PrintCommand.ALIGN_CENTER.toInt()
            )
        }
    }

    /**
     * 打印标题
     * @param title 标题文本
     */
    fun printTitle(title: String) {
        CoroutineScope(Dispatchers.Main).launch {
            btPrintManager?.printText(
                title,
                PrintCommand.FONT_DOUBLE_HEIGHT.toInt(),
                PrintCommand.ALIGN_CENTER.toInt()
            )
            printDivider()
        }
    }

    /**
     * 检查打印机是否已连接
     */
    fun isConnected(): Boolean {
        return btPrintManager?.isConnected() == true
    }

    /**
     * 自动连接上次的设备
     * @return 是否连接成功
     */
    suspend fun autoConnectLastDevice(): Boolean {
        return btPrintManager?.autoConnectLastDevice() ?: false
    }

    /**
     * 打印文本（挂起版）
     */
    suspend fun printTextWait(
        text: String,
        fontSize: Int = PrintCommand.FONT_NORMAL.toInt(),
        align: Int = PrintCommand.ALIGN_LEFT.toInt()
    ) {
        btPrintManager?.printText(text, fontSize, align)
    }

    /**
     * 打印两列文本（挂起版）
     */
    suspend fun printTwoWait(text1: String, text2: String) {
        btPrintManager?.printTwo(text1, text2)
    }

    /**
     * 打印三列文本（挂起版）
     */
    suspend fun printThreeWait(
        text1: String,
        text2: String,
        text3: String,
        fontSize: Int = PrintCommand.FONT_NORMAL.toInt()
    ) {
        btPrintManager?.printThree(text1, text2, text3, fontSize)
    }

    /**
     * 打印二维码（挂起版）
     */
    suspend fun printQrCodeWait(
        text: String,
        width: Int = 2,
        align: Int = PrintCommand.ALIGN_CENTER.toInt()
    ) {
        btPrintManager?.printQrCode(text, width, align)
    }

    /**
     * 打印条形码（挂起版）
     */
    suspend fun printBarCodeWait(
        text: String,
        width: Int=1,
        height: Int=60,
        align: Int = PrintCommand.ALIGN_CENTER.toInt()
    ) {
        btPrintManager?.printBarCode(text, width, height, align)
    }

    /**
     * 打印图片（挂起版）
     */
    suspend fun printImageWait(
        bitmap: Bitmap,
        width: Int = 200,
        height: Int = 200,
        align: Int = PrintCommand.ALIGN_CENTER.toInt()
    ) {
        btPrintManager?.printImage(bitmap, width, height, align)
    }

    /**
     * 打印分割线（挂起版）
     * @param char 分割线字符
     * @param length 长度
     */
    suspend fun printDividerWait(char: String = "-", length: Int = 32) {
        btPrintManager?.printText(
            char.repeat(length),
            PrintCommand.FONT_NORMAL.toInt(),
            PrintCommand.ALIGN_CENTER.toInt()
        )
    }

    /**
     * 打印空行（挂起版）
     * @param lines 行数
     */
    suspend fun printEmptyLinesWait(lines: Int = 1) {
        repeat(lines) {
            btPrintManager?.printText(
                "",
                PrintCommand.FONT_NORMAL.toInt(),
                PrintCommand.ALIGN_CENTER.toInt()
            )
        }
    }
} 