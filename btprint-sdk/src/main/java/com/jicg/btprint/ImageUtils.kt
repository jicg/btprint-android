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
        return try {
            Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
        } catch (e: Exception) {
            Log.e(TAG, "压缩图片失败", e)
            bitmap
        }
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

        // 创建像素数组用于抖动处理
        val pixels = Array(height) { IntArray(width) }
        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixel = bitmap.getPixel(x, y)
                pixels[y][x] = (Color.red(pixel) + Color.green(pixel) + Color.blue(pixel)) / 3
            }
        }

        // Floyd-Steinberg 抖动算法
        for (y in 0 until height) {
            for (x in 0 until width) {
                val oldValue = pixels[y][x]
                val newValue = if (oldValue > 127) 255 else 0
                pixels[y][x] = newValue

                val error = oldValue - newValue

                // 分配误差到相邻像素
                if (x + 1 < width) pixels[y][x + 1] += error * 7 / 16
                if (y + 1 < height) {
                    if (x > 0) pixels[y + 1][x - 1] += error * 3 / 16
                    pixels[y + 1][x] += error * 5 / 16
                    if (x + 1 < width) pixels[y + 1][x + 1] += error * 1 / 16
                }
            }
        }

        // 转换为打印机数据
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (pixels[y][x] < 128) {
                    val byteIndex = y * bytesPerLine + x / 8
                    val bitIndex = x % 8
                    data[byteIndex] = (data[byteIndex].toInt() or (0x80 shr bitIndex)).toByte()
                }
            }
        }

        return data
    }
}
