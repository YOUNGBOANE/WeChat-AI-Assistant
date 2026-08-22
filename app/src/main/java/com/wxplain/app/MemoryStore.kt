package com.wxplain.app

import android.content.Context
import com.wxplain.app.ai.AiEnvelope
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class ChatMemory(
    val talker: String,
    val nick: String,
    val text: String,
    val updatedAt: Long,
)

object MemoryStore {
    private const val FILE = "chat_memories.json"
    private const val DEFAULT_PREF = "memory_default"
    private const val DEFAULT_KEY = "text"

    fun parse(raw: String): List<ChatMemory> {
        val arr = JSONArray(raw)
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val talker = o.optString("talker").trim()
            if (talker.isEmpty()) return@mapNotNull null
            val text = o.optString("text").trim()
            ChatMemory(
                talker = talker,
                nick = o.optString("nick"),
                text = text,
                updatedAt = o.optLong("updatedAt"),
            )
        }
    }

    fun stringify(list: List<ChatMemory>): String {
        val arr = JSONArray()
        for (m in list) {
            if (m.talker.isBlank()) continue
            arr.put(
                JSONObject()
                    .put("talker", m.talker)
                    .put("nick", m.nick)
                    .put("text", m.text)
                    .put("updatedAt", m.updatedAt),
            )
        }
        return arr.toString()
    }

    fun upsert(
        list: List<ChatMemory>,
        talker: String,
        nick: String,
        text: String,
        now: Long = System.currentTimeMillis(),
    ): List<ChatMemory> {
        val id = talker.trim()
        if (id.isEmpty()) return list
        val body = text.trim()
        val idx = list.indexOfFirst { it.talker == id }
        val item = ChatMemory(id, nick.trim(), body, now)
        return if (idx < 0) list + item else list.toMutableList().also { it[idx] = item }
    }

    fun textOf(list: List<ChatMemory>, talker: String): String =
        list.firstOrNull { it.talker == talker.trim() }?.text.orEmpty()

    @Synchronized
    fun load(context: Context): List<ChatMemory> {
        val file = File(context.filesDir, FILE)
        if (!file.exists()) return emptyList()
        return try {
            parse(file.readText())
        } catch (_: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun save(context: Context, list: List<ChatMemory>) {
        File(context.filesDir, FILE).writeText(stringify(list))
    }

    fun mergeAppend(
        list: List<ChatMemory>,
        talker: String,
        nick: String,
        addition: String,
        now: Long = System.currentTimeMillis(),
    ): List<ChatMemory> {
        val add = addition.trim()
        if (talker.trim().isEmpty() || add.isEmpty()) return list
        val old = textOf(list, talker)
        val merged = if (old.isBlank()) add else "${old.trimEnd()}\n$add"
        return upsert(list, talker, nick, merged, now)
    }

    fun put(context: Context, talker: String, nick: String, text: String) {
        save(context, upsert(load(context), talker, nick, text))
    }

    fun append(context: Context, talker: String, nick: String, addition: String) {
        save(context, mergeAppend(load(context), talker, nick, addition))
    }

    fun applyMemory(
        list: List<ChatMemory>,
        talker: String,
        nick: String,
        envelope: AiEnvelope,
        now: Long = System.currentTimeMillis(),
    ): List<ChatMemory> {
        if (talker.isBlank()) return list
        return when {
            envelope.context.isNotBlank() -> upsert(list, talker, nick, envelope.context, now)
            envelope.contextUpdate.isNotBlank() -> mergeAppend(list, talker, nick, envelope.contextUpdate, now)
            else -> list
        }
    }

    fun applyEnvelope(context: Context, talker: String, nick: String, envelope: AiEnvelope) {
        if (talker.isBlank()) return
        save(context, applyMemory(load(context), talker, nick, envelope))
    }

    fun defaultText(context: Context): String =
        context.getSharedPreferences(DEFAULT_PREF, Context.MODE_PRIVATE)
            .getString(DEFAULT_KEY, "")
            .orEmpty()

    fun saveDefault(context: Context, text: String) {
        context.getSharedPreferences(DEFAULT_PREF, Context.MODE_PRIVATE)
            .edit()
            .putString(DEFAULT_KEY, text)
            .apply()
    }

    fun ensureInitial(
        list: List<ChatMemory>,
        talker: String,
        nick: String,
        defaultText: String,
        now: Long = System.currentTimeMillis(),
    ): List<ChatMemory> {
        val id = talker.trim()
        if (id.isEmpty()) return list
        if (list.any { it.talker == id }) return list
        val def = defaultText.trim()
        if (def.isEmpty()) return list
        return upsert(list, id, nick, def, now)
    }

    fun ensureInitial(context: Context, talker: String, nick: String): String {
        val id = talker.trim()
        if (id.isEmpty()) return ""
        val next = ensureInitial(load(context), id, nick, defaultText(context))
        save(context, next)
        return textOf(next, id)
    }

    fun pruneMissing(list: List<ChatMemory>, liveTalkers: Set<String>): List<ChatMemory> =
        list.filter { it.talker in liveTalkers }

    fun pruneMissing(context: Context, liveTalkers: Set<String>): Int {
        val before = load(context)
        val after = pruneMissing(before, liveTalkers)
        if (after.size != before.size) save(context, after)
        return before.size - after.size
    }

    fun text(context: Context, talker: String): String = textOf(load(context), talker)

    fun talkers(context: Context): Set<String> =
        load(context).filter { it.text.isNotBlank() }.map { it.talker }.toSet()
}
