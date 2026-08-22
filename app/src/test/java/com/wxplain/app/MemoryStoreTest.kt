package com.wxplain.app

import com.wxplain.app.ai.AiEnvelope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryStoreTest {
    @Test
    fun upsertAddsAndUpdatesByTalker() {
        val first = MemoryStore.upsert(emptyList(), "wxid_a", "张三", "要三室", 1L)
        assertEquals(1, first.size)
        assertEquals("要三室", MemoryStore.textOf(first, "wxid_a"))

        val updated = MemoryStore.upsert(first, "wxid_a", "张三备注", "预算 200 万", 2L)
        assertEquals(1, updated.size)
        assertEquals("张三备注", updated.single().nick)
        assertEquals("预算 200 万", updated.single().text)
        assertEquals(2L, updated.single().updatedAt)
    }

    @Test
    fun upsertBlankKeepsTalkerSoDefaultDoesNotReseed() {
        val list = MemoryStore.upsert(emptyList(), "wxid_a", "张三", "已知")
        val cleared = MemoryStore.upsert(list, "wxid_a", "张三", "  ")
        assertEquals(1, cleared.size)
        assertEquals("", MemoryStore.textOf(cleared, "wxid_a"))
        val reseeded = MemoryStore.ensureInitial(cleared, "wxid_a", "张三", "默认记忆")
        assertEquals("", MemoryStore.textOf(reseeded, "wxid_a"))
    }

    @Test
    fun upsertIgnoresBlankTalker() {
        val list = MemoryStore.upsert(emptyList(), "  ", "x", "body")
        assertTrue(list.isEmpty())
    }

    @Test
    fun memoriesAreIsolatedByTalker() {
        var list = MemoryStore.upsert(emptyList(), "wxid_a", "甲", "要三室")
        list = MemoryStore.upsert(list, "wxid_b", "乙", "群主")
        assertEquals("要三室", MemoryStore.textOf(list, "wxid_a"))
        assertEquals("群主", MemoryStore.textOf(list, "wxid_b"))
        list = MemoryStore.upsert(list, "wxid_a", "甲", "")
        assertEquals("", MemoryStore.textOf(list, "wxid_a"))
        assertEquals("群主", MemoryStore.textOf(list, "wxid_b"))
    }

    @Test
    fun mergeAppendAddsToExisting() {
        var list = MemoryStore.upsert(emptyList(), "wxid_a", "甲", "要三室", 1L)
        list = MemoryStore.mergeAppend(list, "wxid_a", "甲", "预算 200 万", 2L)
        assertEquals("要三室\n预算 200 万", MemoryStore.textOf(list, "wxid_a"))
        list = MemoryStore.mergeAppend(list, "wxid_b", "乙", "第一次聊", 3L)
        assertEquals("第一次聊", MemoryStore.textOf(list, "wxid_b"))
    }

    @Test
    fun applyMemoryOverwriteOrAppendNotBoth() {
        val base = MemoryStore.upsert(emptyList(), "wxid_a", "甲", "旧记忆", 1L)
        val overwritten = MemoryStore.applyMemory(
            base,
            "wxid_a",
            "甲",
            AiEnvelope(reply = "ok", question = "", context = "新的全部记忆", contextUpdate = "不该追加"),
            2L,
        )
        assertEquals("新的全部记忆", MemoryStore.textOf(overwritten, "wxid_a"))

        val appended = MemoryStore.applyMemory(
            base,
            "wxid_a",
            "甲",
            AiEnvelope(reply = "ok", question = "", context = "", contextUpdate = "预算 200 万"),
            3L,
        )
        assertEquals("旧记忆\n预算 200 万", MemoryStore.textOf(appended, "wxid_a"))
    }

    @Test
    fun ensureInitialCopiesDefaultOnce() {
        val seeded = MemoryStore.ensureInitial(emptyList(), "wxid_a", "甲", "称呼老板", 1L)
        assertEquals("称呼老板", MemoryStore.textOf(seeded, "wxid_a"))
        val again = MemoryStore.ensureInitial(seeded, "wxid_a", "甲", "另一份默认", 2L)
        assertEquals("称呼老板", MemoryStore.textOf(again, "wxid_a"))
        val skipped = MemoryStore.ensureInitial(emptyList(), "wxid_b", "乙", "  ", 3L)
        assertTrue(skipped.isEmpty())
    }

    @Test
    fun pruneMissingDropsDeletedTalkers() {
        var list = MemoryStore.upsert(emptyList(), "wxid_a", "甲", "记A")
        list = MemoryStore.upsert(list, "wxid_b", "乙", "记B")
        val kept = MemoryStore.pruneMissing(list, setOf("wxid_b"))
        assertEquals(listOf("wxid_b"), kept.map { it.talker })
        assertEquals("记B", MemoryStore.textOf(kept, "wxid_b"))
        assertEquals("", MemoryStore.textOf(kept, "wxid_a"))
    }
}
