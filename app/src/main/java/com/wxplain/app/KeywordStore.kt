package com.wxplain.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class KeywordRule(
    val id: String,
    val keyword: String,
    val data: String,
)

object KeywordStore {
    private const val FILE = "keyword_rules.json"

    @Synchronized
    fun load(context: Context): List<KeywordRule> {
        val file = File(context.filesDir, FILE)
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                KeywordRule(
                    id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
                    keyword = o.optString("keyword"),
                    data = o.optString("data"),
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun save(context: Context, rules: List<KeywordRule>) {
        val arr = JSONArray()
        for (r in rules) {
            arr.put(
                JSONObject()
                    .put("id", r.id)
                    .put("keyword", r.keyword)
                    .put("data", r.data),
            )
        }
        File(context.filesDir, FILE).writeText(arr.toString())
    }

    fun tokens(raw: String): List<String> =
        raw.split(Regex("[、,，/;；|\\n\\r]+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

    fun matchedTokens(rule: KeywordRule, texts: List<String>): List<String> {
        if (texts.isEmpty()) return emptyList()
        return tokens(rule.keyword).filter { k ->
            texts.any { it.contains(k, ignoreCase = true) }
        }
    }

    fun match(rules: List<KeywordRule>, texts: List<String>): List<KeywordRule> {
        if (texts.isEmpty()) return emptyList()
        return rules.filter { matchedTokens(it, texts).isNotEmpty() }
    }

    fun pack(hits: List<KeywordRule>): String {
        if (hits.isEmpty()) return ""
        return hits.joinToString("\n\n") { r ->
            val title = tokens(r.keyword).joinToString("、").ifBlank { r.keyword.trim() }
            val body = r.data.trim()
            if (body.isEmpty()) "【$title】" else "【$title】\n$body"
        }
    }
}
