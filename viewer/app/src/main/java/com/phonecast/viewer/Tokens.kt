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
            ?: migrateLegacy(c, room)

    /**
     * 兼容 0.7.x 及更早: 那时的键是 "t:<地址>/<设备名>"(令牌绑在地址上)。
     * 0.8.0 改成只按设备名存(同一台电脑走局域网还是中继都是同一个 agent, 且局域网 IP 常变),
     * 但当时漏了迁移, 结果升级后老用户全部要重新配对 —— 这里补回来。
     */
    private fun migrateLegacy(c: Context, room: String): String? {
        val p = prefs(c)
        val legacyKey = p.all.keys.firstOrNull { it.startsWith("t:") && it.endsWith("/$room") }
            ?: return null
        val token = (p.all[legacyKey] as? String)?.takeIf { it.isNotEmpty() } ?: return null
        p.edit().putString(key(room), token).remove(legacyKey).apply()
        return token
    }

    fun put(c: Context, room: String, token: String) {
        prefs(c).edit().putString(key(room), token).apply()
    }

    fun clear(c: Context, room: String) {
        prefs(c).edit().remove(key(room)).apply()
    }
}
