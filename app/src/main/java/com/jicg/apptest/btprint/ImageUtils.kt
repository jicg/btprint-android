package com.jicg.apptest.btprint

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log

/**
 * 图片处理工具类
 */
object ImageUtils {
    private const val TAG = "ImageUtils"

    /**
     * 压缩图片
     * @param bitmap 原图
     * @param targetWidth 目标宽度
     * @param targetHeight 目标高度
     * @return 压缩后的图片
     */
    fun compressBitmap(bitmap: Bitmap, targetWidth: Int, targetHeight: Int): Bitmap {
        try {
            // 如果原图尺寸小于目标尺寸，直接返回原图
            if (bitmap.width <= targetWidth && bitmap.height <= targetHeight) {
                return bitmap
            }

            // 计算压缩比例
            val scale = minOf(
                targetWidth.toFloat() / bitmap.width,
                targetHeight.toFloat() / bitmap.height
            )

            // 计算新的尺寸
            val newWidth = (bitmap.width * scale).toInt()
            val newHeight = (bitmap.height * scale).toInt()

            // 压缩图片
            return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
        } catch (e: Exception) {
            Log.e(TAG, "压缩图片失败: ${e.message}")
            throw e
        }
    }

    /**
     * 计算灰度值
     * @param pixel 像素值
     * @return 灰度值 (0-255)
     */
    fun calculateGray(pixel: Int): Int {
        val r = Color.red(pixel)
        val g = Color.green(pixel)
        val b = Color.blue(pixel)
        return (r * 0.299 + g * 0.587 + b * 0.114).toInt()
    }

    /**
     * 调整像素值
     * @param pixel 原像素值
     * @param error 误差值
     * @return 调整后的像素值
     */
    fun adjustPixel(pixel: Int, error: Int): Int {
        val r = (Color.red(pixel) + error).coerceIn(0, 255)
        val g = (Color.green(pixel) + error).coerceIn(0, 255)
        val b = (Color.blue(pixel) + error).coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }

    /**
     * 使用抖动算法处理图片
     * @param bitmap 原图
     * @param threshold 阈值 (0-255)
     * @return 处理后的点阵数据
     */
    fun ditherImage(bitmap: Bitmap, threshold: Int = 128): ByteArray {
        try {
            val width = bitmap.width
            val height = bitmap.height
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

            // 计算每行字节数（8个点一个字节）
            val bytesPerLine = (width + 7) / 8
            val buffer = ByteArray(bytesPerLine * height)

            // 使用抖动算法处理图片
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val pixel = pixels[y * width + x]
                    val gray = calculateGray(pixel)
                    val error = gray - if (gray < threshold) 0 else 255

                    // 扩散误差到相邻像素
                    if (x + 1 < width) {
                        pixels[y * width + (x + 1)] = adjustPixel(pixels[y * width + (x + 1)], error * 7 / 16)
                    }
                    if (y + 1 < height) {
                        if (x > 0) {
                            pixels[(y + 1) * width + (x - 1)] = adjustPixel(pixels[(y + 1) * width + (x - 1)], error * 3 / 16)
                        }
                        pixels[(y + 1) * width + x] = adjustPixel(pixels[(y + 1) * width + x], error * 5 / 16)
                        if (x + 1 < width) {
                            pixels[(y + 1) * width + (x + 1)] = adjustPixel(pixels[(y + 1) * width + (x + 1)], error * 1 / 16)
                        }
                    }

                    // 设置黑白点
                    if (gray < threshold) {
                        val byteIndex = y * bytesPerLine + x / 8
                        val bitIndex = 7 - (x % 8)
                        buffer[byteIndex] = (buffer[byteIndex].toInt() or (1 shl bitIndex)).toByte()
                    }
                }
            }

            return buffer
        } catch (e: Exception) {
            Log.e(TAG, "处理图片失败: ${e.message}")
            throw e
        }
    }
} 