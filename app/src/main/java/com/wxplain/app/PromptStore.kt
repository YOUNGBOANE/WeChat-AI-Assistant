package com.wxplain.app

import android.content.Context

object PromptStore {
    private const val PREF = "assistant_prompt"
    private const val KEY = "text"

    fun load(context: Context): String =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, "").orEmpty()

    fun save(context: Context, text: String) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, text)
            .apply()
    }
}
