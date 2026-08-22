package com.wxplain.app.wechat

/**
 * 发给模型的对话切片：背景走已知信息，这里只保留最近几句用来接话。
 */
object ChatSlice {
    const val FETCH = 24
    const val LINES_WITH_MEMORY = 8
    const val LINES_WITHOUT_MEMORY = 12
    const val CHARS_WITH_MEMORY = 600
    const val CHARS_WITHOUT_MEMORY = 1000
    const val LINE_MAX = 72

    fun compact(lines: List<String>, hasMemory: Boolean): String {
        val maxLines = if (hasMemory) LINES_WITH_MEMORY else LINES_WITHOUT_MEMORY
        val maxChars = if (hasMemory) CHARS_WITH_MEMORY else CHARS_WITHOUT_MEMORY
        val cleaned = lines.map { clipLine(stripNoise(it)) }.filter { it.isNotEmpty() }
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
