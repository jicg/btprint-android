package com.jicg.apptest

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.jicg.btprint.BtPrintActivity
import com.jicg.btprint.PrintUtils
import com.jicg.btprint.WifiPrintActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private lateinit var modeRadio: RadioGroup
    private lateinit var btEntryButton: Button
    private lateinit var wifiEntryButton: Button
    private lateinit var disconnectButton: Button
    private lateinit var printTestButton: Button
    private lateinit var imagePreview: ImageView
    private var currentBitmap: Bitmap? = null

    private val selectImageLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.data?.let { uri ->
                    // 大图解码放 IO 线程并按打印宽度降采样，避免主线程 ANR
                    lifecycleScope.launch {
                        val bitmap = decodeSampledBitmap(uri, PRINT_IMAGE_TARGET_WIDTH)
                        if (bitmap != null) {
                            currentBitmap = bitmap
                            imagePreview.setImageBitmap(bitmap)
                            Toast.makeText(this@MainActivity, "图片加载成功", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this@MainActivity, "加载图片失败", Toast.LENGTH_SHORT)
                                .show()
                        }
                    }
                }
            }
        }

    /**
     * 按目标宽度解码并降采样（inSampleSize 取 2 的幂）
     */
    private suspend fun decodeSampledBitmap(uri: Uri, targetWidth: Int): Bitmap? =
        withContext(Dispatchers.IO) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null

            var sample = 1
            while (bounds.outWidth / (sample * 2) >= targetWidth) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            }
        }

    companion object {
        /** 打印图片的目标宽度（58mm 纸宽 384 点），预览/打印共用同一张降采样后的位图 */
        private const val PRINT_IMAGE_TARGET_WIDTH = 384
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        currentBitmap = BitmapFactory.decodeResource(resources, R.mipmap.android)
        // 尝试自动连接上次的设备（按上次成功时的类型：蓝牙 / WiFi）
        lifecycleScope.launch {
            if (PrintUtils.autoConnectLastDevice()) {
                Toast.makeText(this@MainActivity, "已自动连接上次的打印机", Toast.LENGTH_SHORT)
                    .show()
                updateButtonState()
            }
        }

        // 创建根布局
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }

        // 打印方式配置：蓝牙 / WiFi / 两者都支持，选中即持久化
        rootLayout.addView(TextView(this).apply {
            text = "打印方式"
            textSize = 14f
        })
        modeRadio = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
        }
        val modes = listOf(
            PrintMode.BLUETOOTH to "蓝牙打印",
            PrintMode.WIFI to "WiFi 打印",
            PrintMode.BOTH to "两者都支持"
        )
        modes.forEach { (mode, label) ->
            modeRadio.addView(RadioButton(this).apply {
                text = label
                id = mode.ordinal
                textSize = 14f
            })
        }
        modeRadio.check(PrintModeStore.get(this).ordinal)
        modeRadio.setOnCheckedChangeListener { _, checkedId ->
            val mode = PrintMode.entries.firstOrNull { it.ordinal == checkedId } ?: return@setOnCheckedChangeListener
            PrintModeStore.set(this@MainActivity, mode)
            updateEntryVisibility(mode)
        }
        rootLayout.addView(modeRadio)

        // 蓝牙打印机入口（跳转蓝牙连接配置页）
        btEntryButton = Button(this).apply {
            text = "蓝牙打印机"
            setOnClickListener {
                startActivity(Intent(this@MainActivity, BtPrintActivity::class.java))
            }
        }
        rootLayout.addView(btEntryButton)

        // WiFi 打印机入口（跳转 WiFi 连接配置页：手动 IP / 局域网扫描）
        wifiEntryButton = Button(this).apply {
            text = "WiFi 打印机"
            setOnClickListener {
                startActivity(Intent(this@MainActivity, WifiPrintActivity::class.java))
            }
        }
        rootLayout.addView(wifiEntryButton)

        // 断开当前连接（仅已连接时显示）
        disconnectButton = Button(this).apply {
            text = "断开连接"
            visibility = View.GONE
            setOnClickListener {
                PrintUtils.disconnect()
                lifecycleScope.launch { updateButtonState() }
            }
        }
        rootLayout.addView(disconnectButton)

        // 创建打印测试按钮
        printTestButton = Button(this).apply {
            text = "打印测试"
            setOnClickListener {


                lifecycleScope.launch {
                    if (PrintUtils.isConnected()) {
                        try {
                            PrintUtils.printTitleWait("测试")
                            if (currentBitmap != null) {
                                PrintUtils.printImageWait(currentBitmap!!)
                            }
                            PrintUtils.printTwoWait("店仓：", "测试店仓")
                            PrintUtils.printTwoWait("店仓：", "测试店仓")
                            PrintUtils.printTwoWait("员工：", "黎明")
                            PrintUtils.printTwoWait("时间：", "2024-01-01 12:00:00")
                            PrintUtils.printTwoWait("店仓：", "测试店仓")
                            PrintUtils.printTwoWait("VIP卡号", "xxxxxxxxx")
                            PrintUtils.printDividerWait("=")
                            PrintUtils.printThreeWait("商品", "1", "800.0")
                            PrintUtils.printDividerWait("-")
                            PrintUtils.printThreeWait("小王aaa", "2", "200")
                            PrintUtils.printDividerWait("-")
                            PrintUtils.printThreeWait("小王111", "33", "20")
                            PrintUtils.printDividerWait("-")
                            PrintUtils.printThreeWait("小王bbb", "45", "40")
                            PrintUtils.printDividerWait("=")
                            PrintUtils.printTwoWait("合计", "1111.0")
                            PrintUtils.printTextWait("")
                            PrintUtils.printBarCodeWait("12312312312312")
                            PrintUtils.printTextWait("")
                            PrintUtils.printTextWait(
                                "测试撒测测试测试测试撒测测试测试测试测试撒测\n测试测、试测试测试撒测测试测试。测试测试撒测测试测试测试",
                                fontSize = 0
                            )
                            PrintUtils.printTextWait("")
                            PrintUtils.printTextWait("")
                            PrintUtils.printTextWait("")
                            PrintUtils.printTextWait("")
                            PrintUtils.printTextWait("")
                        } catch (e: Exception) {
                            e.printStackTrace()
                            Toast.makeText(
                                this@MainActivity,
                                "打印测试失败: ${e.message}",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    } else {
                        Toast.makeText(this@MainActivity, "请先连接打印机", Toast.LENGTH_SHORT)
                            .show()
                    }
                }

            }
        }
        rootLayout.addView(printTestButton)

        // 创建图片预览
        imagePreview = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                300
            ).apply {
                topMargin = 16
            }
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(0xFFEEEEEE.toInt())
            setImageResource(android.R.drawable.ic_menu_camera)
        }
        rootLayout.addView(imagePreview)

        // 创建选择图片按钮
        Button(this).apply {
            text = "从相册选择图片"
            setOnClickListener {
                // 打开相册选择图片
                val intent =
                    Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
                selectImageLauncher.launch(intent)
            }
        }.also { rootLayout.addView(it) }

        setContentView(rootLayout)
        updateEntryVisibility(PrintModeStore.get(this))
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            updateButtonState()
        }
    }

    /**
     * 按打印方式控制两个配置页入口的显隐
     */
    private fun updateEntryVisibility(mode: PrintMode) {
        btEntryButton.visibility = if (mode == PrintMode.WIFI) View.GONE else View.VISIBLE
        wifiEntryButton.visibility = if (mode == PrintMode.BLUETOOTH) View.GONE else View.VISIBLE
    }

    private suspend fun updateButtonState() {
        disconnectButton.visibility =
            if (PrintUtils.isConnected()) View.VISIBLE else View.GONE
    }
}
