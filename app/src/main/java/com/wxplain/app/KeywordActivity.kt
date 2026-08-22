package com.wxplain.app

import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.card.MaterialCardView
import java.util.UUID

class KeywordActivity : AppCompatActivity() {
    private val rules = mutableListOf<KeywordRule>()
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        rules.addAll(KeywordStore.load(this))
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val bar = MaterialToolbar(this).apply {
            title = getString(R.string.menu_keywords)
            setBackgroundColor(0xFF1B5E20.toInt())
            setTitleTextColor(0xFFFFFFFF.toInt())
            setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
            setNavigationOnClickListener { finish() }
        }
        bar.menu.add(0, 1, 0, "添加").setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
        bar.setOnMenuItemClickListener {
            if (it.itemId == 1) {
                edit(null)
                true
            } else false
        }
        root.addView(bar)
        val hint = TextView(this).apply {
            text = getString(R.string.keywords_page_hint)
            setPadding(dp(16), dp(12), dp(16), dp(8))
            textSize = 13f
            setTextColor(0xFF757575.toInt())
        }
        root.addView(hint)
        val scroll = ScrollView(this)
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(24))
        }
        scroll.addView(list)
        root.addView(scroll)
        setContentView(root)
        render()
    }

    private fun render() {
        list.removeAllViews()
        if (rules.isEmpty()) {
            list.addView(TextView(this).apply {
                text = getString(R.string.keywords_empty)
                gravity = Gravity.CENTER
                setPadding(0, dp(32), 0, dp(16))
                setTextColor(0xFF9E9E9E.toInt())
            })
            return
        }
        for (r in rules) {
            list.addView(card(r))
        }
    }

    private fun card(rule: KeywordRule): View {
        val card = MaterialCardView(this).apply {
            radius = dp(12).toFloat()
            cardElevation = 2f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(12) }
            isClickable = true
            isFocusable = true
            setOnClickListener { edit(rule) }
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        col.addView(TextView(this).apply {
            text = KeywordStore.tokens(rule.keyword).joinToString("、")
                .ifBlank { rule.keyword.ifBlank { "（未填关键词）" } }
            textSize = 16f
            setTextColor(0xFF212121.toInt())
        })
        col.addView(TextView(this).apply {
            text = rule.data.replace('\n', ' ').trim().ifBlank { "（未填说明）" }
            textSize = 13f
            setTextColor(0xFF757575.toInt())
            setPadding(0, dp(6), 0, 0)
        })
        card.addView(col)
        return card
    }

    private fun edit(existing: KeywordRule?) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        val kw = EditText(this).apply {
            hint = getString(R.string.keywords_key_hint)
            setText(existing?.keyword.orEmpty())
            minLines = 2
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        val data = EditText(this).apply {
            hint = getString(R.string.keywords_data_hint)
            setText(existing?.data.orEmpty())
            minLines = 4
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        box.addView(kw)
        box.addView(data)
        val builder = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "添加资料" else "编辑资料")
            .setView(box)
            .setPositiveButton("保存") { _, _ ->
                val k = kw.text?.toString().orEmpty().trim()
                val d = data.text?.toString().orEmpty()
                if (KeywordStore.tokens(k).isEmpty()) return@setPositiveButton
                if (existing == null) {
                    rules += KeywordRule(UUID.randomUUID().toString(), k, d)
                } else {
                    val i = rules.indexOfFirst { it.id == existing.id }
                    if (i >= 0) rules[i] = existing.copy(keyword = k, data = d)
                }
                persist()
            }
            .setNegativeButton("取消", null)
        if (existing != null) {
            builder.setNeutralButton("删除") { _, _ ->
                rules.removeAll { it.id == existing.id }
                persist()
            }
        }
        builder.show()
    }

    private fun persist() {
        KeywordStore.save(this, rules.toList())
        render()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
