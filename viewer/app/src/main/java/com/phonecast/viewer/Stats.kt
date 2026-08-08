package com.phonecast.viewer

/**
 * 链路与解码的实时指标。
 *
 * 为什么这样量: 手机A、电脑、手机B 三个时钟互不同步, 跨设备的时间戳不能直接相减。
 * 所以拆成两段各自在单一时钟域内测量 ——
 *   - 网络段: 观看端发时间戳、电脑原样回弹, 得到 RTT(半程≈单向)
 *   - 本机段: 从"收到该帧"到"该帧上屏", 全程用手机B 自己的时钟
 * 两段相加就是端到端时延的可靠估计, 且不依赖任何时钟对齐。
 *
 * 分位数比平均值有用得多: 卡顿是尾部延迟造成的, 平均值会把它抹平。
 */
class Stats {

    private class Window(val capacity: Int = 120) {
        private val values = LongArray(capacity)
        private var n = 0
        private var idx = 0

        fun add(v: Long) {
            values[idx] = v
            idx = (idx + 1) % capacity
            if (n < capacity) n++
        }

        fun percentile(p: Double): Long {
            if (n == 0) return 0
            val sorted = values.copyOf(n).sortedArray()
            return sorted[((n - 1) * p).toInt().coerceIn(0, n - 1)]
        }

        fun isEmpty() = n == 0
    }

    private val rtt = Window()
    private val displayLatency = Window()

    @Volatile var videoBytes = 0L; private set
    @Volatile var audioBytes = 0L; private set
    @Volatile var videoFrames = 0L; private set
    @Volatile var droppedBacklog = 0L
    @Volatile var droppedLate = 0L
    @Volatile var pendingPackets = 0

    private var windowStart = System.nanoTime()
    private var windowVideoBytes = 0L
    private var windowFrames = 0L
    @Volatile var kbps = 0; private set
    @Volatile var fps = 0.0; private set

    fun onRtt(ms: Long) = rtt.add(ms)

    /** 上报给电脑端做自适应决策 */
    fun rttP95(): Long = rtt.percentile(0.95)

    /** 从收到该帧到它真正上屏, 全在本机时钟域内 */
    fun onDisplayLatency(ms: Long) = displayLatency.add(ms)

    fun onVideo(bytes: Int) {
        videoBytes += bytes
        videoFrames++
        windowVideoBytes += bytes
        windowFrames++
        tick()
    }

    fun onAudio(bytes: Int) {
        audioBytes += bytes
    }

    /** 每秒结算一次瞬时码率与帧率, 免得用累计值算出"越来越平"的假象 */
    private fun tick() {
        val now = System.nanoTime()
        val elapsed = now - windowStart
        if (elapsed < 1_000_000_000L) return
        val sec = elapsed / 1e9
        kbps = (windowVideoBytes * 8 / 1000 / sec).toInt()
        fps = windowFrames / sec
        windowStart = now
        windowVideoBytes = 0
        windowFrames = 0
    }

    fun snapshot(): String = buildString {
        append("码率 ${kbps} kbps   帧率 %.1f fps\n".format(fps))
        if (!rtt.isEmpty()) {
            append("网络 RTT  P50 ${rtt.percentile(0.5)}ms  P95 ${rtt.percentile(0.95)}ms\n")
        }
        if (!displayLatency.isEmpty()) {
            append("收到→上屏 P50 ${displayLatency.percentile(0.5)}ms  P95 ${displayLatency.percentile(0.95)}ms\n")
        }
        if (!rtt.isEmpty() && !displayLatency.isEmpty()) {
            // 只含"单向网络 + 本机解码上屏"。手机A 的采集与编码在另一个时钟域,
            // 量不到, 所以这里不叫"端到端", 免得看着比实际乐观。
            append("链路+解码 ≈ ${rtt.percentile(0.5) / 2 + displayLatency.percentile(0.5)}ms")
            append(" (不含手机A 采集编码)\n")
        }
        append("积压 $pendingPackets 帧   丢弃 积压${droppedBacklog}/过期${droppedLate}\n")
        append("累计 视频 ${videoBytes / 1024} KB   音频 ${audioBytes / 1024} KB")
    }
}
