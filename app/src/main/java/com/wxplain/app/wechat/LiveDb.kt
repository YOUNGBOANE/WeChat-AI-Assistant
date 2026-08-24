package com.wxplain.app.wechat

import android.content.Context
import com.wxplain.app.MemoryStore
import com.wxplain.app.PendingConfirmStore
import com.wxplain.app.ingest.KeyStore

object LiveDb {
    fun conversationUsernames(context: Context): Set<String>? {
        if (!WeChatStore.snapshotFile(context).exists()) return null
        return try {
            query(context, "SELECT username FROM rconversation;").lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("ok") && !it.startsWith("Error") }
                .toSet()
        } catch (_: Exception) {
            null
        }
    }

    fun pruneDeletedChats(context: Context): Set<String>? {
        val names = conversationUsernames(context) ?: return null
        MemoryStore.pruneMissing(context, names)
        PendingConfirmStore.pruneMissing(context, names)
        return names
    }

    fun conversations(context: Context): List<Conversation> {
        val sql = """
            SELECT c.username,
                   IFNULL(NULLIF(r.conRemark,''), IFNULL(r.nickname, c.username)),
                   IFNULL(c.unReadCount, 0),
                   IFNULL(c.conversationTime, 0)
            FROM rconversation c
            LEFT JOIN rcontact r ON c.username = r.username
            ORDER BY c.conversationTime DESC
            LIMIT 300;
        """.trimIndent()
        val out = query(context, sql)
        val list = ArrayList<Conversation>()
        for (line in out.lines()) {
            val p = line.split("|")
            if (p.size < 4) continue
            if (p[0].startsWith("ok") || p[0].startsWith("Error")) continue
            val user = p[0].trim()
            if (user.isEmpty()) continue
            val nick = p[1].ifBlank { user }
            val unread = p[2].toIntOrNull() ?: 0
            val time = p[3].toLongOrNull() ?: 0L
            val kind = when {
                user.endsWith("@chatroom") -> Conversation.Kind.GROUP
                user.startsWith("gh_") || user.endsWith("@app") -> Conversation.Kind.OFFICIAL
                else -> Conversation.Kind.CONTACT
            }
            list += Conversation(user, nick, unread, time, kind)
        }
        return list
    }

    fun messages(context: Context, talker: String, limit: Int = 400): List<ChatMessage> {
        val safe = talker.replace("'", "''")
        val sql = """
            SELECT msgId, msgSvrId, type,
                   replace(replace(IFNULL(content,''), char(10), ' '), '|', '/'),
                   createTime, isSend, IFNULL(imgPath,'')
            FROM message
            WHERE talker = '$safe'
            ORDER BY createTime DESC
            LIMIT $limit;
        """.trimIndent()
        val out = query(context, sql)
        val list = ArrayList<ChatMessage>()
        for (line in out.lines()) {
            val p = line.split("|")
            if (p.size < 6) continue
            if (p[0].startsWith("ok") || !p[0].all { it.isDigit() || it == '-' }) continue
            list += ChatMessage(
                msgId = p[0].toLongOrNull() ?: 0L,
                msgSvrId = p[1].toLongOrNull() ?: 0L,
                type = p[2].toIntOrNull() ?: 0,
                content = p[3],
                createTime = p[4].toLongOrNull() ?: 0L,
                isSend = p[5] == "1",
                imgPath = p.getOrElse(6) { "" },
            )
        }
        return list
    }

    fun contactName(context: Context, username: String): String {
        val safe = username.replace("'", "''")
        val sql = """
            SELECT IFNULL(NULLIF(conRemark,''), IFNULL(nickname, username))
            FROM rcontact WHERE username = '$safe' LIMIT 1;
        """.trimIndent()
        return try {
            query(context, sql)
                .lines()
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() && !it.startsWith("ok") && !it.startsWith("Error") }
                ?: username
        } catch (_: Exception) {
            username
        }
    }

    fun resolveTalker(context: Context, raw: String): String {
        val id = raw.trim()
        if (id.isEmpty()) return ""
        if (id.startsWith("wxid_") || id.endsWith("@chatroom") || id.startsWith("gh_")) return id
        val byUser = try {
            messages(context, id, 1)
        } catch (_: Exception) {
            emptyList()
        }
        if (byUser.isNotEmpty()) return id
        val safe = id.replace("'", "''")
        val sql = """
            SELECT username FROM rcontact
            WHERE username = '$safe' OR nickname = '$safe' OR conRemark = '$safe'
            LIMIT 1;
        """.trimIndent()
        return try {
            query(context, sql)
                .lines()
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() && !it.startsWith("ok") && !it.startsWith("Error") }
                ?: id
        } catch (_: Exception) {
            id
        }
    }

    fun transcript(context: Context, talker: String, limit: Int = 80): String {
        val msgs = messages(context, talker, limit).asReversed()
        if (msgs.isEmpty()) return ""
        return msgs.mapNotNull { line(it) }.joinToString("\n")
    }

    fun recentTranscript(context: Context, talker: String, hasMemory: Boolean): String =
        recentFromMessages(messages(context, talker, ChatSlice.FETCH), hasMemory)

    fun recentFromMessages(msgs: List<ChatMessage>, hasMemory: Boolean): String {
        if (msgs.isEmpty()) return ""
        return ChatSlice.compact(msgs.asReversed().mapNotNull { ChatSlice.line(it) }, hasMemory)
    }

    /** 对方最近一次连续发言（跳过末尾自己发的），按时间从早到晚。 */
    fun lastPeerBurst(context: Context, talker: String, limit: Int = ChatSlice.FETCH): List<String> =
        lastPeerBurstFromMessages(messages(context, talker, limit))

    fun lastPeerBurstFromMessages(msgs: List<ChatMessage>): List<String> {
        val burst = ArrayList<String>()
        var started = false
        for (m in msgs) {
            if (m.type == MsgTypes.SYSTEM || m.type == MsgTypes.REVOKE) continue
            if (m.isSend) {
                if (started) break
                continue
            }
            started = true
            val t = peerText(m)
            if (t.isNotEmpty()) burst += t
        }
        return burst.asReversed()
    }

    fun lastPeerBurstFromTranscript(chat: String): List<String> {
        val lines = chat.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val burst = ArrayList<String>()
        var started = false
        for (i in lines.indices.reversed()) {
            val line = lines[i]
            val mine = line.startsWith("我:") || line.startsWith("我：")
            if (mine) {
                if (started) break
                continue
            }
            started = true
            val text = when {
                line.startsWith("对方:") -> line.removePrefix("对方:").trim()
                line.startsWith("对方：") -> line.removePrefix("对方：").trim()
                ":" in line -> line.substringAfter(":").trim()
                else -> line
            }
            if (text.isNotEmpty()) burst += text
        }
        return burst.asReversed()
    }

    private fun line(m: ChatMessage): String? {
        val body = displayBody(m) ?: return null
        val who = if (m.isSend) "我" else "对方"
        if (!m.isSend && body.contains(": ")) {
            val idx = body.indexOf(": ")
            val prefix = body.substring(0, idx).trim()
            if (prefix.isNotEmpty() && !prefix.startsWith("[")) {
                return "${prefix}: ${body.substring(idx + 2).take(400)}"
            }
        }
        return "$who: ${body.take(400)}"
    }

    private fun peerText(m: ChatMessage): String {
        val body = displayBody(m) ?: return ""
        if (body.contains(": ")) {
            val idx = body.indexOf(": ")
            val prefix = body.substring(0, idx).trim()
            if (prefix.isNotEmpty() && !prefix.startsWith("[")) {
                return body.substring(idx + 2).trim()
            }
        }
        return body
    }

    private fun displayBody(m: ChatMessage): String? = ChatSlice.body(m)

    private fun query(context: Context, sql: String): String {
        val dbFile = WeChatStore.snapshotFile(context)
        if (!dbFile.exists()) error("请先刷新会话")
        val keys = WeChatStore.candidatePasswords(context)
        if (keys.isEmpty()) error("尚未捕获密钥：请在 LSPosed 启用本模块并完全退出后重开微信")
        for (key in keys) {
            val out = SqlCipherCli.query(dbFile.absolutePath, key, sql)
            if (WeChatStore.isDecryptError(out)) continue
            WeChatStore.rememberWorkingPassword(context, key)
            return out
        }
        KeyStore.clear(context)
        error("数据库无法解密，可能已换号。请完全退出并重开微信后再刷新")
    }
}
