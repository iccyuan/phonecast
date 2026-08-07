package com.phonecast.viewer

import android.content.Context

/**
 * 设备令牌本地存储: 每个 (地址, 设备名) 一枚。
 * 配对成功后由电脑端下发, 此后连接免输配对码; 电脑端撤销时本地也会清掉。
 */
object Tokens {
    private fun prefs(c: Context) = c.getSharedPreferences("phonecast_tokens", Context.MODE_PRIVATE)
    private fun key(addr: String, room: String) = "t:$addr/$room"

    fun get(c: Context, addr: String, room: String): String? =
        prefs(c).getString(key(addr, room), null)?.takeIf { it.isNotEmpty() }

    fun put(c: Context, addr: String, room: String, token: String) {
        prefs(c).edit().putString(key(addr, room), token).apply()
    }

    fun clear(c: Context, addr: String, room: String) {
        prefs(c).edit().remove(key(addr, room)).apply()
    }
}
