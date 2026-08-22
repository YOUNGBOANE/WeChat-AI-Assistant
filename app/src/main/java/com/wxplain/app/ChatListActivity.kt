package com.wxplain.app

import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.textview.MaterialTextView
import com.wxplain.app.wechat.Conversation
import com.wxplain.app.wechat.LiveDb
import com.wxplain.app.wechat.WeChatStore
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class ChatListActivity : AppCompatActivity() {
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var emptyView: TextView
    private lateinit var refresh: SwipeRefreshLayout
    private lateinit var list: RecyclerView
    private val items = mutableListOf<Row>()
    private var remembered = emptySet<String>()

    private sealed class Row {
        data class Header(val title: String) : Row()
        data class Item(val conv: Conversation) : Row()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        root.addView(MaterialToolbar(this).apply {
            title = getString(R.string.menu_chats)
            setBackgroundColor(0xFF1B5E20.toInt())
            setTitleTextColor(0xFFFFFFFF.toInt())
            setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
            setNavigationOnClickListener { finish() }
        })

        refresh = SwipeRefreshLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            setOnRefreshListener { loadChats(copyDb = true) }
        }
        val frame = android.widget.FrameLayout(this)
        list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@ChatListActivity)
            adapter = Adapter()
            addItemDecoration(ContactDivider())
        }
        emptyView = MaterialTextView(this).apply {
            gravity = Gravity.CENTER
            text = "下拉刷新，读取当前会话"
            textSize = 15f
        }
        frame.addView(list)
        frame.addView(
            emptyView,
            android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        refresh.addView(frame)
        root.addView(refresh)
        setContentView(root)
        loadChats(copyDb = true)
    }

    override fun onResume() {
        super.onResume()
        remembered = MemoryStore.talkers(this)
        list.adapter?.notifyDataSetChanged()
    }

    private fun loadChats(copyDb: Boolean) {
        refresh.isRefreshing = true
        io.execute {
            val result = runCatching {
                if (copyDb) {
                    WeChatStore.refreshSnapshot(this).getOrThrow()
                    LiveDb.pruneDeletedChats(this)
                }
                LiveDb.conversations(this)
            }
            main.post {
                refresh.isRefreshing = false
                result.fold(
                    onSuccess = { convs ->
                        remembered = MemoryStore.talkers(this)
                        items.clear()
                        for (kind in Conversation.Kind.entries) {
                            val part = convs.filter { it.kind == kind }
                            if (part.isEmpty()) continue
                            val title = when (kind) {
                                Conversation.Kind.CONTACT -> "联系人 (${part.size})"
                                Conversation.Kind.GROUP -> "群聊 (${part.size})"
                                Conversation.Kind.OFFICIAL -> "公众号 (${part.size})"
                            }
                            items += Row.Header(title)
                            part.forEach { items += Row.Item(it) }
                        }
                        list.adapter?.notifyDataSetChanged()
                        emptyView.visibility = if (convs.isEmpty()) View.VISIBLE else View.GONE
                        if (convs.isEmpty()) emptyView.text = "没有会话"
                    },
                    onFailure = {
                        emptyView.visibility = View.VISIBLE
                        emptyView.text = it.message ?: "读取失败"
                        Toast.makeText(this, emptyView.text, Toast.LENGTH_LONG).show()
                    },
                )
            }
        }
    }

    private inner class ContactDivider : RecyclerView.ItemDecoration() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x1F000000
            strokeWidth = resources.displayMetrics.density
        }

        override fun onDrawOver(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
            val left = parent.paddingLeft + dp(16).toFloat()
            val right = (parent.width - parent.paddingRight).toFloat()
            for (i in 0 until parent.childCount) {
                val child = parent.getChildAt(i)
                val pos = parent.getChildAdapterPosition(child)
                if (pos == RecyclerView.NO_POSITION || pos >= items.lastIndex) continue
                if (items[pos] is Row.Item && items[pos + 1] is Row.Item) {
                    val y = child.bottom.toFloat()
                    c.drawLine(left, y, right, y, paint)
                }
            }
        }
    }

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemViewType(position: Int) = if (items[position] is Row.Header) 0 else 1
        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return if (viewType == 0) {
                val tv = MaterialTextView(
                    this@ChatListActivity,
                    null,
                    com.google.android.material.R.attr.textAppearanceLabelMedium,
                ).apply {
                    setPadding(dp(20), dp(14), dp(16), dp(6))
                }
                object : RecyclerView.ViewHolder(tv) {}
            } else {
                val row = LinearLayout(this@ChatListActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(16), dp(12), dp(16), dp(12))
                    layoutParams = RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                }
                row.addView(TextView(this@ChatListActivity).apply {
                    id = android.R.id.text1
                    textSize = 16f
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                row.addView(TextView(this@ChatListActivity).apply {
                    id = android.R.id.hint
                    text = getString(R.string.memory_mark)
                    textSize = 13f
                    setTextColor(0xFF1B5E20.toInt())
                    setPadding(dp(10), dp(4), dp(10), dp(4))
                })
                row.addView(TextView(this@ChatListActivity).apply {
                    id = android.R.id.text2
                    textSize = 12f
                    setTextColor(0xFF757575.toInt())
                })
                object : RecyclerView.ViewHolder(row) {}
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = items[position]) {
                is Row.Header -> (holder.itemView as TextView).text = row.title
                is Row.Item -> {
                    val box = holder.itemView as LinearLayout
                    box.findViewById<TextView>(android.R.id.text1).text =
                        if (row.conv.unread > 0) "${row.conv.nickname}  (${row.conv.unread})"
                        else row.conv.nickname
                    box.findViewById<TextView>(android.R.id.text2).text = formatTime(row.conv.lastTime)
                    val mark = box.findViewById<TextView>(android.R.id.hint)
                    val has = remembered.contains(row.conv.username)
                    mark.setTextColor(if (has) 0xFF1B5E20.toInt() else 0xFFBDBDBD.toInt())
                    mark.setOnClickListener {
                        startActivity(
                            Intent(this@ChatListActivity, MemoryActivity::class.java).apply {
                                putExtra(MemoryActivity.EXTRA_TALKER, row.conv.username)
                                putExtra(MemoryActivity.EXTRA_NICK, row.conv.nickname)
                            },
                        )
                    }
                    box.setOnClickListener {
                        startActivity(
                            Intent(this@ChatListActivity, ChatActivity::class.java).apply {
                                putExtra(ChatActivity.EXTRA_TALKER, row.conv.username)
                                putExtra(ChatActivity.EXTRA_NICK, row.conv.nickname)
                            },
                        )
                    }
                }
            }
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}

private fun formatTime(ts: Long): String {
    if (ts <= 0) return ""
    val now = Calendar.getInstance()
    val msg = Calendar.getInstance().apply { timeInMillis = ts }
    return when {
        now.get(Calendar.DAY_OF_YEAR) == msg.get(Calendar.DAY_OF_YEAR) &&
            now.get(Calendar.YEAR) == msg.get(Calendar.YEAR) ->
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))
        now.get(Calendar.YEAR) == msg.get(Calendar.YEAR) ->
            SimpleDateFormat("MM-dd", Locale.getDefault()).format(Date(ts))
        else -> SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(ts))
    }
}
