package com.jicg.btprint

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.graphics.Bitmap
import com.jicg.btprint.BtPrintManager.BarcodeType
import com.jicg.btprint.transport.ConnectionTarget
import com.jicg.btprint.transport.TcpTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 打印结果
 *
 * @param taskType 任务类型（如 printText、printImage、initPrinter）
 * @param success 是否成功
 * @param error 失败时的异常（success 为 false 时非空）
 */
data class PrintResult(
    val taskType: String,
    val success: Boolean,
    val error: Throwable? = null
)

/**
 * 打印工具类（门面模式）
 * 统一对外提供打印 API
 */
object PrintUtils {
    // ============ 从 BtPrintManager 重导出的常用常量（门面一站式使用） ============
    const val FONT_SIZE_SMALL = BtPrintManager.FONT_SIZE_SMALL    // 小字体
    const val FONT_SIZE_NORMAL = BtPrintManager.FONT_SIZE_NORMAL  // 正常字体
    const val FONT_SIZE_LARGE = BtPrintManager.FONT_SIZE_LARGE    // 大字体

    const val ALIGN_LEFT = BtPrintManager.ALIGN_LEFT
    const val ALIGN_CENTER = BtPrintManager.ALIGN_CENTER
    const val ALIGN_RIGHT = BtPrintManager.ALIGN_RIGHT

    const val CUT_FULL = BtPrintManager.CUT_FULL        // 全切
    const val CUT_PARTIAL = BtPrintManager.CUT_PARTIAL  // 半切（留连接点）

    /** GBK 编码（与 BtPrintManager 同一实例） */
    val CHARSET_GBK = BtPrintManager.CHARSET_GBK

    /**
     * 应用上下文（仅持有进程级 Application，生命周期与进程一致，无内存泄漏风险）
     * 注意：不要将 Activity/Service 等短生命周期 Context 存入此处
     */
    private lateinit var appContext: Application

    /**
     * 协程作用域（release 后可惰性重建）
     */
    @Volatile
    private var scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var initialized = false
    private var debugMode = false

    /**
     * 打印任务队列（严格 FIFO，逐个顺序执行）
     */
    @Volatile
    private var queueChannel: Channel<suspend () -> Unit>? = null
    private val _queueSize = MutableStateFlow(0)

    /**
     * 当前队列中待执行的任务数
     */
    val queueSize: StateFlow<Int> = _queueSize.asStateFlow()

    /**
     * 打印结果监听器（在 IO 线程回调，UI 更新需自行切主线程）
     */
    private var resultListener: ((PrintResult) -> Unit)? = null

