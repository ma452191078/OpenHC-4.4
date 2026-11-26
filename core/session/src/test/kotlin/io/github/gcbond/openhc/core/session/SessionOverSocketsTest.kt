package io.github.gcbond.openhc.core.session

import io.github.gcbond.openhc.core.dmsdp.DmsdpAction
import io.github.gcbond.openhc.core.dmsdp.DmsdpChannel
import io.github.gcbond.openhc.core.dmsdp.DmsdpEvent
import io.github.gcbond.openhc.core.dmsdp.KeepAliveMode
import io.github.gcbond.openhc.core.hisight.AccessUnitSink
import io.github.gcbond.openhc.core.hisight.HiSightChannel
import io.github.gcbond.openhc.core.hisight.HiSightOutput
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 编排器（假的层）跑在真实的执行器上，对端是本机回环上按脚本行事的「手机」：
 * 验证监听、端口回告、主动连接、UDP、写和关闭在真实 socket 上的衔接，以及结束后没有遗留线程。
 */
class SessionOverSocketsTest {

    /** 转发给真正的会话，同时记下监听开在了哪个端口，供「手机」去连。 */
    private class Tap(private val inner: SessionLogic) : SessionLogic by inner {
        val listenerOf = ConcurrentHashMap<SessionChannel, Int>()
        val portOf = ConcurrentHashMap<Int, Int>()

        private fun note(outputs: List<SessionOutput>): List<SessionOutput> {
            for (o in outputs) if (o is SessionAction.Listen) listenerOf[o.channel] = o.id
            return outputs
        }

        override fun start(nowMillis: Long) = note(inner.start(nowMillis))
        override fun onListening(id: Int, port: Int, nowMillis: Long): List<SessionOutput> {
            portOf[id] = port
            return note(inner.onListening(id, port, nowMillis))
        }
        override fun onAccepted(id: Int, remoteHost: String, nowMillis: Long) = note(inner.onAccepted(id, remoteHost, nowMillis))
        override fun onData(id: Int, data: ByteArray, offset: Int, length: Int, nowMillis: Long) =
            note(inner.onData(id, data, offset, length, nowMillis))

        fun portFor(channel: SessionChannel): Int {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (System.nanoTime() < deadline) {
                val port = listenerOf[channel]?.let { portOf[it] }
                if (port != null) return port
                Thread.sleep(5)
            }
            throw AssertionError("$channel 的监听没有开好")
        }
    }

    private fun Socket.expect(text: String) {
        val n = text.bytes().size
        val out = ByteArrayOutputStream()
        val buf = ByteArray(n)
        while (out.size() < n) {
            val r = getInputStream().read(buf, 0, n - out.size())
            if (r < 0) break
            out.write(buf, 0, r)
        }
        assertEquals(text, out.toByteArray().text())
    }

    private fun Socket.send(text: String) {
        getOutputStream().write(text.bytes())
    }

    private fun Socket.assertEof() {
        val r = try {
            getInputStream().read()
        } catch (e: java.net.SocketException) {
            -1
        }
        assertEquals(-1, r)
    }

    private fun freePort(): Int = ServerSocket(0, 1, InetAddress.getByName(LOOPBACK)).use { it.localPort }

