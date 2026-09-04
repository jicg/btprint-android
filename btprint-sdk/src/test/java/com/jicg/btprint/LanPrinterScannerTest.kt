package com.jicg.btprint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket

class LanPrinterScannerTest {

    @Test
    fun hostsOf_generatesFullSubnet() {
        val hosts = LanPrinterScanner.hostsOf("192.168.1.8")
        assertEquals(254, hosts.size)
        assertEquals("192.168.1.1", hosts.first())
        assertEquals("192.168.1.254", hosts.last())
    }

    @Test
    fun hostsOf_invalidInput_returnsEmpty() {
        assertTrue(LanPrinterScanner.hostsOf("").isEmpty())
        assertTrue(LanPrinterScanner.hostsOf("abc").isEmpty())
        assertTrue(LanPrinterScanner.hostsOf(".8").isEmpty())
    }

    @Test
    fun probe_closedPort_returnsFalse() {
        assertFalse(LanPrinterScanner.probe("127.0.0.1", 1, 100))
    }

    @Test
    fun probe_openPort_returnsTrue() {
        ServerSocket(0).use { server ->
            assertTrue(LanPrinterScanner.probe("127.0.0.1", server.localPort, 500))
        }
    }

    @Test
    fun localIpv4Addresses_doesNotCrash() {
        // 无网卡环境（CI）返回空列表，有网卡环境至少不抛异常
        LanPrinterScanner.localIpv4Addresses()
    }
}
