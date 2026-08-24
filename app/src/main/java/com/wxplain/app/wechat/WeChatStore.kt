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
            appendLine("      sz=\$(stat -c%s \"\$db\" 2>/dev/null || echo 0)")
            appendLine("      mt=\$(stat -c%Y \"\$db\" 2>/dev/null || echo 0)")
            appendLine("      wt=\$(stat -c%Y \"\$db-wal\" 2>/dev/null || echo 0)")
            appendLine("      echo \"\$(basename \"\$d\")|\$d|\$sz|\$mt|\$wt\"")
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
            WxAccount(
                hash = p[0],
                mmDir = p[1],
                dbPath = "${p[1]}/EnMicroMsg.db",
                dbSize = p[2].toLongOrNull() ?: 0L,
                dbMtime = p.getOrNull(3)?.toLongOrNull() ?: 0L,
                walMtime = p.getOrNull(4)?.toLongOrNull() ?: 0L,
            )
        }
    }

    fun pickAccount(accounts: List<WxAccount>, uinHash: String? = null): WxAccount? {
        if (accounts.isEmpty()) return null
        val want = uinHash?.trim()?.lowercase().orEmpty()
        if (want.isNotEmpty()) {
            accounts.firstOrNull { it.hash.equals(want, ignoreCase = true) }?.let { return it }
        }
        return accounts.maxWithOrNull(
            compareBy<WxAccount> { maxOf(it.walMtime, it.dbMtime) }
                .thenBy { it.dbSize }
                .thenBy { it.hash },
        )
    }

    fun primaryAccount(): WxAccount? = pickAccount(findAccounts(), currentUinHash())

    fun currentUinHash(): String? {
        val uin = parseUinFromPrefs(readUinPrefs()) ?: return null
        return CipherKey.uinHash(uin)
    }

    fun parseUinFromPrefs(raw: String): String? = CipherKey.parseUinFromPrefs(raw)

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
        val hex = readKeyHex(context) ?: return ""
        val pwd = CipherKey.hexToPassword(hex)
        if (pwd.isBlank()) return ""
        val live = readLiveCapturedKey()
        val hash = live?.hash.orEmpty().ifBlank { KeyStore.accountHash(context).orEmpty() }
        if (KeyStore.password(context) != pwd || KeyStore.hex(context) != hex) {
            KeyStore.save(context, hex, pwd, hash)
        }
        return pwd
    }

    fun readKeyHex(context: Context): String? {
        val live = readLiveCapturedKey()
        if (live != null) return live.hex
        return KeyStore.hex(context)?.takeIf { it.isNotBlank() }
    }

    fun candidatePasswords(context: Context): List<String> {
        val hexes = CipherKey.mergeKeyHexes(
            readLiveCapturedKey(),
            KeyStore.hex(context),
            readLiveKeyHistory(),
        )
        return hexes.map { CipherKey.hexToPassword(it) }.filter { it.isNotBlank() }.distinct()
    }

    fun rememberWorkingPassword(context: Context, password: String) {
        if (password.isBlank()) return
        if (KeyStore.password(context) == password) return
        val hex = CipherKey.mergeKeyHexes(
            readLiveCapturedKey(),
            KeyStore.hex(context),
            readLiveKeyHistory(),
        ).firstOrNull { CipherKey.hexToPassword(it) == password } ?: return
        val hash = readLiveCapturedKey()?.hash.orEmpty().ifBlank { KeyStore.accountHash(context).orEmpty() }
        KeyStore.save(context, hex, password, hash)
    }

    fun isDecryptError(out: String): Boolean = CipherKey.isDecryptError(out)

    fun readLiveCapturedKey(): CapturedKey? =
        CipherKey.parseCapturedKey(catWeChatFiles(".wxplain_key"))

    fun readLiveKeyHistory(): List<String> {
        val hist = CipherKey.parseKeyHistory(catWeChatFiles(".wxplain_keys")).toMutableList()
        CipherKey.parseCapturedKey(catWeChatFiles(".wechat_key"))?.hex?.let { hist += it }
        return hist
    }

    private fun wechatFilesDir(): String {
        val pid = wechatPid()
        return if (!pid.isNullOrBlank()) {
            "/proc/$pid/root/data/data/$PKG/files"
        } else {
            "/data/data/$PKG/files"
        }
    }

    private fun catWeChatFiles(name: String): String {
        val dir = wechatFilesDir()
        return Su.run("cat '$dir/$name' 2>/dev/null")
    }

    private fun readUinPrefs(): String {
        val pid = wechatPid()
        val bases = buildList {
            if (!pid.isNullOrBlank()) add("/proc/$pid/root/data/data/$PKG")
            add("/data_mirror/data_ce/null/0/$PKG")
            add("/data/data/$PKG")
        }
        val files = listOf(
            "shared_prefs/system_config_prefs.xml",
            "shared_prefs/auth_info_key_prefs.xml",
            "shared_prefs/com.tencent.mm_preferences.xml",
        )
        val cmd = buildString {
            append("for b in")
            for (b in bases) append(" '$b'")
            appendLine("; do")
            appendLine("  for f in ${files.joinToString(" ") { "'$it'" }}; do")
            appendLine("    p=\"\$b/\$f\"")
            appendLine("    if [ -f \"\$p\" ]; then cat \"\$p\"; echo; fi")
            appendLine("  done")
            appendLine("done")
        }
        return Su.run(cmd)
    }
}
