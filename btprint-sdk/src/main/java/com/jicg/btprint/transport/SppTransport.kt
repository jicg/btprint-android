package com.jicg.btprint.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import java.io.IOException
import java.io.OutputStream
import java.util.UUID

/**
 * 经典蓝牙 SPP（串口协议）传输层，RFCOMM 通道
 */
@SuppressLint("MissingPermission")
class SppTransport(
    context: Context,
    private val device: BluetoothDevice,
    /** 连接前是否取消系统蓝牙扫描（扫描会拖慢 RFCOMM 连接；重连场景调用方可能已停扫） */
    private val cancelDiscovery: Boolean = true,
) : PrintTransport {

    private val adapter: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

    @Volatile
    private var socket: BluetoothSocket? = null

    @Volatile
    private var outputStream: OutputStream? = null

    override val target: ConnectionTarget = ConnectionTarget.BluetoothTarget(device)

    override var onDisconnected: (() -> Unit)? = null

    override val isConnected: Boolean
        get() = socket?.isConnected == true

    override suspend fun connect() {
        if (cancelDiscovery) {
            try {
                adapter?.adapter?.takeIf { it.isDiscovering }?.cancelDiscovery()
            } catch (e: SecurityException) {
                Log.w(TAG, "取消扫描失败: 缺少蓝牙权限", e)
            }
        }
        val s = device.createRfcommSocketToServiceRecord(UUID.fromString(SPP_UUID))
        try {
            s.connect()
        } catch (e: IOException) {
            try {
                s.close()
            } catch (closeError: Exception) {
                Log.w(TAG, "关闭失败 socket 出错", closeError)
            }
            // 部分国产打印机 SDP 记录不完整，service-record 方式连不上但 RFCOMM channel 1 直连可用
            val fallback = connectRfcommChannel(1)
            if (fallback != null) {
                socket = fallback
                outputStream = fallback.outputStream
                return
            }
            throw e
        }
        socket = s
        outputStream = s.outputStream
    }

    /**
     * 反射按 RFCOMM 通道号直连（隐藏 API，部分系统版本被限制），失败返回 null
     */
    private fun connectRfcommChannel(channel: Int): BluetoothSocket? = try {
        val method = BluetoothDevice::class.java.getMethod(
            "createRfcommSocket", Int::class.javaPrimitiveType
        )
        val socket = method.invoke(device, channel) as BluetoothSocket
        try {
            socket.connect()
            socket
        } catch (e: IOException) {
            try {
                socket.close()
            } catch (closeError: Exception) {
                Log.w(TAG, "关闭 fallback socket 出错", closeError)
            }
            null
        }
    } catch (t: Throwable) {
        // 隐藏 API 被系统禁用时反射直接失败，走正常连接失败流程即可
        null
    }

    override suspend fun write(data: ByteArray) {
        val out = outputStream ?: throw IOException("打印机未连接")
        if (!isConnected) throw IOException("打印机未连接")
        out.write(data)
        out.flush()
    }

    override fun close() {
        val s = socket
        socket = null
        outputStream = null
        try {
            s?.close()
        } catch (e: Exception) {
            Log.e(TAG, "关闭 SPP 连接失败", e)
        }
    }

    companion object {
        private const val TAG = "SppTransport"
        val SPP_UUID: String = "00001101-0000-1000-8000-00805F9B34FB"
    }
}
