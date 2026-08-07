package com.phonecast.viewer

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

/**
 * PhoneCast v2 协议客户端 (直连 agent 或经 hub 中继, 线协议相同):
 *  - 握手: "PCV2" + u8 keyLen + key + u8 roomLen + room → 1 字节状态码
 *  - 之后双向帧 [u8 ch][u32 len][payload]:
 *      ch0 视频: 首帧 12B codec meta, 之后 [8B pts+flags][ES]
 *      ch1 音频: 首帧 4B codec id (0=禁用), 之后 [8B pts+flags][ES]
 *      ch2 控制: 上行, 每帧一条 scrcpy 控制消息
 */
class StreamClient(
    private val host: String,
    private val port: Int,
    private val room: String,
    /** 首次配对用的 6 位配对码; 已配对(有令牌)时可为空 */
    private val code: String,
    /** 上次配对拿到的设备令牌, 有则免输配对码 */
    private val token: String?,
    private val listener: Listener,
) {
    interface Listener {
        fun onConnected()
        /** 配对成功, 保存令牌供下次免输 */
        fun onPaired(token: String)
        fun onCodecMeta(width: Int, height: Int)
        fun onPacket(isConfig: Boolean, ptsUs: Long, data: ByteArray)
        fun onAudioPacket(isConfig: Boolean, ptsUs: Long, data: ByteArray)
        fun onAudioDisabled(reason: String)
        /** 已保存的设备令牌被电脑端拒绝(撤销过), 需清掉重新配对 */
        fun onTokenRejected()
        /** 电脑端告知正在投的是哪台手机(机型), 用于列表显示 */
        fun onDeviceName(name: String)
        fun onDisconnected(reason: String)
    }

    private companion object {
        const val CH_VIDEO = 0
        const val CH_AUDIO = 1
        const val CH_CONTROL = 2
        const val CH_AUTH_CHALLENGE = 0x20
        const val CH_AUTH_RESPONSE = 0x21
        const val CH_AUTH_RESULT = 0x22
        const val CH_DEVICE_INFO = 0x23
        const val FLAG_CONFIG = 1L shl 63
        const val PTS_MASK = (1L shl 62) - 1 // bit62=关键帧标记
        const val MAX_FRAME = 8 shl 20
    }

    private val socket = Socket()
    private val sendQueue = LinkedBlockingQueue<ByteArray>()
    @Volatile private var closed = false

    fun start() = thread(name = "stream-reader") { runReader() }

    private fun runReader() {
        try {
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(host, port), 5000)
            socket.getOutputStream().write(handshake())

            val input = DataInputStream(BufferedInputStream(socket.getInputStream(), 256 * 1024))
            when (val status = input.readUnsignedByte()) {
                0 -> {}
                1 -> return fail("密钥错误")
                2 -> return fail("设备名不在线 (电脑端未启动或设备名不对)")
                3 -> return fail("该电脑已有其他观看端在连")
                4 -> return fail("中继内部错误, 稍后重试")
                else -> return fail("协议错误 (status=$status)")
            }
            // 认证在 sender 线程启动前完成, 写端此刻独占, 无需与队列竞争
            if (!doAuth(input)) return
            listener.onConnected()
            thread(name = "stream-sender") { runSender() }

            var videoMetaSeen = false
            var audioMetaSeen = false
            while (!closed) {
                val ch = input.readUnsignedByte()
                val size = input.readInt()
                require(size in 0..MAX_FRAME) { "异常帧长 $size, 流已错位" }
                val data = ByteArray(size)
                input.readFully(data)
                when (ch) {
                    CH_VIDEO -> if (!videoMetaSeen) {
                        videoMetaSeen = true
                        val buf = ByteBuffer.wrap(data) // u32 codecId + u32 w + u32 h
                        buf.int
                        listener.onCodecMeta(buf.int, buf.int)
                    } else emitMedia(data, listener::onPacket)
                    CH_AUDIO -> if (!audioMetaSeen) {
                        audioMetaSeen = true
                        if (ByteBuffer.wrap(data).int == 0) listener.onAudioDisabled("手机A 端音频不可用")
                    } else emitMedia(data, listener::onAudioPacket)
                    CH_DEVICE_INFO -> listener.onDeviceName(String(data))
                }
            }
        } catch (e: Exception) {
            if (!closed) listener.onDisconnected(e.message ?: e.javaClass.simpleName)
        } finally {
            close()
        }
    }

    /**
     * 端到端认证: 电脑端发 16 字节 nonce, 这里用 HMAC-SHA256(凭据, nonce) 作答。
     * 配对码/令牌本身不上线, 中继服务器也无法冒充本机。
     */
    private fun doAuth(input: DataInputStream): Boolean {
        val ch = input.readUnsignedByte()
        val len = input.readInt()
        require(len in 1..MAX_FRAME) { "认证帧异常 ($len)" }
        val nonce = ByteArray(len)
        input.readFully(nonce)
        if (ch != CH_AUTH_CHALLENGE) {
            fail("协议错误: 未收到认证挑战")
            return false
        }

        val useToken = !token.isNullOrEmpty()
        val secret = if (useToken) token!! else code
        if (secret.isEmpty()) {
            fail("请输入电脑端显示的 6 位配对码")
            return false
        }
        val mac = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        }
        sendFrame(CH_AUTH_RESPONSE,
            byteArrayOf(if (useToken) 1 else 0) + mac.doFinal(nonce))

        val rch = input.readUnsignedByte()
        val rlen = input.readInt()
        require(rlen in 0..MAX_FRAME) { "认证结果异常 ($rlen)" }
        val result = ByteArray(rlen)
        input.readFully(result)
        if (rch != CH_AUTH_RESULT || result.isEmpty() || result[0].toInt() != 0) {
            fail(if (useToken) "配对已失效, 请重新输入配对码" else "配对码错误或已过期")
            if (useToken) listener.onTokenRejected()
            return false
        }
        if (result.size > 1) { // 首次配对: 收下设备令牌, 下次免输
            listener.onPaired(String(result, 1, result.size - 1))
        }
        return true
    }

    private inline fun emitMedia(frame: ByteArray, emit: (Boolean, Long, ByteArray) -> Unit) {
        val ptsAndFlags = ByteBuffer.wrap(frame).long
        emit(ptsAndFlags and FLAG_CONFIG != 0L, ptsAndFlags and PTS_MASK, frame.copyOfRange(8, frame.size))
    }

    private fun handshake(): ByteArray {
        val r = room.toByteArray()
        require(r.size < 256) { "设备名过长" }
        return ByteArrayOutputStream().apply {
            write("PCV3".toByteArray())
            write(r.size); write(r)
        }.toByteArray()
    }

    /** 直接写一帧 (认证阶段用, 此时 sender 线程还没接管写端) */
    private fun sendFrame(ch: Int, payload: ByteArray) {
        val out = socket.getOutputStream()
        out.write(ByteBuffer.allocate(5 + payload.size)
            .put(ch.toByte()).putInt(payload.size).put(payload).array())
        out.flush()
    }

    private fun fail(reason: String) {
        listener.onDisconnected(reason)
    }

    private fun runSender() {
        try {
            val out = socket.getOutputStream()
            while (!closed) {
                val msg = sendQueue.take()
                if (msg.isEmpty()) continue
                val frame = ByteBuffer.allocate(5 + msg.size)
                    .put(CH_CONTROL.toByte()).putInt(msg.size).put(msg)
                out.write(frame.array())
                out.flush()
            }
        } catch (_: Exception) {
            close()
        }
    }

    fun send(msg: ByteArray) {
        if (!closed) sendQueue.offer(msg)
    }

    fun close() {
        closed = true
        runCatching { socket.close() }
        sendQueue.offer(ByteArray(0)) // 唤醒 sender 退出
    }
}
