package com.phonecast.viewer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 一条已保存的投屏目标。
 * addr/room 是连接坐标(电脑地址 + 电脑上的设备名), name 是电脑回报的被投屏手机机型。
 */
data class Entry(val addr: String, val room: String, val name: String = "") {
    val host: String get() = addr.substringBeforeLast(':')
    val port: Int get() = addr.substringAfterLast(':').toIntOrNull() ?: 27184
    /** 列表主标题: 优先显示真实机型 */
    val title: String get() = if (name.isNotEmpty()) name else room
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
            arr.optJSONObject(i)?.let {
                val addr = it.optString("addr")
                val room = it.optString("room")
                if (addr.isEmpty() || room.isEmpty()) null
                else Entry(addr, room, it.optString("name"))
            }
        }
    }

    /** 新增或提到最前 (最近使用优先); 已有条目保留其机型名 */
    fun touch(c: Context, e: Entry) {
        val old = list(c).find { it.addr == e.addr && it.room == e.room }
        val merged = if (e.name.isEmpty() && old != null) e.copy(name = old.name) else e
        save(c, listOf(merged) + list(c).filterNot { it.addr == e.addr && it.room == e.room })
    }

    /** 电脑端回报机型后更新显示名 */
    fun setName(c: Context, addr: String, room: String, name: String) {
        if (name.isEmpty()) return
        val items = list(c)
        if (items.none { it.addr == addr && it.room == room }) return
        save(c, items.map { if (it.addr == addr && it.room == room) it.copy(name = name) else it })
    }

    fun remove(c: Context, e: Entry) {
        save(c, list(c).filterNot { it.addr == e.addr && it.room == e.room })
        Tokens.clear(c, e.addr, e.room)
    }

    private fun save(c: Context, items: List<Entry>) {
        val arr = JSONArray()
        items.forEach {
            arr.put(JSONObject().put("addr", it.addr).put("room", it.room).put("name", it.name))
        }
        prefs(c).edit().putString(KEY, arr.toString()).apply()
    }
}
