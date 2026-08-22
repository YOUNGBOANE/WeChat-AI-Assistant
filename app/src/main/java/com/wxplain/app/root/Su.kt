package com.wxplain.app.root

import com.topjohnwu.superuser.Shell

object Su {
    fun available(): Boolean {
        return try {
            val r = Shell.cmd("id -u").exec()
            r.isSuccess && r.out.any { it.trim() == "0" }
        } catch (_: Exception) {
            false
        }
    }

    fun run(cmd: String): String {
        val r = Shell.cmd(cmd).exec()
        val out = r.out.joinToString("\n").trim()
        return if (out.isNotEmpty()) out else r.err.joinToString("\n").trim()
    }

    fun ok(cmd: String): Boolean = Shell.cmd(cmd).exec().isSuccess
}
