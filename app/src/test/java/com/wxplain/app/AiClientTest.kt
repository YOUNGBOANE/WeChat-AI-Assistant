package com.wxplain.app

import com.wxplain.app.ai.AiClient
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiClientTest {
    @Test
    fun buildUserContentOmitsMemoryWhenBlank() {
        val text = AiClient.buildUserContent("我: 你好\n对方: 在吗")
        assertTrue(text.contains("最近对话："))
        assertFalse(text.contains("已知信息"))
        assertFalse(text.contains("相关资料"))
        assertTrue(text.contains("按指定标签格式输出"))
        assertFalse(text.contains("人工已填写"))
    }

    @Test
    fun buildUserContentIncludesMemoryAndExtra() {
        val text = AiClient.buildUserContent(
            chatLog = "对方: 这周末方便吗",
            extra = "【时间】\n本周五下午",
            memory = "对方希望用口语回复",
        )
        assertTrue(text.contains("关于这个会话的已知信息：\n对方希望用口语回复"))
        assertTrue(text.contains("最近对话：\n对方: 这周末方便吗"))
        assertTrue(text.contains("相关资料：\n【时间】\n本周五下午"))
        assertTrue(text.contains("按指定标签格式输出"))
    }

    @Test
    fun buildUserContentIncludesChoice() {
        val text = AiClient.buildUserContent(
            chatLog = "对方: 这周末方便吗",
            choice = "人工对「对方说的那个时间是哪天？」的答复：周五",
        )
        assertTrue(text.contains("人工已填写：\n人工对「对方说的那个时间是哪天？」的答复：周五"))
    }

    @Test
    fun buildSystemPromptAlwaysHasFormatSpec() {
        val sys = AiClient.buildSystemPrompt("你是助手")
        assertTrue(sys.startsWith("你是助手"))
        assertTrue(sys.contains("<reply>"))
        assertTrue(sys.contains("<option>"))
        assertTrue(AiClient.buildSystemPrompt("").contains("<context_update>"))
        val spec = AiClient.buildSystemPrompt("")
        assertTrue(spec.contains("向人工确认"))
        assertTrue(spec.contains("问句"))
        assertTrue(spec.contains("禁止同时出现"))
        assertFalse(spec.contains("前端会提供「是 / 否"))
    }
}
