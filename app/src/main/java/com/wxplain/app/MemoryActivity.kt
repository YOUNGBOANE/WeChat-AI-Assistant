package com.wxplain.app

import android.os.Bundle
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar

class MemoryActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_TALKER = "talker"
        const val EXTRA_NICK = "nick"
    }

    private lateinit var input: EditText
    private var talker: String = ""
    private var nick: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        talker = intent.getStringExtra(EXTRA_TALKER).orEmpty().trim()
        nick = intent.getStringExtra(EXTRA_NICK).orEmpty().ifBlank { talker }
        if (talker.isEmpty()) {
            finish()
            return
        }

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(MaterialToolbar(this).apply {
            title = getString(R.string.menu_memory)
            subtitle = nick
            setBackgroundColor(0xFF1B5E20.toInt())
            setTitleTextColor(0xFFFFFFFF.toInt())
            setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
            setNavigationOnClickListener { finish() }
        })
        root.addView(TextView(this).apply {
            text = getString(R.string.memory_hint)
            setPadding(dp(16), dp(12), dp(16), dp(4))
            textSize = 13f
            setTextColor(0xFF757575.toInt())
        })
        input = EditText(this).apply {
            hint = getString(R.string.memory_input_hint)
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            setPadding(dp(16), dp(12), dp(16), dp(16))
            textSize = 16f
            background = null
            setText(MemoryStore.ensureInitial(this@MemoryActivity, talker, nick))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
        }
        root.addView(input)
        setContentView(root)
    }

    override fun onPause() {
        super.onPause()
        if (talker.isBlank()) return
        MemoryStore.put(this, talker, nick, input.text?.toString().orEmpty())
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
