package io.github.gcbond.openhc.tools.desktop

import io.github.gcbond.openhc.core.auth.InMemoryTrustStore
import io.github.gcbond.openhc.core.auth.LocalIdentity
import io.github.gcbond.openhc.core.auth.ServiceId
import io.github.gcbond.openhc.core.auth.TrustStore
import io.github.gcbond.openhc.core.auth.TrustStoreCodec
import io.github.gcbond.openhc.core.auth.TrustStoreSnapshot
import io.github.gcbond.openhc.core.auth.TrustedPeer
import io.github.gcbond.openhc.core.common.MalformedMessageException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * 落盘的信任库：一个目录（0700）里一个文件（0600），内容是 core:auth 的版本化格式（[TrustStoreCodec]）。
 * 文件里有本机长期身份的私钥，所以只给当前用户读写；首次配对成功时原子地整份重写。
 *
 * 写盘失败时内存里的内容不变、异常抛给认证层，本次配对按失败处理。线程安全。
 */
internal class FileTrustStore private constructor(
    val file: Path,
    initial: TrustStoreSnapshot,
) : TrustStore {
    private var memory = InMemoryTrustStore(initial)

    /** 已配对的手机数（日志用）。 */
    val peerCount: Int
        @Synchronized get() = memory.snapshot().peers.size

    @Synchronized
    override fun findLocalIdentity(serviceId: ServiceId, authId: ByteArray): LocalIdentity? =
        memory.findLocalIdentity(serviceId, authId)

    @Synchronized
    override fun findPeer(serviceId: ServiceId, authId: ByteArray): TrustedPeer? = memory.findPeer(serviceId, authId)

    @Synchronized
    override fun commitPairing(identity: LocalIdentity, peer: TrustedPeer) {
        val next = InMemoryTrustStore(memory.snapshot())
        next.commitPairing(identity, peer)
        PrivateFiles.writeAtomically(file, TrustStoreCodec.encode(next.snapshot()))
        memory = next
    }

    override fun toString() = "FileTrustStore($file)"

    companion object {
        const val FILE_NAME = "trust-store.bin"

        /**
         * 打开 [dir] 里的信任库；目录不存在就新建（0700），文件不存在就当空库。
         * 目录或文件的归属、权限不对，或者文件内容损坏、版本不认识，都直接报错，不当成空库。
         */
        @Throws(IOException::class)
        fun open(dir: Path): FileTrustStore {
            PrivateFiles.prepareDirectory(dir)
            val file = dir.resolve(FILE_NAME)
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return FileTrustStore(file, TrustStoreSnapshot.EMPTY)
            PrivateFiles.checkPrivateFile(file)
            val snapshot = try {
                TrustStoreCodec.decode(Files.readAllBytes(file))
            } catch (e: MalformedMessageException) {
                throw IOException("信任库文件 $file 无法解析：${e.message}")
            }
            return FileTrustStore(file, snapshot)
        }
    }
}
