package com.wxplain.app

import com.wxplain.app.wechat.ChatMessage
import com.wxplain.app.wechat.ChatSlice
import com.wxplain.app.wechat.MsgTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatSliceTest {
    @Test
    fun compactKeepsNewestAndDropsOld() {
        val lines = (1..20).map { "对方: 第${it}句很长很长很长很长" }
        val out = ChatSlice.compact(lines, hasMemory = true)
        assertFalse(out.contains("第1句"))
        assertTrue(out.contains("第20句"))
        assertTrue(out.lines().size <= ChatSlice.LINES_WITH_MEMORY)
        assertTrue(out.length <= ChatSlice.CHARS_WITH_MEMORY + 20)
    }

    @Test
    fun compactStripsXmlPayload() {
        val xml = "对方: <msg><appmsg><title>报价单</title><url>http://x</url></appmsg></msg>"
        val out = ChatSlice.compact(listOf(xml, "我: 好的"), hasMemory = false)
        assertFalse(out.contains("<appmsg>"))
        assertTrue(out.contains("报价单"))
        assertTrue(out.contains("我: 好的"))
    }

    @Test
    fun nonTextBecomesLabel() {
        val img = ChatMessage(1, 1, MsgTypes.IMAGE, "", 0, false, "")
        assertEquals("对方: [图片]", ChatSlice.line(img))
        val sys = ChatMessage(2, 2, MsgTypes.SYSTEM, "你已添加了对方", 0, false, "")
        assertEquals(null, ChatSlice.line(sys))
    }

    @Test
    fun withMemoryUsesTighterBudget() {
        val lines = (1..12).map { "对方: ${"哈".repeat(40)}$it" }
        val with = ChatSlice.compact(lines, hasMemory = true)
        val without = ChatSlice.compact(lines, hasMemory = false)
        assertTrue(with.length <= without.length)
        assertTrue(with.lines().size <= without.lines().size)
    }
}
