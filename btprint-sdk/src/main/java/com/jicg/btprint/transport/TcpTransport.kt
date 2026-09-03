package com.jicg.btprint.transport

import android.util.Log
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 网络打印机传输层：ESC/POS over TCP（Wi-Fi / 以太网机型）
 * 默认端口 9100，是 ESC/POS 网络打印的事实标准端口
 */
class TcpTransport(
    private val host: String,
    private val port: Int = DEFAULT_PORT,
    /** TCP 连接超时（毫秒） */
    private val connectTimeoutMs: Int = 10_000,
) : PrintTransport {

    @Volatile
    private var socket: Socket? = null

    @Volatile
    private var outputStream: OutputStream? = null

    override val target: ConnectionTarget = ConnectionTarget.NetworkTarget(host, port)

    override var onDisconnected: (() -> Unit)? = null

    override val isConnected: Boolean
        get() = socket?.isConnected == true && socket?.isClosed == false

    override suspend fun connect() {
        val s = Socket()
        try {
            s.connect(InetSocketAddress(host, port), connectTimeoutMs)
        } catch (e: IOException) {
            try {
                s.close()
            } catch (closeError: Exception) {
                Log.w(TAG, "关闭失败 socket 出错", closeError)
            }
            throw e
        }
        socket = s
        outputStream = s.getOutputStream()
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
            Log.e(TAG, "关闭 TCP 连接失败", e)
        }
    }

    companion object {
        private const val TAG = "TcpTransport"
        const val DEFAULT_PORT: Int = 9100
    }
}
