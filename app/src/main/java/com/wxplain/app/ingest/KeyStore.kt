package com.wxplain.app.ingest

import android.content.Context
import android.content.SharedPreferences
import com.wxplain.app.wechat.CipherKey

object KeyStore {
    private const val PREF = "wxplain_key"
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    }

    fun save(context: Context, hex: String, password: String = CipherKey.hexToPassword(hex)) {
        val p = prefs ?: context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        p.edit()
            .putString("hex", hex)
            .putString("password", password)
            .putLong("time", System.currentTimeMillis())
            .apply()
    }

    fun password(context: Context): String? {
        val p = prefs ?: context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        return p.getString("password", null)?.takeIf { it.isNotBlank() }
    }

    fun hex(context: Context): String? {
        val p = prefs ?: context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        return p.getString("hex", null)
    }
}
