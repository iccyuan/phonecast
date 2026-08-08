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
import android.view.WindowManager
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
    private lateinit var statsText: TextView
    private var statsOn = false
    private val statsHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private val packets = LinkedBlockingQueue<Packet>()
    @Volatile private var videoFourcc = "h264"
    @Volatile private var waitingForKeyframe = false

    /** PTS → 收到时刻(本机纳秒), 用于测"收到→上屏" */
    private val recvAt = HashMap<Long, Long>()
    private val surfaceReady = CountDownLatch(1)
    /** 每"一代"解码器一个: 编码器重启后换新的, 等新 codec meta */
    @Volatile private var metaLatch = CountDownLatch(1)
    @Volatile private var generation = 0

    @Volatile private var running = true
    // 触摸坐标映射用的当前视频尺寸: 先取 codec meta, 之后跟随解码器输出(手机A 旋转会变)
    @Volatile private var videoW = 0
    @Volatile private var videoH = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterImmersive()

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
        // 那三个键反而占地方。本机与被控手势的冲突由沉浸式全屏解决(见 enterImmersive):
        // 系统栏隐藏后边缘滑动直达被控手机, 想用本机手势就先轻扫呼出系统栏。
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

        // 指标面板: 码率/帧率/RTT/上屏时延/积压, 排查卡顿时不用靠猜
        statsText = TextView(this).apply {
            setTextColor(0xFF9FE8B0.toInt())
            textSize = 10f
            typeface = android.graphics.Typeface.MONOSPACE
            background = Ui.rounded(this@PlayerActivity, 0xCC000000.toInt(), 8)
            val p = Ui.dp(this@PlayerActivity, 8)
            setPadding(p, p, p, p)
            visibility = View.GONE
            setLineSpacing(0f, 1.15f)
        }
        val statsBtn = View(this).apply {
            background = Ui.circleButton(this@PlayerActivity,
                Icons.Info(this@PlayerActivity), 0x99000000.toInt(), 9)
            contentDescription = "链路指标"
            setOnClickListener { toggleStats() }
        }

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
            addView(statsBtn, FrameLayout.LayoutParams(
                Ui.dp(context, 34), Ui.dp(context, 34), Gravity.TOP or Gravity.END).apply {
                topMargin = Ui.dp(context, 8)
                rightMargin = Ui.dp(context, 92)
            })
            addView(statsText, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
                topMargin = Ui.dp(context, 50)
                leftMargin = Ui.dp(context, 10)
            })
        })

        room = intent.getStringExtra("room") ?: ""
        val addrs = intent.getStringArrayListExtra("addrs") ?: arrayListOf()
        client = StreamClient(
            addrs, room, intent.getStringExtra("code") ?: "", Tokens.get(this, room), this)
        client.start()
        thread(name = "decoder-input") { runDecoder() }
    }

    /**
     * 粘性沉浸式全屏: 隐藏状态栏/导航栏后, 本机的系统手势(边缘返回/底部 Home)不再直接
     * 生效, 边缘滑动会传给画面发到被控手机; 需要本机手势时先从边缘轻扫呼出系统栏再滑。
     * 单靠 systemGestureExclusionRects 解决不了冲突: 返回手势每边最多豁免 200dp,
     * 底部 Home 手势区更是完全不可豁免。
     */
    private fun enterImmersive() {
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
    }

    /** 系统对话框等抢走焦点会清掉沉浸态, 拿回焦点时补上 */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersive()
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

    private fun toggleStats() {
        statsOn = !statsOn
        statsText.visibility = if (statsOn) View.VISIBLE else View.GONE
        if (statsOn) refreshStats() else statsHandler.removeCallbacksAndMessages(null)
    }

    /** 每秒刷新一次面板; 关掉面板就停, 不做无谓的唤醒 */
    private fun refreshStats() {
        if (!statsOn || isFinishing) return
        client.stats.pendingPackets = packets.size
        statsText.text = client.stats.snapshot()
        statsHandler.postDelayed({ refreshStats() }, 1000)
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
        metaLatch.countDown() // 放行解码循环去建解码器
        runOnUiThread { fitSurface() }
    }

    /** 电脑端重启了编码器: 让当前解码器这一代作废, 等新 meta 再建 */
    override fun onVideoReset() {
        generation++
        packets.clear()
        packets.offer(poison) // 把阻塞在 take() 的解码线程唤醒
        waitingForKeyframe = false
        synchronized(recvAt) { recvAt.clear() }
        metaLatch = CountDownLatch(1)
        runOnUiThread {
            statusText.visibility = View.VISIBLE
            statusText.text = "正在调整画质…"
        }
    }

    /**
     * 入口侧限深: 队列无上限的话, 网络抖动积压的帧会让延迟单调增长("越用越卡")。
     * 超限时丢到下一个关键帧为止 —— P 帧依赖参考帧, 不能随便挑着丢。
     */
    override fun onPacket(isConfig: Boolean, ptsUs: Long, data: ByteArray) {
        if (packets.size >= MAX_PENDING_PACKETS) {
            packets.clear()
            waitingForKeyframe = true
            client.stats.droppedBacklog++
        }
        if (waitingForKeyframe) {
            if (!isConfig && !isKeyframe(data)) return
            waitingForKeyframe = false
        }
        // 记下收到时刻, 上屏时按 PTS 反查, 得到"收到→上屏"的真实耗时
        if (!isConfig) {
            synchronized(recvAt) {
                if (recvAt.size > 240) recvAt.clear() // 只是防泄漏, 正常不会涨到这里
                recvAt[ptsUs] = System.nanoTime()
            }
        }
        packets.offer(Packet(isConfig, ptsUs, data))
        client.stats.pendingPackets = packets.size
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

    /**
     * 解码主循环。之所以是循环: 电脑端换码率时会重启编码器, 届时要丢掉旧解码器、
     * 等新的 codec meta 再建一个 —— 用旧解码器解新码流会直接黑屏。
     */
    private fun runDecoder() {
        try {
            surfaceReady.await()
        } catch (_: InterruptedException) {
            return
        }
        while (running) {
            try {
                metaLatch.await()
            } catch (_: InterruptedException) {
                return
            }
            if (!running) return
            decodeOneGeneration()
        }
    }

    private fun decodeOneGeneration() {

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

        val gen = generation
        thread(name = "decoder-output") { drainOutput(codec, gen) }

        var firstConfigSent = false
        var pendingConfig: ByteArray? = null // 旋转后的新 SPS/PPS: 拼在下一帧前内联送入
        try {
            while (running && gen == generation) {
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
            if (running && gen == generation) onDisconnected("解码中断: ${e.message}")
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            // 只有"不是因为换代而退出"时才结束整个播放; 换代要留着继续下一轮
            if (gen == generation) running = false
        }
    }

    /**
     * 输出侧: 只渲染"最新"的一帧。
     * 若解码器一次吐出多帧(网络抖动后追赶), 把旧的以 render=false 丢掉 ——
     * 投屏是实时画面, 播放过期帧只会让延迟固化下来。
     */
    private fun drainOutput(codec: MediaCodec, gen: Int) {
        val info = MediaCodec.BufferInfo()
        try {
            while (running && gen == generation) {
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
                    client.stats.droppedLate++
                    idx = next
                }
                val shownPts = info.presentationTimeUs
                codec.releaseOutputBuffer(idx, true)
                synchronized(recvAt) {
                    recvAt.remove(shownPts)?.let {
                        client.stats.onDisplayLatency((System.nanoTime() - it) / 1_000_000)
                    }
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
            statusText.visibility = View.GONE // 画面已出, 收起提示
            fitSurface()
        }
    }

    /**
     * 按视频宽高比等比缩放并居中, 让触摸坐标可线性映射。
     * 边缘手势冲突由沉浸式全屏解决(见 enterImmersive), 画面可以放心铺满。
     */
    private fun fitSurface() {
        val vw = videoW
        val vh = videoH
        if (vw == 0 || vh == 0 || container.width == 0) return

        val availW = container.width.coerceAtLeast(1)
        val availH = container.height.coerceAtLeast(1)
        val scale = minOf(availW.toFloat() / vw, availH.toFloat() / vh)
        val w = (vw * scale).toInt()
        val h = (vh * scale).toInt()
        surfaceView.layoutParams = FrameLayout.LayoutParams(w, h, Gravity.CENTER)

        // 辅助手段: 系统栏被临时呼出的几秒里, 尽量豁免画面区的本机手势
        // (返回手势每边最多豁免 200dp、底部 Home 区不可豁免, 兜底靠沉浸式)
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