    @Test
    fun wholeSessionRunsOverLoopbackSockets() {
        val prefix = "over-sockets-${COUNTER.incrementAndGet()}"
        val events: MutableList<SessionEvent> = Collections.synchronizedList(ArrayList())
        val frames = AtomicInteger()
        val layers = FakeLayers()
        val config = Synthetic.config(localIp = LOOPBACK, port = freePort(), controlBindHost = LOOPBACK)
        val tap = Tap(ReceiverSession(config, AccessUnitSink { frames.incrementAndGet() }, layers))
        val runner = SocketRunner(tap, { events += it }, SocketRunner.Options(threadName = prefix))

        val phoneUdp = DatagramSocket(InetSocketAddress(LOOPBACK, 0)).apply { soTimeout = 5_000 }
        val hisightServer = ServerSocket(0, 1, InetAddress.getByName(LOOPBACK))
        val videoServer = ServerSocket(0, 1, InetAddress.getByName(LOOPBACK))
        val remoteServer = ServerSocket(0, 1, InetAddress.getByName(LOOPBACK))
        layers.nearby.onFeed = { text ->
            when (text) {
                "auth?" -> listOf(Synthetic.authRequest())
                "dmsdp?" -> listOf(Synthetic.dmsdpRequest())
                else -> emptyList()
            }
        }
        layers.dmsdp.onFeed = { channel, text ->
            when {
                channel == DmsdpChannel.SESSION && text == "nego" -> listOf(DmsdpAction.OpenControlListener)
                text == "crypto" -> listOf(
                    DmsdpAction.Send(DmsdpChannel.CONTROL, "crypto-ok".bytes()),
                    DmsdpAction.OpenKeepAliveSocket(phoneUdp.localPort),
                    DmsdpEvent.EncryptionEnabled(KeepAliveMode.UDP),
                )
                text == "display" -> listOf(DmsdpEvent.DisplayPortAnnounced(hisightServer.localPort))
                else -> emptyList()
            }
        }
        // 第一次定时到期时发一个 UDP 保活。
        layers.dmsdp.onTickHandler = {
            layers.dmsdp.nextTickAtMillis = null
            listOf(DmsdpAction.SendDatagram("ka".bytes()))
        }
        layers.hisight.onFeedControl = { text ->
            when (text) {
                "setup" -> listOf(
                    HiSightOutput.Send(HiSightChannel.CONTROL, "setup-ok".bytes()),
                    HiSightOutput.StreamNegotiated(1280, 720, 30, touchInput = false, keyInput = false, textInput = false),
                    HiSightOutput.Connect(HiSightChannel.VIDEO, videoServer.localPort),
                    HiSightOutput.Connect(HiSightChannel.REMOTE_CONTROL, remoteServer.localPort),
                )
                "play-ok" -> listOf(HiSightOutput.PlayAccepted)
                else -> emptyList()
            }
        }

        runner.start()
        try {
            val control = Socket(LOOPBACK, tap.portFor(SessionChannel.CONTROL)).apply { soTimeout = 5_000 }
            control.expect("connect-id")
            control.send("auth?")
            val authPort = tap.portFor(SessionChannel.AUTH)
            control.expect("accept:19:$authPort")

            val auth = Socket(LOOPBACK, authPort).apply { soTimeout = 5_000 }
            auth.send("hello")
            auth.expect("hello-reply")
            auth.send("done")
            auth.expect("done-reply")

            control.send("dmsdp?")
            val dmsdpPort = tap.portFor(SessionChannel.DMSDP_SESSION)
            control.expect("accept:20:$dmsdpPort")
            val dmsdp = Socket(LOOPBACK, dmsdpPort).apply { soTimeout = 5_000 }
            dmsdp.send("nego")
            val controlPort = tap.portFor(SessionChannel.DMSDP_CONTROL)
            dmsdp.expect("control-port:$controlPort")
            val dmsdpControl = Socket(LOOPBACK, controlPort).apply { soTimeout = 5_000 }
            dmsdpControl.expect("OPTIONS")
            dmsdpControl.send("crypto")
            dmsdpControl.expect("crypto-ok")

            // 定时器到期 → UDP 保活发到手机；手机回一个，会话收到。
            layers.dmsdp.nextTickAtMillis = 0
            runner.submit { emptyList() }
            val packet = DatagramPacket(ByteArray(64), 64)
            phoneUdp.receive(packet)
            assertEquals("ka", packet.data.text(0, packet.length))
            phoneUdp.send(DatagramPacket("pong".bytes(), 4, packet.socketAddress))

            dmsdpControl.send("display")
            val hisight = hisightServer.accept().apply { soTimeout = 5_000 }
            hisight.expect("ANNOUNCE")
            hisight.send("setup")
            hisight.expect("setup-ok")
            val video = videoServer.accept().apply { soTimeout = 5_000 }
            val remote = remoteServer.accept().apply { soTimeout = 5_000 }
            hisight.expect("PLAY")
            hisight.send("play-ok")
            video.send("frame")
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (frames.get() == 0 && System.nanoTime() < deadline) Thread.sleep(5)
            assertEquals(1, frames.get())
            assertTrue("dmsdp.onDatagram(pong)" in layers.calls, layers.calls.toString())

            // 本机结束：先 TEARDOWN，再由上到下关闭全部连接。
            runner.close()
            assertTrue(runner.isTerminated)
            hisight.expect("TEARDOWN")
            for (socket in listOf(hisight, video, remote, dmsdpControl, dmsdp, auth, control)) {
                socket.assertEof()
                socket.close()
            }
        } finally {
            runner.close()
            phoneUdp.close()
            hisightServer.close()
            videoServer.close()
            remoteServer.close()
        }

        val names = events.filter { it !is SessionEvent.Diagnostic }.map { it.javaClass.simpleName }
        // PLAY 应答走投屏控制连接、第一帧走视频连接：两条 TCP 各有读线程，到达协议线程的先后不确定，
        // 所以 PlayAccepted 与 VideoStarted 只要求都出现在 VideoNegotiated 之后、Ended 之前，不规定彼此先后。
        assertEquals(
            listOf(
                "ControlListening", "PhoneConnected", "AuthStarted", "ShowPinCode", "Authenticated", "DeviceSessionReady",
                "ProjectionRequested", "VideoNegotiated",
            ),
            names.take(8),
        )
        assertEquals(setOf("PlayAccepted", "VideoStarted"), names.subList(8, 10).toSet())
        assertEquals(listOf("Ended"), names.drop(10))
        val alive = Thread.getAllStackTraces().keys.filter { it.name.startsWith(prefix) && it.isAlive }
        assertTrue(alive.isEmpty(), "线程没有退出: ${alive.map { it.name }}")
    }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        val COUNTER = AtomicInteger()
    }
}
