package io.github.gcbond.openhc.tools.desktop

import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class DesktopOptionsTest {
    private val required = arrayOf(
        "--identity", "/tmp/id.json", "--trust-dir", "/tmp/trust",
        "--ip", "192.168.66.150", "--port", "32000", "--bt", "0A:1B:2C:3D:4E:5F", "--video-out", "/tmp/out.h264",
    )

    @Test
    fun requiredOnlyUsesDefaults() {
        val o = DesktopOptions.parse(required)
        assertEquals(Paths.get("/tmp/id.json"), o.identity)
        assertEquals("192.168.66.150", o.ip)
        assertEquals(32000, o.port)
        assertEquals("0.0.0.0", o.bindIp)
        assertEquals(listOf(1280, 720, 30, 160), listOf(o.width, o.height, o.fps, o.dpi))
        assertEquals(1.0, o.density)
        assertNull(o.gop)
        assertNull(o.bitrate)
        assertNull(o.carInfoOut)
        assertNull(o.durationSeconds)
        assertEquals(true, o.localFilter)
        assertEquals(false, DesktopOptions.parse(required + arrayOf("--local-filter", "off")).localFilter)
        assertFailsWith<UsageException> { DesktopOptions.parse(required + arrayOf("--local-filter", "maybe")) }
    }

    @Test
    fun optionalValuesAndEqualsForm() {
        val o = DesktopOptions.parse(
            required + arrayOf("--width=1920", "--height", "1080", "--fps=55", "--density", "1.5", "--gop", "60",
                "--bitrate=8000000", "--carinfo-out", "/tmp/carinfo.bin", "--duration=90", "--bind-ip", "192.168.66.150"),
        )
        assertEquals(listOf(1920, 1080, 55), listOf(o.width, o.height, o.fps))
        assertEquals(1.5, o.density)
        assertEquals(60, o.gop)
        assertEquals(8_000_000, o.bitrate)
        assertEquals(Paths.get("/tmp/carinfo.bin"), o.carInfoOut)
        assertEquals(90L, o.durationSeconds)
        assertEquals("192.168.66.150", o.bindIp)
    }

    @Test
    fun mistakesAreReported() {
        val missing = assertFailsWith<UsageException> { DesktopOptions.parse(arrayOf("--identity", "x")) }
        assertEquals(true, missing.message!!.contains("--trust-dir"))
        assertFailsWith<UsageException> { DesktopOptions.parse(required + arrayOf("--nope", "1")) }
        assertFailsWith<UsageException> { DesktopOptions.parse(required + arrayOf("--fps", "abc")) }
        assertFailsWith<UsageException> { DesktopOptions.parse(required + arrayOf("--fps", "0")) }
        // 同一个参数写两次。
        assertFailsWith<UsageException> { DesktopOptions.parse(required + arrayOf("--port", "1")) }
        assertFailsWith<UsageException> { DesktopOptions.parse(required + arrayOf("--duration", "-5")) }
        assertFailsWith<UsageException> { DesktopOptions.parse(required + arrayOf("--width")) }
        assertFailsWith<UsageException> { DesktopOptions.parse(arrayOf("positional")) }
        assertFailsWith<HelpRequested> { DesktopOptions.parse(arrayOf("--help")) }
    }
}
