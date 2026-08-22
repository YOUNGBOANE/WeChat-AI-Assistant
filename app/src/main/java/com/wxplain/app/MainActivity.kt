package com.wxplain.app

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.card.MaterialCardView
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var statusChip: LinearLayout
    private lateinit var statusLabel: TextView
    private lateinit var statusDot: View
    private var lastStatus: EnvStatus? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val toolbar = MaterialToolbar(this).apply {
            title = getString(R.string.app_name)
            setBackgroundColor(0xFF1B5E20.toInt())
            setTitleTextColor(0xFFFFFFFF.toInt())
        }
        statusChip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(6), dp(12), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(0x33FFFFFF)
            }
            isClickable = true
            isFocusable = true
            visibility = View.GONE
        }
        statusDot = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).apply { marginEnd = dp(8) }
            background = dot(0xFFFF5252.toInt())
        }
        statusLabel = TextView(this).apply {
            text = "检测不通过"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 13f
        }
        statusChip.addView(statusDot)
        statusChip.addView(statusLabel)
        statusChip.setOnClickListener { showStatusDetail() }
        toolbar.menu.add(0, 1, 0, "status").apply {
            actionView = statusChip
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        root.addView(toolbar)

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        val scroll = android.widget.ScrollView(this)
        body.addView(menuCard(getString(R.string.menu_chats), getString(R.string.menu_chats_hint)) {
            startActivity(Intent(this, ChatListActivity::class.java))
        })
        body.addView(menuCard(getString(R.string.menu_prompts), getString(R.string.menu_prompts_hint)) {
            startActivity(Intent(this, PromptActivity::class.java))
        })
        body.addView(menuCard(getString(R.string.menu_default_memory), getString(R.string.menu_default_memory_hint)) {
            startActivity(Intent(this, DefaultMemoryActivity::class.java))
        })
        body.addView(menuCard(getString(R.string.menu_keywords), getString(R.string.menu_keywords_hint)) {
            startActivity(Intent(this, KeywordActivity::class.java))
        })
        body.addView(menuCard(getString(R.string.menu_models), getString(R.string.menu_models_hint)) {
            startActivity(Intent(this, ModelActivity::class.java))
        })
        body.addView(menuCard(getString(R.string.menu_logs), getString(R.string.menu_logs_hint)) {
            startActivity(Intent(this, LogActivity::class.java))
        })
        scroll.addView(body)
        root.addView(scroll)
        setContentView(root)
        loadStatus()
    }

    override fun onResume() {
        super.onResume()
        loadStatus()
    }

    private fun menuCard(title: String, hint: String, onClick: () -> Unit): View {
        val card = MaterialCardView(this).apply {
            radius = dp(12).toFloat()
            cardElevation = 2f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(12) }
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(18), dp(16), dp(18))
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        col.addView(TextView(this).apply {
            text = title
            textSize = 18f
            setTextColor(0xFF212121.toInt())
        })
        col.addView(TextView(this).apply {
            text = hint
            textSize = 13f
            setTextColor(0xFF757575.toInt())
            setPadding(0, dp(4), 0, 0)
        })
        row.addView(col)
        row.addView(TextView(this).apply {
            text = ">"
            textSize = 20f
            setTextColor(0xFF9E9E9E.toInt())
        })
        card.addView(row)
        return card
    }

    private fun applyStatus(status: EnvStatus) {
        lastStatus = status
        statusChip.visibility = if (status.ok) View.GONE else View.VISIBLE
        if (!status.ok) {
            statusLabel.text = "检测不通过"
            statusDot.background = dot(0xFFFF5252.toInt())
        }
    }

    private fun showStatusDetail() {
        val status = lastStatus ?: return
        if (status.ok) return
        AlertDialog.Builder(this)
            .setMessage(status.failures().joinToString("\n\n"))
            .setPositiveButton("重试") { _, _ -> loadStatus() }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun loadStatus() {
        io.execute {
            val status = runCatching { Env.check(this) }.getOrElse {
                EnvStatus(false, false, null, false, "", "", 0)
            }
            main.post { applyStatus(status) }
        }
    }

    private fun dot(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
