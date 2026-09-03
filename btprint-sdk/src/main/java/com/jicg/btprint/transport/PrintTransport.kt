package com.jicg.btprint.transport

import android.bluetooth.BluetoothDevice
import java.io.IOException

/**
 * 打印机连接目标
 */
sealed interface ConnectionTarget {
    /** 展示给用户的名称（设备名或 host:port） */
    val displayName: String

    /** 蓝牙设备（经典 SPP 或 BLE） */
    data class BluetoothTarget(val device: BluetoothDevice) : ConnectionTarget {
        override val displayName: String
            get() = try {
                device.name?.takeIf { it.isNotBlank() } ?: device.address
            } catch (e: SecurityException) {
                device.address
            }
    }

    /** 网络打印机（Wi-Fi / 以太网，ESC/POS over TCP） */
    data class NetworkTarget(val host: String, val port: Int) : ConnectionTarget {
        override val displayName: String get() = "$host:$port"
    }
}

/**
 * 打印机传输层抽象
 * SPP / BLE / TCP 各自实现，BtPrintManager 只负责 ESC/POS 指令编排
 *
 * 实现约定：
 * - [connect] 可阻塞，调用方负责协程调度与串行化
 * - [write] 未连接必须抛 [IOException]，由调用方统一收尾
 * - [close] 幂等，允许重复调用
 */
interface PrintTransport {
    /** 本传输层连接的目标 */
    val target: ConnectionTarget

    /** 连接是否可用（纯状态查询，不写字节） */
    val isConnected: Boolean

    /**
     * 连接意外断开回调（远端断开、链路丢失）。
     * 仅已建立的连接断开会触发；本地 [close] 不触发。
     */
    var onDisconnected: (() -> Unit)?

    /** 建立连接 */
    suspend fun connect()

    /** 写入数据（实现内部可自行分包/限流） */
    suspend fun write(data: ByteArray)

    /** 关闭连接，幂等 */
    fun close()
}