    /**
     * 初始化打印工具
     * 建议在 Application.onCreate() 中调用
     * @param context 应用上下文
     */
    @Synchronized
    fun init(context: Context) {
        this.appContext = context.applicationContext as Application
        this.initialized = true
        // release() 后再次 init 时重建协程作用域
        ensureActiveScope()
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
     * 释放资源（释放后可通过 init 重新初始化）
     */
    fun release() {
        scope.cancel()
        queueChannel = null
        _queueSize.value = 0
        initialized = false
        // 容错：未调用 init 直接 release 时 appContext 未初始化
        if (::appContext.isInitialized) {
            BtPrintManager.getInstance(appContext).close()
        }
    }

    /**
     * 确保协程作用域与队列可用（被取消/关闭后惰性重建）
     */
    private fun ensureActiveScope() {
        if (!scope.isActive) {
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        }
        if (queueChannel == null) {
            startQueueConsumer()
        }
    }

    /**
     * 启动队列消费者协程（严格 FIFO 顺序执行任务）
     */
    private fun startQueueConsumer() {
        val channel = Channel<suspend () -> Unit>(Channel.UNLIMITED)
        queueChannel = channel
        scope.launch {
            // 队列被 clearQueue/release 替换（queueChannel 变更）时退出，丢弃剩余 pending 任务
            while (channel === queueChannel) {
                val task = channel.receiveCatching().getOrNull() ?: break
                try {
                    task()
                } catch (e: Exception) {
                    // 未知异常（如外部经 enqueue 提交的自定义任务）不能杀死消费协程，
                    // 否则后续任务永远得不到执行
                    if (debugMode) e.printStackTrace()
                } finally {
                    _queueSize.value = (_queueSize.value - 1).coerceAtLeast(0)
                }
            }
        }
    }

    /**
     * 设置打印结果监听器（null 表示取消监听）
     * 回调运行在 IO 线程，UI 更新需自行切换到主线程
     */
    fun setPrintResultListener(listener: ((PrintResult) -> Unit)?) {
        resultListener = listener
    }

    /**
     * 通知打印结果
     */
    private fun notifyResult(taskType: String, success: Boolean, error: Throwable? = null) {
        resultListener?.invoke(PrintResult(taskType, success, error))
    }

    /**
     * 获取蓝牙打印管理器
     */
    fun getPrintManager(): BtPrintManager {
        check(initialized) { "PrintUtils 未初始化，请先调用 PrintUtils.init(context)" }
        return BtPrintManager.getInstance(appContext)
    }

    /**
     * 将任务入队（严格 FIFO 顺序执行）
     * @return 入队协程的 Job（其完成表示任务已入队，不代表打印完成；打印结果通过 setPrintResultListener 获取）
     */
    fun enqueue(task: suspend () -> Unit): Job {
        // 同步触发未初始化检查，保证调用即报错
        getPrintManager()
        ensureActiveScope()
        return scope.launch {
            val channel = queueChannel
            if (channel != null) {
                _queueSize.value++
                try {
                    channel.send(task)
                } catch (e: Exception) {
                    // 队列已被 clearQueue/release 关闭：回滚计数，任务丢弃属预期行为
                    _queueSize.value = (_queueSize.value - 1).coerceAtLeast(0)
                }
            }
        }
    }

    /**
     * 清空队列中所有待执行任务（正在执行的任务不受影响）
     */
    fun clearQueue() {
        val channel = queueChannel
        queueChannel = null
        channel?.close()
        _queueSize.value = 0
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
     * 当前连接目标（蓝牙设备或网络地址），未连接时为 null
     */
    val connectionTarget: StateFlow<ConnectionTarget?>
        get() = getPrintManager().connectionTarget

    /**
     * 连接 BLE（低功耗蓝牙）打印机（异步）
     */
    fun connectBle(device: BluetoothDevice): Job {
        check(initialized) { "PrintUtils 未初始化，请先调用 PrintUtils.init(context)" }
        return scope.launch {
            val ok = try {
                getPrintManager().connectBle(device)
            } catch (e: Exception) {
                if (debugMode) e.printStackTrace()
                notifyResult("connectBle", false, e)
                return@launch
            }
            notifyResult("connectBle", ok)
        }
    }

    /**
     * 连接 BLE 打印机（挂起）
     * @return 是否连接成功
     */
    suspend fun connectBleWait(device: BluetoothDevice): Boolean {
        check(initialized) { "PrintUtils 未初始化，请先调用 PrintUtils.init(context)" }
        return getPrintManager().connectBle(device)
    }

    /**
     * 连接网络打印机 Wi-Fi / 以太网，ESC/POS over TCP（异步）
     * @param host 打印机 IP 或域名
     * @param port 端口，默认 9100
     */
    fun connectTcp(host: String, port: Int = TcpTransport.DEFAULT_PORT): Job {
        check(initialized) { "PrintUtils 未初始化，请先调用 PrintUtils.init(context)" }
        return scope.launch {
            val ok = try {
                getPrintManager().connectTcp(host, port)
            } catch (e: Exception) {
                if (debugMode) e.printStackTrace()
                notifyResult("connectTcp", false, e)
                return@launch
            }
            notifyResult("connectTcp", ok)
        }
    }

    /**
     * 连接网络打印机（挂起）
     * @return 是否连接成功
     */
    suspend fun connectTcpWait(host: String, port: Int = TcpTransport.DEFAULT_PORT): Boolean {
        check(initialized) { "PrintUtils 未初始化，请先调用 PrintUtils.init(context)" }
        return getPrintManager().connectTcp(host, port)
    }

    /**
     * 初始化打印机（异步）
     */
    fun initPrinter(): Job {
        return enqueue {
            try {
                getPrintManager().initPrinter()
                notifyResult("initPrinter", true)
            } catch (e: Exception) {
                if (debugMode) e.printStackTrace()
                notifyResult("initPrinter", false, e)
            }
        }
    }

    /**
     * 初始化打印机（挂起）
     */
    suspend fun initPrinterWait() {
        getPrintManager().initPrinter()
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
        return enqueue {
            try {
                getPrintManager().printText(text, fontSize, align, feedLines)
                notifyResult("printText", true)
            } catch (e: Exception) {
                if (debugMode) e.printStackTrace()
                notifyResult("printText", false, e)
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
    fun printTwo(
        text1: String,
        text2: String,
        fontSize: Int = FONT_SIZE_NORMAL
    ): Job {
        return enqueue {
            try {
                getPrintManager().printTwo(text1, text2, fontSize)
                notifyResult("printTwo", true)
            } catch (e: Exception) {
                if (debugMode) e.printStackTrace()
                notifyResult("printTwo", false, e)
            }
        }
    }

    /**
     * 打印两列文本（挂起）
     */
    suspend fun printTwoWait(
        text1: String,
        text2: String,
        fontSize: Int = FONT_SIZE_NORMAL
    ) {
        getPrintManager().printTwo(text1, text2, fontSize)
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
        return enqueue {
            try {
                getPrintManager().printThree(text1, text2, text3, fontSize)
                notifyResult("printThree", true)
            } catch (e: Exception) {
                if (debugMode) e.printStackTrace()
                notifyResult("printThree", false, e)
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
     * 设置纸张宽度（58mm / 80mm），影响多列排版、分割线与图片最大宽度
     * 设置后持久化，下次启动自动恢复
     */
    fun setPaperWidth(width: BtPrintManager.PaperWidth) {
        getPrintManager().paperWidth = width
    }

    /**
     * 获取当前纸张宽度（默认 58mm）
     */
    fun getPaperWidth(): BtPrintManager.PaperWidth {
        return getPrintManager().paperWidth
    }

    /**
     * 打印分割线
     * @param length 长度（字符数）；传 -1（默认）时自动使用当前纸宽的整行字符数
     */
    fun printDivider(char: String = "-", length: Int = -1): Job {
        return enqueue {
            try {
                getPrintManager().printDivider(char, length)
                notifyResult("printDivider", true)
            } catch (e: Exception) {
                if (debugMode) e.printStackTrace()
                notifyResult("printDivider", false, e)
            }
        }
    }

    /**
     * 打印分割线（挂起）
     * @param length 长度（字符数）；传 -1（默认）时自动使用当前纸宽的整行字符数
     */
    suspend fun printDividerWait(char: String = "-", length: Int = -1) {
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
        return enqueue {
            try {
                getPrintManager().printQrCode(text, width, height, align)
                notifyResult("printQrCode", true)
            } catch (e: Exception) {
                if (debugMode) e.printStackTrace()
                notifyResult("printQrCode", false, e)
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
        return enqueue {
            try {
                getPrintManager().printBarCode(text, width, height, align, barcodeType)
                notifyResult("printBarCode", true)
            } catch (e: Exception) {
                if (debugMode) e.printStackTrace()
                notifyResult("printBarCode", false, e)
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
        return enqueue {
            try {
                getPrintManager().printImage(bitmap, width, height, align)
                notifyResult("printImage", true)
            } catch (e: Exception) {
                if (debugMode) e.printStackTrace()
                notifyResult("printImage", false, e)
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
        return enqueue {
            try {
                getPrintManager().printText("", FONT_SIZE_NORMAL, ALIGN_LEFT, lines)
                notifyResult("printEmptyLines", true)
            } catch (e: Exception) {
                if (debugMode) e.printStackTrace()
                notifyResult("printEmptyLines", false, e)
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
     * @param dividerLength 分割线长度（字符数）；传 -1（默认）时自动使用当前纸宽的整行字符数
     */
    fun printTitle(
        text: String,
        fontSize: Int = FONT_SIZE_LARGE,
        align: Int = ALIGN_CENTER,
        dividerChar: String = "=",
        dividerLength: Int = -1
    ): Job {
        return enqueue {
            try {
                val manager = getPrintManager()
                manager.printDivider(dividerChar, dividerLength)
                manager.printText(text, fontSize, align, 1)
                manager.printDivider(dividerChar, dividerLength)
                notifyResult("printTitle", true)
            } catch (e: Exception) {
                if (debugMode) e.printStackTrace()
                notifyResult("printTitle", false, e)
            }
        }
    }

    /**
     * 打印标题（挂起）
     * @param dividerLength 分割线长度（字符数）；传 -1（默认）时自动使用当前纸宽的整行字符数
     */
    suspend fun printTitleWait(
        text: String,
        fontSize: Int = FONT_SIZE_LARGE,
        align: Int = ALIGN_CENTER,
        dividerChar: String = "=",
        dividerLength: Int = -1
    ) {
        val manager = getPrintManager()
        manager.printDivider(dividerChar, dividerLength)
        manager.printText(text, fontSize, align, 1)
        manager.printDivider(dividerChar, dividerLength)
    }

    /**
     * 切纸（异步，先走纸再切刀；无切刀的机型会忽略切刀命令）
     * @param mode CUT_FULL 全切 / CUT_PARTIAL 半切（留连接点）
     * @param feedLines 切纸前走纸行数
     */
    fun cutPaper(mode: Int = CUT_PARTIAL, feedLines: Int = 3): Job {
        return enqueue {
            try {
                getPrintManager().cutPaper(mode, feedLines)
                notifyResult("cutPaper", true)
            } catch (e: Exception) {
                if (debugMode) e.printStackTrace()
                notifyResult("cutPaper", false, e)
            }
        }
    }

    /**
     * 切纸（挂起）
     */
    suspend fun cutPaperWait(mode: Int = CUT_PARTIAL, feedLines: Int = 3) {
        getPrintManager().cutPaper(mode, feedLines)
    }

    /**
     * 打开钱箱（异步，仅带钱箱接口的机型有效）
     * @param pin 钱箱引脚：0=2 号引脚，1=5 号引脚
     */
    fun openCashDrawer(pin: Int = 0): Job {
        return enqueue {
            try {
                getPrintManager().openCashDrawer(pin)
                notifyResult("openCashDrawer", true)
            } catch (e: Exception) {
                if (debugMode) e.printStackTrace()
                notifyResult("openCashDrawer", false, e)
            }
        }
    }

    /**
     * 打开钱箱（挂起）
     */
    suspend fun openCashDrawerWait(pin: Int = 0) {
        getPrintManager().openCashDrawer(pin)
    }

    /**
     * 编码文本为 GBK 字节
     */
    fun encodeGbk(text: String): ByteArray {
        return text.toByteArray(CHARSET_GBK)
    }

    /**
     * 取消打印任务
     * 注意：仅对尚未出队执行的任务有效；任务一旦开始执行（或已出队）无法通过此 Job 中断，
     * 如需中断进行中的打印请使用 clearQueue() + disconnect()
     */
    fun cancelPrint(job: Job?) {
        job?.cancel()
    }
}
