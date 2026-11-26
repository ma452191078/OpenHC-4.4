package io.github.gcbond.openhc.core.session

import io.github.gcbond.openhc.core.hisight.AccessUnitSink
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.ArrayDeque
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/** 无线会话经桥接跑在执行器上：平台输出与 TCP 输出的先后、以及在真实执行器和回环 socket 上的衔接。 */
class WirelessRunnerBridgeTest {

    /** 假执行器：执行桥接返回的 TCP 输出，再跑排到「协议线程」上的任务，全部记进 [trace]。 */
    private class FakeExecutor {
        val layers = FakeWirelessLayers()
        val session = WirelessReceiverSession(WirelessSynthetic.config(), AccessUnitSink { }, layers)
        val trace = ArrayList<String>()
        val platform = ArrayList<WirelessOutput>()
        val tcp = ArrayList<SessionOutput>()
        private val tasks = ArrayDeque<(Long) -> List<SessionOutput>>()
        val bridge = WirelessRunnerBridge(session) { o ->
            platform += o
            trace += "platform ${o::class.simpleName}"
        }

        init {
            bridge.attach { task -> tasks.addLast(task) }
        }

        fun run(outputs: List<SessionOutput>) {
            execute(outputs)
            while (tasks.isNotEmpty()) execute(tasks.removeFirst()(0))
        }

        fun execute(outputs: List<SessionOutput>) {
            for (o in outputs) {
                tcp += o
                trace += "tcp ${o::class.simpleName}"
            }
        }

        fun bluetooth(id: Int, text: String) {
            val bytes = text.bytes()
            run(bridge.onData(id, bytes, 0, bytes.size, 0))
        }

        inline fun <reified T : WirelessOutput> lastPlatform(): T = platform.filterIsInstance<T>().last()
    }

    @Test
    fun platformOutputsAfterTcpTeardownWaitForTheExecutor() {
        val x = FakeExecutor()
        x.run(x.bridge.start(0))
        val control = x.lastPlatform<WirelessAction.ListenRfcomm>()
        x.run(x.bridge.onListening(control.id, 0, 0))
        x.run(x.bridge.onAccepted(control.acceptId, WirelessSynthetic.PHONE_BLUETOOTH, 0))
        x.bluetooth(control.acceptId, "hello")
        val auth = x.lastPlatform<WirelessAction.ListenRfcomm>()
        x.run(x.bridge.onListening(auth.id, 5, 0))
        x.run(x.bridge.onAccepted(auth.acceptId, WirelessSynthetic.PHONE_BLUETOOTH, 0))
        x.bluetooth(auth.acceptId, "hello")
        x.bluetooth(auth.acceptId, "done")
        // 认证通过就开热点；热点开好、协商完，才在热点地址上开控制监听。
        val start = x.lastPlatform<WirelessAction.StartWifiNetwork>()
        x.run(x.bridge.onWifiNetworkReady(start.id, WirelessSynthetic.hotspot(), 0))
        x.bluetooth(control.acceptId, "nego")
        x.bluetooth(control.acceptId, "confirm")
        val listen = x.tcp.filterIsInstance<SessionAction.Listen>().single()
        // 会话事件（认证开始、认证成功）走执行器那一侧，交给它的事件回调。
        assertEquals(listOf("AuthStarted", "Authenticated"), x.tcp.filterIsInstance<SessionEvent>().map { it::class.simpleName })
        x.trace.clear()
        x.run(x.bridge.onListening(listen.id, 40100, 0))
        assertEquals(listOf("platform RfcommWrite"), x.trace)
        x.bluetooth(control.acceptId, "mac")
        x.run(x.bridge.onAccepted(listen.acceptId, WirelessSynthetic.PHONE_WIFI_IP, 0))

        // 本机结束：内嵌编排器的 TCP 收尾先交给执行器；蓝牙、WiFi 的收尾排到它后面。
        x.trace.clear()
        val first = x.bridge.stop(0)
        assertEquals(listOf("Close", "Close"), first.map { it::class.simpleName })
        assertTrue(x.trace.isEmpty())
        assertTrue(x.session.isEnded)
        assertFalse(x.bridge.isEnded)
        // 排队期间来的输入不插队（会话已结束，也没有新输出）。
        assertEquals(emptyList(), x.bridge.onTick(0))
        x.run(first)
        assertEquals(
            listOf(
                "tcp Close", "tcp Close",
                "platform RfcommClose", "platform RfcommClose", "platform StopWifiNetwork", "platform Ended",
            ),
            x.trace,
        )
        assertTrue(x.bridge.isEnded)
    }

