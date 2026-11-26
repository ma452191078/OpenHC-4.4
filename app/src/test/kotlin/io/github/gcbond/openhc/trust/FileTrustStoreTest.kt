package io.github.gcbond.openhc.trust

import io.github.gcbond.openhc.core.auth.LocalIdentity
import io.github.gcbond.openhc.core.auth.ServiceId
import io.github.gcbond.openhc.core.auth.TrustedPeer
import io.github.gcbond.openhc.core.common.identity.AuthServiceIdentity
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 落盘的信任库：读写、原子替换、损坏处理。身份、密钥都是合成值。 */
class FileTrustStoreTest {
    private val root: File = Files.createTempDirectory("openhc-app-trust").toFile()
    private val dir = File(root, "trust")
    private val service = ServiceId.of(AuthServiceIdentity("org.example.auth", "EXAMPLE"))
    private val carId = "0123456789ABCDEF".toByteArray(Charsets.US_ASCII)
    private val phoneA = "FEDCBA9876543210".toByteArray(Charsets.US_ASCII)
    private val phoneB = "AAAABBBBCCCCDDDD".toByteArray(Charsets.US_ASCII)

    @AfterTest
    fun cleanUp() {
        root.walkTopDown().forEach { it.setWritable(true) }
        root.walkBottomUp().forEach { it.delete() }
    }

    /** 目录真的写不进去（以 root 身份运行时只读权限不起作用）。 */
    private fun cannotWrite(directory: File): Boolean = try {
        val probe = File(directory, "probe")
        val created = probe.createNewFile()
        if (created) probe.delete()
        !created
    } catch (e: IOException) {
        true
    }

    private fun identity() = LocalIdentity(service, carId, ByteArray(32) { it.toByte() })
    private fun peer(id: ByteArray, seed: Int = 0xA0) = TrustedPeer(service, id, ByteArray(32) { (seed + it).toByte() })
    private fun names(): List<String> = dir.list()!!.sorted()

    @Test
    fun missingDirectoryAndFileMeanAnEmptyStore() {
        val store = FileTrustStore.open(dir)
        assertTrue(dir.isDirectory)
        assertEquals(0, store.peerCount)
        assertNull(store.findPeer(service, phoneA))
        assertNull(store.findLocalIdentity(service, carId))
        assertEquals(emptyList(), names(), "只读不写，不建文件")
    }

    @Test
    fun pairingIsPersistedAndSurvivesReopen() {
        FileTrustStore.open(dir).commitPairing(identity(), peer(phoneA))
        assertEquals(listOf(FileTrustStore.FILE_NAME), names(), "临时文件已改名，没有残留")

        val reopened = FileTrustStore.open(dir)
        assertEquals(1, reopened.peerCount)
        assertContentEquals(peer(phoneA).publicKey, assertNotNull(reopened.findPeer(service, phoneA)).publicKey)
        assertContentEquals(identity().privateKey, assertNotNull(reopened.findLocalIdentity(service, carId)).privateKey)
    }

    @Test
    fun laterPairingsReplaceTheWholeFile() {
        val store = FileTrustStore.open(dir)
        store.commitPairing(identity(), peer(phoneA))
        store.commitPairing(identity(), peer(phoneB))
        // 同一台手机重新配对：旧记录被新公钥覆盖。
        store.commitPairing(identity(), peer(phoneA, seed = 0x10))
        assertEquals(listOf(FileTrustStore.FILE_NAME), names())

        val reopened = FileTrustStore.open(dir)
        assertEquals(2, reopened.peerCount)
        assertContentEquals(peer(phoneA, seed = 0x10).publicKey, assertNotNull(reopened.findPeer(service, phoneA)).publicKey)
        assertNotNull(reopened.findPeer(service, phoneB))
    }

