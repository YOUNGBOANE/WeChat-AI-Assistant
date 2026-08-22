package com.wxplain.app.xposed

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File

object KeyHook {
    private const val TAG = "[wxplain:key]"
    private val provider = Uri.parse("content://com.wxplain.app.key/key")
    @Volatile private var captured = false

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        XposedBridge.hookAllMethods(
            android.app.Application::class.java,
            "attach",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val ctx = param.args[0] as? Context ?: return
                    hookCipher(ctx)
                }
            },
        )
    }

    private fun hookCipher(ctx: Context) {
        val dbClass = try {
            ctx.classLoader.loadClass("com.tencent.wcdb.core.Database")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG Database class missing: ${t.message}")
            return
        }
        var n = 0
        for (m in dbClass.declaredMethods) {
            if (m.name != "setCipherKey") continue
            XposedBridge.hookMethod(m, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (captured) return
                    val bytes = param.args.getOrNull(0) as? ByteArray ?: return
                    if (bytes.isEmpty() || bytes.size > 64) return
                    val hex = bytes.joinToString("") { "%02x".format(it) }
                    persist(ctx, hex)
                    captured = true
                }
            })
            n++
        }
        XposedBridge.log("$TAG hooked setCipherKey overloads=$n")
    }

    private fun persist(ctx: Context, hex: String) {
        try {
            File(ctx.filesDir, ".wxplain_key").writeText("key=$hex\ntime=${System.currentTimeMillis()}\n")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG write file failed: ${t.message}")
        }
        try {
            val values = ContentValues().apply { put("key", hex) }
            ctx.contentResolver.insert(provider, values)
        } catch (t: Throwable) {
            XposedBridge.log("$TAG provider failed: ${t.message}")
        }
        XposedBridge.log("$TAG captured len=${hex.length}")
    }
}