    @Test
    fun runsOnSocketRunnerOverLoopback() {
        val layers = FakeWirelessLayers()
        val session = WirelessReceiverSession(WirelessSynthetic.config(), AccessUnitSink { }, layers)
        val platform = LinkedBlockingQueue<WirelessOutput>()
        val events = LinkedBlockingQueue<SessionEvent>()
        val bridge = WirelessRunnerBridge(session) { platform.add(it) }
        val runner = SocketRunner(bridge, { events.add(it) }, SocketRunner.Options(threadName = "wireless-test"))
        bridge.attach(runner)

        fun <T : WirelessOutput> next(type: Class<T>): T {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (System.nanoTime() < deadline) {
                val o = platform.poll(100, TimeUnit.MILLISECONDS) ?: continue
                if (type.isInstance(o)) return type.cast(o)
            }
            fail("等不到 ${type.simpleName}")
        }

        fun <T : SessionEvent> event(type: Class<T>): T {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (System.nanoTime() < deadline) {
                val e = events.poll(100, TimeUnit.MILLISECONDS) ?: continue
                if (type.isInstance(e)) return type.cast(e)
            }
            fail("等不到 ${type.simpleName}")
        }

        fun bluetooth(id: Int, text: String) {
            val bytes = text.bytes()
            runner.submit { now -> bridge.onData(id, bytes, 0, bytes.size, now) }
        }

        /** 在协议线程上读假层的状态（假层只在协议线程上被改）。 */
        fun dmsdpPhoneIp(): String? {
            val answer = LinkedBlockingQueue<Array<String?>>()
            runner.submit { _ ->
                answer.add(arrayOf(layers.wifi.dmsdpPhoneIp))
                emptyList()
            }
            return answer.poll(5, TimeUnit.SECONDS)?.get(0)
        }

        runner.start()
        try {
            // 蓝牙段由测试扮演平台：结果一律经 runner.submit 交回协议线程。
            val control = next(WirelessAction.ListenRfcomm::class.java)
            runner.submit { now -> bridge.onListening(control.id, 0, now) }
            next(WirelessEvent.PairingReady::class.java)
            runner.submit { now -> bridge.onAccepted(control.acceptId, WirelessSynthetic.PHONE_BLUETOOTH, now) }
            bluetooth(control.acceptId, "hello")
            val auth = next(WirelessAction.ListenRfcomm::class.java)
            runner.submit { now -> bridge.onListening(auth.id, 5, now) }
            runner.submit { now -> bridge.onAccepted(auth.acceptId, WirelessSynthetic.PHONE_BLUETOOTH, now) }
            bluetooth(auth.acceptId, "hello")
            bluetooth(auth.acceptId, "done")
            event(SessionEvent.Authenticated::class.java)
            // 认证通过就开热点。「热点」用本机回环代替：车机地址 127.0.0.1。
            val start = next(WirelessAction.StartWifiNetwork::class.java)
            val net = WirelessSynthetic.hotspot(5745, localIp = "127.0.0.1")
            runner.submit { now -> bridge.onWifiNetworkReady(start.id, net, now) }
            bluetooth(control.acceptId, "nego")
            assertEquals("dc:5745", next(WirelessAction.RfcommWrite::class.java).bytes.text())
            bluetooth(control.acceptId, "confirm")
            // 执行器开好监听（系统分配端口）之后，会话才向手机要 MAC。
            val ask = next(WirelessAction.RfcommWrite::class.java)
            assertEquals("mac?", ask.bytes.text())
            bluetooth(control.acceptId, "mac")
            val sent = next(WirelessEvent.ConnectionInfoSent::class.java)
            assertTrue(sent.port in 1..65535)

            Socket().use { phone ->
                phone.connect(InetSocketAddress("127.0.0.1", sent.port), 3_000)
                phone.soTimeout = 5_000
                assertEquals("127.0.0.1", event(SessionEvent.PhoneConnected::class.java).phoneIp)
                assertEquals("connect-id", phone.readText(10))
                phone.getOutputStream().write("dmsdp?".bytes())
                val prefix = "accept:20:"
                assertEquals(prefix, phone.readText(prefix.length))
                val port = phone.readSome().toInt()
                Socket().use { dmsdp ->
                    dmsdp.connect(InetSocketAddress("127.0.0.1", port), 3_000)
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                    while (dmsdpPhoneIp() != "127.0.0.1") {
                        assertTrue(System.nanoTime() < deadline, "设备会话没有建起来")
                        Thread.sleep(5)
                    }
                }
            }
        } finally {
            runner.close()
        }
        assertTrue(runner.isTerminated)
        // 收尾：蓝牙两条连接、热点都交给了平台去关，Ended 在最后。
        val rest = ArrayList<WirelessOutput>()
        platform.drainTo(rest)
        assertIs<WirelessEvent.Ended>(rest.last())
        assertEquals(1, rest.count { it is WirelessAction.StopWifiNetwork })
        assertTrue(rest.count { it is WirelessAction.RfcommClose } >= 2)
    }

    /** 读到的第一段（回环上一次写的内容一次就能读完）。 */
    private fun Socket.readSome(): String {
        val buf = ByteArray(64)
        val r = getInputStream().read(buf)
        return if (r <= 0) "" else buf.copyOf(r).text()
    }

    private fun Socket.readText(length: Int): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(length)
        while (out.size() < length) {
            val r = getInputStream().read(buf, 0, length - out.size())
            if (r < 0) break
            out.write(buf, 0, r)
        }
        return out.toByteArray().text()
    }
}
