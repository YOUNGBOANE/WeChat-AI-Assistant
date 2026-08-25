package com.wxplain.app.xposed

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Bundle
import com.wxplain.app.ingest.LocalIpc
import com.wxplain.app.wechat.CipherKey
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File
import java.util.WeakHashMap

object KeyHook {
    private const val TAG = "[wxplain:key]"
    private val provider = Uri.parse("content://com.wxplain.app.key/key")
    private val dbClassNames = setOf(
        "com.tencent.wcdb.core.Database",
        "com.tencent.wcdb.database.SQLiteDatabase",
    )
    private val hookedClasses = java.util.Collections.newSetFromMap(WeakHashMap<Class<*>, Boolean>())
    @Volatile private var lastHex = ""
    @Volatile private var haveEnMicro = false
    @Volatile private var mainProcess = true
    @Volatile private var appContext: Context? = null
    private val lock = Any()
    private val paths = WeakHashMap<Any, String>()

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        mainProcess = lpparam.processName.isNullOrEmpty() || lpparam.processName == "com.tencent.mm"
        XposedBridge.log("$TAG install process=${lpparam.processName} main=$mainProcess")
        hookClassLoads()
        hookCipher(lpparam.classLoader)
        hookContextCapture()
    }

    private fun hookClassLoads() {
        try {
            XposedBridge.hookAllMethods(
                ClassLoader::class.java,
                "loadClass",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val name = param.args.getOrNull(0) as? String ?: return
                        if (name !in dbClassNames) return
                        val cls = param.result as? Class<*> ?: return
                        hookDbClass(cls)
                    }
                },
            )
        } catch (t: Throwable) {
            XposedBridge.log("$TAG loadClass hook skip: ${t.message}")
        }
    }

    private fun hookContextCapture() {
        val grab = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val ctx = when (val t = param.thisObject) {
                    is Application -> t
                    else -> param.args.getOrNull(0) as? Context
                } ?: return
                appContext = ctx.applicationContext ?: ctx
                hookCipher(ctx.classLoader)
            }
        }
        try {
            XposedBridge.hookAllMethods(Application::class.java, "attach", grab)
        } catch (t: Throwable) {
            XposedBridge.log("$TAG attach hook skip: ${t.message}")
        }
        try {
            XposedHelpers.findAndHookMethod(Application::class.java, "onCreate", grab)
        } catch (t: Throwable) {
            XposedBridge.log("$TAG onCreate hook skip: ${t.message}")
        }
    }

    private fun hookCipher(cl: ClassLoader) {
        for (name in dbClassNames) {
            val cls = try {
                XposedHelpers.findClass(name, cl)
            } catch (_: Throwable) {
                null
            } ?: continue
            hookDbClass(cls)
        }
    }

    private fun hookDbClass(dbClass: Class<*>) {
        synchronized(lock) {
            if (!hookedClasses.add(dbClass)) return
        }
        XposedBridge.log("$TAG hook class=${dbClass.name} loader=${dbClass.classLoader}")
        hookPathSources(dbClass)
        var n = 0
        for (m in dbClass.declaredMethods) {
            if (!isKeyMethod(m)) continue
            val name = m.name
            try {
                XposedBridge.hookMethod(
                    m,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            onCipherKey(param)
                        }
                    },
                )
                n++
            } catch (t: Throwable) {
                XposedBridge.log("$TAG hook ${dbClass.name}.$name skip: ${t.message}")
            }
        }
        XposedBridge.log("$TAG hooked ${dbClass.name} keyMethods=$n main=$mainProcess")
    }

    private fun hookPathSources(dbClass: Class<*>) {
        try {
            XposedBridge.hookAllConstructors(
                dbClass,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        rememberPath(param.thisObject, param.args)
                        if (param.args.any { it is ByteArray }) onCipherKey(param)
                    }
                },
            )
        } catch (t: Throwable) {
            XposedBridge.log("$TAG constructors skip: ${t.message}")
        }
        for (m in dbClass.declaredMethods) {
            if (m.parameterTypes.none { it == String::class.java }) continue
            val n = m.name
            if (n != "open" && n != "openDatabase" && n != "create" && n != "initialize") continue
            try {
                XposedBridge.hookMethod(
                    m,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            rememberPath(param.thisObject, param.args)
                        }
                    },
                )
            } catch (_: Throwable) {
            }
        }
    }

    private fun isKeyMethod(m: java.lang.reflect.Method): Boolean {
        val pts = m.parameterTypes
        if (pts.none { it == ByteArray::class.java }) return false
        val name = m.name
        return name.contains("open", ignoreCase = true) ||
            name.contains("cipher", ignoreCase = true) ||
            name.contains("Key")
    }

    private fun onCipherKey(param: XC_MethodHook.MethodHookParam) {
        val hex = param.args.firstNotNullOfOrNull { keyHex(it) } ?: return
        var path = pathOf(param.thisObject).ifBlank { pathFromArgs(param.args) }
        if (path.isEmpty()) {
            path = try {
                (XposedHelpers.callMethod(param.thisObject, "getPath") as? String).orEmpty()
            } catch (_: Throwable) {
                ""
            }
        }
        val enMicro = path.contains("EnMicroMsg")
        if (haveEnMicro && !enMicro) return
        if (!enMicro && !mainProcess) return
        if (hex == lastHex && (enMicro == haveEnMicro)) return
        persist(hex, path)
        lastHex = hex
        if (enMicro) haveEnMicro = true
    }

    private fun pathFromArgs(args: Array<Any?>): String {
        var fallback = ""
        for (arg in args) {
            val s = when (arg) {
                is String -> arg
                is File -> arg.path
                else -> continue
            }
            if (s.contains("EnMicroMsg")) return s
            if (fallback.isEmpty() && (s.endsWith(".db") || s.contains("MicroMsg"))) fallback = s
        }
        return fallback
    }

    private fun rememberPath(db: Any, args: Array<Any?>) {
        var picked = ""
        for (arg in args) {
            val s = when (arg) {
                is String -> arg
                is File -> arg.path
                else -> continue
            }
            if (s.contains("EnMicroMsg")) {
                picked = s
                break
            }
            if (picked.isEmpty() && (s.endsWith(".db") || s.contains("MicroMsg"))) picked = s
        }
        if (picked.isNotEmpty()) synchronized(lock) { paths[db] = picked }
    }

    private fun pathOf(db: Any): String {
        synchronized(lock) { paths[db] }?.let { if (it.isNotBlank()) return it }
        var c: Class<*>? = db.javaClass
        var depth = 0
        while (c != null && depth++ < 6) {
            for (f in c.declaredFields) {
                if (f.type != String::class.java && f.type != File::class.java) continue
                f.isAccessible = true
                val v = try {
                    f.get(db)
                } catch (_: Throwable) {
                    null
                } ?: continue
                val s = when (v) {
                    is String -> v
                    is File -> v.path
                    else -> continue
                }
                if (s.contains("EnMicroMsg") || s.endsWith(".db")) {
                    synchronized(lock) { paths[db] = s }
                    return s
                }
            }
            c = c.superclass
        }
        return ""
    }

    private fun keyHex(arg: Any?): String? = when (arg) {
        is ByteArray -> {
            if (arg.isEmpty() || arg.size > 64) null
            else arg.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }
        is String -> {
            if (arg.isEmpty() || arg.length > 64) null
            else arg.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }
        is CharArray -> keyHex(String(arg))
        else -> null
    }

    private fun persist(hex: String, path: String) {
        val ctx = context()
        val hash = CipherKey.hashFromDbPath(path)
        val body = buildString {
            appendLine("key=$hex")
            if (path.isNotBlank()) appendLine("path=$path")
            if (hash.isNotBlank()) appendLine("hash=$hash")
            appendLine("time=${System.currentTimeMillis()}")
        }
        if (ctx != null) {
            try {
                File(ctx.filesDir, ".wxplain_key").writeText(body)
                File(ctx.filesDir, ".wechat_key").writeText(
                    "key=$hex\npageSize=1024\nversion=version1\ntime=${System.currentTimeMillis()}\n",
                )
                appendHistory(ctx, hex)
            } catch (t: Throwable) {
                XposedBridge.log("$TAG write file failed: ${t.message}")
            }
            val values = ContentValues().apply {
                put("key", hex)
                if (hash.isNotBlank()) put("hash", hash)
                if (path.isNotBlank()) put("path", path)
            }
            val inserted = try {
                ctx.contentResolver.insert(provider, values)
            } catch (t: Throwable) {
                XposedBridge.log("$TAG provider failed: ${t.message}")
                null
            }
            if (inserted == null) {
                try {
                    val extras = Bundle().apply {
                        putString("key", hex)
                        if (hash.isNotBlank()) putString("hash", hash)
                        if (path.isNotBlank()) putString("path", path)
                    }
                    LocalIpc.call("key_save", extras, readTimeoutMs = 5_000)
                } catch (t: Throwable) {
                    XposedBridge.log("$TAG ipc key_save failed: ${t.message}")
                }
            }
        } else {
            XposedBridge.log("$TAG persist without context len=${hex.length}")
        }
        XposedBridge.log("$TAG captured len=${hex.length} enmicro=${path.contains("EnMicroMsg")} hash=$hash path=$path")
    }

    private fun appendHistory(ctx: Context, hex: String) {
        val file = File(ctx.filesDir, ".wxplain_keys")
        val prev = if (file.exists()) {
            file.readLines().map { it.trim() }.filter { it.isNotEmpty() }
        } else {
            emptyList()
        }
        if (prev.lastOrNull() == hex) return
        file.writeText((prev + hex).takeLast(16).joinToString("\n", postfix = "\n"))
    }

    private fun context(): Context? {
        appContext?.let { return it }
        return try {
            val at = Class.forName("android.app.ActivityThread")
            val app = at.getMethod("currentApplication").invoke(null) as? Context
            app?.also { appContext = it.applicationContext ?: it }
        } catch (_: Throwable) {
            null
        }
    }
}
