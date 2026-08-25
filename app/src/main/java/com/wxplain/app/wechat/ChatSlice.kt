package com.wxplain.app.wechat

/**
 * 发给模型的对话切片。
 * 有记忆或可用记录不超过 [INIT_THRESHOLD] 条：最近 [LINES_REPLY] 条、最多 [CHARS_REPLY] 字，用来接话。
 * 无记忆且超过阈值：最近 [LINES_INIT] 条、最多 [CHARS_INIT] 字，只用来初始化 <context>。
 */
object ChatSlice {
    const val INIT_THRESHOLD = 10
    const val FETCH_REPLY = 10
    const val FETCH_INIT = 100
    const val LINES_REPLY = 10
    const val LINES_INIT = 100
    const val CHARS_REPLY = 800
    const val CHARS_INIT = 4000
    const val LINE_MAX = 72

    fun needsMemoryInit(hasMemory: Boolean, lineCount: Int): Boolean =
        !hasMemory && lineCount > INIT_THRESHOLD

    fun prepare(lines: List<String>): List<String> =
        lines.map { clipLine(stripNoise(it)) }.filter { it.isNotEmpty() }

    fun lines(msgs: List<ChatMessage>): List<String> =
        prepare(msgs.asReversed().mapNotNull { line(it) })

    fun compactReply(lines: List<String>): String = compact(lines, LINES_REPLY, CHARS_REPLY)

    fun compactInit(lines: List<String>): String = compact(lines, LINES_INIT, CHARS_INIT)

    fun compact(lines: List<String>, maxLines: Int, maxChars: Int): String {
        val cleaned = prepare(lines)
        if (cleaned.isEmpty()) return ""
        val tail = cleaned.takeLast(maxLines)
        var start = 0
        var size = tail.sumOf { it.length + 1 }
        while (start < tail.lastIndex && size > maxChars) {
            size -= tail[start].length + 1
            start++
        }
        return tail.drop(start).joinToString("\n")
    }

    fun line(m: ChatMessage): String? {
        val body = body(m) ?: return null
        val who = if (m.isSend) "我" else "对方"
        if (!m.isSend && body.contains(": ") && !body.startsWith("[")) {
            val idx = body.indexOf(": ")
            val prefix = body.substring(0, idx).trim()
            if (prefix.isNotEmpty() && !prefix.startsWith("[")) {
                return clipLine("$prefix: ${body.substring(idx + 2)}")
            }
        }
        return clipLine("$who: $body")
    }

    fun body(m: ChatMessage): String? {
        if (m.type == MsgTypes.SYSTEM || m.type == MsgTypes.REVOKE) return null
        if (m.type == MsgTypes.TEXT) {
            val text = m.content.trim()
            return text.takeIf { it.isNotEmpty() }?.let { clipLine(it) }
        }
        val label = MsgTypes.label(m.type)
        val plain = plainText(m.content)
        return if (plain.isEmpty()) "[$label]" else clipLine("[$label] $plain")
    }

    fun plainText(raw: String): String {
        var s = raw.trim()
        if (s.isEmpty()) return ""
        if ('<' in s && '>' in s) {
            s = s.replace(Regex("<[^>]+>"), " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace(Regex("\\s+"), " ")
                .trim()
        }
        return s
    }

    fun stripNoise(line: String): String {
        val t = line.trim()
        if (t.isEmpty()) return ""
        return if ('<' in t && '>' in t) {
            val head = t.substringBefore(':').trim()
            val rest = if (":" in t) t.substringAfter(':').trim() else t
            val plain = plainText(rest)
            when {
                plain.isEmpty() -> ""
                head.isNotEmpty() && head.length <= 12 && head != t -> "$head: $plain"
                else -> plain
            }
        } else t
    }

    fun clipLine(s: String): String {
        val t = s.trim()
        if (t.length <= LINE_MAX) return t
        return t.take(LINE_MAX) + "…"
    }
}
