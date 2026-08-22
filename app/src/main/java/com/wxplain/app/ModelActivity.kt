package com.wxplain.app

import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.wxplain.app.ai.AiVendor
import com.wxplain.app.ai.ModelCatalog
import com.wxplain.app.ai.ModelConfig
import com.wxplain.app.ai.ModelStore

class ModelActivity : AppCompatActivity() {
    private lateinit var vendorSpinner: Spinner
    private lateinit var modelSpinner: Spinner
    private lateinit var apiInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val saved = ModelStore.load(this)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(MaterialToolbar(this).apply {
            title = getString(R.string.menu_models)
            setBackgroundColor(0xFF1B5E20.toInt())
            setTitleTextColor(0xFFFFFFFF.toInt())
            setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
            setNavigationOnClickListener { finish() }
        })

        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        form.addView(label("服务商"))
        vendorSpinner = Spinner(this)
        val vendorLabels = ModelCatalog.vendors.map { it.label }
        vendorSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, vendorLabels)
        vendorSpinner.setSelection(ModelCatalog.vendors.indexOfFirst { it.id == saved.vendorId }.coerceAtLeast(0))
        form.addView(vendorSpinner)

        form.addView(label("模型"))
        modelSpinner = Spinner(this)
        form.addView(modelSpinner)
        bindModels(currentVendor(), saved.modelId)

        vendorSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                bindModels(ModelCatalog.vendors[position], null)
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        form.addView(label("API"))
        apiInput = EditText(this).apply {
            hint = "API Key"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(saved.apiKey)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            textSize = 16f
        }
        form.addView(apiInput)

        root.addView(form)
        setContentView(root)
    }

    override fun onPause() {
        super.onPause()
        val vendor = currentVendor()
        val model = vendor.models.getOrElse(modelSpinner.selectedItemPosition) { vendor.models.first() }
        ModelStore.save(
            this,
            ModelConfig(
                vendorId = vendor.id,
                modelId = model.id,
                apiKey = apiInput.text?.toString().orEmpty().trim(),
            ),
        )
    }

    private fun currentVendor(): AiVendor {
        val i = vendorSpinner.selectedItemPosition.coerceIn(0, ModelCatalog.vendors.lastIndex)
        return ModelCatalog.vendors[i]
    }

    private fun bindModels(vendor: AiVendor, selectedId: String?) {
        val labels = vendor.models.map { it.label }
        modelSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        val idx = vendor.models.indexOfFirst { it.id == selectedId }
        modelSpinner.setSelection(if (idx >= 0) idx else 0)
    }

    private fun label(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(0xFF757575.toInt())
        setPadding(0, dp(12), 0, dp(6))
        gravity = Gravity.START
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
