package com.wxplain.app.ai

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

data class AiResult(
    val reply: String,
    val sent: String,
)

object AiClient {
    fun complete(
        config: ModelConfig,
        systemPrompt: String,
        chatLog: String,
        extra: String = "",
        memory: String = "",
        choice: String = "",
    ): AiResult {
        val key = config.apiKey.trim()
        if (key.isEmpty()) error("还没有填写 API Key，先在助手里打开 AI模型管理")

        val user = buildUserContent(chatLog, extra, memory, choice)
        val messages = JSONArray()
        val sys = buildSystemPrompt(systemPrompt)
        messages.put(JSONObject().put("role", "system").put("content", sys))
        messages.put(JSONObject().put("role", "user").put("content", user))
        val sent = "$sys\n\n$user"
        val payload = JSONObject()
            .put("model", config.model.id)
            .put("messages", messages)
            .put("stream", false)

        val url = URL(config.vendor.baseUrl.trimEnd('/') + "/chat/completions")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 90_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer $key")
        }
        try {
            conn.outputStream.use { it.write(payload.toString().toByteArray(StandardCharsets.UTF_8)) }
            val code = conn.responseCode
            val raw = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(StandardCharsets.UTF_8)
                ?.use { it.readText() }
                .orEmpty()
            if (code !in 200..299) error(apiError(raw, code))
            return AiResult(reply = parseContent(raw), sent = sent)
        } finally {
            conn.disconnect()
        }
    }

    fun buildSystemPrompt(userPrompt: String): String {
        val p = userPrompt.trim()
        return if (p.isEmpty()) AiEnvelope.FORMAT_SPEC else "$p\n\n${AiEnvelope.FORMAT_SPEC}"
    }

    fun buildUserContent(
        chatLog: String,
        extra: String = "",
        memory: String = "",
        choice: String = "",
    ): String {
        return buildString {
            val mem = memory.trim()
            if (mem.isNotEmpty()) {
                append("关于这个会话的已知信息：\n")
                append(mem)
                append("\n\n")
            }
            if (chatLog.isBlank()) append("（当前窗口没有可用的文字记录）")
            else {
                append("最近对话：\n")
                append(chatLog)
            }
            if (extra.isNotBlank()) {
                append("\n\n相关资料：\n")
                append(extra.trim())
            }
            if (choice.isNotBlank()) {
                append("\n\n人工已填写：\n")
                append(choice.trim())
                append("\n请根据该答复继续。仍用标签格式；能确定就给 <reply>，还需要人工确认或填写再给新的 <option>。")
            }
            append("\n\n按指定标签格式输出。")
            if (mem.isNotEmpty()) append("背景以已知信息为准，最近对话只用于接当前这一轮。")
            else append("最近对话只用于接当前这一轮。")
        }
    }

    private fun parseContent(raw: String): String {
        val root = JSONObject(raw)
        val msg = root.optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?: error("模型没有返回内容")
        val content = msg.optString("content").trim()
        if (content.isNotEmpty()) return content
        val reasoning = msg.optString("reasoning_content").trim()
        if (reasoning.isNotEmpty()) return reasoning
        error("模型返回为空")
    }

    private fun apiError(raw: String, code: Int): String {
        val parsed = runCatching {
            JSONObject(raw).optJSONObject("error")?.optString("message")
                ?: JSONObject(raw).optString("message")
        }.getOrNull()
        return if (!parsed.isNullOrBlank()) parsed else "接口失败 HTTP $code"
    }
}
