package com.wxplain.app.wechat

data class WxAccount(
    val hash: String,
    val mmDir: String,
    val dbPath: String,
    val dbSize: Long,
    val dbMtime: Long = 0L,
    val walMtime: Long = 0L,
)

data class Conversation(
    val username: String,
    val nickname: String,
    val unread: Int,
    val lastTime: Long,
    val kind: Kind,
) {
    enum class Kind { CONTACT, GROUP, OFFICIAL }
}

data class ChatMessage(
    val msgId: Long,
    val msgSvrId: Long,
    val type: Int,
    val content: String,
    val createTime: Long,
    val isSend: Boolean,
    val imgPath: String,
)

object MsgTypes {
    const val TEXT = 1
    const val IMAGE = 3
    const val VOICE = 34
    const val CARD = 42
    const val VIDEO = 43
    const val EMOJI = 47
    const val LOCATION = 48
    const val APP = 49
    const val SYSTEM = 10000
    const val REVOKE = 10002

    fun label(type: Int): String = when (type) {
        TEXT -> "文本"
        IMAGE -> "图片"
        VOICE -> "语音"
        CARD -> "名片"
        VIDEO -> "视频"
        EMOJI -> "表情"
        LOCATION -> "位置"
        APP -> "链接/文件"
        SYSTEM -> "系统"
        REVOKE -> "撤回"
        else -> "类型$type"
    }
}
