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
            throw e
        }
        socket = s
        outputStream = s.outputStream
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
