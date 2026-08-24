package com.wxplain.app

import android.content.Context
import com.wxplain.app.root.Su
import com.wxplain.app.wechat.WeChatStore

data class EnvStatus(
    val root: Boolean,
    val wechatRunning: Boolean,
    val wechatPid: String?,
    val moduleInLog: Boolean,
    val key: String,
    val dbHash: String,
    val dbSize: Long,
) {
    val ok: Boolean
        get() = root && wechatRunning && moduleInLog && key.isNotBlank() && dbSize > 0

    fun failures(): List<String> = buildList {
        if (!root) add("没有超级用户权限，请在授权管理里打开本应用")
        if (!wechatRunning) add("没有发现正在运行的微信，先打开微信再下拉刷新")
        if (!moduleInLog) add("模块还没挂上微信，请在框架里启用本应用、勾选微信后重开微信")
        if (key.isBlank()) add("还没拿到解密口令，完全退出并重开一次微信后再试")
        if (dbSize <= 0) add("没有定位到会话数据文件")
    }
}

object Env {
    fun check(context: Context): EnvStatus {
        val root = Su.available()
        val pid = if (root) WeChatStore.wechatPid() else null
        val acct = if (root) WeChatStore.primaryAccount() else null
        val key = if (root) WeChatStore.readKey(context) else ""
        val logHit = if (root) {
            val latest = Su.run("ls -t /data/adb/lspd/log/modules* 2>/dev/null | head -1")
            if (latest.isNotBlank()) Su.run("grep wxplain '$latest' | tail -1") else ""
        } else ""
        return EnvStatus(
            root = root,
            wechatRunning = !pid.isNullOrBlank(),
            wechatPid = pid,
            moduleInLog = logHit.contains("wxplain"),
            key = key,
            dbHash = acct?.hash.orEmpty(),
            dbSize = acct?.dbSize ?: 0L,
        )
    }
}
