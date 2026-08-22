package com.wxplain.app

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.wxplain.app.wechat.ChatMessage
import com.wxplain.app.wechat.LiveDb
import com.wxplain.app.wechat.MsgTypes
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class ChatActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_TALKER = "talker"
        const val EXTRA_NICK = "nick"
    }

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    private lateinit var talker: String
    private lateinit var nick: String
    private lateinit var memoryBanner: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        talker = intent.getStringExtra(EXTRA_TALKER).orEmpty()
        nick = intent.getStringExtra(EXTRA_NICK) ?: talker
        if (talker.isNotBlank()) MemoryStore.ensureInitial(this, talker, nick)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val toolbar = MaterialToolbar(this).apply {
            title = nick
            setBackgroundColor(0xFF1B5E20.toInt())
            setTitleTextColor(0xFFFFFFFF.toInt())
            setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
            setNavigationOnClickListener { finish() }
        }
        toolbar.menu.add(0, 1, 0, getString(R.string.menu_memory))
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        toolbar.setOnMenuItemClickListener {
            if (it.itemId == 1) {
                openMemory()
                true
            } else false
        }
        root.addView(toolbar)
        memoryBanner = TextView(this).apply {
            setPadding(dp(16), dp(10), dp(16), dp(10))
            textSize = 13f
            setTextColor(0xFF1B5E20.toInt())
            setBackgroundColor(0xFFE8F5E9.toInt())
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            visibility = View.GONE
            isClickable = true
            isFocusable = true
            setOnClickListener { openMemory() }
        }
        root.addView(memoryBanner)
        bindMemory()
        val list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@ChatActivity).apply { stackFromEnd = true }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f,
            )
        }
        root.addView(list)
        val empty = TextView(this).apply {
            gravity = Gravity.CENTER
            text = "加载中…"
            setPadding(dp(16), dp(32), dp(16), dp(16))
        }
        root.addView(empty)
        setContentView(root)

        io.execute {
            val result = runCatching { LiveDb.messages(this, talker).asReversed() }
            main.post {
                result.fold(
                    onSuccess = { msgs ->
                        empty.visibility = if (msgs.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
                        if (msgs.isEmpty()) empty.text = "没有消息"
                        else {
                            toolbar.subtitle = "共 ${msgs.size} 条"
                            list.adapter = MsgAdapter(msgs)
                        }
                    },
                    onFailure = { empty.text = it.message ?: "读取失败" },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (this::memoryBanner.isInitialized) bindMemory()
    }

    private fun openMemory() {
        startActivity(
            Intent(this, MemoryActivity::class.java).apply {
                putExtra(MemoryActivity.EXTRA_TALKER, talker)
                putExtra(MemoryActivity.EXTRA_NICK, nick)
            },
        )
    }

    private fun bindMemory() {
        val text = MemoryStore.text(this, talker)
        if (text.isBlank()) {
            memoryBanner.visibility = View.GONE
        } else {
            memoryBanner.visibility = View.VISIBLE
            memoryBanner.text = "记忆：$text"
        }
    }

    private inner class MsgAdapter(val data: List<ChatMessage>) :
        RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = data.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val col = LinearLayout(this@ChatActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(6), dp(12), dp(6))
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            }
            col.addView(TextView(this@ChatActivity).apply {
                id = android.R.id.text2
                textSize = 11f
                setTextColor(0xFF9E9E9E.toInt())
            })
            col.addView(TextView(this@ChatActivity).apply {
                id = android.R.id.text1
                textSize = 15f
                setPadding(dp(10), dp(8), dp(10), dp(8))
            })
            return object : RecyclerView.ViewHolder(col) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val m = data[position]
            val col = holder.itemView as LinearLayout
            col.gravity = if (m.isSend) Gravity.END else Gravity.START
            val meta = col.findViewById<TextView>(android.R.id.text2)
            val body = col.findViewById<TextView>(android.R.id.text1)
            val who = if (m.isSend) "我" else "对方"
            meta.text = "$who  ${fmt.format(Date(m.createTime))}"
            val text = preview(m)
            body.text = text
            body.setBackgroundColor(if (m.isSend) 0xFFC8E6C9.toInt() else 0xFFF5F5F5.toInt())
        }
    }

    private fun preview(m: ChatMessage): String {
        if (m.type == MsgTypes.TEXT) return m.content.ifBlank { "[空]" }
        val clip = m.content.replace('\n', ' ').take(160)
        return "[${MsgTypes.label(m.type)}] $clip"
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
