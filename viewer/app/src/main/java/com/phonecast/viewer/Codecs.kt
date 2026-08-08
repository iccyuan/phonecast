package com.phonecast.viewer

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat

/** 本机解码能力查询: 用于向电脑端上报(决定是否启用 H.265)。 */
object Codecs {

    /** 是否存在【硬件】解码器 —— 软解 H.265 在手机上跟不上实时投屏, 不能算数 */
    fun canDecode(mime: String): Boolean = try {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            !info.isEncoder &&
                info.supportedTypes.any { it.equals(mime, ignoreCase = true) } &&
                isHardware(info)
        }
    } catch (_: Exception) {
        false
    }

    private fun isHardware(info: MediaCodecInfo): Boolean =
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            info.isHardwareAccelerated
        } else {
            // 老系统没有这个 API, 用命名约定判断: 软解通常叫 OMX.google.* / c2.android.*
            val n = info.name.lowercase()
            !n.startsWith("omx.google.") && !n.startsWith("c2.android.")
        }

    /** scrcpy 的 fourcc → MediaCodec MIME */
    fun mimeFor(fourcc: String): String = when (fourcc) {
        "h265" -> MediaFormat.MIMETYPE_VIDEO_HEVC
        "av01" -> MediaFormat.MIMETYPE_VIDEO_AV1
        else -> MediaFormat.MIMETYPE_VIDEO_AVC
    }
}
