package com.jicg.btprint.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.UUID

/**
 * BLE（低功耗蓝牙）传输层，基于 GATT 透传 characteristic
 *
 * 适配国内热敏打印机常见的透传服务 UUID（FFE0/FFE1、FFE5/FFE9、Nordic UART、ISSC），
 * 未命中时兜底使用第一个带写属性的 characteristic。
 * 数据按 MTU 分包写入，带响应写逐包等待 onCharacteristicWrite 回执，避免冲掉打印机缓冲。
 */
@SuppressLint("MissingPermission")
class BleTransport(
    private val context: Context,
    private val device: BluetoothDevice,
    /** 整体连接超时（毫秒），包含服务发现 */
    private val connectTimeoutMs: Int = 15_000,
) : PrintTransport {

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var gatt: BluetoothGatt? = null

    @Volatile
    private var writeChar: BluetoothGattCharacteristic? = null

    /** GATT 协商后的 MTU，未协商完成前按默认 23 分包 */
    @Volatile
    private var mtu: Int = 23

    /** 是否使用无响应写（写完不等回执） */
    @Volatile
    private var noResponse: Boolean = false

    /** 连接已建立且可写（服务发现完成） */
    @Volatile
    private var ready: Boolean = false

    @Volatile
    private var closed: Boolean = false

    /** 当前带响应写的回执等待（回调线程与写入协程之间的一次性桥梁） */
    @Volatile
    private var pendingWrite: CompletableDeferred<Unit>? = null

    /** 连接/服务发现阶段的等待 */
    @Volatile
    private var connectDefer: CompletableDeferred<Unit>? = null

    override val target: ConnectionTarget = ConnectionTarget.BluetoothTarget(device)

    override var onDisconnected: (() -> Unit)? = null

    override val isConnected: Boolean
        get() = ready && gatt != null && !closed

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "BLE 已连接，开始服务发现")
                try {
                    g.discoverServices()
                } catch (e: Exception) {
                    fail(IOException("BLE 服务发现启动失败: ${e.message}"))
                }
            } else {
                val wasReady = ready
                fail(IOException("BLE 连接断开/失败, status=$status, newState=$newState"))
                if (wasReady) {
                    Log.i(TAG, "BLE 已建立的连接断开")
                    onDisconnected?.invoke()
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail(IOException("BLE 服务发现失败 status=$status"))
                return
            }
            val c = findWriteCharacteristic(g)
            if (c == null) {
                fail(IOException("未找到可写入的透传 characteristic"))
                return
            }
            writeChar = c
            noResponse =
                c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0 &&
                        c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE == 0
            ready = true
            // 尽力协商大 MTU；结果在 onMtuChanged 中生效，失败也不影响连接
            try {
                g.requestMtu(MAX_MTU)
            } catch (e: Exception) {
                Log.w(TAG, "请求 MTU 失败，按默认 23 分包", e)
            }
            Log.i(TAG, "BLE 透传通道就绪: ${c.uuid}, noResponse=$noResponse")
            connectDefer?.complete(Unit)
            connectDefer = null
        }

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "BLE MTU 协商完成: $newMtu")
                mtu = newMtu
            }
        }

        @Suppress("DEPRECATION") // 3 参回调在 API 33 标记弃用，但为兼容低版本仍需重写
        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            val d = pendingWrite ?: return
            pendingWrite = null
            if (status == BluetoothGatt.GATT_SUCCESS) {
                d.complete(Unit)
            } else {
                d.completeExceptionally(IOException("BLE 写入失败 status=$status"))
            }
        }
    }

    override suspend fun connect() {
        if (closed) throw IOException("传输层已关闭，请新建实例")
        val defer = CompletableDeferred<Unit>()
        connectDefer = defer
        mainHandler.post {
            val g = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    // 4 参重载（API 23）的回调固定投递到主线程，无需 Handler 变体；
                    // TRANSPORT_LE 强制走低功耗通道，避免双模打印机协商成经典通道
                    device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
                } else {
                    // API 21-22 无 4 参重载，退回 3 参（缺省自动选择传输方式）
                    @Suppress("DEPRECATION")
                    device.connectGatt(context, false, callback)
                }
            } catch (e: Exception) {
                connectDefer = null
                defer.completeExceptionally(IOException("BLE 连接启动失败: ${e.message}"))
                return@post
            }
            if (g == null) {
                connectDefer = null
                defer.completeExceptionally(IOException("BLE 连接启动失败: connectGatt 返回 null"))
            } else if (closed) {
                // 连接已超时/被取消（post 延迟到超时之后）：立即释放这个野生 Gatt，
                // 否则它永远收不到 disconnect/close，还会把 isConnected 顶成 true
                try {
                    g.close()
                } catch (e: Exception) {
                    Log.w(TAG, "关闭超时残留的 Gatt 失败", e)
                }
            } else {
                gatt = g
            }
        }
        withTimeoutOrNull(connectTimeoutMs.toLong()) {
            defer.await()
        } ?: run {
            close()
            throw IOException("BLE 连接超时(${connectTimeoutMs}ms)")
        }
    }

    override suspend fun write(data: ByteArray) {
        if (!ready) throw IOException("打印机未连接")
        // 每包有效载荷 = MTU - 3（ATT 头），未协商完成前为 20 字节
        val chunkSize = (mtu - 3).coerceAtLeast(MIN_CHUNK)
        var offset = 0
        while (offset < data.size) {
            val end = minOf(offset + chunkSize, data.size)
            writeChunk(data.copyOfRange(offset, end))
            offset = end
            // 无响应写拿不到回执，块间主动 pacing，避免打印机 BLE FIFO 溢出丢包
            if (noResponse && offset < data.size) delay(PACING_MS)
        }
    }

    private suspend fun writeChunk(chunk: ByteArray) {
        val deferred = CompletableDeferred<Unit>()
        mainHandler.post {
            val g = gatt
            val c = writeChar
            if (g == null || c == null || !ready || closed) {
                deferred.completeExceptionally(IOException("打印机未连接"))
                return@post
            }
            c.value = chunk
            if (noResponse) {
                // 无响应写拿不到单包回执，入队成功即视为成功
                if (g.writeCharacteristic(c)) {
                    deferred.complete(Unit)
                } else {
                    deferred.completeExceptionally(IOException("BLE 写入入队失败"))
                }
            } else {
                pendingWrite = deferred
                if (!g.writeCharacteristic(c)) {
                    pendingWrite = null
                    deferred.completeExceptionally(IOException("BLE 写入入队失败"))
                }
            }
        }
        deferred.await()
    }

    override fun close() {
        closed = true
        ready = false
        val g = gatt
        gatt = null
        writeChar = null
        connectDefer?.completeExceptionally(IOException("BLE 已关闭"))
        connectDefer = null
        pendingWrite?.completeExceptionally(IOException("BLE 已关闭"))
        pendingWrite = null
        mainHandler.post {
            try {
                g?.disconnect()
            } catch (e: Exception) {
                Log.w(TAG, "BLE disconnect 失败", e)
            }
            try {
                g?.close()
            } catch (e: Exception) {
                Log.w(TAG, "BLE close 失败", e)
            }
        }
    }

    /** 连接/发现阶段失败统一收尾 */
    private fun fail(error: IOException) {
        ready = false
        connectDefer?.completeExceptionally(error)
        connectDefer = null
        pendingWrite?.completeExceptionally(error)
        pendingWrite = null
    }

    private fun findWriteCharacteristic(g: BluetoothGatt): BluetoothGattCharacteristic? {
        val writable = BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
        // 常见打印机透传服务
        for ((serviceUuid, charUuid) in TRANSPARENT_UUIDS) {
            val service = g.getService(serviceUuid) ?: continue
            val c = service.getCharacteristic(charUuid) ?: continue
            if (c.properties and writable != 0) return c
        }
        // 兜底：任一带写属性的 characteristic
        for (service in g.services) {
            for (c in service.characteristics) {
                if (c.properties and writable != 0) return c
            }
        }
        return null
    }

    companion object {
        private const val TAG = "BleTransport"
        private const val MAX_MTU = 512
        private const val MIN_CHUNK = 20
        /** 无响应写的块间节流间隔，防止打印机 BLE FIFO 溢出 */
        private const val PACING_MS = 5L

        /** 常见透传服务 -> 写特征 */
        private val TRANSPARENT_UUIDS: List<Pair<UUID, UUID>> = listOf(
            // 国内打印机最常见透传服务
            UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb") to
                    UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb"),
            // 另一常见变体
            UUID.fromString("0000ffe5-0000-1000-8000-00805f9b34fb") to
                    UUID.fromString("0000ffe9-0000-1000-8000-00805f9b34fb"),
            // Nordic UART（nRF 系列透传）
            UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e") to
                    UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e"),
            // ISSC 透传
            UUID.fromString("49535343-fe7d-4ae5-8fa9-9fafd205e455") to
                    UUID.fromString("49535343-8841-43f4-a8d4-ecbe34729bb3"),
        )
    }
}
