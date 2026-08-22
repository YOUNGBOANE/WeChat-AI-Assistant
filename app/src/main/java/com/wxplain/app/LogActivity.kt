package com.wxplain.app

import android.os.Bundle
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LogActivity : AppCompatActivity() {
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
    private lateinit var listRoot: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        listRoot = buildListScreen()
        setContentView(listRoot)
    }

    override fun onBackPressed() {
        if (this::listRoot.isInitialized && findViewById<View>(android.R.id.list) == null) {
            setContentView(listRoot)
            return
        }
        super.onBackPressed()
    }

    private fun buildListScreen(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val logs = UsageLogStore.load(this)
        val bar = toolbar(getString(R.string.menu_logs), backToFinish = true)
        if (logs.isNotEmpty()) {
            bar.menu.add(0, 1, 0, "清除全部").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            bar.setOnMenuItemClickListener {
                if (it.itemId == 1) {
                    confirmClear()
                    true
                } else false
            }
        }
        root.addView(bar)
        if (logs.isEmpty()) {
            root.addView(TextView(this).apply {
                text = "还没有调用记录"
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(48), dp(16), dp(16))
                textSize = 15f
                setTextColor(0xFF757575.toInt())
            })
        } else {
            val list = RecyclerView(this).apply { id = android.R.id.list }
            list.layoutManager = LinearLayoutManager(this)
            list.adapter = Adapter(logs)
            root.addView(list)
        }
        return root
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setMessage("清除全部使用日志？")
            .setPositiveButton("清除") { _, _ ->
                UsageLogStore.clear(this)
                listRoot = buildListScreen()
                setContentView(listRoot)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private inner class Adapter(val data: List<UsageLog>) :
        RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = data.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val col = LinearLayout(this@LogActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(12), dp(16), dp(12))
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            }
            fun tv(id: Int, size: Float, color: Int, top: Int = 0) = TextView(this@LogActivity).apply {
                this.id = id
                textSize = size
                setTextColor(color)
                if (top > 0) setPadding(0, dp(top), 0, 0)
            }
            col.addView(tv(android.R.id.text2, 12f, 0xFF9E9E9E.toInt()))
            col.addView(tv(android.R.id.text1, 14f, 0xFF212121.toInt(), 6))
            col.addView(tv(android.R.id.summary, 14f, 0xFF2E7D32.toInt(), 4))
            return object : RecyclerView.ViewHolder(col) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val log = data[position]
            val col = holder.itemView as LinearLayout
            val who = log.nick.ifBlank { "未知会话" }
            col.findViewById<TextView>(android.R.id.text2).text =
                "${fmt.format(Date(log.time))}  $who"
            col.findViewById<TextView>(android.R.id.text1).text =
                "发送：${oneLine(log.sent).ifBlank { "（空）" }.take(160)}"
            val replyView = col.findViewById<TextView>(android.R.id.summary)
            if (log.ok) {
                replyView.text = "回复：${oneLine(log.reply).take(120)}"
                replyView.setTextColor(0xFF2E7D32.toInt())
            } else {
                replyView.text = "失败：${oneLine(log.error).take(120)}"
                replyView.setTextColor(0xFFC62828.toInt())
            }
            col.setOnClickListener { showDetail(log) }
        }
    }

    private fun showDetail(log: UsageLog) {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(toolbar("调用详情", backToFinish = false))
        val scroll = ScrollView(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(24))
        }
        fun block(title: String, body: String) {
            col.addView(TextView(this).apply {
                text = title
                textSize = 13f
                setTextColor(0xFF1565C0.toInt())
                setPadding(0, dp(14), 0, dp(6))
            })
            col.addView(TextView(this).apply {
                text = body.ifBlank { "（空）" }
                textSize = 15f
                setTextColor(0xFF212121.toInt())
                setTextIsSelectable(true)
            })
        }
        col.addView(TextView(this).apply {
            text = "${fmt.format(Date(log.time))}\n会话：${log.nick.ifBlank { "未知" }}"
            textSize = 13f
            setTextColor(0xFF757575.toInt())
        })
        block("发送的内容", log.sent)
        block("回复的内容", log.reply)
        if (log.error.isNotBlank()) block("错误", log.error)
        scroll.addView(col)
        root.addView(scroll)
        setContentView(root)
    }

    private fun toolbar(title: String, backToFinish: Boolean): MaterialToolbar =
        MaterialToolbar(this).apply {
            this.title = title
            setBackgroundColor(0xFF1B5E20.toInt())
            setTitleTextColor(0xFFFFFFFF.toInt())
            setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
            setNavigationOnClickListener {
                if (backToFinish) finish() else setContentView(listRoot)
            }
        }

    private fun oneLine(s: String) = s.replace('\n', ' ').trim()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
