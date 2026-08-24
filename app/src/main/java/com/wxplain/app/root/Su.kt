package com.wxplain.app.root

import com.topjohnwu.superuser.Shell

data class SuResult(
    val out: String,
    val err: String,
    val success: Boolean,
)

object Su {
    fun available(): Boolean {
        return try {
            val r = Shell.cmd("id -u").exec()
            r.isSuccess && r.out.any { it.trim() == "0" }
        } catch (_: Exception) {
            false
        }
    }

    fun exec(cmd: String): SuResult {
        val r = Shell.cmd(cmd).exec()
        return SuResult(
            out = r.out.joinToString("\n").trim(),
            err = r.err.joinToString("\n").trim(),
            success = r.isSuccess,
        )
    }

    fun run(cmd: String): String {
        val r = exec(cmd)
        return if (r.out.isNotEmpty()) r.out else r.err
    }

    fun ok(cmd: String): Boolean = Shell.cmd(cmd).exec().isSuccess
}
