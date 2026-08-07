package com.phonecast.viewer

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * AAC 音频播放: 首个 config 包(AudioSpecificConfig)作 csd-0 建解码器,
 * 之后单线程 解码→AudioTrack 直写。队列超限丢旧包防延迟累积。
 */
class AudioPlayer {

    private class Packet(val ptsUs: Long, val data: ByteArray)

    private val queue = LinkedBlockingQueue<Packet>()
    @Volatile private var running = true
    private var started = false

    fun feed(isConfig: Boolean, ptsUs: Long, data: ByteArray) {
        if (!running) return
        if (isConfig) {
            if (!started) {
                started = true
                thread(name = "audio-player") { run(data) }
            }
            return // 中途重复 config (理论上音频不发生) 忽略
        }
        if (!started) return
        if (queue.size > 64) queue.poll() // 播放跟不上时丢最旧的, 保持低延迟
        queue.offer(Packet(ptsUs, data))
    }

    private fun run(config: ByteArray) {
        val codec: MediaCodec
        try {
            codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 48000, 2)
            format.setByteBuffer("csd-0", ByteBuffer.wrap(config))
            codec.configure(format, null, null, 0)
            codec.start()
        } catch (_: Exception) {
            return // 音频起不来只静音, 不影响视频
        }

        var track: AudioTrack? = null
        val info = MediaCodec.BufferInfo()
        try {
            while (running) {
                val p = queue.poll(50, TimeUnit.MILLISECONDS)
                if (p != null) {
                    val idx = codec.dequeueInputBuffer(10_000)
                    if (idx >= 0) {
                        codec.getInputBuffer(idx)!!.put(p.data)
                        codec.queueInputBuffer(idx, 0, p.data.size, p.ptsUs, 0)
                    } // 解码器堵住就丢这包
                }
                while (true) {
                    val oidx = codec.dequeueOutputBuffer(info, 0)
                    when {
                        oidx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ->
                            track = makeTrack(codec.outputFormat, track)
                        oidx >= 0 -> {
                            if (info.size > 0) {
                                val pcm = ByteArray(info.size)
                                codec.getOutputBuffer(oidx)!!.get(pcm)
                                track?.write(pcm, 0, pcm.size)
                            }
                            codec.releaseOutputBuffer(oidx, false)
                        }
                        else -> break
                    }
                }
            }
        } catch (_: Exception) {
            // 关闭路径上的解码器异常, 静默退出
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            track?.release()
        }
    }

    private fun makeTrack(format: MediaFormat, old: AudioTrack?): AudioTrack {
        old?.release()
        val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val mask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val minBuf = AudioTrack.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT)
        val builder = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                .build())
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(rate)
                .setChannelMask(mask)
                .build())
            .setBufferSizeInBytes(minBuf * 2)
        if (Build.VERSION.SDK_INT >= 26) {
            builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        }
        return builder.build().also { it.play() }
    }

    fun release() {
        running = false
    }
}
