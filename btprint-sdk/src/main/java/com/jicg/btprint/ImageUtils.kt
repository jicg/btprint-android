package com.jicg.btprint

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Log

/**
 * 图片处理工具类
 */
object ImageUtils {
    private const val TAG = "ImageUtils"

    /**
     * 压缩图片
     * @param bitmap 原始图片
     * @param targetWidth 目标宽度
     * @param targetHeight 目标高度
     * @return 压缩后的图片
     */
    fun compressBitmap(bitmap: Bitmap, targetWidth: Int, targetHeight: Int): Bitmap {
        require(targetWidth > 0 && targetHeight > 0) {
            "targetWidth 和 targetHeight 必须大于 0，当前: ${targetWidth}x$targetHeight"
        }
        // HARDWARE 配置的位图无法被 createScaledBitmap/getPixels 读取，先转为可读的 ARGB_8888
        val source = if (bitmap.config == Bitmap.Config.HARDWARE) {
            bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: bitmap
        } else {
            bitmap
        }
        // 缩放失败（如内存不足）直接抛给调用方，打印任务整体失败；
        // 不回退原图——原图宽度超过可打印点数会让 GS v 0 包头与数据错位，打出乱码
        return Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true)
    }

    /**
     * 使用抖动算法处理图片
     * @param bitmap 图片
     * @return 图片数据
     */
    fun ditherImage(bitmap: Bitmap): ByteArray {
        val width = bitmap.width
        val height = bitmap.height

        // 计算每行字节数（每8个像素一个字节）
        val bytesPerLine = (width + 7) / 8
        val dataSize = bytesPerLine * height
        val data = ByteArray(dataSize)

        // 批量读取像素到 IntArray（消除逐像素 getPixel 的 JNI 调用开销）
        // 要求位图格式为 ARGB_8888（compressBitmap 的产物满足）
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        // 转为灰度数组
        val gray = IntArray(width * height)
        for (i in pixels.indices) {
            val p = pixels[i]
            gray[i] = (Color.red(p) + Color.green(p) + Color.blue(p)) / 3
        }

        // Floyd-Steinberg 抖动算法
        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                val oldValue = gray[index]
                val newValue = if (oldValue > 127) 255 else 0
                gray[index] = newValue

                val error = oldValue - newValue

                // 分配误差到相邻像素
                if (x + 1 < width) gray[index + 1] += error * 7 / 16
                if (y + 1 < height) {
                    if (x > 0) gray[index + width - 1] += error * 3 / 16
                    gray[index + width] += error * 5 / 16
                    if (x + 1 < width) gray[index + width + 1] += error * 1 / 16
                }
            }
        }

        // 转换为打印机数据
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (gray[y * width + x] < 128) {
                    val byteIndex = y * bytesPerLine + x / 8
                    val bitIndex = x % 8
                    data[byteIndex] = (data[byteIndex].toInt() or (0x80 shr bitIndex)).toByte()
                }
            }
        }

        return data
    }
}
