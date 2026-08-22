package com.wxplain.app

import com.wxplain.app.wechat.CipherKey
import com.wxplain.app.wechat.DbStamp
import com.wxplain.app.wechat.SnapshotCopy
import com.wxplain.app.wechat.WeChatStore
import org.junit.Assert.assertEquals
import org.junit.Test

class CipherKeyTest {
    @Test
    fun hexToPasswordMatchesKnownKey() {
        assertEquals("1d6aa70", CipherKey.hexToPassword("31643661613730"))
        assertEquals("1d6aa70", CipherKey.hexToPassword("key=31643661613730"))
    }

    @Test
    fun parseAccountLines() {
        val raw = "67a678be6858e0e747d163968e154cd6|/data/data/com.tencent.mm/MicroMsg/67a678be6858e0e747d163968e154cd6|1321984"
        val a = WeChatStore.parseAccounts(raw).single()
        assertEquals("67a678be6858e0e747d163968e154cd6", a.hash)
        assertEquals(1321984L, a.dbSize)
        assertEquals("${a.mmDir}/EnMicroMsg.db", a.dbPath)
    }

    @Test
    fun parseStampReadsDbWalShm() {
        val raw = """
            db|1321984 1700000000
            wal|4096 1700000001
            shm|32768 1700000001
        """.trimIndent()
        val s = WeChatStore.parseStamp(raw, "/data/EnMicroMsg.db")!!
        assertEquals(1321984L, s.dbSize)
        assertEquals(1700000000L, s.dbMtime)
        assertEquals(4096L, s.walSize)
        assertEquals(32768L, s.shmSize)
    }

    @Test
    fun decideSnapshotCopySkipsWhenUnchanged() {
        val stamp = DbStamp("/db", 1000, 10, 200, 11, 32, 11)
        assertEquals(SnapshotCopy.SKIP, WeChatStore.decideSnapshotCopy(stamp, stamp, destOk = true, destWalMissing = false))
    }

    @Test
    fun decideSnapshotCopyWalOnlyWhenDbUnchanged() {
        val last = DbStamp("/db", 1000, 10, 200, 11, 32, 11)
        val now = last.copy(walSize = 4096, walMtime = 12)
        assertEquals(SnapshotCopy.WAL, WeChatStore.decideSnapshotCopy(last, now, destOk = true, destWalMissing = false))
    }

    @Test
    fun decideSnapshotCopyFullWhenDbChanged() {
        val last = DbStamp("/db", 1000, 10, 200, 11, 32, 11)
        val now = last.copy(dbSize = 2000, dbMtime = 20)
        assertEquals(SnapshotCopy.FULL, WeChatStore.decideSnapshotCopy(last, now, destOk = true, destWalMissing = false))
        assertEquals(SnapshotCopy.FULL, WeChatStore.decideSnapshotCopy(null, now, destOk = false, destWalMissing = true))
    }
}
