package io.github.gcbond.openhc.core.common.codec

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Ipv4TextTest {

    @Test
    fun acceptsCanonicalDottedDecimal() {
        for (ip in listOf("0.0.0.0", "192.0.2.10", "192.168.66.100", "10.123.200.254", "255.255.255.255", "1.2.3.4")) {
            assertTrue(Ipv4Text.isCanonical(ip), ip)
        }
    }

    @Test
    fun rejectsEverythingElse() {
        val bad = listOf(
            "", "1.2.3", "1.2.3.4.5", "1..3.4", ".1.2.3", "1.2.3.",
            "192.168.066.100", "01.2.3.4", "00.1.1.1", // 前导 0
            "256.1.1.1", "1.2.3.999", "1000.1.1.1",
            "１92.168.66.100", "1.2.3.a", "+1.2.3.4", "-1.2.3.4", " 1.2.3.4", "1.2.3.4 ",
        )
        for (ip in bad) assertFalse(Ipv4Text.isCanonical(ip), ip)
    }
}
