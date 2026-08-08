package com.phonecast.viewer

import android.content.Context

/**
 * 设备令牌本地存储。
 * 按【设备名】存, 不带地址 —— 同一台电脑无论走局域网还是中继都是同一个 agent,
 * 令牌通用; 而且局域网 IP 经常变, 绑地址会让配对白白失效。
 */
object Tokens {
    private fun prefs(c: Context) = c.getSharedPreferences("phonecast_tokens", Context.MODE_PRIVATE)
    private fun key(room: String) = "room:$room"

    fun get(c: Context, room: String): String? =
        prefs(c).getString(key(room), null)?.takeIf { it.isNotEmpty() }

    fun put(c: Context, room: String, token: String) {
        prefs(c).edit().putString(key(room), token).apply()
    }

    fun clear(c: Context, room: String) {
        prefs(c).edit().remove(key(room)).apply()
    }
}
