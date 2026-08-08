package com.phonecast.viewer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 一条已保存的投屏目标。
 * 同一台电脑可能有多条可达路径(局域网直连 / 公网中继), 按优先级存成列表,
 * 连接时依次尝试 —— 局域网通就走局域网, 不通自动回落中继。
 * name 是电脑回报的被投屏手机机型。
 */
data class Entry(val room: String, val addrs: List<String>, val name: String = "") {
    /** 列表主标题: 优先显示真实机型 */
    val title: String get() = if (name.isNotEmpty()) name else room

    companion object {
        fun hostOf(addr: String): String = addr.substringBeforeLast(':')
        fun portOf(addr: String): Int = addr.substringAfterLast(':').toIntOrNull() ?: 27184

        /** 私网地址视为局域网, 优先尝试且超时给得短 */
        fun isLan(addr: String): Boolean {
            val h = hostOf(addr)
            return h.startsWith("192.168.") || h.startsWith("10.") || h == "127.0.0.1" ||
                Regex("^172\\.(1[6-9]|2\\d|3[01])\\.").containsMatchIn(h)
        }

        /** 局域网排前面 */
        fun order(addrs: List<String>): List<String> =
            addrs.filter { it.isNotBlank() }.distinct().sortedByDescending { isLan(it) }
    }

    fun routeLabel(): String {
        val lan = addrs.count { isLan(it) }
        val relay = addrs.size - lan
        return when {
            lan > 0 && relay > 0 -> "局域网优先,可回落中继"
            lan > 0 -> "局域网直连"
            else -> "经中继"
        }
    }
}

/** 已保存列表 (按最近使用排序), 存 SharedPreferences 的 JSON 数组。 */
object Saved {
    private const val PREFS = "phonecast_saved"
    private const val KEY = "list"

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun list(c: Context): List<Entry> {
        val raw = prefs(c).getString(KEY, "[]") ?: "[]"
        val arr = runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val room = o.optString("room")
            if (room.isEmpty()) return@mapNotNull null
            val addrs = o.optJSONArray("addrs")?.let { a ->
                (0 until a.length()).map { a.optString(it) }
            } ?: listOfNotNull(o.optString("addr").takeIf { it.isNotEmpty() }) // 兼容旧格式
            if (addrs.isEmpty()) null else Entry(room, Entry.order(addrs), o.optString("name"))
        }
    }

    /**
     * 新增或提到最前(最近使用优先)。同一设备名视为同一台:
     * 地址会合并 —— 先扫码拿到中继地址、之后在家扫到局域网地址, 两条都留着。
     */
    fun touch(c: Context, e: Entry) {
        val old = list(c).find { it.room == e.room }
        val merged = Entry(
            room = e.room,
            addrs = Entry.order(e.addrs + (old?.addrs ?: emptyList())),
            name = e.name.ifEmpty { old?.name ?: "" },
        )
        save(c, listOf(merged) + list(c).filterNot { it.room == e.room })
    }

    /** 电脑端回报机型后更新显示名 */
    fun setName(c: Context, room: String, name: String) {
        if (name.isEmpty()) return
        val items = list(c)
        if (items.none { it.room == room }) return
        save(c, items.map { if (it.room == room) it.copy(name = name) else it })
    }

    /**
     * 用电脑报来的当前局域网地址【替换】本地存的局域网地址(中继地址保留)。
     * 换 WiFi 后旧 IP 会永远连不上, 留着只会让每次连接白等超时, 所以是替换不是追加。
     */
    fun updateLanAddrs(c: Context, room: String, lan: List<String>) {
        if (lan.isEmpty()) return
        val items = list(c)
        val e = items.find { it.room == room } ?: return
        val relay = e.addrs.filterNot { Entry.isLan(it) }
        val next = Entry.order(lan + relay)
        if (next == e.addrs) return
        save(c, items.map { if (it.room == room) it.copy(addrs = next) else it })
    }

    /** 连接成功的地址提到最前, 下次先试它 */
    fun promote(c: Context, room: String, addr: String) {
        val items = list(c)
        val e = items.find { it.room == room } ?: return
        if (e.addrs.firstOrNull() == addr) return
        val next = e.copy(addrs = listOf(addr) + e.addrs.filterNot { it == addr })
        save(c, items.map { if (it.room == room) next else it })
    }

    fun remove(c: Context, e: Entry) {
        save(c, list(c).filterNot { it.room == e.room })
        Tokens.clear(c, e.room)
    }

    private fun save(c: Context, items: List<Entry>) {
        val arr = JSONArray()
        items.forEach { e ->
            arr.put(JSONObject()
                .put("room", e.room)
                .put("addrs", JSONArray().apply { e.addrs.forEach { put(it) } })
                .put("name", e.name))
        }
        prefs(c).edit().putString(KEY, arr.toString()).apply()
    }
}
