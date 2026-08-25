package com.wxplain.app.ai

data class AiEnvelope(
    val reply: String,
    val question: String,
    val context: String,
    val contextUpdate: String,
) {
    val hasOptions: Boolean get() = question.isNotBlank()
    val hasReply: Boolean get() = reply.isNotBlank()
    val memoryUpdated: Boolean get() = context.isNotBlank() || contextUpdate.isNotBlank()

    companion object {
        const val INIT_FORMAT_SPEC = """输出必须只使用下面的标签，不要输出标签以外的解释，不要输出 <reply> 或 <option>。

<context>该会话的全部已知信息</context>

规则：
1. 只输出一个 <context>。根据最近对话整理该会话背景：身份、称呼、关系、约定、偏好、正在进行的事项。
2. 只写能确定的事实，不要编造，不要记无意义闲聊。
3. 不要输出准备发送的回复。"""

        const val FORMAT_SPEC = """输出必须使用下面的标签，不要输出标签以外的解释。

<reply>填进微信输入框、准备发出去的正文</reply>
<option>一句需要向人工确认或填写的问题</option>
<context_update>追加到该会话已知信息</context_update>
<context>覆盖该会话的全部已知信息</context>

规则：
1. 每一条输出必须有 <reply> 或 <option> 二者之一，不能都没有，也不要同时使用。
2. 能确定回复时只用 <reply>。遇到需要操作者本人确认或补充的信息时不要用 <reply>，改为一个 <option>，里面只写问句。例如：对方说的那个时间是哪天？这件事是否已经确认？前端会把问题交给操作者填写。不要自己列选项，也不要编造需要本人才能确定的事实。
3. 最近对话里如果出现已知信息尚未掌握的内容，必须及时用 <context> 或 <context_update> 更新记忆，不要等下一轮。只补一条新事实时用 <context_update>（追加到末尾）；要整份重写该会话已知信息时用 <context>（覆盖全部旧内容）。二者最多只用其中一个，禁止同时出现。没有新事实时两个都不要输出。
4. <reply> 里只放准备发送的话。
5. 已知信息是该会话背景；最近对话只用于接当前这一轮，不要根据过早的闲聊展开。"""

        private val REPLY = Regex("(?is)<reply\\s*>(.*?)</reply\\s*>")
        private val OPTION = Regex("(?is)<option\\s*>(.*?)</option\\s*>")
        private val CONTEXT_UPDATE = Regex("(?is)<context_update\\s*>(.*?)</context_update\\s*>")
        private val CONTEXT = Regex("(?is)<context\\s*>(.*?)</context\\s*>")

        fun parse(raw: String): AiEnvelope {
            val replies = REPLY.findAll(raw).map { it.groupValues[1].trim() }.filter { it.isNotEmpty() }.toList()
            val optionBodies = OPTION.findAll(raw).map { it.groupValues[1].trim() }.filter { it.isNotEmpty() }.toList()
            val updates = CONTEXT_UPDATE.findAll(raw).map { it.groupValues[1].trim() }.filter { it.isNotEmpty() }.toList()
            val contexts = CONTEXT.findAll(raw).map { it.groupValues[1].trim() }.filter { it.isNotEmpty() }.toList()
            val overwrite = contexts.lastOrNull().orEmpty()
            return AiEnvelope(
                reply = replies.joinToString("\n"),
                question = optionBodies.joinToString("\n"),
                context = overwrite,
                contextUpdate = if (overwrite.isNotEmpty()) "" else updates.joinToString("\n"),
            )
        }

        fun asQuestion(raw: String): String {
            val t = raw.trim()
            if (t.isEmpty()) return ""
            return if (t.endsWith("?") || t.endsWith("？")) t else "$t？"
        }

        fun formatChoice(question: String, answer: String): String =
            "人工对「${asQuestion(question)}」的答复：$answer"
    }
}
