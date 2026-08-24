package com.wxplain.app

import android.content.Context
import org.json.JSONObject

data class AssistantConfig(
    val prompt: String = "",
    val defaultMemory: String = "",
    val keywords: List<KeywordRule> = emptyList(),
    val hasPrompt: Boolean = true,
    val hasDefaultMemory: Boolean = true,
    val hasKeywords: Boolean = true,
)

object ConfigBackup {
    const val KIND = "assistant_config"
    const val VERSION = 1
    const val APP_ID = "com.wxplain.app"

    fun snapshot(context: Context): AssistantConfig = AssistantConfig(
        prompt = PromptStore.load(context),
        defaultMemory = MemoryStore.defaultText(context),
        keywords = KeywordStore.load(context),
    )

    fun exportJson(config: AssistantConfig): String {
        val obj = JSONObject()
            .put("app", APP_ID)
            .put("kind", KIND)
            .put("version", VERSION)
            .put("prompt", config.prompt)
            .put("default_memory", config.defaultMemory)
            .put("keywords", KeywordStore.toArray(config.keywords))
        return obj.toString(2)
    }

    fun parse(raw: String): AssistantConfig {
        val text = raw.trim().removePrefix("\uFEFF")
        if (text.isEmpty()) error("文件是空的")
        val obj = try {
            JSONObject(text)
        } catch (_: Exception) {
            error("不是有效的 JSON")
        }
        val kind = obj.optString("kind").trim()
        if (kind.isNotEmpty() && kind != KIND) error("不是本应用的配置文件")
        val app = obj.optString("app").trim()
        if (app.isNotEmpty() && app != APP_ID) error("不是本应用的配置文件")
        if (kind.isEmpty() && app.isEmpty()) error("不是本应用的配置文件")
        val ver = obj.optInt("version", 1)
        if (ver > VERSION) error("配置文件版本过新，请先升级应用")
        val hasPrompt = obj.has("prompt")
        val hasMemory = obj.has("default_memory")
        val hasKeywords = obj.has("keywords")
        if (!hasPrompt && !hasMemory && !hasKeywords) {
            error("文件里没有提示词、默认记忆或关键词资料")
        }
        val keywords = if (hasKeywords) {
            val arr = obj.optJSONArray("keywords") ?: error("关键词资料格式不对")
            KeywordStore.parseArray(arr)
        } else {
            emptyList()
        }
        return AssistantConfig(
            prompt = obj.optString("prompt"),
            defaultMemory = obj.optString("default_memory"),
            keywords = keywords,
            hasPrompt = hasPrompt,
            hasDefaultMemory = hasMemory,
            hasKeywords = hasKeywords,
        )
    }

    fun apply(context: Context, config: AssistantConfig) {
        if (config.hasPrompt) PromptStore.save(context, config.prompt)
        if (config.hasDefaultMemory) MemoryStore.saveDefault(context, config.defaultMemory)
        if (config.hasKeywords) KeywordStore.save(context, config.keywords)
    }

    fun summary(config: AssistantConfig): String = buildList {
        if (config.hasPrompt) {
            val n = config.prompt.trim().length
            add(if (n == 0) "提示词：空白" else "提示词：$n 字")
        }
        if (config.hasDefaultMemory) {
            val n = config.defaultMemory.trim().length
            add(if (n == 0) "默认记忆：空白" else "默认记忆：$n 字")
        }
        if (config.hasKeywords) {
            add("关键词资料：${config.keywords.size} 条")
        }
    }.joinToString("\n")
}
