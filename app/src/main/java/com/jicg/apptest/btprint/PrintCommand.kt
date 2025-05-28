package com.jicg.apptest.btprint

import java.nio.charset.Charset

/**
 * 打印命令常量
 */
object PrintCommand {
    // 打印命令
    const val ESC: Byte = 0x1B
    const val GS: Byte = 0x1D
    const val INIT: Byte = 0x40
    const val FEED_PAPER: Byte = 0x0C
    
    // 对齐方式
    const val ALIGN_LEFT: Int = 0x00
    const val ALIGN_CENTER: Int = 0x01
    const val ALIGN_RIGHT: Int = 0x02
    
    // 字体风格

    const val FONT_NORMAL: Int = 0x01
    const val FONT_BOLD: Int = 0x01
    const val FONT_DOUBLE_HEIGHT: Byte = 0x10
    const val FONT_DOUBLE_WIDTH: Byte = 0x20
    
    // 行间距
    const val LINE_SPACING_DEFAULT: Byte = 0x20
    const val LINE_SPACING_1: Byte = 0x10
    const val LINE_SPACING_2: Byte = 0x20
    const val LINE_SPACING_3: Byte = 0x30
    
    // 切纸命令
    const val CUT_PAPER: Byte = 0x1D
    const val CUT_PAPER_FULL: Byte = 0x00
    const val CUT_PAPER_PARTIAL: Byte = 0x01
    
    // 条形码命令
    const val BARCODE_HEIGHT: Byte = 0x68
    const val BARCODE_WIDTH: Byte = 0x77
    const val BARCODE_TYPE: Byte = 0x6B
    const val BARCODE_CODE128: Byte = 0x49


    const val ONE_CODE93: Byte = 72
    const val ONE_JAN13: Byte = 2
    const val ONE_CODE128: Byte = 73
    const val ONE_JAN8: Byte = 3
    const val ONE_CODABAR: Byte = 6

    const val TWO_PDF417: Byte = 100
    const val TWO_DATAMATRIX: Byte = 101
    const val TWO_QRCODE: Byte = 102
    
    // 二维码命令
    const val QR_SIZE: Byte = 0x43
    const val QR_ERROR_CORRECTION: Byte = 0x45
    const val QR_DATA: Byte = 0x50
    const val QR_PRINT: Byte = 0x51




     val CHARSET_GBK = Charset.forName("GBK")


    // 控制命令
    val FEED_LINE:Int = 0x0A

    // 动态生成命令
    fun getAlignmentCommand(align: Int) = byteArrayOf(ESC, 0x61, align.toByte())
    fun getFontSizeCommand(fontSize: Int) = when (fontSize) {
        FEED_LINE -> byteArrayOf(ESC, 0x45, 1)
        else -> byteArrayOf(ESC, 0x4D, 0)
    }
} 