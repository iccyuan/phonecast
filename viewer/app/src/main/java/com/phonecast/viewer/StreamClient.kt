package com.phonecast.viewer

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
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
    private val key: String,
    private val room: String,
    private val listener: Listener,
) {
    interface Listener {
        fun onConnected()
        fun onCodecMeta(width: Int, height: Int)
        fun onPacket(isConfig: Boolean, ptsUs: Long, data: ByteArray)
        fun onAudioPacket(isConfig: Boolean, ptsUs: Long, data: ByteArray)
        fun onAudioDisabled(reason: String)
        fun onDisconnected(reason: String)
    }

    private companion object {
        const val CH_VIDEO = 0
        const val CH_AUDIO = 1
        const val CH_CONTROL = 2
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
                0 -> listener.onConnected()
                1 -> return fail("密钥错误")
                2 -> return fail("配对码不在线 (电脑端 agent 未连接)")
                3 -> return fail("该手机已有其他观看端")
                4 -> return fail("中继内部错误, 稍后重试")
                else -> return fail("协议错误 (status=$status)")
            }
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
                }
            }
        } catch (e: Exception) {
            if (!closed) listener.onDisconnected(e.message ?: e.javaClass.simpleName)
        } finally {
            close()
        }
    }

    private inline fun emitMedia(frame: ByteArray, emit: (Boolean, Long, ByteArray) -> Unit) {
        val ptsAndFlags = ByteBuffer.wrap(frame).long
        emit(ptsAndFlags and FLAG_CONFIG != 0L, ptsAndFlags and PTS_MASK, frame.copyOfRange(8, frame.size))
    }

    private fun handshake(): ByteArray {
        val k = key.toByteArray()
        val r = room.toByteArray()
        require(k.size < 256 && r.size < 256) { "密钥/配对码过长" }
        return ByteArrayOutputStream().apply {
            write("PCV2".toByteArray())
            write(k.size); write(k)
            write(r.size); write(r)
        }.toByteArray()
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
