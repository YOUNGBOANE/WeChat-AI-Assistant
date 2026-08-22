package com.wxplain.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingConfirmStoreTest {
    @Test
    fun upsertReplacesSameTalkerAndKeepsOthers() {
        var list = PendingConfirmStore.upsert(emptyList(), "wxid_a", "时间是哪天", "", 1L)
        list = PendingConfirmStore.upsert(list, "wxid_b", "地点在哪", "公司", 2L)
        list = PendingConfirmStore.upsert(list, "wxid_a", "时间是哪天", "周五", 3L)
        assertEquals(2, list.size)
        assertEquals("周五", PendingConfirmStore.getOf(list, "wxid_a")?.draft)
        assertEquals("公司", PendingConfirmStore.getOf(list, "wxid_b")?.draft)
    }

    @Test
    fun removeOnlyThatTalker() {
        var list = PendingConfirmStore.upsert(emptyList(), "wxid_a", "问A", "a", 1L)
        list = PendingConfirmStore.upsert(list, "wxid_b", "问B", "b", 2L)
        list = PendingConfirmStore.remove(list, "wxid_a")
        assertNull(PendingConfirmStore.getOf(list, "wxid_a"))
        assertEquals("问B", PendingConfirmStore.getOf(list, "wxid_b")?.question)
    }

    @Test
    fun upsertIgnoresBlank() {
        assertTrue(PendingConfirmStore.upsert(emptyList(), "", "q", "d").isEmpty())
        assertTrue(PendingConfirmStore.upsert(emptyList(), "wxid_a", "  ", "d").isEmpty())
    }

    @Test
    fun pruneMissingDropsDeletedTalkers() {
        var list = PendingConfirmStore.upsert(emptyList(), "wxid_a", "问A", "a", 1L)
        list = PendingConfirmStore.upsert(list, "wxid_b", "问B", "b", 2L)
        val kept = PendingConfirmStore.pruneMissing(list, setOf("wxid_b"))
        assertEquals(1, kept.size)
        assertEquals("wxid_b", kept.single().talker)
    }
}
