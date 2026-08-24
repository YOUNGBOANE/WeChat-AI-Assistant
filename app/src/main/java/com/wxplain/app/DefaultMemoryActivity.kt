package com.wxplain.app

import android.os.Bundle
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar

class DefaultMemoryActivity : AppCompatActivity() {
    private lateinit var input: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(MaterialToolbar(this).apply {
            title = getString(R.string.menu_default_memory)
            setBackgroundColor(0xFF1B5E20.toInt())
            setTitleTextColor(0xFFFFFFFF.toInt())
            setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
            setNavigationOnClickListener { finish() }
        })
        root.addView(TextView(this).apply {
            text = getString(R.string.default_memory_hint)
            setPadding(dp(16), dp(12), dp(16), dp(4))
            textSize = 13f
            setTextColor(0xFF757575.toInt())
        })
        input = EditText(this).apply {
            hint = getString(R.string.default_memory_input_hint)
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            setPadding(dp(16), dp(12), dp(16), dp(16))
            textSize = 16f
            background = null
            setText(MemoryStore.defaultText(this@DefaultMemoryActivity))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
        }
        root.addView(input)
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        if (!::input.isInitialized) return
        val stored = MemoryStore.defaultText(this)
        if (input.text?.toString() != stored) input.setText(stored)
    }

    override fun onPause() {
        super.onPause()
        MemoryStore.saveDefault(this, input.text?.toString().orEmpty())
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
