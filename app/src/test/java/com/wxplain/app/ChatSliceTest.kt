package com.wxplain.app

import com.wxplain.app.ai.AiClient
import com.wxplain.app.ai.AiEnvelope
import com.wxplain.app.wechat.ChatSlice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatSliceTest {
    @Test
    fun needsMemoryInitOnlyWhenNoMemoryAndMoreThanTen() {
        assertFalse(ChatSlice.needsMemoryInit(hasMemory = true, lineCount = 100))
        assertFalse(ChatSlice.needsMemoryInit(hasMemory = false, lineCount = 10))
        assertFalse(ChatSlice.needsMemoryInit(hasMemory = false, lineCount = 3))
        assertTrue(ChatSlice.needsMemoryInit(hasMemory = false, lineCount = 11))
    }

    @Test
    fun compactReplyKeepsAtMostTenRecentAnd800Chars() {
        val lines = (1..15).map { "对方: 第${it}条" + "字".repeat(40) }
        val out = ChatSlice.compactReply(lines)
        val kept = out.lines()
        assertTrue(kept.size <= ChatSlice.LINES_REPLY)
        assertTrue(out.length <= ChatSlice.CHARS_REPLY)
        assertTrue(kept.last().contains("第15条"))
        assertFalse(kept.any { it.contains("第1条") })
    }

    @Test
    fun compactInitKeepsAtMost100RecentAnd4000Chars() {
        val lines = (1..120).map { "对方: 记录$it " + "字".repeat(60) }
        val out = ChatSlice.compactInit(lines)
        val kept = out.lines()
        assertTrue(kept.size <= ChatSlice.LINES_INIT)
        assertTrue(out.length <= ChatSlice.CHARS_INIT)
        assertTrue(kept.last().contains("记录120"))
        assertFalse(kept.any { it.contains("记录1 ") })
    }

    @Test
    fun initPromptAsksOnlyForContext() {
        val sys = AiClient.buildSystemPrompt("简洁", initMemory = true)
        val user = AiClient.buildUserContent("对方: 你好", extra = "资料", memory = "旧记忆", initMemory = true)
        assertTrue(sys.contains(AiEnvelope.INIT_FORMAT_SPEC))
        assertFalse(sys.contains("<reply>填进微信输入框"))
        assertTrue(user.contains("请只输出 <context>"))
        assertFalse(user.contains("关于这个会话的已知信息"))
        assertFalse(user.contains("按指定标签格式输出"))
    }

    @Test
    fun replyPromptKeepsRecentSliceAndMemory() {
        val user = AiClient.buildUserContent("对方: 你好", extra = "", memory = "他是同事", initMemory = false)
        assertTrue(user.contains("关于这个会话的已知信息"))
        assertTrue(user.contains("最近对话只用于接当前这一轮"))
        assertTrue(user.contains("若出现已知信息未掌握的事实"))
        assertFalse(user.contains("请只输出 <context>"))
    }
}
