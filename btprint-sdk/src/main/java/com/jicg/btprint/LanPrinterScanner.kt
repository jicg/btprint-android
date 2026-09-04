package com.jicg.btprint

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext

/**
 * 局域网打印机扫描器：通过 TCP connect 探测常见打印端口
 * （9100 = 标准 RAW 打印端口，4001 = 部分国产打印机）
 * 纯 JVM 实现，不依赖 Android API，可在单元测试中直接运行
 */
object LanPrinterScanner {

    /** 扫描命中的网络打印机 */
    data class FoundPrinter(val host: String, val port: Int)

    /** 常见打印机端口 */
    val COMMON_PORTS: IntArray = intArrayOf(9100, 4001)

    private const val CONNECT_TIMEOUT_MS = 200
    private const val PARALLELISM = 128
    private const val PROGRESS_STEP = 50

    /**
     * 扫描本机所在的局域网（按 /24 子网处理，覆盖家用/办公常见网段）
     * @param ports 要探测的端口列表
     * @param onFound 每命中一台打印机立即回调（在 IO 线程，UI 更新需自行切主线程）
     * @param onProgress 进度回调（scanned/total，在 IO 线程）
     * @return 扫描结果（按 IP、端口排序）；扫描期间协程被取消则异常向上传播
     */
    suspend fun scan(
        ports: IntArray = COMMON_PORTS,
        onFound: (FoundPrinter) -> Unit = {},
        onProgress: (scanned: Int, total: Int) -> Unit = { _, _ -> }
    ): List<FoundPrinter> = withContext(Dispatchers.IO) {
        val targets = localIpv4Addresses().flatMap { hostsOf(it) }
        if (targets.isEmpty() || ports.isEmpty()) return@withContext emptyList()

        val total = targets.size * ports.size
        val scanned = AtomicInteger(0)
        val found = CopyOnWriteArrayList<FoundPrinter>()
        val semaphore = Semaphore(PARALLELISM)

        coroutineScope {
            targets.map { host ->
                async {
                    semaphore.withPermit {
                        // 取消后不再派发新探测；已阻塞的 connect 最多超时后自然退出
                        coroutineContext.ensureActive()
                        ports.forEach { port ->
                            if (probe(host, port)) {
                                FoundPrinter(host, port).also {
                                    found.add(it)
                                    onFound(it)
                                }
                            }
                            val done = scanned.incrementAndGet()
                            if (done % PROGRESS_STEP == 0 || done >= total) onProgress(done, total)
                        }
                    }
                }
            }.awaitAll()
        }
        found.sortedWith(compareBy({ it.host }, { it.port }))
    }

    /** TCP connect 探测：端口开放返回 true，超时/拒绝返回 false */
    internal fun probe(host: String, port: Int, timeoutMs: Int = CONNECT_TIMEOUT_MS): Boolean =
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                true
            }
        } catch (e: Exception) {
            false
        }

    /**
     * 本机所有局域网 IPv4 地址（site-local，排除回环、链路本地与点对点网卡如 VPN）
     */
    fun localIpv4Addresses(): List<String> {
        val interfaces = try {
            NetworkInterface.getNetworkInterfaces()
        } catch (e: Exception) {
            null
        } ?: return emptyList()
        return try {
            Collections.list(interfaces)
                .filter { it.isUp && !it.isLoopback && !it.isPointToPoint }
                .flatMap { ni -> Collections.list(ni.inetAddresses) }
                .filterIsInstance<Inet4Address>()
                .filter { it.isSiteLocalAddress }
                .mapNotNull { it.hostAddress }
                .distinct()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 由本机 IP 推导 /24 子网内的全部主机地址（去掉网络地址 .0 与广播地址 .255）。
     * 其它前缀长度的网段请通过手动输入 IP 连接
     */
    internal fun hostsOf(baseIp: String): List<String> {
        val idx = baseIp.lastIndexOf('.')
        if (idx <= 0) return emptyList()
        val prefix = baseIp.substring(0, idx + 1)
        return (1..254).map { "$prefix$it" }
    }
}
