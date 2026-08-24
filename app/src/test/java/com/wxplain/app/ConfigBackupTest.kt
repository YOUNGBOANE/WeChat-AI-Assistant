package com.wxplain.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigBackupTest {
    @Test
    fun summaryCountsSections() {
        val text = ConfigBackup.summary(
            AssistantConfig(
                prompt = "abc",
                defaultMemory = "  ",
                keywords = listOf(
                    KeywordRule("1", "a", "b"),
                    KeywordRule("2", "c", "d"),
                ),
            ),
        )
        assertTrue(text.contains("提示词：3 字"))
        assertTrue(text.contains("默认记忆：空白"))
        assertTrue(text.contains("关键词资料：2 条"))
    }

    @Test
    fun summaryOmitsMissingSections() {
        val text = ConfigBackup.summary(
            AssistantConfig(
                prompt = "hi",
                hasPrompt = true,
                hasDefaultMemory = false,
                hasKeywords = false,
            ),
        )
        assertEquals("提示词：2 字", text)
    }
}
