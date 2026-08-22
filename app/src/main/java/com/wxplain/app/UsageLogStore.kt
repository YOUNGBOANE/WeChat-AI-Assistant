package com.wxplain.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class UsageLog(
    val time: Long,
    val nick: String,
    val sent: String,
    val reply: String,
    val error: String,
) {
    val ok: Boolean get() = error.isBlank() && reply.isNotBlank()
}

object UsageLogStore {
    private const val FILE = "usage_logs.json"
    private const val PREF = "usage_log_meta"
    private const val WIPED = "wiped_plain"
    private const val MAX = 40

    private fun wipeOld(context: Context) {
        val p = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        if (p.getBoolean(WIPED, false)) return
        File(context.filesDir, FILE).delete()
        p.edit().putBoolean(WIPED, true).apply()
    }

    @Synchronized
    fun add(context: Context, log: UsageLog) {
        wipeOld(context)
        val list = load(context).toMutableList()
        list.add(0, log)
        while (list.size > MAX) list.removeAt(list.lastIndex)
        save(context, list)
    }

    @Synchronized
    fun clear(context: Context) {
        wipeOld(context)
        File(context.filesDir, FILE).delete()
    }

    @Synchronized
    fun load(context: Context): List<UsageLog> {
        wipeOld(context)
        val file = File(context.filesDir, FILE)
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                UsageLog(
                    time = o.optLong("time"),
                    nick = o.optString("nick"),
                    sent = o.optString("sent"),
                    reply = o.optString("reply"),
                    error = o.optString("error"),
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun save(context: Context, list: List<UsageLog>) {
        val arr = JSONArray()
        for (log in list) {
            arr.put(
                JSONObject()
                    .put("time", log.time)
                    .put("nick", log.nick)
                    .put("sent", log.sent)
                    .put("reply", log.reply)
                    .put("error", log.error),
            )
        }
        File(context.filesDir, FILE).writeText(arr.toString())
    }
}
