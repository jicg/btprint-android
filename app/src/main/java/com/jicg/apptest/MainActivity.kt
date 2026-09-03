package com.jicg.apptest

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.jicg.btprint.BtPrintActivity
import com.jicg.btprint.BtPrintManager
import com.jicg.btprint.PrintUtils
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var btPrintButton: Button
    private lateinit var printTestButton: Button
    private lateinit var imagePreview: ImageView
    private var currentBitmap: Bitmap? = null

    private val selectImageLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.data?.let { uri ->
                    try {
                        // 从相册加载图片
                        currentBitmap = MediaStore.Images.Media.getBitmap(contentResolver, uri)
                        imagePreview.setImageBitmap(currentBitmap)
                        Toast.makeText(this, "图片加载成功", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(this, "加载图片失败: ${e.message}", Toast.LENGTH_SHORT)
                            .show()
                    }
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PrintUtils.init(this)
        currentBitmap = BitmapFactory.decodeResource(resources, R.mipmap.android)
//        imagePreview.setImageResource(R.mipmap.android)
        // 尝试自动连接上次的设备
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

        // 创建蓝牙打印按钮（未连接时跳转连接页，已连接时断开）
        btPrintButton = Button(this).apply {
            text = "蓝牙打印"
            setOnClickListener {
                lifecycleScope.launch {
                    if (PrintUtils.isConnected()) {
                        PrintUtils.disconnect()
                        Toast.makeText(this@MainActivity, "已断开打印机", Toast.LENGTH_SHORT)
                            .show()
                        updateButtonState()
                    } else {
                        // 跳转到蓝牙打印界面
                        startActivity(
                            Intent(
                                this@MainActivity,
                                BtPrintActivity::class.java
                            )
                        )
                    }
                }
            }
        }
        rootLayout.addView(btPrintButton)

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
                                "测试撒测测试测试测试测试撒测测试测试测试测试撒测\n测试测、试测试测试撒测测试测试。测试测试撒测测试测试测试",
                                fontSize = 0
                            )
                            PrintUtils.printTextWait("")
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
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            updateButtonState()
        }
    }

    private suspend fun updateButtonState() {
        if (PrintUtils.isConnected()) {
            btPrintButton.text = "断开打印"
        } else {
            btPrintButton.text = "蓝牙打印"
        }
    }
}