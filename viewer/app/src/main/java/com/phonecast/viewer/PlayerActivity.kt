package com.phonecast.viewer

import android.app.Activity
import android.graphics.Color
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaCodecInfo.CodecCapabilities
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

/**
 * 播放页: MediaCodec 硬解 H.264 渲染到 SurfaceView, 触摸事件映射为 scrcpy 控制消息回传。
 * 解码用同步模式双线程: 输入线程(喂包)+输出线程(渲染), 各自独立阻塞, 保持低延迟。
 */
class PlayerActivity : Activity(), StreamClient.Listener, SurfaceHolder.Callback {

    private class Packet(val isConfig: Boolean, val ptsUs: Long, val data: ByteArray)

    private val poison = Packet(false, -1, ByteArray(0))

    private lateinit var client: StreamClient
    private var addr = ""
    private var room = ""
    private val audioPlayer = AudioPlayer()
    private lateinit var container: FrameLayout
    private lateinit var surfaceView: SurfaceView
    private lateinit var statusText: TextView

    private val packets = LinkedBlockingQueue<Packet>()
    private val surfaceReady = CountDownLatch(1)
    private val metaReady = CountDownLatch(1)

    @Volatile private var running = true
    // 触摸坐标映射用的当前视频尺寸: 先取 codec meta, 之后跟随解码器输出(手机A 旋转会变)
    @Volatile private var videoW = 0
    @Volatile private var videoH = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        surfaceView = SurfaceView(this).apply {
            keepScreenOn = true
            holder.addCallback(this@PlayerActivity)
            setOnTouchListener { v, ev -> handleTouch(v.width, v.height, ev); true }
        }
        statusText = TextView(this).apply {
            text = "正在连接..."
            setTextColor(Ui.MUTED)
            textSize = 14f
        }
        container = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(surfaceView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(statusText, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }

        val navBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFF161A22.toInt())
            // 前三个键发给【被投屏的手机】, 最后一个是退出本次投屏(本机)
            for ((label, code) in listOf(
                "‹  返回" to ControlMessages.KEYCODE_BACK,
                "主页" to ControlMessages.KEYCODE_HOME,
                "多任务" to ControlMessages.KEYCODE_APP_SWITCH,
            )) {
                addView(Ui.flatButton(context, label, Ui.TEXT).apply {
                    setOnClickListener { ControlMessages.keyPress(code).forEach(client::send) }
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
            }
            addView(Ui.flatButton(context, "✕ 断开", Ui.MUTED).apply {
                setOnClickListener { finish() }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            addView(container, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(navBar, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(context, 52)))
        })

        addr = intent.getStringExtra("addr") ?: ""
        room = intent.getStringExtra("room") ?: ""
        client = StreamClient(
            intent.getStringExtra("host")!!, intent.getIntExtra("port", 27184),
            room, intent.getStringExtra("code") ?: "", Tokens.get(this, addr, room), this)
        client.start()
        thread(name = "decoder-input") { runDecoder() }
    }

    // ---- StreamClient.Listener (非 UI 线程) ----

    override fun onConnected() {
        runOnUiThread { statusText.text = "已连接,等待画面..." }
    }

    override fun onPaired(token: String) {
        Tokens.put(this, addr, room, token)
        runOnUiThread { Toast.makeText(this, "配对成功,下次无需再输配对码", Toast.LENGTH_SHORT).show() }
    }

    override fun onTokenRejected() {
        Tokens.clear(this, addr, room)
    }

    override fun onDeviceName(name: String) {
        Saved.setName(this, addr, room, name)
    }

    override fun onAudioPacket(isConfig: Boolean, ptsUs: Long, data: ByteArray) {
        audioPlayer.feed(isConfig, ptsUs, data)
    }

    override fun onAudioDisabled(reason: String) {
        runOnUiThread { Toast.makeText(this, "无音频: $reason", Toast.LENGTH_SHORT).show() }
    }

    override fun onCodecMeta(width: Int, height: Int) {
        videoW = width
        videoH = height
        metaReady.countDown()
        runOnUiThread { fitSurface() }
    }

    override fun onPacket(isConfig: Boolean, ptsUs: Long, data: ByteArray) {
        packets.offer(Packet(isConfig, ptsUs, data))
    }

    override fun onDisconnected(reason: String) {
        runOnUiThread {
            if (!isFinishing) {
                Toast.makeText(this, "连接断开: $reason", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    // ---- 解码 ----

    private fun runDecoder() {
        try {
            surfaceReady.await()
            metaReady.await()
        } catch (_: InterruptedException) {
            return
        }
        if (!running) return

        val codec: MediaCodec
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, videoW, videoH)
            val name = MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(format)
            codec = MediaCodec.createByCodecName(name)
            if (Build.VERSION.SDK_INT >= 30 &&
                codec.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                    .isFeatureSupported(CodecCapabilities.FEATURE_LowLatency)
            ) {
                format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
            codec.configure(format, surfaceView.holder.surface, null, 0)
            codec.start()
        } catch (e: Exception) {
            onDisconnected("解码器初始化失败: ${e.message}")
            return
        }

        thread(name = "decoder-output") { drainOutput(codec) }

        var firstConfigSent = false
        var pendingConfig: ByteArray? = null // 旋转后的新 SPS/PPS: 拼在下一帧前内联送入
        try {
            while (running) {
                val p = packets.take()
                if (p === poison) break
                if (p.isConfig && firstConfigSent) {
                    pendingConfig = p.data
                    continue
                }
                val data = if (!p.isConfig && pendingConfig != null) {
                    (pendingConfig + p.data).also { pendingConfig = null }
                } else p.data
                val flags = if (p.isConfig) {
                    firstConfigSent = true
                    MediaCodec.BUFFER_FLAG_CODEC_CONFIG
                } else 0

                val idx = codec.dequeueInputBuffer(-1)
                if (idx >= 0) {
                    codec.getInputBuffer(idx)!!.put(data)
                    codec.queueInputBuffer(idx, 0, data.size, p.ptsUs, flags)
                }
            }
        } catch (e: Exception) {
            if (running) onDisconnected("解码中断: ${e.message}")
        } finally {
            running = false
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }
    }

    private fun drainOutput(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        try {
            while (running) {
                val idx = codec.dequeueOutputBuffer(info, 100_000)
                when {
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> onOutputFormat(codec.outputFormat)
                    idx >= 0 -> codec.releaseOutputBuffer(idx, true)
                }
            }
        } catch (_: Exception) {
            // codec 停止时的 IllegalStateException, 正常退出
        }
    }

    private fun onOutputFormat(format: MediaFormat) {
        // 优先 crop 矩形, 编码对齐(如 1080→1088)时 KEY_WIDTH 不是真实画面尺寸
        val w = if (format.containsKey("crop-right"))
            format.getInteger("crop-right") - format.getInteger("crop-left") + 1
        else format.getInteger(MediaFormat.KEY_WIDTH)
        val h = if (format.containsKey("crop-bottom"))
            format.getInteger("crop-bottom") - format.getInteger("crop-top") + 1
        else format.getInteger(MediaFormat.KEY_HEIGHT)
        videoW = w
        videoH = h
        runOnUiThread {
            statusText.text = ""
            fitSurface()
        }
    }

    /** 按视频宽高比把 SurfaceView 等比缩放并居中, 让触摸坐标可线性映射。 */
    private fun fitSurface() {
        val vw = videoW
        val vh = videoH
        if (vw == 0 || vh == 0 || container.width == 0) return
        val scale = minOf(container.width.toFloat() / vw, container.height.toFloat() / vh)
        surfaceView.layoutParams = FrameLayout.LayoutParams(
            (vw * scale).toInt(), (vh * scale).toInt(), Gravity.CENTER)
    }

    // ---- 触摸 ----

    private fun handleTouch(viewW: Int, viewH: Int, ev: MotionEvent) {
        val vw = videoW
        val vh = videoH
        if (vw == 0 || vh == 0 || viewW == 0 || viewH == 0) return

        fun sendPointer(action: Int, index: Int, pressed: Boolean) {
            val x = (ev.getX(index) * vw / viewW).toInt().coerceIn(0, vw - 1)
            val y = (ev.getY(index) * vh / viewH).toInt().coerceIn(0, vh - 1)
            client.send(ControlMessages.touch(
                action, ev.getPointerId(index).toLong(), x, y, vw, vh, pressed))
        }

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN ->
                sendPointer(ControlMessages.ACTION_DOWN, ev.actionIndex, true)
            MotionEvent.ACTION_MOVE ->
                for (i in 0 until ev.pointerCount) sendPointer(ControlMessages.ACTION_MOVE, i, true)
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_POINTER_UP ->
                sendPointer(ControlMessages.ACTION_UP, ev.actionIndex, false)
            MotionEvent.ACTION_CANCEL ->
                for (i in 0 until ev.pointerCount) sendPointer(ControlMessages.ACTION_UP, i, false)
        }
    }

    // ---- Surface / 生命周期 ----

    override fun surfaceCreated(holder: SurfaceHolder) = surfaceReady.countDown()

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        // 退后台即断开会话, 重进重连 (MVP 策略)
        if (!isFinishing) finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        running = false
        client.close()
        audioPlayer.release()
        packets.offer(poison)
    }
}
