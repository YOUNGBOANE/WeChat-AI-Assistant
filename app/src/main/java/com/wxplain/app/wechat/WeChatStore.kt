package com.wxplain.app.wechat

import android.content.Context
import android.os.Process
import com.wxplain.app.ingest.KeyStore
import com.wxplain.app.root.Su
import java.io.File

data class DbStamp(
    val srcPath: String,
    val dbSize: Long,
    val dbMtime: Long,
    val walSize: Long,
    val walMtime: Long,
    val shmSize: Long,
    val shmMtime: Long,
)

enum class SnapshotCopy { SKIP, WAL, FULL }

object WeChatStore {
    private const val PKG = "com.tencent.mm"

    fun wechatPid(): String? {
        val raw = Su.run("pidof $PKG")
        return raw.split(Regex("\\s+")).firstOrNull { it.all(Char::isDigit) }
    }

    fun findAccounts(): List<WxAccount> {
        val pid = wechatPid()
        val script = buildString {
            appendLine("scan() {")
            appendLine("  base=\"\$1\"; [ -d \"\$base\" ] || return 1")
            appendLine("  found=0")
            appendLine("  for d in \"\$base\"/*; do")
            appendLine("    db=\"\$d/EnMicroMsg.db\"")
            appendLine("    if [ -f \"\$db\" ]; then")
            appendLine("      echo \"\$(basename \"\$d\")|\$d|\$(stat -c%s \"\$db\" 2>/dev/null || echo 0)\"")
            appendLine("      found=1")
            appendLine("    fi")
            appendLine("  done")
            appendLine("  [ \"\$found\" = 1 ]")
            appendLine("}")
            if (!pid.isNullOrBlank()) {
                appendLine("scan /proc/$pid/root/data/data/$PKG/MicroMsg && exit 0")
            }
            appendLine("scan /data_mirror/data_ce/null/0/$PKG/MicroMsg && exit 0")
            appendLine("scan /data/data/$PKG/MicroMsg")
        }
        return parseAccounts(Su.run(script))
    }

    fun parseAccounts(output: String): List<WxAccount> {
        return output.lines().mapNotNull { line ->
            val p = line.trim().split("|")
            if (p.size < 3 || p[0].length < 8) return@mapNotNull null
            WxAccount(p[0], p[1], "${p[1]}/EnMicroMsg.db", p[2].toLongOrNull() ?: 0L)
        }
    }

    fun primaryAccount(): WxAccount? =
        findAccounts().maxWithOrNull(compareBy<WxAccount> { it.dbSize }.thenBy { it.hash })

    fun snapshotFile(context: Context): File = File(context.cacheDir, "live/EnMicroMsg.db")

    fun parseStamp(output: String, srcPath: String): DbStamp? {
        var dbSize = -1L
        var dbMtime = 0L
        var walSize = 0L
        var walMtime = 0L
        var shmSize = 0L
        var shmMtime = 0L
        for (line in output.lines()) {
            val p = line.trim().split("|")
            if (p.size != 2) continue
            val nums = p[1].trim().split(Regex("\\s+"))
            if (nums.size < 2) continue
            val size = nums[0].toLongOrNull() ?: continue
            val mtime = nums[1].toLongOrNull() ?: continue
            when (p[0]) {
                "db" -> {
                    dbSize = size
                    dbMtime = mtime
                }
                "wal" -> {
                    walSize = size
                    walMtime = mtime
                }
                "shm" -> {
                    shmSize = size
                    shmMtime = mtime
                }
            }
        }
        if (dbSize <= 0) return null
        return DbStamp(srcPath, dbSize, dbMtime, walSize, walMtime, shmSize, shmMtime)
    }

    fun decideSnapshotCopy(
        last: DbStamp?,
        now: DbStamp,
        destOk: Boolean,
        destWalMissing: Boolean,
    ): SnapshotCopy {
        if (!destOk || last == null) return SnapshotCopy.FULL
        if (last.srcPath != now.srcPath) return SnapshotCopy.FULL
        if (last.dbSize != now.dbSize || last.dbMtime != now.dbMtime) return SnapshotCopy.FULL
        if (destWalMissing && now.walSize > 0) return SnapshotCopy.WAL
        val walSame = last.walSize == now.walSize && last.walMtime == now.walMtime
        val shmSame = last.shmSize == now.shmSize && last.shmMtime == now.shmMtime
        return if (walSame && shmSame) SnapshotCopy.SKIP else SnapshotCopy.WAL
    }

