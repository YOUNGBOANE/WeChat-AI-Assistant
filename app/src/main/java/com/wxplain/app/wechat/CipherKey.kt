package com.wxplain.app.wechat

import java.security.MessageDigest

data class CapturedKey(
    val hex: String,
    val path: String = "",
    val hash: String = "",
    val time: Long = 0L,
)

object CipherKey {
    fun hexToPassword(hex: String): String {
        val h = hex.trim().lowercase().removePrefix("key=")
        if (h.length < 2) return ""
        val out = StringBuilder()
        var i = 0
        while (i + 1 < h.length) {
            val b = h.substring(i, i + 2).toIntOrNull(16) ?: break
            if (b in 1..126) out.append(b.toChar())
            i += 2
        }
        return out.toString()
    }

    fun hashFromDbPath(path: String): String {
        val parts = path.replace('\\', '/').split('/').filter { it.isNotEmpty() }
        val i = parts.indexOfLast { it.equals("EnMicroMsg.db", ignoreCase = true) }
        if (i > 0) {
            val dir = parts[i - 1]
            if (dir.length == 32 && dir.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) {
                return dir.lowercase()
            }
        }
        return ""
    }

    fun parseCapturedKey(raw: String): CapturedKey? {
        var hex = ""
        var path = ""
        var hash = ""
        var time = 0L
        for (line in raw.lineSequence()) {
            val t = line.trim()
            when {
                t.startsWith("key=") -> hex = t.removePrefix("key=").trim()
                t.startsWith("path=") -> path = t.removePrefix("path=").trim()
                t.startsWith("hash=") -> hash = t.removePrefix("hash=").trim()
                t.startsWith("time=") -> time = t.removePrefix("time=").trim().toLongOrNull() ?: 0L
            }
        }
        if (hex.isBlank()) {
            val only = raw.trim()
            if (only.matches(Regex("[0-9a-fA-F]{4,128}"))) hex = only
        }
        if (hex.isBlank() || !hex.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) return null
        if (hash.isBlank()) hash = hashFromDbPath(path)
        return CapturedKey(hex, path, hash, time)
    }

    fun parseKeyHistory(raw: String): List<String> {
        val seen = LinkedHashSet<String>()
        val lines = raw.lineSequence().map { it.trim() }.filter { it.matches(Regex("[0-9a-fA-F]{4,128}")) }.toList()
        for (h in lines.asReversed()) seen += h
        return seen.toList()
    }

    fun mergeKeyHexes(live: CapturedKey?, storedHex: String?, history: List<String>): List<String> {
        val out = LinkedHashSet<String>()
        live?.hex?.trim()?.takeIf { it.isNotEmpty() }?.let { out += it }
        storedHex?.trim()?.takeIf { it.isNotEmpty() }?.let { out += it }
        for (h in history) {
            val t = h.trim()
            if (t.isNotEmpty()) out += t
        }
        return out.toList()
    }

    fun parseUinFromPrefs(raw: String): String? {
        val re = Regex("""<(?:int|long)\s+name="(default_uin|_auth_uin|last_login_uin)"\s+value="(-?\d+)"""")
        for (m in re.findAll(raw)) {
            val v = m.groupValues[2]
            if (v != "0") return v
        }
        return null
    }

    fun uinHash(uin: String): String {
        val md = MessageDigest.getInstance("MD5").digest(uin.toByteArray(Charsets.UTF_8))
        return md.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun isDecryptError(out: String): Boolean {
        val t = out.trim()
        if (t.isEmpty()) return false
        if (t.contains("file is not a database", ignoreCase = true)) return true
        if (t.contains("file is encrypted", ignoreCase = true)) return true
        if (t.contains("cipher", ignoreCase = true) && t.contains("error", ignoreCase = true)) return true
        return t.lineSequence().any { line ->
            val s = line.trim()
            s.startsWith("Error") || s.startsWith("error:")
        }
    }
}
