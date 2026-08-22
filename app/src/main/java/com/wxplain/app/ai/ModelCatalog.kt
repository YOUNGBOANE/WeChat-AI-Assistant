package com.wxplain.app.ai

data class AiModel(
    val id: String,
    val label: String,
)

data class AiVendor(
    val id: String,
    val label: String,
    val baseUrl: String,
    val models: List<AiModel>,
)

object ModelCatalog {
    val vendors: List<AiVendor> = listOf(
        AiVendor(
            id = "deepseek",
            label = "DeepSeek",
            // OpenAI 兼容：POST {baseUrl}/chat/completions，Authorization: Bearer <api_key>
            baseUrl = "https://api.deepseek.com",
            models = listOf(
                AiModel(id = "deepseek-v4-flash", label = "V4-Flash"),
            ),
        ),
        AiVendor(
            id = "bytedance",
            label = "字节",
            // 火山方舟 OpenAI 兼容：POST {baseUrl}/chat/completions，Authorization: Bearer <api_key>
            baseUrl = "https://ark.cn-beijing.volces.com/api/v3",
            models = listOf(
                AiModel(id = "doubao-seed-2-0-lite-260428", label = "Seed-2.0-Lite"),
            ),
        ),
    )

    fun vendor(id: String): AiVendor =
        vendors.firstOrNull { it.id == id } ?: vendors.first()

    fun model(vendor: AiVendor, id: String): AiModel =
        vendor.models.firstOrNull { it.id == id } ?: vendor.models.first()
}
