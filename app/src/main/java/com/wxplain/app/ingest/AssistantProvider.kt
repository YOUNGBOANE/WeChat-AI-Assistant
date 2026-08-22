package com.wxplain.app.ingest

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
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
import com.wxplain.app.wechat.LiveDb
import com.wxplain.app.wechat.WeChatStore

class AssistantProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val out = Bundle()
        if (!callerAllowed()) {
            out.putString("error", "无权调用")
            return out
        }
        val ctx = context
        if (ctx == null) {
            out.putString("error", "助手未启动")
            return out
        }
        when (method) {
            "complete" -> {}
            "pending_save", "pending_get", "pending_clear" -> return pendingCall(ctx, method, extras)
            else -> {
                out.putString("error", "未知方法")
                return out
            }
        }
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
            val memory = if (talker.isNotBlank() && (liveTalkers == null || talker in liveTalkers)) {
                MemoryStore.ensureInitial(ctx, talker, nick)
            } else {
                ""
            }
            val hasMemory = memory.isNotBlank()
            val msgs = if (talker.isNotBlank()) LiveDb.messages(ctx, talker, ChatSlice.FETCH) else emptyList()
            chat = if (msgs.isNotEmpty()) {
                LiveDb.recentFromMessages(msgs, hasMemory)
            } else {
                ChatSlice.compact(fallback.lines(), hasMemory)
            }
            val peer = if (msgs.isNotEmpty()) {
                LiveDb.lastPeerBurstFromMessages(msgs)
            } else {
                LiveDb.lastPeerBurstFromTranscript(chat)
            }
            val hits = KeywordStore.match(KeywordStore.load(ctx), peer)
            val extra = KeywordStore.pack(hits)
            val result = AiClient.complete(ModelStore.load(ctx), prompt, chat, extra, memory, choice)
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
            UsageLogStore.add(
                ctx,
                UsageLog(
                    time = System.currentTimeMillis(),
                    nick = nick,
                    sent = result.sent,
                    reply = result.reply,
                    error = "",
                ),
            )
            out.putInt("chat_chars", chat.length)
            out
        } catch (t: Throwable) {
            val msg = t.message ?: t.javaClass.simpleName
            UsageLogStore.add(
                ctx,
                UsageLog(
                    time = System.currentTimeMillis(),
                    nick = nick,
                    sent = "",
                    reply = "",
                    error = msg,
                ),
            )
            out.putString("error", msg)
            out
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    private fun pendingCall(ctx: android.content.Context, method: String, extras: Bundle?): Bundle {
        val out = Bundle()
        val talker = extras?.getString("talker").orEmpty().trim()
        if (talker.isEmpty()) {
            out.putString("error", "缺少会话")
            return out
        }
        when (method) {
            "pending_save" -> {
                val question = extras?.getString("question").orEmpty().trim()
                val draft = extras?.getString("draft").orEmpty()
                if (question.isEmpty()) {
                    out.putString("error", "缺少问题")
                    return out
                }
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

    private fun callerAllowed(): Boolean {
        val ctx = context ?: return false
        val uid = Binder.getCallingUid()
        if (uid == Process.myUid()) return true
        return try {
            ctx.packageManager.getPackagesForUid(uid)?.contains("com.tencent.mm") == true
        } catch (_: Exception) {
            false
        }
    }
}
