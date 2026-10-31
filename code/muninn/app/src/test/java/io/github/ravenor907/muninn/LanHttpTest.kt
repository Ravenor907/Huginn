package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.net.LanHttp
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URL

/** Plain HTTP goes only to home-network addresses written as numbers. */
class LanHttpTest {
    @Test
    fun onlyHomeNetworkNumbers() {
        assertTrue(LanHttp.lanHost("192.168.1.20"))
        assertTrue(LanHttp.lanHost("10.0.0.5"))
        assertTrue(LanHttp.lanHost("172.16.4.1"))
        assertTrue(LanHttp.lanHost("[fe80::1]"))
        assertFalse(LanHttp.lanHost("8.8.8.8"))
        assertFalse(LanHttp.lanHost("172.32.0.1"))
        assertFalse(LanHttp.lanHost("127.0.0.1"))
        // Never a name (it could point anywhere once looked up).
        assertFalse(LanHttp.lanHost("example.com"))
        assertFalse(LanHttp.lanHost("router.local"))
        assertFalse(LanHttp.lanHost(""))
        assertFalse(LanHttp.lanHost(null))
    }

    @Test
    fun plainHttpElsewhereIsRefused() {
        val refused = try { LanHttp.open(URL("http://example.com/x")); false } catch (e: java.io.IOException) { true }
        assertTrue(refused)
        val lan = LanHttp.open(URL("http://192.168.1.20:53317/api/localsend/v2/info"))
        assertTrue(lan is LanHttp)
    }
}
