package com.wxplain.app.ingest

import android.content.Context
import android.os.Bundle
import com.wxplain.app.KeywordStore
import com.wxplain.app.MemoryStore
import com.wxplain.app.PendingConfirmStore
import com.wxplain.app.PromptStore
import com.wxplain.app.UsageLog
import com.wxplain.app.UsageLogStore
import com.wxplain.app.ai.AiClient
import com.wxplain.app.ai.AiEnvelope
import com.wxplain.app.ai.ModelStore
import com.wxplain.app.wechat.ChatSlice
import com.wxplain.app.wechat.CipherKey
import com.wxplain.app.wechat.LiveDb
import com.wxplain.app.wechat.WeChatStore

object AssistantApi {
    fun handle(ctx: Context, method: String, extras: Bundle?): Bundle {
        return when (method) {
            "complete" -> complete(ctx, extras)
            "pending_save", "pending_get", "pending_clear" -> pendingCall(ctx, method, extras)
            "key_save" -> keySave(ctx, extras)
            else -> errorBundle("未知方法")
        }
    }

    private fun complete(ctx: Context, extras: Bundle?): Bundle {
        val out = Bundle()
        val talkerRaw = extras?.getString("talker").orEmpty().trim()
        val fallback = extras?.getString("chat").orEmpty()
        val choice = extras?.getString("choice").orEmpty().trim()
        val prompt = PromptStore.load(ctx)
        var talker = talkerRaw
        var nick = talkerRaw
        var chat = fallback
        return try {
            WeChatStore.refreshSnapshot(ctx).getOrThrow()
            val liveTalkers = LiveDb.pruneDeletedChats(ctx)
            talker = LiveDb.resolveTalker(ctx, talkerRaw)
            if (talker.isNotBlank()) {
                nick = LiveDb.contactName(ctx, talker)
            }
            var memory = if (talker.isNotBlank() && (liveTalkers == null || talker in liveTalkers)) {
                MemoryStore.text(ctx, talker)
            } else {
                ""
            }
            val fetch = if (memory.isNotBlank()) ChatSlice.FETCH_REPLY else ChatSlice.FETCH_INIT
            val msgs = if (talker.isNotBlank()) LiveDb.messages(ctx, talker, fetch) else emptyList()
            val lines = if (msgs.isNotEmpty()) {
                ChatSlice.lines(msgs)
            } else {
                ChatSlice.prepare(fallback.lines())
            }
            val peer = if (msgs.isNotEmpty()) {
                LiveDb.lastPeerBurstFromMessages(msgs)
            } else {
                LiveDb.lastPeerBurstFromTranscript(lines.joinToString("\n"))
            }
            val extra = KeywordStore.pack(KeywordStore.match(KeywordStore.load(ctx), peer))
            val config = ModelStore.load(ctx)
            var memoryUpdated = false
            if (choice.isBlank() && talker.isNotBlank() &&
                ChatSlice.needsMemoryInit(memory.isNotBlank(), lines.size)
            ) {
                val initChat = ChatSlice.compactInit(lines)
                val initResult = AiClient.complete(
                    config, prompt, initChat, extra, memory = "", choice = "", initMemory = true,
                )
                val initEnvelope = AiEnvelope.parse(initResult.reply)
                if (initEnvelope.context.isBlank() && initEnvelope.contextUpdate.isBlank()) {
                    error("模型没有返回 <context>")
                }
                MemoryStore.applyEnvelope(ctx, talker, nick, initEnvelope)
                memory = MemoryStore.text(ctx, talker)
                if (memory.isBlank()) error("记忆初始化失败")
                memoryUpdated = true
                logUsage(ctx, nick, initResult.sent, initResult.reply, "")
            }
            chat = ChatSlice.compactReply(lines)
            val result = AiClient.complete(config, prompt, chat, extra, memory, choice)
            val envelope = AiEnvelope.parse(result.reply)
            if (!envelope.hasOptions && !envelope.hasReply) {
                error("模型没有返回 <reply> 或 <option>")
            }
            MemoryStore.applyEnvelope(ctx, talker, nick, envelope)
            if (talker.isNotBlank()) {
                if (envelope.hasOptions) PendingConfirmStore.putKeepingDraft(ctx, talker, envelope.question)
                else PendingConfirmStore.clear(ctx, talker)
            }
            fillResult(out, envelope)
            if (memoryUpdated) out.putBoolean("memory_updated", true)
            logUsage(ctx, nick, result.sent, result.reply, "")
            out.putInt("chat_chars", chat.length)
            out
        } catch (t: Throwable) {
            val msg = t.message ?: t.javaClass.simpleName
            logUsage(ctx, nick, "", "", msg)
            out.putString("error", msg)
            out
        }
    }

    private fun pendingCall(ctx: Context, method: String, extras: Bundle?): Bundle {
        val out = Bundle()
        val talker = extras?.getString("talker").orEmpty().trim()
        if (talker.isEmpty()) return errorBundle("缺少会话")
        when (method) {
            "pending_save" -> {
                val question = extras?.getString("question").orEmpty().trim()
                val draft = extras?.getString("draft").orEmpty()
                if (question.isEmpty()) return errorBundle("缺少问题")
                PendingConfirmStore.put(ctx, talker, question, draft)
            }
            "pending_get" -> {
                val p = PendingConfirmStore.get(ctx, talker)
                if (p != null) {
                    out.putString("question", p.question)
                    out.putString("draft", p.draft)
                }
            }
            "pending_clear" -> PendingConfirmStore.clear(ctx, talker)
        }
        return out
    }

    private fun keySave(ctx: Context, extras: Bundle?): Bundle {
        val hex = extras?.getString("key")?.trim().orEmpty()
        if (hex.length < 4 || hex.length > 128) return errorBundle("密钥无效")
        if (!hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
            return errorBundle("密钥无效")
        }
        val hash = extras?.getString("hash")?.trim().orEmpty()
        KeyStore.save(ctx, hex, password = CipherKey.hexToPassword(hex), hash = hash)
        return Bundle()
    }

    private fun fillResult(out: Bundle, envelope: AiEnvelope) {
        out.putBoolean("memory_updated", envelope.memoryUpdated)
        when {
            envelope.hasOptions -> {
                out.putString("type", "option")
                out.putString("question", envelope.question)
            }
            envelope.hasReply -> {
                out.putString("type", "reply")
                out.putString("text", envelope.reply)
            }
            else -> error("模型没有返回 <reply> 或 <option>")
        }
    }

    private fun logUsage(ctx: Context, nick: String, sent: String, reply: String, error: String) {
        UsageLogStore.add(
            ctx,
            UsageLog(
                time = System.currentTimeMillis(),
                nick = nick,
                sent = sent,
                reply = reply,
                error = error,
            ),
        )
    }

    private fun errorBundle(msg: String) = Bundle().apply { putString("error", msg) }
}
