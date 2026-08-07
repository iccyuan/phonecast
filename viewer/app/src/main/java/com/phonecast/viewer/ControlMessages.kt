package com.phonecast.viewer

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * scrcpy v2.7 控制消息编码 (手机B → server, 经电脑中继原样转发)。
 * 字段布局须与 scrcpy-server 的 ControlMessageReader 逐字节一致, 大端序。
 */
object ControlMessages {

    const val ACTION_DOWN = 0 // AMOTION_EVENT_ACTION_DOWN / AKEY_EVENT_ACTION_DOWN
    const val ACTION_UP = 1
    const val ACTION_MOVE = 2

    const val KEYCODE_HOME = 3
    const val KEYCODE_BACK = 4
    const val KEYCODE_APP_SWITCH = 187

    private const val TYPE_INJECT_KEYCODE: Byte = 0
    private const val TYPE_INJECT_TOUCH_EVENT: Byte = 2

    /**
     * 触摸事件, 32 字节。x/y 为视频帧坐标, videoW/videoH 必须与 server 当前的视频尺寸
     * 一致(不一致会被 server 丢弃), 取解码器最新输出尺寸即可。
     */
    fun touch(action: Int, pointerId: Long, x: Int, y: Int, videoW: Int, videoH: Int, pressed: Boolean): ByteArray =
        ByteBuffer.allocate(32).order(ByteOrder.BIG_ENDIAN).apply {
            put(TYPE_INJECT_TOUCH_EVENT)
            put(action.toByte())
            putLong(pointerId)
            putInt(x)
            putInt(y)
            putShort(videoW.toShort())
            putShort(videoH.toShort())
            putShort(if (pressed) 0xFFFF.toShort() else 0) // pressure, u16 定点
            putInt(0) // actionButton
            putInt(0) // buttons
        }.array()

    /** 按键: 一次完整的 down+up, 各 14 字节。 */
    fun keyPress(keycode: Int): List<ByteArray> = listOf(key(ACTION_DOWN, keycode), key(ACTION_UP, keycode))

    private fun key(action: Int, keycode: Int): ByteArray =
        ByteBuffer.allocate(14).order(ByteOrder.BIG_ENDIAN).apply {
            put(TYPE_INJECT_KEYCODE)
            put(action.toByte())
            putInt(keycode)
            putInt(0) // repeat
            putInt(0) // metaState
        }.array()
}