    fun refreshSnapshot(context: Context): Result<File> {
        val acct = primaryAccount() ?: return Result.failure(IllegalStateException("未找到微信数据库，请先打开并登录微信"))
        val destDir = File(context.cacheDir, "live")
        val dest = File(destDir, "EnMicroMsg.db")
        val destWal = File(destDir, "EnMicroMsg.db-wal")
        val destShm = File(destDir, "EnMicroMsg.db-shm")
        val stampFile = File(destDir, "stamp")
        val now = readSourceStamp(acct.dbPath) ?: return Result.failure(IllegalStateException("无法读取微信数据库状态"))
        val last = loadStamp(stampFile)
        val destOk = dest.exists() && dest.length() >= 1024
        val mode = decideSnapshotCopy(last, now, destOk, destWalMissing = now.walSize > 0 && !destWal.exists())
        val uid = Process.myUid()
        when (mode) {
            SnapshotCopy.SKIP -> return Result.success(dest)
            SnapshotCopy.WAL -> {
                if (copySidecars(acct.dbPath, dest, destWal, destShm, destDir, uid)) {
                    saveStamp(stampFile, now)
                    return Result.success(dest)
                }
            }
            SnapshotCopy.FULL -> {}
        }
        if (!copyFull(acct.dbPath, dest, destWal, destShm, destDir, uid)) {
            return Result.failure(IllegalStateException("复制数据库失败"))
        }
        saveStamp(stampFile, now)
        return Result.success(dest)
    }

    private fun readSourceStamp(dbPath: String): DbStamp? {
        val cmd = buildString {
            appendLine("s() { if [ -f \"\$1\" ]; then stat -c '%s %Y' \"\$1\"; else echo '0 0'; fi; }")
            appendLine("echo \"db|\$(s '$dbPath')\"")
            appendLine("echo \"wal|\$(s '$dbPath-wal')\"")
            appendLine("echo \"shm|\$(s '$dbPath-shm')\"")
        }
        return parseStamp(Su.run(cmd), dbPath)
    }

    private fun loadStamp(file: File): DbStamp? {
        if (!file.exists()) return null
        return try {
            val lines = file.readLines()
            if (lines.isEmpty()) return null
            parseStamp(lines.drop(1).joinToString("\n"), lines[0].trim())
        } catch (_: Exception) {
            null
        }
    }

    private fun saveStamp(file: File, stamp: DbStamp) {
        file.writeText(
            buildString {
                appendLine(stamp.srcPath)
                appendLine("db|${stamp.dbSize} ${stamp.dbMtime}")
                appendLine("wal|${stamp.walSize} ${stamp.walMtime}")
                appendLine("shm|${stamp.shmSize} ${stamp.shmMtime}")
            },
        )
    }

    private fun copySidecars(
        srcDb: String,
        dest: File,
        destWal: File,
        destShm: File,
        destDir: File,
        uid: Int,
    ): Boolean {
        val cmd = """
            mkdir -p '${destDir.absolutePath}'
            cp -f '$srcDb-wal' '${destWal.absolutePath}' 2>/dev/null
            cp -f '$srcDb-shm' '${destShm.absolutePath}' 2>/dev/null
            chown $uid:$uid '${destDir.absolutePath}' '${dest.absolutePath}' '${destWal.absolutePath}' '${destShm.absolutePath}' 2>/dev/null
            chmod 700 '${destDir.absolutePath}'
            chmod 600 '${dest.absolutePath}' '${destWal.absolutePath}' '${destShm.absolutePath}' 2>/dev/null
            test -s '${dest.absolutePath}' && echo OK
        """.trimIndent()
        return Su.run(cmd).contains("OK") && dest.length() >= 1024
    }

    private fun copyFull(
        srcDb: String,
        dest: File,
        destWal: File,
        destShm: File,
        destDir: File,
        uid: Int,
    ): Boolean {
        val cmd = """
            mkdir -p '${destDir.absolutePath}'
            dd if='$srcDb' of='${dest.absolutePath}' bs=1M 2>/dev/null
            cp -f '$srcDb-wal' '${destWal.absolutePath}' 2>/dev/null
            cp -f '$srcDb-shm' '${destShm.absolutePath}' 2>/dev/null
            chown $uid:$uid '${destDir.absolutePath}' '${dest.absolutePath}' '${destWal.absolutePath}' '${destShm.absolutePath}' 2>/dev/null
            chmod 700 '${destDir.absolutePath}'
            chmod 600 '${dest.absolutePath}' '${destWal.absolutePath}' '${destShm.absolutePath}' 2>/dev/null
            test -s '${dest.absolutePath}' && echo OK
        """.trimIndent()
        return Su.run(cmd).contains("OK") && dest.length() >= 1024
    }

    fun readKey(context: Context): String {
        KeyStore.password(context)?.let { if (it.isNotBlank()) return it }
        val hex = readKeyHex(context) ?: return ""
        val pwd = CipherKey.hexToPassword(hex)
        if (pwd.isNotBlank()) KeyStore.save(context, hex, pwd)
        return pwd
    }

    fun readKeyHex(context: Context): String? {
        KeyStore.hex(context)?.let { if (it.isNotBlank()) return it }
        val pid = wechatPid()
        val filesDir = if (!pid.isNullOrBlank()) {
            "/proc/$pid/root/data/data/$PKG/files"
        } else {
            "/data/data/$PKG/files"
        }
        val raw = Su.run("cat $filesDir/.wxplain_key $filesDir/.wechat_key 2>/dev/null")
        return raw.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("key=") }
            ?.removePrefix("key=")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }
}
