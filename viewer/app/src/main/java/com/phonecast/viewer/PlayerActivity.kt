package com.phonecast.viewer

import android.app.Activity
import android.graphics.Color
import android.graphics.Rect
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
import android.view.View
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

    private companion object {
        // 约 1 秒余量: 再多就是延迟而不是"平滑"
        const val MAX_PENDING_PACKETS = 60
    }

    private lateinit var audioBtn: View
    private var audioOn = true

    private val packets = LinkedBlockingQueue<Packet>()
    @Volatile private var videoFourcc = "h264"
    @Volatile private var waitingForKeyframe = false
    private var droppedBacklog = 0
    private var droppedLate = 0
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
        // 连接过程中的状态提示: 药丸形底衬, 出画面后隐藏
        statusText = TextView(this).apply {
            text = "正在连接…"
            setTextColor(Ui.TEXT)
            textSize = 13.5f
            background = Ui.rounded(this@PlayerActivity, Ui.SURFACE, 20, Ui.BORDER)
            val ph = Ui.dp(this@PlayerActivity, 18)
            setPadding(ph, Ui.dp(this@PlayerActivity, 10), ph, Ui.dp(this@PlayerActivity, 10))
        }
        container = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(surfaceView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(statusText, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }

        // 不再放「返回/主页/多任务」三个键: 手势时代直接在画面里比划更自然,
        // 那三个键反而占地方。取而代之的是画面四周留出安全边距(见 fitSurface),
        // 边距内是本机手势区, 边距内侧的画面区则把本机手势屏蔽掉交给被控手机。
        val exitBtn = View(this).apply {
            background = Ui.circleButton(this@PlayerActivity,
                Icons.Close(this@PlayerActivity, Color.WHITE), 0x99000000.toInt(), 9)
            contentDescription = "断开"
            setOnClickListener { finish() }
        }
        // 声音开关: 关掉后电脑直接不再发送音频, 省的是链路流量而不只是本机音量
        audioBtn = View(this).apply {
            contentDescription = "声音"
            setOnClickListener { toggleAudio() }
        }
        updateAudioIcon()

        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(container, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            // 两个按钮都放在边距区里, 不压占画面
            addView(exitBtn, FrameLayout.LayoutParams(
                Ui.dp(context, 34), Ui.dp(context, 34), Gravity.TOP or Gravity.END).apply {
                topMargin = Ui.dp(context, 8)
                rightMargin = Ui.dp(context, 8)
            })
            addView(audioBtn, FrameLayout.LayoutParams(
                Ui.dp(context, 34), Ui.dp(context, 34), Gravity.TOP or Gravity.END).apply {
                topMargin = Ui.dp(context, 8)
                rightMargin = Ui.dp(context, 50)
            })
        })

        room = intent.getStringExtra("room") ?: ""
        val addrs = intent.getStringArrayListExtra("addrs") ?: arrayListOf()
        client = StreamClient(
            addrs, room, intent.getStringExtra("code") ?: "", Tokens.get(this, room), this)
        client.start()
        thread(name = "decoder-input") { runDecoder() }
    }

    // ---- StreamClient.Listener (非 UI 线程) ----

    override fun onConnected(addr: String, viaLan: Boolean) {
        this.addr = addr
        Saved.promote(this, room, addr) // 这条路通, 下次先试它
        // 明明有局域网地址却回落到中继, 多半是手机开着 VPN 把局域网流量也吸走了
        val lanSkipped = !viaLan && intent.getStringArrayListExtra("addrs")
            ?.any { Entry.isLan(it) } == true
        runOnUiThread {
            statusText.text = if (viaLan) "已连接(局域网),等待画面…" else "已连接(中继),等待画面…"
            if (lanSkipped) {
                Toast.makeText(this,
                    "局域网连不通,已走中继。若手机开着 VPN,请关闭或开启其「绕过局域网」选项",
                    Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onPaired(token: String) {
        Tokens.put(this, room, token)
        runOnUiThread { Toast.makeText(this, "配对成功,下次无需再输配对码", Toast.LENGTH_SHORT).show() }
    }

    override fun onTokenRejected() {
        Tokens.clear(this, room)
    }

    override fun onVideoCodec(fourcc: String) {
        videoFourcc = fourcc
    }

    private fun toggleAudio() {
        audioOn = !audioOn
        client.setAudioEnabled(audioOn)
        audioPlayer.setMuted(!audioOn)
        updateAudioIcon()
        Toast.makeText(this,
            if (audioOn) "已开启声音" else "已关闭声音(电脑端同时停止发送)",
            Toast.LENGTH_SHORT).show()
    }

    private fun updateAudioIcon() {
        audioBtn.background = Ui.circleButton(this,
            if (audioOn) Icons.SoundOn(this) else Icons.SoundOff(this),
            0x99000000.toInt(), 9)
    }

    override fun onDeviceName(name: String) {
        Saved.setName(this, room, name)
    }

    override fun onLanAddrs(addrs: List<String>) {
        // 电脑换了 WiFi 后 IP 会变, 用它报来的地址替换旧的, 下次即可直接走局域网
        Saved.updateLanAddrs(this, room, addrs)
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

    /**
     * 入口侧限深: 队列无上限的话, 网络抖动积压的帧会让延迟单调增长("越用越卡")。
     * 超限时丢到下一个关键帧为止 —— P 帧依赖参考帧, 不能随便挑着丢。
     */
    override fun onPacket(isConfig: Boolean, ptsUs: Long, data: ByteArray) {
        if (packets.size >= MAX_PENDING_PACKETS) {
            packets.clear()
            waitingForKeyframe = true
            droppedBacklog++
        }
        if (waitingForKeyframe) {
            if (!isConfig && !isKeyframe(data)) return
            waitingForKeyframe = false
        }
        packets.offer(Packet(isConfig, ptsUs, data))
    }

    /** 从 Annex-B 起始码后的 NAL 头判断关键帧 (H.264: IDR=5; H.265: IDR_W_RADL/N_LP=19/20) */
    private fun isKeyframe(data: ByteArray): Boolean {
        var i = 0
        while (i + 4 < data.size) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 1) {
                val nal = data[i + 3].toInt() and 0xFF
                return if (videoFourcc == "h265") {
                    val type = (nal shr 1) and 0x3F
                    type in 16..21 || type in 32..34 // IRAP 或 VPS/SPS/PPS
                } else {
                    (nal and 0x1F) in intArrayOf(5, 7, 8)
                }
            }
            i++
        }
        return false
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
        val mime = Codecs.mimeFor(videoFourcc)
        try {
            val format = MediaFormat.createVideoFormat(mime, videoW, videoH)
            val name = MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(format)
            codec = MediaCodec.createByCodecName(name)
            if (Build.VERSION.SDK_INT >= 30 &&
                codec.codecInfo.getCapabilitiesForType(mime)
                    .isFeatureSupported(CodecCapabilities.FEATURE_LowLatency)
            ) {
                format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
            codec.configure(format, surfaceView.holder.surface, null, 0)
            codec.start()
        } catch (e: Exception) {
            onDisconnected("解码器初始化失败($mime): ${e.message}")
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

    /**
     * 输出侧: 只渲染"最新"的一帧。
     * 若解码器一次吐出多帧(网络抖动后追赶), 把旧的以 render=false 丢掉 ——
     * 投屏是实时画面, 播放过期帧只会让延迟固化下来。
     */
    private fun drainOutput(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        try {
            while (running) {
                var idx = codec.dequeueOutputBuffer(info, 100_000)
                if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    onOutputFormat(codec.outputFormat)
                    continue
                }
                if (idx < 0) continue

                // 看看后面还有没有已经就绪的帧: 有就说明当前这帧已经过期
                while (true) {
                    val next = codec.dequeueOutputBuffer(info, 0)
                    if (next < 0) break
                    if (next == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        onOutputFormat(codec.outputFormat)
                        continue
                    }
                    codec.releaseOutputBuffer(idx, false) // 丢掉旧帧, 不上屏
                    droppedLate++
                    idx = next
                }
                codec.releaseOutputBuffer(idx, true)
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
            statusText.visibility = View.GONE // 画面已出, 收起提示
            fitSurface()
        }
    }

    /**
     * 按视频宽高比等比缩放并居中, 让触摸坐标可线性映射。
     *
     * 关键点: 当被控手机的分辨率不小于本机时, 画面会铺到屏幕边缘, 此时从边缘发起的
     * 滑动会被【本机】的系统手势(返回/主页)截走, 传不到被控手机。所以这种情况下四周
     * 留出一圈空白: 空白区是本机手势区, 画面区是被控手机的操作区, 两者互不打架。
     */
    private fun fitSurface() {
        val vw = videoW
        val vh = videoH
        if (vw == 0 || vh == 0 || container.width == 0) return

        val dm = resources.displayMetrics
        val remoteNotSmaller = vw >= dm.widthPixels || vh >= dm.heightPixels
        val inset = if (remoteNotSmaller) Ui.dp(this, 22) else 0

        val availW = (container.width - inset * 2).coerceAtLeast(1)
        val availH = (container.height - inset * 2).coerceAtLeast(1)
        val scale = minOf(availW.toFloat() / vw, availH.toFloat() / vh)
        val w = (vw * scale).toInt()
        val h = (vh * scale).toInt()
        surfaceView.layoutParams = FrameLayout.LayoutParams(w, h, Gravity.CENTER)

        // 画面区内屏蔽本机系统手势, 让边缘滑动落到被控手机上
        // (系统对每条边的豁免高度有上限, 拿不满也能显著改善)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            surfaceView.post {
                surfaceView.systemGestureExclusionRects =
                    listOf(Rect(0, 0, surfaceView.width, surfaceView.height))
            }
        }
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