    @Test
    fun leftoverTempFileFromAnInterruptedWriteIsIgnoredAndRemoved() {
        FileTrustStore.open(dir).commitPairing(identity(), peer(phoneA))
        // 上次写到一半（改名之前）断电：临时文件是残缺的，原文件没动过。
        File(dir, ".${FileTrustStore.FILE_NAME}.tmp").writeBytes(byteArrayOf(1, 2, 3))

        val reopened = FileTrustStore.open(dir)
        assertEquals(1, reopened.peerCount)
        assertEquals(listOf(FileTrustStore.FILE_NAME), names())
    }

    @Test
    fun failedWriteLeavesMemoryAndFileUnchanged() {
        val store = FileTrustStore.open(dir)
        store.commitPairing(identity(), peer(phoneA))
        val before = File(dir, FileTrustStore.FILE_NAME).readBytes()

        // 目标位置被一个非空目录占着，改名一定失败（同样适用于以 root 身份运行的测试）。
        val occupied = File(root, "occupied")
        val target = File(occupied, FileTrustStore.FILE_NAME)
        assertTrue(File(target, "keep").mkdirs())
        assertFailsWith<IOException> { FileTrustStore.writeAtomically(target, byteArrayOf(9)) }
        assertEquals(listOf(FileTrustStore.FILE_NAME), occupied.list()!!.toList(), "失败时临时文件已删掉")

        // 写不进去（目录只读）：配对按失败处理，内存和文件都保持原样。
        if (dir.setWritable(false) && cannotWrite(dir)) {
            assertFailsWith<IOException> { store.commitPairing(identity(), peer(phoneB)) }
            assertNull(store.findPeer(service, phoneB))
            assertEquals(1, store.peerCount)
            assertContentEquals(before, File(dir, FileTrustStore.FILE_NAME).readBytes())
        }
        dir.setWritable(true)
    }

    @Test
    fun corruptFileIsAnErrorNotAnEmptyStore() {
        dir.mkdirs()
        File(dir, FileTrustStore.FILE_NAME).writeBytes(byteArrayOf(1, 2, 3))
        val e = assertFailsWith<TrustStoreCorruptException> { FileTrustStore.open(dir) }
        assertTrue(e.message!!.contains("无法解析"), e.message)
        assertTrue(File(dir, FileTrustStore.FILE_NAME).exists(), "不擅自删掉")
    }

    @Test
    fun unknownVersionIsAnError() {
        dir.mkdirs()
        // 魔数对、版本是以后的版本 2。
        File(dir, FileTrustStore.FILE_NAME).writeBytes(byteArrayOf('O'.code.toByte(), 'H'.code.toByte(), 'T'.code.toByte(), 'S'.code.toByte(), 0, 2, 0, 0, 0, 0))
        val e = assertFailsWith<TrustStoreCorruptException> { FileTrustStore.open(dir) }
        assertTrue(e.message!!.contains("版本"), e.message)
    }

    @Test
    fun userConfirmedResetSetsTheBadFileAside() {
        dir.mkdirs()
        val bad = byteArrayOf(1, 2, 3)
        File(dir, FileTrustStore.FILE_NAME).writeBytes(bad)

        val kept = assertNotNull(FileTrustStore.setAside(dir, wallClockMillis = 1_000))
        assertFalse(File(dir, FileTrustStore.FILE_NAME).exists())
        assertContentEquals(bad, kept.readBytes(), "坏文件原样留存，排查用")
        assertEquals(0, FileTrustStore.open(dir).peerCount)

        // 同一时刻再留存一次也不覆盖之前留下的。
        File(dir, FileTrustStore.FILE_NAME).writeBytes(byteArrayOf(4))
        val second = assertNotNull(FileTrustStore.setAside(dir, wallClockMillis = 1_000))
        assertFalse(second == kept)
        assertContentEquals(bad, kept.readBytes())
        assertNull(FileTrustStore.setAside(dir, wallClockMillis = 2_000), "没有文件时什么都不做")
    }

    @Test
    fun aPathThatIsNotAFileIsAnIoError() {
        assertTrue(File(dir, FileTrustStore.FILE_NAME).mkdirs())
        val e = assertFailsWith<IOException> { FileTrustStore.open(dir) }
        assertFalse(e is TrustStoreCorruptException)
    }
}
