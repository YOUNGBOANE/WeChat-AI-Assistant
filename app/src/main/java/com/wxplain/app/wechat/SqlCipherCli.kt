package com.wxplain.app.wechat

import android.content.Context
import com.wxplain.app.root.Su
import java.io.File

object SqlCipherCli {
    private const val TMP = "/data/local/tmp/wxplain_bin"
    private val names = listOf("sqlcipher", "libz.so.1", "libcrypto.so.3", "libedit.so", "libncursesw.so.6")

    fun deploy(context: Context) {
        val dir = File(context.filesDir, "bin")
        dir.mkdirs()
        for (name in names) {
            val out = File(dir, name)
            if (out.exists() && out.length() > 1000) continue
            context.assets.open("bin/$name").use { input ->
                out.outputStream().use { input.copyTo(it) }
            }
        }
        val src = dir.absolutePath
        Su.run("mkdir -p $TMP && cp -f $src/* $TMP/ && chmod 755 $TMP/*")
    }

    fun query(dbPath: String, password: String, sql: String): String {
        val script = File.createTempFile("wxplain_", ".sql")
        try {
            script.writeText(buildString {
                appendLine(".headers off")
                appendLine(".mode list")
                appendLine(".separator |")
                appendLine(".output /dev/null")
                appendLine("PRAGMA key='${password.replace("'", "''")}';")
                appendLine("PRAGMA cipher_compatibility=3;")
                appendLine("PRAGMA cipher_page_size=1024;")
                appendLine("PRAGMA kdf_iter=4000;")
                appendLine("PRAGMA cipher_use_hmac=OFF;")
                appendLine(".output stdout")
                appendLine(sql.trim())
            })
            val cmd =
                "LD_PRELOAD='$TMP/libz.so.1:$TMP/libcrypto.so.3:$TMP/libedit.so:$TMP/libncursesw.so.6' " +
                    "$TMP/sqlcipher '$dbPath' < '${script.absolutePath}'"
            val r = Su.exec(cmd)
            if (r.out.isNotBlank()) return r.out
            if (CipherKey.isDecryptError(r.err)) return r.err
            return r.out
        } finally {
            script.delete()
        }
    }
}
