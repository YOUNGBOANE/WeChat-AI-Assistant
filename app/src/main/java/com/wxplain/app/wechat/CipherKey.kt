package com.wxplain.app.wechat

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
}
