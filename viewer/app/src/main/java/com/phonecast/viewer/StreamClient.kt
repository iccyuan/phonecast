package com.phonecast.viewer

import android.content.res.Resources
import android.media.MediaFormat
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
    /** 候选地址, 按优先级排列(局域网在前); 逐个尝试直到握手成功 */
    private val addrs: List<String>,
    private val room: String,
    /** 首次配对用的 6 位配对码; 已配对(有令牌)时可为空 */
    private val code: String,
    /** 上次配对拿到的设备令牌, 有则免输配对码 */
    private val token: String?,
    private val listener: Listener,
) {
    interface Listener {
        /** 连上了; addr 是实际用上的地址, viaLan 表示走的是局域网 */
        fun onConnected(addr: String, viaLan: Boolean)
        /** 视频编码类型: "h264" / "h265" / "av01" —— 决定用哪个解码器 */
        fun onVideoCodec(fourcc: String)
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
        /** 电脑端告知它当前的局域网地址, 用于刷新本地保存的直连路径 */
        fun onLanAddrs(addrs: List<String>)
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
        const val CH_LAN_ADDRS = 0x24
        const val CH_CLIENT_INFO = 0x30
        const val CH_AUDIO_TOGGLE = 0x31
        const val FLAG_CONFIG = 1L shl 63
        const val PTS_MASK = (1L shl 62) - 1 // bit62=关键帧标记
        const val MAX_FRAME = 8 shl 20
    }

    private var socket = Socket()
    private val sendQueue = LinkedBlockingQueue<ByteArray>()
    @Volatile private var closed = false
    @Volatile private var wantAudio = true

    fun start() = thread(name = "stream-reader") { runReader() }

    /**
     * 逐个候选地址尝试建链: 局域网超时给得短(不通就 1.5 秒内失败), 中继给足 6 秒。
     * 返回握手已完成的输入流, 全部失败则返回 null。
     */
    private fun connectAny(): Pair<DataInputStream, String>? {
        var lastError = "没有可用地址"
        for (addr in addrs) {
            if (closed) return null
            val lan = Entry.isLan(addr)
            val s = Socket()
            try {
                s.tcpNoDelay = true
                s.connect(InetSocketAddress(Entry.hostOf(addr), Entry.portOf(addr)),
                    if (lan) 1500 else 6000)
                s.getOutputStream().write(handshake())
                val input = DataInputStream(BufferedInputStream(s.getInputStream(), 256 * 1024))
                when (val status = input.readUnsignedByte()) {
                    0 -> {
                        socket = s
                        listener.onConnected(addr, lan)
                        return input to addr
                    }
                    // 设备名不在线: 这条路走不通(可能是同网段别的机器), 换下一条
                    2 -> lastError = "设备名不在线 (电脑端未启动或设备名不对)"
                    3 -> { // 已有观看端是明确结论, 换地址也一样, 直接结束
                        s.close()
                        fail("该电脑已有其他观看端在连")
                        return null
                    }
                    4 -> lastError = "中继内部错误, 稍后重试"
                    else -> lastError = "协议错误 (status=$status)"
                }
            } catch (e: Exception) {
                lastError = e.message ?: e.javaClass.simpleName
            }
            runCatching { s.close() }
        }
        fail(if (addrs.size > 1) "都连不上: $lastError" else lastError)
        return null
    }

    private fun runReader() {
        try {
            val (input, _) = connectAny() ?: return
            // 认证在 sender 线程启动前完成, 写端此刻独占, 无需与队列竞争
            if (!doAuth(input)) return
            // 上报本机屏幕与解码能力: 电脑据此决定编码分辨率与是否用 H.265
            sendFrame(CH_CLIENT_INFO, clientInfo().toByteArray())
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
                        // u32 codecId(fourcc) + u32 w + u32 h
                        val buf = ByteBuffer.wrap(data)
                        val fourcc = ByteArray(4).also { buf.get(it) }
                        listener.onVideoCodec(String(fourcc).trim { it.code == 0 })
                        listener.onCodecMeta(buf.int, buf.int)
                    } else emitMedia(data, listener::onPacket)
                    CH_AUDIO -> if (!audioMetaSeen) {
                        audioMetaSeen = true
                        if (ByteBuffer.wrap(data).int == 0) listener.onAudioDisabled("手机A 端音频不可用")
                    } else emitMedia(data, listener::onAudioPacket)
                    CH_DEVICE_INFO -> listener.onDeviceName(String(data))
                    CH_LAN_ADDRS -> listener.onLanAddrs(
                        String(data).split(',').map { it.trim() }.filter { it.isNotEmpty() })
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

    /**
     * 上报本机能力: 屏幕像素(决定编码分辨率, 免得按被控机原生分辨率浪费码率)、
     * 能否硬解 H.265、是否要音频。
     */
    private fun clientInfo(): String {
        val dm = Resources.getSystem().displayMetrics
        val h265 = Codecs.canDecode(MediaFormat.MIMETYPE_VIDEO_HEVC)
        return "w=${dm.widthPixels};h=${dm.heightPixels};h265=${if (h265) 1 else 0};audio=${if (wantAudio) 1 else 0}"
    }

    /** 运行中切换声音: 关掉后电脑直接不发, 省的是链路流量而不只是本机音量 */
    fun setAudioEnabled(on: Boolean) {
        wantAudio = on
        sendQueue.offer(frameBytes(CH_AUDIO_TOGGLE, byteArrayOf(if (on) 1 else 0)))
    }

    private fun frameBytes(ch: Int, payload: ByteArray): ByteArray =
        ByteBuffer.allocate(5 + payload.size)
            .put(ch.toByte()).putInt(payload.size).put(payload).array()

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
                val frame = sendQueue.take() // 队列里放的是完整帧, 便于混发控制/音频开关
                if (frame.isEmpty()) continue
                out.write(frame)
                out.flush()
            }
        } catch (_: Exception) {
            close()
        }
    }

    /** 发一条 scrcpy 控制消息(触摸/按键) */
    fun send(msg: ByteArray) {
        if (!closed) sendQueue.offer(frameBytes(CH_CONTROL, msg))
    }

    fun close() {
        closed = true
        runCatching { socket.close() }
        sendQueue.offer(ByteArray(0)) // 唤醒 sender 退出
    }
}
