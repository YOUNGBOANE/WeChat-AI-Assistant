package com.wxplain.app

import com.wxplain.app.ai.AiEnvelope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiEnvelopeTest {
    @Test
    fun parseReplyAndOptionalMemory() {
        val e = AiEnvelope.parse(
            """
            <reply>今晚有空</reply>
            <context_update>对方说今晚方便</context_update>
            """.trimIndent(),
        )
        assertEquals("今晚有空", e.reply)
        assertEquals("", e.question)
        assertEquals("对方说今晚方便", e.contextUpdate)
        assertEquals("", e.context)
        assertTrue(e.hasReply)
        assertFalse(e.hasOptions)
        assertTrue(e.memoryUpdated)
    }

    @Test
    fun parseOptionIsAQuestionNotChoices() {
        val e = AiEnvelope.parse(
            """
            <reply>不该用</reply>
            <option>对方是要三室吗</option>
            """.trimIndent(),
        )
        assertTrue(e.hasOptions)
        assertEquals("对方是要三室吗", e.question)
        assertEquals("对方是要三室吗？", AiEnvelope.asQuestion(e.question))
        assertTrue(e.hasReply)
    }

    @Test
    fun parseKeepsExistingQuestionMark() {
        assertEquals("今晚方便吗？", AiEnvelope.asQuestion("今晚方便吗？"))
        assertEquals("今晚方便吗?", AiEnvelope.asQuestion("今晚方便吗?"))
    }

    @Test
    fun formatChoiceIncludesQuestion() {
        assertEquals(
            "人工对「对方是要三室吗？」的答复：是",
            AiEnvelope.formatChoice("对方是要三室吗", "是"),
        )
        assertEquals(
            "人工对「这些东西的总价是？」的答复：12800",
            AiEnvelope.formatChoice("这些东西的总价是", "12800"),
        )
    }

    @Test
    fun parseContextOverwritesLast() {
        val e = AiEnvelope.parse(
            """
            <reply>好的</reply>
            <context>旧</context>
            <context>客户要三室，预算 200 万</context>
            """.trimIndent(),
        )
        assertEquals("客户要三室，预算 200 万", e.context)
        assertEquals("好的", e.reply)
    }

    @Test
    fun parseMissingAction() {
        val e = AiEnvelope.parse("<context_update>只记一笔</context_update>")
        assertFalse(e.hasReply)
        assertFalse(e.hasOptions)
        assertTrue(e.memoryUpdated)
    }

    @Test
    fun contextTagDoesNotEatContextUpdate() {
        val e = AiEnvelope.parse(
            """
            <reply>嗯</reply>
            <context_update>追加一句</context_update>
            """.trimIndent(),
        )
        assertEquals("", e.context)
        assertEquals("追加一句", e.contextUpdate)
    }

    @Test
    fun bothMemoryTagsKeepOnlyOverwrite() {
        val e = AiEnvelope.parse(
            """
            <reply>好的</reply>
            <context>客户要三室</context>
            <context_update>预算 200 万</context_update>
            """.trimIndent(),
        )
        assertEquals("客户要三室", e.context)
        assertEquals("", e.contextUpdate)
    }
}
