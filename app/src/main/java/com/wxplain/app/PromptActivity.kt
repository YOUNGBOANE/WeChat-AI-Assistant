package com.wxplain.app

import android.os.Bundle
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar

class PromptActivity : AppCompatActivity() {
    private lateinit var input: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(MaterialToolbar(this).apply {
            title = getString(R.string.menu_prompts)
            setBackgroundColor(0xFF1B5E20.toInt())
            setTitleTextColor(0xFFFFFFFF.toInt())
            setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
            setNavigationOnClickListener { finish() }
        })
        input = EditText(this).apply {
            hint = getString(R.string.prompt_hint)
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            setPadding(dp(16), dp(16), dp(16), dp(16))
            textSize = 16f
            background = null
            setText(PromptStore.load(this@PromptActivity))
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
        PromptStore.save(this, input.text?.toString().orEmpty())
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
