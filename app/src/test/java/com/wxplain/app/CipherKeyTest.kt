package com.wxplain.app

import com.wxplain.app.wechat.CapturedKey
import com.wxplain.app.wechat.CipherKey
import com.wxplain.app.wechat.DbStamp
import com.wxplain.app.wechat.SnapshotCopy
import com.wxplain.app.wechat.WeChatStore
import com.wxplain.app.wechat.WxAccount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        assertEquals(0L, a.dbMtime)
        assertEquals(0L, a.walMtime)
    }

    @Test
    fun parseAccountLinesWithMtime() {
        val raw = "67a678be6858e0e747d163968e154cd6|/data/mm/67a678be6858e0e747d163968e154cd6|100|10|20"
        val a = WeChatStore.parseAccounts(raw).single()
        assertEquals(100L, a.dbSize)
        assertEquals(10L, a.dbMtime)
        assertEquals(20L, a.walMtime)
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

    @Test
    fun decideSnapshotCopyFullWhenAccountPathChanges() {
        val last = DbStamp("/old/EnMicroMsg.db", 2000, 10, 200, 11, 32, 11)
        val now = DbStamp("/new/EnMicroMsg.db", 500, 30, 100, 31, 32, 31)
        assertEquals(SnapshotCopy.FULL, WeChatStore.decideSnapshotCopy(last, now, destOk = true, destWalMissing = false))
    }

    @Test
    fun pickAccountPrefersRecentWalOverLargerDb() {
        val oldLarge = WxAccount("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "/a", "/a/EnMicroMsg.db", 9_000_000, dbMtime = 10, walMtime = 11)
        val newSmall = WxAccount("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "/b", "/b/EnMicroMsg.db", 100_000, dbMtime = 20, walMtime = 50)
        val picked = WeChatStore.pickAccount(listOf(oldLarge, newSmall))
        assertEquals(newSmall.hash, picked!!.hash)
    }

    @Test
    fun pickAccountPrefersUinHash() {
        val oldLarge = WxAccount("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "/a", "/a/EnMicroMsg.db", 9_000_000, dbMtime = 10, walMtime = 11)
        val current = WxAccount("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "/b", "/b/EnMicroMsg.db", 100_000, dbMtime = 1, walMtime = 1)
        val picked = WeChatStore.pickAccount(listOf(oldLarge, current), uinHash = current.hash)
        assertEquals(current.hash, picked!!.hash)
    }

    @Test
    fun pickAccountFallsBackToSizeWhenMtimeMissing() {
        val small = WxAccount("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "/a", "/a/EnMicroMsg.db", 100)
        val large = WxAccount("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "/b", "/b/EnMicroMsg.db", 999)
        assertEquals(large.hash, WeChatStore.pickAccount(listOf(small, large))!!.hash)
    }

    @Test
    fun hashFromDbPathReadsAccountFolder() {
        val path = "/data/data/com.tencent.mm/MicroMsg/67a678be6858e0e747d163968e154cd6/EnMicroMsg.db"
        assertEquals("67a678be6858e0e747d163968e154cd6", CipherKey.hashFromDbPath(path))
        assertEquals("", CipherKey.hashFromDbPath("/data/data/com.tencent.mm/EnMicroMsg.db"))
    }

    @Test
    fun parseCapturedKeyReadsHexPathHash() {
        val raw = """
            key=31643661613730
            path=/data/data/com.tencent.mm/MicroMsg/67a678be6858e0e747d163968e154cd6/EnMicroMsg.db
            time=1700000000
        """.trimIndent()
        val k = CipherKey.parseCapturedKey(raw)!!
        assertEquals("31643661613730", k.hex)
        assertEquals("67a678be6858e0e747d163968e154cd6", k.hash)
        assertEquals(1700000000L, k.time)
    }

    @Test
    fun mergeKeyHexesPutsLiveFirst() {
        val live = CapturedKey("aa11")
        val merged = CipherKey.mergeKeyHexes(live, "bb22", listOf("cc33", "aa11"))
        assertEquals(listOf("aa11", "bb22", "cc33"), merged)
    }

    @Test
    fun parseKeyHistoryNewestFirstUnique() {
        val raw = "aa11\nbb22\naa11\ncc33\n"
        assertEquals(listOf("cc33", "aa11", "bb22"), CipherKey.parseKeyHistory(raw))
    }

    @Test
    fun parseUinFromPrefsSkipsZero() {
        val raw = """
            <?xml version='1.0' encoding='utf-8' standalone='yes' ?>
            <map>
            <int name="default_uin" value="0" />
            <int name="_auth_uin" value="-1881034049" />
            </map>
        """.trimIndent()
        assertEquals("-1881034049", CipherKey.parseUinFromPrefs(raw))
        assertEquals("202cb962ac59075b964b07152d234b70", CipherKey.uinHash("123"))
        assertNull(CipherKey.parseUinFromPrefs("<int name=\"default_uin\" value=\"0\" />"))
    }

    @Test
    fun isDecryptErrorDetectsWrongKey() {
        assertTrue(CipherKey.isDecryptError("Error: file is not a database"))
        assertFalse(CipherKey.isDecryptError(""))
        assertFalse(CipherKey.isDecryptError("wxid_abc|nick|0|1"))
    }
}
