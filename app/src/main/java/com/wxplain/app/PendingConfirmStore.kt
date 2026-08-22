package com.wxplain.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class PendingConfirm(
    val talker: String,
    val question: String,
    val draft: String,
    val updatedAt: Long,
)

object PendingConfirmStore {
    private const val FILE = "pending_confirms.json"
    private const val MAX = 20

    fun parse(raw: String): List<PendingConfirm> {
        val arr = JSONArray(raw)
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val talker = o.optString("talker").trim()
            val question = o.optString("question").trim()
            if (talker.isEmpty() || question.isEmpty()) return@mapNotNull null
            PendingConfirm(
                talker = talker,
                question = question,
                draft = o.optString("draft"),
                updatedAt = o.optLong("updatedAt"),
            )
        }
    }

    fun stringify(list: List<PendingConfirm>): String {
        val arr = JSONArray()
        for (p in list) {
            if (p.talker.isBlank() || p.question.isBlank()) continue
            arr.put(
                JSONObject()
                    .put("talker", p.talker)
                    .put("question", p.question)
                    .put("draft", p.draft)
                    .put("updatedAt", p.updatedAt),
            )
        }
        return arr.toString()
    }

    fun upsert(
        list: List<PendingConfirm>,
        talker: String,
        question: String,
        draft: String,
        now: Long = System.currentTimeMillis(),
    ): List<PendingConfirm> {
        val id = talker.trim()
        val q = question.trim()
        if (id.isEmpty() || q.isEmpty()) return list
        val item = PendingConfirm(id, q, draft, now)
        val next = listOf(item) + list.filterNot { it.talker == id }
        return if (next.size <= MAX) next else next.take(MAX)
    }

    fun remove(list: List<PendingConfirm>, talker: String): List<PendingConfirm> {
        val id = talker.trim()
        if (id.isEmpty()) return list
        return list.filterNot { it.talker == id }
    }

    fun getOf(list: List<PendingConfirm>, talker: String): PendingConfirm? =
        list.firstOrNull { it.talker == talker.trim() }

    @Synchronized
    fun load(context: Context): List<PendingConfirm> {
        val file = File(context.filesDir, FILE)
        if (!file.exists()) return emptyList()
        return try {
            parse(file.readText())
        } catch (_: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun save(context: Context, list: List<PendingConfirm>) {
        File(context.filesDir, FILE).writeText(stringify(list))
    }

    fun put(context: Context, talker: String, question: String, draft: String) {
        save(context, upsert(load(context), talker, question, draft))
    }

    fun putKeepingDraft(context: Context, talker: String, question: String) {
        val prev = get(context, talker)
        val draft = if (prev != null && prev.question == question.trim()) prev.draft else ""
        put(context, talker, question, draft)
    }

    fun get(context: Context, talker: String): PendingConfirm? = getOf(load(context), talker)

    fun clear(context: Context, talker: String) {
        save(context, remove(load(context), talker))
    }

    fun pruneMissing(list: List<PendingConfirm>, liveTalkers: Set<String>): List<PendingConfirm> =
        list.filter { it.talker in liveTalkers }

    fun pruneMissing(context: Context, liveTalkers: Set<String>) {
        val before = load(context)
        val after = pruneMissing(before, liveTalkers)
        if (after.size != before.size) save(context, after)
    }
}
