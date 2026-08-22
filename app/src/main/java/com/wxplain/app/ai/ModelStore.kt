package com.wxplain.app.ai

import android.content.Context

data class ModelConfig(
    val vendorId: String,
    val modelId: String,
    val apiKey: String,
) {
    val vendor: AiVendor get() = ModelCatalog.vendor(vendorId)
    val model: AiModel get() = ModelCatalog.model(vendor, modelId)
}

object ModelStore {
    private const val PREF = "assistant_model"
    private const val VENDOR = "vendor_id"
    private const val MODEL = "model_id"
    private const val KEY = "api_key"

    fun load(context: Context): ModelConfig {
        val p = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val vendor = ModelCatalog.vendor(p.getString(VENDOR, "").orEmpty())
        val model = ModelCatalog.model(vendor, p.getString(MODEL, "").orEmpty())
        return ModelConfig(
            vendorId = vendor.id,
            modelId = model.id,
            apiKey = p.getString(KEY, "").orEmpty(),
        )
    }

    fun save(context: Context, config: ModelConfig) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit()
            .putString(VENDOR, config.vendorId)
            .putString(MODEL, config.modelId)
            .putString(KEY, config.apiKey)
            .apply()
    }
}
